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
 * GPU Route C — reliably bring up the base {@code gpu} plugin on the device. Two intermittent failures kept the
 * plugin from ever creating its GL context (so the engine silently fell back to the CPU renderer):
 * <ol>
 *   <li><b>Persisted {@code runelite.gpuplugin=false}</b> in the device profile overrides the plugin's
 *       {@code enabledByDefault=true}, so {@code startUp} never runs.</li>
 *   <li><b>{@code startUp} runs too early</b> — during LOGGING_IN the AWT canvas isn't valid yet, so its
 *       {@code if (!canvas.isValid()) return false} retry loop spins, and on the headless device path the canvas
 *       did not reliably become valid, so {@code createGLContext} never fired.</li>
 * </ol>
 *
 * <p>This helper, armed by {@code -Drunelite.routecgpu=true} (set by the native launcher only in GPU Route C mode),
 * runs per {@code Hooks.draw} and, once the client is LOGGED_IN (in-world): forces the canvas valid (an EDT
 * {@code validate()} under its tree lock) and force-enables + starts the gpu plugin via the same path the plugin
 * list uses ({@link MobileUiBridge#setPluginEnabled}). {@code PluginManager.startPlugin} is a no-op once the plugin
 * is active, so this is safe to repeat; it retries each tick until {@code client.isGpu()} (then it is inert). It
 * mutates nothing in CPU/L1 mode (inert unless the flag is set).
 */
@Slf4j
public final class RouteCGpuStarter
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.routecgpu");
	private static final String GPU_PLUGIN = "net.runelite.client.plugins.gpu.GpuPlugin";
	private static final int MAX_ATTEMPTS = 30;     // ~ a minute of retries, then give up (avoid spinning forever)

	private static boolean done;
	private static int attempts;
	private static long lastActionMs;

	private RouteCGpuStarter()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Per {@code Hooks.draw}: ensure the gpu plugin is enabled + started once in-world. Inert once GPU is active. */
	public static void tick(Client client)
	{
		if (!ENABLED || done || client == null)
		{
			return;
		}
		if (client.isGpu())
		{
			done = true;
			log.info("[routecgpu] GPU active (isGpu=true) — done after {} attempt(s)", attempts);
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;   // wait for in-world: the canvas is only laid out (valid) once the world is entered
		}

		// Throttle the enable/validate to ~1.5s so we don't hammer the EDT every frame while startUp comes up.
		final long now = System.currentTimeMillis();
		if (lastActionMs != 0 && now - lastActionMs < 1500)
		{
			return;
		}
		lastActionMs = now;

		final Canvas canvas = client.getCanvas();
		final boolean valid = canvas != null && canvas.isValid();
		attempts++;
		log.info("[routecgpu] in-world, GPU not yet active (attempt {}/{}): canvas={} valid={}",
			attempts, MAX_ATTEMPTS, canvas == null ? "null" : "present", valid);

		// (1) Force the canvas valid so the gpu plugin's startUp canvas.isValid() retry can pass. validate() runs on
		//     the EDT under the same tree lock the plugin synchronizes on.
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
					log.info("[routecgpu] canvas.validate() -> valid={}", canvas.isValid());
				}
				catch (Throwable t)
				{
					log.warn("[routecgpu] canvas.validate() failed", t);
				}
			});
		}

		// (2) Force-enable + start the gpu plugin (overrides persisted gpuplugin=false). startPlugin is a no-op once
		//     active, so this is harmless if the plugin is already up (its own startUp retry will then complete).
		final String r = MobileUiBridge.setPluginEnabled(GPU_PLUGIN, true);
		log.info("[routecgpu] setPluginEnabled({}, true) -> {}", GPU_PLUGIN, r);

		if (attempts >= MAX_ATTEMPTS)
		{
			done = true;
			log.warn("[routecgpu] giving up after {} attempts — GPU plugin did not become active", attempts);
		}
	}
}
