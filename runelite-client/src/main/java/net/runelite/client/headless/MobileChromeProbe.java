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
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 hybrid-chrome de-risk — the one-shot DESKTOP experiment that settles the reviewer's open question:
 * <b>what switches the game's active tab</b> when the native chrome's tab is tapped?
 *
 * <p>Two candidate primitives:
 * <ul>
 *   <li><b>Synth-click</b> — synthesize a real left click on the live desktop tab stone at its canvas bounds
 *       (the path a desktop click takes; the same {@code HeadlessLogin} click shape). Expected to fire every
 *       rebuild/listener the engine ties to a tab click.</li>
 *   <li><b>{@code setVarcIntValue(TOPLEVEL_PANEL, tab)}</b> — poke the active-tab varc directly. Simpler, but
 *       the reviewer warns it may flip the var without driving the real rebuild (the engine may even revert
 *       it on the next layout tick).</li>
 * </ul>
 *
 * <p>The clean, low-assumption observable is {@code TOPLEVEL_PANEL} itself: (a) after clicking stone {@code T}
 * does the active-tab varc read {@code T}? (b) after setting the varc to {@code T2}, does it <em>stick</em>
 * for the observe window (vs the engine reverting it)? The result decides the chrome's switching primitive.
 *
 * <p><b>This probe mutates</b> (it clicks/sets to switch tabs), so it is a SEPARATE gate from the read-only
 * {@link MobileChromeBridge} and is <b>desktop-only</b> — the device launch path never carries it. Entirely
 * inert unless {@code -Drunelite.mobilechrome.probe=true}; one-shot; emits a single bash-parseable verdict
 * line ({@code [chrome-probe] CHROME_SWITCH …}) for md5-gating.
 */
@Slf4j
public final class MobileChromeProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.probe");

	private static final int[] STONES_OSRS_STRETCH = {
		InterfaceID.ToplevelOsrsStretch.STONE0, InterfaceID.ToplevelOsrsStretch.STONE1, InterfaceID.ToplevelOsrsStretch.STONE2,
		InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelOsrsStretch.STONE5,
		InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelOsrsStretch.STONE7, InterfaceID.ToplevelOsrsStretch.STONE8,
		InterfaceID.ToplevelOsrsStretch.STONE9, InterfaceID.ToplevelOsrsStretch.STONE10, InterfaceID.ToplevelOsrsStretch.STONE11,
		InterfaceID.ToplevelOsrsStretch.STONE12, InterfaceID.ToplevelOsrsStretch.STONE13,
	};
	private static final int[] STONES_PRE_EOC = {
		InterfaceID.ToplevelPreEoc.STONE0, InterfaceID.ToplevelPreEoc.STONE1, InterfaceID.ToplevelPreEoc.STONE2,
		InterfaceID.ToplevelPreEoc.STONE3, InterfaceID.ToplevelPreEoc.STONE4, InterfaceID.ToplevelPreEoc.STONE5,
		InterfaceID.ToplevelPreEoc.STONE6, InterfaceID.ToplevelPreEoc.STONE7, InterfaceID.ToplevelPreEoc.STONE8,
		InterfaceID.ToplevelPreEoc.STONE9, InterfaceID.ToplevelPreEoc.STONE10, InterfaceID.ToplevelPreEoc.STONE11,
		InterfaceID.ToplevelPreEoc.STONE12, InterfaceID.ToplevelPreEoc.STONE13,
	};

	private static final int BASELINE_DEADLINE_FRAMES = 250;
	private static final int OBSERVE_FRAMES = 25;

	private enum Phase { WAIT_BASELINE, CLICK_MOVE, CLICK_PRESS, CLICK_OBSERVE, VARC_SET, VARC_OBSERVE, DONE }

	private static Phase phase = Phase.WAIT_BASELINE;
	private static int frames;
	private static int observe;

	private static int[] stones;
	private static int baselineTab;
	private static int tClick = -1;
	private static int tVarc = -1;
	private static int clickCx;
	private static int clickCy;

	// results
	private static boolean clickReached;   // after clicking STONE{tClick}, TOPLEVEL_PANEL read tClick
	private static int varcSetTo = -1;
	private static boolean varcImmediate;  // right after setVarc, the varc read the target
	private static boolean varcSticks;     // the varc still read the target at the end of the observe window
	private static boolean focused;

	private MobileChromeProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** One step of the experiment, on the client thread (from {@code Hooks.draw}), only when LOGGED_IN. */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || phase == Phase.DONE || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		frames++;

		switch (phase)
		{
			case WAIT_BASELINE:
				if (tryEstablishBaseline(client))
				{
					phase = Phase.CLICK_MOVE;
				}
				else if (frames >= BASELINE_DEADLINE_FRAMES)
				{
					emit(client, "INCONCLUSIVE_NO_BASELINE");
				}
				break;

			case CLICK_MOVE:
				// Cycle 1: prime focus + move the pointer onto the stone so the engine re-targets it.
				postMove(client, clickCx, clickCy);
				phase = Phase.CLICK_PRESS;
				break;

			case CLICK_PRESS:
				// Cycle 2: press + release + click at the stone — the proven HeadlessLogin click shape.
				postClick(client, clickCx, clickCy);
				observe = 0;
				phase = Phase.CLICK_OBSERVE;
				break;

			case CLICK_OBSERVE:
				observe++;
				if (varc(client) == tClick)
				{
					clickReached = true;
				}
				if (clickReached || observe >= OBSERVE_FRAMES)
				{
					log.info("[chrome-probe] click STONE{}={} -> activeTab={} reached={}",
						tClick, idForStone(tClick), varc(client), clickReached);
					phase = Phase.VARC_SET;
				}
				break;

			case VARC_SET:
				varcSetTo = tVarc;
				try
				{
					client.setVarcIntValue(VarClientID.TOPLEVEL_PANEL, tVarc);
				}
				catch (Throwable t)
				{
					log.info("[chrome-probe] setVarcIntValue(TOPLEVEL_PANEL,{}) threw: {}", tVarc, String.valueOf(t));
				}
				varcImmediate = varc(client) == tVarc;
				observe = 0;
				phase = Phase.VARC_OBSERVE;
				break;

			case VARC_OBSERVE:
				observe++;
				if (observe >= OBSERVE_FRAMES)
				{
					varcSticks = varc(client) == tVarc;
					log.info("[chrome-probe] setVarc TOPLEVEL_PANEL={} -> immediate={} sticks={} (now={})",
						tVarc, varcImmediate, varcSticks, varc(client));
					emit(client, "DONE");
				}
				break;

			default:
				break;
		}
	}

	private static boolean tryEstablishBaseline(Client client)
	{
		final int top = safeTop(client);
		if (top <= 0)
		{
			return false;
		}
		final boolean resized = safeResized(client);
		stones = resized
			? (safeVarbit(client, VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1 ? STONES_PRE_EOC : STONES_OSRS_STRETCH)
			: null;
		if (stones == null)
		{
			// Fixed mode is not our product target; only run the experiment on a resizable root.
			return false;
		}
		baselineTab = varc(client);

		// Pick two distinct, currently-resolvable stones to switch to (bounds present), both != baseline tab.
		tClick = firstResolvableStoneExcept(client, baselineTab, -1);
		tVarc = firstResolvableStoneExcept(client, baselineTab, tClick);
		if (tClick < 0 || tVarc < 0)
		{
			return false;
		}
		final int[] c = stoneCenter(client, tClick);
		if (c == null)
		{
			return false;
		}
		clickCx = c[0];
		clickCy = c[1];
		log.info("[chrome-probe] baseline frame={} top={} resized={} baselineTab={} tClick={} (center {},{}) tVarc={}",
			frames, top, resized, baselineTab, tClick, clickCx, clickCy, tVarc);
		return true;
	}

	/** Index of the first stone (!= a, != b) whose widget resolves with positive bounds. */
	private static int firstResolvableStoneExcept(Client client, int a, int b)
	{
		for (int i = 0; i < stones.length; i++)
		{
			if (i == a || i == b)
			{
				continue;
			}
			if (stoneCenter(client, i) != null)
			{
				return i;
			}
		}
		return -1;
	}

	private static int[] stoneCenter(Client client, int idx)
	{
		if (idx < 0 || idx >= stones.length)
		{
			return null;
		}
		try
		{
			final Widget w = client.getWidget(stones[idx]);
			if (w == null || w.isHidden())
			{
				return null;
			}
			final Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				return null;
			}
			return new int[]{b.x + b.width / 2, b.y + b.height / 2};
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static int idForStone(int idx)
	{
		return idx >= 0 && idx < stones.length ? stones[idx] : -1;
	}

	// ---- AWT posting (mirrors JavaBridge's proven click shape; we are already on the client thread) --------

	private static void postMove(Client client, int x, int y)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		if (!focused)
		{
			q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
			q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
			focused = true;
		}
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
	}

	private static void postClick(Client client, int x, int y)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 1,
			0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 2,
			0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static void emit(Client client, String result)
	{
		phase = Phase.DONE;
		log.info("[chrome-probe] CHROME_SWITCH result={} baselineTab={} tClick={} clickReached={} "
				+ "tVarc={} varcImmediate={} varcSticks={} finalTab={}",
			result, baselineTab, tClick, clickReached, tVarc, varcImmediate, varcSticks, varc(client));
	}

	private static int varc(Client client)
	{
		try
		{
			return client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static int safeTop(Client client)
	{
		try
		{
			return client.getTopLevelInterfaceId();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static boolean safeResized(Client client)
	{
		try
		{
			return client.isResized();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static int safeVarbit(Client client, int id)
	{
		try
		{
			return client.getVarbitValue(id);
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}
}
