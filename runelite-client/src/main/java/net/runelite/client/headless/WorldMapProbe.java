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

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.VarClientInt;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.worldmap.WorldMap;

/**
 * World-map mobile de-risk (WORLDMAP-MOBILE-PLAN.md Phase 0) — answers every input-model unknown for a
 * touch-driven world map in ONE headless run, before any device work:
 *
 * <ol>
 *   <li><b>panned</b> — does a synthetic left-button drag stream (the exact {@code JavaBridge.onTouch} event
 *       shapes: PRESSED, per-frame DRAGGED, RELEASED) pan the map? Oracle: {@code WorldMap.getWorldMapPosition()}
 *       moves.</li>
 *   <li><b>zoomWheel</b> — does a synthetic {@code MouseWheelEvent} over the map view zoom the MAP (not the
 *       camera)? Oracle: {@code WorldMap.getWorldMapZoom()} changes.</li>
 *   <li><b>zoomBtn</b> — does a plain click on the map's own {@code ZOOM_IN} button zoom (the pinch fallback)?</li>
 *   <li><b>searchInput</b> — does clicking the map's {@code SEARCH} control open a gamepack text field
 *       ({@code VarClientInt.INPUT_TYPE} != 0, what the shell's soft-keyboard poll watches)?</li>
 *   <li><b>relocate</b> — does {@code setForcedPosition} on the map window STICK (chat behaviour) or get re-pinned
 *       by the engine (tab-stone behaviour)? Tried on {@code UNIVERSE} then {@code WINDOW}; the steady-state PNGs
 *       are the ground truth (the tab-stone lesson: {@code getBounds} lies, only the render is truth).</li>
 *   <li><b>closed</b> — does clicking the map's {@code CLOSE} X close it?</li>
 * </ol>
 *
 * <p>Opens the map by clicking the world-map orb ({@code Orbs.ORB_WORLDMAP}). Captures PNGs at each stage
 * (open / panned / relocated-*) for layout recon: what the map covers at the device canvas size, where its edge
 * UI lands vs the safe area. Mutates map state + clicks; desktop de-risk only. Inert unless
 * {@code -Drunelite.worldmapprobe=true}. The {@code [worldmap] WORLDMAP …} line is the verdict gate.
 */
@Slf4j
public final class WorldMapProbe
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.worldmapprobe");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.worldmapprobe.dir");
		if (dir == null || dir.isEmpty())
		{
			dir = System.getProperty("runelite.headless.capture.dir");
		}
		DIR = (dir == null || dir.isEmpty()) ? null : new File(dir);
		if (ENABLED && DIR != null)
		{
			//noinspection ResultOfMethodCallIgnored
			DIR.mkdirs();
		}
	}

	private static final int OPEN_RETRY = 30;     // frames between orb-click retries while the map isn't open
	private static final int OPEN_TRIES = 4;
	private static final int SETTLE = 20;         // frames for the opened map / a forced position to settle
	private static final int DRAG_STEPS = 16;     // per-frame MOUSE_DRAGGED steps in the pan test
	private static final int DRAG_DX = 9;         // px per step (right+down, total ~144x80 — an unambiguous pan)
	private static final int DRAG_DY = 5;
	private static final int READ_DELAY = 12;     // frames between an action and reading its oracle
	private static final int RELOC_X = 80;        // forced-position target (clearly inset from 0,0)
	private static final int RELOC_Y = 40;

	private enum Phase
	{
		FIX_TAB, FIX_OPENBTN, FIX_WINDOW_WAIT, FIX_TYPE, FIX_FIND, FIX_VERIFY,
		WAIT, OPENING, OPEN_SETTLE, PAN_PRESS, PAN_HOLD, PAN_DRAG, PAN_READ, PAN_API, PAN_API_READ,
		WHEEL, WHEEL_READ, ZBTN, ZBTN_READ, SEARCH, SEARCH_READ, CLOSE, CLOSE_READ,
		REOPEN, REOPENING, REOPEN_ZBTN_READ, RELOC_UNIVERSE, RELOC_WINDOW, DONE
	}

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int openTries;
	private static boolean focused;
	private static boolean verdictEmitted;

	// ---- open-failure diagnostics (legacy-account world-map bug) -------------------------------------------
	private static int chatSeen = -1;                 // -1 = baseline not taken yet (skip the login backlog)
	private static java.util.Set<Integer> rootGroups; // widget root groups last frame (null = baseline pending)
	private static int hoverFrames;                   // frames spent hovering the orb before the menu read + click

	// ---- Data-orbs account fix (root cause of the legacy open failure) -------------------------------------
	// The legacy account has the old "Data orbs" setting OFF server-side (ORBS_DISABLED varbit 4084 = 1), so
	// the SERVER never opens the Orbs interface (160) at login; HudVeneerBridge fakes it open client-side, the
	// world-map orb renders + its op is consumed, but the op is a server-bound if_button on an interface node
	// the server never opened — silently discarded. Fix = flip the toggle through the REAL settings UI (the
	// settings side panel + All Settings window are server-opened, so their buttons work), which the server
	// persists on the account. Armed by an orbsfix.mode marker next to the capture dir; one-shot.
	private static Boolean fixMode;                   // null = marker not checked yet
	private static int fixTabTry;                     // index into FIX_TAB_ORDER while hunting the wrench stone
	private static int fixStartVarbit = -1;
	private static Rectangle fixToggleRect;
	private static boolean fixClicked;
	private static final int[] FIX_TAB_ORDER = {11, 10, 12, 13, 9, 8};

	private static Point panBefore;
	private static int dragX, dragY;
	private static boolean panned;
	private static boolean pannedApi;
	private static float zoomBefore;
	private static boolean zoomWheel;
	private static boolean zoomBtn;
	private static boolean searchInput;
	private static String relocUniverse = "?";   // reported-bounds delta for the UNIVERSE force (PNG = truth)
	private static String relocWindow = "?";
	private static boolean closed;

	private WorldMapProbe()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client c, GameState state)
	{
		if (!ENABLED || c == null || state != GameState.LOGGED_IN || phase == Phase.DONE)
		{
			return;
		}

		if (welcomeUp(c))
		{
			if ((phaseFrame++ % 20) == 5)
			{
				synthClick(c, c.getCanvasWidth() / 2, c.getCanvasHeight() / 2);
			}
			return;
		}

		if (fixMode == null)
		{
			fixMode = DIR != null && DIR.getParentFile() != null
				&& new File(DIR.getParentFile(), "orbsfix.mode").exists();
			if (fixMode)
			{
				log.info("[worldmap] FIX MODE armed (orbsfix.mode) — will enable Data orbs through the settings UI");
				phase = Phase.FIX_TAB;
			}
		}

		try
		{
			diffChat(c);
			diffRoots(c);
			step(c);
		}
		catch (RuntimeException ex)
		{
			log.warn("[worldmap] tick threw {} in {}", String.valueOf(ex), phase);
		}
		phaseFrame++;
	}

	private static void step(Client c)
	{
		switch (phase)
		{
			case FIX_TAB:
			{
				if (phaseFrame == 0)
				{
					fixStartVarbit = safeVarbitInt(c, net.runelite.api.gameval.VarbitID.ORBS_DISABLED);
					log.info("[worldmap] fix: ORBS_DISABLED={} at start", fixStartVarbit);
					if (fixStartVarbit == 0)
					{
						log.info("[worldmap] fix: Data orbs already enabled — skipping to the orb test");
						go(Phase.WAIT);
						break;
					}
				}
				if (boundsOf(c, InterfaceID.Settings.UNIVERSE) != null)
				{
					// The All Settings window is already up (e.g. opened by hand / native taps) — go type.
					go(Phase.FIX_WINDOW_WAIT);
					break;
				}
				if (boundsOf(c, InterfaceID.SettingsSide.SETTINGS_OPEN) != null)
				{
					go(Phase.FIX_OPENBTN);
					break;
				}
				if ((phaseFrame % 12) == 6 && fixTabTry < FIX_TAB_ORDER.length)
				{
					final int idx = FIX_TAB_ORDER[fixTabTry++];
					log.info("[worldmap] fix: replaying side-stone {} op to reach the wrench panel", idx);
					replayStoneOp(c, idx);
				}
				else if (phaseFrame == FIX_TAB_ORDER.length * 12 + 12)
				{
					log.info("[worldmap] fix: side panel not reachable by stone replay — waiting for a native/manual "
						+ "open of the settings window (All Settings) for ~600 frames");
				}
				else if (phaseFrame >= 600)
				{
					log.info("[worldmap] fix: settings window never appeared — giving up");
					go(Phase.WAIT);
				}
				break;
			}

			case FIX_OPENBTN:
			{
				final Rectangle b = boundsOf(c, InterfaceID.SettingsSide.SETTINGS_OPEN);
				if (b == null)
				{
					go(Phase.FIX_TAB);
					break;
				}
				// A REAL click (not an op-script replay): opening the big settings window is server-side, the
				// engine must send the if_button itself.
				synthClick(c, b.x + b.width / 2, b.y + b.height / 2);
				log.info("[worldmap] fix: clicked All Settings at {},{}", b.x + b.width / 2, b.y + b.height / 2);
				go(Phase.FIX_WINDOW_WAIT);
				break;
			}

			case FIX_WINDOW_WAIT:
			{
				final Rectangle u = boundsOf(c, InterfaceID.Settings.UNIVERSE);
				if (u != null)
				{
					final Rectangle s = boundsOf(c, InterfaceID.Settings.SEARCHBAR_CONTAINER);
					if (s != null)
					{
						synthClick(c, s.x + s.width / 2, s.y + s.height / 2);   // focus the search field
					}
					go(Phase.FIX_TYPE);
				}
				else if (phaseFrame >= 80)
				{
					log.info("[worldmap] fix: settings window never opened — giving up");
					go(Phase.WAIT);
				}
				break;
			}

			case FIX_TYPE:
				if (phaseFrame == 6)
				{
					typeString(c, "orb");
				}
				else if (phaseFrame >= 18)
				{
					try
					{
						final Widget st = c.getWidget(InterfaceID.Settings.SEARCH_TEXT);
						log.info("[worldmap] fix: search text now '{}'", st == null ? "null" : st.getText());
					}
					catch (RuntimeException ignore)
					{
					}
					go(Phase.FIX_FIND);
				}
				break;

			case FIX_FIND:
			{
				if ((phaseFrame % 10) == 5)
				{
					fixToggleRect = findRowByText(c, "data orbs");
					if (fixToggleRect != null)
					{
						log.info("[worldmap] fix: Data orbs row at {} — clicking", fixToggleRect);
						synthClick(c, fixToggleRect.x + fixToggleRect.width / 2,
							fixToggleRect.y + fixToggleRect.height / 2);
						fixClicked = false;
						go(Phase.FIX_VERIFY);
						break;
					}
					if (phaseFrame >= 75)
					{
						dumpSettingsTexts(c);
						log.info("[worldmap] fix: no 'Data orbs' row found — giving up");
						go(Phase.WAIT);
					}
				}
				break;
			}

			case FIX_VERIFY:
			{
				final int v = safeVarbitInt(c, net.runelite.api.gameval.VarbitID.ORBS_DISABLED);
				if (v == 0)
				{
					log.info("[worldmap] fix: SUCCESS — ORBS_DISABLED now 0 (Data orbs enabled, server-persisted)");
					capture(c, "orbsfix");
					final Rectangle x = boundsOf(c, InterfaceID.Settings.CLOSE);
					if (x != null)
					{
						synthClick(c, x.x + x.width / 2, x.y + x.height / 2);
					}
					go(Phase.WAIT);
					break;
				}
				if (phaseFrame >= 40 && !fixClicked && fixToggleRect != null)
				{
					// Row-center click didn't take — the actual checkbox sits at the row's right edge.
					fixClicked = true;
					synthClick(c, fixToggleRect.x + fixToggleRect.width - 12,
						fixToggleRect.y + fixToggleRect.height / 2);
					log.info("[worldmap] fix: retry click at the row's right edge");
				}
				else if (phaseFrame >= 100)
				{
					log.info("[worldmap] fix: varbit still {} after clicking — giving up", v);
					go(Phase.WAIT);
				}
				break;
			}

			case WAIT:
			{
				final Rectangle orb = boundsOf(c, InterfaceID.Orbs.ORB_WORLDMAP);
				if (orb == null)
				{
					return;
				}
				// Window-rect test: WORLDMAP_X/Y/MINUSWIDTH/MINUSHEIGHT look like the desktop map window's
				// remembered draggable/resizable rect. Seed an explicitly VALID rect ("x,y,minusW,minusH")
				// before the open — if the open honors it (geom dump + the zoom-click oracle), the bridge can
				// place a centered window with native hit-testing.
				final String rect = System.getProperty("runelite.worldmapprobe.varcrect", "");
				if (!rect.isEmpty())
				{
					try
					{
						final String[] p = rect.split(",");
						c.setVarcIntValue(net.runelite.api.gameval.VarClientID.WORLDMAP_X, Integer.parseInt(p[0].trim()));
						c.setVarcIntValue(net.runelite.api.gameval.VarClientID.WORLDMAP_Y, Integer.parseInt(p[1].trim()));
						c.setVarcIntValue(net.runelite.api.gameval.VarClientID.WORLDMAP_MINUSWIDTH, Integer.parseInt(p[2].trim()));
						c.setVarcIntValue(net.runelite.api.gameval.VarClientID.WORLDMAP_MINUSHEIGHT, Integer.parseInt(p[3].trim()));
						log.info("[worldmap] seeded varc rect {}", rect);
					}
					catch (RuntimeException ex)
					{
						log.warn("[worldmap] bad varcrect {}", rect);
					}
				}
				// Reserve-shrink test: the engine caps the map window at canvas minus a 215x165 reserve (the
				// side column + chat strip). Hiding those widgets didn't shrink the reserve — try zeroing
				// their LAYOUT sizes before the open (what the open script plausibly measures).
				if (Boolean.getBoolean("runelite.worldmapprobe.preshrink"))
				{
					try
					{
						final boolean pre = c.getVarbitValue(net.runelite.api.gameval.VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
						// NOT the minimap container — the world-map orb is its child (zeroing it killed the open).
						final int[] ids = {
							pre ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER,
							pre ? InterfaceID.ToplevelPreEoc.SIDE_CONTAINER : InterfaceID.ToplevelOsrsStretch.SIDE_CONTAINER,
						};
						for (int id : ids)
						{
							final Widget w = c.getWidget(id);
							if (w != null)
							{
								w.setOriginalWidth(0);
								w.setOriginalHeight(0);
								w.revalidate();
							}
						}
						log.info("[worldmap] pre-shrunk chat/side/minimap layout sizes to 0");
					}
					catch (RuntimeException ignore)
					{
					}
				}
				// Decisive sizing test: the engine sizes the map AT OPEN from the live viewport, and the
				// viewport excludes the chat strip while the chat is layout-visible. Pre-hide the chat so the
				// open should produce a full-height map (the mobile fullscreen lever, if the geom dump agrees).
				if (Boolean.getBoolean("runelite.worldmapprobe.prehidechat"))
				{
					try
					{
						final boolean pre = c.getVarbitValue(net.runelite.api.gameval.VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
						final Widget chat = c.getWidget(pre
							? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER);
						if (chat != null && !chat.isHidden())
						{
							chat.setHidden(true);
							log.info("[worldmap] pre-hid the chat before opening");
						}
					}
					catch (RuntimeException ignore)
					{
					}
				}
				// Hover first, click on a later frame — getMenuEntries() reflects the pointer only after the
				// engine processed the MOUSE_MOVED, so the menu the click will consume is readable here.
				if (hoverFrames < 3)
				{
					if (hoverFrames == 0)
					{
						synthMove(c, orb.x + orb.width / 2, orb.y + orb.height / 2);
					}
					hoverFrames++;
					return;
				}
				hoverFrames = 0;
				logMenu(c);
				dumpAccountState(c);
				synthClick(c, orb.x + orb.width / 2, orb.y + orb.height / 2);
				log.info("[worldmap] clicked world-map orb at {},{}", orb.x + orb.width / 2, orb.y + orb.height / 2);
				go(Phase.OPENING);
				break;
			}

			case OPENING:
			{
				if (boundsOf(c, InterfaceID.Worldmap.MAP_CONTAINER) != null)
				{
					dumpGeometry(c);
					go(Phase.OPEN_SETTLE);
					break;
				}
				if ((phaseFrame % 10) == 9)
				{
					// The boundsOf gate hides "exists but hidden/zero-size" — log the raw widget states while
					// the open is pending so a loaded-but-hidden interface is distinguishable from not-loaded.
					logRawState(c, "UNIVERSE", InterfaceID.Worldmap.UNIVERSE);
					logRawState(c, "MAP_CONTAINER", InterfaceID.Worldmap.MAP_CONTAINER);
				}
				if (phaseFrame >= OPEN_RETRY)
				{
					if (++openTries >= OPEN_TRIES)
					{
						log.info("[worldmap] map never opened after {} orb clicks", openTries);
						finish();
						break;
					}
					go(Phase.WAIT);
				}
				break;
			}

			case OPEN_SETTLE:
				if (phaseFrame >= SETTLE)
				{
					capture(c, "open");
					go(Phase.PAN_PRESS);
				}
				break;

			case PAN_PRESS:
			{
				final Rectangle map = mapView(c);
				if (map == null)
				{
					log.info("[worldmap] no map view widget for the pan test");
					finish();
					break;
				}
				panBefore = mapPos(c);
				dragX = map.x + map.width / 2;
				dragY = map.y + map.height / 2;
				synthPress(c, dragX, dragY);
				go(Phase.PAN_HOLD);
				break;
			}

			case PAN_HOLD:
				// Hold the button a few frames before the first DRAGGED, like a real finger settling — in case the
				// gamepack's drag detection wants press-then-move across cycles (the inventory-drag-delay shape).
				if (phaseFrame >= 4)
				{
					go(Phase.PAN_DRAG);
				}
				break;

			case PAN_DRAG:
				if (phaseFrame < DRAG_STEPS)
				{
					// Two DRAGGED events per frame, bigger steps — a brisk, unambiguous drag.
					dragX += DRAG_DX;
					dragY += DRAG_DY;
					synthDrag(c, dragX, dragY);
					dragX += DRAG_DX;
					dragY += DRAG_DY;
					synthDrag(c, dragX, dragY);
				}
				else
				{
					synthRelease(c, dragX, dragY);
					go(Phase.PAN_READ);
				}
				break;

			case PAN_READ:
				if (phaseFrame >= READ_DELAY)
				{
					final Point now = mapPos(c);
					panned = panBefore != null && now != null
						&& (now.getX() != panBefore.getX() || now.getY() != panBefore.getY());
					log.info("[worldmap] pan: before={} after={} panned={}", str(panBefore), str(now), panned);
					capture(c, "panned");
					go(Phase.PAN_API);
				}
				break;

			case PAN_API:
			{
				// The API pan primitive: move the map centre directly (RuneLite mixin) — deterministic, no mouse
				// fidelity needed. If this is green the shell's pan gesture can drive it 1:1 (px -> tiles via zoom).
				panBefore = mapPos(c);
				try
				{
					final WorldMap wm = c.getWorldMap();
					if (wm != null && panBefore != null)
					{
						wm.setWorldMapPositionTarget(new net.runelite.api.coords.WorldPoint(
							panBefore.getX() + 30, panBefore.getY() + 15, 0));
					}
				}
				catch (RuntimeException ex)
				{
					log.info("[worldmap] setWorldMapPositionTarget threw {}", String.valueOf(ex));
				}
				go(Phase.PAN_API_READ);
				break;
			}

			case PAN_API_READ:
				if (phaseFrame >= READ_DELAY)
				{
					final Point now = mapPos(c);
					pannedApi = panBefore != null && now != null
						&& (now.getX() != panBefore.getX() || now.getY() != panBefore.getY());
					log.info("[worldmap] api pan: before={} after={} pannedApi={}", str(panBefore), str(now), pannedApi);
					capture(c, "panned-api");
					go(Phase.WHEEL);
				}
				break;

			case WHEEL:
			{
				final Rectangle map = mapView(c);
				zoomBefore = mapZoom(c);
				if (map != null)
				{
					synthWheel(c, map.x + map.width / 2, map.y + map.height / 2, -3);   // wheel up = zoom in
				}
				go(Phase.WHEEL_READ);
				break;
			}

			case WHEEL_READ:
				if (phaseFrame >= READ_DELAY)
				{
					final float now = mapZoom(c);
					zoomWheel = now != zoomBefore;
					log.info("[worldmap] wheel zoom: before={} after={} zoomWheel={}", zoomBefore, now, zoomWheel);
					go(Phase.ZBTN);
				}
				break;

			case ZBTN:
			{
				final Rectangle b = boundsOf(c, InterfaceID.Worldmap.ZOOM_IN);
				zoomBefore = mapZoom(c);
				if (b != null)
				{
					log.info("[worldmap] ZBTN click at {},{} (fresh bounds {})", b.x + b.width / 2, b.y + b.height / 2, b);
					synthClick(c, b.x + b.width / 2, b.y + b.height / 2);
				}
				else
				{
					log.info("[worldmap] ZOOM_IN widget missing");
				}
				go(Phase.ZBTN_READ);
				break;
			}

			case ZBTN_READ:
				if (phaseFrame >= READ_DELAY)
				{
					final float now = mapZoom(c);
					zoomBtn = now != zoomBefore;
					log.info("[worldmap] button zoom: before={} after={} zoomBtn={}", zoomBefore, now, zoomBtn);
					go(Phase.SEARCH);
				}
				break;

			case SEARCH:
			{
				final Rectangle b = boundsOf(c, InterfaceID.Worldmap.SEARCH);
				if (b != null)
				{
					synthClick(c, b.x + b.width / 2, b.y + b.height / 2);
				}
				else
				{
					log.info("[worldmap] SEARCH widget missing");
				}
				go(Phase.SEARCH_READ);
				break;
			}

			case SEARCH_READ:
				if (phaseFrame >= READ_DELAY)
				{
					searchInput = inputOpen(c);
					log.info("[worldmap] search: inputOpen={}", searchInput);
					if (searchInput)
					{
						synthKey(c, KeyEvent.VK_ESCAPE);   // close the field so it can't eat the later clicks
					}
					go(Phase.CLOSE);
				}
				break;

			// CLOSE is tested BEFORE the relocate experiments — forcing the window skews widget hit geometry
			// (the first run clicked CLOSE post-relocate and it silently missed).
			case CLOSE:
			{
				final Rectangle b = boundsOf(c, InterfaceID.Worldmap.CLOSE);
				if (b != null)
				{
					log.info("[worldmap] CLOSE click at {},{} (fresh bounds {})", b.x + b.width / 2, b.y + b.height / 2, b);
					synthClick(c, b.x + b.width / 2, b.y + b.height / 2);
				}
				else
				{
					log.info("[worldmap] CLOSE widget missing");
				}
				go(Phase.CLOSE_READ);
				break;
			}

			case CLOSE_READ:
				// Poll: the close takes more than a few frames to land (run 2 mis-read closed=false at 12 frames
				// while the very next phase found the map gone).
				if (boundsOf(c, InterfaceID.Worldmap.MAP_CONTAINER) == null)
				{
					closed = true;
					log.info("[worldmap] close: closed=true after {} frames", phaseFrame);
					go(Phase.REOPEN);
				}
				else if (phaseFrame >= 40)
				{
					log.info("[worldmap] close: closed=false (still open after {} frames)", phaseFrame);
					go(Phase.RELOC_UNIVERSE);   // map still open — run the relocate tests on it directly
				}
				break;

			case REOPEN:
			{
				final Rectangle orb = boundsOf(c, InterfaceID.Orbs.ORB_WORLDMAP);
				if (orb != null)
				{
					synthClick(c, orb.x + orb.width / 2, orb.y + orb.height / 2);
					go(Phase.REOPENING);
				}
				break;
			}

			case REOPENING:
				if (boundsOf(c, InterfaceID.Worldmap.MAP_CONTAINER) != null)
				{
					// The reopened map (fullscreen with the varcs zeroed) — dump + CLICK-TEST it: do the
					// re-laid controls hit where they render? (The forced-grow lesson: pixels alone lie.)
					log.info("[worldmap] REOPENED:");
					dumpGeometry(c);
					final Rectangle z = boundsOf(c, InterfaceID.Worldmap.ZOOM_IN);
					zoomBefore = mapZoom(c);
					if (z != null)
					{
						log.info("[worldmap] reopen ZBTN click at {},{}", z.x + z.width / 2, z.y + z.height / 2);
						synthClick(c, z.x + z.width / 2, z.y + z.height / 2);
					}
					go(Phase.REOPEN_ZBTN_READ);
				}
				else if (phaseFrame >= OPEN_RETRY * 2)
				{
					log.info("[worldmap] reopen for the relocate tests failed");
					finish();
				}
				break;

			case REOPEN_ZBTN_READ:
				if (phaseFrame >= READ_DELAY)
				{
					final float now = mapZoom(c);
					log.info("[worldmap] reopen button zoom: before={} after={} works={}", zoomBefore, now, now != zoomBefore);
					capture(c, "reopened");
					go(Phase.RELOC_UNIVERSE);
				}
				break;

			case RELOC_UNIVERSE:
				relocUniverse = relocStep(c, InterfaceID.Worldmap.UNIVERSE, "universe", relocUniverse, Phase.RELOC_WINDOW);
				break;

			case RELOC_WINDOW:
				relocWindow = relocStep(c, InterfaceID.Worldmap.WINDOW, "window", relocWindow, Phase.DONE);
				if (phase == Phase.DONE)
				{
					finish();   // relocStep only advances the phase; the verdict line still needs emitting
				}
				break;

			default:
				break;
		}
	}

	/**
	 * One relocate trial: force {@code widgetId} to (RELOC_X, RELOC_Y) EVERY frame (the veneer pattern — survives
	 * a redraw re-pin only if forcing each frame wins), settle, capture a steady-state PNG (ground truth), report
	 * the bounds delta, restore (clear the force is not possible — bounds revert on interface rebuild; the map is
	 * closed right after), and advance.
	 */
	private static String relocStep(Client c, int widgetId, String name, String result, Phase next)
	{
		final Widget w = widget(c, widgetId);
		if (w == null)
		{
			log.info("[worldmap] reloc {}: widget missing", name);
			go(next);
			return "missing";
		}
		w.setForcedPosition(RELOC_X, RELOC_Y);
		w.revalidate();
		if (phaseFrame >= SETTLE)
		{
			final Rectangle b = w.getBounds();
			final boolean at = b != null && Math.abs(b.x - RELOC_X) <= 8 && Math.abs(b.y - RELOC_Y) <= 8;
			final String r = name + ":boundsAt=" + at + ":" + (b == null ? "null" : (b.x + "," + b.y + "," + b.width + "x" + b.height));
			log.info("[worldmap] reloc {} steady-state {} (PNG is the render truth)", name, r);
			capture(c, "relocated-" + name);
			go(next);
			return r;
		}
		return result;
	}

	private static void finish()
	{
		go(Phase.DONE);
		if (verdictEmitted)
		{
			return;
		}
		verdictEmitted = true;
		log.info("[worldmap] WORLDMAP panned={} pannedApi={} zoomWheel={} zoomBtn={} searchInput={} closed={} relocUniverse={} relocWindow={}",
			panned, pannedApi, zoomWheel, zoomBtn, searchInput, closed, relocUniverse, relocWindow);
	}

	// ---- open-failure diagnostics --------------------------------------------------------------------------

	/** New chat lines since last frame (the engine says WHY an open was refused via a game message). */
	private static void diffChat(Client c)
	{
		try
		{
			final java.util.List<net.runelite.api.MessageNode> all = new java.util.ArrayList<>();
			for (final net.runelite.api.MessageNode node : c.getMessages())
			{
				if (node != null)
				{
					all.add(node);
				}
			}
			if (chatSeen < 0)
			{
				chatSeen = all.size();   // baseline: skip the login backlog
				return;
			}
			for (int i = chatSeen; i < all.size(); i++)
			{
				final net.runelite.api.MessageNode n = all.get(i);
				log.info("[worldmap] chat+ type={} name={} value={}",
					n.getType(), n.getName(), n.getValue());
			}
			chatSeen = all.size();
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** Widget-root group loads/unloads since last frame (does ANY interface load off the orb click?). */
	private static void diffRoots(Client c)
	{
		try
		{
			final java.util.Set<Integer> now = new java.util.TreeSet<>();
			final Widget[] roots = c.getWidgetRoots();
			if (roots != null)
			{
				for (final Widget w : roots)
				{
					if (w != null)
					{
						now.add(w.getId() >>> 16);
					}
				}
			}
			if (rootGroups != null && !now.equals(rootGroups))
			{
				final java.util.Set<Integer> added = new java.util.TreeSet<>(now);
				added.removeAll(rootGroups);
				final java.util.Set<Integer> gone = new java.util.TreeSet<>(rootGroups);
				gone.removeAll(now);
				log.info("[worldmap] roots changed: +{} -{} (now {})", added, gone, now);
			}
			rootGroups = now;
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static int safeVarbitInt(Client c, int id)
	{
		try
		{
			return c.getVarbitValue(id);
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	/** Replay a side-stone's op script (client-side tab switch — works while the stones are hidden). */
	private static void replayStoneOp(Client c, int idx)
	{
		try
		{
			final boolean pre = c.getVarbitValue(net.runelite.api.gameval.VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int base = pre ? InterfaceID.ToplevelPreEoc.STONE0 : InterfaceID.ToplevelOsrsStretch.STONE0;
			final Widget w = c.getWidget(base + idx);
			if (w == null)
			{
				return;
			}
			final Object[] op = w.getOnOpListener();
			if (op == null)
			{
				return;
			}
			c.createScriptEventBuilder(op).setSource(w).setOp(1).build().run();
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] fix: stone {} replay threw {}", idx, String.valueOf(ex));
		}
	}

	/** Type committed text into the engine — per char PRESSED + TYPED + RELEASED (the JavaBridge.onText shape). */
	private static void typeString(Client c, String s)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		long t = System.currentTimeMillis();
		for (int i = 0; i < s.length(); i++)
		{
			final char ch = s.charAt(i);
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t++, 0, KeyEvent.VK_UNDEFINED, ch));
		}
	}

	/** Find the settings row whose text contains {@code needle} (case-insensitive); returns its bounds. */
	private static Rectangle findRowByText(Client c, String needle)
	{
		try
		{
			final Widget root = c.getWidget(InterfaceID.Settings.UNIVERSE);
			if (root == null)
			{
				return null;
			}
			return scanForText(root, needle.toLowerCase(), 0);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static Rectangle scanForText(Widget w, String needle, int depth)
	{
		if (w == null || depth > 5)
		{
			return null;
		}
		try
		{
			final String text = w.getText();
			if (text != null && text.toLowerCase().contains(needle))
			{
				final Rectangle b = w.getBounds();
				if (b != null && b.width > 0 && b.height > 0)
				{
					return b;
				}
			}
			final Widget[][] kids = {w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
			for (final Widget[] arr : kids)
			{
				if (arr == null)
				{
					continue;
				}
				for (final Widget k : arr)
				{
					final Rectangle r = scanForText(k, needle, depth + 1);
					if (r != null)
					{
						return r;
					}
				}
			}
		}
		catch (RuntimeException ignore)
		{
		}
		return null;
	}

	/** Diagnostic: list the texts visible in the settings window (what DID the search produce?). */
	private static void dumpSettingsTexts(Client c)
	{
		try
		{
			final StringBuilder sb = new StringBuilder();
			collectTexts(c.getWidget(InterfaceID.Settings.UNIVERSE), sb, 0);
			log.info("[worldmap] fix: settings texts = [{}]", sb);
		}
		catch (RuntimeException ignore)
		{
		}
	}

	private static void collectTexts(Widget w, StringBuilder sb, int depth)
	{
		if (w == null || depth > 5 || sb.length() > 1500)
		{
			return;
		}
		try
		{
			final String t = w.getText();
			if (t != null && !t.isEmpty() && !w.isHidden())
			{
				sb.append(t.replaceAll("<[^>]*>", "")).append(" | ");
			}
			final Widget[][] kids = {w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()};
			for (final Widget[] arr : kids)
			{
				if (arr == null)
				{
					continue;
				}
				for (final Widget k : arr)
				{
					collectTexts(k, sb, depth + 1);
				}
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	/** The hover menu at the click point — IS the left-click action actually the world-map open op? */
	private static void logMenu(Client c)
	{
		try
		{
			final MenuEntry[] entries = c.getMenuEntries();
			if (entries == null || entries.length == 0)
			{
				log.info("[worldmap] menu: EMPTY");
				return;
			}
			for (int i = 0; i < entries.length; i++)
			{
				final MenuEntry e = entries[i];
				if (e == null)
				{
					continue;
				}
				log.info("[worldmap] menu[{}]{} option={} target={} type={} id={} p0={} p1={}",
					i, i == entries.length - 1 ? " <LEFT-CLICK>" : "",
					e.getOption(), e.getTarget(), e.getType(), e.getIdentifier(), e.getParam0(), e.getParam1());
			}
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] menu read threw {}", String.valueOf(ex));
		}
	}

	/** The account-stored state the open script could be gated on — dumped right before each orb click. */
	private static void dumpAccountState(Client c)
	{
		try
		{
			log.info("[worldmap] state varbit ORBS_DISABLED={} mmMinimised12986={} mmAlt6254={} WORLDMAP_TOGGLES={} WORLDMAP_DISABLE_KEY={} varp WORLDMAP_PERM={} TUTORIAL={} TUTORIAL_2={} varc rect={},{},{},{}",
				safeVarbit(c, net.runelite.api.gameval.VarbitID.ORBS_DISABLED),
				safeVarbit(c, 12986),
				safeVarbit(c, 6254),
				safeVarbit(c, net.runelite.api.gameval.VarbitID.WORLDMAP_TOGGLES),
				safeVarbit(c, net.runelite.api.gameval.VarbitID.WORLDMAP_DISABLE_KEY),
				safeVarp(c, net.runelite.api.gameval.VarPlayerID.WORLDMAP_PERM),
				safeVarp(c, net.runelite.api.gameval.VarPlayerID.TUTORIAL),
				safeVarp(c, net.runelite.api.gameval.VarPlayerID.TUTORIAL_2),
				safeVarc(c, net.runelite.api.gameval.VarClientID.WORLDMAP_X),
				safeVarc(c, net.runelite.api.gameval.VarClientID.WORLDMAP_Y),
				safeVarc(c, net.runelite.api.gameval.VarClientID.WORLDMAP_MINUSWIDTH),
				safeVarc(c, net.runelite.api.gameval.VarClientID.WORLDMAP_MINUSHEIGHT));
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] state dump threw {}", String.valueOf(ex));
		}
		try
		{
			final Widget orb = c.getWidget(InterfaceID.Orbs.ORB_WORLDMAP);
			log.info("[worldmap] orb onOp={}", orb == null ? "null-widget"
				: java.util.Arrays.toString(orb.getOnOpListener()));
			// The floating map's host layer in the PreEoc toplevel — if this is missing/hidden the floating-map
			// opensub has nowhere to land and fails silently.
			logRawState(c, "FLOATER", InterfaceID.ToplevelPreEoc.FLOATER);
			logRawState(c, "OVERLAY_HUD", InterfaceID.ToplevelPreEoc.OVERLAY_HUD);
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] orb/floater dump threw {}", String.valueOf(ex));
		}
	}

	private static String safeVarbit(Client c, int id)
	{
		try
		{
			return Integer.toString(c.getVarbitValue(id));
		}
		catch (RuntimeException ex)
		{
			return "ERR";
		}
	}

	private static String safeVarp(Client c, int id)
	{
		try
		{
			return Integer.toString(c.getVarpValue(id));
		}
		catch (RuntimeException ex)
		{
			return "ERR";
		}
	}

	private static String safeVarc(Client c, int id)
	{
		try
		{
			return Integer.toString(c.getVarcIntValue(id));
		}
		catch (RuntimeException ex)
		{
			return "ERR";
		}
	}

	private static void logRawState(Client c, String name, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[worldmap] pending {} = null", name);
			}
			else
			{
				log.info("[worldmap] pending {} hidden={} bounds={}", name, w.isHidden(), w.getBounds());
			}
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] pending {} threw {}", name, String.valueOf(ex));
		}
	}

	// ---- engine reads --------------------------------------------------------------------------------------

	/** The pannable map view — MAP_DISPLAY when present, else MAP_CONTAINER. */
	private static Rectangle mapView(Client c)
	{
		final Rectangle d = boundsOf(c, InterfaceID.Worldmap.MAP_DISPLAY);
		return d != null ? d : boundsOf(c, InterfaceID.Worldmap.MAP_CONTAINER);
	}

	private static Point mapPos(Client c)
	{
		try
		{
			final WorldMap wm = c.getWorldMap();
			return wm == null ? null : wm.getWorldMapPosition();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static float mapZoom(Client c)
	{
		try
		{
			final WorldMap wm = c.getWorldMap();
			return wm == null ? Float.NaN : wm.getWorldMapZoom();
		}
		catch (RuntimeException ex)
		{
			return Float.NaN;
		}
	}

	private static boolean inputOpen(Client c)
	{
		try
		{
			return c.getVarcIntValue(VarClientInt.INPUT_TYPE) != 0;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void dumpGeometry(Client c)
	{
		dumpOne(c, "UNIVERSE", InterfaceID.Worldmap.UNIVERSE);
		dumpOne(c, "WINDOW", InterfaceID.Worldmap.WINDOW);
		dumpOne(c, "MAP_CONTAINER", InterfaceID.Worldmap.MAP_CONTAINER);
		dumpOne(c, "MAP_DISPLAY", InterfaceID.Worldmap.MAP_DISPLAY);
		dumpOne(c, "CLOSE", InterfaceID.Worldmap.CLOSE);
		dumpOne(c, "ZOOM_IN", InterfaceID.Worldmap.ZOOM_IN);
		dumpOne(c, "ZOOM_OUT", InterfaceID.Worldmap.ZOOM_OUT);
		dumpOne(c, "SEARCH", InterfaceID.Worldmap.SEARCH);
		dumpOne(c, "BOTTOM", InterfaceID.Worldmap.BOTTOM);
		dumpOne(c, "KEY_CONTAINER", InterfaceID.Worldmap.KEY_CONTAINER);
		dumpOne(c, "OVERVIEW_CONTAINER", InterfaceID.Worldmap.OVERVIEW_CONTAINER);
	}

	private static void dumpOne(Client c, String name, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[worldmap] geom {} = null", name);
				return;
			}
			log.info("[worldmap] geom {} bounds={} hidden={}", name, w.getBounds(), w.isHidden());
		}
		catch (RuntimeException ex)
		{
			log.info("[worldmap] geom {} threw {}", name, String.valueOf(ex));
		}
	}

	private static Widget widget(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return (w == null || w.isHidden()) ? null : w;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static Rectangle boundsOf(Client c, int id)
	{
		final Widget w = widget(c, id);
		if (w == null)
		{
			return null;
		}
		final Rectangle b = w.getBounds();
		return (b == null || b.width <= 0 || b.height <= 0) ? null : b;
	}

	private static String str(Point p)
	{
		return p == null ? "null" : (p.getX() + "," + p.getY());
	}

	private static boolean welcomeUp(Client c)
	{
		try
		{
			final Widget w = c.getWidget(InterfaceID.WelcomeScreen.UNIVERSE);
			return w != null && !w.isHidden();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void go(Phase next)
	{
		phase = next;
		phaseFrame = 0;
	}

	// ---- synthetic input (the proven JavaBridge / probe event shapes) ---------------------------------------

	private static EventQueue queue(Client c, int x, int y)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return null;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		if (!focused)
		{
			final long t = System.currentTimeMillis();
			q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
			q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
			focused = true;
		}
		return q;
	}

	private static void synthClick(Client c, int x, int y)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		final Canvas canvas = c.getCanvas();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3, 0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static void synthMove(Client c, int x, int y)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		q.postEvent(new MouseEvent(c.getCanvas(), MouseEvent.MOUSE_MOVED, System.currentTimeMillis(),
			0, x, y, 0, false));
	}

	private static void synthPress(Client c, int x, int y)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		final Canvas canvas = c.getCanvas();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 1,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static void synthDrag(Client c, int x, int y)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		q.postEvent(new MouseEvent(c.getCanvas(), MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
			InputEvent.BUTTON1_DOWN_MASK, x, y, 0, false, MouseEvent.NOBUTTON));
	}

	private static void synthRelease(Client c, int x, int y)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		q.postEvent(new MouseEvent(c.getCanvas(), MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
			0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static void synthWheel(Client c, int x, int y, int rotation)
	{
		final EventQueue q = queue(c, x, y);
		if (q == null)
		{
			return;
		}
		final Canvas canvas = c.getCanvas();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
		q.postEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, t + 1, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.abs(rotation), rotation));
	}

	private static void synthKey(Client c, int vk)
	{
		final Canvas canvas = c.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t, 0, vk, KeyEvent.CHAR_UNDEFINED));
		q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t + 1, 0, vk, KeyEvent.CHAR_UNDEFINED));
	}

	private static void capture(Client c, String mode)
	{
		if (DIR == null)
		{
			return;
		}
		try
		{
			final BufferProvider bp = c.getBufferProvider();
			final int[] px = bp.getPixels();
			final int w = bp.getWidth();
			final int h = bp.getHeight();
			if (px == null || w <= 0 || h <= 0 || px.length < w * h)
			{
				return;
			}
			final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, w, h, px, 0, w);
			final File out = new File(DIR, String.format("worldmap-%s-%dx%d.png", mode, w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[worldmap] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[worldmap] capture failed: {}", String.valueOf(ex));
		}
	}
}
