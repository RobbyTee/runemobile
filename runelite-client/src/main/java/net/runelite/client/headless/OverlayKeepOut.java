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

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Keeps plugin overlay boxes off the mobile HUD. A box is any overlay laid out on the screen rather than on the world:
 * Quest Helper's step, the infoboxes, a minigame's panel, whether snapped to a corner or dragged somewhere in the
 * overlay editor. The HUD is the chat with the column beside it, the minimap cluster, the open side panel, and the
 * shell's own controls ({@link HudVeneerBridge#setOverlayKeepOut}).
 *
 * <p>{@code OverlayRenderer} takes one of these each frame and, just before a box draws, moves it the shortest way to
 * a spot that covers none of that and stays on screen. A box placed earlier in the frame counts too, so a moved box
 * doesn't land on another; if that leaves no room, only the HUD counts. Where even that has no room, the box stays
 * where it was. The snap corners' own rules still run first, so most boxes never move here. Null on desktop.
 */
public final class OverlayKeepOut
{
	/** Canvas px left between a box and what it keeps off. */
	static final int GAP = 4;
	/** Spots tried for one box before it is left where it is. */
	private static final int MAX_TRIES = 64;

	private final List<Rectangle> hud;
	private final Rectangle area;
	// Where each box drew this frame, by overlay: one drawn twice in a frame mustn't avoid its own first copy.
	private final Map<Overlay, Rectangle> placed = new IdentityHashMap<>();

	OverlayKeepOut(List<Rectangle> hud, Rectangle area)
	{
		this.hud = hud;
		this.area = area;
	}

	/**
	 * This frame's HUD in shown canvas px, or null when there is nothing to keep clear (desktop, a layout the veneer
	 * doesn't lay out). Client thread, before the overlays draw.
	 */
	public static OverlayKeepOut current(Client c)
	{
		if (!HudVeneerBridge.isEnabled() || !c.isResized())
		{
			return null;
		}
		final Rectangle area = new Rectangle(0, 0, c.getCanvasWidth(), HudStrip.shownRows(c.getCanvasHeight()));
		final List<Rectangle> hud = new ArrayList<>();
		final int[] chat = HudVeneerBridge.getOverlayChatAvoid();
		if (chat != null)
		{
			// The shells' column sits left of the chat, at the chat's scale.
			final int column = Math.round(HudVeneerBridge.OFFICIAL_COLUMN_W * HudStrip.shownScale());
			add(hud, area, chat[0] - column, chat[1], chat[2] + column, chat[3]);
		}
		addWidget(hud, area, c, InterfaceID.ToplevelPreEoc.MAP_CONTAINER);
		addWidget(hud, area, c, InterfaceID.ToplevelPreEoc.SIDE_CONTAINER);
		final int[] shell = HudVeneerBridge.overlayKeepOut();
		for (int i = 0; i + 3 < shell.length; i += 4)
		{
			add(hud, area, shell[i], shell[i + 1], shell[i + 2], shell[i + 3]);
		}
		return hud.isEmpty() ? null : new OverlayKeepOut(hud, area);
	}

	private static void addWidget(List<Rectangle> hud, Rectangle area, Client c, int id)
	{
		final Widget w = c.getWidget(id);
		final Rectangle b = w == null || w.isHidden() ? null : w.getBounds();
		if (b != null)
		{
			// Large portrait shows the minimap as a copy of the one drawn below the screen.
			final int[] s = HudStrip.toShown(new int[]{b.x, b.y, b.width, b.height});
			add(hud, area, s[0], s[1], s[2], s[3]);
		}
	}

	private static void add(List<Rectangle> hud, Rectangle area, int x, int y, int w, int h)
	{
		final Rectangle r = new Rectangle(x, y, w, h);
		if (w > 0 && h > 0 && r.intersects(area))
		{
			hud.add(r);
		}
	}

	/**
	 * Move {@code location}, where {@code overlay} is about to draw at its last size, off the HUD. Only a box laid out
	 * on the screen moves: not one drawn on the world, a tooltip, one its plugin places itself, one of the HUD's own
	 * containers, or one left in the canvas corner by the minimap (the multi-combat icon). One drawn inside a parent
	 * (an interface) stays inside it. Client thread.
	 */
	public void place(Overlay overlay, OverlayPosition position, Point location)
	{
		if (position == OverlayPosition.DYNAMIC || position == OverlayPosition.TOOLTIP
			|| position == OverlayPosition.DETACHED || OverlayBridge.HUD_OWNED.contains(overlay.getName()))
		{
			return;
		}
		// That corner's own widgets belong beside the minimap, but a box the user snapped or dragged there doesn't
		if (position == OverlayPosition.CANVAS_TOP_RIGHT && overlay.getPreferredPosition() == null
			&& overlay.getPreferredLocation() == null)
		{
			return;
		}
		final int w = overlay.getBounds().width, h = overlay.getBounds().height;
		final Rectangle parent = overlay.getParentBounds();
		final Rectangle within = parent == null || parent.isEmpty() ? area : parent.intersection(area);
		final List<Rectangle> avoid = new ArrayList<>(hud);
		for (Map.Entry<Overlay, Rectangle> e : placed.entrySet())
		{
			if (e.getKey() != overlay)
			{
				avoid.add(e.getValue());
			}
		}
		if (!clear(location, w, h, avoid, within))
		{
			clear(location, w, h, hud, within);
		}
		if (w > 0 && h > 0)
		{
			placed.put(overlay, new Rectangle(location.x, location.y, w, h));
		}
	}

	/**
	 * Move {@code loc} to the nearest spot where a {@code w x h} box overlaps none of {@code avoid} and fits inside
	 * {@code within}: a best-first search over the spots just past each side of whatever the box overlaps, nearest
	 * the start first. Returns whether the box ends up clear; {@code loc} moves only then. A box that overlaps nothing
	 * stays put, even partly outside {@code within}.
	 */
	static boolean clear(Point loc, int w, int h, List<Rectangle> avoid, Rectangle within)
	{
		if (w <= 0 || h <= 0)
		{
			return true;
		}
		final Point from = new Point(loc);
		final PriorityQueue<Point> open = new PriorityQueue<>(Comparator.comparingLong(p -> dist2(p, from)));
		final Set<Point> seen = new HashSet<>();
		open.add(from);
		seen.add(from);
		final Rectangle box = new Rectangle(w, h);
		for (int tries = 0; tries < MAX_TRIES && !open.isEmpty(); tries++)
		{
			final Point p = open.poll();
			box.setLocation(p);
			boolean hit = false;
			for (Rectangle r : avoid)
			{
				if (box.intersects(r))
				{
					hit = true;
					next(open, seen, within, w, h, r.x - GAP - w, p.y);
					next(open, seen, within, w, h, r.x + r.width + GAP, p.y);
					next(open, seen, within, w, h, p.x, r.y - GAP - h);
					next(open, seen, within, w, h, p.x, r.y + r.height + GAP);
				}
			}
			if (!hit)
			{
				loc.setLocation(p);
				return true;
			}
		}
		return false;
	}

	private static void next(PriorityQueue<Point> open, Set<Point> seen, Rectangle within, int w, int h, int x, int y)
	{
		if (x >= within.x && y >= within.y && x + w <= within.x + within.width && y + h <= within.y + within.height)
		{
			final Point p = new Point(x, y);
			if (seen.add(p))
			{
				open.add(p);
			}
		}
	}

	private static long dist2(Point a, Point b)
	{
		final long dx = a.x - b.x, dy = a.y - b.y;
		return dx * dx + dy * dy;
	}
}
