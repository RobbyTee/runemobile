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

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.worldhopper.WorldHopperPlugin;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import net.runelite.http.api.worlds.WorldType;

/**
 * Native-UI data bridge for <b>World Switcher</b>. Unlike {@link QuestHelperBridge}, the world data is part of
 * the client/API ({@link WorldService} → {@link World}), so reads are <b>direct</b> — no reflection. Only the
 * <em>hop</em> action reflects, because {@code WorldHopperPlugin.hop(int)} is private and lives in another
 * package; reflecting one private method is cheaper than re-implementing the world-hop handshake.
 *
 * <p>The world list snapshot is rebuilt on the {@link ClientThread} via {@link #tick(Client)} (current world +
 * favourites read live game state) and published as volatile JSON the native thread reads lock-free. A hop
 * queues onto the client thread. Refresh only runs while the native screen is open ({@link #setUiOpen}).
 */
@Slf4j
public final class WorldSwitcherBridge
{
	private static volatile Client client;
	private static volatile ClientThread clientThread;
	private static volatile PluginManager pluginManager;
	private static volatile WorldService worldService;

	private static volatile boolean uiOpen;
	private static volatile String listJson = "{\"available\":false}";
	private static long lastRefresh;

	// Reflected hop(int) on the WorldHopper plugin (private; resolved lazily).
	private static Object worldHopper;
	private static Method hopMethod;

	private WorldSwitcherBridge()
	{
	}

	public static void init(Client c, ClientThread ct, PluginManager pm)
	{
		client = c;
		clientThread = ct;
		pluginManager = pm;
	}

	private static WorldService worldService()
	{
		WorldService ws = worldService;
		if (ws == null)
		{
			try
			{
				ws = RuneLite.getInjector().getInstance(WorldService.class);
				worldService = ws;
			}
			catch (Throwable ignore)
			{
			}
		}
		return ws;
	}

	public static boolean isAvailable()
	{
		final WorldService ws = worldService();
		return ws != null && ws.getWorlds() != null;
	}

	public static void setUiOpen(boolean open)
	{
		uiOpen = open;
		if (open)
		{
			final WorldService ws = worldService();
			if (ws != null)
			{
				ws.refresh(); // kick a fresh world-list fetch when the screen opens
			}
			lastRefresh = 0;
		}
	}

	public static String getWorldListJson()
	{
		return listJson;
	}

	/** Hop to {@code worldId} (client thread; reflects WorldHopperPlugin.hop(int)). Requires being logged in. */
	public static void hop(int worldId)
	{
		final ClientThread ct = clientThread;
		if (ct == null)
		{
			return;
		}
		ct.invokeLater(() ->
		{
			if (!resolveHop())
			{
				log.info("[worldswitcher] hop {} aborted: WorldHopper plugin not found", worldId);
				return;
			}
			final PluginManager pm = pluginManager;
			final Plugin p = (Plugin) worldHopper;
			final Client c = client;
			log.info("[worldswitcher] hop {} -> gameState={} pluginEnabled={} pluginActive={}",
				worldId,
				c == null ? "?" : c.getGameState(),
				pm != null && pm.isPluginEnabled(p),
				pm != null && pm.isPluginActive(p));
			try
			{
				hopMethod.invoke(worldHopper, worldId);
				log.info("[worldswitcher] hop {} invoked ok (quick-hop target set; completes on game ticks)", worldId);
			}
			catch (Throwable ex)
			{
				log.warn("[worldswitcher] hop {} failed", worldId, ex);
			}
		});
	}

	public static void tick(Client c)
	{
		if (!uiOpen || c == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastRefresh < 1500)
		{
			return;
		}
		lastRefresh = now;
		rebuild(c);
	}

	private static void rebuild(Client c)
	{
		try
		{
			final WorldService ws = worldService();
			final WorldResult res = ws == null ? null : ws.getWorlds();
			if (res == null)
			{
				listJson = "{\"available\":false}";
				return;
			}
			final List<World> worlds = res.getWorlds();
			final int current = c.getWorld();
			final StringBuilder sb = new StringBuilder(16384);
			sb.append("{\"available\":true,\"current\":").append(current).append(",\"worlds\":[");
			boolean first = true;
			for (World w : worlds)
			{
				final EnumSet<WorldType> types = EnumSet.copyOf(w.getTypes());
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				sb.append('{')
					.append("\"id\":").append(w.getId()).append(',')
					.append("\"players\":").append(w.getPlayers()).append(',')
					.append("\"location\":").append(w.getLocation()).append(',')
					.append("\"members\":").append(types.contains(WorldType.MEMBERS)).append(',')
					.append("\"pvp\":").append(types.contains(WorldType.PVP)).append(',')
					.append("\"highRisk\":").append(types.contains(WorldType.HIGH_RISK)).append(',')
					.append("\"activity\":").append(quote(w.getActivity())).append(',')
					.append("\"types\":[");
				boolean ft = true;
				for (WorldType t : types)
				{
					if (!ft)
					{
						sb.append(',');
					}
					ft = false;
					sb.append(quote(t.name()));
				}
				sb.append("]}");
			}
			sb.append("]}");
			listJson = sb.toString();
		}
		catch (Throwable ex)
		{
			log.debug("[worldswitcher] list refresh failed", ex);
			listJson = "{\"available\":false}";
		}
	}

	private static boolean resolveHop()
	{
		if (hopMethod != null && worldHopper != null)
		{
			return true;
		}
		final PluginManager pm = pluginManager;
		if (pm == null)
		{
			return false;
		}
		try
		{
			for (Plugin p : pm.getPlugins())
			{
				if (p instanceof WorldHopperPlugin)
				{
					final Method m = WorldHopperPlugin.class.getDeclaredMethod("hop", int.class);
					m.setAccessible(true);
					worldHopper = p;
					hopMethod = m;
					return true;
				}
			}
		}
		catch (Throwable ex)
		{
			log.debug("[worldswitcher] resolve hop failed", ex);
		}
		return false;
	}

	private static String quote(String s)
	{
		if (s == null)
		{
			return "\"\"";
		}
		final StringBuilder sb = new StringBuilder(s.length() + 2);
		sb.append('"');
		for (int i = 0; i < s.length(); i++)
		{
			final char ch = s.charAt(i);
			switch (ch)
			{
				case '"': sb.append("\\\""); break;
				case '\\': sb.append("\\\\"); break;
				case '\n': sb.append("\\n"); break;
				case '\r': sb.append("\\r"); break;
				case '\t': sb.append("\\t"); break;
				default:
					if (ch < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) ch));
					}
					else
					{
						sb.append(ch);
					}
			}
		}
		sb.append('"');
		return sb.toString();
	}
}
