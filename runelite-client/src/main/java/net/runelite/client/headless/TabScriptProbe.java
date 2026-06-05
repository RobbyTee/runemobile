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
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A (A4) decision probe — can a tab be opened by REPLAYING the stone's own op script
 * ({@code client.runScript(stone.getOnOpListener())}) instead of synth-clicking the stone? If yes, the engine tab
 * stones can be {@code setHidden} (removing the gray cover patch the native 2-col grid otherwise needs) while the
 * native grid drives tab switching through the script directly — no covers, no proxy-click on a live stone.
 *
 * <p>Tests, on the client thread:
 * <ol>
 *   <li><b>replay</b> — {@code runScript} of two different stones' op listeners changes {@code activeTab}
 *       ({@code VarClientID.TOPLEVEL_PANEL}) to two different values (i.e. the script opens the tab).</li>
 *   <li><b>worksHidden</b> — after {@code setHidden(true)} on ALL stones, replaying still switches tabs (so the
 *       stones can be hidden and the gray box removed).</li>
 * </ol>
 * The op listeners are read + cached BEFORE hiding (they are plain data; the script runs regardless of widget
 * visibility). Mutates varcs (tab state) + hides stones; desktop de-risk only. Inert unless
 * {@code -Drunelite.tabscript=true}. The {@code [tabscript] TABSCRIPT …} line is the gate.
 */
@Slf4j
public final class TabScriptProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.tabscript");

	private static final int DELAY = 6;   // frames between a runScript and reading activeTab

	// Stones to exercise (engine tab indices): inventory(3) & prayer(5) for the visible test; combat(0) & magic(6)
	// for the hidden test — four distinct tabs so activeTab is expected to change between each.
	private static final int A = 3, B = 5, C = 0, D = 6;

	private enum Phase { WAIT, SCRIPT_A, READ_A, SCRIPT_B, READ_B, HIDE, SCRIPT_C, READ_C, SCRIPT_D, READ_D, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int welcomeFrame;
	private static boolean focused;
	private static boolean verdictEmitted;

	private static int[] stones;
	private static String rootName = "?";
	private static final Object[][] onOp = new Object[14][];
	private static int vA, vB, vC, vD;
	private static boolean readOk;

	private TabScriptProbe()
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

		if (welcomeUp(c))
		{
			if ((welcomeFrame++ % 20) == 5)
			{
				synthClickCentre(c);   // dismiss the "click to play" welcome screen
			}
			return;
		}

		switch (phase)
		{
			case WAIT:
				if (resolve(c))
				{
					readListeners(c);
					go(Phase.SCRIPT_A);
				}
				break;

			case SCRIPT_A:
				runOp(c, A);
				go(Phase.READ_A);
				break;
			case READ_A:
				if (phaseFrame >= DELAY)
				{
					vA = activeTab(c);
					go(Phase.SCRIPT_B);
				}
				break;
			case SCRIPT_B:
				runOp(c, B);
				go(Phase.READ_B);
				break;
			case READ_B:
				if (phaseFrame >= DELAY)
				{
					vB = activeTab(c);
					go(Phase.HIDE);
				}
				break;

			case HIDE:
				setAllHidden(c, true);
				if (phaseFrame >= 3)
				{
					go(Phase.SCRIPT_C);
				}
				break;
			case SCRIPT_C:
				setAllHidden(c, true);
				runOp(c, C);
				go(Phase.READ_C);
				break;
			case READ_C:
				setAllHidden(c, true);
				if (phaseFrame >= DELAY)
				{
					vC = activeTab(c);
					go(Phase.SCRIPT_D);
				}
				break;
			case SCRIPT_D:
				setAllHidden(c, true);
				runOp(c, D);
				go(Phase.READ_D);
				break;
			case READ_D:
				setAllHidden(c, true);
				if (phaseFrame >= DELAY)
				{
					vD = activeTab(c);
					setAllHidden(c, false);   // restore
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
		final boolean replay = readOk && vA != Integer.MIN_VALUE && vB != Integer.MIN_VALUE && vA != vB;
		final boolean worksHidden = vC != Integer.MIN_VALUE && vD != Integer.MIN_VALUE && vC != vD;
		final String concl = (replay && worksHidden) ? "script-open-hide-ok"
			: !replay ? "replay-failed" : "fails-when-hidden";
		log.info("[tabscript] TABSCRIPT root={} replay={} worksHidden={} vA={} vB={} vC={} vD={} onOpA={} conclusion={}",
			rootName, replay, worksHidden, vA, vB, vC, vD,
			onOp[A] == null ? "null" : Arrays.toString(onOp[A]), concl);
	}

	// ---- internals ----------------------------------------------------------------------------------------

	private static boolean resolve(Client c)
	{
		try
		{
			if (!c.isResized())
			{
				rootName = "FIXED(unsupported)";
				return false;
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			rootName = preEoc ? "PreEoc" : "OsrsStretch";
			stones = preEoc ? new int[]{
				InterfaceID.ToplevelPreEoc.STONE0, InterfaceID.ToplevelPreEoc.STONE1, InterfaceID.ToplevelPreEoc.STONE2,
				InterfaceID.ToplevelPreEoc.STONE3, InterfaceID.ToplevelPreEoc.STONE4, InterfaceID.ToplevelPreEoc.STONE5,
				InterfaceID.ToplevelPreEoc.STONE6, InterfaceID.ToplevelPreEoc.STONE7, InterfaceID.ToplevelPreEoc.STONE8,
				InterfaceID.ToplevelPreEoc.STONE9, InterfaceID.ToplevelPreEoc.STONE10, InterfaceID.ToplevelPreEoc.STONE11,
				InterfaceID.ToplevelPreEoc.STONE12, InterfaceID.ToplevelPreEoc.STONE13,
			} : new int[]{
				InterfaceID.ToplevelOsrsStretch.STONE0, InterfaceID.ToplevelOsrsStretch.STONE1, InterfaceID.ToplevelOsrsStretch.STONE2,
				InterfaceID.ToplevelOsrsStretch.STONE3, InterfaceID.ToplevelOsrsStretch.STONE4, InterfaceID.ToplevelOsrsStretch.STONE5,
				InterfaceID.ToplevelOsrsStretch.STONE6, InterfaceID.ToplevelOsrsStretch.STONE7, InterfaceID.ToplevelOsrsStretch.STONE8,
				InterfaceID.ToplevelOsrsStretch.STONE9, InterfaceID.ToplevelOsrsStretch.STONE10, InterfaceID.ToplevelOsrsStretch.STONE11,
				InterfaceID.ToplevelOsrsStretch.STONE12, InterfaceID.ToplevelOsrsStretch.STONE13,
			};
			return c.getWidget(stones[A]) != null;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void readListeners(Client c)
	{
		readOk = true;
		for (int i = 0; i < 14; i++)
		{
			try
			{
				final Widget w = c.getWidget(stones[i]);
				onOp[i] = w == null ? null : w.getOnOpListener();
			}
			catch (RuntimeException ex)
			{
				onOp[i] = null;
			}
		}
		for (int idx : new int[]{A, B, C, D})
		{
			if (onOp[idx] == null)
			{
				readOk = false;
			}
		}
		log.info("[tabscript] in-world root={} onOp[A=inv]={} onOp[C=combat]={}",
			rootName, onOp[A] == null ? "null" : Arrays.toString(onOp[A]),
			onOp[C] == null ? "null" : Arrays.toString(onOp[C]));
	}

	private static void runOp(Client c, int i)
	{
		try
		{
			final Widget w = c.getWidget(stones[i]);
			if (w == null || onOp[i] == null)
			{
				return;
			}
			// Replay the stone's own op script with the stone as the source widget (resolves the CS2 magic args)
			// and op 1 (left-click "open"). This is RuneLite's standard widget-op replay (see BankPlugin).
			c.createScriptEventBuilder(onOp[i]).setSource(w).setOp(1).build().run();
		}
		catch (RuntimeException ex)
		{
			log.info("[tabscript] runScript stone {} threw {}", i, String.valueOf(ex));
		}
	}

	private static void setAllHidden(Client c, boolean hidden)
	{
		for (int i = 0; i < 14; i++)
		{
			try
			{
				final Widget w = c.getWidget(stones[i]);
				if (w != null && w.isHidden() != hidden)
				{
					w.setHidden(hidden);
				}
			}
			catch (RuntimeException ignore)
			{
			}
		}
	}

	private static int activeTab(Client c)
	{
		try
		{
			return c.getVarcIntValue(VarClientID.TOPLEVEL_PANEL);
		}
		catch (RuntimeException ex)
		{
			return Integer.MIN_VALUE;
		}
	}

	private static void go(Phase next)
	{
		phase = next;
		phaseFrame = 0;
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

	private static void synthClickCentre(Client c)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final int x = c.getCanvasWidth() / 2;
		final int y = c.getCanvasHeight() / 2;
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
}
