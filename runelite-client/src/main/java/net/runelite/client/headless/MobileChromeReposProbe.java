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

import java.awt.Rectangle;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;

/**
 * W3 hybrid-chrome — DESKTOP de-risk for the AUTHENTIC mobile tab layout: instead of native-drawing tab icons
 * (which crop the stone out of the composited frame and look boxed), <b>reposition the real desktop tab-stone
 * widgets</b> into two vertical edge columns, and let the ENGINE draw them — feathered borders, correct active
 * state, no native artifacts. This is exactly how the official OSRS-Mobile client looks ("the same stone icons,
 * just moved vertically").
 *
 * <p>The one thing to settle on desktop first (the reviewer's warning, {@code Widget.java:161}: "almost any
 * interaction with this widget from a clientscript will recalculate this value"): does
 * {@code setOriginalX/Y + setXPositionMode/YPositionMode(ABSOLUTE_LEFT/TOP) + revalidate()} actually MOVE a tab
 * stone, and does it HOLD — or does the toplevel layout script reset it (requiring re-apply)? Widget layout only
 * re-runs on {@code TOPLEVEL_REDRAW}/resize (see {@code MinimapPlugin}), not every frame, so re-applying each
 * {@code Hooks.draw} should hold them with at most a 1-frame flicker on a redraw. This probe applies the move
 * every frame and reports whether the stones reach + hold their column targets.
 *
 * <p><b>Mutates widget positions</b> → a SEPARATE gate from the read-only {@link MobileChromeBridge}, and
 * desktop-first. Entirely inert unless {@code -Drunelite.mobilechrome.repos=true}. Emits a single
 * bash-parseable verdict line ({@code [chrome-repos] CHROME_REPOS …}) for md5-gating.
 */
@Slf4j
public final class MobileChromeReposProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.repos");

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

	private static final int EDGE = 6;     // canvas px from the screen edge
	private static final int GAP = 5;      // px between stacked stones
	private static final int TOP_MARGIN = 40;  // keep the columns clear of the very top (minimap/world map)
	private static final int TOL = 3;      // px tolerance when checking a stone reached its target
	private static final int STABILIZE_FRAMES = 40;

	private static int loggedInFrames;
	private static boolean verdictEmitted;
	private static boolean loggedApply;
	private static int reapplyDrift;       // frames on which a stone had drifted and needed re-applying

	private MobileChromeReposProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Each {@code Hooks.draw} on the client thread (only when enabled + LOGGED_IN): reposition + verify. */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		final int[] stones = stonesForRoot(client);
		if (stones == null)
		{
			return;   // not a resizable root yet
		}
		loggedInFrames++;

		final int canvasW = client.getCanvasWidth();
		final int canvasH = client.getCanvasHeight();
		if (canvasW <= 0 || canvasH <= 0)
		{
			return;
		}

		// Split present stones into a left column (idx 0–6) and a right column (idx 7–13).
		final java.util.List<Integer> left = new java.util.ArrayList<>();
		final java.util.List<Integer> right = new java.util.ArrayList<>();
		for (int i = 0; i < stones.length; i++)
		{
			final Widget w = safeWidget(client, stones[i]);
			if (w != null && !w.isHidden())
			{
				(i <= 6 ? left : right).add(i);
			}
		}

		boolean driftedThisFrame = false;
		driftedThisFrame |= placeColumn(client, stones, left, true, canvasW, canvasH);
		driftedThisFrame |= placeColumn(client, stones, right, false, canvasW, canvasH);
		if (driftedThisFrame)
		{
			reapplyDrift++;
		}
		loggedApply = true;

		if (!verdictEmitted && loggedInFrames >= STABILIZE_FRAMES)
		{
			verdictEmitted = true;
			emitVerdict(client, stones, left, right, canvasW, canvasH);
		}
	}

	/**
	 * Reposition every stone in {@code col} into a vertical column at the {@code left}/right edge, stacked +
	 * vertically centred. Returns true if any stone had drifted from its target (i.e. the layout had reset it
	 * since the last frame) and was re-applied.
	 */
	private static boolean placeColumn(Client client, int[] stones, java.util.List<Integer> col,
		boolean isLeft, int canvasW, int canvasH)
	{
		final int m = col.size();
		if (m == 0)
		{
			return false;
		}
		boolean drifted = false;
		// Use the first stone's size for the column metrics.
		final Widget first = safeWidget(client, stones[col.get(0)]);
		final int sw = first != null ? Math.max(1, first.getWidth()) : 33;
		final int sh = first != null ? Math.max(1, first.getHeight()) : 36;
		final int step = sh + GAP;
		final int totalH = m * step - GAP;
		final int y0 = Math.max(TOP_MARGIN, (canvasH - totalH) / 2);
		final int tx = isLeft ? EDGE : (canvasW - sw - EDGE);

		for (int k = 0; k < m; k++)
		{
			final int idx = col.get(k);
			final Widget w = safeWidget(client, stones[idx]);
			if (w == null)
			{
				continue;
			}
			final int ty = y0 + k * step;
			final Rectangle b = w.getBounds();
			if (b != null && Math.abs(b.x - tx) <= TOL && Math.abs(b.y - ty) <= TOL)
			{
				continue;   // already at target — the layout has not reset it; nothing to do
			}
			drifted = true;
			try
			{
				final Widget parent = w.getParent();
				final Point pl = parent != null ? parent.getCanvasLocation() : null;
				final int plx = pl != null ? pl.getX() : 0;
				final int ply = pl != null ? pl.getY() : 0;
				final int ox = tx - plx;
				final int oy = ty - ply;
				w.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
				w.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
				w.setOriginalX(ox);
				w.setOriginalY(oy);
				w.revalidate();
				if (!loggedApply)
				{
					final Rectangle after = w.getBounds();
					log.info("[chrome-repos] stone idx={} {} -> target {},{} parent={},{} orig {},{} after={}",
						idx, b, tx, ty, plx, ply, ox, oy, after);
				}
			}
			catch (RuntimeException ex)
			{
				log.info("[chrome-repos] reposition idx={} threw: {}", idx, String.valueOf(ex));
			}
		}
		return drifted;
	}

	private static void emitVerdict(Client client, int[] stones, java.util.List<Integer> left,
		java.util.List<Integer> right, int canvasW, int canvasH)
	{
		int present = left.size() + right.size();
		int held = countHeld(client, stones, left, true, canvasW, canvasH)
			+ countHeld(client, stones, right, false, canvasW, canvasH);
		log.info("[chrome-repos] CHROME_REPOS canvas={}x{} present={} heldAtTarget={} reapplyDriftFrames={} ofFrames={}",
			canvasW, canvasH, present, held, reapplyDrift, loggedInFrames);
	}

	private static int countHeld(Client client, int[] stones, java.util.List<Integer> col,
		boolean isLeft, int canvasW, int canvasH)
	{
		final int m = col.size();
		if (m == 0)
		{
			return 0;
		}
		final Widget first = safeWidget(client, stones[col.get(0)]);
		final int sw = first != null ? Math.max(1, first.getWidth()) : 33;
		final int sh = first != null ? Math.max(1, first.getHeight()) : 36;
		final int step = sh + GAP;
		final int totalH = m * step - GAP;
		final int y0 = Math.max(TOP_MARGIN, (canvasH - totalH) / 2);
		final int tx = isLeft ? EDGE : (canvasW - sw - EDGE);
		int held = 0;
		for (int k = 0; k < m; k++)
		{
			final Widget w = safeWidget(client, stones[col.get(k)]);
			if (w == null)
			{
				continue;
			}
			final int ty = y0 + k * step;
			final Rectangle b = w.getBounds();
			if (b != null && Math.abs(b.x - tx) <= TOL && Math.abs(b.y - ty) <= TOL)
			{
				held++;
			}
		}
		return held;
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

	private static Widget safeWidget(Client client, int id)
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
