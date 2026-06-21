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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.config.ProfileManager;

/**
 * Native-UI bridge for <b>config profiles</b> — the shared-core seam the mobile shell uses to list the user's
 * config profiles and switch the active one, mirroring the desktop {@code ProfilePanel}. The desktop panel
 * reads the profile list under a {@link ProfileManager.Lock} on a background executor and drives a switch via
 * {@link ConfigManager#switchProfile(ConfigProfile)} on that same executor; this facade does the same so the
 * heavy switch (config reload + {@code ConfigChanged} fan-out + optional remote sync) never blocks the engine
 * frame loop. Wired from {@code HeadlessClientUI} alongside the rest of the mobile-UI facade.
 *
 * <p>Reads ({@link #getProfilesJson()}) take the profile-store file lock inline — not a per-frame hot path
 * (the native side reads only while the profiles screen is open), so no client-thread snapshot is needed.
 */
@Slf4j
public final class ConfigProfileBridge
{
	private static volatile ConfigManager configManager;
	private static volatile ProfileManager profileManager;
	// Switches run here (a daemon single-thread executor), exactly as the desktop ProfilePanel runs them on its
	// background executor — never the client thread, which a multi-second reload/sync would stall.
	private static volatile ExecutorService switchExecutor;

	private ConfigProfileBridge()
	{
	}

	public static void init(ConfigManager cm, ProfileManager pm)
	{
		configManager = cm;
		profileManager = pm;
	}

	/** Whether the native shell should offer the profiles screen (both singletons wired). */
	public static boolean isAvailable()
	{
		return configManager != null && profileManager != null;
	}

	/**
	 * Snapshot of the config profiles as JSON:
	 * <pre>
	 * {"available":true,"profiles":[
	 *   {"id":123,"name":"default","active":true,"sync":false,"internal":false}, ...]}
	 * </pre>
	 * {@code internal} flags the engine's own profiles (e.g. {@code $rsprofile}) so the native side can hide
	 * them like the desktop panel does. {@code active} marks the currently-loaded profile.
	 */
	public static String getProfilesJson()
	{
		final ConfigManager cm = configManager;
		final ProfileManager pm = profileManager;
		if (cm == null || pm == null)
		{
			return "{\"available\":false}";
		}
		try (ProfileManager.Lock lock = pm.lock())
		{
			final ConfigProfile active = cm.getProfile();
			final long activeId = active == null ? -1 : active.getId();
			final List<ConfigProfile> profiles = lock.getProfiles();
			final StringBuilder sb = new StringBuilder(256);
			sb.append("{\"available\":true,\"profiles\":[");
			boolean first = true;
			for (ConfigProfile p : profiles)
			{
				if (p == null)
				{
					continue;
				}
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				sb.append('{')
					.append("\"id\":").append(p.getId()).append(',')
					.append("\"name\":").append(quote(p.getName())).append(',')
					.append("\"active\":").append(p.getId() == activeId).append(',')
					.append("\"sync\":").append(p.isSync()).append(',')
					.append("\"internal\":").append(p.isInternal())
					.append('}');
			}
			sb.append("]}");
			return sb.toString();
		}
		catch (Throwable ex)
		{
			BridgeFaults.note("ConfigProfileBridge.getProfilesJson", ex);
			return "{\"available\":false}";
		}
	}

	/**
	 * Switch the active config profile by id. Returns a JSON ack immediately; the switch itself runs on the
	 * background executor and completes asynchronously (the native side re-reads {@link #getProfilesJson()} to
	 * pick up the new active flag). {@code ok:false} when the bridge is unwired, the id is unknown, or it is
	 * already the active profile.
	 */
	public static String switchProfile(long id)
	{
		final ConfigManager cm = configManager;
		final ProfileManager pm = profileManager;
		if (cm == null || pm == null)
		{
			return ack(false, "unavailable");
		}
		final ConfigProfile active = cm.getProfile();
		if (active != null && active.getId() == id)
		{
			return ack(false, "already-active");
		}
		final ConfigProfile target;
		try (ProfileManager.Lock lock = pm.lock())
		{
			target = lock.findProfile(id);
		}
		catch (Throwable ex)
		{
			BridgeFaults.note("ConfigProfileBridge.switchProfile.find", ex);
			return ack(false, "lookup-failed");
		}
		if (target == null)
		{
			return ack(false, "unknown");
		}
		executor().submit(() ->
		{
			try
			{
				cm.switchProfile(target);
			}
			catch (Throwable ex)
			{
				BridgeFaults.note("ConfigProfileBridge.switchProfile", ex);
			}
		});
		return ack(true, null);
	}

	private static synchronized ExecutorService executor()
	{
		if (switchExecutor == null)
		{
			switchExecutor = Executors.newSingleThreadExecutor(r ->
			{
				final Thread t = new Thread(r, "mobile-profile-switch");
				t.setDaemon(true);
				return t;
			});
		}
		return switchExecutor;
	}

	private static String ack(boolean ok, String error)
	{
		final StringBuilder sb = new StringBuilder(48);
		sb.append("{\"ok\":").append(ok);
		if (error != null)
		{
			sb.append(",\"error\":").append(quote(error));
		}
		sb.append('}');
		return sb.toString();
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
