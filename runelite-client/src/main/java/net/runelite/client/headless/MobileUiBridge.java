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

import java.awt.EventQueue;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.externalplugins.ExternalPluginClient;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigItemDescriptor;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;

/**
 * Phase-6 (W3): the second reverse-JNI facade — the <em>Java-owned</em> mobile-UI control plane, sibling to
 * the hot-path {@link JavaBridge} (frame blit + input). Where {@code JavaBridge} is a thin pipe the native
 * side drives every display tick, this is a <em>thick</em> facade: the native UIKit chrome (plugin list,
 * config panel, chat-tap) calls a handful of coarse methods, and <b>this class owns the RuneLite-internal
 * details</b> — most importantly <em>which Java thread</em> each mutation runs on.
 *
 * <h2>Why a facade and not native reflection over JNI</h2>
 * The desktop config UI mutates plugins/config from the Swing event thread, and the engine relies on it:
 * <ul>
 *   <li>{@code PluginManager.startPlugin}/{@code stopPlugin} {@code assert SwingUtilities.isEventDispatchThread()}
 *       — they MUST run on the AWT EDT (under iOS, the CACIO CTC event thread).</li>
 *   <li>{@code ConfigManager.setConfiguration} posts {@code ConfigChanged} <b>synchronously</b> via the
 *       single-threaded {@code EventBus}, so every subscriber runs on the calling thread. The desktop
 *       calls it from the EDT; subscribers that touch the running client re-dispatch onto the
 *       {@link ClientThread} themselves.</li>
 * </ul>
 * If the native side called those directly, they would run on the JNI-attached UIKit thread — wrong thread,
 * silent corruption (worse still if assertions are disabled, where the {@code assert} is a no-op). So this
 * facade exposes exactly two dispatch lanes and routes every mutation through the correct one:
 * <ul>
 *   <li>{@link #onEdt(Runnable)} — plugin lifecycle (start/stop), mirroring {@code PluginListPanel}.</li>
 *   <li>{@link #onClientThread(Runnable)} — config writes + any read/write that touches live game state
 *       (widget bounds, varbits), mirroring how desktop config subscribers behave.</li>
 * </ul>
 *
 * <h2>Shape of the surface</h2>
 * Reads return <b>JSON snapshots</b> (hand-built, escaped) so the JNI surface is a few {@code String}-returning
 * static methods — trivial to cache method ids for and to parse into Swift value types <em>once</em> per
 * panel open (never bound live to SwiftUI at frame rate). Writes are fire-and-forget: they queue onto the
 * right lane and return immediately; the resulting {@code ConfigChanged}/{@code PluginChanged} is the source
 * of truth, observed by re-reading the snapshot. Live UI-state the chrome polls (chat-input bounds, soft-input
 * open) is snapshotted on the client thread in {@link #tick(Client)} into volatiles and read lock-free.
 *
 * <p>Entirely inert unless {@code -Drunelite.mobileui=true}. {@link #init} wires the injected singletons in
 * once at startup (from {@code HeadlessClientUI}); {@link #tick} runs each frame from {@code Hooks.draw}.
 */
@Slf4j
public final class MobileUiBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobileui");

	// Wired once by init() from HeadlessClientUI (which has these injected). volatile: published from the
	// engine/EDT init path, read from the native JNI thread.
	private static volatile PluginManager pluginManager;
	private static volatile ConfigManager configManager;
	private static volatile ClientThread clientThread;
	private static volatile Client client;
	private static volatile EventBus eventBus;
	private static volatile boolean listenerRegistered;

	// ---- Event/ack surface (the panel's refresh + write-confirm signal) --------------------------------
	// A monotonic sequence over ConfigChanged/PluginChanged so the panel can poll for changes (incl. ones it
	// did NOT make) and re-read only when something moved — never bind live at frame rate. Separately, every
	// queued write gets an id and the client thread records the highest id it has PROCESSED, so the panel
	// gets an ack even for a no-op write (which emits no ConfigChanged: ConfigManager early-returns when the
	// value is unchanged). Bounded ring of recent events for delta polling.
	private static final AtomicLong eventSeq = new AtomicLong();
	private static final AtomicLong writeSeq = new AtomicLong();
	private static volatile long processedWriteId;
	private static final int MAX_EVENTS = 256;
	private static final Object eventsLock = new Object();
	private static final ArrayDeque<Evt> events = new ArrayDeque<>();

	private static final class Evt
	{
		final long seq;
		final String kind; // "config" | "plugin"
		final String group; // config group, or plugin className
		final String key;   // config key, or "loaded"/"unloaded"

		Evt(long seq, String kind, String group, String key)
		{
			this.seq = seq;
			this.kind = kind;
			this.group = group;
			this.key = key;
		}
	}

	// Live UI-state snapshot (written on the client thread in tick(), read lock-free from the native thread).
	private static volatile boolean chatInputVisible;
	private static volatile int chatInputX;
	private static volatile int chatInputY;
	private static volatile int chatInputW;
	private static volatile int chatInputH;

	private MobileUiBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Wire the facade to the live singletons. Called once from {@code HeadlessClientUI.init()} — the one
	 * component that already has {@link PluginManager}/{@link ConfigManager}/{@link ClientThread} injected —
	 * so this class stays a pure static JNI facade (the native side cannot hold a Guice-built instance).
	 */
	public static void init(PluginManager pm, ConfigManager cm, ClientThread ct, Client c, EventBus eb)
	{
		pluginManager = pm;
		configManager = cm;
		clientThread = ct;
		client = c;
		eventBus = eb;
		if (eb != null && !listenerRegistered)
		{
			eb.register(new Listener());
			listenerRegistered = true;
		}
		log.info("[mobileui] facade wired: plugins={} config={} clientThread={} eventBus={}",
			pm != null, cm != null, ct != null, eb != null);
	}

	/**
	 * The EventBus subscriber that feeds the panel's revision surface. Public, non-static, {@code on<Event>}
	 * names — the shape {@code EventBus.register} requires. Runs on whatever thread posts the event (config
	 * on the client thread, plugin on the EDT); {@link #recordEvent} is the only mutation and it is guarded.
	 */
	public static final class Listener
	{
		@Subscribe
		public void onConfigChanged(ConfigChanged e)
		{
			recordEvent("config", e.getGroup(), e.getKey());
		}

		@Subscribe
		public void onPluginChanged(PluginChanged e)
		{
			recordEvent("plugin", e.getPlugin().getClass().getName(), e.isLoaded() ? "loaded" : "unloaded");
		}
	}

	private static void recordEvent(String kind, String group, String key)
	{
		final long s = eventSeq.incrementAndGet();
		synchronized (eventsLock)
		{
			events.addLast(new Evt(s, kind, group, key));
			while (events.size() > MAX_EVENTS)
			{
				events.removeFirst();
			}
		}
	}

	/** Latest event sequence number. The panel stores this and polls {@link #pollMobileUiEvents(long)} from it. */
	public static long getMobileUiSeq()
	{
		return eventSeq.get();
	}

	/**
	 * Events with {@code seq > sinceSeq}, as {@code {seq, events:[{seq,kind,group,key}]}}. The panel diffs:
	 * any {@code config} event for a group it shows → re-read that group; any {@code plugin} event → re-read
	 * the plugin list (a toggle can disable conflicting plugins). Returns the latest {@code seq} so the panel
	 * advances its cursor even when no event matched.
	 */
	public static String pollMobileUiEvents(long sinceSeq)
	{
		final Json out = new Json().obj().kl("seq", eventSeq.get()).key("events").arr();
		synchronized (eventsLock)
		{
			for (Evt e : events)
			{
				if (e.seq > sinceSeq)
				{
					out.obj().kl("seq", e.seq).kv("kind", e.kind).kv("group", e.group).kvNullable("key", e.key).end();
				}
			}
		}
		return out.end().end().toString();
	}

	/** Highest write id whose client-thread runnable has finished — the fire-and-forget write ack. */
	public static long getProcessedWriteId()
	{
		return processedWriteId;
	}

	/**
	 * Flush pending config to disk now ({@code ConfigManager.sendConfig}, which writes only the dirty delta).
	 * On desktop this happens on a 5-minute timer + a clean shutdown; on iOS the app is closed/killed without
	 * running either, so the native shell calls this at explicit checkpoints (config/panel close, app
	 * background) to make plugin toggles and config edits survive a relaunch. Safe to call off-thread (the
	 * desktop flush runs on a scheduled executor too).
	 */
	public static String flushConfig()
	{
		final ConfigManager cm = configManager;
		if (!ENABLED || cm == null)
		{
			return "{\"ok\":false,\"reason\":\"facade-off\"}";
		}
		cm.sendConfig();
		return "{\"ok\":true}";
	}

	// ---- Plugin hub (browse / install) -----------------------------------------------------------------
	// Resolved lazily from the injector (not part of init()'s wiring). The manifest is downloaded ONCE on a
	// background daemon (network must not touch the JNI/client thread) and cached; hubManifest() returns
	// {status:loading} until ready, then the full plugin list with live installed flags. install/uninstall
	// route onto the client thread (config write) and flush so the installed list survives a relaunch.

	private static volatile ExternalPluginManager extPluginManager;
	private static volatile ExternalPluginClient extPluginClient;
	private static volatile List<PluginHubManifest.DisplayData> hubDisplay;
	private static volatile String hubStatus = "idle";
	private static volatile boolean hubLoading;

	private static void ensureHubInjected()
	{
		if (extPluginManager != null && extPluginClient != null)
		{
			return;
		}
		final com.google.inject.Injector inj = RuneLite.getInjector();
		if (inj != null)
		{
			extPluginManager = inj.getInstance(ExternalPluginManager.class);
			extPluginClient = inj.getInstance(ExternalPluginClient.class);
		}
	}

	/**
	 * Plugin-hub catalog as JSON. First call kicks off a one-time background manifest download and returns
	 * {@code {"status":"loading"}}; the native side polls until {@code status} is {@code ok} (or
	 * {@code error:*}). When ready: {@code {status:ok, plugins:[{internalName,displayName,author,description,
	 * version,unavailable,installed}]}} with {@code installed} recomputed live each call.
	 */
	public static String hubManifest()
	{
		if (!ENABLED)
		{
			return "{\"status\":\"off\"}";
		}
		ensureHubInjected();
		if (extPluginClient == null || extPluginManager == null)
		{
			return "{\"status\":\"not-ready\"}";
		}
		final List<PluginHubManifest.DisplayData> display = hubDisplay;
		if (display == null)
		{
			if (!hubLoading)
			{
				hubLoading = true;
				hubStatus = "loading";
				final Thread t = new Thread(() ->
				{
					try
					{
						hubDisplay = extPluginClient.downloadManifestFull().getDisplay();
						hubStatus = "ok";
					}
					catch (Throwable ex)
					{
						hubStatus = "error:" + ex.getClass().getSimpleName();
						log.warn("[mobileui] hub manifest download failed", ex);
					}
					finally
					{
						hubLoading = false;
					}
				}, "mobileui-hub-manifest");
				t.setDaemon(true);
				t.start();
			}
			return "{\"status\":\"" + hubStatus + "\"}";
		}
		final Set<String> installed = new HashSet<>(extPluginManager.getInstalledExternalPlugins());
		final Json out = new Json().obj().kv("status", "ok").key("plugins").arr();
		for (PluginHubManifest.DisplayData d : display)
		{
			out.obj()
				.kv("internalName", d.getInternalName())
				.kv("displayName", d.getDisplayName())
				.kvNullable("author", d.getAuthor())
				.kvNullable("description", d.getDescription())
				.kvNullable("version", d.getVersion())
				.kvNullable("unavailable", d.getUnavailableReason())
				.kb("installed", installed.contains(d.getInternalName()))
				.end();
		}
		return out.end().end().toString();
	}

	/** Install a hub plugin by internal name (config write + flush so it persists; load happens async). */
	public static String hubInstall(String internalName)
	{
		return hubInstallOrRemove(internalName, true);
	}

	/** Uninstall a hub plugin by internal name. */
	public static String hubUninstall(String internalName)
	{
		return hubInstallOrRemove(internalName, false);
	}

	private static String hubInstallOrRemove(String internalName, boolean install)
	{
		if (!ENABLED || internalName == null)
		{
			return "{\"ok\":false,\"reason\":\"facade-off\"}";
		}
		ensureHubInjected();
		final ExternalPluginManager epm = extPluginManager;
		final ConfigManager cm = configManager;
		final ClientThread ct = clientThread;
		if (epm == null || cm == null || ct == null)
		{
			return "{\"ok\":false,\"reason\":\"not-ready\"}";
		}
		final long id = enqueueClientWrite(ct, () ->
		{
			if (install)
			{
				epm.install(internalName);
			}
			else
			{
				epm.remove(internalName);
			}
			cm.sendConfig(); // persist the installed list immediately
		});
		return "{\"ok\":true,\"writeId\":" + id + "}";
	}

	/**
	 * Queue {@code r} on the client thread and return its write id. The runnable records the id as processed
	 * when it finishes (even if it was a no-op that emitted no {@code ConfigChanged}), so the panel can poll
	 * {@link #getProcessedWriteId()} for an ack. Caller must pass a non-null {@code ct}.
	 */
	private static long enqueueClientWrite(ClientThread ct, Runnable r)
	{
		final long id = writeSeq.incrementAndGet();
		ct.invokeLater(() ->
		{
			try
			{
				r.run();
			}
			finally
			{
				// invokeLater is FIFO and single-threaded, so ids complete monotonically.
				processedWriteId = id;
			}
		});
		return id;
	}

	// ---- The two dispatch lanes (the whole point of this class) ----------------------------------------

	/**
	 * Run {@code r} on the AWT event thread (under iOS, the CACIO CTC event thread). Use for plugin
	 * lifecycle — {@code PluginManager.startPlugin}/{@code stopPlugin} assert this thread.
	 */
	private static void onEdt(Runnable r)
	{
		EventQueue.invokeLater(r);
	}

	/**
	 * Run {@code r} on the game/client thread. Use for config writes and anything reading/writing live game
	 * state (widgets, varbits) — the same thread the desktop's config subscribers hop onto.
	 */
	private static void onClientThread(Runnable r)
	{
		final ClientThread ct = clientThread;
		if (ct != null)
		{
			ct.invokeLater(r);
		}
	}

	// ---- Reads: JSON snapshots (safe off-thread — they read already-resolved metadata) -----------------

	/**
	 * Snapshot of every (non-hidden) loaded plugin: identity, enabled/active state, and its config group (or
	 * null). The native list renders straight from this. {@code className} is the stable handle the write
	 * methods take.
	 */
	public static String listPlugins()
	{
		final PluginManager pm = pluginManager;
		if (!ENABLED || pm == null)
		{
			return "[]";
		}
		final Json out = new Json().arr();
		for (Plugin p : pm.getPlugins())
		{
			final PluginDescriptor d = p.getClass().getAnnotation(PluginDescriptor.class);
			if (d == null || d.hidden())
			{
				continue;
			}
			String group = null;
			boolean hasConfig = false;
			try
			{
				final Config proxy = pm.getPluginConfigProxy(p);
				if (proxy != null)
				{
					final ConfigDescriptor cd = configManager.getConfigDescriptor(proxy);
					group = cd.getGroup().value();
					hasConfig = !cd.getItems().isEmpty();
				}
			}
			catch (RuntimeException ex)
			{
				// a plugin with no/odd config proxy still belongs in the list (toggle only)
			}
			out.obj()
				.kv("className", p.getClass().getName())
				.kv("name", d.name())
				.kv("description", d.description())
				.kb("enabled", pm.isPluginEnabled(p))
				.kb("active", pm.isPluginActive(p))
				.kb("hasConfig", hasConfig)
				.kb("developer", d.developerPlugin())
				.kvNullable("configGroup", group)
				.end();
		}
		return out.end().toString();
	}

	/**
	 * Full descriptor for one plugin's config {@code group}: per item the key, label, description, type,
	 * current value, and the typed metadata the native control needs (enum choices, int range, units,
	 * secret). Mirrors what the desktop {@code ConfigPanel} reflects, so the native side can render the same
	 * control set. Unknown/custom serialized types are returned with {@code "type":"unknown"} so the native
	 * side renders them read-only first.
	 */
	public static String getPluginConfig(String group)
	{
		final ConfigManager cm = configManager;
		final PluginManager pm = pluginManager;
		if (!ENABLED || cm == null || pm == null || group == null)
		{
			return "{}";
		}
		final ConfigDescriptor cd = descriptorForGroup(group);
		if (cd == null)
		{
			return "{}";
		}
		final Json out = new Json().obj().kv("group", group).key("items").arr();
		for (ConfigItemDescriptor item : cd.getItems())
		{
			final ConfigItem ci = item.getItem();
			if (ci.hidden())
			{
				// Desktop ConfigPanel skips hidden() items; match that so the native panel never shows them.
				continue;
			}
			final String key = item.key();
			final Type type = item.getType();
			final boolean secret = ci.secret();
			out.obj()
				.kv("key", key)
				.kv("name", item.name())
				.kv("description", ci.description())
				.kv("section", ci.section())
				.ki("position", item.position())
				.kb("secret", secret)
				.kv("type", typeName(type));
			// Secret values (passwords/tokens) are NOT round-tripped through the native side or its logs: emit
			// null. The native control renders a secure field and only sends a value when the user edits it.
			out.kvNullable("value", secret ? null : cm.getConfiguration(group, key));

			final String warning = ci.warning();
			if (warning != null && !warning.isEmpty())
			{
				out.kv("warning", warning);
			}
			final Range range = item.getRange();
			if (range != null)
			{
				out.ki("min", range.min()).ki("max", range.max());
			}
			final Units units = item.getUnits();
			if (units != null)
			{
				out.kv("units", units.value());
			}
			if (item.getAlpha() != null)
			{
				// Color picker should expose the alpha channel (desktop uses colorToAlphaHexCode when present).
				out.kb("alpha", true);
			}
			if (type instanceof Class && ((Class<?>) type).isEnum())
			{
				out.key("choices").arr();
				for (Object c : ((Class<?>) type).getEnumConstants())
				{
					out.val(((Enum<?>) c).name());
				}
				out.end();
			}
			else
			{
				// Set<Enum> multi-select: emit the element enum's constants so the panel can render checkboxes.
				final Class<?> el = setElementEnum(type);
				if (el != null)
				{
					out.key("choices").arr();
					for (Object c : el.getEnumConstants())
					{
						out.val(((Enum<?>) c).name());
					}
					out.end();
				}
			}
			out.end();
		}
		return out.end().end().toString();
	}

	// ---- Writes: queued onto the correct lane, fire-and-forget -----------------------------------------

	/**
	 * Enable or disable a plugin by class name — the exact {@code PluginListPanel} sequence
	 * ({@code setPluginEnabled} then {@code startPlugin}/{@code stopPlugin}), dispatched onto the EDT because
	 * start/stop assert it. The follow-on {@code PluginChanged} is the confirmation; re-read
	 * {@link #listPlugins()} after it.
	 */
	public static String setPluginEnabled(String className, boolean enabled)
	{
		final PluginManager pm = pluginManager;
		if (!ENABLED || pm == null || className == null)
		{
			return "{\"ok\":false,\"reason\":\"facade-off\"}";
		}
		final Plugin plugin = pluginByClassName(className);
		if (plugin == null)
		{
			return "{\"ok\":false,\"reason\":\"no-such-plugin\"}";
		}
		onEdt(() ->
		{
			try
			{
				pm.setPluginEnabled(plugin, enabled);
				if (enabled)
				{
					pm.startPlugin(plugin);
				}
				else
				{
					pm.stopPlugin(plugin);
				}
			}
			catch (PluginInstantiationException ex)
			{
				log.warn("[mobileui] toggle {} -> {} failed", className, enabled, ex);
			}
		});
		return "{\"ok\":true,\"queued\":\"edt\"}";
	}

	/**
	 * Write one config value (already serialized to its string form by the native control), queued onto the
	 * client thread — the desktop fires {@code ConfigChanged} synchronously, so this guarantees subscribers
	 * run where game-touching ones expect to be. The resulting {@code ConfigChanged} is the confirmation.
	 */
	public static String setConfig(String group, String key, String value)
	{
		final ConfigManager cm = configManager;
		if (!ENABLED || cm == null || group == null || key == null || value == null)
		{
			return "{\"ok\":false,\"reason\":\"facade-off\"}";
		}
		final ClientThread ct = clientThread;
		if (ct == null)
		{
			// Don't ack a write we can't actually queue (the old code returned ok:true and dropped it).
			return "{\"ok\":false,\"reason\":\"not-ready\"}";
		}
		final long id = enqueueClientWrite(ct, () -> cm.setConfiguration(group, key, value));
		return "{\"ok\":true,\"writeId\":" + id + "}";
	}

	/**
	 * Reset one config item to its default, matching the desktop: unset it, then reapply group defaults so
	 * the item is restored to its declared default value (not merely left unset). Returns a write id to poll.
	 */
	public static String resetConfig(String group, String key)
	{
		final ConfigManager cm = configManager;
		final PluginManager pm = pluginManager;
		if (!ENABLED || cm == null || pm == null || group == null || key == null)
		{
			return "{\"ok\":false,\"reason\":\"facade-off\"}";
		}
		final ClientThread ct = clientThread;
		if (ct == null)
		{
			return "{\"ok\":false,\"reason\":\"not-ready\"}";
		}
		final Config proxy = proxyForGroup(group);
		final long id = enqueueClientWrite(ct, () ->
		{
			cm.unsetConfiguration(group, key);
			if (proxy != null)
			{
				cm.setDefaultConfiguration(proxy, false);
			}
		});
		return "{\"ok\":true,\"writeId\":" + id + "}";
	}

	// ---- Live UI-state snapshot (client thread writes, native thread reads) ----------------------------

	/**
	 * Called each frame from {@code Hooks.draw} (the client thread) when the facade is on. Snapshots the
	 * chatbox text-entry widget bounds + visibility into volatiles so the native shell can (a) know where to
	 * hit-test a chat tap and (b) raise the soft keyboard when the field is live — without doing any widget
	 * read from the JNI thread.
	 */
	public static void tick(Client c)
	{
		if (!ENABLED || c == null)
		{
			return;
		}
		// The "click here to talk" affordance is the right target — NOT the scroll/message area or the tabs.
		// INPUT_CLICKAREA is the entry hit-region; fall back to the input line (CHATBOX_INPUT) if absent.
		Widget w = c.getWidget(InterfaceID.Chatbox.INPUT_CLICKAREA);
		if (w == null || w.isHidden())
		{
			w = c.getWidget(WidgetInfo.CHATBOX_INPUT);
		}
		if (w == null || w.isHidden())
		{
			chatInputVisible = false;
			return;
		}
		final java.awt.Rectangle b = w.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			chatInputVisible = false;
			return;
		}
		chatInputX = b.x;
		chatInputY = b.y;
		chatInputW = b.width;
		chatInputH = b.height;
		chatInputVisible = true;
	}

	public static boolean isChatInputVisible()
	{
		return chatInputVisible;
	}

	/** Chat-input bounds packed as {@code [x,y,w,h]} (game-canvas pixels), or {@code [0,0,0,0]} if hidden. */
	public static int[] getChatInputBounds()
	{
		return chatInputVisible
			? new int[]{chatInputX, chatInputY, chatInputW, chatInputH}
			: new int[]{0, 0, 0, 0};
	}

	// ---- internals -------------------------------------------------------------------------------------

	private static Plugin pluginByClassName(String className)
	{
		final PluginManager pm = pluginManager;
		if (pm == null)
		{
			return null;
		}
		for (Plugin p : pm.getPlugins())
		{
			if (p.getClass().getName().equals(className))
			{
				return p;
			}
		}
		return null;
	}

	private static ConfigDescriptor descriptorForGroup(String group)
	{
		final PluginManager pm = pluginManager;
		final ConfigManager cm = configManager;
		if (pm == null || cm == null)
		{
			return null;
		}
		for (Plugin p : pm.getPlugins())
		{
			try
			{
				final Config proxy = pm.getPluginConfigProxy(p);
				if (proxy == null)
				{
					continue;
				}
				final ConfigDescriptor cd = cm.getConfigDescriptor(proxy);
				if (group.equals(cd.getGroup().value()))
				{
					return cd;
				}
			}
			catch (RuntimeException ignored)
			{
				// keep scanning
			}
		}
		return null;
	}

	/** The {@link Config} proxy backing a group — needed to reapply defaults on reset (desktop's path). */
	private static Config proxyForGroup(String group)
	{
		final PluginManager pm = pluginManager;
		final ConfigManager cm = configManager;
		if (pm == null || cm == null)
		{
			return null;
		}
		for (Plugin p : pm.getPlugins())
		{
			try
			{
				final Config proxy = pm.getPluginConfigProxy(p);
				if (proxy != null && group.equals(cm.getConfigDescriptor(proxy).getGroup().value()))
				{
					return proxy;
				}
			}
			catch (RuntimeException ignored)
			{
				// keep scanning
			}
		}
		return null;
	}

	/** If {@code type} is a {@code Set<E extends Enum>}, the element enum class; else null. */
	private static Class<?> setElementEnum(Type type)
	{
		if (!(type instanceof ParameterizedType))
		{
			return null;
		}
		final ParameterizedType pt = (ParameterizedType) type;
		final Type raw = pt.getRawType();
		if (!(raw instanceof Class) || !Set.class.isAssignableFrom((Class<?>) raw))
		{
			return null;
		}
		final Type[] args = pt.getActualTypeArguments();
		if (args.length == 1 && args[0] instanceof Class && ((Class<?>) args[0]).isEnum())
		{
			return (Class<?>) args[0];
		}
		return null;
	}

	/**
	 * The native control to render for a config item. Keep this list in lockstep with the desktop
	 * {@code ConfigPanel} control set; anything we don't recognise maps to {@code unknown} (rendered
	 * read-only on the native side until we add support).
	 */
	private static String typeName(Type type)
	{
		if (!(type instanceof Class))
		{
			// parameterized (e.g. Set<...>, EnumSet<...>) — treat as a multi-select string for now
			final String s = type == null ? "" : type.getTypeName();
			return s.contains("Set") ? "set" : "unknown";
		}
		final Class<?> c = (Class<?>) type;
		if (c == boolean.class || c == Boolean.class)
		{
			return "boolean";
		}
		if (c == int.class || c == Integer.class)
		{
			return "int";
		}
		if (c == double.class || c == Double.class)
		{
			return "double";
		}
		if (c == String.class)
		{
			return "string";
		}
		if (c.isEnum())
		{
			return "enum";
		}
		if (c == java.awt.Color.class)
		{
			return "color";
		}
		if (c == java.awt.Dimension.class)
		{
			return "dimension";
		}
		// Keybind / ModifierlessKeybind / Notification / custom serialized types: name them so the native side
		// can special-case the ones it supports and render the rest read-only.
		final String simple = c.getSimpleName();
		switch (simple)
		{
			case "Keybind":
			case "ModifierlessKeybind":
				return "keybind";
			case "Notification":
				return "notification";
			default:
				return "unknown";
		}
	}

	/**
	 * Minimal streaming JSON writer — no Gson dependency on the JNI surface, correct escaping, and the chain
	 * shape keeps the DTO builders above readable. Tracks an open-container stack so {@link #end()} closes the
	 * right bracket. Not general-purpose; just enough for these snapshots.
	 */
	private static final class Json
	{
		private final StringBuilder sb = new StringBuilder(256);
		private char[] stack = new char[16];
		private int depth;
		private boolean needComma;

		private void push(char c)
		{
			if (depth == stack.length)
			{
				final char[] grown = new char[stack.length * 2];
				System.arraycopy(stack, 0, grown, 0, depth);
				stack = grown;
			}
			stack[depth++] = c;
		}

		Json obj()
		{
			sep();
			sb.append('{');
			push('}');
			needComma = false;
			return this;
		}

		Json arr()
		{
			sep();
			sb.append('[');
			push(']');
			needComma = false;
			return this;
		}

		Json end()
		{
			sb.append(stack[--depth]);
			needComma = true;
			return this;
		}

		Json key(String k)
		{
			sep();
			quote(k);
			sb.append(':');
			needComma = false;
			return this;
		}

		Json kv(String k, String v)
		{
			key(k);
			quote(v == null ? "" : v);
			needComma = true;
			return this;
		}

		Json kvNullable(String k, String v)
		{
			key(k);
			if (v == null)
			{
				sb.append("null");
			}
			else
			{
				quote(v);
			}
			needComma = true;
			return this;
		}

		Json kb(String k, boolean v)
		{
			key(k);
			sb.append(v ? "true" : "false");
			needComma = true;
			return this;
		}

		Json ki(String k, int v)
		{
			key(k);
			sb.append(v);
			needComma = true;
			return this;
		}

		Json kl(String k, long v)
		{
			key(k);
			sb.append(v);
			needComma = true;
			return this;
		}

		Json val(String v)
		{
			sep();
			quote(v == null ? "" : v);
			needComma = true;
			return this;
		}

		private void sep()
		{
			if (needComma)
			{
				sb.append(',');
			}
		}

		private void quote(String s)
		{
			sb.append('"');
			for (int i = 0; i < s.length(); i++)
			{
				final char ch = s.charAt(i);
				switch (ch)
				{
					case '"':
						sb.append("\\\"");
						break;
					case '\\':
						sb.append("\\\\");
						break;
					case '\n':
						sb.append("\\n");
						break;
					case '\r':
						sb.append("\\r");
						break;
					case '\t':
						sb.append("\\t");
						break;
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
		}

		@Override
		public String toString()
		{
			return sb.toString();
		}
	}
}
