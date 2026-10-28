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

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Unit tests for {@link OverlayKeepOut}: where a plugin's overlay box goes when it would cover the mobile HUD. The
 * search is pure geometry, so it is tested directly; {@link OverlayKeepOut#place} is tested on a hand-built HUD.
 */
public class OverlayKeepOutTest
{
	private static final int GAP = OverlayKeepOut.GAP;
	// A portrait screen's shown rows, its chat at the top left with the column, and the minimap at the top right.
	private static final Rectangle SCREEN = new Rectangle(0, 0, 600, 1304);
	private static final Rectangle CHAT = new Rectangle(0, 86, 410, 114);
	private static final Rectangle MAP = new Rectangle(430, 80, 170, 160);

	@Test
	public void aBoxOverTheChatDropsBelowIt()
	{
		// Quest Helper's step, dragged to where the chat opens.
		final Point at = new Point(63, 190);
		assertTrue(OverlayKeepOut.clear(at, 140, 95, List.of(CHAT, MAP), SCREEN));
		assertEquals(new Point(63, CHAT.y + CHAT.height + GAP), at);
	}

	@Test
	public void aClearBoxStaysPut()
	{
		final Point at = new Point(20, 400);
		assertTrue(OverlayKeepOut.clear(at, 140, 95, List.of(CHAT, MAP), SCREEN));
		assertEquals(new Point(20, 400), at);
	}

	@Test
	public void aBoxWithNoRoomBelowGoesPastTheNextControlToo()
	{
		// Below the chat is a control too close under it for the box to fit between them.
		final Rectangle area = new Rectangle(0, 0, 600, 400);
		final Rectangle chat = new Rectangle(0, 0, 300, 100);
		final Rectangle buttons = new Rectangle(0, 150, 260, 100);
		final Point at = new Point(10, 60);
		assertTrue(OverlayKeepOut.clear(at, 150, 80, List.of(chat, buttons), area));
		assertEquals(new Point(10, buttons.y + buttons.height + GAP), at);
	}

	@Test
	public void aBoxWithNowhereToGoStaysWhereItWas()
	{
		final Rectangle area = new Rectangle(0, 0, 300, 200);
		final Point at = new Point(0, 0);
		assertFalse(OverlayKeepOut.clear(at, 280, 180, List.of(new Rectangle(100, 50, 50, 50)), area));
		assertEquals(new Point(0, 0), at);
	}

	@Test
	public void onlyBoxesOnTheScreenMove()
	{
		final OverlayKeepOut keepOut = new OverlayKeepOut(List.of(CHAT), SCREEN);
		final Point world = new Point(63, 190);
		keepOut.place(box("WorldMarker", 140, 95), OverlayPosition.DYNAMIC, world);
		assertEquals(new Point(63, 190), world);
		final Point hud = new Point(0, 86);
		keepOut.place(box("RESIZABLE_VIEWPORT_BOTTOM_LINE_CHATBOX_PARENT", 410, 114), OverlayPosition.TOP_LEFT, hud);
		assertEquals(new Point(0, 86), hud);
		final Point step = new Point(63, 190);
		keepOut.place(box("QuestHelperOverlay", 140, 95), OverlayPosition.TOP_LEFT, step);
		assertEquals(new Point(63, CHAT.y + CHAT.height + GAP), step);
	}

	@Test
	public void theMinimapCornerKeepsItsOwnWidgetsButNotABoxSnappedThere()
	{
		final OverlayKeepOut keepOut = new OverlayKeepOut(List.of(MAP), SCREEN);
		// The multi-combat icon, at its own spot by the minimap.
		final Point icon = new Point(560, 200);
		keepOut.place(box("MULTICOMBAT_RESIZABLE_MODERN", 30, 30), OverlayPosition.CANVAS_TOP_RIGHT, icon);
		assertEquals(new Point(560, 200), icon);
		// A plugin's box the user snapped into that corner.
		final Overlay snapped = box("Snapped", 140, 95);
		snapped.setPreferredPosition(OverlayPosition.CANVAS_TOP_RIGHT);
		final Point at = new Point(460, 100);
		keepOut.place(snapped, OverlayPosition.CANVAS_TOP_RIGHT, at);
		assertEquals(new Point(460, MAP.y + MAP.height + GAP), at);
	}

	@Test
	public void aMovedBoxKeepsOffOneAlreadyDrawnButNotOffItself()
	{
		final OverlayKeepOut keepOut = new OverlayKeepOut(List.of(CHAT), SCREEN);
		final Overlay first = box("First", 140, 95);
		final Point a = new Point(10, 204);
		keepOut.place(first, OverlayPosition.TOP_LEFT, a);
		assertEquals(new Point(10, 204), a);
		// A second box dragged over the chat would drop onto the first one, so it goes below that instead.
		final Point b = new Point(10, 150);
		keepOut.place(box("Second", 140, 95), OverlayPosition.TOP_LEFT, b);
		assertEquals(new Point(10, 204 + 95 + GAP), b);
		// Drawn a second time in the frame, the first box doesn't move off its own first copy.
		final Point again = new Point(10, 204);
		keepOut.place(first, OverlayPosition.TOP_LEFT, again);
		assertEquals(new Point(10, 204), again);
	}

	private static Overlay box(String name, int w, int h)
	{
		final Overlay o = new Overlay()
		{
			@Override
			public Dimension render(Graphics2D graphics)
			{
				return null;
			}

			@Override
			public String getName()
			{
				return name;
			}
		};
		o.getBounds().setSize(w, h);
		return o;
	}
}
