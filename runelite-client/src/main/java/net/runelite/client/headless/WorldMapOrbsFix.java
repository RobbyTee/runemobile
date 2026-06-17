/*
 * Copyright (c) 2024, RuneLite mobile port.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package net.runelite.client.headless;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * Productized world-map open fix for accounts with the old "Data orbs" setting OFF.
 *
 * <p>Root cause (diagnosed by {@code WorldMapProbe}'s FIX MODE): when {@code ORBS_DISABLED} (varbit 4084) is
 * set, the SERVER never opens the Orbs interface (group 160) at login. The mobile chrome fakes that interface
 * open client-side ({@code HudVeneerBridge.ensureOrbsLoaded}) so the world-map orb renders and its tap op is
 * <i>consumed</i> — but the op is a server-bound {@code if_button} on an interface node the server never opened,
 * so it is silently discarded and the map never appears. The world-map orb is the only open path, so on these
 * accounts the map is completely unreachable.
 *
 * <p>Fix: enable "Data orbs" through the REAL settings UI. The settings side panel and the All Settings window
 * are server-opened, so their buttons send valid {@code if_button}s; clicking the "Data orbs" toggle flips
 * {@code ORBS_DISABLED} to 0 and the server PERSISTS it on the account. After that the server opens the orbs
 * interface and the world-map orb works (this session and every future login).
 *
 * <p>STATUS (groundwork — NOT yet a working auto-fix): the detection + the settings-search + toggle + verify
 * logic are productized and the root cause is confirmed, but auto-OPENING the settings UI from the shared core
 * is blocked in the mobile chrome. {@code SettingsSide.SETTINGS_OPEN} is a PHANTOM widget that reports bounds at
 * the off-screen logical origin (~25,228) even while the panel is closed; the chrome render-relocates the
 * displayed panel to the right via {@code setForcedPosition} WITHOUT updating that widget's {@code getBounds};
 * and replaying the settings tab op toggles the phantom to hidden. So the shared core cannot reliably locate or
 * click the "All Settings" button, and a synthetic click on it never opened the server-side window in testing
 * (the same wall {@code WorldMapProbe}'s FIX MODE hit, where it fell back to a manual open). Cracking it needs
 * either a chrome change that makes the engine settings panel reliably clickable, or a non-UI way to flip the
 * server setting.
 *
 * <p>Gated DORMANT behind {@code -Drunelite.orbsfix=true} (NOT set by the app) so it does nothing by default —
 * arm it explicitly to resume the investigation. Inert on desktop RuneLite either way. The state machine + widget
 * steps mirror {@code WorldMapProbe}'s FIX MODE with the diagnostic capture stripped.
 */
@Slf4j
public final class WorldMapOrbsFix
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.orbsfix");

	/** The settings (wrench) side-panel tab index — confirmed via the native grid ("native tab 11 -> openTab"). */
	private static final int SETTINGS_TAB = 11;

	private enum Phase
	{
		SETTLE,   // wait for the client + varbit to settle, then decide whether a fix is needed
		OPEN,     // reach the settings side panel (replay stones) until the "All Settings" button shows
		OPENBTN,  // hover then real-click "All Settings" on a later frame (server opens the window)
		WINDOW,   // wait for the All Settings window, focus its search field
		TYPE,     // type "orb" to filter the settings list
		FIND,     // find + click the "Data orbs" row
		VERIFY,   // confirm ORBS_DISABLED flipped to 0, then close the window
		DONE,     // finished for this login session (success, give-up, or not-needed)
	}

	private static Phase phase = Phase.SETTLE;
	private static int frame;
	private static GameState last;
	private static boolean focused;
	private static Rectangle openBtnRect;
	private static boolean openOpTried;
	private static Rectangle toggleRect;
	private static boolean retried;

	private WorldMapOrbsFix()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client c, GameState state)
	{
		if (c == null)
		{
			return;
		}
		// Re-arm on every fresh login so a failed attempt retries next session; a success self-disables because
		// the server-persisted varbit reads 0 on the next SETTLE. Reset focus so the one-time FOCUS_GAINED fires.
		if (state != last)
		{
			if (state == GameState.LOGGED_IN)
			{
				phase = Phase.SETTLE;
				frame = 0;
				openBtnRect = null;
				openOpTried = false;
				toggleRect = null;
				retried = false;
				focused = false;
			}
			last = state;
		}
		if (state != GameState.LOGGED_IN || phase == Phase.DONE)
		{
			return;
		}
		frame++;
		step(c);
	}

	private static void step(Client c)
	{
		switch (phase)
		{
			case SETTLE:
			{
				// Let the client settle so the varbit + widgets are valid before deciding.
				if (frame < 60)
				{
					return;
				}
				final int v = varbit(c, VarbitID.ORBS_DISABLED);
				if (v == 0)
				{
					done("Data orbs already enabled — world map reachable, nothing to do");
					return;
				}
				if (v < 0)
				{
					if (frame >= 300)
					{
						done("ORBS_DISABLED varbit unreadable");
					}
					return;
				}
				log.info("[orbsfix] Data orbs OFF (ORBS_DISABLED={}) — world-map orb is dead on this account; "
					+ "enabling via the settings UI", v);
				go(Phase.OPEN);
				break;
			}

			case OPEN:
			{
				if (bounds(c, InterfaceID.Settings.UNIVERSE) != null)
				{
					// The All Settings window is already up (opened by hand) — skip straight to it.
					go(Phase.WINDOW);
					break;
				}
				// SETTINGS_OPEN exists as a PHANTOM at the logical origin (~25,228) even while the settings tab is
				// closed, so its mere presence does NOT mean the panel is displayed. ALWAYS drive the chrome's
				// openTab flow (it consumes pendingOpenTab in its tick and displays + relocates the panel) and wait
				// until the button's bounds actually move into the on-screen relocated region before clicking.
				if (frame == 6 || frame == 60)
				{
					HudVeneerBridge.openTab(SETTINGS_TAB);
				}
				final Rectangle b = bounds(c, InterfaceID.SettingsSide.SETTINGS_OPEN);
				if (frame == 20 || frame == 70 || frame == 130)
				{
					log.info("[orbsfix] after openTab: SETTINGS_OPEN bounds = {}", b);
				}
				if (b != null && b.x > 800)
				{
					// Displayed + relocated into the visible area — its bounds are now a real click target.
					openBtnRect = b;
					log.info("[orbsfix] settings panel DISPLAYED — All Settings at {}", b);
					synthMove(c, b.x + b.width / 2, b.y + b.height / 2);
					go(Phase.OPENBTN);
					break;
				}
				if (frame >= 220)
				{
					if (b != null)
					{
						// Never relocated into view (getBounds stuck at the logical origin) — try the logical
						// position as a last resort so OPENBTN at least attempts + logs the outcome.
						openBtnRect = b;
						log.info("[orbsfix] settings panel never relocated into view (bounds {}); trying logical", b);
						go(Phase.OPENBTN);
					}
					else
					{
						done("settings side panel never reachable");
					}
				}
				break;
			}

			case OPENBTN:
			{
				if (bounds(c, InterfaceID.Settings.UNIVERSE) != null)
				{
					go(Phase.WINDOW);
					break;
				}
				// The native chrome render-relocates the settings side panel a few frames after it opens (the
				// engine's setForcedPosition + revalidate moves the widget AND its getBounds). Re-read the bounds
				// EVERY frame and keep the cursor hovering on the button as it moves, then click only AFTER the
				// relocate has settled — clicking at the stale pre-relocate position (the first-frame bug) missed.
				final Rectangle b = bounds(c, InterfaceID.SettingsSide.SETTINGS_OPEN);
				if (b != null)
				{
					openBtnRect = b;
				}
				if (openBtnRect == null)
				{
					go(Phase.OPEN);
					break;
				}
				final int cx = openBtnRect.x + openBtnRect.width / 2;
				final int cy = openBtnRect.y + openBtnRect.height / 2;
				if (frame == 20 || frame == 50 || frame == 80)
				{
					log.info("[orbsfix] All Settings bounds @frame {} = {} (click target {},{})",
						frame, openBtnRect, cx, cy);
				}
				synthMove(c, cx, cy);   // keep the hover on the (relocating) button every frame
				if (frame == 30 || frame == 55 || frame == 80)
				{
					synthClick(c, cx, cy);
					log.info("[orbsfix] clicked All Settings @frame {} at {},{}", frame, cx, cy);
				}
				else if (frame == 100 && !openOpTried)
				{
					// Fallback: replay the button's own onOp script directly (in case the open is clientscript-driven).
					openOpTried = true;
					replayWidgetOp(c, InterfaceID.SettingsSide.SETTINGS_OPEN);
					log.info("[orbsfix] All Settings still closed — replayed its onOp as a fallback");
				}
				else if (frame >= 130)
				{
					done("All Settings window never opened after settled clicks + op replay");
				}
				break;
			}

			case WINDOW:
			{
				final Rectangle u = bounds(c, InterfaceID.Settings.UNIVERSE);
				if (u != null)
				{
					final Rectangle s = bounds(c, InterfaceID.Settings.SEARCHBAR_CONTAINER);
					if (s != null)
					{
						synthClick(c, s.x + s.width / 2, s.y + s.height / 2);   // focus the search field
					}
					go(Phase.TYPE);
				}
				else if (frame >= 100)
				{
					done("All Settings window never opened");
				}
				break;
			}

			case TYPE:
				if (frame == 6)
				{
					typeString(c, "orb");   // filters the settings list down to the Data orbs row
				}
				else if (frame >= 18)
				{
					go(Phase.FIND);
				}
				break;

			case FIND:
			{
				if ((frame % 10) == 5)
				{
					toggleRect = findRowByText(c, "data orbs");
					if (toggleRect != null)
					{
						synthClick(c, toggleRect.x + toggleRect.width / 2,
							toggleRect.y + toggleRect.height / 2);
						go(Phase.VERIFY);
						break;
					}
					if (frame >= 90)
					{
						done("no 'Data orbs' row found in the settings window");
					}
				}
				break;
			}

			case VERIFY:
			{
				final int v = varbit(c, VarbitID.ORBS_DISABLED);
				if (v == 0)
				{
					log.info("[orbsfix] SUCCESS — Data orbs enabled (ORBS_DISABLED=0, server-persisted); the "
						+ "world-map orb now opens the map");
					final Rectangle x = bounds(c, InterfaceID.Settings.CLOSE);
					if (x != null)
					{
						synthClick(c, x.x + x.width / 2, x.y + x.height / 2);
					}
					done("enabled");
					break;
				}
				if (frame >= 40 && !retried && toggleRect != null)
				{
					// The row-center click didn't take — the actual checkbox sits at the row's right edge.
					retried = true;
					synthClick(c, toggleRect.x + toggleRect.width - 12, toggleRect.y + toggleRect.height / 2);
				}
				else if (frame >= 120)
				{
					done("ORBS_DISABLED still " + v + " after clicking the toggle");
				}
				break;
			}

			default:
				break;
		}
	}

	private static void done(String why)
	{
		log.info("[orbsfix] done: {}", why);
		go(Phase.DONE);
	}

	private static void go(Phase next)
	{
		phase = next;
		frame = 0;
	}

	// ---- engine helpers (mirror WorldMapProbe's validated shapes) -------------------------------------------

	private static int varbit(Client c, int id)
	{
		try
		{
			return c.getVarbitValue(id);
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static Widget widget(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return (w == null || w.isHidden()) ? null : w;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static Rectangle bounds(Client c, int id)
	{
		final Widget w = widget(c, id);
		if (w == null)
		{
			return null;
		}
		final Rectangle b = w.getBounds();
		return (b == null || b.width <= 0 || b.height <= 0) ? null : b;
	}

	/** Replay an arbitrary widget's onOp script (op 1) — a clientscript-driven fallback for a server if_button. */
	private static void replayWidgetOp(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				return;
			}
			final Object[] op = w.getOnOpListener();
			if (op == null)
			{
				return;
			}
			c.createScriptEventBuilder(op).setSource(w).setOp(1).build().run();
		}
		catch (RuntimeException ex)
		{
			log.debug("[orbsfix] widget {} op replay threw {}", id, String.valueOf(ex));
		}
	}

	/** Type committed text into the engine — per char PRESSED + TYPED + RELEASED (the JavaBridge.onText shape). */
	private static void typeString(Client c, String s)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		long t = System.currentTimeMillis();
		for (int i = 0; i < s.length(); i++)
		{
			final char ch = s.charAt(i);
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
		}
	}

	/** Find the settings row whose text contains {@code needle} (case-insensitive); returns its bounds. */
	private static Rectangle findRowByText(Client c, String needle)
	{
		try
		{
			return scanForText(c.getWidget(InterfaceID.Settings.UNIVERSE), needle.toLowerCase(), 0);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static Rectangle scanForText(Widget w, String needle, int depth)
	{
		if (w == null || depth > 5)
		{
			return null;
		}
		try
		{
			final String text = w.getText();
			if (text != null && text.toLowerCase().contains(needle))
			{
				final Rectangle b = w.getBounds();
				if (b != null && b.width > 0 && b.height > 0)
				{
					return b;
				}
			}
			final Widget[][] kids = {w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
			for (final Widget[] arr : kids)
			{
				if (arr == null)
				{
					continue;
				}
				for (final Widget k : arr)
				{
					final Rectangle r = scanForText(k, needle, depth + 1);
					if (r != null)
					{
						return r;
					}
				}
			}
		}
		catch (RuntimeException ignore)
		{
		}
		return null;
	}

	private static void synthMove(Client c, int x, int y)
	{
		final Canvas canvas = c.getCanvas();
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

	private static void synthClick(Client c, int x, int y)
	{
		final Canvas canvas = c.getCanvas();
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
}
