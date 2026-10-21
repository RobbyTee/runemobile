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
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MobileChromeBridge}'s rebuild throttle. Widget bounds refresh at most every
 * {@code BOUNDS_REFRESH_MS}, but a change to the state a tap or a tab switch moves (here the active tab) rebuilds
 * at once, and the first logged-in frame after a logout builds without waiting. The window is driven through the
 * {@code lastBuild} field, not the clock, so no assertion depends on how fast the test runs. {@code ENABLED} is read
 * once at class load; the static initialiser sets it first, and no other test loads the class. A tick also reads
 * JavaBridge's rect snapshots, which loads JavaBridge, so its flag is set too: the JavaBridge tests share this JVM
 * and fail if they find the class already loaded with the bridge off.
 */
public class MobileChromeBridgeTest
{
	static
	{
		System.setProperty("runelite.mobilechrome", "true");
		System.setProperty("runelite.bridge", "true");
	}

	@Test
	public void throttlesBoundsButRebuildsOnATabSwitch() throws Exception
	{
		final Field last = MobileChromeBridge.class.getDeclaredField("lastBuild");
		last.setAccessible(true);
		final Widget stone = mock(Widget.class);
		when(stone.getBounds()).thenReturn(new Rectangle(10, 20, 30, 40));
		final Client c = loggedInClient(stone);

		MobileChromeBridge.tick(c);
		final long seq = MobileChromeBridge.getSnapshotSeq();
		assertTrue("a logged-in build publishes the bounds", MobileChromeBridge.getSnapshot().contains("[10,20,30,40]"));

		when(stone.getBounds()).thenReturn(new Rectangle(11, 20, 30, 40));
		last.setLong(null, System.currentTimeMillis() + 60_000);
		MobileChromeBridge.tick(c);
		assertEquals("inside the window a moved widget waits", seq, MobileChromeBridge.getSnapshotSeq());

		when(c.getVarcIntValue(VarClientID.TOPLEVEL_PANEL)).thenReturn(4);
		MobileChromeBridge.tick(c);
		assertEquals("a tab switch rebuilds at once", seq + 1, MobileChromeBridge.getSnapshotSeq());
		assertTrue(MobileChromeBridge.getSnapshot().contains("\"activeTab\":4"));
		assertTrue(MobileChromeBridge.getSnapshot().contains("[11,20,30,40]"));

		last.setLong(null, System.currentTimeMillis() + 60_000);
		when(c.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		MobileChromeBridge.tick(c);
		assertTrue("a logout publishes at once", MobileChromeBridge.getSnapshot().contains("\"loggedIn\":false"));
		when(c.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(stone.getBounds()).thenReturn(new Rectangle(12, 20, 30, 40));
		MobileChromeBridge.tick(c);
		assertTrue("the first logged-in frame after a logout builds immediately",
			MobileChromeBridge.getSnapshot().contains("[12,20,30,40]"));

		when(stone.getBounds()).thenReturn(new Rectangle(13, 20, 30, 40));
		last.setLong(null, System.currentTimeMillis() - 60_000);
		MobileChromeBridge.tick(c);
		assertTrue("once the window passes the bounds refresh", MobileChromeBridge.getSnapshot().contains("[13,20,30,40]"));
	}

	private static Client loggedInClient(Widget stone0)
	{
		final Client c = mock(Client.class);
		when(c.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(c.isResized()).thenReturn(true);
		when(c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT)).thenReturn(1);   // PRE_EOC
		when(c.getTopLevelInterfaceId()).thenReturn(164);
		when(c.getVarcIntValue(VarClientID.TOPLEVEL_PANEL)).thenReturn(3);
		when(c.getWidget(InterfaceID.ToplevelPreEoc.STONE0)).thenReturn(stone0);
		return c;
	}
}
