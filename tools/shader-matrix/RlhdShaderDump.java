import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import rs117.hd.HdPlugin;
import rs117.hd.HdPluginConfig;
import rs117.hd.opengl.shader.ShaderIncludes;

// Assembles every RLHD program's per-stage GLSL exactly as ShaderTemplate.compile does at runtime (the real
// HdPlugin.getShaderIncludes() + per-program defines), with no GL. Config = HdPluginConfig defaults, overridden by
// key=value args (method name = enum constant name / true / false / int). Writes <out>/<program>.<stage>.glsl.
public class RlhdShaderDump {
	static final int VS = 35633, FS = 35632;
	static final String SHADER_TYPE_DEFINE =
		"#define VERTEX_SHADER 35633\n#define FRAGMENT_SHADER 35632\n#define GEOMETRY_SHADER 36313\n" +
		"#define COMPUTE_SHADER 37305\n#define SHADER_TYPE ";

	static sun.misc.Unsafe U;
	static Object alloc(Class<?> c) throws Exception { return U.allocateInstance(c); }
	static Field field(Class<?> c, String n) throws Exception {
		for (Class<?> k = c; k != null; k = k.getSuperclass())
			try { Field f = k.getDeclaredField(n); f.setAccessible(true); return f; } catch (NoSuchFieldException e) {}
		throw new NoSuchFieldException(c + "." + n);
	}
	static void set(Object o, String n, Object v) throws Exception { field(o.getClass(), n).set(o, v); }
	static void setStatic(Class<?> c, String n, Object v) throws Exception {
		Field f = field(c, n);
		if (Modifier.isFinal(f.getModifiers())) {
			Object base = U.staticFieldBase(f); long off = U.staticFieldOffset(f); U.putObject(base, off, v);
		} else f.set(null, v);
	}

	public static void main(String[] args) throws Exception {
		Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
		U = (sun.misc.Unsafe) uf.get(null);
		Path out = Paths.get(args[0]); Files.createDirectories(out);
		Map<String, String> ov = new HashMap<>();
		for (int i = 1; i < args.length; i++) { String[] kv = args[i].split("=", 2); ov.put(kv[0], kv[1]); }

		HdPluginConfig config = (HdPluginConfig) Proxy.newProxyInstance(
			HdPluginConfig.class.getClassLoader(), new Class<?>[] { HdPluginConfig.class }, (p, m, a) -> {
				Class<?> rt = m.getReturnType();
				if (ov.containsKey(m.getName())) {
					String v = ov.get(m.getName());
					if (rt == boolean.class) return Boolean.parseBoolean(v);
					if (rt == int.class) return Integer.parseInt(v);
					if (rt.isEnum()) for (Object c : rt.getEnumConstants()) if (((Enum<?>) c).name().equals(v)) return c;
					throw new IllegalArgumentException("bad override " + m.getName() + "=" + v);
				}
				if (m.isDefault())
					return MethodHandles.privateLookupIn(HdPluginConfig.class, MethodHandles.lookup())
						.unreflectSpecial(m, HdPluginConfig.class).bindTo(p).invokeWithArguments(a == null ? new Object[0] : a);
				if (m.getName().equals("hashCode")) return 0;
				if (m.getName().equals("equals")) return p == a[0];
				if (m.getName().equals("toString")) return "cfg";
				throw new UnsupportedOperationException(m.toString());
			});

		if (ov.containsKey("@list")) {
			for (Method m : HdPluginConfig.class.getMethods()) {
				if (m.getParameterCount() != 0 || !m.isDefault()) continue;
				Class<?> rt = m.getReturnType();
				if (!(rt == boolean.class || rt == int.class || rt.isEnum())) continue;
				Object v = m.invoke(config);
				System.out.println(m.getName() + " = " + (v instanceof Enum ? ((Enum<?>) v).name() : v) + (rt.isEnum() ? "   " + Arrays.toString(Arrays.stream(rt.getEnumConstants()).map(c -> ((Enum<?>) c).name()).toArray()) : ""));
			}
			return;
		}
		HdPlugin plugin = (HdPlugin) alloc(HdPlugin.class);
		set(plugin, "config", config);
		Class<?> zrC = Class.forName("rs117.hd.renderer.zone.ZoneRenderer");
		Object zr = alloc(zrC);
		set(zr, "plugin", plugin);
		set(zr, "uboWorldViews", Class.forName("rs117.hd.opengl.uniforms.UBOWorldViews").getDeclaredConstructor().newInstance());
		set(plugin, "renderer", zr);
		set(plugin, "configColorFilter", Enum.valueOf((Class) Class.forName("rs117.hd.config.ColorFilter"), ov.getOrDefault("@colorFilter", "NONE")));
		Class<?> skyC = Class.forName("rs117.hd.scene.SkyManager");
		Object sky = alloc(skyC);
		set(sky, "config", config);
		set(sky, "plugin", plugin);
		set(sky, "configLatLon", new float[2]);
		set(plugin, "skyManager", sky);
		Method ucc = HdPlugin.class.getDeclaredMethod("updateCachedConfigs"); ucc.setAccessible(true);
		try { ucc.invoke(plugin); }
		catch (InvocationTargetException e) {
			// developerTools.updateConfig() is updateCachedConfigs' last statement; everything before it has run.
			if (!(e.getCause() instanceof NullPointerException) || !String.valueOf(e.getCause().getMessage()).contains("developerTools")) throw e;
		}

		// UBOs (constructors are GL-free; only initialize() touches GL)
		for (String[] u : new String[][] { { "uboGlobal", "UBOGlobal" }, { "uboSky", "UBOSky" }, { "uboLights", "UBOLights" },
			{ "uboLightsCulling", "UBOLights" }, { "uboUi", "UBOUI" } }) {
			Class<?> c = Class.forName("rs117.hd.opengl.uniforms." + u[1]);
			Object inst;
			try { inst = c.getDeclaredConstructor().newInstance(); }
			catch (NoSuchMethodException e) { inst = c.getDeclaredConstructor(boolean.class).newInstance(u[0].equals("uboLightsCulling")); }
			set(plugin, u[0], inst);
		}
		int materialCount = Integer.parseInt(ov.getOrDefault("@materials", "595"));
		Class<?> mmC = Class.forName("rs117.hd.scene.MaterialManager");
		Class<?> matC = Class.forName("rs117.hd.scene.materials.Material");
		setStatic(mmC, "MATERIALS", Array.newInstance(matC, materialCount));
		// MATERIAL_CONSTANTS: one `#define MAT_<NAME> getMaterial(i)` per MATERIAL_MAP entry the shaders reference
		@SuppressWarnings("unchecked") Map<String, Object> matMap = (Map<String, Object>) field(mmC, "MATERIAL_MAP").get(null);
		int mi = 0;
		for (String name : ov.getOrDefault("@mats", "").split(",")) {
			if (name.isEmpty()) continue;
			Object mat = alloc(matC);
			field(matC, "uboIndex").setInt(mat, mi++);
			matMap.put(name.toLowerCase(), mat);
		}
		Object mm = alloc(mmC);
		set(mm, "uboMaterials", alloc(Class.forName("rs117.hd.opengl.uniforms.UBOMaterials")));   // ctor uploads via GL
		set(plugin, "materialManager", mm);
		Class<?> wtC = Class.forName("rs117.hd.scene.WaterTypeManager");
		Object wtm = alloc(wtC);
		Class<?> uwtC = Class.forName("rs117.hd.opengl.uniforms.UBOWaterTypes");
		Object uwt = alloc(uwtC);   // ctor uploads via GL; getCount() = uboStructs.length
		set(uwt, "uboStructs", Array.newInstance(Class.forName("rs117.hd.opengl.uniforms.UBOWaterTypes$WaterTypeStruct"),
			Integer.parseInt(ov.getOrDefault("@waterTypes", "16"))));
		set(wtm, "uboWaterTypes", uwt);
		set(plugin, "waterTypeManager", wtm);

		ShaderIncludes base = plugin.getShaderIncludes();
		Class<?> shadowMode = Class.forName("rs117.hd.config.ShadowMode");
		Object FAST = Enum.valueOf((Class) shadowMode, "FAST"), DETAILED = Enum.valueOf((Class) shadowMode, "DETAILED");

		List<Object[]> progs = new ArrayList<>();   // name, includes, vert, frag
		progs.add(new Object[] { "scene", base.copy().define("WATER_PASS", false), "scene_vert.glsl", "scene_frag.glsl" });
		progs.add(new Object[] { "scene_water", base.copy().define("WATER_PASS", true), "scene_vert.glsl", "scene_frag.glsl" });
		progs.add(new Object[] { "gap_filler", base.copy().define("WATER_PASS", false), "scene_vert.glsl", "gap_filler_frag.glsl" });
		progs.add(new Object[] { "shadow_fast", base.copy().define("SHADOW_MODE", (Enum<?>) FAST), "shadow_vert.glsl", "shadow_frag.glsl" });
		progs.add(new Object[] { "shadow_detailed", base.copy().define("SHADOW_MODE", (Enum<?>) DETAILED), "shadow_vert.glsl", "shadow_frag.glsl" });
		progs.add(new Object[] { "terrain_shadow", base.copy().define("SHADOW_MODE", (Enum<?>) FAST).define("TERRAIN_ONLY_PASS", true)
			.define("SHADOW_TRANSPARENCY", false), "shadow_vert.glsl", "stub_frag.glsl" });
		progs.add(new Object[] { "ui", base.copy(), "ui_vert.glsl", "ui_frag.glsl" });
		// HdPlugin:1060 builds the tiled programs only when dynamic lights are on AND tiled lighting is enabled. Only the
		// layered ones: the TILED_IMAGE_STORE=true program also needs GL_CAPS.GL_ARB_shader_image_load_store, which
		// both ports' facades leave false. A facade that sets it needs a variant here.
		boolean tiled = field(HdPlugin.class, "configTiledLighting").getBoolean(plugin)
			&& !((Enum<?>) field(HdPlugin.class, "configDynamicLights").get(plugin)).name().equals("NONE");
		if (tiled) for (int layer : new int[] { 0, 1 })
			progs.add(new Object[] { "tiled_l" + layer, base.copy().define("TILED_IMAGE_STORE", false).define("TILED_LIGHTING_LAYER", layer),
				"tiled_lighting_vert.glsl", "tiled_lighting_frag.glsl" });
		progs.add(new Object[] { "sky", base.copy(), "sky_vert.glsl", "sky_frag.glsl" });
		progs.add(new Object[] { "star", base.copy(), "star_vert.glsl", "star_frag.glsl" });
		progs.add(new Object[] { "nebula_bake", base.copy(), "sky_nebula_bake_vert.glsl", "sky_nebula_bake_frag.glsl" });
		for (String f : tiled ? new String[] { "overlay_frag", "gamma_calibration_frag", "shadow_map_frag", "tiled_lighting_overlay_frag" }
			: new String[] { "overlay_frag", "gamma_calibration_frag", "shadow_map_frag" })
			progs.add(new Object[] { "ov_" + f.replace("_frag", ""), base.copy(), "overlays/overlay_vert.glsl", "overlays/" + f + ".glsl" });

		int n = 0;
		for (Object[] p : progs) {
			ShaderIncludes inc = (ShaderIncludes) p[1];
			for (int s = 0; s < 2; s++) {
				int type = s == 0 ? VS : FS;
				String src = inc.addInclude("SHADER_TYPE", SHADER_TYPE_DEFINE + type).loadFile((String) p[2 + s]);
				Files.writeString(out.resolve(p[0] + (s == 0 ? ".vert" : ".frag") + ".glsl"), src);
				n++;
			}
		}
		System.out.println("assembled " + n + " stages -> " + out);
	}
}
