import com.github.caciocavallosilano.cacio.ctc.junit.CacioExtension;
import com.google.inject.Injector;
import java.lang.reflect.Field;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.headless.MobileUiBridge;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-6 (W3) STEP-0 de-risk smoke for {@link MobileUiBridge} — the single most likely thing to be wrong
 * (plugin/config thread semantics + type serialization), proven on desktop OpenJDK 21 BEFORE any native
 * UI is built (the W2 develop-first rule). It exercises the facade exactly as the native shell will and
 * checks the things the design audit flagged as load-bearing:
 *
 * <ol>
 *   <li><b>-ea is actually on</b> (positive control) — otherwise the {@code startPlugin} EDT asserts are
 *       silent no-ops and a wrong-thread bug would pass unseen.</li>
 *   <li><b>listPlugins()</b> returns a non-empty, parseable snapshot.</li>
 *   <li><b>Config write lands on the CLIENT thread</b> — {@code setConfig()} fires {@code ConfigChanged}
 *       and the subscriber observes {@code client.isClientThread()==true} (mimics the desktop, where
 *       game-touching config subscribers expect that thread).</li>
 *   <li><b>Plugin toggle lands on the EDT</b> — {@code setPluginEnabled()} fires {@code PluginChanged} and
 *       the subscriber observes {@code EventQueue.isDispatchThread()==true} (what {@code startPlugin}
 *       asserts), the plugin actually becomes active, and the OverlayManager overlay set grows (the real
 *       headless-overlay risk).</li>
 *   <li><b>Cross-launch persistence</b> — a sentinel config value is written + flushed in phase "write";
 *       phase "read" (a second JVM) confirms it survived to disk.</li>
 *   <li><b>Chat-input bounds</b> — if LOGGED_IN, the tick snapshot exposes a plausible chat-entry rect.</li>
 * </ol>
 *
 * Every result is printed as a {@code MUI_*=...} marker on stdout; the runner ({@code run-mobileui-smoke.sh})
 * parses those, computes the GREEN/RED verdict in bash, and md5-gates it against the verbatim log — the
 * documented guard against the false-GREEN history (shell text injection / self-reported success).
 */
@ExtendWith(CacioExtension.class)
public class MobileUiSmoke
{
	private static final String SENTINEL_GROUP = "mobileuismoke";
	private static final String SENTINEL_KEY = "probe";

	/** Records the thread each event was delivered on. */
	public static final class Probe
	{
		volatile ConfigChanged lastConfig;
		volatile boolean configOnClientThread;
		volatile boolean configOnEdt;
		volatile PluginChanged lastPlugin;
		volatile boolean pluginOnEdt;
		volatile boolean pluginOnClientThread;

		private final Client client;

		Probe(Client client)
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
			pluginOnClientThread = client.isClientThread();
			lastPlugin = e;
		}
	}

	@Test
	public void smoke() throws Exception
	{
		final String phase = System.getProperty("mui.phase", "write");
		System.out.println("MUI_PHASE=" + phase);

		// (1) Positive control: is -ea actually enabled in this JVM?
		boolean ea = false;
		// noinspection AssertWithSideEffects,ConstantConditions
		assert (ea = true);
		System.out.println("MUI_EA=" + ea);

		System.out.println("[smoke] booting headless RuneLite (mobileui facade on)…");
		net.runelite.client.headless.RuneLiteHeadless.main(new String[]{"--safe-mode"});

		final Injector injector = await("injector", 30_000, () -> RuneLite.getInjector() != null)
			? RuneLite.getInjector() : null;
		if (injector == null)
		{
			System.out.println("MUI_FATAL=no-injector");
			return;
		}
		final EventBus eventBus = injector.getInstance(EventBus.class);
		final PluginManager pluginManager = injector.getInstance(PluginManager.class);
		final OverlayManager overlayManager = injector.getInstance(OverlayManager.class);
		final ConfigManager configManager = injector.getInstance(ConfigManager.class);
		final Client client = injector.getInstance(Client.class);

		// Facade must be wired (HeadlessClientUI.init ran) before we drive it.
		await("facade-wired", 30_000, () -> !"[]".equals(MobileUiBridge.listPlugins()));

		// READ phase: a fresh JVM just verifies the sentinel survived to disk, then exits.
		if ("read".equals(phase))
		{
			final String got = configManager.getConfiguration(SENTINEL_GROUP, SENTINEL_KEY);
			final String want = System.getProperty("mui.sentinel", "");
			System.out.println("MUI_PERSIST_READBACK=" + got);
			System.out.println("MUI_PERSIST_OK=" + want.equals(got));
			return;
		}

		final Probe probe = new Probe(client);
		eventBus.register(probe);

		// (2) listPlugins snapshot
		final String list = MobileUiBridge.listPlugins();
		final int pluginCount = countOccurrences(list, "\"className\":");
		System.out.println("MUI_LIST_COUNT=" + pluginCount);
		System.out.println("MUI_LIST_OK=" + (pluginCount > 0 && list.startsWith("[") && list.endsWith("]")));

		// (3) Config write -> CLIENT thread
		final String sentinel = "v" + Long.toHexString(System.identityHashCode(probe)) + pluginCount;
		MobileUiBridge.setConfig(SENTINEL_GROUP, SENTINEL_KEY, sentinel);
		await("config-event", 10_000, () -> probe.lastConfig != null);
		final boolean configFired = probe.lastConfig != null;
		System.out.println("MUI_CONFIG_FIRED=" + configFired);
		System.out.println("MUI_CONFIG_VALUE_OK=" + (configFired && sentinel.equals(probe.lastConfig.getNewValue())));
		System.out.println("MUI_CONFIG_ON_CLIENT_THREAD=" + probe.configOnClientThread);
		System.out.println("MUI_CONFIG_ON_EDT=" + probe.configOnEdt);
		System.out.println("MUI_SENTINEL=" + sentinel);

		// (4) Plugin toggle -> EDT (+ becomes active + overlay registers headless). We SEARCH inactive
		// plugins for one whose enabling grows the OverlayManager set, so we actually exercise the headless-
		// overlay path (the audit's stated risk) — not just a no-overlay toggle like Roof Removal. The
		// EDT-thread + PluginChanged proof holds for whichever plugin we land on; the overlay-grow is the
		// bonus that confirms add()-on-enable works with no Swing config panel present.
		boolean toggleEdtOk = false;
		boolean toggleActiveOk = false;
		boolean pluginEventOk = false;
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
			sleep(400); // let overlay registration + PluginChanged settle
			final boolean active = pluginManager.isPluginActive(p);
			final int after = overlayCount(overlayManager);
			// Record the thread proof from the first plugin that actually started.
			if (active && chosen.equals("(none)"))
			{
				chosen = p.getName();
				toggleActiveOk = active;
				toggleEdtOk = probe.pluginOnEdt;
				pluginEventOk = probe.lastPlugin != null;
			}
			if (active && after > before)
			{
				// Found an overlay-bearing plugin — this is the headless-overlay proof.
				chosen = p.getName();
				toggleActiveOk = true;
				toggleEdtOk = probe.pluginOnEdt;
				pluginEventOk = probe.lastPlugin != null;
				overlayGrew = true;
				System.out.println("MUI_OVERLAY_BEFORE=" + before);
				System.out.println("MUI_OVERLAY_AFTER=" + after);
				MobileUiBridge.setPluginEnabled(cls, false); // restore
				sleep(200);
				break;
			}
			MobileUiBridge.setPluginEnabled(cls, false); // restore, keep searching
			sleep(150);
		}
		System.out.println("MUI_TOGGLE_TARGET=" + chosen);
		System.out.println("MUI_TOGGLE_ACTIVE=" + toggleActiveOk);
		System.out.println("MUI_TOGGLE_ON_EDT=" + toggleEdtOk);
		System.out.println("MUI_TOGGLE_PLUGIN_EVENT=" + pluginEventOk);
		System.out.println("MUI_OVERLAY_GREW=" + overlayGrew);
		System.out.println("MUI_TOGGLE_OK=" + (toggleActiveOk && toggleEdtOk));

		// (5) Persistence: flush the sentinel so the "read" phase (second JVM) can confirm it hit disk.
		configManager.sendConfig();
		System.out.println("MUI_PERSIST_WRITTEN=" + sentinel);

		// (6) Chat-input bounds (only meaningful in-game)
		final boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		System.out.println("MUI_LOGGED_IN=" + loggedIn);
		if (loggedIn)
		{
			sleep(1000); // let a tick() snapshot land
			final int[] b = MobileUiBridge.getChatInputBounds();
			final boolean vis = MobileUiBridge.isChatInputVisible();
			System.out.println("MUI_CHAT_VISIBLE=" + vis);
			System.out.println("MUI_CHAT_BOUNDS=" + b[0] + "," + b[1] + "," + b[2] + "," + b[3]);
			System.out.println("MUI_CHAT_OK=" + (vis && b[2] > 0 && b[3] > 0));
		}

		System.out.println("MUI_DONE=1");
	}

	// ---- helpers ---------------------------------------------------------------------------------------

	/**
	 * Currently-inactive, non-hidden, non-developer plugins — the toggle search space. Known overlay-bearing,
	 * harmless plugins are tried FIRST so the overlay-grow proof is reached quickly; the rest follow.
	 */
	private static List<Plugin> inactiveTogglable(PluginManager pm)
	{
		final java.util.List<String> preferred = java.util.Arrays.asList(
			"Ground Markers", "Screen Markers", "Tile Indicators", "World Map", "Coordinates",
			"Menu Entry Swapper", "Mouse Highlight", "Boosts Information");
		final java.util.List<Plugin> head = new java.util.ArrayList<>();
		final java.util.List<Plugin> tail = new java.util.ArrayList<>();
		for (Plugin p : pm.getPlugins())
		{
			final net.runelite.client.plugins.PluginDescriptor d =
				p.getClass().getAnnotation(net.runelite.client.plugins.PluginDescriptor.class);
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
		System.out.println("[smoke] timed out waiting for " + what);
		return false;
	}

	private static void sleep(long ms) throws InterruptedException
	{
		Thread.sleep(ms);
	}
}
