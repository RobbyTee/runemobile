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
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * "Sticky Shift" feasibility spike (mobile-UX audit OQ1 / Finding #9). The whole shift-click customization surface
 * (Menu Entry Swapper shift-to-customize, shift deposit/withdraw/shop-quantity swaps, Ground/Object/NPC "Shift +
 * right-click to mark", overlay SHIFT+right-click "Configure") gates on the engine's {@code client.isKeyPressed(
 * KeyCode.KC_SHIFT)} returning true. On touch there is no hardware shift; {@code isKeyPressed} is getter-only
 * ({@code Client.java}) — the engine maintains the backing key-array from AWT {@code KeyEvent}s delivered to the
 * gamepack {@code Canvas} listener. Our text bridge ({@code JavaBridge.onText}/{@code onKeyStroke}) is device-proven
 * to reach that listener, so a synthetic held {@code KEY_PRESSED VK_SHIFT} (no release) <em>should</em> flip
 * {@code isKeyPressed(KC_SHIFT)}. This probe proves it on the live engine.
 *
 * <p>On a logged-in player it: reads the baseline, posts ONE held {@code KEY_PRESSED VK_SHIFT} and re-reads, then
 * (fallback) re-posts the press each frame and re-reads, then releases and re-reads. The single
 * {@code [shiftfeed] SHIFTFEED ...} line is the gate. GREEN = {@code downOnce || downRepeat} (synthetic shift reaches
 * the backing state) ⇒ ship a native sticky-Shift toggle via a {@code JavaBridge.setShiftHeld} bridge. RED (both
 * false) ⇒ the engine ignores synthetic modifier state and shift is infeasible without forking (skip Findings
 * #9/#10/#16). Mutates input only; desktop de-risk. Inert unless {@code -Drunelite.shiftfeed=true}.
 */
@Slf4j
public final class ShiftFeedSpike
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.shiftfeed");

	private static final int SETTLE = 8;       // frames in-world before the test starts
	private static final int READ_DELAY = 6;   // frames after an action before reading isKeyPressed
	private static final int REPEAT_FRAMES = 8; // frames to re-post the held press in the fallback method

	private enum Phase { WAIT, BASELINE, PRESS_ONCE, READ_ONCE, PRESS_REPEAT, READ_REPEAT, RELEASE, READ_RELEASE, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int settleRun;
	private static int repeatPosts;
	private static boolean focused;
	private static boolean verdictEmitted;

	private static boolean baseline;
	private static boolean downOnce;
	private static boolean downRepeat;
	private static boolean upAfterRelease;

	private ShiftFeedSpike()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client c, GameState state)
	{
		if (!ENABLED || c == null || state != GameState.LOGGED_IN || phase == Phase.DONE)
		{
			return;
		}

		// Dismiss the post-login welcome screen (same as the other A-probes) so we have a focused in-world canvas.
		if (welcomeUp(c))
		{
			if ((phaseFrame++ % 20) == 5)
			{
				synthClick(c, c.getCanvasWidth() / 2, c.getCanvasHeight() / 2);
			}
			return;
		}

		switch (phase)
		{
			case WAIT:
				// Let the world settle and grab focus once before testing.
				ensureFocus(c);
				if (++settleRun >= SETTLE)
				{
					go(Phase.BASELINE);
				}
				break;

			case BASELINE:
				baseline = shiftPressed(c);
				log.info("[shiftfeed] baseline isKeyPressed(KC_SHIFT)={}", baseline);
				go(Phase.PRESS_ONCE);
				break;

			case PRESS_ONCE:
				ensureFocus(c);
				postShift(c, true);
				log.info("[shiftfeed] posted single held KEY_PRESSED VK_SHIFT (no release)");
				go(Phase.READ_ONCE);
				break;

			case READ_ONCE:
				if (phaseFrame >= READ_DELAY)
				{
					downOnce = shiftPressed(c);
					log.info("[shiftfeed] after single press: isKeyPressed(KC_SHIFT)={}", downOnce);
					go(Phase.PRESS_REPEAT);
				}
				break;

			case PRESS_REPEAT:
				// Fallback: re-post the held press each frame (mimics how a real sticky-shift would re-assert before
				// menu build, and covers an engine that clears modifier state on focus/other events).
				ensureFocus(c);
				postShift(c, true);
				if (++repeatPosts >= REPEAT_FRAMES)
				{
					downRepeat = shiftPressed(c);
					log.info("[shiftfeed] after {} repeated presses: isKeyPressed(KC_SHIFT)={}", repeatPosts, downRepeat);
					go(Phase.RELEASE);
				}
				break;

			case RELEASE:
				postShift(c, false);
				log.info("[shiftfeed] posted KEY_RELEASED VK_SHIFT");
				go(Phase.READ_RELEASE);
				break;

			case READ_RELEASE:
				if (phaseFrame >= READ_DELAY)
				{
					upAfterRelease = shiftPressed(c);
					log.info("[shiftfeed] after release: isKeyPressed(KC_SHIFT)={}", upAfterRelease);
					go(Phase.DONE);
					emit();
				}
				break;

			default:
				break;
		}

		phaseFrame++;
	}

	private static void emit()
	{
		if (verdictEmitted)
		{
			return;
		}
		verdictEmitted = true;
		final boolean feasible = downOnce || downRepeat;
		final String method = downOnce ? "single-press" : downRepeat ? "repeat-press" : "none";
		final String concl = feasible
			? ("FEASIBLE via " + method + (upAfterRelease ? " (WARN: stayed-down-after-release)" : " (clean-release)"))
			: "INFEASIBLE-synthetic-shift-ignored";
		log.info("[shiftfeed] SHIFTFEED baseline={} downOnce={} downRepeat={} upAfterRelease={} method={} conclusion={}",
			baseline, downOnce, downRepeat, upAfterRelease, method, concl);
	}

	private static boolean shiftPressed(Client c)
	{
		try
		{
			return c.isKeyPressed(KeyCode.KC_SHIFT);
		}
		catch (RuntimeException ex)
		{
			log.info("[shiftfeed] isKeyPressed threw: {}", ex.toString());
			return false;
		}
	}

	private static void postShift(Client c, boolean pressed)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		if (pressed)
		{
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t, InputEvent.SHIFT_DOWN_MASK,
				KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT));
		}
		else
		{
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t, 0,
				KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED, KeyEvent.KEY_LOCATION_LEFT));
		}
	}

	private static void ensureFocus(Client c)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null || focused)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
		focused = true;
	}

	private static void synthClick(Client c, int x, int y)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		ensureFocus(c);
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3, 0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static boolean welcomeUp(Client c)
	{
		try
		{
			final Widget w = c.getWidget(InterfaceID.WelcomeScreen.UNIVERSE);
			return w != null && !w.isHidden();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void go(Phase next)
	{
		phase = next;
		phaseFrame = 0;
	}
}
