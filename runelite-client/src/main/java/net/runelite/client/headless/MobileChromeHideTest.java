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
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 — DESKTOP feasibility test for TRULY hiding the desktop tab stones (instead of covering them):
 * <ol>
 *   <li><b>Does {@code setHidden(true)} on the stones hold?</b> Re-applied each frame; logs how many present
 *       stones stay hidden — i.e. do they disappear cleanly from the render?</li>
 *   <li><b>Does {@code setVarcIntValue(TOPLEVEL_PANEL, n)} switch the panel?</b> Hidden stones aren't clickable,
 *       so the proxy-click switch dies — the alternative is poking the active-tab varc. At a fixed frame this
 *       sets the varc to a different tab and logs whether it sticks; the captured frame shows whether the side
 *       panel content actually followed (e.g. the spellbook for Magic).</li>
 * </ol>
 * Inert unless {@code -Drunelite.mobilechrome.hidetest=true}. Logs {@code [hide-test] …}.
 */
@Slf4j
public final class MobileChromeHideTest
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.hidetest");

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

	private static int frames;
	private static int inWorldFrames;    // frames since stones first appeared (i.e. actually in the world)
	private static boolean switched;
	private static int switchTarget = -1;
	private static final int SWITCH_AFTER_INWORLD = 40;

	private MobileChromeHideTest()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		final int[] stones = stonesForRoot(client);
		if (stones == null)
		{
			return;
		}
		frames++;

		// Count present stones (non-null = the toplevel is built and we're actually in the world, not the
		// "Welcome to Gielinor / Click here to play" lobby an established account shows).
		int present = 0;
		for (final int id : stones)
		{
			if (safe(client, id) != null)
			{
				present++;
			}
		}

		// Not in the world yet (lobby) -> synthesize the "Click here to play" click (screen centre) to enter.
		if (present == 0)
		{
			if (frames % 20 == 5)
			{
				synthClick(client, client.getCanvasWidth() / 2, client.getCanvasHeight() / 2);
				log.info("[hide-test] lobby (present=0) -> click centre {},{}",
					client.getCanvasWidth() / 2, client.getCanvasHeight() / 2);
			}
			return;
		}

		inWorldFrames++;

		// (1) Hide every present stone, re-applied each frame; count how many stayed hidden vs were re-hidden
		// by us (the layout un-hid them -> a fight).
		int stayedHidden = 0;
		int reHidden = 0;
		for (final int id : stones)
		{
			final Widget w = safe(client, id);
			if (w == null)
			{
				continue;
			}
			if (w.isHidden())
			{
				stayedHidden++;
			}
			else
			{
				w.setHidden(true);
				reHidden++;
			}
		}

		// (2) Once settled in-world, switch the active tab by poking the varc and watch whether it sticks (the
		// captured frame shows whether the side panel content actually followed).
		if (inWorldFrames == SWITCH_AFTER_INWORLD && !switched)
		{
			switched = true;
			final int before = safeVarc(client);
			switchTarget = (before == 6) ? 3 : 6;   // 6 = Magic (spellbook) / 3 = Inventory
			try
			{
				client.setVarcIntValue(VarClientID.TOPLEVEL_PANEL, switchTarget);
			}
			catch (RuntimeException ex)
			{
				log.info("[hide-test] setVarc threw: {}", String.valueOf(ex));
			}
			log.info("[hide-test] HIDE_SWITCH setVarc TOPLEVEL_PANEL before={} target={} immediate={}",
				before, switchTarget, safeVarc(client));
		}

		if (inWorldFrames == 1 || inWorldFrames % 15 == 0)
		{
			// Witnesses: is the spellbook (magic tab content) / inventory content actually shown? This tells us
			// whether setVarc OPENED the panel vs merely changed the indicator value.
			final boolean spellbook = shown(client, InterfaceID.MagicSpellbook.UNIVERSE);
			final boolean inventory = shown(client, InterfaceID.Inventory.ITEMS);
			log.info("[hide-test] inWorld={} present={} stayedHidden={} reHidden={} activeTab={} switchTarget={} "
					+ "spellbookShown={} inventoryShown={}",
				inWorldFrames, present, stayedHidden, reHidden, safeVarc(client), switchTarget, spellbook, inventory);
		}
	}

	private static boolean shown(Client client, int componentId)
	{
		try
		{
			final Widget w = client.getWidget(componentId);
			return w != null && !w.isHidden();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static boolean focused;

	/** Synthesize a left click at {@code (x,y)} (focus-prime + move + press + release + click). */
	private static void synthClick(Client client, int x, int y)
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
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3, 0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static int[] stonesForRoot(Client client)
	{
		try
		{
			if (!client.isResized())
			{
				return null;
			}
			return client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1 ? STONES_PRE_EOC : STONES_OSRS_STRETCH;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static int safeVarc(Client client)
	{
		try
		{
			return client.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
		}
		catch (RuntimeException ex)
		{
			return -999;
		}
	}

	private static Widget safe(Client client, int id)
	{
		try
		{
			return client.getWidget(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}
}
