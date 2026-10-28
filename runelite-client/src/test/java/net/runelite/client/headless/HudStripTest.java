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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.After;
import org.junit.Test;

public class HudStripTest
{
	// A 600-wide canvas showing 1304 rows: the chat drawn at (8, 1312), shown at (45, 85) at 600/765.
	private static final int[] CHAT_SRC = {6, 1310, 465, 149};
	private static final int[] CHAT_DST = {44, 83, 365, 117};
	private static final int[] MAP_SRC = {6, 1487, 215, 211};
	private static final int[] MAP_DST = {428, 89, 169, 165};

	@After
	public void off()
	{
		HudStrip.testLayout(null, 0);
	}

	@Test
	public void spansCoverEverySourcePixelOnce()
	{
		final int[][] sp = HudStrip.spans(765, 600);
		final double[] seen = new double[765];
		for (int[] s : sp)
		{
			int sum = 0;
			for (int q = 1; q < s.length; q++)
			{
				seen[s[0] + q - 1] += s[q] / 256.0;
				sum += s[q];
			}
			assertEquals("each destination pixel's weights sum to one", 256, sum);
		}
		for (int i = 0; i < seen.length; i++)
		{
			assertEquals("source pixel " + i, 600 / 765.0, seen[i], 0.02);
		}
	}

	@Test
	public void copyAveragesAndKeysOut()
	{
		// 4x2 canvas, visible row 0, strip row 1: a 2x1 source halves to one pixel.
		final int w = 4;
		final int[] px = new int[w * 2];
		px[0] = 0x00102030;                      // the frame under the copy
		px[w] = 0x00ff0000;
		px[w + 1] = 0x000000ff;
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 2, 1}, new int[]{0, 0, 1, 1}, false);
		assertEquals(0x00800080, px[0] & 0xffffff);

		// Half keyed: the colour pixel lands at half weight over the frame (premultiplied over).
		px[0] = 0x00000000;
		px[w] = 0x00ff0000;
		px[w + 1] = HudStrip.KEY;
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 2, 1}, new int[]{0, 0, 1, 1}, false);
		assertEquals(0x00800000, px[0] & 0xffffff);

		// All keyed: the frame shows through untouched.
		px[0] = 0x00123456;
		px[w] = HudStrip.KEY;
		px[w + 1] = HudStrip.KEY;
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 2, 1}, new int[]{0, 0, 1, 1}, false);
		assertEquals(0x00123456, px[0]);
	}

	@Test
	public void cpuCopyKeepsTheFramesTopByte()
	{
		final int w = 2;
		final int[] px = {0x7f000000, 0, 0x00ffffff, 0};
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 1, 1}, new int[]{0, 0, 1, 1}, false);
		assertEquals(0x7fffffff, px[0]);
	}

	@Test
	public void gpuCopyBlendsPremultipliedAlpha()
	{
		final int w = 2;
		// A half-alpha premultiplied red over a transparent interface pixel stays half-alpha red.
		final int[] px = {0, 0, 0x80800000, 0};
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 1, 1}, new int[]{0, 0, 1, 1}, true);
		assertEquals(0x80800000, px[0]);
	}

	@Test
	public void copyNeverWritesTheStrip()
	{
		final int w = 2;
		final int[] px = {0, 0, 0x00ffffff, 0x00ffffff};
		// A destination that reaches row 1 (the strip) is cut at the visible rows.
		HudStrip.copyScaled(px, w, 1, new int[]{0, 1, 2, 1}, new int[]{0, 0, 2, 2}, false);
		assertEquals(0x00ffffff, px[2]);
		assertEquals(0x00ffffff, px[3]);
	}

	@Test
	public void pointsOnACopyGoToTheStrip()
	{
		HudStrip.testLayout(new HudStrip.Layout(CHAT_SRC, CHAT_DST, MAP_SRC, MAP_DST), 1304);
		// The copy's corner is the source's corner, and its far corner is the source's far corner.
		long p = HudStrip.toEngine(44, 83);
		assertEquals(6, HudStrip.x(p));
		assertEquals(1310, HudStrip.y(p));
		p = HudStrip.toEngine(44 + 365 - 1, 83 + 117 - 1);
		assertEquals(6 + 465 - 1, HudStrip.x(p));
		assertEquals(1310 + 149 - 1, HudStrip.y(p));
		// Middle of the minimap copy.
		p = HudStrip.toEngine(428 + 84, 89 + 82);
		assertEquals(6 + 107, HudStrip.x(p), 1);
		assertEquals(1487 + 105, HudStrip.y(p), 1);
		// Off both copies: untouched.
		p = HudStrip.toEngine(300, 700);
		assertEquals(300, HudStrip.x(p));
		assertEquals(700, HudStrip.y(p));
	}

	@Test
	public void publishedRectsMoveToTheCopy()
	{
		HudStrip.testLayout(new HudStrip.Layout(CHAT_SRC, CHAT_DST, MAP_SRC, MAP_DST), 1304);
		assertArrayEquals(CHAT_DST, HudStrip.toShown(CHAT_SRC.clone()));
		// A world rect stays put, and a round trip through toEngine lands back inside the rect it came from.
		assertArrayEquals(new int[]{10, 10, 50, 50}, HudStrip.toShown(new int[]{10, 10, 50, 50}));
		final int[] tab = {20, 1430, 60, 25};
		final int[] shown = HudStrip.toShown(tab);
		final long back = HudStrip.toEngine(shown[0] + shown[2] / 2, shown[1] + shown[3] / 2);
		assertEquals(50, HudStrip.x(back), 2);
		assertEquals(1442, HudStrip.y(back), 2);
	}

	@Test
	public void offIsIdentity()
	{
		HudStrip.testLayout(null, 0);
		final long p = HudStrip.toEngine(50, 1400);
		assertEquals(50, HudStrip.x(p));
		assertEquals(1400, HudStrip.y(p));
		final int[] r = {1, 2, 3, 4};
		assertArrayEquals(r, HudStrip.toShown(r));
		assertArrayEquals(new int[]{5, 1500, 100, 80}, HudStrip.shownMenu(true, 5, 1500, 100, 80, 50, 1500, 600));
	}

	@Test
	public void menuOnACopySlidesToTheFinger()
	{
		HudStrip.testLayout(new HudStrip.Layout(CHAT_SRC, CHAT_DST, MAP_SRC, MAP_DST), 1304);
		// Pressed on the chat in the strip at (100, 1350); the menu opened under it.
		final int[] m = HudStrip.shownMenu(true, 60, 1350, 120, 90, 100, 1350, 600);
		final int[] at = HudStrip.toShown(new int[]{100, 1350, 1, 1});
		assertEquals(60 + at[0] - 100, m[0]);
		assertEquals(1350 + at[1] - 1350, m[1]);
		// A pick on the shown menu goes back to the real one; a tap off it does not.
		long p = HudStrip.menuToEngine(m[0] + 10, m[1] + 20);
		assertEquals(70, HudStrip.x(p));
		assertEquals(1370, HudStrip.y(p));
		p = HudStrip.menuToEngine(590, 700);
		assertEquals(590, HudStrip.x(p));
		assertEquals(700, HudStrip.y(p));
		// Closed: the shift is gone, and a point maps as any other does.
		HudStrip.shownMenu(false, 0, 0, 0, 0, 0, 0, 600);
		assertEquals(HudStrip.toEngine(m[0] + 10, m[1] + 20), HudStrip.menuToEngine(m[0] + 10, m[1] + 20));
	}

	@Test
	public void aDragKeepsTheCopyItStartedOn()
	{
		HudStrip.testLayout(new HudStrip.Layout(CHAT_SRC, CHAT_DST, MAP_SRC, MAP_DST), 1304);
		long p = HudStrip.dragStart(400, 150);           // on the chat copy, near its right edge
		assertEquals(1310 + (int) ((150 - 83 + 0.5) * 149 / 117), HudStrip.y(p));
		p = HudStrip.dragTo(400, 260, false);            // past the copy's bottom: still in the strip, below the chat
		assertEquals(1310 + (int) Math.floor((260 - 83 + 0.5) * 149 / 117), HudStrip.y(p));
		p = HudStrip.dragTo(400, 300, true);             // lifted
		assertEquals(1310 + (int) Math.floor((300 - 83 + 0.5) * 149 / 117), HudStrip.y(p));
		p = HudStrip.dragTo(400, 150, false);            // no drag open: an ordinary point again
		assertEquals(HudStrip.toEngine(400, 150), p);

		// A drag that starts off the copies never enters the strip, even across a copy.
		HudStrip.dragStart(300, 600);
		p = HudStrip.dragTo(100, 120, true);
		assertEquals(100, HudStrip.x(p));
		assertEquals(120, HudStrip.y(p));
	}

	@Test
	public void menuInTheStripButOffTheCopiesMovesIntoView()
	{
		HudStrip.testLayout(new HudStrip.Layout(CHAT_SRC, CHAT_DST, MAP_SRC, MAP_DST), 1304);
		final int[] m = HudStrip.shownMenu(true, 400, 1250, 150, 120, 450, 1250, 600);
		assertEquals(400, m[0]);
		assertEquals(1304 - 120, m[1]);
	}
}
