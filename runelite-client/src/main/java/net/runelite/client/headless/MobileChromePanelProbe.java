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
import net.runelite.api.GameState;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;

/**
 * W3 — focused DESKTOP diagnostic: <b>can the docked side panel be moved at all?</b> Earlier the per-frame
 * {@code Hooks.draw} shift showed the panel off-target ~every frame, but that did not distinguish "the move is
 * ineffective for this widget" from "the move works but a layout script resets it." This probe answers it
 * decisively: move {@code SIDE_CONTAINER} once, read its bounds <em>immediately</em>, then watch for N frames.
 * <ul>
 *   <li>If immediate bounds == target → the move IS effective (so the fight is a re-layout to win via an
 *       event hook).</li>
 *   <li>If immediate bounds unchanged → {@code setOriginalX}+{@code revalidate} is ineffective for this widget
 *       (need a parent / different mechanism).</li>
 *   <li>heldAfter == whether it stayed at target after N frames (drift = a script resets it).</li>
 * </ul>
 * Also dumps the panel's position mode + parent so we know what we're dealing with. Inert unless
 * {@code -Drunelite.mobilechrome.panelprobe=true}. One-shot; emits {@code [panel-probe] PANEL_PROBE …}.
 */
@Slf4j
public final class MobileChromePanelProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.panelprobe");
	private static final int SHIFT = 60;        // px to try moving the panel left
	private static final int OBSERVE = 30;
	private static final int TOL = 3;

	private enum Phase { WAIT, OBSERVE, DONE }

	private static Phase phase = Phase.WAIT;
	private static int targetX;
	private static int immediateX;
	private static int obs;
	private static int lastLoggedX = Integer.MIN_VALUE;

	private MobileChromePanelProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || phase == Phase.DONE || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		final int id = panelId(client);
		if (id == -1)
		{
			return;
		}
		final Widget w = safe(client, id);
		if (w == null || w.isHidden())
		{
			return;
		}
		final Rectangle b = w.getBounds();
		if (b == null || b.width <= 0)
		{
			return;
		}

		switch (phase)
		{
			case WAIT:
			{
				final int xMode = safeInt(w::getXPositionMode);
				final int yMode = safeInt(w::getYPositionMode);
				final Widget parent = w.getParent();
				final Point pl = parent != null ? parent.getCanvasLocation() : null;
				final int plx = pl != null ? pl.getX() : 0;
				final int ply = pl != null ? pl.getY() : 0;
				targetX = b.x - SHIFT;
				final int origX = safeInt(w::getOriginalX);
				// Move left WITHIN the widget's existing positioning model (don't change the mode):
				//   ABSOLUTE_RIGHT (2): origX is the offset from the right edge -> INCREASE to move left.
				//   else (LEFT/CENTER): origX is from the left -> decrease to move left.
				final int newOrigX = (xMode == WidgetPositionMode.ABSOLUTE_RIGHT) ? origX + SHIFT : origX - SHIFT;
				log.info("[panel-probe] before bounds={} xMode={} yMode={} origX={} origY={} parentCanvas={},{} -> set origX={} (target x={})",
					b, xMode, yMode, origX, safeInt(w::getOriginalY), plx, ply, newOrigX, targetX);
				try
				{
					w.setOriginalX(newOrigX);
					w.revalidate();
				}
				catch (RuntimeException ex)
				{
					log.info("[panel-probe] move threw: {}", String.valueOf(ex));
				}
				final Rectangle after = w.getBounds();
				immediateX = after != null ? after.x : -1;
				log.info("[panel-probe] immediate-after bounds={} (target x={})", after, targetX);
				phase = Phase.OBSERVE;
				obs = 0;
				break;
			}
			case OBSERVE:
			{
				obs++;
				if (b.x != lastLoggedX)
				{
					log.info("[panel-probe] frame+{} bounds={}", obs, b);
					lastLoggedX = b.x;
				}
				if (obs >= OBSERVE)
				{
					final boolean movedImmediate = Math.abs(immediateX - targetX) <= TOL;
					final boolean heldAfter = Math.abs(b.x - targetX) <= TOL;
					log.info("[panel-probe] PANEL_PROBE movedImmediate={} heldAfter={} immediateX={} settledX={} targetX={}",
						movedImmediate, heldAfter, immediateX, b.x, targetX);
					phase = Phase.DONE;
				}
				break;
			}
			default:
				break;
		}
	}

	private static int panelId(Client client)
	{
		try
		{
			if (!client.isResized())
			{
				return -1;
			}
			return client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
				? InterfaceID.ToplevelPreEoc.SIDE_CONTAINER : InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER;
		}
		catch (RuntimeException ex)
		{
			return -1;
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

	private interface IntSup { int get(); }

	private static int safeInt(IntSup s)
	{
		try
		{
			return s.get();
		}
		catch (RuntimeException ex)
		{
			return -999;
		}
	}
}
