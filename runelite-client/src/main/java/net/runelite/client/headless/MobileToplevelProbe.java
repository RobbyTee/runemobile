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
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * W3 mobile-layout spike (interface group 601, {@code ToplevelOsm}). The official OSRS Mobile chrome —
 * the split edge-tabs / orbs / mobile chat — is the complete interface group 601, whose 135 component
 * definitions ship in the shared cache our client already downloads. The headline question this probe
 * settles is the cheapest-decisive one: <b>can the desktop gamepack actually <em>build</em> group 601 as
 * a toplevel, or is the build trigger mobile-binary-only?</b>
 *
 * <p>The desktop client only ever selects among the desktop toplevels (548 fixed / 161 osrs-stretch /
 * 164 pre-eoc) — see {@code OverlayRenderer.getViewportLayer}. There is no public lever to "set the
 * toplevel"; the engine chooses based on a native {@code clienttype}. The single thing we <em>can</em>
 * try from the client thread is to force the build script ({@link ScriptID#TOPLEVEL_REDRAW}, group 907)
 * with group 601 and observe whether {@link Client#getTopLevelInterfaceId()} flips to 601 and 601's
 * components instantiate (become non-null via {@link Client#getWidget(int)}).
 *
 * <p><b>Entirely inert unless</b> {@code -Drunelite.mobileui.toplevel=osm}. Default ({@code desktop} or
 * unset) is a no-op, so this is fully A/B-gated and reversible — it never changes the toplevel on a normal
 * run. One-shot: it fires once, on the first {@link GameState#LOGGED_IN} frame, and logs a single
 * bash-parseable verdict line ({@code [osm-probe] OSM_PROBE …}) so the result can be md5-gated against the
 * verbatim {@code jvm-stdio.log}.
 */
@Slf4j
public final class MobileToplevelProbe
{
	private static final String MODE = System.getProperty("runelite.mobileui.toplevel", "desktop");
	private static final boolean ENABLED = "osm".equalsIgnoreCase(MODE);

	/** Representative 601 components spanning the whole layout — chrome, viewport, tabs, chat, minimap, orbs. */
	private static final int[] OSM_COMPONENTS = {
		InterfaceID.ToplevelOsm.CONTROL,
		InterfaceID.ToplevelOsm.VIEWPORT,
		InterfaceID.ToplevelOsm.SIDE_RIGHT_TABS,
		InterfaceID.ToplevelOsm.CHAT_CONTAINER,
		InterfaceID.ToplevelOsm.MAP_CONTAINER,
		InterfaceID.ToplevelOsm.ORBS,
	};

	/** Give the engine this many LOGGED_IN frames to build its own toplevel before declaring it never will. */
	private static final int BASELINE_DEADLINE_FRAMES = 250;
	/** After forcing 601, watch this many frames for the toplevel to flip / its components to instantiate. */
	private static final int OBSERVE_FRAMES = 40;

	private enum Phase { WAIT_BASELINE, OBSERVE, DONE }

	private static Phase phase = Phase.WAIT_BASELINE;
	private static int loggedInFrames;
	private static int observeFrames;
	private static int baselineTop;
	private static int baselineOsm;
	private static int lastTop = Integer.MIN_VALUE;
	private static int bestObservedTop = -1;
	private static int bestObservedOsm;

	private MobileToplevelProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Called once per {@code Hooks.draw} (only when enabled). A multi-frame state machine on the client/game
	 * thread ({@code Hooks.draw}'s thread, satisfying {@link Client#runScript}'s contract):
	 * <ol>
	 *   <li><b>WAIT_BASELINE</b> — the engine does not build its toplevel on the very first {@code LOGGED_IN}
	 *       frame, so we wait for {@code getTopLevelInterfaceId() > 0} (a real desktop toplevel) to establish a
	 *       valid baseline. Only then is forcing 601 a meaningful test. If no toplevel ever appears within
	 *       {@link #BASELINE_DEADLINE_FRAMES}, we emit an {@code INCONCLUSIVE} verdict (engine never built one).</li>
	 *   <li>At baseline we force the build script with group 601 ({@code runScript(TOPLEVEL_REDRAW, 601, 1)}),
	 *       then <b>OBSERVE</b> for {@link #OBSERVE_FRAMES} frames whether the toplevel flips to 601 or 601's
	 *       components instantiate (a build can take a few frames). A second arg variant (0) is tried midway.</li>
	 *   <li><b>DONE</b> — emit a single bash-parseable verdict line ({@code [osm-probe] OSM_PROBE … RESULT=…}).</li>
	 * </ol>
	 */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || phase == Phase.DONE || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		loggedInFrames++;
		final int top = safeTopLevel(client);
		if (top != lastTop)
		{
			log.info("[osm-probe] frame={} topLevel={} osmComponents={}/{} phase={}",
				loggedInFrames, top, countPresent(client), OSM_COMPONENTS.length, phase);
			lastTop = top;
		}

		switch (phase)
		{
			case WAIT_BASELINE:
				if (top > 0)
				{
					baselineTop = top;
					baselineOsm = countPresent(client);
					log.info("[osm-probe] baseline established frame={} topLevel={} osmComponents={}/{} -> forcing 601",
						loggedInFrames, baselineTop, baselineOsm, OSM_COMPONENTS.length);
					tryForce(client, 1);
					phase = Phase.OBSERVE;
					observeFrames = 0;
				}
				else if (loggedInFrames >= BASELINE_DEADLINE_FRAMES)
				{
					emitVerdict(-1, -1, 0, "INCONCLUSIVE_NO_TOPLEVEL");
				}
				break;

			case OBSERVE:
				observeFrames++;
				if (observeFrames == OBSERVE_FRAMES / 2)
				{
					tryForce(client, 0); // try the other arg-variant once, in case 1 was wrong
				}
				final int osm = countPresent(client);
				if (top == InterfaceID.TOPLEVEL_OSM && top > bestObservedTop)
				{
					bestObservedTop = top;
				}
				if (osm > bestObservedOsm)
				{
					bestObservedOsm = osm;
				}
				if (top == InterfaceID.TOPLEVEL_OSM && osm > 0)
				{
					emitVerdict(baselineTop, top, osm, "BUILT");
				}
				else if (observeFrames >= OBSERVE_FRAMES)
				{
					final int reportTop = bestObservedTop > 0 ? bestObservedTop : top;
					emitVerdict(baselineTop, reportTop, bestObservedOsm, "NOBUILD");
				}
				break;

			default:
				break;
		}
	}

	private static void emitVerdict(int before, int afterTop, int afterOsm, String result)
	{
		phase = Phase.DONE;
		log.info("[osm-probe] OSM_PROBE topLevelBefore={} topLevelAfter={} osmComponentsBefore={}/{} "
				+ "osmComponentsAfter={}/{} RESULT={}",
			before, afterTop, baselineOsm, OSM_COMPONENTS.length, afterOsm, OSM_COMPONENTS.length, result);
	}

	private static void tryForce(Client client, int arg2)
	{
		try
		{
			client.runScript(ScriptID.TOPLEVEL_REDRAW, InterfaceID.TOPLEVEL_OSM, arg2);
		}
		catch (Throwable t)
		{
			log.info("[osm-probe] runScript(907,601,{}) threw: {}", arg2, String.valueOf(t));
		}
	}

	private static int safeTopLevel(Client client)
	{
		try
		{
			return client.getTopLevelInterfaceId();
		}
		catch (Throwable t)
		{
			return -1;
		}
	}

	private static int countPresent(Client client)
	{
		int n = 0;
		for (final int id : OSM_COMPONENTS)
		{
			try
			{
				final Widget w = client.getWidget(id);
				if (w != null)
				{
					n++;
				}
			}
			catch (Throwable ignored)
			{
				// a missing-interface getWidget can throw on some builds; treat as absent
			}
		}
		return n;
	}
}
