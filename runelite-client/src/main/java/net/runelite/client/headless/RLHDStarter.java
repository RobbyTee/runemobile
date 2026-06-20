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
	// Sentinel (custom key in the "hd" group) marking that the user-tunable graphics defaults were seeded once.
	// Present -> a later launch respects the persisted values (the user's 117HD plugin-config edits) instead of
	// re-forcing the defaults. Not a real ConfigItem, so it never shows in the panel.
	private static final String GFX_SEEDED_KEY = "iosGfxSeeded";
	// Separate one-shot sentinel for the shadow-map-resolution fps default, so applying it doesn't re-seed (reset)
	// the user's other tunable picks.
	private static final String GFX_SHADOWRES_KEY = "iosGfxShadowRes1k";

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
		// uses them. RLHDStarter is shared by iOS (Route C / EAGL, full feature port) AND Android (real-HW,
		// mobile-sane) — both armed by -Drunelite.routecrlhd — but they need different 117HD config. The iOS shell
		// sets -Drunelite.gfxIos=true; without it (Android) we apply Android's mobile config. Config writes queue on
		// the client thread; returning lets them commit before the load on the next tick. (Group "hd" per HdPluginConfig.)
		if (!configForced)
		{
			if (!Boolean.getBoolean("runelite.gfxIos"))
			{
			MobileUiBridge.setConfig("hd", "uiScalingMode", "LINEAR");
			MobileUiBridge.setConfig("hd", "tiledLighting", "false");
			// Mobile-sane quality: 117HD's desktop defaults (MSAA_8 + an 8192x8192 shadow map + 50-tile draw)
			// crush a mobile GPU. Cut the fill-rate/bandwidth killers but KEEP the HD look (lighting/shadows/
			// textures). Pure render settings (no shader-variant change), set BEFORE startUp so the shadow map
			// allocates small. All tunable afterwards in the plugin config panel.
			MobileUiBridge.setConfig("hd", "antiAliasingMode", "DISABLED");
			// FIX-13: 1K shadow map (was 2K). The shadow pass is a bandwidth bottleneck on Mali; 1K vs 2K is
			// barely visible at the mobile present scale but a real fps win — iOS already ships RES_1024 (below).
			// Reversible in the 117HD plugin config (shadowResolution).
			MobileUiBridge.setConfig("hd", "shadowResolution", "RES_1024");
			// Force SINGLE-THREADED model processing (config key "asyncModelProcessing"). RLHD's multithreaded
			// path (RENDER_THREADS) has the engine call the draw callbacks from worker threads, which then touch
			// GL buffers concurrently — but our EGL/GL bridge has ONE thread-affine context, so concurrent buffer
			// access races and corrupts animated models (real players flash with wrong geometry/colours; NPCs on
			// the shared opaque path are less affected). Single-threaded keeps every GL call on the context thread.
			MobileUiBridge.setConfig("hd", "asyncModelProcessing", "false");
			final String d = MobileUiBridge.setConfig("hd", "drawDistance", "30");
			if (d.contains("\"ok\":true"))
			{
				configForced = true;
				log.info("[routecrlhd] forced mobile config: LINEAR, tiledLighting=off, AA=off, shadows=1024, draw=30, single-threaded");
			}
				return;
			}
			// SAFETY-CRITICAL (forced EVERY launch, NOT user-tunable): each MUST stay or RLHD recompiles an unported
			// shader / hits an iOS bridge limit and stops the plugin. uiScalingMode=LINEAR (HYBRID/xBR pull in unported
			// scale/*.glsl); shadowFiltering=SMOOTH (DITHERED/PIXELATED unported); wind/characterDisplacement off
			// (compute-only no-ops); asyncModelProcessing off (single thread-affine EAGL ctx can't do RLHD multithreaded
			// GL -> player flashing); sceneScalingMode=LINEAR (smooth downsample). These are kept OUT of the native
			// graphics panel. (POM is now ported -> it's a user-tunable, seeded off below.) uiScalingMode is also the
			// client-thread-readiness probe.
			final String probe = MobileUiBridge.setConfig("hd", "uiScalingMode", "LINEAR");
			if (!probe.contains("\"ok\":true"))
				return;   // client thread not ready yet — retry next tick before seeding/committing
			MobileUiBridge.setConfig("hd", "shadowFiltering", "SMOOTH");
			MobileUiBridge.setConfig("hd", "windDisplacement", "false");
			MobileUiBridge.setConfig("hd", "characterDisplacement", "false");
			MobileUiBridge.setConfig("hd", "asyncModelProcessing", "false");
			MobileUiBridge.setConfig("hd", "sceneScalingMode", "LINEAR");

			// USER-TUNABLE graphics defaults — seeded ONCE (first launch, gated on the GFX_SEEDED_KEY sentinel) then
			// PERSISTED. After the first launch the user owns these via the 117HD plugin config; we no longer force
			// them, so their edits survive relaunch (RLHD loads the persisted values). All values here are the
			// iOS-ported set the native panel exposes (each safe to change live): tiledLighting (whole-scene lighting),
			// dynamicLights=SOME (50 lights), normalMapping (surface relief), shadowMode=DETAILED + transparency (dappled
			// foliage shadows), antiAliasingMode=MSAA_4 (device max), sceneResolutionScale=100 (native, no SSAA — the fps
			// pick; raise for sharpness). MAX_LIGHT_COUNT/UBO sizing + the tiled FBO/shader notes are in project memory.
			final boolean seed = !"1".equals(MobileUiBridge.getConfig("hd", GFX_SEEDED_KEY));
			if (seed)
			{
				MobileUiBridge.setConfig("hd", "tiledLighting", "true");
				MobileUiBridge.setConfig("hd", "dynamicLights", "SOME");
				MobileUiBridge.setConfig("hd", "normalMapping", "true");
				MobileUiBridge.setConfig("hd", "shadowMode", "DETAILED");
				MobileUiBridge.setConfig("hd", "enableShadowTransparency", "true");
				MobileUiBridge.setConfig("hd", "antiAliasingMode", "MSAA_4");
				MobileUiBridge.setConfig("hd", "sceneResolutionScale", "100");
				MobileUiBridge.setConfig("hd", "parallaxOcclusionMappingToggle", "false"); // POM ported + available, off by default (it ADDS GPU cost)
				MobileUiBridge.setConfig("hd", GFX_SEEDED_KEY, "1");
				MobileUiBridge.flushConfigQueued();   // persist the seed + sentinel (queued AFTER the writes above)
			}
			// fps one-shot (independent sentinel so it doesn't reset the user's other picks): lower the shadow map to
			// 1K once. The shadow pass (full scene rendered from the light's POV) is the heaviest GPU cost after the
			// main scene; 1K vs 2K is barely visible at the mobile present scale but a real fps win. Reversible in the
			// 117HD plugin config (shadowResolution).
			if (!"1".equals(MobileUiBridge.getConfig("hd", GFX_SHADOWRES_KEY)))
			{
				MobileUiBridge.setConfig("hd", "shadowResolution", "RES_1024");
				MobileUiBridge.setConfig("hd", GFX_SHADOWRES_KEY, "1");
				MobileUiBridge.flushConfigQueued();
			}
			configForced = true;
			log.info("[routecrlhd] iOS config applied — safety forced (uiScaling/shadowFiltering/scaling LINEAR/SMOOTH; "
				+ "POM/wind/char-disp/async OFF); tunables {}", seed ? "SEEDED (first launch)" : "from persisted 117HD plugin config");
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
