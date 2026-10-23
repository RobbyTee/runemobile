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

	private static ConfigDescriptor descriptor(Class<? extends Config> iface)
	{
		final ConfigManager cm = mock(ConfigManager.class);
		when(cm.getConfigDescriptor(any())).thenCallRealMethod();
		final Config proxy = (Config) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
			(p, method, args) -> null);
		return cm.getConfigDescriptor(proxy);
	}
}
