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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * GPU Route C scene-render diagnostic (read-only). Localises the black-3D-scene blocker: the JNI GL bridge
 * works end-to-end (UI composites correctly through the device GPU), but the engine never drives the gpu
 * plugin's zone-renderer callbacks — the GL trace shows {@code glMultiDrawArrays} (zone draw) and
 * {@code glBlitFramebuffer} (scene-FBO blit) never fire, so {@code sceneFboValid} stays false and the 3D
 * scene is black while the UI quad composites fine.
 *
 * <p>The engine's per-frame 3D scene render lives in obfuscated gamepack bytecode (method {@code ev.dk}),
 * which branches on {@code client.ly & DrawCallbacks.ZBUF}: ZBUF set → the modern zone path
 * ({@code preSceneDraw}/{@code drawZoneOpaque}/{@code postSceneDraw}); else the legacy software renderer.
 * Either branch ends by invoking {@link net.runelite.api.hooks.Callbacks#drawScene()} (our
 * {@code Hooks.drawScene}). The viewport read inside {@code dk} is skipped entirely when {@code isGpu} — so a
 * zero {@code getViewportWidth()} does NOT gate the GPU scene path (the legacy CPU path renders the world fine
 * at the same {@code 1304x600}/{@code viewport=0x0} state). This probe answers the one fork the GL trace
 * cannot:
 * <ul>
 *   <li><b>drawScene count climbs, GL trace still shows no zone draws</b> ⇒ {@code dk} runs but takes its
 *       LEGACY branch (ZBUF not effective for the gamepack's read of {@code client.ly}) — the GPU/ZBUF flag,
 *       not the viewport, is the blocker.</li>
 *   <li><b>drawScene count stays flat</b> ⇒ {@code dk} is never dispatched — the blocker is above the scene
 *       render entirely (the headless engine isn't asked to draw the 3D viewport).</li>
 * </ul>
 *
 * <p>It also logs the registered {@link DrawCallbacks} concrete class + classloader, to test the flagged
 * intermittent {@code AWTContext.loadNatives} classloader-mismatch theory (the gpu plugin instance the engine
 * holds vs the one that bound the natives).
 *
 * <p>Mutates nothing. Inert unless {@code -Drunelite.gpudiag=true}. Logs at most once/second via slf4j (→
 * {@code .runelite/logs/client.log}).
 */
@Slf4j
public final class GpuSceneDiag
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.gpudiag");
	/** One-shot: dump the live caller chain the first time the engine dispatches its per-frame scene render. */
	private static final boolean DUMP_STACK = Boolean.getBoolean("runelite.gpudiag.stack");
	private static volatile boolean stackDumped;

	private static volatile long sceneDraws;
	private static volatile long drawTicks;
	private static long lastLogMs;
	private static long lastSceneDraws;
	private static long lastDrawTicks;

	private GpuSceneDiag()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Counts {@link net.runelite.api.hooks.Callbacks#drawScene()} — emitted at the END of the engine's
	 * per-frame 3D scene render ({@code ev.dk}), in BOTH the zone and legacy branches. A climbing count means
	 * the scene render is being dispatched; a flat count means it is not.
	 */
	public static void onDrawScene()
	{
		if (DUMP_STACK && !stackDumped)
		{
			stackDumped = true;
			// ONE-SHOT: the engine just dispatched its per-frame 3D scene render (Callbacks.drawScene), so this
			// stack trace is the LIVE caller chain from the frame loop down to the scene render — the exact
			// opaque/computed-dispatch path static decompile could not resolve (the diagnosis's dead end). Capture
			// it in a mode where the scene DOES render (CPU/software headless), then diff against GPU mode (where
			// drawScene is never called) to localise the branch that suppresses the dispatch. Gated
			// -Drunelite.gpudiag.stack=true.
			final StringBuilder sb = new StringBuilder("[gpudiag] FIRST drawScene dispatch — caller chain:\n");
			for (StackTraceElement e : Thread.currentThread().getStackTrace())
			{
				sb.append("    at ").append(e).append('\n');
			}
			log.info(sb.toString());
		}
		sceneDraws++;
		maybeLog(null);
	}

	/** Per {@code Hooks.draw} — installs the tracing wrapper (once, early) + drives the throttled snapshot. */
	public static void tick(Client client)
	{
		drawTicks++;
		installTracerIfNeeded(client);
		maybeLog(client);
	}

	/**
	 * Wrap the gpu plugin's {@link DrawCallbacks} in {@link TracingDrawCallbacks} the moment it registers —
	 * during LOGGING_IN, before the login scene-load constructs the engine's scene-loader (which captures the
	 * current callbacks ref). Wrapping before that point keeps the loader's captured ref equal to {@code client}'s
	 * callbacks, so {@code loadScene}/{@code swapScene} dispatch stays intact. Idempotent (skips once wrapped).
	 */
	private static void installTracerIfNeeded(Client client)
	{
		final DrawCallbacks cb = client.getDrawCallbacks();
		if (cb != null && !(cb instanceof TracingDrawCallbacks)
			&& cb.getClass().getName().contains(".gpu.GpuPlugin"))
		{
			client.setDrawCallbacks(new TracingDrawCallbacks(cb));
			log.info("[gpudiag] installed TracingDrawCallbacks around {}", cb.getClass().getName());
		}
	}

	private static synchronized void maybeLog(Client client)
	{
		if (client == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (lastLogMs != 0 && now - lastLogMs < 1000)
		{
			return;
		}
		lastLogMs = now;

		final long sd = sceneDraws;
		final long dt = drawTicks;
		final DrawCallbacks cb = client.getDrawCallbacks();
		final String cbClass = cb == null ? "null" : cb.getClass().getName();

		// Layout state: device round 1 proved the scene render (ev.dk / drawScene) is never dispatched and the
		// viewport setter (od, which clamps to >=1) is never called (viewport stuck 0x0) — so the engine never
		// draws the game-viewport interface in GPU mode. These reads localise WHY: is a toplevel interface even
		// loaded, is the client in resizable mode, what does the engine think its real size is, are widget roots
		// present. Defensive — some throw before the world is built.
		String layout;
		try
		{
			final java.awt.Dimension real = client.getRealDimensions();
			final net.runelite.api.widgets.Widget[] roots = client.getWidgetRoots();
			layout = String.format("resized=%s topLevelIf=%d real=%dx%d widgetRoots=%d vpOff=%d,%d",
				client.isResized(), client.getTopLevelInterfaceId(),
				real == null ? -1 : real.width, real == null ? -1 : real.height,
				roots == null ? -1 : roots.length,
				client.getViewportXOffset(), client.getViewportYOffset());
		}
		catch (Throwable t)
		{
			layout = "layout-read-failed:" + t;
		}

		log.info("[gpudiag] state={} isGpu={} drawCallbacks={} drawScene(total={} +{}) drawTick(total={} +{}) "
				+ "viewport={}x{} canvas={}x{} sceneNonNull={} gameCycle={} {} || callbacks[{}]",
			client.getGameState(), client.isGpu(), cbClass,
			sd, sd - lastSceneDraws, dt, dt - lastDrawTicks,
			client.getViewportWidth(), client.getViewportHeight(),
			client.getCanvasWidth(), client.getCanvasHeight(),
			client.getScene() != null, client.getGameCycle(), layout,
			TracingDrawCallbacks.summary());

		lastSceneDraws = sd;
		lastDrawTicks = dt;
	}
}
