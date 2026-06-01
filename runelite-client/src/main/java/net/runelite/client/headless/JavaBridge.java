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
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MainBufferProvider;

/**
 * Phase-4 (W3) sub-goal B: the native&lt;-&gt;Java facade for a <em>real interactive</em> client on iOS.
 *
 * <p>This is the one class the native shell calls into via reverse JNI. It exposes two seams:
 * <ul>
 *   <li><b>Live framebuffer blit</b> — {@link #getFrameWidth()}/{@link #getFrameHeight()}/
 *       {@link #getFramePixels()} hand the engine's composited {@code mainBufferProvider} pixels straight
 *       to the native side each screen refresh. This replaces the §6.2 PNG-capture-to-disk display path
 *       (which was choppy, disk-bound and budget-capped) with a direct, smooth blit.</li>
 *   <li><b>Touch ingress</b> — {@link #onTouch(int, int, int, int)} turns a UIKit touch (already mapped to
 *       game coordinates by the native side) into AWT {@link MouseEvent}s posted to the toolkit
 *       {@link EventQueue} with the gamepack {@link Canvas} as source. Sub-goal A proved the on-device CTC
 *       EventQueue accepts off-window posts, so this is the same proven path HeadlessLogin uses, now driven
 *       by real touches instead of a scripted click.</li>
 * </ul>
 *
 * <p>{@link #register(Client)} (top of {@code Hooks.draw}) keeps the bridge pointed at the live client,
 * and {@link #snapshot(MainBufferProvider)} (after the overlays composite) publishes each complete frame.
 * Entirely inert unless {@code -Drunelite.bridge=true}, so it adds nothing to the stock desktop client.
 *
 * <p>Thread model: {@code register} runs on the engine/AWT thread; the native side calls the getters and
 * {@link #onTouch} from the UI thread (JNI-attached). {@code EventQueue.postEvent} is thread-safe, and the
 * pixel array is the buffer's live backing store — reading it off-thread can momentarily tear, which is
 * acceptable for a display.
 */
@Slf4j
public final class JavaBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.bridge");

	// Touch event types — kept in lockstep with the native side (ViewController touchesBegan/Moved/Ended).
	public static final int TOUCH_DOWN = 0;
	public static final int TOUCH_MOVE = 1;
	public static final int TOUCH_UP = 2;

	private static volatile Client client;

	// Published complete frames for the live blit. The native side reads at the display refresh (up to
	// 120 Hz) but the engine composites at ~50 fps, so reading mainBufferProvider directly catches it
	// mid-clear/mid-draw -> fast flashing. Instead we snapshot ONLY at the post-overlay-composite hook
	// (when the frame is whole) into a double buffer, and publish via the volatile `frame` ref so the
	// native reader always copies out a complete, stable frame.
	private static volatile int[] frame;
	private static volatile int frameW;
	private static volatile int frameH;
	private static int[] bufA;
	private static int[] bufB;
	private static boolean useA = true;

	// Input bookkeeping (only ever touched from the single native UI thread that drives onTouch).
	private static boolean focused;
	private static boolean pressed;
	private static int pressButton = MouseEvent.BUTTON1;

	private JavaBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Called at the top of {@code Hooks.draw} each frame to keep the bridge pointed at the live client. */
	public static void register(Client c)
	{
		client = c;
	}

	/**
	 * Called from {@code Hooks.draw} AFTER the overlays composite (the same point {@code HeadlessCapture}
	 * dumps PNGs) — i.e. when {@code mainBufferProvider} holds a <em>complete</em> frame. Copies it into a
	 * double buffer and publishes it for the live blit. Snapshotting here (not at the native read) is what
	 * eliminates the mid-composite flashing.
	 */
	public static void snapshot(MainBufferProvider b)
	{
		if (!ENABLED || b == null)
		{
			return;
		}
		final int[] px = b.getPixels();
		final int w = b.getWidth();
		final int h = b.getHeight();
		if (px == null || w <= 0 || h <= 0)
		{
			return;
		}
		final int n = w * h;
		if (px.length < n)
		{
			return;
		}
		int[] dst = useA ? bufA : bufB;
		if (dst == null || dst.length != n)
		{
			dst = new int[n];
			if (useA)
			{
				bufA = dst;
			}
			else
			{
				bufB = dst;
			}
		}
		System.arraycopy(px, 0, dst, 0, n);
		frameW = w;
		frameH = h;
		frame = dst;        // publish (volatile)
		useA = !useA;       // next snapshot fills the OTHER buffer, so this one stays stable a full cycle
	}

	// ---- Live framebuffer blit (native pulls these each screen refresh) --------------------------------

	public static int getFrameWidth()
	{
		return frameW;
	}

	public static int getFrameHeight()
	{
		return frameH;
	}

	/**
	 * The latest <em>complete</em> composited frame as packed {@code 0xAARRGGBB} ints (a stable snapshot,
	 * not the live back buffer). Returns {@code null} before the first frame.
	 */
	public static int[] getFramePixels()
	{
		return frame;
	}

	// ---- Touch ingress (native maps view coords -> game coords, then calls this) -----------------------

	/**
	 * Post one AWT mouse event for a touch phase. {@code type} is {@link #TOUCH_DOWN}/{@link #TOUCH_MOVE}/
	 * {@link #TOUCH_UP}; {@code (x,y)} are gamepack-canvas pixels; {@code button} is 1 (left) or 3 (right).
	 * The first DOWN also asserts focus + an enter/move so the gamepack samples the pointer correctly (the
	 * same priming HeadlessLogin does), since there is no native focus owner headless.
	 */
	public static void onTouch(int type, int x, int y, int button)
	{
		if (!ENABLED)
		{
			return;
		}
		final Client c = client;
		if (c == null)
		{
			return;
		}
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}

		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		final int awtButton = button == 3 ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
		final int downMask = awtButton == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;

		switch (type)
		{
			case TOUCH_DOWN:
				if (!focused)
				{
					// Prime focus + pointer position on the very first interaction (no headless focus owner).
					q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
					q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
					focused = true;
				}
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 1,
					downMask, x, y, 1, false, awtButton));
				pressed = true;
				pressButton = awtButton;
				break;

			case TOUCH_MOVE:
				if (pressed)
				{
					q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, t,
						pressButton == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK,
						x, y, 0, false, MouseEvent.NOBUTTON));
				}
				else
				{
					q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
				}
				break;

			case TOUCH_UP:
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t,
					0, x, y, 1, false, pressed ? pressButton : awtButton));
				// The gamepack treats a press+release at (roughly) the same spot as a click.
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 1,
					0, x, y, 1, false, pressed ? pressButton : awtButton));
				pressed = false;
				break;

			default:
				log.warn("[bridge] onTouch unknown type {}", type);
				break;
		}
	}
}
