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

import com.google.inject.Injector;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Phase-6 (W3) STEP-0 de-risk smoke for {@link MobileUiBridge}, runnable <b>on device</b> (no JUnit). The
 * desktop harness ({@code ios/dev-tools/headless/MobileUiSmoke.java}) activates CACIO via the byte-buddy
 * JUnit extension; the device has no JUnit and no self-attach, so this lives in the shipping jar and is
 * armed by {@code RuneLiteHeadless.main} — covering BOTH boots (desktop {@code CacioHeadlessTest} and device
 * {@code DeviceHeadlessBoot} both call {@code RuneLiteHeadless.main}) from one code path, so desktop GREEN
 * re-proves the exact bytes that run on device.
 *
 * <p>Dormant unless {@code -Drunelite.mobileui.smoke=true}. When armed, {@link #arm()} spawns a daemon
 * thread that waits for the injector + the wired facade, then drives the facade exactly as the native shell
 * will and prints {@code MUI_*=...} markers to stdout (→ the device {@code jvm-stdio.log}). The verdict is
 * computed OUTSIDE the JVM in bash, md5-gated against the verbatim log — never a JVM-printed success string
 * (the documented false-GREEN guard).
 *
 * <p>What it proves: {@code -ea} live (so the {@code startPlugin} EDT asserts aren't silent no-ops);
 * {@code listPlugins()} parses; a config write fires {@code ConfigChanged} ON THE CLIENT THREAD (not EDT);
 * a plugin toggle runs ON THE EDT, becomes active, and registers an overlay headless; the chat-input
 * snapshot is plausible when logged in; and a config value PERSISTS across launches (each run reads the
 * previous run's sentinel back off disk, then writes its own).
 */
@Slf4j
public final class MobileUiSmokeRunner
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobileui.smoke");
	private static final String SENTINEL_GROUP = "mobileuismoke";
	private static final String SENTINEL_KEY = "probe";

	private MobileUiSmokeRunner()
	{
	}

	/** Spawn the smoke daemon iff {@code -Drunelite.mobileui.smoke=true}. Safe no-op otherwise. */
	public static void arm()
	{
		if (!ENABLED)
		{
			return;
		}
		final Thread t = new Thread(MobileUiSmokeRunner::runDaemon, "mobileui-smoke");
		t.setDaemon(true);
		t.start();
		log.info("[mui-smoke] armed");
	}

	private static void runDaemon()
	{
		try
		{
			out("MUI_PHASE=device");
			// (1) positive control: is -ea actually on? Otherwise the EDT asserts are no-ops.
			boolean ea = false;
			// noinspection AssertWithSideEffects,ConstantConditions
			assert (ea = true);
			out("MUI_EA=" + ea);

			if (!await("injector", 90_000, () -> RuneLite.getInjector() != null))
			{
				out("MUI_FATAL=no-injector");
				return;
			}
			final Injector injector = RuneLite.getInjector();
			final EventBus eventBus = injector.getInstance(EventBus.class);
			final PluginManager pluginManager = injector.getInstance(PluginManager.class);
			final OverlayManager overlayManager = injector.getInstance(OverlayManager.class);
			final ConfigManager configManager = injector.getInstance(ConfigManager.class);
			final Client client = injector.getInstance(Client.class);

			await("facade-wired", 60_000, () -> !"[]".equals(MobileUiBridge.listPlugins()));

			runChecks(eventBus, pluginManager, overlayManager, configManager, client);
		}
		catch (Throwable ex)
		{
			out("MUI_FATAL=" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
			log.warn("[mui-smoke] failed", ex);
		}
	}

	/**
	 * The shared check routine — identical on desktop and device. Prints {@code MUI_*} markers. Restores any
	 * plugin it toggled. Does not throw (records {@code MUI_FATAL} on a hard failure and returns).
	 */
	public static void runChecks(EventBus eventBus, PluginManager pluginManager, OverlayManager overlayManager,
		ConfigManager configManager, Client client) throws InterruptedException
	{
		// (PERSIST a) read back whatever a PRIOR launch flushed to disk (config is loaded at boot). On the
		// first-ever launch this is null; on a second launch it must equal the first launch's MUI_PERSIST_WRITTEN.
		final String preload = configManager.getConfiguration(SENTINEL_GROUP, SENTINEL_KEY);
		out("MUI_PERSIST_PRELOAD=" + preload);

		final Probe probe = new Probe(client);
		eventBus.register(probe);

		// (2) listPlugins snapshot
		final String list = MobileUiBridge.listPlugins();
		final int pluginCount = countOccurrences(list, "\"className\":");
		out("MUI_LIST_COUNT=" + pluginCount);
		out("MUI_LIST_OK=" + (pluginCount > 0 && list.startsWith("[") && list.endsWith("]")));

		// (3) config write -> CLIENT thread (not EDT)
		final String sentinel = "v" + Integer.toHexString(System.identityHashCode(probe)) + pluginCount
			+ "x" + (preload == null ? "0" : Integer.toHexString(preload.hashCode()));
		MobileUiBridge.setConfig(SENTINEL_GROUP, SENTINEL_KEY, sentinel);
		await("config-event", 10_000, () -> probe.lastConfig != null);
		final boolean configFired = probe.lastConfig != null;
		out("MUI_CONFIG_FIRED=" + configFired);
		out("MUI_CONFIG_VALUE_OK=" + (configFired && sentinel.equals(probe.lastConfig.getNewValue())));
		out("MUI_CONFIG_ON_CLIENT_THREAD=" + probe.configOnClientThread);
		out("MUI_CONFIG_ON_EDT=" + probe.configOnEdt);

		// (4) plugin toggle -> EDT (+ becomes active + overlay registers headless). SEARCH for a plugin
		// whose enable grows the OverlayManager set, so we exercise the headless-overlay path, not a no-op.
		boolean toggleEdtOk = false;
		boolean toggleActiveOk = false;
		boolean overlayGrew = false;
		String chosen = "(none)";
		int attempts = 0;
		for (Plugin p : inactiveTogglable(pluginManager))
		{
			if (++attempts > 30)
			{
				break;
			}
			final String cls = p.getClass().getName();
			final int before = overlayCount(overlayManager);
			probe.lastPlugin = null;
			probe.pluginOnEdt = false;
			MobileUiBridge.setPluginEnabled(cls, true);
			await("plugin-active:" + p.getName(), 8_000, () -> pluginManager.isPluginActive(p));
			Thread.sleep(400);
			final boolean active = pluginManager.isPluginActive(p);
			final int after = overlayCount(overlayManager);
			if (active && chosen.equals("(none)"))
			{
				chosen = p.getName();
				toggleActiveOk = true;
				toggleEdtOk = probe.pluginOnEdt;
			}
			if (active && after > before)
			{
				chosen = p.getName();
				toggleActiveOk = true;
				toggleEdtOk = probe.pluginOnEdt;
				overlayGrew = true;
				out("MUI_OVERLAY_BEFORE=" + before);
				out("MUI_OVERLAY_AFTER=" + after);
				MobileUiBridge.setPluginEnabled(cls, false);
				Thread.sleep(200);
				break;
			}
			MobileUiBridge.setPluginEnabled(cls, false);
			Thread.sleep(150);
		}
		out("MUI_TOGGLE_TARGET=" + chosen);
		out("MUI_TOGGLE_ACTIVE=" + toggleActiveOk);
		out("MUI_TOGGLE_ON_EDT=" + toggleEdtOk);
		out("MUI_OVERLAY_GREW=" + overlayGrew);
		out("MUI_TOGGLE_OK=" + (toggleActiveOk && toggleEdtOk));

		// (PERSIST b) flush this run's sentinel so the NEXT launch reads it back as MUI_PERSIST_PRELOAD.
		configManager.sendConfig();
		out("MUI_PERSIST_WRITTEN=" + sentinel);
		out("MUI_PERSIST_XLAUNCH_OK=" + (preload != null));  // true on the 2nd+ launch (proves disk round-trip)

		// (6) chat-input bounds (only meaningful in-game)
		final boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		out("MUI_LOGGED_IN=" + loggedIn);
		if (loggedIn)
		{
			Thread.sleep(1000); // let a tick() snapshot land
			final int[] b = MobileUiBridge.getChatInputBounds();
			final boolean vis = MobileUiBridge.isChatInputVisible();
			out("MUI_CHAT_VISIBLE=" + vis);
			out("MUI_CHAT_BOUNDS=" + b[0] + "," + b[1] + "," + b[2] + "," + b[3]);
			out("MUI_CHAT_OK=" + (vis && b[2] > 0 && b[3] > 0));
		}

		out("MUI_DONE=1");
	}

	// ---- event probe ----------------------------------------------------------------------------------

	/** Records the thread each event was delivered on (the whole point: lane → thread). */
	public static final class Probe
	{
		volatile ConfigChanged lastConfig;
		volatile boolean configOnClientThread;
		volatile boolean configOnEdt;
		volatile PluginChanged lastPlugin;
		volatile boolean pluginOnEdt;

		private final Client client;

		public Probe(Client client)
		{
			this.client = client;
		}

		@Subscribe
		public void onConfigChanged(ConfigChanged e)
		{
			if (SENTINEL_GROUP.equals(e.getGroup()))
			{
				configOnClientThread = client.isClientThread();
				configOnEdt = java.awt.EventQueue.isDispatchThread();
				lastConfig = e;
			}
		}

		@Subscribe
		public void onPluginChanged(PluginChanged e)
		{
			pluginOnEdt = java.awt.EventQueue.isDispatchThread();
			lastPlugin = e;
		}
	}

	// ---- helpers --------------------------------------------------------------------------------------

	private static List<Plugin> inactiveTogglable(PluginManager pm)
	{
		final List<String> preferred = Arrays.asList(
			"Ground Items", "Ground Markers", "Screen Markers", "Tile Indicators", "World Map",
			"Coordinates", "Menu Entry Swapper", "Mouse Highlight");
		final List<Plugin> head = new ArrayList<>();
		final List<Plugin> tail = new ArrayList<>();
		for (Plugin p : pm.getPlugins())
		{
			final PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
			if (d == null || d.hidden() || d.developerPlugin() || pm.isPluginActive(p))
			{
				continue;
			}
			(preferred.contains(p.getName()) ? head : tail).add(p);
		}
		head.addAll(tail);
		return head;
	}

	@SuppressWarnings("unchecked")
	private static int overlayCount(OverlayManager om)
	{
		try
		{
			final Field f = OverlayManager.class.getDeclaredField("overlays");
			f.setAccessible(true);
			return ((List<Object>) f.get(om)).size();
		}
		catch (ReflectiveOperationException ex)
		{
			return -1;
		}
	}

	private static int countOccurrences(String haystack, String needle)
	{
		int n = 0, i = 0;
		while ((i = haystack.indexOf(needle, i)) >= 0)
		{
			n++;
			i += needle.length();
		}
		return n;
	}

	private interface Cond
	{
		boolean ok();
	}

	private static boolean await(String what, long timeoutMs, Cond c) throws InterruptedException
	{
		final long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline)
		{
			if (c.ok())
			{
				return true;
			}
			Thread.sleep(100);
		}
		out("[mui-smoke] timed out waiting for " + what);
		return false;
	}

	/** Markers go to stdout so they land in the device {@code jvm-stdio.log} verbatim for the bash md5-gate. */
	private static void out(String s)
	{
		System.out.println(s);
		System.out.flush();
	}
}
