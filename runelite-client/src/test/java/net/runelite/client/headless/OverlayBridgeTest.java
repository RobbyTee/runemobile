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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Unit tests for {@link OverlayBridge#snapPositionForPoint(int, int, int, int)} — the pure coordinate math
 * that maps where the native UI dropped an overlay to a RuneLite {@link OverlayPosition} snap corner. It
 * touches no engine state, so it is tested directly. The grid is: top/bottom by the vertical midpoint, and
 * left/centre/right by canvas thirds — except the bottom row, which has only two real snap corners (a
 * dropped centre-bottom overlay snaps left). Getting this wrong silently mis-docks every overlay a user
 * repositions, identically on both ports. Also pins {@link OverlayBridge#HUD_OWNED} to upstream's overlay names.
 */
public class OverlayBridgeTest
{
	// A 300x200 canvas: vertical midpoint = 100, column thirds at x=100 and x=200.
	private static final int W = 300;
	private static final int H = 200;

	private static String snap(int x, int y)
	{
		return OverlayBridge.snapPositionForPoint(x, y, W, H);
	}

	@Test
	public void degenerateCanvasDefaultsToTopLeft()
	{
		final String tl = OverlayPosition.TOP_LEFT.name();
		assertEquals(tl, OverlayBridge.snapPositionForPoint(50, 50, 0, H));
		assertEquals(tl, OverlayBridge.snapPositionForPoint(50, 50, W, 0));
		assertEquals(tl, OverlayBridge.snapPositionForPoint(50, 50, -10, -10));
	}

	@Test
	public void topRowMapsToLeftCentreRightByThirds()
	{
		assertEquals(OverlayPosition.TOP_LEFT.name(), snap(50, 50));
		assertEquals(OverlayPosition.TOP_CENTER.name(), snap(150, 50));
		assertEquals(OverlayPosition.TOP_RIGHT.name(), snap(250, 50));
	}

	@Test
	public void bottomRowHasOnlyLeftAndRightCorners()
	{
		assertEquals(OverlayPosition.BOTTOM_LEFT.name(), snap(50, 150));
		// A centre-bottom drop collapses to the left corner — there is no BOTTOM_CENTER snap.
		assertEquals(OverlayPosition.BOTTOM_LEFT.name(), snap(150, 150));
		assertEquals(OverlayPosition.BOTTOM_RIGHT.name(), snap(250, 150));
	}

	@Test
	public void columnBoundariesUseStrictLeftInclusiveRightThirds()
	{
		// Implementation: left = x < third; right = x >= third*2  (strict on the left edge, inclusive on the
		// right). third = 100, third*2 = 200. Pin both edges so a `<`/`<=` or `>=`/`>` regression is caught.
		assertEquals(OverlayPosition.TOP_LEFT.name(), snap(99, 50));     // last left pixel
		assertEquals(OverlayPosition.TOP_CENTER.name(), snap(100, 50));  // x == third -> first centre pixel
		assertEquals(OverlayPosition.TOP_CENTER.name(), snap(199, 50));  // last centre pixel
		assertEquals(OverlayPosition.TOP_RIGHT.name(), snap(200, 50));   // x == third*2 -> first right pixel
	}

	@Test
	public void hudOwnedIsExactlyTheUpstreamOverlaysOverTheVeneerHud() throws Exception
	{
		// The containers HudVeneerBridge pins or hides, in both resizable layouts.
		final Set<Integer> hud = Set.of(
			InterfaceID.ToplevelPreEoc.CHAT_CONTAINER, InterfaceID.ToplevelPreEoc.MAP_CONTAINER,
			InterfaceID.ToplevelPreEoc.SIDE_CONTAINER, InterfaceID.ToplevelPreEoc.SIDE_STATIC_LAYER,
			InterfaceID.ToplevelPreEoc.SIDE_MOVABLE_LAYER, InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER,
			InterfaceID.ToplevelOsrsStretch.MAP_CONTAINER, InterfaceID.ToplevelOsrsStretch.SIDE_MENU);
		// WidgetOverlays and its inner WidgetOverlay are package-private to ui.overlay, so build upstream's real list
		// reflectively; their constructors only store their arguments. A rename upstream fails here instead of
		// quietly putting the container back in the editor.
		final Class<?> outer = Class.forName("net.runelite.client.ui.overlay.WidgetOverlays");
		final Constructor<?> ctor = outer.getDeclaredConstructors()[0];
		ctor.setAccessible(true);
		final Object widgetOverlays = ctor.newInstance(null, null, null);
		final Method create = outer.getDeclaredMethod("createOverlays");
		create.setAccessible(true);
		final Field componentId = Class.forName("net.runelite.client.ui.overlay.WidgetOverlays$WidgetOverlay")
			.getDeclaredField("componentId");
		componentId.setAccessible(true);

		final Set<String> names = new HashSet<>();
		for (Object o : (Collection<?>) create.invoke(widgetOverlays))
		{
			if (hud.contains(componentId.getInt(o)))
			{
				names.add(((Overlay) o).getName());
			}
		}
		assertEquals(OverlayBridge.HUD_OWNED, names);
	}

	@Test
	public void verticalMidpointBelongsToTheBottomRow()
	{
		// y == canvasH/2 (100): top is y < H/2, so the midpoint row is the bottom.
		assertEquals(OverlayPosition.BOTTOM_LEFT.name(), snap(50, 100));
		assertEquals(OverlayPosition.BOTTOM_RIGHT.name(), snap(250, 100));
	}
}
