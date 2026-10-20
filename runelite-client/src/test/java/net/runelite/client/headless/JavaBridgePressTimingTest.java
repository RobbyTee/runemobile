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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The press-timing contract for an armed tap — the shape of the input the engine (and therefore anything
 * downstream of it) is handed for one finger tap.
 *
 * <p>The tap path used to post {@code MOUSE_PRESSED} and {@code MOUSE_RELEASED} one millisecond apart at the
 * LIFT, which reported every tap as a 1ms hold no matter how long the finger was really down. The press now
 * goes out when the settle gate ACCEPTS — while the finger is still on the glass — and is closed at the real
 * lift, so the press/release delta is the hold the user performed. These tests pin that, plus the two things
 * that pressing early makes possible and which must not regress:
 * <ul>
 *   <li><b>No press before the gate accepts.</b> A press that lands before the engine has folded our move
 *       resolves against the PREVIOUS point's menu — the mis-targeting the settle gate exists to prevent.</li>
 *   <li><b>No phantom click from a contact that was not a tap.</b> A press is outstanding for the whole
 *       hold, so a contact that turns into a drag or a long-press has one open with no lift coming. Retiring
 *       it releases without a {@code MOUSE_CLICKED} and consumes the {@code MenuOptionClicked} the engine
 *       builds from the press/release pair, so no walk-here the user never asked for reaches the game.</li>
 * </ul>
 *
 * <p>Pure: the {@link Client} is mocked and no engine boots. Events are captured by pushing an
 * {@link EventQueue} that records what the bridge POSTS, rather than by listening on the canvas — a test
 * canvas is never displayable, so AWT's own delivery to it is not something to assert against.
 */
public class JavaBridgePressTimingTest
{
	static
	{
		System.setProperty("runelite.bridge", "true");
	}

	/** The point every tap in here lands on. */
	private static final int TAP_X = 300;
	private static final int TAP_Y = 220;
	/** How long the simulated finger stays down. Comfortably above the gate's own settle cost. */
	private static final long HOLD_MS = 80L;

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
	 * The headline: one tap, and the engine is handed a press that stays down for the finger's real hold.
	 */
	@Test
	public void pressIsHeldAcrossTheSettleAndClosedAtTheRealLift() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();

		assertEquals("the finger landing must not press — the gate has not resolved the point yet",
			0, cap.count(MouseEvent.MOUSE_PRESSED));

		final int ticks = settleUntilPressed();
		assertEquals("the gate accepting must post exactly one press", 1, cap.count(MouseEvent.MOUSE_PRESSED));
		assertEquals("and no release while the finger is still down", 0, cap.count(MouseEvent.MOUSE_RELEASED));
		assertTrue("the press must wait for the gate, not go out on the first tick", ticks >= 2);

		Thread.sleep(HOLD_MS);

		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();

		assertEquals("the lift must close the press", 1, cap.count(MouseEvent.MOUSE_RELEASED));
		final long reported = cap.pressReleaseDeltaMs();
		assertTrue("reported hold was " + reported + "ms; it must track the real " + HOLD_MS
			+ "ms rather than collapse to the old ~1ms atomic click", reported >= HOLD_MS - 20);
		assertTrue("reported hold " + reported + "ms must not exceed the real hold by more than scheduling slop",
			reported <= HOLD_MS + 500);
	}

	/** The press lands on the point the GATE resolved, which is the only point the engine is holding a menu for. */
	@Test
	public void pressAndReleaseLandOnTheResolvedPoint() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		Thread.sleep(5L);
		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();

		final MouseEvent press = cap.first(MouseEvent.MOUSE_PRESSED);
		final MouseEvent release = cap.first(MouseEvent.MOUSE_RELEASED);
		assertEquals("press x", TAP_X, press.getX());
		assertEquals("press y", TAP_Y, press.getY());
		assertEquals("release must close on the pressed point", press.getX(), release.getX());
		assertEquals("release must close on the pressed point", press.getY(), release.getY());
	}

	/**
	 * A contact that turns out to be a gesture: the outstanding press is retired with a release and NO
	 * {@code MOUSE_CLICKED}, so nothing about it reads as a deliberate click.
	 */
	@Test
	public void aGestureRetiresTheOutstandingPressWithoutAClick() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();

		JavaBridge.cancelArmedTap();
		pump();

		assertEquals("the retired press must be released", 1, cap.count(MouseEvent.MOUSE_RELEASED));
		assertEquals("a retire must never post a click", 0, cap.count(MouseEvent.MOUSE_CLICKED));
	}

	/**
	 * The engine builds a click from the press/release pair itself, so a retire still raises a
	 * {@code MenuOptionClicked}. That one must be consumed — and a GENUINE tap's must not be.
	 */
	@Test
	public void thePhantomClickIsConsumedButAGenuineOneIsNot() throws Exception
	{
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.cancelArmedTap();
		pump();

		final MenuOptionClicked phantom = new MenuOptionClicked(walkEntry());
		invokeMenuOptionClicked(phantom);
		assertTrue("the click built from a retired press must be consumed", phantom.isConsumed());

		// A real tap: committed, so tapState == CLICKED when its truth arrives. That is the test the consume
		// is gated on, and it is what stops a replacement tap's legitimate click from being eaten.
		final MenuOptionClicked genuine = new MenuOptionClicked(walkEntry());
		JavaBridge.armTap(TAP_X, TAP_Y);
		pump();
		settleUntilPressed();
		JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
		pump();
		invokeMenuOptionClicked(genuine);
		assertFalse("a committed tap's own click must survive", genuine.isConsumed());
	}

	/**
	 * A sweep across the range a finger spends on the glass. The assertion is against the hold that REALLY
	 * elapsed rather than the one requested: {@code Thread.sleep} overshoots freely on a loaded CI/dev box, and
	 * the point of the change is that the reported duration is a measurement of actual elapsed time — so an
	 * overshooting sleep is a longer real hold, and the report must follow it. Prints the table it asserts on,
	 * in the style of the other headless verdict harnesses.
	 */
	@Test
	public void reportedHoldTracksTheRealHoldAcrossTheHumanRange() throws Exception
	{
		final long[] holds = {35L, 60L, 95L, 140L, 220L};
		System.out.println("[presstiming] asked(ms)  realHold(ms)  reported(ms)  error(ms)");
		for (final long hold : holds)
		{
			resetTapState();
			cap.clear();
			JavaBridge.armTap(TAP_X, TAP_Y);
			pump();
			settleUntilPressed();

			final long pressedAt = cap.first(MouseEvent.MOUSE_PRESSED).getWhen();
			Thread.sleep(hold);
			JavaBridge.onClick(TAP_X, TAP_Y, MouseEvent.BUTTON1);
			final long liftedAt = System.currentTimeMillis();
			pump();

			final long realHold = liftedAt - pressedAt;
			final long reported = cap.pressReleaseDeltaMs();
			System.out.printf("[presstiming] %9d  %12d  %12d  %+9d%n",
				hold, realHold, reported, reported - realHold);

			assertTrue("the press must be held at least as long as the finger was down: asked " + hold
				+ "ms, reported " + reported + "ms", reported >= hold);
			assertTrue("reported " + reported + "ms must equal the real hold " + realHold
				+ "ms, not a constant", Math.abs(reported - realHold) <= 25L);
		}
	}

	// ---- harness ---------------------------------------------------------------------------------------

	/** Drive the settle gate a frame at a time until it accepts and presses. Returns the ticks it took. */
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

	/** Flush everything already posted: an InvocationEvent behind them is dispatched last. */
	private static void pump() throws Exception
	{
		EventQueue.invokeAndWait(() ->
		{
		});
	}

	private Client loggedInClient(Canvas c)
	{
		// A stable menu tail is what the gate watches: identical reads for STABLE_NEEDED consecutive frames is
		// its proof that the engine rebuilt its menu from OUR move. One fixed entry gives exactly that.
		final MenuEntry[] entries = {walkEntry()};
		final Menu menu = mock(Menu.class);
		when(menu.getMenuEntries()).thenReturn(entries);

		final Client cl = mock(Client.class);
		when(cl.getCanvas()).thenReturn(c);
		when(cl.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(cl.isMenuOpen()).thenReturn(false);
		when(cl.getMenu()).thenReturn(menu);
		// The gate's other observable: the engine's own cursor, which it compares to the tap point. Reporting
		// the tap point stands in for the EDT having folded our move.
		when(cl.getMouseCanvasPosition()).thenReturn(new Point(TAP_X, TAP_Y));
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

	/** A {@link ClientThread} that runs its work inline, so the tap ingress resolves within the test thread. */
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

	private static void invokeMenuOptionClicked(MenuOptionClicked ev) throws Exception
	{
		final Method m = JavaBridge.class.getDeclaredMethod("onMenuOptionClicked", MenuOptionClicked.class);
		m.setAccessible(true);
		m.invoke(null, ev);
	}

	/** The tap statics are shared across tests; start each one from rest. */
	private static void resetTapState() throws Exception
	{
		set("tapState", enumValue("TapState", "IDLE"));
		set("pressOutstanding", false);
		set("suppressClickFrames", 0);
		set("tapUncommitted", false);
		set("queuedTap", false);
		set("entered", false);
		set("focused", false);
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

	/**
	 * Records the {@link MouseEvent}s the bridge posts. Mouse events are swallowed rather than passed to
	 * {@code super}: the assertion is about what was POSTED, and a non-displayable test canvas is not a
	 * meaningful delivery target. Everything else (the focus {@code InvocationEvent}s, our own pump) passes
	 * through untouched.
	 */
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

		int count(int id)
		{
			synchronized (seen)
			{
				return (int) seen.stream().filter(e -> e.getID() == id).count();
			}
		}

		MouseEvent first(int id)
		{
			synchronized (seen)
			{
				return seen.stream().filter(e -> e.getID() == id).findFirst()
					.orElseThrow(() -> new AssertionError("no event with id " + id));
			}
		}

		/** The reported hold: the press/release {@code when} delta, which is what the engine is handed. */
		long pressReleaseDeltaMs()
		{
			return first(MouseEvent.MOUSE_RELEASED).getWhen() - first(MouseEvent.MOUSE_PRESSED).getWhen();
		}

		void popQueue()
		{
			super.pop();
		}
	}
}
