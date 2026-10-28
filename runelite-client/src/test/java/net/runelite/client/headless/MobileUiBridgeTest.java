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

import java.lang.reflect.Proxy;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.plugins.notes.NotesConfig;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MobileUiBridge#hasShownItems}, the plugin list's hasConfig: a plugin gets a settings screen
 * only when the config editor would list something on it. The descriptors come from ConfigManager's own reflection
 * over each config interface. Nothing here reads MobileUiBridge's ENABLED flag, so these pass whichever test loaded
 * the class first.
 */
public class MobileUiBridgeTest
{
	@ConfigGroup("mobileUiBridgeTest")
	interface OneShownItemConfig extends Config
	{
		@ConfigItem(
			keyName = "data",
			name = "",
			description = "",
			hidden = true
		)
		default String data()
		{
			return "";
		}

		@ConfigItem(
			keyName = "shown",
			name = "Shown",
			description = ""
		)
		default boolean shown()
		{
			return true;
		}
	}

	@Test
	public void notesHasNoSettingsScreen()
	{
		// Its one item is the notes text, hidden. A settings screen would hold only "Reset all settings", which wipes it.
		assertFalse(MobileUiBridge.hasShownItems(descriptor(NotesConfig.class)));
	}

	@Test
	public void oneShownItemIsEnough()
	{
		assertTrue(MobileUiBridge.hasShownItems(descriptor(OneShownItemConfig.class)));
	}

	@Test
	public void runeLiteCoreSettingsStillShow()
	{
		// The editor drops the core group's window-chrome items on mobile, but not all of the group.
		assertTrue(MobileUiBridge.hasShownItems(descriptor(RuneLiteConfig.class)));
	}

	@Test
	public void fpsCapIsZeroUnlessThePluginIsOnAndLimiting()
	{
		assertEquals(0, MobileUiBridge.fpsCapFrom(false, "true", "30"));
		assertEquals(0, MobileUiBridge.fpsCapFrom(true, "false", "30"));
		assertEquals(0, MobileUiBridge.fpsCapFrom(true, null, "30"));
		assertEquals(30, MobileUiBridge.fpsCapFrom(true, "true", "30"));
	}

	@Test
	public void fpsCapFallsBackToThePluginDefaultAndRange()
	{
		assertEquals(MobileUiBridge.FPS_CAP_DEFAULT, MobileUiBridge.fpsCapFrom(true, "true", null));
		assertEquals(MobileUiBridge.FPS_CAP_DEFAULT, MobileUiBridge.fpsCapFrom(true, "true", "fast"));
		assertEquals(MobileUiBridge.FPS_CAP_MAX, MobileUiBridge.fpsCapFrom(true, "true", "9999"));
		assertEquals(1, MobileUiBridge.fpsCapFrom(true, "true", "0"));
	}

	@Test
	public void idleLogoutIsTheGameDefaultWhileThePluginIsOff()
	{
		assertEquals(5, MobileUiBridge.idleMinutesFrom(false, "20"));
		assertEquals(20, MobileUiBridge.idleMinutesFrom(true, "20"));
		assertEquals(5, MobileUiBridge.idleMinutesFrom(true, null));
		assertEquals(30, MobileUiBridge.idleMinutesFrom(true, "90"));
		assertEquals(5, MobileUiBridge.idleMinutesFrom(true, "1"));
	}

	@Test
	public void removingTheCapOnlyStopsLimiting()
	{
		assertArrayEquals(new String[][]{{"fpscontrol", "limitFps", "false"}}, MobileUiBridge.fpsCapWrites(0, true));
		assertArrayEquals(new String[][]{{"fpscontrol", "limitFps", "false"}}, MobileUiBridge.fpsCapWrites(-1, false));
	}

	@Test
	public void aCapThatTurnsThePluginOnAlsoHidesItsCounter()
	{
		assertArrayEquals(new String[][]{
			{"fpscontrol", "limitFps", "true"}, {"fpscontrol", "maxFps", "30"}, {"fpscontrol", "drawFps", "false"}},
			MobileUiBridge.fpsCapWrites(30, false));
		// Already on: the player's counter choice stands.
		assertArrayEquals(new String[][]{{"fpscontrol", "limitFps", "true"}, {"fpscontrol", "maxFps", "30"}},
			MobileUiBridge.fpsCapWrites(30, true));
		assertEquals("360", MobileUiBridge.fpsCapWrites(1000, true)[1][2]);
	}

	@Test
	public void idleWritesStayInThePluginRange()
	{
		assertArrayEquals(new String[][]{{"logouttimer", "idleTimeout", "12"}}, MobileUiBridge.idleWrites(12));
		assertEquals("5", MobileUiBridge.idleWrites(1)[0][2]);
		assertEquals("30", MobileUiBridge.idleWrites(45)[0][2]);
	}

	@Test
	public void anHtmlHubDescriptionReachesTheRowsAsPlainText()
	{
		assertEquals("Talks to the 1Password CLI. Needs op & a vault",
			MobileUiBridge.hubDescription("<html>Talks to the <b>1Password</b> CLI.<br>Needs <i>op</i> &amp; a vault</html>"));
		assertEquals("First. Second, in bold.",
			MobileUiBridge.hubDescription("<html><p>First.</p><p>Second, in <b>bold</b>.</p></html>"));
		// Desktop escapes a description without the prefix, so its text stands as written.
		assertEquals("Shows <b> as typed", MobileUiBridge.hubDescription("Shows <b> as typed"));
		assertEquals(null, MobileUiBridge.hubDescription(null));
	}

	private static ConfigDescriptor descriptor(Class<? extends Config> iface)
	{
		final ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfigDescriptor(any())).thenCallRealMethod();
		final Config proxy = (Config) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
			(p, method, args) -> null);
		return cm.getConfigDescriptor(proxy);
	}
}
