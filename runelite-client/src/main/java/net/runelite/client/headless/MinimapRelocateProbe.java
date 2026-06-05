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
import java.awt.image.BufferedImage;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A (A4) decision probe — can the engine's OWN minimap be RELOCATED (to an inset position over the
 * 3D scene) and still (a) render there (not occluded by the viewport) AND (b) stay click-to-walk? This is the
 * A3-style "relocate, don't redraw" question applied to the minimap.
 *
 * <p>Background: A0 + {@link MinimapWalkProbe} settled that a {@code setHidden} minimap is un-clickable (the walk
 * dies), so the original A4 plan assumed <em>cover-in-place</em> — keep the engine minimap live at top-right,
 * cover it with a native draw, and proxy walk-clicks. That requires drawing the minimap (terrain from
 * {@code getMapScene}, dots, compass) <em>natively</em> — the hard part. But {@link ChatRelocateProbe} then proved
 * the engine's chatbox can be {@code setForcedPosition}'d to the top and renders there (not occluded). The minimap
 * relocate case was never tested. If the minimap relocates the same way, A4 collapses to the cheap path: move the
 * engine's pixel-perfect minimap (real terrain/dots/compass) to where we want it, and it stays click-to-walk for
 * free (it IS the live engine minimap) — no native terrain rendering at all.
 *
 * <p>Each frame in-world it {@code setForcedPosition}s {@code MAP_MINIMAP} (the globe widget that renders the map
 * and receives walk-clicks) to a top-left inset (clearly different from the default top-right, unambiguously over
 * the 3D scene) and revalidates, then: captures a PNG (ground truth for "does it render there / is it occluded"),
 * and on a stationary player synth-clicks an offset from the RELOCATED minimap centre to check
 * {@code getLocalDestinationLocation()} goes non-null (a walk was issued AT THE NEW POSITION). Mutates widget
 * position + clicks; desktop de-risk only. Inert unless {@code -Drunelite.minimapreloc=true}.
 *
 * <p>GREEN ({@code moved && walked}, PNG confirms render) ⇒ A4 = relocate the engine minimap (HudVeneerBridge),
 * not native terrain redraw. RED (occluded / didn't move / didn't walk) ⇒ fall back to cover-in-place native draw.
 * The {@code [minimapreloc] MINIMAPRELOC …} line is the gate.
 */
@Slf4j
public final class MinimapRelocateProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.minimapreloc");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.minimapreloc.dir");
		if (dir == null || dir.isEmpty())
		{
			dir = System.getProperty("runelite.headless.capture.dir");
		}
		DIR = (dir == null || dir.isEmpty()) ? null : new File(dir);
		if (ENABLED && DIR != null)
		{
			//noinspection ResultOfMethodCallIgnored
			DIR.mkdirs();
		}
	}

	private static final int INSET = 24;          // top-left target inset (clearly off the default top-right corner)
	private static final int F_SETTLE = 25;        // frames to let the forced position + render settle before capture
	private static final int IDLE_STABLE = 8;      // consecutive idle (no destination) frames before a test click
	private static final int READ_DELAY = 10;      // frames after a click before reading the destination
	private static final int OFFSET = 12;          // px from the relocated minimap centre to click (a few tiles)
	// Click directions tried in turn until one issues a walk (some offsets land on unwalkable tiles).
	private static final int[][] DIRS = {{0, -OFFSET}, {OFFSET, 0}, {0, OFFSET}, {-OFFSET, 0}, {OFFSET, -OFFSET}, {-OFFSET, OFFSET}};

	private enum Phase { WAIT, SETTLE, IDLE, CLICK, READ, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int idleRun;
	private static int attempt;
	private static boolean verdictEmitted;
	private static boolean focused;
	private static boolean dumped;

	private static int containerId;           // MAP_CONTAINER — root-anchored, so setForcedPosition is ~canvas-absolute
	private static int minimapId;             // MAP_MINIMAP — the globe child (renders the map, takes walk-clicks)
	private static String rootName = "?";
	private static int targetX, targetY;
	private static int[] origBounds;          // MAP_CONTAINER bounds before the relocate
	private static int[] nowBounds;           // MAP_CONTAINER bounds after the relocate (read at verdict)
	private static int clickX, clickY;
	private static boolean walked;
	private static int destX = Integer.MIN_VALUE, destY = Integer.MIN_VALUE;

	private MinimapRelocateProbe()
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

		if (phase == Phase.WAIT)
		{
			if (!resolveMinimap(c))
			{
				return;
			}
			dumpAssemblyOnce(c);
			go(Phase.SETTLE);
			log.info("[minimapreloc] in-world root={} minimapOrigBounds={} -> forcing to {},{}",
				rootName, rect(origBounds), targetX, targetY);
		}

		// Re-apply the forced position every frame (survive TOPLEVEL_REDRAW), then revalidate so the globe follows.
		forceMinimap(c);

		switch (phase)
		{
			case SETTLE:
				if (phaseFrame >= F_SETTLE)
				{
					capture(c, "minimap-relocated");
					go(Phase.IDLE);
				}
				break;

			case IDLE:
				if (idle(c) && currentBounds(c) != null)
				{
					go(Phase.CLICK);
				}
				break;

			case CLICK:
			{
				final Rectangle b = currentBounds(c);
				if (b == null)
				{
					go(Phase.IDLE);
					break;
				}
				final int cx = b.x + b.width / 2;
				final int cy = b.y + b.height / 2;
				final int[] d = DIRS[Math.min(attempt, DIRS.length - 1)];
				clickX = cx + d[0];
				clickY = cy + d[1];
				synthClick(c, clickX, clickY);
				log.info("[minimapreloc] CLICK attempt {} at {},{} (relocated centre {},{})", attempt, clickX, clickY, cx, cy);
				go(Phase.READ);
				break;
			}

			case READ:
				if (phaseFrame >= READ_DELAY)
				{
					final LocalPoint dest = safeDest(c);
					if (dest != null)
					{
						walked = true;
						destX = dest.getX();
						destY = dest.getY();
						log.info("[minimapreloc] walked=true dest=({},{})", destX, destY);
						finish(c);
					}
					else if (attempt + 1 < DIRS.length)
					{
						attempt++;
						go(Phase.CLICK);
					}
					else
					{
						log.info("[minimapreloc] walked=false after {} attempts (no walkable offset, or relocated minimap not clickable)", attempt + 1);
						finish(c);
					}
				}
				break;

			default:
				break;
		}

		phaseFrame++;
	}

	private static void finish(Client c)
	{
		final Rectangle nb = boundsOf(c, containerId);   // the relocated CONTAINER — moved check is against the target
		nowBounds = nb == null ? null : new int[]{nb.x, nb.y, nb.width, nb.height};
		go(Phase.DONE);
		emit();
	}

	private static void emit()
	{
		if (verdictEmitted)
		{
			return;
		}
		verdictEmitted = true;
		// "moved" = MAP_MINIMAP's reported canvas bounds are now AT the target. The PNG confirms it renders there.
		final boolean moved = nowBounds != null
			&& Math.abs(nowBounds[0] - targetX) <= 8 && Math.abs(nowBounds[1] - targetY) <= 8;
		final String concl = (moved && walked) ? "relocate-clickwalk"
			: moved ? "moved-but-no-walk"
			: walked ? "walk-but-not-at-target"
			: "no-move-no-walk";
		log.info("[minimapreloc] MINIMAPRELOC root={} origBounds={} target={},{} nowBounds={} moved={} clickPt={},{} "
				+ "walked={} destScene=({},{}) conclusion={} (PNG = whether it renders at the target)",
			rootName, rect(origBounds), targetX, targetY, rect(nowBounds), moved, clickX, clickY,
			walked, destX, destY, concl);
	}

	// ---- internals ----------------------------------------------------------------------------------------

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
			containerId = preEoc ? InterfaceID.ToplevelPreEoc.MAP_CONTAINER : InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER;
			minimapId = preEoc ? InterfaceID.ToplevelPreEoc.MAP_MINIMAP : InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP;
			rootName = preEoc ? "PreEoc" : "OsrsStretch";
			// We relocate the CONTAINER (root-anchored → setForcedPosition is ~canvas-absolute) so the whole
			// minimap+orbs cluster travels as one unit. The globe child (MAP_MINIMAP) follows on revalidate and is
			// what we click for the walk test.
			final Rectangle b = boundsOf(c, containerId);
			final Rectangle mm = boundsOf(c, minimapId);
			if (b == null || b.width <= 0 || b.height <= 0 || mm == null)
			{
				return false;
			}
			origBounds = new int[]{b.x, b.y, b.width, b.height};
			targetX = INSET;
			targetY = INSET;
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void forceMinimap(Client c)
	{
		try
		{
			final Widget w = c.getWidget(containerId);
			if (w != null)
			{
				w.setForcedPosition(targetX, targetY);
				w.revalidate();   // cascade to the globe + orbs children
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** The globe child's current canvas bounds — where the walk-click must land. */
	private static Rectangle currentBounds(Client c)
	{
		return boundsOf(c, minimapId);
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

	private static Rectangle boundsOf(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null || w.isHidden())
			{
				return null;
			}
			return w.getBounds();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static String rect(int[] b)
	{
		return b == null ? "null" : "[" + b[0] + "," + b[1] + "," + b[2] + "," + b[3] + "]";
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

	// One-time dump of the minimap assembly geometry (container/minimap/orbs), so A4 can plan the real relocate.
	private static void dumpAssemblyOnce(Client c)
	{
		if (dumped)
		{
			return;
		}
		dumped = true;
		final boolean preEoc = rootName.equals("PreEoc");
		dumpOne(c, "MAP_CONTAINER", preEoc ? InterfaceID.ToplevelPreEoc.MAP_CONTAINER : InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER);
		dumpOne(c, "MAP_MINIMAP", minimapId);
		dumpOne(c, "ORBS", preEoc ? InterfaceID.ToplevelPreEoc.ORBS : InterfaceID.ToplevelOsrsStretch.ORBS);
	}

	private static void dumpOne(Client c, String name, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[minimapreloc] assembly {} = null", name);
				return;
			}
			final Rectangle b = w.getBounds();
			log.info("[minimapreloc] assembly {} bounds={} orig=({},{},{}x{}) relX={} relY={} hidden={}",
				name, b, w.getOriginalX(), w.getOriginalY(), w.getOriginalWidth(), w.getOriginalHeight(),
				w.getRelativeX(), w.getRelativeY(), w.isHidden());
		}
		catch (RuntimeException ex)
		{
			log.info("[minimapreloc] assembly {} threw {}", name, String.valueOf(ex));
		}
	}

	private static void capture(Client c, String mode)
	{
		if (DIR == null)
		{
			return;
		}
		try
		{
			final BufferProvider bp = c.getBufferProvider();
			final int[] px = bp.getPixels();
			final int w = bp.getWidth();
			final int h = bp.getHeight();
			if (px == null || w <= 0 || h <= 0 || px.length < w * h)
			{
				return;
			}
			final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, w, h, px, 0, w);
			final File out = new File(DIR, String.format("minimapreloc-%s-%dx%d.png", mode, w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[minimapreloc] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[minimapreloc] capture failed: {}", String.valueOf(ex));
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
