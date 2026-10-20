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

import java.awt.AWTEvent;
import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the bridge says the pointer is — the other half of the press-timing contract that
 * {@link JavaBridgePressTimingTest} pins.
 *
 * <p>Every coordinate we hand the engine should be one a finger was really at. The pointer park used to
 * break that outright: it led with a {@code MOUSE_MOVED} to (−1,−1), a point no pointer can occupy, after
 * every single tap in the session.
 *
 * <p>Pure: the {@link Client} is mocked and no engine boots. Events are captured by pushing an
 * {@link EventQueue} that records what the bridge POSTS.
 */
public class JavaBridgePointerStreamTest
{
	static
	{
		System.setProperty("runelite.bridge", "true");
	}

	private static final int TAP_X = 300;
	private static final int TAP_Y = 220;

	private Capture cap;
	private Canvas canvas;
	private Client client;

	@Before
	public void setUp() throws Exception
	{
		resetTapState();
		cap = new Capture();
		Toolkit.getDefaultToolkit().getSystemEventQueue().push(cap);
		canvas = new Canvas();
		client = loggedInClient(canvas);
		JavaBridge.register(client);
		JavaBridge.setClientThread(inlineClientThread());
	}

	@After
	public void tearDown() throws Exception
	{
		cap.popQueue();
		resetTapState();
		JavaBridge.register(null);
	}

	/**
	 * The park is an exit, not a teleport. It used to move the pointer to (−1,−1) first, because that is the
	 * only way to end the engine's hover — at the cost of naming a coordinate no pointer can occupy, after
	 * every tap in the session.
	 */
	@Test
	public void parkingPostsAnExitAndNoFabricatedMove() throws Exception
	{
		JavaBridge.onMove(TAP_X, TAP_Y);
		pump();
		cap.clear();

		JavaBridge.parkPointer();
		pump();

		assertEquals("the park must post exactly one exit", 1, cap.count(MouseEvent.MOUSE_EXITED));
		assertEquals("and must not move the pointer to get there", 0, cap.count(MouseEvent.MOUSE_MOVED));
	}

	/** The exit reports the point the pointer left from, the way a real toolkit's does. */
	@Test
	public void theExitCarriesTheLastRealPoint() throws Exception
	{
		JavaBridge.onMove(TAP_X, TAP_Y);
		pump();
		JavaBridge.parkPointer();
		pump();

		final MouseEvent exit = cap.first(MouseEvent.MOUSE_EXITED);
		assertEquals("exit x", TAP_X, exit.getX());
		assertEquals("exit y", TAP_Y, exit.getY());
	}

	/** Nothing the bridge posts may name a coordinate off the canvas — the whole point of the park change. */
	@Test
	public void noPostedEventNamesAnImpossiblePoint() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();
		JavaBridge.parkPointer();
		pump();

		final List<MouseEvent> bad = cap.all().stream()
			.filter(e -> e.getX() < 0 || e.getY() < 0)
			.collect(Collectors.toList());
		assertTrue("these events name a point no finger was at: "
			+ bad.stream().map(e -> e.getID() + "@" + e.getX() + "," + e.getY()).collect(Collectors.toList()),
			bad.isEmpty());
	}

	// ---- harness ---------------------------------------------------------------------------------------

	private int settleUntilPressed() throws Exception
	{
		for (int i = 1; i <= 40; i++)
		{
			JavaBridge.tapTick(client);
			pump();
			if (cap.count(MouseEvent.MOUSE_PRESSED) > 0)
			{
				return i;
			}
		}
		throw new AssertionError("the settle gate never accepted in 40 ticks");
	}

	private static void pump() throws Exception
	{
		EventQueue.invokeAndWait(() ->
		{
		});
	}

	private Client loggedInClient(Canvas c)
	{
		final MenuEntry[] entries = {walkEntry()};
		final Menu menu = mock(Menu.class);
		when(menu.getMenuEntries()).thenReturn(entries);

		final Client cl = mock(Client.class);
		when(cl.getCanvas()).thenReturn(c);
		when(cl.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(cl.isMenuOpen()).thenReturn(false);
		when(cl.getMenu()).thenReturn(menu);
		when(cl.getMouseCanvasPosition()).thenReturn(new Point(TAP_X, TAP_Y));
		// A viewport the tap point sits inside, so the default is the scene (where a held left button means
		// nothing to the engine). The interface case overrides these.
		when(cl.getViewportXOffset()).thenReturn(0);
		when(cl.getViewportYOffset()).thenReturn(0);
		when(cl.getViewportWidth()).thenReturn(800);
		when(cl.getViewportHeight()).thenReturn(600);
		return cl;
	}

	private static MenuEntry walkEntry()
	{
		final MenuEntry e = mock(MenuEntry.class);
		when(e.getOption()).thenReturn("Walk here");
		when(e.getTarget()).thenReturn("");
		when(e.getType()).thenReturn(MenuAction.WALK);
		when(e.getIdentifier()).thenReturn(0);
		when(e.getParam0()).thenReturn(0);
		when(e.getParam1()).thenReturn(0);
		when(e.getItemId()).thenReturn(-1);
		when(e.getWorldViewId()).thenReturn(-1);
		return e;
	}

	private static ClientThread inlineClientThread()
	{
		final ClientThread ct = mock(ClientThread.class);
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invoke(any(Runnable.class));
		doAnswer(inv ->
		{
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(ct).invokeLater(any(Runnable.class));
		return ct;
	}

	private static void resetTapState() throws Exception
	{
		set("tapState", enumValue("TapState", "IDLE"));
		set("pressOutstanding", false);
		set("suppressClickFrames", 0);
		set("tapUncommitted", false);
		set("queuedTap", false);
		set("entered", false);
		set("focused", false);
		set("lastPointerX", -1);
		set("lastPointerY", -1);
	}

	private static Object enumValue(String type, String name) throws Exception
	{
		for (final Class<?> c : JavaBridge.class.getDeclaredClasses())
		{
			if (c.getSimpleName().equals(type))
			{
				for (final Object o : c.getEnumConstants())
				{
					if (o.toString().equals(name))
					{
						return o;
					}
				}
			}
		}
		throw new AssertionError("no " + type + "." + name);
	}

	private static void set(String field, Object value) throws Exception
	{
		final Field f = JavaBridge.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(null, value);
	}

	/** Records the {@link MouseEvent}s the bridge posts (see {@code JavaBridgePressTimingTest.Capture}). */
	private static final class Capture extends EventQueue
	{
		private final List<MouseEvent> seen = new ArrayList<>();

		@Override
		protected void dispatchEvent(AWTEvent e)
		{
			if (e instanceof MouseEvent)
			{
				synchronized (seen)
				{
					seen.add((MouseEvent) e);
				}
				return;
			}
			super.dispatchEvent(e);
		}

		void clear()
		{
			synchronized (seen)
			{
				seen.clear();
			}
		}

		List<MouseEvent> all()
		{
			synchronized (seen)
			{
				return new ArrayList<>(seen);
			}
		}

		List<MouseEvent> allOf(int id)
		{
			return all().stream().filter(e -> e.getID() == id).collect(Collectors.toList());
		}

		int count(int id)
		{
			return allOf(id).size();
		}

		MouseEvent first(int id)
		{
			return allOf(id).stream().findFirst()
				.orElseThrow(() -> new AssertionError("no event with id " + id));
		}

		void popQueue()
		{
			super.pop();
		}
	}
}
