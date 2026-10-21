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

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.StructComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertFalse;
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
 * The layout switch presses an option in the game's own Settings &gt; Display dropdown, and that press changes
 * an account-wide setting. These pin what it may press: only the option it was asked for, only in the
 * dropdown it checked is the layout setting, and nothing when the layout is already the one asked for.
 */
public class GameLayoutSwitchTest
{
	static
	{
		// HudVeneerBridge reads it once at class load; set before either test class can load it.
		System.setProperty("runelite.hudveneer", "true");
	}

	private static final int BUTTONS = InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1_BUTTONS;
	private static final int OPTIONS_ENUM = 3509;
	private static final int SETTING_ID = 12;

	@Before
	public void reset()
	{
		GameLayoutSwitch.resetForTest();
	}

	/** A built dropdown entry: index {@code index} under the option list, with {@code text} and the Select op. */
	private static Widget option(int index, String text, boolean selectable)
	{
		final Widget w = mock(Widget.class);
		when(w.getIndex()).thenReturn(index);
		when(w.getText()).thenReturn(text);
		when(w.getActions()).thenReturn(selectable ? new String[]{"Select"} : null);
		return w;
	}

	/** The list as script 4568 leaves it: the hover highlight, then the three layouts, in the enum's order. */
	private static Widget[] builtOptions()
	{
		return new Widget[]{
			option(0, "", false),
			option(1, "Fixed - Classic layout", true),
			option(2, "Resizable - Classic layout", true),
			option(3, "Resizable - Modern layout", true),
		};
	}

	/** A logged-in client whose layout setting is {@code settingName} and whose dropdown builds {@code options}. */
	private static Client client(boolean resized, int arrangement, String settingName, Widget[] options)
	{
		final Client c = mock(Client.class);
		when(c.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(c.isResized()).thenReturn(resized);
		when(c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT)).thenReturn(arrangement);
		final StructComposition s = mock(StructComposition.class);
		when(s.getStringValue(GameLayoutSwitch.PARAM_NAME)).thenReturn(settingName);
		when(s.getIntValue(GameLayoutSwitch.PARAM_OPTIONS)).thenReturn(OPTIONS_ENUM);
		when(s.getIntValue(GameLayoutSwitch.PARAM_SETTING)).thenReturn(SETTING_ID);
		when(c.getStructComposition(GameLayoutSwitch.LAYOUT_STRUCT)).thenReturn(s);
		final Widget list = mock(Widget.class);
		when(list.getDynamicChildren()).thenReturn(options);
		when(c.getWidget(BUTTONS)).thenReturn(list);
		return c;
	}

	private static void verifyNothingPressed(Client c)
	{
		verify(c, never()).menuAction(anyInt(), anyInt(), any(MenuAction.class), anyInt(), anyInt(), anyString(), anyString());
	}

	@Test
	public void fromClassicItPressesModernInTheGamesOwnDropdown()
	{
		final Client c = client(true, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, builtOptions());

		assertTrue(GameLayoutSwitch.press(c, GameLayoutSwitch.MODERN));

		// Built with the same arguments the setting's onClick carries, so the list is the game's own.
		verify(c).runScript(GameLayoutSwitch.DROPDOWN_OPEN_SCRIPT,
			InterfaceID.SettingsSide.DISPLAY_DYNAMIC_SETTING_1, BUTTONS,
			InterfaceID.SettingsSide.DROPDOWN_CONTAINER, InterfaceID.SettingsSide.DROPDOWN_PANEL,
			InterfaceID.SettingsSide.DROPDOWN_SCROLLBAR, InterfaceID.SettingsSide.TOOLTIP, InterfaceID.SettingsSide.UNIVERSE,
			OPTIONS_ENUM, SETTING_ID, GameLayoutSwitch.LAYOUT_STRUCT);
		// The option is a dynamic child, addressed by its index, and op 1 is its Select.
		verify(c).menuAction(3, BUTTONS, MenuAction.CC_OP, 1, -1, "Select", "Resizable - Modern layout");
	}

	@Test
	public void aStructThatIsNotTheLayoutSettingIsNeverOpenedOrPressed()
	{
		// After a game update moves the setting, struct 2732 is something else; option 3 of that is not ours.
		final Client c = client(true, 0, "Show the Wilderness skull", builtOptions());

		assertFalse(GameLayoutSwitch.press(c, GameLayoutSwitch.MODERN));

		verify(c, never()).runScript(any());
		verifyNothingPressed(c);
	}

	@Test
	public void aDropdownWithoutTheOptionPressesNothingAndIsClosedAgain()
	{
		final Widget container = mock(Widget.class);
		final Widget[] options = {option(0, "", false), option(1, "Fixed - Classic layout", true)};
		final Client c = client(true, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, options);
		when(c.getWidget(InterfaceID.SettingsSide.DROPDOWN_CONTAINER)).thenReturn(container);

		assertFalse(GameLayoutSwitch.press(c, GameLayoutSwitch.MODERN));

		verifyNothingPressed(c);
		verify(container).setHidden(true);
	}

	@Test
	public void anOptionWithoutItsSelectOpIsNotPressed()
	{
		final Widget[] options = {option(0, "", false), option(3, "Resizable - Modern layout", false)};
		final Client c = client(true, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, options);

		assertFalse(GameLayoutSwitch.press(c, GameLayoutSwitch.MODERN));

		verifyNothingPressed(c);
	}

	@Test
	public void aRequestWhileAlreadyModernPressesNothing()
	{
		final Client c = client(true, 1, GameLayoutSwitch.LAYOUT_SETTING_NAME, builtOptions());

		GameLayoutSwitch.request(GameLayoutSwitch.MODERN);
		GameLayoutSwitch.tick(c);

		verify(c, never()).runScript(any());
		verifyNothingPressed(c);
	}

	@Test
	public void aRequestIsPressedOnceOnTheNextTick()
	{
		// One-shot: the press goes to the server, so a request must never repeat it frame after frame.
		final Client c = client(true, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, builtOptions());

		GameLayoutSwitch.request(GameLayoutSwitch.MODERN);
		GameLayoutSwitch.tick(c);
		GameLayoutSwitch.tick(c);
		GameLayoutSwitch.tick(c);

		verify(c).menuAction(3, BUTTONS, MenuAction.CC_OP, 1, -1, "Select", "Resizable - Modern layout");
	}

	@Test
	public void fixedIsNeitherResizableLayout()
	{
		// Fixed - Classic reads arrangement 0 like Resizable - Classic, but a switch away from it must still run.
		final Client fixed = client(false, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, builtOptions());

		assertFalse(GameLayoutSwitch.isShowing(fixed, GameLayoutSwitch.MODERN));
		assertFalse(GameLayoutSwitch.isShowing(fixed, GameLayoutSwitch.CLASSIC));
	}

	@Test
	public void theSwitchRunsBeforeTheBridgesResizableGate()
	{
		// HudVeneerBridge.tick returns early outside a resizable layout; the request is consumed before that.
		final Client fixed = client(false, 0, GameLayoutSwitch.LAYOUT_SETTING_NAME, builtOptions());

		HudVeneerBridge.switchToModernLayout();
		HudVeneerBridge.tick(fixed);

		verify(fixed).menuAction(3, BUTTONS, MenuAction.CC_OP, 1, -1, "Select", "Resizable - Modern layout");
	}
}
