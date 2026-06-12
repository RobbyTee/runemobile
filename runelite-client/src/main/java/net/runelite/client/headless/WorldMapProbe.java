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
		WAIT, OPENING, OPEN_SETTLE, PAN_PRESS, PAN_HOLD, PAN_DRAG, PAN_READ, PAN_API, PAN_API_READ,
		WHEEL, WHEEL_READ, ZBTN, ZBTN_READ, SEARCH, SEARCH_READ, CLOSE, CLOSE_READ,
		REOPEN, REOPENING, REOPEN_ZBTN_READ, RELOC_UNIVERSE, RELOC_WINDOW, DONE
	}

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static int openTries;
	private static boolean focused;
	private static boolean verdictEmitted;

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

		try
		{
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
			case WAIT:
			{
				final Rectangle orb = boundsOf(c, InterfaceID.Orbs.ORB_WORLDMAP);
				if (orb == null)
				{
					return;
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
