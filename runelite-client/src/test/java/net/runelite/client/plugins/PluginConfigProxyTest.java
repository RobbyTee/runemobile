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
package net.runelite.client.plugins;

import com.google.inject.Guice;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.util.Providers;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A plugin whose dependency shares its config through a public module (EntityHiderPlugin does, for 117HD) has
 * two Config bindings in its injector, the dependency's first. getPluginConfigProxy must still return the
 * plugin's own, or its settings screen shows the dependency's settings.
 */
public class PluginConfigProxyTest
{
	@ConfigGroup("shared")
	public interface SharedConfig extends Config
	{
	}

	@ConfigGroup("own")
	public interface OwnConfig extends Config
	{
	}

	@PluginDescriptor(name = "Shares its config")
	public static class SharingPlugin extends Plugin
	{
		private final SharedConfig config = mock(SharedConfig.class);

		@Provides
		SharedConfig provideConfig()
		{
			return config;
		}

		@Override
		protected Module getPublicModule()
		{
			return b -> b.bind(SharedConfig.class).toProvider(Providers.of(config));
		}
	}

	@PluginDescriptor(name = "Depends on it")
	@PluginDependency(SharingPlugin.class)
	public static class DependentPlugin extends Plugin
	{
		@Provides
		OwnConfig provideConfig(ConfigManager configManager)
		{
			return configManager.getConfig(OwnConfig.class);
		}
	}

	@Test
	public void dependentGetsItsOwnConfig() throws Exception
	{
		final ConfigManager configManager = mock(ConfigManager.class);
		when(configManager.getConfig(any(Class.class))).thenAnswer(a -> mock((Class<?>) a.getArgument(0)));
		RuneLite.setInjector(Guice.createInjector(b -> b.bind(ConfigManager.class).toInstance(configManager)));

		final PluginManager pluginManager = new PluginManager(false, false, null, null, configManager, null,
			new PluginModuleFactory());
		final List<Plugin> plugins = pluginManager.loadPlugins(List.of(SharingPlugin.class, DependentPlugin.class), null);
		assertEquals(2, plugins.size());

		for (Plugin plugin : plugins)
		{
			final Config config = pluginManager.getPluginConfigProxy(plugin);
			if (plugin instanceof DependentPlugin)
			{
				assertTrue("the dependent got " + config, config instanceof OwnConfig);
			}
			else
			{
				assertTrue("the sharing plugin got " + config, config instanceof SharedConfig);
			}
		}
	}
}
