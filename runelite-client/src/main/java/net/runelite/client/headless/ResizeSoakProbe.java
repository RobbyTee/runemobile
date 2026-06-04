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
import net.runelite.api.GameState;

/**
 * Phase-5 (W3) <b>full-screen de-risk</b> — the desktop probe that proves the one untested primitive behind
 * the full-screen toggle: a <em>live, repeated</em> CACIO CTC canvas resize.
 *
 * <p>{@link HeadlessResize} only ever resized the gamepack canvas ONCE (a one-shot on {@code LOGGED_IN}). The
 * full-screen toggle needs to resize it back and forth, mid-game, indefinitely. Whether the gamepack
 * reallocates its {@code MainBufferProvider} + recomputes the viewport cleanly when driven that way — under
 * CTC, off the real shaded client, while logged in and rendering — is the make-or-break unknown. This probe
 * drives exactly that, on desktop OpenJDK 21 (the W2 rule), before any device round-trip.
 *
 * <p>Once {@code LOGGED_IN} and settled, it toggles the canvas between two aspects (size A &harr; size B) via
 * {@link HeadlessResize#applyResize} — the SAME mechanism the native {@code requestResize} will call — for
 * {@code cycles} cycles, asserting after each that the canvas actually reached the requested size, and logging
 * canvas / viewport dims, frames-to-settle, used heap, and any exception. The verdict line
 * {@code RESIZE_SOAK_GREEN}/{@code RESIZE_SOAK_RED} is grepped by the harness.
 *
 * <p>Entirely inert unless {@code -Drunelite.headless.resizeSoak} is set. Desktop-only — never on the device
 * launch path. Gate is canvas-dims (independent of the account's fixed-vs-resizable display mode: the AWT
 * {@code Canvas} — and thus the buffer — resizes either way; the viewport only <em>fills</em> it in resizable
 * mode, which we log but do not gate on).
 *
 * <p>Property forms:
 * <ul>
 *   <li>{@code -Drunelite.headless.resizeSoak=1269x720,1426x720} — toggle between these two sizes</li>
 *   <li>{@code -Drunelite.headless.resizeSoak=1} (or any value without a comma) — use the defaults below</li>
 * </ul>
 * Tunables: {@code .cycles} (default 200), {@code .hold} (frames to dwell at each size, 8),
 * {@code .maxWait} (frames to allow a resize to settle before counting a miss, 90),
 * {@code .settle} (frames to wait after LOGGED_IN before starting, 30).
 */
@Slf4j
public final class ResizeSoakProbe
{
	private static final String SPEC = System.getProperty("runelite.headless.resizeSoak");
	private static final boolean ENABLED = SPEC != null && !SPEC.isEmpty();

	// Two distinct targets to toggle between (defaults bracket the device inset/full-screen aspects @720).
	private static int aW = 1269, aH = 720, bW = 1426, bH = 720;
	private static final int CYCLES = Integer.getInteger("runelite.headless.resizeSoak.cycles", 200);
	private static final int HOLD = Integer.getInteger("runelite.headless.resizeSoak.hold", 8);
	private static final int MAX_WAIT = Integer.getInteger("runelite.headless.resizeSoak.maxWait", 90);
	private static final int SETTLE = Integer.getInteger("runelite.headless.resizeSoak.settle", 30);

	// State machine (all touched only from the client thread in tick()).
	private static int loginFrames;     // frames at LOGGED_IN before we begin (lets the initial layout settle)
	private static int cycle;           // completed cycles so far
	private static int target;          // which size is requested: 0 = A, 1 = B
	private static int waited;          // frames since the in-flight request (until canvas reaches target)
	private static int held;            // frames dwelt at the settled size this cycle
	private static boolean requested;   // a resize is in flight (awaiting the canvas to reach target)
	private static boolean acked;       // the canvas reached target (or timed out) this cycle
	private static boolean started;     // past the settle gate
	private static boolean done;        // verdict emitted

	// Diagnostics.
	private static int acks, misses, exceptions;
	private static int framesToAckSum;
	private static long heapStart, heapPeak;

	static
	{
		parse();
	}

	private ResizeSoakProbe()
	{
	}

	private static void parse()
	{
		if (!ENABLED)
		{
			return;
		}
		try
		{
			if (SPEC.contains(","))
			{
				final String[] pair = SPEC.split(",", 2);
				final int[] a = wh(pair[0]);
				final int[] b = wh(pair[1]);
				if (a != null)
				{
					aW = a[0];
					aH = a[1];
				}
				if (b != null)
				{
					bW = b[0];
					bH = b[1];
				}
			}
		}
		catch (RuntimeException ex)
		{
			log.warn("[resizeSoak] bad spec '{}' (want WxH,WxH) — using defaults {}x{},{}x{}", SPEC, aW, aH, bW, bH);
		}
	}

	private static int[] wh(String s)
	{
		final String[] p = s.trim().split("x", 2);
		if (p.length != 2)
		{
			return null;
		}
		return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Called once per {@code Hooks.draw} (client thread) when enabled. */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || done || client == null)
		{
			return;
		}
		try
		{
			if (state != GameState.LOGGED_IN)
			{
				loginFrames = 0;
				return;
			}
			if (!started)
			{
				if (++loginFrames < SETTLE)
				{
					return;
				}
				started = true;
				heapStart = heapPeak = usedHeap();
				log.info("[resizeSoak] start: A={}x{} B={}x{} cycles={} hold={} maxWait={}",
					aW, aH, bW, bH, CYCLES, HOLD, MAX_WAIT);
				beginCycle(client);
				return;
			}

			final Canvas c = client.getCanvas();
			final int cw = c == null ? -1 : c.getWidth();
			final int ch = c == null ? -1 : c.getHeight();
			final int tw = target == 0 ? aW : bW;
			final int th = target == 0 ? aH : bH;

			if (requested && !acked)
			{
				if (cw == tw && ch == th)
				{
					acked = true;
					acks++;
					framesToAckSum += waited;
				}
				else if (waited >= MAX_WAIT)
				{
					acked = true;
					misses++;
					log.warn("[resizeSoak] cycle {} target {}x{} MISS after {} frames: canvas={}x{} viewport={}x{}",
						cycle, tw, th, waited, cw, ch, client.getViewportWidth(), client.getViewportHeight());
				}
				else
				{
					waited++;
				}
				return;
			}

			// Settled (or gave up): dwell a few frames at the new size, then advance.
			if (held == 0)
			{
				final long h = usedHeap();
				if (h > heapPeak)
				{
					heapPeak = h;
				}
				log.info("[resizeSoak] cycle {}/{} -> {}x{} canvas={}x{} viewport={}x{} framesToSettle={} heapMB={}",
					cycle + 1, CYCLES, tw, th, cw, ch,
					client.getViewportWidth(), client.getViewportHeight(), waited, h / 1048576);
			}
			if (++held <= HOLD)
			{
				return;
			}
			if (++cycle >= CYCLES)
			{
				verdict();
				return;
			}
			target ^= 1;
			beginCycle(client);
		}
		catch (RuntimeException ex)
		{
			exceptions++;
			if (exceptions <= 5)
			{
				log.warn("[resizeSoak] exception in cycle {}", cycle, ex);
			}
		}
	}

	private static void beginCycle(Client client)
	{
		requested = true;
		acked = false;
		waited = 0;
		held = 0;
		HeadlessResize.applyResize(client, target == 0 ? aW : bW, target == 0 ? aH : bH);
	}

	private static void verdict()
	{
		done = true;
		final long heapEnd = usedHeap();
		final boolean green = misses == 0 && exceptions == 0 && acks >= CYCLES;
		log.info("[resizeSoak] RESIZE_SOAK cycles={} acks={} misses={} exceptions={} avgFramesToSettle={} "
				+ "heapStartMB={} heapPeakMB={} heapEndMB={} -> {}",
			CYCLES, acks, misses, exceptions, acks > 0 ? framesToAckSum / acks : -1,
			heapStart / 1048576, heapPeak / 1048576, heapEnd / 1048576,
			green ? "RESIZE_SOAK_GREEN" : "RESIZE_SOAK_RED");
	}

	private static long usedHeap()
	{
		final Runtime r = Runtime.getRuntime();
		return r.totalMemory() - r.freeMemory();
	}
}
