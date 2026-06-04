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
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A — the one hard route-back A0 surfaced: <b>minimap walk</b>. A0 established that the clean HUD
 * remover ({@code setHidden}) makes a widget un-clickable, and chat/orbs route-back is solvable (native filter /
 * cover-in-place), but a native minimap needs to walk the player to an arbitrary (possibly off-screen) world
 * point — and OSRS exposes no "walk to WorldPoint" API; walking off-screen is a minimap click. So the design
 * question is: can a native minimap route-back by synth-clicking the <em>live</em> engine minimap (kept alive and
 * covered by the native draw, NOT hidden)?
 *
 * <p>This probe answers it on desktop with two tests, each on a stationary player:
 * <ol>
 *   <li><b>VISIBLE</b> — minimap live: synth-click a point offset from the minimap centre; does
 *       {@code getLocalDestinationLocation()} go non-null (a walk was issued)? Expected YES.</li>
 *   <li><b>HIDDEN</b> — {@code setHidden} the minimap, click the SAME pixel; expected NO walk (confirms the
 *       minimap can't be hidden if we want click-through walk — it must stay live + covered).</li>
 * </ol>
 * GREEN (visibleWalks &amp;&amp; !hiddenWalks) ⇒ native minimap walk = keep the engine minimap LIVE, cover it
 * natively, and translate native-minimap taps to engine-minimap pixels. Mutates (clicks + hides); desktop de-risk
 * only. Inert unless {@code -Drunelite.minimapwalk=true}. The {@code [minimapwalk] MINIMAPWALK …} line is the gate.
 */
@Slf4j
public final class MinimapWalkProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.minimapwalk");

	private static final int IDLE_STABLE = 8;     // consecutive idle (no destination) frames before a test click
	private static final int READ_DELAY = 10;     // frames after a click before reading the destination
	private static final int OFFSET = 12;         // px from minimap centre to click (a few tiles at default zoom)
	// Click directions tried in turn until one issues a walk (some offsets land on unwalkable tiles).
	private static final int[][] DIRS = {{0, -OFFSET}, {OFFSET, 0}, {0, OFFSET}, {-OFFSET, 0}, {OFFSET, -OFFSET}, {-OFFSET, OFFSET}};

	private enum Phase { WAIT, IDLE_V, CLICK_V, READ_V, IDLE_H, CLICK_H, READ_H, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int idleRun;
	private static int attempt;
	private static boolean verdictEmitted;
	private static boolean focused;

	private static int cx, cy;            // minimap centre (canvas px)
	private static int clickX, clickY;    // the pixel of the successful visible click (reused for the hidden test)
	private static int[] minimapBounds;
	private static String rootName = "?";
	private static boolean visibleWalked;
	private static boolean hiddenWalked;
	private static int destX = Integer.MIN_VALUE, destY = Integer.MIN_VALUE;

	private MinimapWalkProbe()
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
			if ((phaseFrame++ % 20) == 5)
			{
				synthClick(c, c.getCanvasWidth() / 2, c.getCanvasHeight() / 2);
			}
			return;
		}

		switch (phase)
		{
			case WAIT:
				if (resolveMinimap(c))
				{
					log.info("[minimapwalk] in-world root={} minimapBounds=[{},{},{},{}] centre={},{}",
						rootName, minimapBounds[0], minimapBounds[1], minimapBounds[2], minimapBounds[3], cx, cy);
					go(Phase.IDLE_V);
				}
				break;

			case IDLE_V:
				if (idle(c))
				{
					go(Phase.CLICK_V);
				}
				break;

			case CLICK_V:
			{
				final int[] d = DIRS[Math.min(attempt, DIRS.length - 1)];
				clickX = cx + d[0];
				clickY = cy + d[1];
				synthClick(c, clickX, clickY);
				log.info("[minimapwalk] VISIBLE attempt {} click {},{} (centre {},{})", attempt, clickX, clickY, cx, cy);
				go(Phase.READ_V);
				break;
			}

			case READ_V:
				if (phaseFrame >= READ_DELAY)
				{
					final LocalPoint dest = safeDest(c);
					if (dest != null)
					{
						visibleWalked = true;
						destX = dest.getX();
						destY = dest.getY();
						log.info("[minimapwalk] VISIBLE walked=true dest=({},{})", destX, destY);
						go(Phase.IDLE_H);
					}
					else if (attempt + 1 < DIRS.length)
					{
						attempt++;
						go(Phase.CLICK_V);
					}
					else
					{
						log.info("[minimapwalk] VISIBLE walked=false after {} attempts (no walkable offset?)", attempt + 1);
						go(Phase.DONE);
						emit();
					}
				}
				break;

			case IDLE_H:
				if (idle(c))
				{
					go(Phase.CLICK_H);
				}
				break;

			case CLICK_H:
				setHidden(c, true);
				// give the hide a frame to take effect, then click the same pixel that worked while visible
				if (phaseFrame >= 3)
				{
					synthClick(c, clickX, clickY);
					log.info("[minimapwalk] HIDDEN click {},{} (minimap hidden)", clickX, clickY);
					go(Phase.READ_H);
				}
				break;

			case READ_H:
				setHidden(c, true);   // keep it hidden across the read window
				if (phaseFrame >= READ_DELAY)
				{
					final LocalPoint dest = safeDest(c);
					hiddenWalked = dest != null;
					log.info("[minimapwalk] HIDDEN walked={}", hiddenWalked);
					setHidden(c, false);
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
		final String concl = (visibleWalked && !hiddenWalked) ? "live-minimap-clickthrough"
			: visibleWalked ? "both-walk-unexpected" : "no-walk";
		log.info("[minimapwalk] MINIMAPWALK root={} minimapBounds=[{},{},{},{}] centre={},{} clickPt={},{} "
				+ "visibleWalked={} hiddenWalked={} destScene=({},{}) conclusion={}",
			rootName, minimapBounds[0], minimapBounds[1], minimapBounds[2], minimapBounds[3], cx, cy, clickX, clickY,
			visibleWalked, hiddenWalked, destX, destY, concl);
	}

	private static boolean idle(Client c)
	{
		if (safeDest(c) == null)
		{
			idleRun++;
		}
		else
		{
			idleRun = 0;
		}
		return idleRun >= IDLE_STABLE;
	}

	private static void go(Phase next)
	{
		phase = next;
		phaseFrame = 0;
		idleRun = 0;
	}

	private static boolean resolveMinimap(Client c)
	{
		try
		{
			if (!c.isResized())
			{
				rootName = "FIXED(unsupported)";
				return false;
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int id = preEoc ? InterfaceID.ToplevelPreEoc.MAP_MINIMAP : InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP;
			rootName = preEoc ? "PreEoc" : "OsrsStretch";
			final Widget w = c.getWidget(id);
			if (w == null || w.isHidden())
			{
				return false;
			}
			final Rectangle b = w.getBounds();
			if (b == null || b.width <= 0 || b.height <= 0)
			{
				return false;
			}
			minimapBounds = new int[]{b.x, b.y, b.width, b.height};
			cx = b.x + b.width / 2;
			cy = b.y + b.height / 2;
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void setHidden(Client c, boolean hidden)
	{
		try
		{
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int id = preEoc ? InterfaceID.ToplevelPreEoc.MAP_MINIMAP : InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP;
			final Widget w = c.getWidget(id);
			if (w != null)
			{
				w.setHidden(hidden);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static LocalPoint safeDest(Client c)
	{
		try
		{
			return c.getLocalDestinationLocation();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
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
