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
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.HashTable;
import net.runelite.api.MenuAction;
import net.runelite.api.WidgetNode;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for two pieces of {@link HudVeneerBridge} that both ports depend on.
 *
 * <p>The overlay snap-corner safe-frame geometry — the rect the native shells use to keep RuneLite overlays
 * clear of the relocated chat box. The math is pure (native pushes the chat position/size via setters; no
 * engine read), and a wrong rect silently lets overlays render under the chat on both ports. Covered: the
 * {@code chatX < 0} "not relocating" null guard, the default fallbacks (row height -> CONTROLS_H=23, width ->
 * CHAT_DEFAULT_W=519), and the collapsed-vs-open height (collapsed = the tab row only; open = the row + the
 * message area ORIG_CHAT_CONTENT_H=142 + 2).
 *
 * <p>And the snapshot's {@code dialogueActive} bit, which decides whether the chatbox region belongs to the
 * chat veneer or to the dialogue drawn over it — a miss there costs the user the dialogue's buttons.
 *
 * <p>{@code ENABLED} is read once at class load from {@code -Drunelite.hudveneer}; set in the static
 * initialiser before the class loads (reliable — no other module test references the headless bridges).
 * The chat geometry is shared static state, so every test sets the full state it needs (it never relies on
 * a default left by another test).
 */
public class HudVeneerBridgeTest
{
	static
	{
		System.setProperty("runelite.hudveneer", "true");
	}

	// Mirrors of the layout-dump constants in HudVeneerBridge (the contract the rect math is built on).
	private static final int CONTROLS_H = 23;
	private static final int CHAT_DEFAULT_W = 519;
	private static final int ORIG_CHAT_CONTENT_H = 142;

	/**
	 * Full reset of the shared static chat geometry before each test, so the suite is order-independent
	 * regardless of how JUnit sequences the methods. Tests still set the specific state they assert on; this
	 * is the safety net so a value left by one test can never leak into the next.
	 */
	@Before
	public void resetState()
	{
		HudVeneerBridge.relocateChat(-1, -1, false);   // chatX < 0 = not relocating
		HudVeneerBridge.setChatTabRowHeight(0);         // 0 = fall back to CONTROLS_H
		HudVeneerBridge.setChatAreaWidth(0);            // 0 = fall back to CHAT_DEFAULT_W
		HudVeneerBridge.setChatCollapsed(false);
	}

	@Test
	public void enabledFlagIsSet()
	{
		assertTrue("runelite.hudveneer must be enabled before HudVeneerBridge loads", HudVeneerBridge.isEnabled());
	}

	@Test
	public void overlaySafeInsetsRoundTrip()
	{
		HudVeneerBridge.setOverlaySafeInsets(1, 2, 3, 4);
		assertArrayEquals(new int[]{1, 2, 3, 4}, HudVeneerBridge.getOverlayInsets());
	}

	@Test
	public void chatAvoidIsNullWhenNotRelocating()
	{
		HudVeneerBridge.relocateChat(-1, -1, false);   // x < 0 = veneer is not relocating the chat
		assertNull("no avoidance rect when the veneer isn't moving the chat", HudVeneerBridge.getOverlayChatAvoid());
	}

	@Test
	public void chatAvoidZeroRowAndWidthFallBackToHardcodedDefaults()
	{
		// Explicitly drive 0 in for both -> the rect must use the hardcoded fallbacks CONTROLS_H (23) and
		// CHAT_DEFAULT_W (519), open height = rowH + ORIG_CHAT_CONTENT_H + 2.
		HudVeneerBridge.setChatTabRowHeight(0);
		HudVeneerBridge.setChatAreaWidth(0);
		HudVeneerBridge.setChatCollapsed(false);
		HudVeneerBridge.relocateChat(10, 20, false);

		final int expectedH = CONTROLS_H + ORIG_CHAT_CONTENT_H + 2;
		assertArrayEquals(new int[]{10, 20, CHAT_DEFAULT_W, expectedH}, HudVeneerBridge.getOverlayChatAvoid());
	}

	@Test
	public void chatAvoidCollapsedIsTheTabRowOnly()
	{
		HudVeneerBridge.setChatCollapsed(true);        // row height/width left at the @Before defaults (0)
		HudVeneerBridge.relocateChat(10, 20, false);

		// Collapsed: just the tab row (no message area added), width still the default fallback.
		assertArrayEquals(new int[]{10, 20, CHAT_DEFAULT_W, CONTROLS_H}, HudVeneerBridge.getOverlayChatAvoid());
	}

	@Test
	public void chatAvoidHonoursProvidedRowHeightAndWidth()
	{
		HudVeneerBridge.setChatTabRowHeight(30);   // native enlarged tab row
		HudVeneerBridge.setChatAreaWidth(600);
		HudVeneerBridge.setChatCollapsed(false);
		HudVeneerBridge.relocateChat(5, 6, false);

		final int expectedH = 30 + ORIG_CHAT_CONTENT_H + 2;
		assertArrayEquals(new int[]{5, 6, 600, expectedH}, HudVeneerBridge.getOverlayChatAvoid());
	}

	// --- dialogue detection -------------------------------------------------------------------------------
	// A chatbox dialogue is not a fixed list of interface groups: it is whatever the engine has opened ONTO
	// THE CHATBOX. Content opens its own boxes there (a quest's "do you wish to take one?" Yes/No), and one we
	// fail to recognise is not a cosmetic miss -- the native shells keep their chat veneer over it and route
	// every tap on it to the chat, so its buttons cannot be pressed at all. These pin the rule itself: the
	// ATTACHMENT decides, and an interface the engine opened anywhere else is not a dialogue.

	/** A mocked client far enough along for {@link HudVeneerBridge#tick} to reach the chat snapshot: every
	 *  widget read comes back null (the engine-widget work is all null-guarded), so the only thing the
	 *  snapshot's {@code dialogueActive} can be reading is the component table. */
	private static Client loggedInClientWith(HashTable<WidgetNode> componentTable)
	{
		final Client c = mock(Client.class);
		when(c.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(c.isResized()).thenReturn(true);
		when(c.getComponentTable()).thenReturn(componentTable);
		return c;
	}

	/** A component table holding one open interface: {@code group} opened on {@code parentComponent}. The node's
	 *  hash IS the parent component it was opened on, which is the half that decides. */
	private static HashTable<WidgetNode> openInterface(int parentComponent, int group)
	{
		final WidgetNode node = mock(WidgetNode.class);
		when(node.getHash()).thenReturn((long) parentComponent);
		when(node.getId()).thenReturn(group);
		@SuppressWarnings("unchecked")
		final HashTable<WidgetNode> table = mock(HashTable.class);
		// A fresh iterator per call -- tick walks the table more than once (detection, then the dialogue rect).
		when(table.iterator()).thenAnswer(inv -> Collections.singletonList(node).iterator());
		return table;
	}

	@Test
	public void interfaceOpenedOnTheChatboxIsADialogue()
	{
		// Confirmdestroy stands in for the unnamed kinds: a real chatbox box (item + message + Yes/No) that none
		// of the named-widget checks look for, so only the attachment can report it.
		final Client c = loggedInClientWith(openInterface(InterfaceID.Chatbox.MES_LAYER, InterfaceID.CONFIRMDESTROY));
		HudVeneerBridge.relocateChat(93, 8, true);

		HudVeneerBridge.tick(c);

		assertTrue("an interface opened onto the chatbox is a dialogue, named kind or not",
			HudVeneerBridge.getChatSnapshot().contains("\"dialogueActive\":true"));
	}

	@Test
	public void interfaceOpenedElsewhereIsNotADialogue()
	{
		// The orb cluster is opened onto a TOPLEVEL component, like every other non-chatbox interface. Reporting
		// one of those would hide the chat veneer and swallow taps over the chatbox for as long as it stayed open.
		final Client c = loggedInClientWith(openInterface(InterfaceID.ToplevelOsrsStretch.ORBS, InterfaceID.ORBS));
		HudVeneerBridge.relocateChat(93, 8, true);

		HudVeneerBridge.tick(c);

		assertTrue("only the chatbox's own open interfaces are dialogues",
			HudVeneerBridge.getChatSnapshot().contains("\"dialogueActive\":false"));
	}

	@Test
	public void unnamedDialogueRectComesFromItsRootComponent()
	{
		// The shells union this rect with the chatbox rect to decide which taps belong to the dialogue, so an
		// unnamed kind has to publish one of its own: its root component (child 0), the same component the named
		// kinds' UNIVERSE entries name. Without it the box is detected but its rect is whatever the chat says.
		final Client c = loggedInClientWith(openInterface(InterfaceID.Chatbox.MES_LAYER, InterfaceID.CONFIRMDESTROY));
		final Widget root = mock(Widget.class);
		when(root.getBounds()).thenReturn(new Rectangle(93, 8, 519, 142));
		when(c.getWidget(InterfaceID.Confirmdestroy.UNIVERSE)).thenReturn(root);
		HudVeneerBridge.relocateChat(93, 8, true);

		HudVeneerBridge.tick(c);

		assertTrue("the unnamed dialogue's own rect, in canvas px",
			HudVeneerBridge.getChatSnapshot().contains("\"dlgX\":93,\"dlgY\":8,\"dlgW\":519,\"dlgH\":142"));
	}

	// --- pressing an unnamed dialogue --------------------------------------------------------------------------
	// Its buttons are the box's OWN components, so there is no modelled continue or option row to fire, and a
	// click at the finger is resolved against the scene behind the relocated chatbox -- it walks the player past
	// the box instead of pressing anything. tapDialogue therefore carries the POINT in and fires the op of the
	// widget under it. What these pin is the engine's dispatch rule (param0: -1 static, index dynamic), that the
	// BUTTON wins over the box behind it, and that a press lands only where there is something to press.

	/** A widget with canvas bounds and an optional sparse action list, the two things the resolver reads. */
	private static Widget widgetAt(int id, int x, int y, int w, int h, String... actions)
	{
		final Widget wi = mock(Widget.class);
		when(wi.getId()).thenReturn(id);
		when(wi.getBounds()).thenReturn(new Rectangle(x, y, w, h));
		when(wi.getActions()).thenReturn(actions.length == 0 ? null : actions);
		return wi;
	}

	/** A logged-in client with {@code group} open on the chatbox and {@code root} as that group's root component. */
	private static Client clientWithDialogue(int group, Widget root)
	{
		final Client c = loggedInClientWith(openInterface(InterfaceID.Chatbox.MES_LAYER, group));
		when(c.getWidget(group << 16)).thenReturn(root);
		return c;
	}

	/** Tap a box the shell has already seen open: a tick to publish it, the tap, then the tick that resolves it.
	 *  That order is the real one -- a tap is stamped with the box that was on screen when the finger landed. */
	private static void tapAfterPublish(Client c, int x, int y)
	{
		HudVeneerBridge.relocateChat(93, 8, true);
		HudVeneerBridge.tick(c);
		HudVeneerBridge.tapDialogue(x, y);
		HudVeneerBridge.tick(c);
	}

	@Test
	public void tapOnADialogueButtonFiresThatButtonsOwnOp()
	{
		// The box fills the chatbox and carries an op of its own; the Yes sits inside it. The tap is on the Yes,
		// so the Yes is what must fire -- the enclosing box is not what the finger pressed.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142, "Close");
		final Widget yes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(box.getStaticChildren()).thenReturn(new Widget[]{yes});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 320, 115);

		// param0 = -1: a static interface component is addressed by id alone, and passing an index misroutes the
		// engine's op dispatch (the rule the tab stones established here).
		verify(c).menuAction(-1, (584 << 16) | 7, MenuAction.CC_OP, 1, -1, "Continue", "");
	}

	@Test
	public void tapOnADynamicRowIsAddressedByItsIndex()
	{
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget row = widgetAt((584 << 16) | 3, 100, 40, 300, 20, null, "Select");
		when(row.getIndex()).thenReturn(4);
		when(box.getDynamicChildren()).thenReturn(new Widget[]{row});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 150, 50);

		// A dynamic child is addressed by index, and the op is the first NAMED action -- here op 2, since op 1
		// has no name and so no menu entry a finger could have pressed.
		verify(c).menuAction(4, (584 << 16) | 3, MenuAction.CC_OP, 2, -1, "Select", "");
	}

	@Test
	public void tapOnTheInertPartOfADialoguePressesNothing()
	{
		// Parchment with no op anywhere under the finger. Firing something would answer a quest for the player,
		// and falling back to a click is the walk-the-player this path exists to stop: the tap is consumed.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget text = widgetAt((584 << 16) | 5, 100, 40, 300, 40);
		when(box.getStaticChildren()).thenReturn(new Widget[]{text});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 200, 60);

		verify(c, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
			anyString(), anyString());
	}

	@Test
	public void tapOutsideEveryWidgetPressesNothing()
	{
		// The region gate is the shells' (chatbox rect union the published dialogue rect), so a point can arrive
		// that no widget holds. Nothing is pressed rather than the nearest thing found.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget yes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(box.getStaticChildren()).thenReturn(new Widget[]{yes});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 700, 400);

		verify(c, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
			anyString(), anyString());
	}

	@Test
	public void tapIsDroppedWhenAnotherBoxHasReplacedTheOneTapped()
	{
		// The finger was on the destroy prompt; by the time the tap resolves, a different box is open at the same
		// place. Pressing what is there now would answer, in the player's name, a question they never saw.
		final Widget destroy = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget yes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(destroy.getStaticChildren()).thenReturn(new Widget[]{yes});
		final Client tapped = clientWithDialogue(InterfaceID.CONFIRMDESTROY, destroy);

		final Widget other = widgetAt(InterfaceID.OBJECTBOX << 16, 93, 8, 519, 142);
		final Widget button = widgetAt((InterfaceID.OBJECTBOX << 16) | 2, 300, 100, 60, 30, "Continue");
		when(other.getStaticChildren()).thenReturn(new Widget[]{button});
		final Client live = clientWithDialogue(InterfaceID.OBJECTBOX, other);

		HudVeneerBridge.relocateChat(93, 8, true);
		HudVeneerBridge.tick(tapped);          // the destroy prompt is what is on screen
		HudVeneerBridge.tapDialogue(320, 115); // ... and what the tap is stamped with
		HudVeneerBridge.tick(live);            // the box changed before the tap could resolve

		verify(live, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
			anyString(), anyString());
	}

	@Test
	public void aSelfHiddenButtonIsNotPressed()
	{
		// A box can carry a button it is not currently offering. Its bounds and actions stay populated, so only
		// its hidden flag says so -- and pressing what the user cannot see is not an answer they gave.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget ghost = widgetAt((584 << 16) | 9, 300, 100, 60, 30, "Continue");
		when(ghost.isSelfHidden()).thenReturn(true);
		when(box.getStaticChildren()).thenReturn(new Widget[]{ghost});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 320, 115);

		verify(c, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
			anyString(), anyString());
	}

	@Test
	public void twoButtonsOfEqualSizeResolveToTheOneDrawnOnTop()
	{
		// Same size, same place, both pressable: the later child is the one the engine draws over the other, so
		// it is the one under the finger. Declining the overlap would leave the tap with nothing to press.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget under = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Yes");
		final Widget over = widgetAt((584 << 16) | 8, 300, 100, 60, 30, "No");
		when(box.getStaticChildren()).thenReturn(new Widget[]{under, over});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		tapAfterPublish(c, 320, 115);

		verify(c).menuAction(-1, (584 << 16) | 8, MenuAction.CC_OP, 1, -1, "No", "");
	}

	@Test
	public void tapIsDroppedWhenTheSameGroupReopensAfterAGap()
	{
		// The case the group check CANNOT see: a destroy prompt replaced by another destroy prompt -- same group,
		// different item. Reaching it needs the gap that makes a tap survive at all: tick returns early while
		// logged out, so the tap is never consumed there, and the engine runs on without it. The tap is dated for
		// exactly this, and two ticks later it is no longer an answer to anything the finger was on.
		final Widget first = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget firstYes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(first.getStaticChildren()).thenReturn(new Widget[]{firstYes});
		final Client tapped = clientWithDialogue(InterfaceID.CONFIRMDESTROY, first);

		final Widget second = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget secondYes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(second.getStaticChildren()).thenReturn(new Widget[]{secondYes});
		final Client live = clientWithDialogue(InterfaceID.CONFIRMDESTROY, second);

		final Client loading = mock(Client.class);
		when(loading.getGameState()).thenReturn(GameState.LOADING);

		HudVeneerBridge.relocateChat(93, 8, true);
		HudVeneerBridge.tick(tapped);          // the first prompt is on screen
		HudVeneerBridge.tapDialogue(320, 115); // ... and the tap is dated to it
		HudVeneerBridge.tick(loading);         // a hop: the tap is not consumed, but time passes
		HudVeneerBridge.tick(loading);
		HudVeneerBridge.tick(live);            // back, with another prompt of the SAME group open

		verify(live, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(),
			anyString(), anyString());
	}

	@Test
	public void aTapTakenAfterThisTicksResolveIsStillAnsweredByTheNextOne()
	{
		// The other side of dating a tap: one ticks' staleness is the NORMAL path, not a stale tap. A finger that
		// lands after this tick's one-shot has already run is answered by the following tick, and must be.
		final Widget box = widgetAt(584 << 16, 93, 8, 519, 142);
		final Widget yes = widgetAt((584 << 16) | 7, 300, 100, 60, 30, "Continue");
		when(box.getStaticChildren()).thenReturn(new Widget[]{yes});
		final Client c = clientWithDialogue(InterfaceID.CONFIRMDESTROY, box);

		HudVeneerBridge.relocateChat(93, 8, true);
		HudVeneerBridge.tick(c);
		HudVeneerBridge.tapDialogue(320, 115);
		HudVeneerBridge.tick(c);

		verify(c).menuAction(-1, (584 << 16) | 7, MenuAction.CC_OP, 1, -1, "Continue", "");
	}
}
