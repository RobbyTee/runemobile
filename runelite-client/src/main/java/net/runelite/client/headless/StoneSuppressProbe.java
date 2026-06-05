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
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A (A4) — find a lever that ACTUALLY removes the engine tab-stone bottom row in STEADY STATE (the
 * engine re-pins the stone widgets every frame, so moving/hiding the stones themselves is overridden; confirmed on
 * device). Untested: their PARENT containers (STONE0-6 share one, STONE7-13 another) and higher ancestors — those
 * may be normal containers that relocate/hide like the chat/minimap do.
 *
 * <p>Each lever is applied CONTINUOUSLY for many frames, then a PNG is captured at the end (steady state — the
 * earlier single-frame probes were flukes that caught the off-state before the re-pin). Levers, in order:
 * <ol>
 *   <li><b>baseline</b> — no suppression (row visible, sanity).</li>
 *   <li><b>parentHidden</b> — {@code setHidden(true)} on the two stone-row parents each frame.</li>
 *   <li><b>parentReloc</b> — force the two parents off-screen each frame.</li>
 *   <li><b>grandparentHidden</b> — {@code setHidden(true)} on the parents' parent.</li>
 * </ol>
 * Also dumps STONE0's full ancestor chain (id/bounds/hidden) once. Mutates widget state; desktop de-risk only.
 * Inert unless {@code -Drunelite.stonesuppress=true}. PNGs (stonesuppress-*.png) are the ground truth.
 */
@Slf4j
public final class StoneSuppressProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.stonesuppress");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.stonesuppress.dir");
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

	private static final int HOLD = 45;   // frames to hold each lever before capturing (steady state)

	private enum Phase { WAIT, BASELINE, P_HIDE, P_RELOC, GP_HIDE, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int welcomeFrame;
	private static boolean focused;
	private static boolean dumped;

	private static int stone0, stone7;
	private static String rootName = "?";
	private static int offX, offY;

	private StoneSuppressProbe()
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

		if (phase == Phase.WAIT)
		{
			if (!resolve(c) || c.getWidget(stone0) == null)
			{
				return;   // wait until the stones are actually built
			}
			offX = c.getCanvasWidth() + 200;
			offY = c.getCanvasHeight() + 200;
			dumpAncestryOnce(c);
			go(Phase.BASELINE);
		}

		switch (phase)
		{
			case BASELINE:
				if (phaseFrame >= HOLD)
				{
					capture(c, "0-baseline");
					go(Phase.P_HIDE);
				}
				break;

			case P_HIDE:
				setParentsHidden(c, true);
				if (phaseFrame == HOLD - 5)
				{
					// With the parent hidden, can we still read the stone + its op script, and does replaying it
					// switch tabs? (proves hide-parent + openTab is a viable combo). Open the inventory tab (idx 3).
					final Widget s = safeWidget(c, stoneN(c, 3));
					final Object[] op = s == null ? null : safeOnOp(s);
					if (s != null && op != null)
					{
						try
						{
							c.createScriptEventBuilder(op).setSource(s).setOp(1).build().run();
						}
						catch (RuntimeException ignore)
						{
						}
					}
					log.info("[stonesuppress] parentHidden openTab check: stone3Resolves={} onOpPresent={} activeBefore={}",
						s != null, op != null, safeVarc(c));
				}
				if (phaseFrame == HOLD - 2)
				{
					log.info("[stonesuppress] parentHidden openTab result: activeAfter={} (expect 3 = inventory)", safeVarc(c));
				}
				if (phaseFrame >= HOLD)
				{
					capture(c, "1-parentHidden");
					setParentsHidden(c, false);
					go(Phase.P_RELOC);
				}
				break;

			case P_RELOC:
				relocateParentsOff(c);
				if (phaseFrame >= HOLD)
				{
					capture(c, "2-parentReloc");
					resetParents(c);
					go(Phase.GP_HIDE);
				}
				break;

			case GP_HIDE:
				setGrandparentsHidden(c, true);
				if (phaseFrame == HOLD - 5)
				{
					// Confirm openTab still works with the GRANDPARENT hidden: open a tab that is NOT currently
					// active (combat=0 unless already active, else magic=6) and check activeTab follows.
					final int before = safeVarc(c);
					final int target = (before == 0) ? 6 : 0;
					final Widget s = safeWidget(c, stoneN(c, target));
					final Object[] op = s == null ? null : safeOnOp(s);
					if (s != null && op != null)
					{
						try
						{
							c.createScriptEventBuilder(op).setSource(s).setOp(1).build().run();
						}
						catch (RuntimeException ignore)
						{
						}
					}
					log.info("[stonesuppress] grandparentHidden openTab check: stoneResolves={} onOpPresent={} before={} target={}",
						s != null, op != null, before, target);
				}
				if (phaseFrame == HOLD - 2)
				{
					log.info("[stonesuppress] grandparentHidden openTab result: activeAfter={} (should equal target)", safeVarc(c));
				}
				if (phaseFrame >= HOLD)
				{
					capture(c, "3-grandparentHidden");
					setGrandparentsHidden(c, false);
					log.info("[stonesuppress] STONESUPPRESS done root={} — inspect the 4 PNGs (which removed the row)", rootName);
					go(Phase.DONE);
				}
				break;

			default:
				break;
		}

		phaseFrame++;
	}

	// ---- levers --------------------------------------------------------------------------------------------

	private static void setParentsHidden(Client c, boolean hidden)
	{
		setHidden(parentOf(c, stone0), hidden);
		setHidden(parentOf(c, stone7), hidden);
	}

	private static void relocateParentsOff(Client c)
	{
		relocateAbs(parentOf(c, stone0), offX, offY);
		relocateAbs(parentOf(c, stone7), offX, offY);
	}

	private static void resetParents(Client c)
	{
		clearForced(parentOf(c, stone0));
		clearForced(parentOf(c, stone7));
	}

	private static void setGrandparentsHidden(Client c, boolean hidden)
	{
		setHidden(grandparentOf(c, stone0), hidden);
		setHidden(grandparentOf(c, stone7), hidden);
	}

	// ---- widget helpers ------------------------------------------------------------------------------------

	private static Widget parentOf(Client c, int id)
	{
		final Widget w = safeWidget(c, id);
		return w == null ? null : w.getParent();
	}

	private static Widget grandparentOf(Client c, int id)
	{
		final Widget p = parentOf(c, id);
		return p == null ? null : p.getParent();
	}

	private static void setHidden(Widget w, boolean hidden)
	{
		try
		{
			if (w != null && w.isHidden() != hidden)
			{
				w.setHidden(hidden);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static void relocateAbs(Widget w, int ax, int ay)
	{
		try
		{
			if (w == null)
			{
				return;
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
			w.setForcedPosition(ax - px, ay - py);
			w.revalidate();
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static void clearForced(Widget w)
	{
		try
		{
			if (w != null)
			{
				w.setForcedPosition(-1, -1);
				w.revalidate();
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static Widget safeWidget(Client c, int id)
	{
		try
		{
			return c.getWidget(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static int stoneN(Client c, int n)
	{
		final boolean preEoc = rootName.equals("PreEoc");
		switch (n)
		{
			case 6:  return preEoc ? InterfaceID.ToplevelPreEoc.STONE6 : InterfaceID.ToplevelOsrsStretch.STONE6;
			case 3:  return preEoc ? InterfaceID.ToplevelPreEoc.STONE3 : InterfaceID.ToplevelOsrsStretch.STONE3;
			default: return preEoc ? InterfaceID.ToplevelPreEoc.STONE0 : InterfaceID.ToplevelOsrsStretch.STONE0;
		}
	}

	private static int safeVarc(Client c)
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

	private static Object[] safeOnOp(Widget w)
	{
		try
		{
			return w.getOnOpListener();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

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
			stone0 = preEoc ? InterfaceID.ToplevelPreEoc.STONE0 : InterfaceID.ToplevelOsrsStretch.STONE0;
			stone7 = preEoc ? InterfaceID.ToplevelPreEoc.STONE7 : InterfaceID.ToplevelOsrsStretch.STONE7;
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void dumpAncestryOnce(Client c)
	{
		if (dumped)
		{
			return;
		}
		dumped = true;
		Widget w = safeWidget(c, stone0);
		for (int lvl = 0; w != null && lvl < 8; lvl++)
		{
			Rectangle b = null;
			try
			{
				b = w.getBounds();
			}
			catch (RuntimeException ignore)
			{
			}
			log.info("[stonesuppress] ancestor lvl={} id={} bounds={} hidden={}",
				lvl, w.getId(), b == null ? "null" : (b.x + "," + b.y + "," + b.width + "x" + b.height), w.isHidden());
			Widget p;
			try
			{
				p = w.getParent();
			}
			catch (RuntimeException ex)
			{
				p = null;
			}
			w = p;
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
			final File out = new File(DIR, String.format("stonesuppress-%s-%dx%d.png", mode, w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[stonesuppress] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[stonesuppress] capture failed: {}", String.valueOf(ex));
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
