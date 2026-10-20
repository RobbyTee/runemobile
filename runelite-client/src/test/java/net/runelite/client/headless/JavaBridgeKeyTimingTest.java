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

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The timing contract for synthesized keys — the shape of the keyboard input the engine (and every plugin
 * listening to it) is handed.
 *
 * <p>Every key the bridge produced used to go out as {@code KEY_PRESSED} at {@code t}, {@code KEY_TYPED} at
 * {@code t+1} and {@code KEY_RELEASED} at {@code t+2}: three numbers written into the {@code when} field of
 * three events dispatched in a single EDT drain. Nothing was held for 2ms, and a whole committed string was
 * delivered inside one game tick. The keys now take real time — the press goes out, and the release follows
 * after an interval that actually elapses — so what the stream reports is what happened.
 *
 * <p>The dwell itself is a stand-in: iOS hands us committed text, never a key down or up, so there is no
 * measured hold to report. These tests therefore pin the property that IS ours to keep — that the reported
 * timing is not a fiction — rather than any particular number.
 *
 * <p>Delivery is observed with a {@link java.awt.event.KeyListener} on the canvas, because that is how
 * {@code dispatchKey} delivers: a posted {@code KeyEvent} is routed by the {@code KeyboardFocusManager} to
 * the focus owner, and headless there is none.
 */
public class JavaBridgeKeyTimingTest
{
	static
	{
		System.setProperty("runelite.bridge", "true");
	}

	/** Must match {@code JavaBridge.KEY_HOLD_MIN_MS} / {@code KEY_GAP_MIN_MS}. */
	private static final long HOLD_MIN_MS = 45L;
	private static final long GAP_MIN_MS = 25L;
	/** Scheduler slack. A late delivery only ever strengthens "it really took time", so this is one-sided. */
	private static final long SLACK_MS = 15L;
	/** How long a test waits for keys that are, by design, not instant. */
	private static final long WAIT_MS = 10_000L;

	/** One delivered key event, with the moment it actually arrived alongside the moment it claims. */
	private static final class Seen
	{
		final int id;
		final int vk;
		final char ch;
		final int modifiers;
		final long claimedMs;    // the event's own when
		final long arrivedMs;    // wall clock at delivery

		Seen(KeyEvent e)
		{
			this.id = e.getID();
			this.vk = e.getKeyCode();
			this.ch = e.getKeyChar();
			this.modifiers = e.getModifiersEx();
			this.claimedMs = e.getWhen();
			this.arrivedMs = System.currentTimeMillis();
		}
	}

	private final List<Seen> seen = new ArrayList<>();
	private Canvas canvas;
	private Client client;

	@Before
	public void setUp() throws Exception
	{
		resetKeyPacer();
		synchronized (seen)
		{
			seen.clear();
		}
		canvas = new Canvas();
		canvas.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyPressed(KeyEvent e)
			{
				record(e);
			}

			@Override
			public void keyTyped(KeyEvent e)
			{
				record(e);
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
				record(e);
			}
		});
		client = mock(Client.class);
		when(client.getCanvas()).thenReturn(canvas);
		JavaBridge.register(client);
	}

	@After
	public void tearDown() throws Exception
	{
		JavaBridge.setShiftHeld(false);   // a latch left down would carry into the next test
		JavaBridge.register(null);
		resetKeyPacer();
	}

	/**
	 * The headline: a key is held for a real interval, and the interval it reports is the one that elapsed.
	 */
	@Test
	public void aKeyIsHeldForTimeThatActuallyPasses() throws Exception
	{
		JavaBridge.onKeyStroke(KeyEvent.VK_ENTER);
		awaitCount(3);   // pressed, typed, released

		final Seen pressed = firstOf(KeyEvent.KEY_PRESSED);
		final Seen released = releaseOf(pressed);

		final long reallyElapsed = released.arrivedMs - pressed.arrivedMs;
		assertTrue("the release must arrive after a hold the user could have made, not in the same breath "
				+ "as the press (elapsed " + reallyElapsed + "ms)",
			reallyElapsed >= HOLD_MIN_MS - SLACK_MS);

		final long claimed = released.claimedMs - pressed.claimedMs;
		assertTrue("and the hold it REPORTS must be that same interval, not a number written into a "
				+ "timestamp (claimed " + claimed + "ms, elapsed " + reallyElapsed + "ms)",
			Math.abs(claimed - reallyElapsed) <= SLACK_MS);
	}

	/** The old shape: three events one millisecond apart. Nothing may report a hold that short again. */
	@Test
	public void noKeyReportsTheOldTwoMillisecondHold() throws Exception
	{
		JavaBridge.onText("ok");
		awaitCount(6);

		final List<Seen> presses = allOf(KeyEvent.KEY_PRESSED);
		assertEquals("every press must be released -- a key left down is a stuck key",
			presses.size(), allOf(KeyEvent.KEY_RELEASED).size());
		// Paired by key rather than by index: holds outlast gaps, so keys overlap the way a typist's do and
		// the releases do not necessarily come back in the order the presses went out.
		for (final Seen press : presses)
		{
			final long claimed = releaseOf(press).claimedMs - press.claimedMs;
			assertTrue("key '" + press.ch + "' reports a " + claimed + "ms hold", claimed >= HOLD_MIN_MS - SLACK_MS);
		}
	}

	/** A committed string is TYPED, over time, rather than handed to the engine whole inside one tick. */
	@Test
	public void aCommittedStringIsTypedOutRatherThanDumped() throws Exception
	{
		final long startedMs = System.currentTimeMillis();
		JavaBridge.onText("hello");
		final long returnedMs = System.currentTimeMillis();
		awaitCount(15);   // five keys x pressed + typed + released

		final List<Seen> presses = allOf(KeyEvent.KEY_PRESSED);
		assertEquals("one press per character", 5, presses.size());

		final long span = presses.get(4).arrivedMs - presses.get(0).arrivedMs;
		assertTrue("the five keys must be spread over real time, not dumped into one drain (span "
				+ span + "ms)", span >= 4 * GAP_MIN_MS - SLACK_MS);
		assertTrue("...and onText must have returned long before the last key went out, so the UI thread is "
				+ "never held for the length of a paste",
			returnedMs - startedMs < span);

		for (int i = 1; i < presses.size(); i++)
		{
			assertTrue("consecutive keys must not claim the same instant",
				presses.get(i).claimedMs > presses.get(i - 1).claimedMs);
		}
	}

	/** Order is the one thing pacing must not cost: the characters must spell what was committed. */
	@Test
	public void theCharactersArriveInTheOrderTheyWereCommitted() throws Exception
	{
		JavaBridge.onText("abcdef");
		awaitCount(18);

		final StringBuilder typed = new StringBuilder();
		for (final Seen s : allOf(KeyEvent.KEY_TYPED))
		{
			typed.append(s.ch);
		}
		assertEquals("abcdef", typed.toString());
	}

	/** Two sources, one stream: a keystroke committed after some text must still land after it. */
	@Test
	public void keysFromDifferentSourcesKeepTheirOrder() throws Exception
	{
		JavaBridge.onText("ab");
		JavaBridge.onKeyStroke(KeyEvent.VK_BACK_SPACE);
		awaitCount(9);

		final List<Seen> presses = allOf(KeyEvent.KEY_PRESSED);
		assertEquals(3, presses.size());
		assertEquals('a', presses.get(0).ch);
		assertEquals('b', presses.get(1).ch);
		assertEquals("the backspace was committed last and must arrive last",
			KeyEvent.VK_BACK_SPACE, presses.get(2).vk);
	}

	/** A hotkey chord is a key too: held, then released, so the listener's latch clears at the end of a hold. */
	@Test
	public void aKeybindChordIsHeldAndThenReleased() throws Exception
	{
		JavaBridge.onKeybind(KeyEvent.VK_F3, java.awt.event.InputEvent.SHIFT_DOWN_MASK);
		awaitCount(2);

		final Seen pressed = firstOf(KeyEvent.KEY_PRESSED);
		final Seen released = releaseOf(pressed);
		assertEquals(KeyEvent.VK_F3, pressed.vk);
		assertEquals("the chord's modifier mask must survive the pacing",
			java.awt.event.InputEvent.SHIFT_DOWN_MASK, pressed.modifiers);
		assertTrue("a chord must be held like one", released.arrivedMs - pressed.arrivedMs >= HOLD_MIN_MS - SLACK_MS);
		assertEquals("a chord carries no character, so it produces no KEY_TYPED", 0, allOf(KeyEvent.KEY_TYPED).size());
	}

	/**
	 * The hold must be the engine's experience of it, not our scheduler's. Timed from the moment the press
	 * was QUEUED, an EDT that is behind by more than the hold receives press and release in the same drain
	 * and the key is never observably down -- however honest the two timestamps look. So: stall the EDT
	 * past a whole hold BEFORE the key is committed, and the release must still arrive a hold after the
	 * press was actually delivered.
	 */
	@Test
	public void theHoldIsMeasuredFromDeliveryNotFromWhenItWasQueued() throws Exception
	{
		final long stallMs = 3 * HOLD_MIN_MS;
		EventQueue.invokeLater(() ->
		{
			try
			{
				Thread.sleep(stallMs);
			}
			catch (InterruptedException ie)
			{
				Thread.currentThread().interrupt();
			}
		});

		JavaBridge.onKeyStroke(KeyEvent.VK_ENTER);
		awaitCount(3);

		final Seen pressed = firstOf(KeyEvent.KEY_PRESSED);
		final long heldForMs = releaseOf(pressed).arrivedMs - pressed.arrivedMs;
		assertTrue("the press and the release must not arrive in the same drain just because the EDT was "
				+ "behind -- the key has to be observably down (delivered " + heldForMs + "ms apart)",
			heldForMs >= HOLD_MIN_MS - SLACK_MS);
	}

	/**
	 * The sticky modifier latch is not paced, but it does share the stream's order. A latch released while
	 * a burst is still going out must not overtake keys committed before it and clear the modifier out from
	 * under them.
	 */
	@Test
	public void aModifierLatchCannotOvertakeKeysCommittedBeforeIt() throws Exception
	{
		JavaBridge.setShiftHeld(true);
		JavaBridge.onText("ab");
		JavaBridge.setShiftHeld(false);
		awaitCount(8);   // shift down, a x3, b x3, shift up

		final List<Seen> order = new ArrayList<>();
		synchronized (seen)
		{
			for (final Seen x : seen)
			{
				if (x.id != KeyEvent.KEY_TYPED)
				{
					order.add(x);
				}
			}
		}
		final int shiftDown = indexOf(order, KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT);
		final int shiftUp = indexOf(order, KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT);
		final int pressB = indexOf(order, KeyEvent.KEY_PRESSED, KeyEvent.getExtendedKeyCodeForChar('b'));
		assertTrue("the latch must go down before the keys it was toggled for", shiftDown < pressB);
		assertTrue("...and must not come up before them", shiftUp > pressB);
	}

	private static int indexOf(List<Seen> order, int id, int vk)
	{
		for (int i = 0; i < order.size(); i++)
		{
			if (order.get(i).id == id && order.get(i).vk == vk)
			{
				return i;
			}
		}
		throw new AssertionError("no event id=" + id + " vk=" + vk + " in " + order.size() + " events");
	}

	// ---- plumbing ---------------------------------------------------------------------------------

	private void record(KeyEvent e)
	{
		synchronized (seen)
		{
			seen.add(new Seen(e));
			seen.notifyAll();
		}
	}

	/** Wait until at least {@code n} key events have been delivered, or fail. */
	private void awaitCount(int n) throws Exception
	{
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
		synchronized (seen)
		{
			while (seen.size() < n)
			{
				final long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
				if (leftMs <= 0)
				{
					throw new AssertionError("timed out waiting for " + n + " key events; saw " + seen.size());
				}
				seen.wait(Math.min(leftMs, 50L));
			}
		}
		// The pacer schedules the release separately, so let any same-moment stragglers land before asserting.
		EventQueue.invokeAndWait(() ->
		{
		});
	}

	private List<Seen> allOf(int id)
	{
		final List<Seen> out = new ArrayList<>();
		synchronized (seen)
		{
			for (final Seen s : seen)
			{
				if (s.id == id)
				{
					out.add(s);
				}
			}
		}
		return out;
	}

	/** The release that closes a given press, matched on the key itself. */
	private Seen releaseOf(Seen press)
	{
		for (final Seen s : allOf(KeyEvent.KEY_RELEASED))
		{
			if (s.vk == press.vk && s.arrivedMs >= press.arrivedMs)
			{
				return s;
			}
		}
		throw new AssertionError("no release for vk=" + press.vk + " -- the key is still down");
	}

	private Seen firstOf(int id)
	{
		final List<Seen> all = allOf(id);
		assertTrue("expected at least one event of id " + id, !all.isEmpty());
		return all.get(0);
	}

	/** Drain anything a previous test left pending, so one test's keys cannot land in another's capture. */
	private static void resetKeyPacer() throws Exception
	{
		final Field f = JavaBridge.class.getDeclaredField("keyQueue");
		f.setAccessible(true);
		((Queue<?>) f.get(null)).clear();
	}
}
