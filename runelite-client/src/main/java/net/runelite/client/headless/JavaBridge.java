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
import java.awt.event.MouseWheelEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
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
 * <p>Thread model: {@code register} runs on the engine/AWT thread; the native side calls the getters,
 * {@link #onTouch} and {@link #onClick} from the UI thread (JNI-attached). {@code EventQueue.postEvent}
 * is thread-safe, and the pixel array is the buffer's live backing store — reading it off-thread can
 * momentarily tear, which is acceptable for a display.
 *
 * <p><b>Input contract (Phase 5).</b> The native side owns gesture <em>recognition</em> (UIKit gesture
 * recognizers: tap, long-press, pan, pinch) and this facade exposes only the raw AWT primitives:
 * <ul>
 *   <li>{@link #onClick(int, int, int)} — a discrete <em>tap</em>: the full focus-prime + move + press +
 *       release + click sequence at one point (the proven {@code HeadlessLogin} click shape). Button 1 =
 *       a left tap (walk/interact / menu-entry select); button 3 = a long-press (open the right-click
 *       menu).</li>
 *   <li>{@link #onTouch(int, int, int, int)} — a <em>drag</em> stream: press / drag / release with NO
 *       synthesized click on release. This is the camera / scroll primitive; a release here is just a
 *       {@code MOUSE_RELEASED}. Earlier (sub-goal B) this method auto-clicked on every UP, which produced
 *       a phantom click at the end of every drag and long-press — removed.</li>
 * </ul>
 */
@Slf4j
public final class JavaBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.bridge");

	// Touch event types — kept in lockstep with the native side (the pan recognizer's began/changed/ended).
	public static final int TOUCH_DOWN = 0;
	public static final int TOUCH_MOVE = 1;
	public static final int TOUCH_UP = 2;

	private static volatile Client client;

	// Published complete frames for the live blit. The native side reads at the display refresh (up to
	// 120 Hz) but the engine composites at ~50 fps, so reading mainBufferProvider directly catches it
	// mid-clear/mid-draw -> fast flashing. Instead we snapshot ONLY at the post-overlay-composite hook
	// (when the frame is whole) into a double buffer, and publish via the volatile `frame` ref so the
	// native reader always copies out a complete, stable frame. `frameSeq` lets the native side skip the
	// (costly) CGImage rebuild when the engine hasn't composited a new frame since the last display tick.
	private static volatile int[] frame;
	private static volatile int frameW;
	private static volatile int frameH;
	private static volatile int frameSeq;
	private static int[] bufA;
	private static int[] bufB;
	private static boolean useA = true;

	// Input bookkeeping (only ever touched from the single native UI thread that drives onTouch/onClick).
	private static boolean focused;
	private static boolean pressed;
	private static int pressButton = MouseEvent.BUTTON1;

	// Camera (two-finger pan -> direct yaw/pitch target). Sub-pixel remainders so a slow drag still turns
	// (a small per-event pixel delta would otherwise truncate to 0 yaw units).
	private static final float YAW_UNITS_PER_PX = 1.5f;    // ~a screen-width drag ≈ half a turn
	private static final float PITCH_UNITS_PER_PX = 1.2f;
	private static final int PITCH_MIN = 128;              // RS camera-pitch clamp (normal range)
	private static final int PITCH_MAX = 383;
	private static float yawRemainder;
	private static float pitchRemainder;

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
		frameSeq++;         // bump LAST so a native reader that sees the new seq also sees this frame
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
	 * Monotonically increasing counter bumped once per published frame. The native side caches the last
	 * value it rendered and skips the {@code CGImage} rebuild when it is unchanged (the display refreshes
	 * faster than the engine composites), so an idle login screen costs almost nothing per tick.
	 */
	public static int getFrameSeq()
	{
		return frameSeq;
	}

	/**
	 * The latest <em>complete</em> composited frame as packed {@code 0xAARRGGBB} ints (a stable snapshot,
	 * not the live back buffer). Returns {@code null} before the first frame.
	 */
	public static int[] getFramePixels()
	{
		return frame;
	}

	// ---- Input ingress (native recognizes the gesture, then calls these with game coords) --------------

	/** Resolve the live gamepack canvas, or {@code null} if the bridge is off / the client isn't ready. */
	private static Canvas liveCanvas()
	{
		if (!ENABLED)
		{
			return null;
		}
		final Client c = client;
		return c == null ? null : c.getCanvas();
	}

	/**
	 * Assert focus + pointer position on the very first interaction. There is no native focus owner
	 * headless, so the gamepack ignores mouse input until it believes the canvas has focus — exactly what
	 * {@code HeadlessLogin} primes before its scripted click.
	 */
	private static void primeFocus(EventQueue q, Canvas canvas, long t, int x, int y)
	{
		if (!focused)
		{
			q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
			q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
			focused = true;
		}
	}

	/**
	 * A discrete tap at {@code (x,y)} (gamepack-canvas pixels): the full focus-prime + move + press +
	 * release + click sequence — the proven {@code HeadlessLogin.synthesizeClick} shape. {@code button} is
	 * 1 (left tap: walk / interact / select a menu entry) or 3 (long-press: open the right-click menu).
	 *
	 * <p>This is the ONLY path that synthesizes {@code MOUSE_CLICKED}; the native side calls it solely for
	 * a recognized tap or long-press, so a drag (which streams through {@link #onTouch}) never produces a
	 * spurious click.
	 */
	public static void onClick(int x, int y, int button)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		final int awtButton = button == 3 ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
		final int downMask = awtButton == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;

		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			downMask, x, y, 1, false, awtButton));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3,
			0, x, y, 1, false, awtButton));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4,
			0, x, y, 1, false, awtButton));
	}

	/**
	 * Move the pointer to {@code (x,y)} with no button held — a plain {@code MOUSE_MOVED}. This is the
	 * "hover" primitive: it updates the gamepack's recorded mouse position so the open right-click menu
	 * highlights the entry under the finger as it slides. Distinct from a drag (which holds a button and
	 * posts {@code MOUSE_DRAGGED} via {@link #onTouch}) so the menu-track gesture can't be confused for one.
	 */
	public static void onMove(int x, int y)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
	}

	/**
	 * Scroll the wheel by {@code rotation} notches at {@code (x,y)} — a {@link MouseWheelEvent}, which
	 * {@code Hooks.mouseWheelMoved} routes through {@code mouseManager} exactly like a {@link MouseEvent}.
	 * Pinch-zoom maps to this: negative {@code rotation} = wheel up = zoom in. {@code (x,y)} is the pinch
	 * centroid so the gamepack zooms about it.
	 */
	public static void onWheel(int x, int y, int rotation)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null || rotation == 0)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, t + 1, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.abs(rotation), rotation));
	}

	/**
	 * Rotate the camera by a two-finger pan: apply the raw pixel delta {@code (dxPixels,dyPixels)} to the
	 * camera yaw/pitch <em>targets</em> directly via the client API (no synthesized middle-mouse, so it is
	 * independent of the "middle mouse moves camera" setting). Yaw wraps mod 2048; pitch clamps to the
	 * normal RS range. Only acts in-game. The native side sends raw pixel deltas; sensitivity + sub-pixel
	 * accumulation live here.
	 */
	public static void onCameraDrag(int dxPixels, int dyPixels)
	{
		final Client c = ENABLED ? client : null;
		if (c == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final float yawF = dxPixels * YAW_UNITS_PER_PX + yawRemainder;
		final int yawStep = (int) yawF;
		yawRemainder = yawF - yawStep;
		if (yawStep != 0)
		{
			c.setCameraYawTarget((c.getCameraYawTarget() + yawStep) & 2047);
		}

		final float pitchF = dyPixels * PITCH_UNITS_PER_PX + pitchRemainder;
		final int pitchStep = (int) pitchF;
		pitchRemainder = pitchF - pitchStep;
		if (pitchStep != 0)
		{
			// Drag down -> camera tilts down (pitch decreases toward the horizon).
			int p = c.getCameraPitchTarget() - pitchStep;
			p = Math.max(PITCH_MIN, Math.min(PITCH_MAX, p));
			c.setCameraPitchTarget(p);
		}
	}

	/**
	 * One phase of a drag stream — {@link #TOUCH_DOWN}/{@link #TOUCH_MOVE}/{@link #TOUCH_UP} at
	 * {@code (x,y)}, {@code button} 1 or 3. DOWN presses, MOVE drags (or hovers when not pressed), UP
	 * releases — with NO synthesized click (unlike a tap, which goes through {@link #onClick}). Used by the
	 * camera / scroll gestures, where a release must not register as a walk-here click.
	 */
	public static void onTouch(int type, int x, int y, int button)
	{
		final Canvas canvas = liveCanvas();
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
				primeFocus(q, canvas, t, x, y);
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
				// Release only — a drag is NOT a click. (The pre-Phase-5 auto-MOUSE_CLICKED here was the
				// phantom-click bug: every camera/scroll drag ended with a stray walk-here click.)
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t,
					0, x, y, 1, false, pressed ? pressButton : awtButton));
				pressed = false;
				break;

			default:
				log.warn("[bridge] onTouch unknown type {}", type);
				break;
		}
	}
}
