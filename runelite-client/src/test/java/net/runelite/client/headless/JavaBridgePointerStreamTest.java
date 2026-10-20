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
 * <p>Every coordinate we hand the engine should be one a finger was really at. Two things used to break that:
 * the pointer park led with a {@code MOUSE_MOVED} to (−1,−1), a point no pointer can occupy, after every
 * single tap; and a tap's whole contact produced exactly ONE move event, so the finger's real travel between
 * landing and lifting was thrown away. These tests hold both lines.
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
		JavaBridge.onTapMove(TAP_X + 3, TAP_Y - 2);
		pump();
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

	/**
	 * The headline for the move stream: the samples the finger really produced while the press was down reach
	 * the engine as drags, instead of being dropped so that one tap only ever showed up as a single move.
	 */
	@Test
	public void theFingersRealTravelReachesTheEngineAsDrags() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();

		// Pumped between samples so each is dispatched before the next is posted. A burst posted faster than
		// the EDT drains is coalesced by AWT itself -- see samplesPostedFasterThanTheQueueDrainsCoalesce.
		JavaBridge.onTapMove(TAP_X + 2, TAP_Y + 1);
		pump();
		JavaBridge.onTapMove(TAP_X + 5, TAP_Y + 3);
		pump();
		JavaBridge.onTapMove(TAP_X + 6, TAP_Y + 4);
		pump();

		assertEquals("every sample the finger made must go out", 3, cap.count(MouseEvent.MOUSE_DRAGGED));
		final List<MouseEvent> drags = cap.allOf(MouseEvent.MOUSE_DRAGGED);
		assertEquals("the drag must carry the real sample point", TAP_X + 5, drags.get(1).getX());
		assertEquals("the drag must carry the real sample point", TAP_Y + 3, drags.get(1).getY());
		assertTrue("a drag must say which button is down",
			(drags.get(0).getModifiersEx() & java.awt.event.InputEvent.BUTTON1_DOWN_MASK) != 0);
	}

	/**
	 * Samples arriving faster than the EDT drains are folded into one, because {@link EventQueue} coalesces
	 * pending mouse-motion events -- and that is the behaviour we want, not a leak. A desktop toolkit does
	 * exactly this with a real mouse: the engine is handed where the pointer IS, not a backlog of where it
	 * was. What must survive is the latest point, which is also the one the release closes on.
	 */
	@Test
	public void samplesPostedFasterThanTheQueueDrainsCoalesce() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();

		JavaBridge.onTapMove(TAP_X + 2, TAP_Y + 1);
		JavaBridge.onTapMove(TAP_X + 5, TAP_Y + 3);
		JavaBridge.onTapMove(TAP_X + 6, TAP_Y + 4);
		pump();

		final List<MouseEvent> drags = cap.allOf(MouseEvent.MOUSE_DRAGGED);
		assertTrue("the travel must still reach the engine", !drags.isEmpty());
		final MouseEvent last = drags.get(drags.size() - 1);
		assertEquals("whatever was folded, the newest point must be the one that lands", TAP_X + 6, last.getX());
		assertEquals("whatever was folded, the newest point must be the one that lands", TAP_Y + 4, last.getY());

		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();
		assertEquals("and the release still closes on the real lift point, which is tracked per sample "
			+ "rather than read back out of the queue", TAP_X + 6, cap.first(MouseEvent.MOUSE_RELEASED).getX());
	}

	/** The release closes where the finger actually left the glass, not back at the point it pressed on. */
	@Test
	public void theReleaseClosesOnTheRealLiftPoint() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.onTapMove(TAP_X + 7, TAP_Y + 4);
		pump();
		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();

		final MouseEvent press = cap.first(MouseEvent.MOUSE_PRESSED);
		final MouseEvent release = cap.first(MouseEvent.MOUSE_RELEASED);
		assertEquals("the press still lands on the point the gate resolved", TAP_X, press.getX());
		assertEquals("the release follows the finger", TAP_X + 7, release.getX());
		assertEquals("the release follows the finger", TAP_Y + 4, release.getY());
	}

	/**
	 * Nothing streams before the gate has pressed. A move posted while the gate is still settling would push
	 * the engine's cursor off the point it is resolving, and the gate would never accept — the arm would fail
	 * open and the lift would pay for a whole second settle, which is the latency the arm exists to remove.
	 */
	@Test
	public void noSampleStreamsBeforeTheGateAccepts() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();

		JavaBridge.onTapMove(TAP_X + 4, TAP_Y + 2);
		pump();
		assertEquals("a sample before the press must not move the engine's cursor",
			0, cap.count(MouseEvent.MOUSE_DRAGGED));

		settleUntilPressed();
		assertEquals("the gate must still press on the point it resolved", TAP_X,
			cap.first(MouseEvent.MOUSE_PRESSED).getX());
	}

	/** A sample after the contact is over belongs to nothing, and must not reach the engine. */
	@Test
	public void noSampleStreamsAfterTheLift() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();
		cap.clear();

		JavaBridge.onTapMove(TAP_X + 9, TAP_Y + 9);
		pump();
		assertEquals("the contact is over; there is no finger to report", 0, cap.count(MouseEvent.MOUSE_DRAGGED));
	}

	/**
	 * Outside the 3D viewport a held left button is not idle motion — it is the engine's drag-to-rearrange
	 * (an inventory slot, a prayer icon). Replaying a tap's roll there would invent an item drag the user
	 * never made, so the stream stops at the viewport edge and those taps keep their single-point shape.
	 */
	@Test
	public void aRollOverAnInterfaceIsNotReplayedAsADrag() throws Exception
	{
		// The tap point is inside the canvas but outside the scene viewport the mock reports.
		when(client.getViewportXOffset()).thenReturn(0);
		when(client.getViewportYOffset()).thenReturn(0);
		when(client.getViewportWidth()).thenReturn(100);
		when(client.getViewportHeight()).thenReturn(100);

		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.onTapMove(TAP_X + 6, TAP_Y + 6);
		pump();

		assertEquals("a roll over an interface must not become an engine item drag",
			0, cap.count(MouseEvent.MOUSE_DRAGGED));

		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();
		assertEquals("and its release stays on the pressed slot", TAP_X,
			cap.first(MouseEvent.MOUSE_RELEASED).getX());
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
		set("dragStreamOpen", false);
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
