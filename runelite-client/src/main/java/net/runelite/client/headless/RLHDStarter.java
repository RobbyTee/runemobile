/*
 * Copyright (c) 2026, Jordan Simpson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.headless;

import java.awt.Canvas;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Track G AG3-b — bring up the 117HD (RLHD) plugin (Zone renderer) on the device. Sibling of
 * {@link RouteCGpuStarter}, but RLHD is NOT a built-in plugin: it lives in the staged {@code client-<ver>-rlhd.jar}
 * on the classpath (package {@code rs117.hd.*}), OUTSIDE the {@code net.runelite.client.plugins} package
 * {@code PluginManager.loadCorePlugins()} scans — so it is never auto-discovered. This helper, armed by
 * {@code -Drunelite.routecrlhd=true} and run per {@code Hooks.draw} once there is a drawing canvas, loads it via
 * the public {@code PluginManager.loadPlugins(List<Class>)} path ({@link MobileUiBridge#loadAndStartPlugin}) and
 * starts it. {@code HdPlugin} declares {@code conflicts="GPU"}, so {@code startPlugin} disables the base gpu plugin
 * automatically (do NOT also arm {@link RouteCGpuStarter} in this mode). {@code HdPlugin.startUp} defers the GL work
 * onto the client thread and retries until {@code textureManager.vanillaTexturesAvailable()} and the canvas is
 * valid — so the load is issued ONCE and we then just force the canvas valid + watch for {@code client.isGpu()}.
 * Inert (mutates nothing) unless the flag is set.
 */
@Slf4j
public final class RLHDStarter
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.routecrlhd");
	private static final String HD_PLUGIN = "rs117.hd.HdPlugin";
	private static final int MAX_ATTEMPTS = 40;     // RLHD's own startUp retry needs the game cache; allow ~a minute

	private static boolean done;
	private static boolean configForced;
	private static boolean loadIssued;
	private static int attempts;
	private static long lastActionMs;

	private RLHDStarter()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Per {@code Hooks.draw}: load + start 117HD once in-world, then force the canvas valid until it activates. */
	public static void tick(Client client)
	{
		if (!ENABLED || done || client == null)
		{
			return;
		}
		if (client.isGpu())
		{
			done = true;
			log.info("[routecrlhd] GPU renderer active (isGpu=true) — 117HD up after {} attempt(s)", attempts);
			return;
		}

		// Engage once there is a drawing canvas — LOGGED_IN (the 3D world) OR the OSRS LOGIN_SCREEN.
		final GameState gs = client.getGameState();
		if (gs != GameState.LOGGED_IN && gs != GameState.LOGIN_SCREEN)
		{
			return;
		}

		// Throttle so we don't hammer the EDT/client thread every frame while startUp comes up.
		final long now = System.currentTimeMillis();
		if (lastActionMs != 0 && now - lastActionMs < 1500)
		{
			return;
		}
		lastActionMs = now;

		// Phase 0: BEFORE loading the plugin, force the simplest GLSL-ES paths so HdPlugin.startUp's shader compile
		// uses them. We force the MINIMAL flat-scene config the iOS Zone shader gate (Z0) proved compiles+links on
		// the device EAGL ES 3.0 driver. Any feature Z0 left OFF pulls in shader code with its OWN un-ported
		// int<->float (#13) cast sites (e.g. parallax-occlusion-mapping's minLayers/numLayers loop) that the runtime
		// downporter's cast map doesn't cover -> ShaderException. Matching the runtime config to Z0's buildConfig()
		// makes the assembled shader equal the device-green ES300. Config writes queue on the client thread; returning
		// lets them commit before the load on the next tick. (Group "hd"; keys/values per HdPluginConfig + the
		// ShadowMode/DynamicLights enums.)
		if (!configForced)
		{
			MobileUiBridge.setConfig("hd", "uiScalingMode", "LINEAR");                 // else HYBRID/xBR pull in scale/*.glsl
			// B1 TILED LIGHTING (heaviest portable feature): lets the WHOLE scene light up (vs the ~50 nearest lights of
			// non-tiled dynamic lighting). A per-tile culling pass (tiled_lighting_{vert,frag}.glsl, ios-overrides ES3
			// ports) writes each screen tile's active light indices into tiledLightingArray (RGBA16UI 2D array, one layer
			// per culling pass, ES3.0-core color-renderable), and scene_frag's calculateLighting (utils/lights.glsl TILED
			// branch) reads the tile's indices instead of looping all lights. With tiled ON, HdPlugin sizes
			// MAX_LIGHT_COUNT = UBOLights.MAX_LIGHTS; the java-override caps that 1000->480 so UBOLights (480*32=15360 B)
			// + UBOLightsCulling (480*16=7680 B) both fit Apple's 16384 B uniform-block cap -> no texture-backed store
			// needed (unlike UBOMaterials). The ES3.2-only layered FBO attach (glFramebufferTexture) is emulated in the
			// bridge by attaching layer 0 (the per-layer pass re-attaches each layer via glFramebufferTextureLayer);
			// image-load-store is caps-gated off (GL_ARB_shader_image_load_store=false on iOS) so the per-layer path is
			// taken. Simulator EAGL gate GREEN: scene + tiled (layer 0 + layer >0) compile + link at TILED_LIGHTING=1.
			MobileUiBridge.setConfig("hd", "tiledLighting", "true");                   // TILED_LIGHTING on (whole-scene lighting)
			// B1 DYNAMIC LIGHTS (third ported feature): torches/fires/spells cast coloured point light. DYNAMIC_LIGHTS
			// on pulls utils/lights.glsl (ios-overrides ES3 port, 2 casts) into scene_frag's lighting. SOME =
			// getMaxSceneLights()=50 -> UBOLights+UBOLightsCulling shader blocks ~2.4KB, under Apple's 16KB cap (so no
			// texture-backed store needed — that's only the 1000-light TILED path). TILED_LIGHTING stays off. The
			// per-fragment loop runs over pointLightsCount (active nearby lights only, not all 50). Simulator EAGL gate
			// GREEN at DYNAMIC_LIGHTS=1/MAX_LIGHT_COUNT=50.
			MobileUiBridge.setConfig("hd", "dynamicLights", "SOME");                   // DYNAMIC_LIGHTS on (50 max scene lights)
			// B1 NORMAL MAPPING (second ported feature): NORMAL_MAPPING swaps the sampleNormalMap stub (TBN[2]) for
			// the real utils/normals.glsl body, which samples the normal map as a layer of the EXISTING material
			// textureArray (material.normalMap index — no new texture loading) and does one int->float cast that's
			// already in both the assembler + runtime (RouteCBridge) downport maps. TBN/cotangent_frame already
			// compiled (the stub used it). Simulator EAGL gate GREEN with NORMAL_MAPPING=1.
			MobileUiBridge.setConfig("hd", "normalMapping", "true");                   // NORMAL_MAPPING on
			MobileUiBridge.setConfig("hd", "parallaxOcclusionMappingToggle", "false"); // PARALLAX_OCCLUSION_MAPPING off
			// B1 SHADOWS: scene_frag pulls the real utils/shadows.glsl shadow-map sampling (ES3 port in
			// ios-overrides/utils/shadows.glsl); the shadow-map FBO's GL_CLAMP_TO_BORDER + GL_TEXTURE_BORDER_COLOR
			// (ES3.2-only) are remapped/no-op'd in the bridge (texelFetch ignores wrap state) and DEPTH_COMPONENT24
			// is forced to UNSIGNED_INT (strict ES3). SMOOTH filtering = the SHADOW_FILTERING_PCF=0 path.
			// Upgraded FAST -> DETAILED + transparency: DETAILED samples the caster's texture alpha so
			// foliage casts DAPPLED shadows (light through leaf gaps) instead of solid blobs; transparency packs
			// depth+alpha (16+8=24 bits) into gl_FragDepth (shadow FBO stays depth-only, DEPTH_COMPONENT24). The
			// shadow pass already runs on device (FAST), and detailedShadowProgram + the DETAILED/TRANSPARENCY
			// shader paths were ES3-ported (round-2 shadow_frag + B1 shadows.glsl); Simulator EAGL gate GREEN at
			// SHADOW_MODE=2/SHADOW_TRANSPARENCY=true. SMOOTH (PCF) filtering unchanged.
			MobileUiBridge.setConfig("hd", "shadowMode", "DETAILED");                  // SHADOW_MODE 2 (was FAST)
			MobileUiBridge.setConfig("hd", "shadowFiltering", "SMOOTH");               // SHADOW_FILTERING_PCF (0)
			MobileUiBridge.setConfig("hd", "enableShadowTransparency", "true");        // SHADOW_TRANSPARENCY on
			MobileUiBridge.setConfig("hd", "windDisplacement", "false");               // WIND_DISPLACEMENT off
			MobileUiBridge.setConfig("hd", "characterDisplacement", "false");          // CHARACTER_DISPLACEMENT off
			// Single-threaded model processing: RLHD's multithreadedModelProcessing (keyName asyncModelProcessing)
			// defaults TRUE -> useMultithreading() -> setGpuFlags(RENDER_THREADS(2)), so the engine calls the draw
			// callbacks from worker threads that touch the DynamicModelVAO GL buffers concurrently. Our Route C bridge
			// is a SINGLE thread-affine EAGL context, so concurrent buffer access races and intermittently corrupts the
			// per-frame animated-model data -> real PLAYERS (the most-animated models) flash with geometry/colour
			// sampled from elsewhere; NPCs render clean. NOT a buffer-mapping bug (players share the NPC buffer code) —
			// the race is above the mapping layer. Force it off so RENDER_THREADS=0 and all GL stays on one thread.
			// Durable constraint: any RLHD/engine multithreaded-GL feature must stay off on the single-context bridge.
			MobileUiBridge.setConfig("hd", "asyncModelProcessing", "false");
			// B1 MSAA (was DISABLED): hardware anti-aliasing on top of the SSAA. Round 7 black-screened because with
			// no SSAA the scene FBO blit-resolved the multisampled FBO DIRECTLY to the default FBO (CAEAGLLayer) —
			// Apple's GLES is fragile on that exact resolve. Now that sceneResolutionScale=200 (SSAA), RLHD takes the
			// SAFE path: it resolves the MSAA into an intermediate fboSceneResolve renderbuffer (matching format/size,
			// HdPlugin:1356-1364) FIRST, then downscales that to the screen — sidestepping the direct-to-default blit.
			// iOS caps GL_MAX_SAMPLES at 4, so MSAA_4 == the device max. NOTE: this is a runtime FBO change the
			// Simulator shader probe can't pre-validate — pure device test; revert to DISABLED if it regresses.
			MobileUiBridge.setConfig("hd", "antiAliasingMode", "MSAA_4");
			// Scene resolution scale (SSAA): renders the scene to fboScene at sceneResolution = sceneViewport *
			// (sceneResolutionScale/100), then downsamples into the viewport with sceneScalingMode's filter
			// (HdPlugin:1315, ZoneRenderer:1109). 200% (2x SSAA) was added to smooth edges back when the canvas was the
			// tiny FIXED 765x503 upscaled ~3x to retina. In RESIZABLE mode the engine canvas is already ~1304x600
			// (1.7x wider), so 2x SSAA on top renders the scene at 2608x1200 + MSAA_4 + shadows + tiled lighting EVERY
			// frame -> ~9 fps on device (device-measured). The higher native canvas + MSAA_4 already carry the edge AA,
			// so SSAA is largely redundant here: drop to 100 (native) to cut the scene-render pixel count ~4x. (After
			// the direct-present path lands and removes the glReadPixels readback ceiling, SSAA can be dialed back up
			// for quality if wanted.) LINEAR pins a smooth downsample for any residual scaling.
			MobileUiBridge.setConfig("hd", "sceneScalingMode", "LINEAR");
			final String last = MobileUiBridge.setConfig("hd", "sceneResolutionScale", "100");
			if (last.contains("\"ok\":true"))
			{
				configForced = true;
				log.info("[routecrlhd] forced iOS config (uiScaling=LINEAR; POM/wind/char-disp/asyncModelProcessing OFF; shadows=DETAILED/SMOOTH/transparency; normalMapping=ON; dynamicLights=SOME; tiledLighting=ON; MSAA_4; sceneResolutionScale=100 LINEAR = native, no SSAA)");
			}
			return;
		}

		final Canvas canvas = client.getCanvas();
		final boolean valid = canvas != null && canvas.isValid();
		attempts++;
		log.info("[routecrlhd] {} 117HD not yet active (attempt {}/{}): canvas={} valid={} loadIssued={}",
			gs, attempts, MAX_ATTEMPTS, canvas == null ? "null" : "present", valid, loadIssued);

		// Force the canvas valid so HdPlugin.startUp's canvas.isValid() retry can pass (EDT, under the tree lock).
		if (canvas != null && !valid)
		{
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					synchronized (canvas.getTreeLock())
					{
						canvas.validate();
					}
					log.info("[routecrlhd] canvas.validate() -> valid={}", canvas.isValid());
				}
				catch (Throwable t)
				{
					log.warn("[routecrlhd] canvas.validate() failed", t);
				}
			});
		}

		// Load + start HdPlugin exactly ONCE (loadPlugins is not idempotent — a second call would double-load).
		// Its own clientThread retry then waits for the game cache + valid canvas; we just keep validating above.
		if (!loadIssued)
		{
			final String r = MobileUiBridge.loadAndStartPlugin(HD_PLUGIN);
			loadIssued = true;
			log.info("[routecrlhd] loadAndStartPlugin({}) -> {}", HD_PLUGIN, r);
		}

		if (attempts >= MAX_ATTEMPTS)
		{
			done = true;
			log.warn("[routecrlhd] giving up after {} attempts — 117HD did not become active", attempts);
		}
	}
}
