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
 * W3 Feature-A phase A1 (desktop half): prove a clean, fully HUD-suppressed, edge-to-edge SCENE frame at the
 * device's wide full-bleed aspect. A0 proved {@code setHidden} is the only clean HUD remover; this extends it to
 * the WHOLE engine 2D interface (minimap + orbs + chat + side panels + tab stones), at a wide ~19.5:9 canvas
 * (set via {@code -Drunelite.headless.resize}), and confirms the new unknown: does the resizable 3D viewport
 * actually FILL a very wide canvas (so the scene is genuinely edge-to-edge) rather than letterbox?
 *
 * <p>This is the headless artifact behind "full-screen finally worth it": with the engine HUD suppressed the
 * scene goes edge-to-edge, and the native side then draws the HUD veneers (A3–A5) + the existing tab rails over
 * it. The runtime resize TOGGLE (reverted with the full-screen feature) is not needed here — the native-HUD
 * design is edge-to-edge by default, so the launch-time resize sets the aspect once.
 *
 * <p>Mutates widget state (hides the HUD); a desktop de-risk probe, never the device launch path. Inert unless
 * {@code -Drunelite.edge2edge=true}. PNG to {@code -Drunelite.edge2edge.dir} (or {@code …capture.dir}); the
 * one-shot {@code [edge2edge] EDGE2EDGE …} line is the md5-gate.
 */
@Slf4j
public final class EdgeToEdgeProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.edge2edge");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.edge2edge.dir");
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

	private static final int F_SETTLE = 25;     // hide the HUD this many frames before the capture
	private static final int F_VERDICT = 40;

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

	private static int frame;
	private static boolean started;
	private static boolean verdictEmitted;
	private static int[] hudIds;
	private static String rootName = "?";
	private static boolean focused;

	private EdgeToEdgeProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client c, GameState state)
	{
		if (!ENABLED || c == null || state != GameState.LOGGED_IN || verdictEmitted)
		{
			return;
		}

		if (welcomeUp(c))
		{
			if ((frame++ % 20) == 5)
			{
				synthClickCentre(c);
			}
			return;
		}

		if (!started)
		{
			if (!resolveHud(c))
			{
				return;
			}
			started = true;
			frame = 0;
			log.info("[edge2edge] in-world root={} hudTargets={} canvas={}x{}",
				rootName, hudIds.length, c.getCanvasWidth(), c.getCanvasHeight());
		}

		// Hide the entire engine 2D HUD every frame (the engine rebuilds the toplevel on TOPLEVEL_REDRAW).
		for (final int id : hudIds)
		{
			setHidden(c, id);
		}
		frame++;

		if (frame == F_SETTLE)
		{
			capture(c);
		}

		if (frame >= F_VERDICT)
		{
			verdictEmitted = true;
			final int cw = c.getCanvasWidth();
			final int ch = c.getCanvasHeight();
			final int vw = c.getViewportWidth();
			final int vh = c.getViewportHeight();
			final int vx = c.getViewportXOffset();
			final int vy = c.getViewportYOffset();
			final boolean fills = vx <= 2 && vy <= 2 && vw >= cw - 4 && vh >= ch - 4;
			int hidden = 0;
			for (final int id : hudIds)
			{
				if (isHiddenOrAbsent(c, id))
				{
					hidden++;
				}
			}
			log.info("[edge2edge] EDGE2EDGE root={} canvas={}x{} viewport={}x{}@{},{} viewportFillsCanvas={} "
					+ "hudTotal={} hudHidden={}",
				rootName, cw, ch, vw, vh, vx, vy, fills, hudIds.length, hidden);
		}
	}

	private static boolean resolveHud(Client c)
	{
		try
		{
			if (!c.isResized())
			{
				rootName = "FIXED(unsupported)";
				return false;
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int[] stones = preEoc ? STONES_PRE_EOC : STONES_OSRS_STRETCH;
			final int[] containers = preEoc
				? new int[]{
					InterfaceID.ToplevelPreEoc.MAP_MINIMAP, InterfaceID.ToplevelPreEoc.MAP_CONTAINER,
					InterfaceID.ToplevelPreEoc.ORBS, InterfaceID.ToplevelPreEoc.CHAT_CONTAINER,
					InterfaceID.ToplevelPreEoc.SIDE_PANELS, InterfaceID.ToplevelPreEoc.SIDE_CONTAINER,
				}
				: new int[]{
					InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP, InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER,
					InterfaceID.ToplevelOsrsStretch.ORBS, InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER,
					InterfaceID.ToplevelOsrsStretch.SIDE_PANELS, InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER,
				};
			rootName = preEoc ? "PreEoc" : "OsrsStretch";
			hudIds = new int[containers.length + stones.length];
			System.arraycopy(containers, 0, hudIds, 0, containers.length);
			System.arraycopy(stones, 0, hudIds, containers.length, stones.length);
			return c.getWidget(InterfaceID.ToplevelPreEoc.MAP_MINIMAP) != null
				|| c.getWidget(InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP) != null;
		}
		catch (RuntimeException ex)
		{
			return false;
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

	private static void setHidden(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w != null && !w.isHidden())
			{
				w.setHidden(true);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static boolean isHiddenOrAbsent(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return w == null || w.isHidden();
		}
		catch (RuntimeException ex)
		{
			return true;
		}
	}

	private static void capture(Client c)
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
			final File out = new File(DIR, String.format("edge2edge-scene-%dx%d.png", w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[edge2edge] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[edge2edge] capture failed: {}", String.valueOf(ex));
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
