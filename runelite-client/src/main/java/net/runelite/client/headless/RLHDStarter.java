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
			MobileUiBridge.setConfig("hd", "tiledLighting", "false");                  // else the tiled-lighting program compiles
			MobileUiBridge.setConfig("hd", "dynamicLights", "NONE");                   // DYNAMIC_LIGHTS off
			MobileUiBridge.setConfig("hd", "normalMapping", "false");                  // NORMAL_MAPPING off
			MobileUiBridge.setConfig("hd", "parallaxOcclusionMappingToggle", "false"); // PARALLAX_OCCLUSION_MAPPING off
			MobileUiBridge.setConfig("hd", "shadowMode", "OFF");                       // SHADOW_MODE 0
			MobileUiBridge.setConfig("hd", "windDisplacement", "false");               // WIND_DISPLACEMENT off
			final String last = MobileUiBridge.setConfig("hd", "characterDisplacement", "false"); // CHARACTER_DISPLACEMENT off
			if (last.contains("\"ok\":true"))
			{
				configForced = true;
				log.info("[routecrlhd] forced Z0 minimal config (uiScaling=LINEAR; tiled/dynamic/normal/POM/shadow/wind/char-disp OFF)");
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
