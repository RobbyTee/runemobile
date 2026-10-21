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

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.util.Text;

/**
 * Switches the account's "Game client layout" through the game's own Settings &gt; Display dropdown. It backs
 * the native shells' one-tap "Switch to Resizable - Modern", the only layout their HUD supports.
 *
 * <p>The layout is not the client's to set. The window mode is a client preference, but Classic vs Modern is
 * {@link VarbitID#RESIZABLE_STONE_ARRANGEMENT}, a server-side varbit that follows the account to every client.
 * The option's own "Select" script (4569) only closes the list; the change is the server answering the
 * button press. So this does what a desktop click does: build the dropdown with the click handler the game
 * gave it, then fire the option's op as the engine's own menu action, which is what sends the press.
 *
 * <p>Nothing has to be on screen. The settings side interface opens at login with the other tab interfaces,
 * and its dropdown builds and presses while the panel is hidden. Every cache-derived id is checked before
 * use, so a game update that moves the setting fails in the log instead of pressing whatever sits at the
 * old index.
 *
 * <p>Client thread only ({@link #tick}); {@link #request} is the one cross-thread entry.
 */
@Slf4j
final class GameLayoutSwitch
{
	// The layout setting as script 7992 builds it on SettingsSide's first dynamic display setting: struct 2732
	// names it (param 1086), keys it (1077) and lists its option labels (an enum, param 1091).
	static final int LAYOUT_STRUCT = 2732;
	static final int PARAM_NAME = 1086;
	static final int PARAM_SETTING = 1077;
	static final int PARAM_OPTIONS = 1091;
	static final String LAYOUT_SETTING_NAME = "Game client layout";
	// The setting button's onClick, set by script 4567: it builds one child per option under
	// DISPLAY_DYNAMIC_SETTING_1_BUTTONS (child 0 is the hover highlight, so option i is child i + 1).
	static final int DROPDOWN_OPEN_SCRIPT = 4568;
	private static final String SELECT = "Select";

	static final String MODERN = "Resizable - Modern";
	static final String CLASSIC = "Resizable - Classic";

	// How long the server gets to answer a press before the switch is reported as not taken.
	private static final long CONFIRM_MS = 15_000;

	private static volatile String requested;   // the label to press; set off-thread, consumed by tick
	private static String awaiting;               // the label pressed, until the layout shows it (client thread)
	private static long pressedAt;

	// Desktop harness only (ios/dev-tools/headless/run-layout-switch.sh): once logged in, switch the account to
	// Classic and back to Modern through this class, ending with a VERDICT line. An account already on Classic
	// is only switched to Modern. Either way it ends on Modern, the layout the shells need.
	private static final boolean TEST_CYCLE = Boolean.getBoolean("runelite.hudveneer.test.layoutCycle");
	private static final long TEST_SETTLE_MS = 10_000;
	private static long testNextAt;
	private static int testStep;   // 0 start, 1 to Classic pressed, 2 to Modern pressed, 3 done

	private GameLayoutSwitch()
	{
	}

	/** Test seam: forget any request or outstanding press. Production never calls this. */
	static void resetForTest()
	{
		requested = null;
		awaiting = null;
		pressedAt = 0;
	}

	/** Press {@code label} in the layout dropdown on the next client tick. Any thread. */
	static void request(String label)
	{
		requested = label;
	}

	/** Client thread, every logged-in frame, in Fixed too: Fixed is a layout this switches away from. */
	static void tick(Client c)
	{
		final String label = requested;
		if (label != null)
		{
			requested = null;
			if (isShowing(c, label))
			{
				log.info("[layout] already {}: nothing to switch", label);
			}
			else if (press(c, label))
			{
				awaiting = label;
				pressedAt = System.currentTimeMillis();
			}
		}
		if (awaiting != null)
		{
			final long waited = System.currentTimeMillis() - pressedAt;
			if (isShowing(c, awaiting))
			{
				log.info("[layout] now {} (toplevel {}), {} ms after the press", awaiting, c.getTopLevelInterfaceId(), waited);
				awaiting = null;
			}
			else if (waited > CONFIRM_MS)
			{
				log.warn("[layout] {} did not take: the layout is unchanged {} ms after the press", awaiting, waited);
				awaiting = null;
			}
		}
		if (TEST_CYCLE)
		{
			testCycle(c);
		}
	}

	/** Whether the live layout is {@code label}. Fixed is neither resizable layout. */
	static boolean isShowing(Client c, String label)
	{
		if (!c.isResized())
		{
			return false;
		}
		final int arrangement = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT);
		return MODERN.equals(label) ? arrangement == 1 : CLASSIC.equals(label) && arrangement == 0;
	}

	/** Open the layout dropdown and press {@code label}. True if the press was sent. */
	static boolean press(Client c, String label)
	{
		try
		{
			final StructComposition s = c.getStructComposition(LAYOUT_STRUCT);
			final String name = s == null ? null : s.getStringValue(PARAM_NAME);
			if (!LAYOUT_SETTING_NAME.equals(name))
			{
				log.warn("[layout] struct {} is \"{}\", not \"{}\": the game has moved the setting, not switching",
					LAYOUT_STRUCT, name, LAYOUT_SETTING_NAME);
				return false;
			}
			c.runScript(DROPDOWN_OPEN_SCRIPT,
				InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1, InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1_BUTTONS,
				InterfaceID.SettingsSide.DROPDOWN_CONTAINER, InterfaceID.SettingsSide.DROPDOWN_PANEL,
				InterfaceID.SettingsSide.DROPDOWN_SCROLLBAR, InterfaceID.SettingsSide.TOOLTIP, InterfaceID.SettingsSide.UNIVERSE,
				s.getIntValue(PARAM_OPTIONS), s.getIntValue(PARAM_SETTING), LAYOUT_STRUCT);
			final Widget option = findOption(c, label);
			if (option == null)
			{
				log.warn("[layout] the layout dropdown has no \"{}\" option to select: not switching", label);
				closeDropdown(c);
				return false;
			}
			// A dynamic child is addressed by its index (param0), the static list by its id (param1); op 1 is
			// "Select". This is the engine's click path, so it sends the press and runs the option's onOp, which
			// closes the list again.
			c.menuAction(option.getIndex(), InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1_BUTTONS,
				MenuAction.CC_OP, 1, -1, SELECT, option.getText());
			log.info("[layout] pressed \"{}\" (option child {})", Text.removeTags(option.getText()), option.getIndex());
			return true;
		}
		catch (RuntimeException ex)
		{
			BridgeFaults.note("GameLayoutSwitch.press", ex);
			return false;
		}
	}

	/** The built option whose label starts with {@code label} and that can be selected, or null. */
	private static Widget findOption(Client c, String label)
	{
		final Widget list = c.getWidget(InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1_BUTTONS);
		final Widget[] options = list == null ? null : list.getDynamicChildren();
		if (options == null)
		{
			return null;
		}
		for (final Widget o : options)
		{
			if (o != null && o.getText() != null && Text.removeTags(o.getText()).startsWith(label) && canSelect(o))
			{
				return o;
			}
		}
		return null;
	}

	private static boolean canSelect(Widget w)
	{
		final String[] actions = w.getActions();
		if (actions == null)
		{
			return false;
		}
		for (final String a : actions)
		{
			if (SELECT.equals(a))
			{
				return true;
			}
		}
		return false;
	}

	/** What the option's own onOp does on the way out: hide the list this opened. */
	private static void closeDropdown(Client c)
	{
		final Widget w = c.getWidget(InterfaceID.SettingsSide.DROPDOWN_CONTAINER);
		if (w != null)
		{
			w.setHidden(true);
		}
	}

	private static void testCycle(Client c)
	{
		final long now = System.currentTimeMillis();
		if (testStep == 0 && testNextAt == 0)
		{
			testNextAt = now + TEST_SETTLE_MS;   // let the toplevel and the side interfaces finish loading
		}
		if (testStep == 3 || requested != null || awaiting != null || now < testNextAt)
		{
			return;
		}
		testNextAt = now + 3_000;   // a settled layout before the next step reads it
		switch (testStep)
		{
			case 0:
				log.info("[layout] TEST start: toplevel {}, resized={}, arrangement={}", c.getTopLevelInterfaceId(),
					c.isResized(), c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT));
				testStep = isShowing(c, MODERN) ? 1 : 2;
				request(testStep == 1 ? CLASSIC : MODERN);
				break;
			case 1:
				if (!isShowing(c, CLASSIC))
				{
					testDone(c, "RED", "the switch to Classic did not take");
					return;
				}
				testStep = 2;
				request(MODERN);
				break;
			default:
				testDone(c, isShowing(c, MODERN) ? "GREEN" : "RED", "ended on toplevel " + c.getTopLevelInterfaceId());
				break;
		}
	}

	private static void testDone(Client c, String verdict, String detail)
	{
		testStep = 3;
		log.info("[layout] VERDICT: {} ({}; resized={}, arrangement={})", verdict, detail, c.isResized(),
			c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT));
	}
}
