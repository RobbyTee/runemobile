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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * GPU Route C black-scene reproduction probe — DESKTOP ONLY.
 *
 * <p>The device-verified diagnosis ({@code ios/dev-tools/gpu-routec/BLACK-SCENE-DIAGNOSIS.md}) is that the engine
 * uploads the GPU scene ({@code loadScene}/{@code swapScene} fire) and runs the composite ({@code draw(int)} ~60fps)
 * but NEVER dispatches the per-frame zone render ({@code preSceneDraw}/{@code drawZone*}/{@code postSceneDraw} = 0)
 * in GPU headless mode — a render-gap, not a bridge fault (the JNI GL bridge is proven end-to-end on device).
 *
 * <p>iOS and desktop share the SAME window-less {@code HeadlessClientUI}/CACIO canvas; the only difference is the
 * GL bridge (EAGL on device, none on desktop). So this probe reproduces the engine handshake WITHOUT any GL: it
 * replays exactly the four engine-facing calls the real {@code GpuPlugin.startUp} makes —
 * <pre>
 *     client.setDrawCallbacks(this);
 *     client.setGpuFlags(DrawCallbacks.GPU | DrawCallbacks.ZBUF);
 *     client.setExpandedMapLoading(...);
 *     client.resizeCanvas();              // rebuild main buffer provider with alpha
 * </pre>
 * (see {@code GpuPlugin.java:361-368}) but with a no-op, GL-free {@link DrawCallbacks} that only COUNTS callbacks
 * (wrapped in {@link TracingDrawCallbacks}). The engine then believes it is in GPU/ZBUF mode and {@code isGpu()}
 * is true, with no GL context required.
 *
 * <p>Run the desktop CACIO login harness with {@code -Drunelite.gpuhandshake=true -Drunelite.gpudiag=true}
 * ({@code ios/dev-tools/headless/run-gpu-handshake.sh}) and read the {@code [gpudiag]} callback counts at
 * LOGGED_IN:
 * <ul>
 *   <li><b>preSceneDraw &gt; 0 / drawScene &gt; 0 on desktop</b> ⇒ the engine DOES dispatch the GPU scene render
 *       in this headless/CACIO setup ⇒ the device black-scene is iOS/bridge-specific (the offscreen-FBO-not-tied-
 *       to-canvas hypothesis) — focus there.</li>
 *   <li><b>preSceneDraw = 0 / drawScene = 0 on desktop</b> ⇒ the device signature reproduces on desktop ⇒ the gap
 *       is the shared W2/CACIO setup, NOT iOS ⇒ attach jdb/IntelliJ here, break at the scene-render dispatch, read
 *       the gate directly — no device rounds.</li>
 * </ul>
 *
 * <p>Installs the handshake ONCE, as early as a valid canvas exists (login screen) — BEFORE the login scene-load,
 * so the engine's scene-loader captures this wrapper as its callbacks ref and {@code loadScene}/{@code swapScene}
 * dispatch stays intact (the same install-before-scene-load constraint {@link TracingDrawCallbacks} documents).
 *
 * <p>This probe MUTATES client state (it is the handshake), so it is gated by its own property and must NEVER be
 * armed on the device launch path — there the real {@code GpuPlugin} owns the handshake. Inert unless
 * {@code -Drunelite.gpuhandshake=true}.
 */
@Slf4j
public final class GpuHandshakeProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.gpuhandshake");

	/** {@code DrawCallbacks.GPU | DrawCallbacks.ZBUF} — exactly the default flags the real gpu plugin sets. */
	private static final int GPU_FLAGS = DrawCallbacks.GPU | DrawCallbacks.ZBUF;

	/** {@code GpuPluginConfig.expandedMapLoadingZones()} default; irrelevant to dispatch, set for faithfulness. */
	private static final int EXPANDED_MAP_LOADING_ZONES =
		Integer.getInteger("runelite.gpuhandshake.expandedZones", 0);

	private static volatile boolean installed;

	private GpuHandshakeProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** A GL-free {@link DrawCallbacks}: only {@code draw(int)} and {@code swapScene} are abstract — everything else
	 * uses the interface defaults. Does no rendering; {@link TracingDrawCallbacks} wraps it for the counts. */
	private static final class NoOpCallbacks implements DrawCallbacks
	{
		@Override
		public void draw(int overlayColor)
		{
		}

		@Override
		public void swapScene(Scene scene)
		{
		}
	}

	/** Per {@code Hooks.draw} — installs the no-GL GPU handshake once a valid canvas exists, then is inert. */
	public static void tick(Client client)
	{
		if (!ENABLED || installed || client == null)
		{
			return;
		}

		// Mirror GpuPlugin.startUp's canvas precondition: it needs a valid canvas (it builds AWTContext from it and
		// resizeCanvas() rebuilds the buffer provider). At STARTING the canvas may not be valid yet — retry next tick.
		final Canvas canvas = client.getCanvas();
		if (canvas == null || !canvas.isValid())
		{
			return;
		}

		try
		{
			final DrawCallbacks cb = new TracingDrawCallbacks(new NoOpCallbacks());
			client.setDrawCallbacks(cb);
			client.setGpuFlags(GPU_FLAGS);
			client.setExpandedMapLoading(EXPANDED_MAP_LOADING_ZONES);
			// force rebuild of main buffer provider to enable alpha channel (verbatim from GpuPlugin.startUp)
			client.resizeCanvas();
			installed = true;
			log.info("[gpuhandshake] installed no-GL GPU handshake: setGpuFlags(GPU|ZBUF) + tracing DrawCallbacks; "
					+ "state={} isGpu={} canvas={}x{}",
				client.getGameState(), client.isGpu(), client.getCanvasWidth(), client.getCanvasHeight());
		}
		catch (Throwable t)
		{
			// Leave installed=false to retry next tick (e.g. resizeCanvas raced the canvas becoming valid).
			log.warn("[gpuhandshake] handshake install failed (will retry next tick)", t);
		}
	}
}
