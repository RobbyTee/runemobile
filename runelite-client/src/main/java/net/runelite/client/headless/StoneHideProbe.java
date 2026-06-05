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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A (A4) — how do we make the engine tab stones DISAPPEAR? setHidden is known not to stick for the
 * stones (they are "pinned" / re-shown — the project previously used opaque covers because of this). Now that tabs
 * can be opened by script (TabScriptProbe), the stones no longer need to be clickable, so we can try to push them
 * OFF-SCREEN with a forced position (which DOES stick for the minimap/chat). Two captures:
 * <ol>
 *   <li><b>hidden</b> — setHidden(true) on all stones (re-applied) → does the bottom row vanish? (expected NO).</li>
 *   <li><b>offscreen</b> — forced-position all stones far off the right/bottom edge → does the row vanish, or does
 *       the forced position get CLAMPED to the parent (still visible)?</li>
 * </ol>
 * The PNGs are the ground truth. Mutates positions / hidden; desktop de-risk only. Inert unless
 * {@code -Drunelite.stonehide=true}. The {@code [stonehide] STONEHIDE …} line records the resulting bounds.
 */
@Slf4j
public final class StoneHideProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.stonehide");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.stonehide.dir");
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

	private static final int SETTLE = 22;

	private enum Phase { WAIT, HIDE, CAP_HIDE, SHOW, OFF, CAP_OFF, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int welcomeFrame;
	private static boolean focused;
	private static boolean verdictEmitted;

	private static int[] stones;
	private static String rootName = "?";
	private static int offX, offY;

	private StoneHideProbe()
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
				synthClickCentre(c);
			}
			return;
		}

		switch (phase)
		{
			case WAIT:
				if (resolve(c))
				{
					offX = c.getCanvasWidth() + 200;     // far off the right edge
					offY = c.getCanvasHeight() + 200;     // far off the bottom edge
					go(Phase.HIDE);
				}
				break;

			case HIDE:
				setAllHidden(c, true);
				if (phaseFrame >= SETTLE)
				{
					capture(c, "setHidden");
					go(Phase.CAP_HIDE);
				}
				break;
			case CAP_HIDE:
				setAllHidden(c, false);
				go(Phase.SHOW);
				break;
			case SHOW:
				if (phaseFrame >= 4)
				{
					go(Phase.OFF);
				}
				break;
			case OFF:
				relocateAllOff(c);
				if (phaseFrame >= SETTLE)
				{
					capture(c, "offscreen");
					emit(c);
					go(Phase.DONE);
				}
				break;
			default:
				break;
		}

		phaseFrame++;
	}

	private static void emit(Client c)
	{
		if (verdictEmitted)
		{
			return;
		}
		verdictEmitted = true;
		// Report the resulting bounds of a couple of stones — if x/y are at offX/offY the forced move stuck; if they
		// are back near the bottom, it was clamped. The PNGs (setHidden / offscreen) are the visual ground truth.
		final Rectangle b0 = boundsOf(c, stones[0]);
		final Rectangle b6 = boundsOf(c, stones[6]);
		log.info("[stonehide] STONEHIDE root={} target=off({},{}) stone0={} stone6={} canvas={}x{} (PNGs are ground truth)",
			rootName, offX, offY, rect(b0), rect(b6), c.getCanvasWidth(), c.getCanvasHeight());
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
			return boundsOf(c, stones[0]) != null;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void setAllHidden(Client c, boolean hidden)
	{
		for (final int id : stones)
		{
			try
			{
				final Widget w = c.getWidget(id);
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

	private static void relocateAllOff(Client c)
	{
		for (final int id : stones)
		{
			try
			{
				final Widget w = c.getWidget(id);
				if (w == null)
				{
					continue;
				}
				int px = 0, py = 0;
				final Widget p = w.getParent();
				if (p != null)
				{
					final net.runelite.api.Point pl = p.getCanvasLocation();
					if (pl != null)
					{
						px = pl.getX();
						py = pl.getY();
					}
				}
				w.setForcedPosition(offX - px, offY - py);
				w.revalidate();
			}
			catch (RuntimeException ignore)
			{
			}
		}
	}

	private static Rectangle boundsOf(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
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

	private static String rect(Rectangle b)
	{
		return b == null ? "null" : "[" + b.x + "," + b.y + "," + b.width + "," + b.height + "]";
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
			final File out = new File(DIR, String.format("stonehide-%s-%dx%d.png", mode, w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[stonehide] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[stonehide] capture failed: {}", String.valueOf(ex));
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
