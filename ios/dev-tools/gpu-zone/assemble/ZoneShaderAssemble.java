/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that
 * the conditions of the BSD 2-clause license are met.
 */

// Standalone, dependency-free host-JVM assembler for the RLHD zone shaders. Replicates the resolution algorithm of
// RLHD's rs117.hd.opengl.shader.ShaderIncludes.parse() (vendored under ../rlhd-src/java for reference) WITHOUT its
// lwjgl/ResourcePath/UniformBuffer dependencies: the UBO std140 blocks are hand-written in uniforms/*.glsl (plain
// #include), and the #define identifiers (ZONE_RENDERER, UI_SCALING_MODE, MATERIAL_GETTER, ...) arrive as
// `#include IDENTIFIER` directives resolved from the same fixed define map HdPlugin.getShaderIncludes() builds.
//
// Produces ../assembled/<name>.<stage>.assembled.glsl (flat GLSL, all #include/#define resolved, still desktop
// #version 330) — the input the iOS ZoneDownport then rewrites to ES 3.00 and the Z0 probe compiles on EAGL.
//
// Config = a MINIMAL flat-scene set (ZONE_RENDERER on; shadows/tiled/dynamic-lights/displacement OFF) — the B0
// bring-up target. Grow the config (and re-run) as later B-phases turn features on. Unknown `#include IDENTIFIER`
// or missing files are reported (not silently stubbed) so the define map's gaps are explicit.
//
// Build+run:  javac ZoneShaderAssemble.java && java ZoneShaderAssemble

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class ZoneShaderAssemble {
	static final Path SHADER_ROOT = Paths.get("..", "rlhd-src", "shaders", "hd").toAbsolutePath().normalize();
	static final Path OUT_DIR = Paths.get("..", "assembled").toAbsolutePath().normalize();

	// GL shader-type constants (for the SHADER_TYPE define block, mirroring ShaderTemplate).
	static final int GL_VERTEX_SHADER = 35633, GL_FRAGMENT_SHADER = 35632, GL_GEOMETRY_SHADER = 36313, GL_COMPUTE_SHADER = 37305;

	// The zone-path program entry shaders to assemble: name -> (file, glStage). geometry/compute are NOT ported.
	static final String[][] TARGETS = {
		{ "scene",  "scene_vert.glsl",  "V" },
		{ "scene",  "scene_frag.glsl",  "F" },
		{ "shadow", "shadow_vert.glsl", "V" },
		{ "shadow", "shadow_frag.glsl", "F" },
		{ "ui",     "ui_vert.glsl",     "V" },
		{ "ui",     "ui_frag.glsl",     "F" },
	};

	final Map<String, String> includeMap = new HashMap<>();   // identifier -> replacement text
	final List<String> missing = new ArrayList<>();           // unresolved identifier includes (reported)
	final Deque<Path> fileStack = new ArrayDeque<>();
	final Set<String> pragmaOnce = new HashSet<>();

	// --- the fixed define map (HdPlugin.getShaderIncludes @ 7dded59, minimal flat-scene config) ---
	void define(String id, String val) { includeMap.put(id, "#define " + id + " " + val); }
	void define(String id, boolean b)  { define(id, b ? "1" : "0"); }
	void define(String id, int v)      { define(id, Integer.toString(v)); }

	void buildConfig() {
		define("UI_SCALING_MODE", 0);
		define("COLOR_BLINDNESS", 0);
		define("APPLY_COLOR_FILTER", false);
		define("MATERIAL_COUNT", 1);
		define("WATER_TYPE_COUNT", 1);
		define("DYNAMIC_LIGHTS", false);
		define("TILED_LIGHTING", false);
		define("TILED_LIGHTING_LAYER_COUNT", 0);
		define("TILED_LIGHTING_TILE_SIZE", 16);
		define("MAX_LIGHT_COUNT", 1);
		define("NORMAL_MAPPING", false);
		define("PARALLAX_OCCLUSION_MAPPING", false);
		define("SHADOW_MODE", 0);
		define("SHADOW_TRANSPARENCY", false);
		define("SHADOW_FILTERING", 0);
		define("SHADOW_RESOLUTION", 2048);
		define("VANILLA_COLOR_BANDING", false);
		define("UNDO_VANILLA_SHADING", false);
		define("LEGACY_GREY_COLORS", false);
		define("DISABLE_DIRECTIONAL_SHADING", false);
		define("FLAT_SHADING", false);
		define("WIND_DISPLACEMENT", false);
		define("WIND_DISPLACEMENT_NOISE_RESOLUTION", 128);
		define("CHARACTER_DISPLACEMENT", false);
		define("MAX_CHARACTER_POSITION_COUNT", 1);
		define("WIREFRAME", false);
		define("WINDOWS_HDR_CORRECTION", false);
		define("LEGACY_RENDERER", false);
		define("ZONE_RENDERER", true);
		// ZoneRenderer.addShaderIncludes() overrides these two for the zone path (HdPlugin's base values are 0/"",
		// which size WorldViewArray[0] = illegal). We don't vendor ZoneRenderer, so replicate its override: a real
		// world-view count (>=1) + the array getter. (Compile gate needs >=1; bump to the renderer's true max for
		// correctness when ZoneRenderer is staged in B0.1.)
		define("MAX_SIMULTANEOUS_WORLD_VIEWS", 1);
		includeMap.put("WORLD_VIEW_GETTER", "#define getWorldView(i) WorldViewArray[i]");
		// VERSION_HEADER: compute-only in the zone path (comp*.glsl), but define it harmlessly in case.
		includeMap.put("VERSION_HEADER", "#version 330");
		// Dynamic getters: the simple (non-Intel-workaround) form RLHD emits when dynamic indexing works.
		includeMap.put("MATERIAL_GETTER", "#define getMaterial(i) MaterialArray[i]");
		includeMap.put("WATER_TYPE_GETTER", "#define getWaterType(i) WaterTypeArray[i]");
		// MATERIAL_CONSTANTS: per-material `#define MAT_<NAME> getMaterial(idx)`. Empty in the minimal gate; filled
		// from compile errors if scene_frag references named MAT_ constants in always-compiled code.
		includeMap.put("MATERIAL_CONSTANTS", "");
		// SHADER_TYPE block (mirrors ShaderTemplate.SHADER_TYPE_DEFINE); the per-target SHADER_TYPE value is set in run().
	}

	String shaderTypeBlock(int type) {
		return "#define VERTEX_SHADER " + GL_VERTEX_SHADER + "\n"
			+ "#define FRAGMENT_SHADER " + GL_FRAGMENT_SHADER + "\n"
			+ "#define GEOMETRY_SHADER " + GL_GEOMETRY_SHADER + "\n"
			+ "#define COMPUTE_SHADER " + GL_COMPUTE_SHADER + "\n"
			+ "#define SHADER_TYPE " + type;
	}

	static final Pattern IDENT = Pattern.compile("^[A-Za-z_]\\w*");

	String loadFile(Path file) throws IOException {
		Path norm = file.toAbsolutePath().normalize();
		fileStack.push(norm);
		String out = parse(Files.readString(norm), norm.getParent());
		fileStack.pop();
		return out;
	}

	String parse(String source, Path curDir) throws IOException {
		StringBuilder sb = new StringBuilder();
		for (String line : source.split("\r?\n")) {
			String t = line.stripLeading();
			if (t.startsWith("#include ")) {
				String expr = t.substring(9).stripLeading();
				if (expr.isEmpty()) { sb.append("// empty include\n"); continue; }
				char c = expr.charAt(0);
				if (c == '"' || c == '<') {
					char end = (c == '<') ? '>' : '"';
					int e = expr.indexOf(end, 1);
					String inc = expr.substring(1, e);
					// `<...>` resolves from the shader root; `"..."` relative to the current file's dir.
					Path target = (c == '<') ? SHADER_ROOT.resolve(inc) : curDir.resolve(inc);
					target = target.normalize();
					if (!Files.exists(target)) { missing.add("FILE " + inc + " (from " + fileStack.peek() + ")"); sb.append("// MISSING FILE: ").append(inc).append('\n'); continue; }
					sb.append(loadFile(target)).append('\n');
				} else {
					Matcher m = IDENT.matcher(expr);
					if (m.find()) {
						String id = m.group();
						String rep = includeMap.get(id);
						if (rep == null) { missing.add("IDENT " + id + " (from " + fileStack.peek() + ")"); sb.append("// MISSING IDENT: ").append(id).append('\n'); }
						else sb.append(rep).append('\n');
					} else sb.append("// unparsed include: ").append(expr).append('\n');
				}
			} else if (t.startsWith("#pragma once")) {
				String f = fileStack.peek().toString();
				if (!pragmaOnce.add(f)) { sb.append("// #pragma once: already included\n"); break; }
				sb.append("// #pragma once: first include\n");
			} else {
				sb.append(line).append('\n');
			}
		}
		return sb.toString();
	}

	void run() throws IOException {
		buildConfig();
		Files.createDirectories(OUT_DIR);
		// clear stale assembled outputs (keep .gitkeep)
		try (var s = Files.list(OUT_DIR)) {
			for (Path p : (Iterable<Path>) s::iterator)
				if (p.getFileName().toString().endsWith(".assembled.glsl")) Files.delete(p);
		}

		int ok = 0;
		for (String[] tgt : TARGETS) {
			String name = tgt[0], file = tgt[1]; boolean vert = tgt[2].equals("V");
			pragmaOnce.clear(); fileStack.clear();
			missing.clear();
			includeMap.put("SHADER_TYPE", shaderTypeBlock(vert ? GL_VERTEX_SHADER : GL_FRAGMENT_SHADER));
			String assembled = loadFile(SHADER_ROOT.resolve(file));
			String stage = vert ? "vert" : "frag";
			Path outPath = OUT_DIR.resolve(name + "." + stage + ".assembled.glsl");
			Files.writeString(outPath, assembled);
			int lines = assembled.split("\n").length;
			if (missing.isEmpty()) { ok++; System.out.printf("OK   %-22s -> %s (%d lines)%n", file, outPath.getFileName(), lines); }
			else {
				System.out.printf("WARN %-22s -> %s (%d lines) — %d unresolved:%n", file, outPath.getFileName(), lines, missing.size());
				for (String mm : new LinkedHashSet<>(missing)) System.out.println("       - " + mm);
			}
		}
		System.out.printf("%nASSEMBLE SUMMARY: %d/%d clean%n", ok, TARGETS.length);
		System.out.println(ok == TARGETS.length ? "VERDICT = ALL_TARGETS_ASSEMBLE_CLEAN" : "VERDICT = ASSEMBLE_HAS_UNRESOLVED");
	}

	public static void main(String[] args) throws IOException { new ZoneShaderAssemble().run(); }
}
