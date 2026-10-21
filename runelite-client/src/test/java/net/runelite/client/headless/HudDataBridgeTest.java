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

import java.lang.reflect.Field;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HudDataBridge}'s publish throttle. The full snapshot is rebuilt at most every
 * {@code REFRESH_MS}, but {@code loggedIn} must stay exact: a logout publishes at once, and the first logged-in
 * frame after it builds without waiting out the window (iOS relocates the HUD off that flag). The window is driven
 * through the {@code lastRefresh} field, not the clock, so no assertion depends on how fast the test runs.
 * {@code ENABLED} is read once at class load; the static initialiser sets it first, and no other test loads the
 * class.
 */
public class HudDataBridgeTest
{
	static
	{
		System.setProperty("runelite.huddata", "true");
	}

	@Test
	public void throttlesTheBuildButKeepsLoggedInExact() throws Exception
	{
		final Field last = HudDataBridge.class.getDeclaredField("lastRefresh");
		last.setAccessible(true);
		final Client c = client(GameState.LOGGED_IN);

		HudDataBridge.tick(c);
		final int seq = HudDataBridge.getSeq();
		assertTrue("a logged-in build publishes", HudDataBridge.getSnapshot().contains("\"loggedIn\":true"));

		last.setLong(null, System.currentTimeMillis() + 60_000);
		HudDataBridge.tick(c);
		assertEquals("inside the window nothing is rebuilt", seq, HudDataBridge.getSeq());

		HudDataBridge.tick(client(GameState.LOGIN_SCREEN));
		assertTrue("a logout publishes at once, window or not",
			HudDataBridge.getSnapshot().contains("\"loggedIn\":false"));

		HudDataBridge.tick(c);
		assertEquals("the first logged-in frame after a logout builds immediately", seq + 1, HudDataBridge.getSeq());
		assertTrue(HudDataBridge.getSnapshot().contains("\"loggedIn\":true"));
	}

	@SuppressWarnings("unchecked")
	private static Client client(GameState state)
	{
		final Client c = mock(Client.class);
		when(c.getGameState()).thenReturn(state);
		final IterableHashTable<MessageNode> messages = mock(IterableHashTable.class);
		when(messages.iterator()).thenReturn(Collections.emptyIterator());
		when(c.getMessages()).thenReturn(messages);
		return c;
	}
}
