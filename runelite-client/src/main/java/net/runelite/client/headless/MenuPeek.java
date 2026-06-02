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
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuEntry;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Touch-targeting de-risk (W3): proves the <b>menu-peek primitive</b> that the press-and-peek input model
 * rests on, headless on desktop OpenJDK 21 + CACIO, BEFORE any native UI is built (the W2 develop-first rule).
 *
 * <p>The press-and-peek plan reads the engine's <em>current left-click action</em> at an arbitrary pointer
 * position without committing: post a synthetic {@code MOUSE_MOVED} to (x,y), then a frame or two later read
 * {@link Client#getMenuEntries()} on the client thread and take the <b>last</b> array element — which is the
 * top displayed entry / left-click default (see {@code MenuOpened.getFirstEntry()}, which returns
 * {@code menuEntries[length-1]}). The whole plan is load-bearing on one unproven empirical claim:
 *
 * <blockquote>Does {@code getMenuEntries()}, read in {@code Hooks.draw} a cycle after a synthetic move,
 * reflect the correct hover/left-click entries for that point — with NO right-click menu open?</blockquote>
 *
 * <p>This probe settles exactly that, self-validating: it enumerates real interactables (NPCs/players, whose
 * names it knows), moves the synthetic pointer onto each one's convex-hull centroid, and asserts the peeked
 * menu's <b>tail</b> entry targets <em>that named entity</em>. If hovering a "Goblin" yields a top entry whose
 * target contains "Goblin", the primitive works. It also measures how many client cycles the read takes to
 * stabilize after a move (the latency number), and confirms peeking never opens a menu
 * ({@link Client#isMenuOpen()} stays false — peek is hover-only, it must not press a button).
 *
 * <p><b>Read-only.</b> It posts {@code MOUSE_MOVED} only — never a press/click and never {@code menuAction},
 * so it cannot fire a real in-game action (safe on a live throwaway account). Invoke-parity is a separate,
 * explicitly destructive step left for later. One-shot; runs on the client thread (from {@code Hooks.draw});
 * entirely inert unless {@code -Drunelite.menupeek=true}. Emits {@code PEEK_*} markers + a single
 * bash-parseable {@code PEEK_VERDICT} census line to stdout; the GREEN/RED call is computed OUTSIDE the JVM
 * in bash, md5-gated against the verbatim log (the documented false-GREEN guard — never a JVM-printed verdict).
 */
@Slf4j
public final class MenuPeek
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.menupeek");

	// LOGGED_IN frames to wait before acting (the welcome screen is up immediately, so this is short).
	private static final int WARMUP_FRAMES = Integer.getInteger("runelite.menupeek.warmup", 60);
	// Enter-world: the post-login welcome screen (InterfaceID.WelcomeScreen, group 378) covers the viewport
	// and the 3D camera isn't positioned until you click to play, so dismiss it first (what a player does).
	private static final int ENTER_SETTLE = Integer.getInteger("runelite.menupeek.enterSettle", 60);
	private static final int ENTER_RECLICK = Integer.getInteger("runelite.menupeek.enterReclick", 30);
	private static final int ENTER_MAX = Integer.getInteger("runelite.menupeek.enterMax", 6);
	private static final int DEADLINE_FRAMES = Integer.getInteger("runelite.menupeek.deadline", 600);
	// Max candidates to peek (NPCs + players, then a couple fixed grid points).
	private static final int MAX_CANDS = Integer.getInteger("runelite.menupeek.maxCands", 10);
	// Per-candidate: how many client cycles to wait for the menu read to stabilize, and how many consecutive
	// identical reads count as "stable" (the read is considered settled at the first of that run).
	private static final int MAX_SETTLE = Integer.getInteger("runelite.menupeek.settle", 12);
	private static final int STABLE_NEEDED = Integer.getInteger("runelite.menupeek.stable", 2);

	private enum Phase { WAIT_WARMUP, ENTER_WORLD, SETTLE, PEEK, DONE }

	private static Phase phase = Phase.WAIT_WARMUP;
	private static int frames;
	private static boolean primed;

	// Enter-world bookkeeping.
	private static int enterAttempts;
	private static int lastEnterClick = -1000;
	private static int settleUntil;
	private static int enterX;
	private static int enterY;

	private static final List<Cand> cands = new ArrayList<>();
	private static int ci;            // current candidate index
	private static int sub;           // 0 = post move; 1+ = settle/read
	private static int sinceMove;     // client cycles since the move was posted for this candidate
	private static String lastFp;     // last tail fingerprint seen (stability tracking)
	private static int stable;        // consecutive identical tail reads

	// Census accumulators (for the single PEEK_VERDICT line).
	private static int namedCands;
	private static int tailMatch;     // named cands whose TAIL entry target contained the entity name
	private static int anyMatch;      // named cands where SOME entry target contained the name
	private static int emptyMenus;    // peeks that read zero entries
	// The rigorous denominator: named actors that stayed put for the whole peek (movement detected via scene
	// location). The primitive's claim must hold 100% on these; movers/offscreen are reported but excluded.
	private static int stationaryNamed;
	private static int tailMatchStat; // stationary named cands whose TAIL targeted the entity (the core gate)
	private static int movedNamed;    // named actors that changed scene location during the peek
	private static int offscreenNamed;// named actors that had walked off-viewport by hover time
	private static int maxTicks;
	private static int sumTicks;
	private static int tickSamples;
	private static boolean menuOpenDuringPeek;

	private MenuPeek()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** One step of the experiment, on the client thread (from {@code Hooks.draw}), only when LOGGED_IN. */
	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || phase == Phase.DONE || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		frames++;

		try
		{
			switch (phase)
			{
				case WAIT_WARMUP:
					if (frames >= WARMUP_FRAMES)
					{
						primeFocus(client);
						enterX = Math.max(0, client.getViewportXOffset()) + safe(client.getViewportWidth(), 765) / 2;
						enterY = Math.max(0, client.getViewportYOffset()) + safe(client.getViewportHeight(), 503) / 2;
						if (welcomeUp(client))
						{
							out("PEEK_ENTER welcome screen up -> click " + enterX + "," + enterY + " to enter world");
							phase = Phase.ENTER_WORLD;
						}
						else
						{
							beginCollect(client);
						}
					}
					break;

				case ENTER_WORLD:
					if (!welcomeUp(client))
					{
						out("PEEK_ENTER entered world after " + enterAttempts + " click(s)");
						settleUntil = frames + ENTER_SETTLE;
						phase = Phase.SETTLE;
					}
					else if (frames - lastEnterClick >= ENTER_RECLICK)
					{
						if (enterAttempts >= ENTER_MAX)
						{
							out("PEEK_FATAL=welcome-stuck attempts=" + enterAttempts);
							verdict();
							break;
						}
						enterAttempts++;
						lastEnterClick = frames;
						// The ONLY button-press the probe makes: clicking "play" on the welcome screen to enter
						// the world (benign — exactly what a player does). World-entity peeks need a live camera.
						synthesizeClick(client, enterX, enterY);
					}
					break;

				case SETTLE:
					if (frames >= settleUntil)
					{
						beginCollect(client);
					}
					break;

				case PEEK:
					stepPeek(client);
					break;

				default:
					break;
			}

			if (phase != Phase.DONE && frames >= DEADLINE_FRAMES)
			{
				out("PEEK_FATAL=deadline frames=" + frames + " phase=" + phase);
				verdict();
			}
		}
		catch (RuntimeException ex)
		{
			out("PEEK_FATAL=ex:" + ex.getClass().getSimpleName() + ":" + ex.getMessage());
			log.warn("[menupeek] failed", ex);
			verdict();
		}
	}

	// ---- phases ----------------------------------------------------------------------------------------

	/** Whether the post-login welcome screen (InterfaceID.WelcomeScreen root) is shown over the world. */
	private static boolean welcomeUp(Client client)
	{
		try
		{
			final Widget w = client.getWidget(InterfaceID.WelcomeScreen.UNIVERSE);
			return w != null && !w.isHidden();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	/** Read the baseline + enumerate candidates; advance to PEEK (or finish if there are none). */
	private static void beginCollect(Client client)
	{
		baseline(client);
		collect(client);
		if (cands.isEmpty())
		{
			out("PEEK_FATAL=no-candidates");
			verdict();
		}
		else
		{
			phase = Phase.PEEK;
		}
	}

	/** Read the menu with NO synthetic move first — shows what's there at rest, to contrast against a peek. */
	private static void baseline(Client client)
	{
		final MenuEntry[] e = client.getMenuEntries();
		final MenuEntry tail = (e != null && e.length > 0) ? e[e.length - 1] : null;
		out("PEEK_BASELINE count=" + (e == null ? -1 : e.length)
			+ " menuOpen=" + client.isMenuOpen()
			+ " tail=" + fingerprint(tail));
	}

	/** Enumerate real interactables (local player, NPCs, players) by projected model centre + known name. */
	private static void collect(Client client)
	{
		final int vw = safe(client.getViewportWidth(), 765);
		final int vh = safe(client.getViewportHeight(), 503);
		final int vx = Math.max(0, client.getViewportXOffset());
		final int vy = Math.max(0, client.getViewportYOffset());

		// Diagnostics: which root/interface is up + the viewport, so a blocking post-login splash is visible.
		int npcN = 0;
		int playerN = 0;
		final WorldView wv = client.getTopLevelWorldView();
		if (wv != null)
		{
			for (final Actor a : wv.npcs())
			{
				npcN++;
			}
			for (final Actor a : wv.players())
			{
				playerN++;
			}
		}
		out("PEEK_DIAG topLevel=" + safeInt(client::getTopLevelInterfaceId)
			+ " resized=" + safeBool(client::isResized)
			+ " plane=" + safeInt(client::getPlane)
			+ " vp=" + vx + "," + vy + "," + vw + "," + vh
			+ " npcs=" + npcN + " players=" + playerN);

		// Local player first (always present, name known) — a self-hover is a clean named target.
		addActor(client, client.getLocalPlayer(), "SELF");
		if (wv != null)
		{
			for (final Actor a : wv.npcs())
			{
				if (cands.size() >= MAX_CANDS)
				{
					break;
				}
				addActor(client, a, "NPC");
			}
			for (final Actor a : wv.players())
			{
				if (cands.size() >= MAX_CANDS)
				{
					break;
				}
				if (a != client.getLocalPlayer())
				{
					addActor(client, a, "PLAYER");
				}
			}
		}

		// A couple of fixed viewport points (generic — no expected name): the empty-ground "Walk here" case and
		// a saturated overlap region. Also surface whatever covers the centre (a splash) via their full dump.
		cands.add(new Cand(vx + vw / 2, vy + vh / 2, null, "GRID"));
		cands.add(new Cand(vx + vw / 3, vy + vh / 3, null, "GRID"));

		for (int i = 0; i < cands.size(); i++)
		{
			final Cand c = cands.get(i);
			if (matchable(c))
			{
				namedCands++;
			}
			out("PEEK_CAND_DEF=" + i + " kind=" + c.kind + " name=" + (c.name == null ? "(none)" : c.name)
				+ " pt=" + c.x + "," + c.y);
		}
		out("PEEK_COLLECTED total=" + cands.size() + " named=" + namedCands);
	}

	/**
	 * Add an actor candidate at its projected model centre. Uses {@link Perspective#localToCanvas} (pure camera
	 * math — independent of the renderer, unlike {@code getConvexHull}, which came back degenerate (0,0)
	 * headless) at half the actor's logical height, falling back to the hull centroid. Skips anything that
	 * projects off the world viewport.
	 */
	private static void addActor(Client client, Actor a, String kind)
	{
		if (a == null)
		{
			return;
		}
		final String name = stripTags(a.getName());
		if (name == null || name.isEmpty())
		{
			return;
		}
		final int[] p = actorCanvasPoint(client, a);
		if (p == null)
		{
			return;
		}
		cands.add(new Cand(p[0], p[1], name, kind, a));
	}

	/**
	 * Aim point for an actor, in canvas pixels — null if it can't be aimed onto the viewport. Prefers the
	 * convex-hull centroid (the EXACT clickbox the engine hit-tests, guaranteed inside the convex shape) now
	 * that the camera is live in-world; falls back to {@link Perspective#localToCanvas} at half model height
	 * (pure camera math) when the hull is degenerate (it was (0,0) before we entered the world).
	 */
	private static int[] actorCanvasPoint(Client client, Actor a)
	{
		try
		{
			int x = -1;
			int y = -1;
			final int[] cen = centroid(a.getConvexHull());
			if (cen != null && !(cen[0] == 0 && cen[1] == 0))
			{
				x = cen[0];
				y = cen[1];
			}
			else
			{
				final LocalPoint lp = a.getLocalLocation();
				if (lp != null)
				{
					final Point pt = Perspective.localToCanvas(client, lp, client.getPlane(), a.getLogicalHeight() / 2);
					if (pt != null)
					{
						x = pt.getX();
						y = pt.getY();
					}
				}
			}
			return (x >= 0 && inViewport(client, x, y)) ? new int[]{x, y} : null;
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static boolean inViewport(Client client, int x, int y)
	{
		final int vx = client.getViewportXOffset();
		final int vy = client.getViewportYOffset();
		final int vw = client.getViewportWidth();
		final int vh = client.getViewportHeight();
		if (vx < 0 || vy < 0 || vw <= 0 || vh <= 0)
		{
			return false;
		}
		return x >= vx && x < vx + vw && y >= vy && y < vy + vh;
	}

	/** Per-candidate sub-state machine: post a move, then settle-read the menu until the tail stabilizes. */
	private static void stepPeek(Client client)
	{
		final Cand c = cands.get(ci);

		if (sub == 0)
		{
			// Re-aim actors at hover time so a wanderer is targeted where it IS now, not where it was at
			// collect (the collect-time miss this fixes). Snapshot its scene location to detect movement.
			if (c.actor != null)
			{
				final int[] p = actorCanvasPoint(client, c.actor);
				if (p == null)
				{
					// Walked off-screen / no longer projectable since collect — a known unaimable case, not a
					// primitive failure. Record + skip (excluded from the stationary gate).
					recordSkipped(c, "offscreen");
					ci++;
					if (ci >= cands.size())
					{
						clearHover(client);
						verdict();
					}
					return;
				}
				c.x = p[0];
				c.y = p[1];
				final LocalPoint lp = c.actor.getLocalLocation();
				if (lp != null)
				{
					c.locX = lp.getX();
					c.locY = lp.getY();
				}
			}
			postMove(client, c.x, c.y);
			sinceMove = 0;
			lastFp = null;
			stable = 0;
			sub = 1;
			return;
		}

		sinceMove++;
		if (client.isMenuOpen())
		{
			menuOpenDuringPeek = true;
		}

		final MenuEntry[] e = client.getMenuEntries();
		final int count = e == null ? 0 : e.length;
		final MenuEntry tail = count > 0 ? e[count - 1] : null;
		final String fp = fingerprint(tail);

		if (fp.equals(lastFp) && count > 0)
		{
			stable++;
		}
		else
		{
			stable = 0;
			lastFp = fp;
		}

		final boolean settled = stable >= STABLE_NEEDED;
		if (settled || sinceMove >= MAX_SETTLE)
		{
			finishCand(client, c, e, count, tail, settled);
			ci++;
			sub = 0;
			if (ci >= cands.size())
			{
				clearHover(client);
				verdict();
			}
		}
	}

	private static void finishCand(Client client, Cand c, MenuEntry[] e, int count, MenuEntry tail, boolean settled)
	{
		// Frame at which the tail first reached its stable value (clamp >= 1).
		final int ticksToStable = Math.max(1, sinceMove - (settled ? stable : 0));
		tickSamples++;
		sumTicks += ticksToStable;
		maxTicks = Math.max(maxTicks, ticksToStable);
		if (count == 0)
		{
			emptyMenus++;
		}

		// Movement detection: did the actor change scene location between the move-post and this settle-read?
		boolean moved = false;
		if (c.actor != null && c.locX != Integer.MIN_VALUE)
		{
			final LocalPoint now = c.actor.getLocalLocation();
			moved = now == null || now.getX() != c.locX || now.getY() != c.locY;
		}

		final String tailTarget = stripTags(tail == null ? "" : tail.getTarget());
		boolean tMatch = false;
		boolean aMatch = false;
		if (matchable(c))
		{
			final String want = c.name.toLowerCase();
			tMatch = tailTarget != null && tailTarget.toLowerCase().contains(want);
			if (e != null)
			{
				for (final MenuEntry me : e)
				{
					final String t = stripTags(me.getTarget());
					if (t != null && t.toLowerCase().contains(want))
					{
						aMatch = true;
						break;
					}
				}
			}
			if (tMatch)
			{
				tailMatch++;
			}
			if (aMatch)
			{
				anyMatch++;
			}
			// Rigorous gate denominator: only entities that held still. A mover that slid off its clickbox
			// in the settle window is the exact case release-revalidation handles in production, not a flaw.
			if (moved)
			{
				movedNamed++;
			}
			else
			{
				stationaryNamed++;
				if (tMatch)
				{
					tailMatchStat++;
				}
			}
		}

		out("PEEK_CAND=" + ci
			+ " kind=" + c.kind
			+ " name=" + (c.name == null ? "(none)" : c.name)
			+ " pt=" + c.x + "," + c.y
			+ " count=" + count
			+ " ticks=" + ticksToStable
			+ " settled=" + settled
			+ " moved=" + moved
			+ " menuOpen=" + client.isMenuOpen()
			+ " tailOption=" + esc(tail == null ? "" : tail.getOption())
			+ " tailTarget=" + esc(tailTarget)
			+ " tailType=" + (tail == null ? "-" : String.valueOf(tail.getType()))
			+ " tailMatch=" + tMatch
			+ " anyMatch=" + aMatch);

		// Dump the top entries (tail-first = display order) for the first 3 named candidates AND every GRID
		// point, so the "tail is the top/left-click entry" ordering — and whatever covers a grid point (a
		// splash) — can be eyeballed in the verbatim log.
		if (e != null && ((c.name != null && ci < 3) || "GRID".equals(c.kind)))
		{
			final int n = Math.min(8, e.length);
			for (int k = 0; k < n; k++)
			{
				final int idx = e.length - 1 - k;   // display order: tail first
				final MenuEntry me = e[idx];
				out("PEEK_DUMP=" + ci + " disp=" + k + " arrIdx=" + idx
					+ " option=" + esc(me.getOption())
					+ " target=" + esc(stripTags(me.getTarget()))
					+ " type=" + String.valueOf(me.getType())
					+ " deprio=" + me.isDeprioritized());
			}
		}
	}

	/**
	 * A candidate whose hovered TOP entry should target it by name — a real interactable other than the local
	 * player (self-hover legitimately yields "Walk here", so SELF is peeked + reported but never match-gated).
	 */
	private static boolean matchable(Cand c)
	{
		return c.name != null && !"SELF".equals(c.kind);
	}

	/** Record a named actor that had walked off-viewport by hover time (excluded from the stationary gate). */
	private static void recordSkipped(Cand c, String why)
	{
		if (matchable(c))
		{
			offscreenNamed++;
		}
		out("PEEK_CAND=" + ci + " kind=" + c.kind + " name=" + (c.name == null ? "(none)" : c.name)
			+ " skipped=" + why);
	}

	private static void verdict()
	{
		final int avg = tickSamples > 0 ? Math.round((float) sumTicks / tickSamples) : -1;
		// Single bash-parseable census. The GREEN/RED decision is made in menupeek-verdict.sh, NOT here.
		// The core gate is stationary: tailMatchStat == stationaryNamed (every named entity that held still
		// had its hovered TOP entry target it). movedNamed/offscreenNamed are reported, not gated.
		out("PEEK_VERDICT"
			+ " cands=" + cands.size()
			+ " named=" + namedCands
			+ " stationaryNamed=" + stationaryNamed
			+ " tailMatchStat=" + tailMatchStat
			+ " movedNamed=" + movedNamed
			+ " offscreenNamed=" + offscreenNamed
			+ " tailMatch=" + tailMatch
			+ " anyMatch=" + anyMatch
			+ " emptyMenus=" + emptyMenus
			+ " maxTicks=" + maxTicks
			+ " avgTicks=" + avg
			+ " menuOpenDuringPeek=" + (menuOpenDuringPeek ? 1 : 0));
		out("PEEK_DONE=1");
		phase = Phase.DONE;
	}

	// ---- AWT injection (read-only: move + focus only, never a button press) ----------------------------

	private static void primeFocus(Client client)
	{
		if (primed)
		{
			return;
		}
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, 0, 0, 0, false));
		primed = true;
	}

	private static void postMove(Client client, int x, int y)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
	}

	/** Full focus-prime + move + press + release + click at (x,y) — the proven HeadlessLogin click shape. */
	private static void synthesizeClick(Client client, int x, int y)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 1,
			java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 2, 0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 3, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static void clearHover(Client client)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_EXITED, t, 0, -1, -1, 0, false));
	}

	// ---- helpers ---------------------------------------------------------------------------------------

	private static final class Cand
	{
		int x;               // re-projected fresh at hover time for actors (handles drift)
		int y;
		final String name;   // null for a generic grid point
		final String kind;
		final Actor actor;   // null for a grid point; used to re-aim + detect movement at hover time
		int locX = Integer.MIN_VALUE;   // actor scene location snapshot at move time (movement detection)
		int locY = Integer.MIN_VALUE;

		Cand(int x, int y, String name, String kind)
		{
			this(x, y, name, kind, null);
		}

		Cand(int x, int y, String name, String kind, Actor actor)
		{
			this.x = x;
			this.y = y;
			this.name = name;
			this.kind = kind;
			this.actor = actor;
		}
	}

	private static String fingerprint(MenuEntry e)
	{
		if (e == null)
		{
			return "EMPTY";
		}
		return e.getOption() + "|" + stripTags(e.getTarget()) + "|" + e.getType()
			+ "|" + e.getIdentifier() + "|" + e.getParam0() + "|" + e.getParam1() + "|" + e.getItemId();
	}

	/** Centroid of a convex hull's vertices — guaranteed inside the (convex) shape. Null if no vertices. */
	private static int[] centroid(Shape hull)
	{
		if (hull == null)
		{
			return null;
		}
		final PathIterator it = hull.getPathIterator(null);
		final double[] coords = new double[6];
		double sx = 0;
		double sy = 0;
		int n = 0;
		while (!it.isDone())
		{
			final int seg = it.currentSegment(coords);
			if (seg == PathIterator.SEG_MOVETO || seg == PathIterator.SEG_LINETO)
			{
				sx += coords[0];
				sy += coords[1];
				n++;
			}
			it.next();
		}
		if (n == 0)
		{
			return null;
		}
		return new int[]{(int) (sx / n), (int) (sy / n)};
	}

	private static String stripTags(String s)
	{
		return s == null ? null : s.replaceAll("<[^>]*>", "");
	}

	/** Make a value safe for one space-delimited token (no spaces/newlines) so the bash grep stays simple. */
	private static String esc(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "-";
		}
		return s.replaceAll("\\s+", "_");
	}

	private static int safe(int v, int dflt)
	{
		return v > 0 ? v : dflt;
	}

	private static int safeInt(java.util.function.IntSupplier s)
	{
		try
		{
			return s.getAsInt();
		}
		catch (RuntimeException ex)
		{
			return -1;
		}
	}

	private static boolean safeBool(java.util.function.BooleanSupplier s)
	{
		try
		{
			return s.getAsBoolean();
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	/** Markers go to stdout so they land in the captured log verbatim for the bash md5-gate. */
	private static void out(String s)
	{
		System.out.println(s);
		System.out.flush();
		log.info("[menupeek] {}", s);
	}
}
