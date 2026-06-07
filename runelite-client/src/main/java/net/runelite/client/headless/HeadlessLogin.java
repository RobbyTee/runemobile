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
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.EnumSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.World;
import net.runelite.api.WorldType;

/**
 * Gate 1c-ii / Phase-4 spike: the minimal slice of input injection needed to cross the
 * {@code LOGIN_SCREEN → LOGGED_IN} boundary headless, in-process (no native touch bridge).
 *
 * <p>The {@code JX_*} launcher handoff auto-<em>authenticates</em> the account — the gamepack renders the
 * "Welcome to RuneScape / Play Now / &lt;name&gt;" welcome screen ({@code loginIndex == 10}) — but does
 * <em>not</em> auto-<em>login</em>; it parks at {@link GameState#LOGIN_SCREEN} waiting for a click on the
 * Play Now button. There is no programmatic shortcut ({@code setGameState(LOGGED_IN)} doesn't drive the
 * gamepack's network login), so we synthesize the click.
 *
 * <p>On a <em>fresh</em> client (e.g. the device, where {@code .runelite} is recreated each install) the
 * gamepack first shows an <strong>EULA Accept/Decline dialog</strong> at an earlier login sub-state
 * ({@code loginIndex == 12} as observed on device) before it will reach the welcome screen. So this is a
 * <strong>two-stage</strong> state machine: if the EULA stage is enabled, click <em>Accept</em> first, then
 * (once the gamepack advances to {@code loginIndex == 10}) click <em>Play Now</em>. The EULA stage is inert
 * on a client that has already accepted (desktop), where {@code loginIndex} goes straight to 10.
 *
 * <p>This answers a real Phase-4 kill-risk: does the window-less engine accept a synthesized AWT mouse
 * event at all (it may gate input on real focus, which has no peer headless)? We post a focus-gain plus a
 * full enter/move/press/release/click sequence to the gamepack {@link Canvas} via the toolkit
 * {@link EventQueue} — the same mechanism a UIKit-touch → AWT-{@code MouseEvent} bridge would use on iOS.
 *
 * <p>Entirely inert unless {@code -Drunelite.headless.autologin=true}. The Play Now point defaults to the
 * button centre and is overridable with {@code -Drunelite.headless.autologin.point=x,y}; the EULA stage is
 * gated on {@code -Drunelite.headless.autologin.eula=true} with point
 * {@code -Drunelite.headless.autologin.eula.point=x,y} and sub-state
 * {@code -Drunelite.headless.autologin.eula.loginIndex=12}. Each stage re-arms up to {@code maxAttempts}
 * times if the state hasn't advanced, so a coordinate miss is visible (the captured frames + the attempt
 * log tell us whether the click landed).
 */
@Slf4j
public final class HeadlessLogin
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.headless.autologin");
	// The "logged into a Jagex account, ready to play" welcome screen — the only login sub-state with a
	// Play Now button. (2 = username/password form, 4 = authenticator.)
	private static final int WELCOME_LOGIN_INDEX =
		Integer.getInteger("runelite.headless.autologin.loginIndex", 10);
	private static final int WARMUP_DRAWS = Integer.getInteger("runelite.headless.autologin.warmup", 20);
	private static final int COOLDOWN_DRAWS = Integer.getInteger("runelite.headless.autologin.cooldown", 120);
	private static final int MAX_ATTEMPTS = Integer.getInteger("runelite.headless.autologin.maxAttempts", 5);

	// Stage 1 (optional): the EULA Accept/Decline dialog a fresh client shows before the welcome screen.
	// Inert on a client that already accepted (desktop) — loginIndex never equals EULA_LOGIN_INDEX there.
	private static final boolean EULA_ENABLED = Boolean.getBoolean("runelite.headless.autologin.eula");
	private static final int EULA_LOGIN_INDEX =
		Integer.getInteger("runelite.headless.autologin.eula.loginIndex", 12);

	// Stage 2: the Play Now button centre.
	private static final int CLICK_X;
	private static final int CLICK_Y;
	// Stage 1: the EULA Accept button centre (765x503 fixed login; observed on device ~302,312).
	private static final int EULA_X;
	private static final int EULA_Y;

	// Optional: switch to a specific world before clicking Play Now. A throwaway free-to-play account is
	// rejected ("You need a members' account to use this world") if the remembered world is members, so we
	// point it at an F2P world first. World W's host is oldschool{W-300}.runescape.com.
	private static final int WORLD_ID = Integer.getInteger("runelite.headless.autologin.world", 0);
	private static final String WORLD_ADDRESS = System.getProperty("runelite.headless.autologin.worldAddress");

	private static int draws;
	// Per-stage attempt bookkeeping so an EULA retry budget can't starve the Play Now stage.
	private static int eulaAttempts;
	private static int lastEulaDraw;
	private static boolean eulaCleared;
	private static int playAttempts;
	private static int lastPlayDraw;
	private static boolean worldSelected;
	private static boolean done;

	static
	{
		final int[] play = parsePoint("runelite.headless.autologin.point",
			Constants.GAME_FIXED_WIDTH / 2 /* 382 — Play Now is horizontally centred */,
			248 /* ~middle of the welcome panel (765x503 fixed login) */);
		CLICK_X = play[0];
		CLICK_Y = play[1];
		final int[] eula = parsePoint("runelite.headless.autologin.eula.point", 302, 312);
		EULA_X = eula[0];
		EULA_Y = eula[1];
		if (ENABLED)
		{
			log.info("[headless-login] ENABLED playPoint={},{} welcomeLoginIndex={} eula={} eulaPoint={},{} "
					+ "eulaLoginIndex={} maxAttempts={} world={} worldAddress={}",
				CLICK_X, CLICK_Y, WELCOME_LOGIN_INDEX, EULA_ENABLED, EULA_X, EULA_Y, EULA_LOGIN_INDEX,
				MAX_ATTEMPTS, WORLD_ID, WORLD_ADDRESS);
		}
	}

	private static int[] parsePoint(String prop, int defX, int defY)
	{
		int x = defX;
		int y = defY;
		final String p = System.getProperty(prop);
		if (p != null && p.contains(","))
		{
			try
			{
				final String[] parts = p.split(",", 2);
				x = Integer.parseInt(parts[0].trim());
				y = Integer.parseInt(parts[1].trim());
			}
			catch (NumberFormatException ex)
			{
				log.warn("[headless-login] bad {} '{}', using default {},{}", prop, p, defX, defY);
				x = defX;
				y = defY;
			}
		}
		return new int[]{x, y};
	}

	private HeadlessLogin()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Called once per {@code Hooks.draw} (only when enabled). Drives the login as a two-stage machine: while
	 * the EULA dialog is up (stage 1, optional) it synthesizes an Accept click; once the gamepack reaches the
	 * authenticated welcome screen (stage 2) it synthesizes a Play Now click. Each stage re-arms until the
	 * state advances.
	 */
	public static void tick(Client client, GameState state, int loginIndex)
	{
		if (!ENABLED || done)
		{
			return;
		}

		draws++;

		if (state != GameState.LOGIN_SCREEN)
		{
			// Past the login screen. OSRS may show an in-world "Welcome to RuneScape" news overlay (WelcomeScreen)
			// once LOGGED_IN that needs a click to enter the world — dismiss it before declaring done, so headless
			// harnesses actually reach the scene (not just the authenticated lobby). Keep waiting through
			// LOGGING_IN/LOADING; only finish once LOGGED_IN with no welcome overlay.
			if (state == GameState.LOGGED_IN)
			{
				if (tryDismissWelcome(client))
				{
					return;   // welcome overlay shown -> clicked it this tick, keep going until it's gone
				}
				if (eulaAttempts > 0 || playAttempts > 0)
				{
					log.info("[headless-login] state now {} after eula={} play={} attempt(s) — stopping injection",
						state, eulaAttempts, playAttempts);
				}
				done = true;
			}
			return;
		}

		if (draws < WARMUP_DRAWS)
		{
			return;
		}

		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}

		// Stage 1 — EULA Accept/Decline. Only fires on a fresh client that shows the dialog; once we observe
		// the welcome sub-state we treat the EULA as cleared and never click it again.
		if (loginIndex == WELCOME_LOGIN_INDEX)
		{
			eulaCleared = true;
		}
		if (EULA_ENABLED && !eulaCleared && loginIndex == EULA_LOGIN_INDEX)
		{
			if (eulaAttempts >= MAX_ATTEMPTS)
			{
				if (!done)
				{
					log.warn("[headless-login] still on the EULA dialog (loginIndex={}) after {} Accept attempt(s) "
						+ "at {},{} — giving up", loginIndex, eulaAttempts, EULA_X, EULA_Y);
					done = true;
				}
				return;
			}
			if (eulaAttempts > 0 && draws - lastEulaDraw < COOLDOWN_DRAWS)
			{
				return;
			}
			eulaAttempts++;
			lastEulaDraw = draws;
			log.info("[headless-login] EULA attempt {}/{}: synthesizing Accept click at {},{} (canvas {}x{} displayable={})",
				eulaAttempts, MAX_ATTEMPTS, EULA_X, EULA_Y, canvas.getWidth(), canvas.getHeight(), canvas.isDisplayable());
			synthesizeClick(canvas, EULA_X, EULA_Y);
			return;
		}

		// Stage 2 — Play Now on the authenticated welcome screen.
		if (loginIndex != WELCOME_LOGIN_INDEX)
		{
			return;
		}

		if (playAttempts >= MAX_ATTEMPTS)
		{
			if (!done)
			{
				log.warn("[headless-login] still LOGIN_SCREEN after {} Play Now attempt(s) at {},{} — giving up "
					+ "(wrong coords, or the engine rejects synthesized input headless)", playAttempts, CLICK_X, CLICK_Y);
				done = true;
			}
			return;
		}

		// Cooldown only gates re-arms — the first click fires as soon as we're warmed up on the welcome screen.
		if (playAttempts > 0 && draws - lastPlayDraw < COOLDOWN_DRAWS)
		{
			return;
		}

		// Point the client at the configured F2P world before the first click, so a free account isn't
		// bounced back to the login screen with "You need a members' account to use this world".
		if (!worldSelected && WORLD_ID > 0 && WORLD_ADDRESS != null)
		{
			selectWorld(client);
			worldSelected = true;
		}

		playAttempts++;
		lastPlayDraw = draws;
		log.info("[headless-login] Play Now attempt {}/{}: synthesizing click at {},{} (canvas {}x{} displayable={})",
			playAttempts, MAX_ATTEMPTS, CLICK_X, CLICK_Y, canvas.getWidth(), canvas.getHeight(), canvas.isDisplayable());
		synthesizeClick(canvas, CLICK_X, CLICK_Y);
	}

	private static int welcomeAttempts;
	private static int lastWelcomeDraw;
	private static boolean welcomeDone;
	// The "CLICK HERE TO PLAY" news welcome screen renders at the fixed 765x503 login canvas anchored top-left; the
	// red play button sits near (390,95) there. Configurable in case it moves.
	private static final int WELCOME_X = Integer.getInteger("runelite.headless.autologin.welcomeX", 390);
	private static final int WELCOME_Y = Integer.getInteger("runelite.headless.autologin.welcomeY", 95);

	/**
	 * Dismiss the post-login "CLICK HERE TO PLAY" news welcome screen so the harness reaches the live scene instead
	 * of stalling on the lobby. Detected by {@code getLocalPlayer() == null} while LOGGED_IN (not spawned yet);
	 * synthesizes a click at the play button. Returns {@code true} while still pre-spawn (caller keeps waiting),
	 * {@code false} once the player has spawned (in-world) or we gave up.
	 */
	private static boolean tryDismissWelcome(Client client)
	{
		if (welcomeDone)
		{
			return false;
		}
		try
		{
			// In-world once the 3D viewport is allocated (it's 0x0 while the post-login news screen is up).
			if (client.getViewportWidth() > 0)
			{
				welcomeDone = true;
				return false;
			}
			if (welcomeAttempts >= MAX_ATTEMPTS)
			{
				log.warn("[headless-login] still pre-spawn after {} welcome click(s) at {},{} — giving up",
					welcomeAttempts, WELCOME_X, WELCOME_Y);
				welcomeDone = true;
				return false;
			}
			if (welcomeAttempts > 0 && draws - lastWelcomeDraw < COOLDOWN_DRAWS)
			{
				return true;   // waiting out the cooldown between clicks
			}
			final Canvas canvas = client.getCanvas();
			if (canvas == null)
			{
				return true;
			}
			welcomeAttempts++;
			lastWelcomeDraw = draws;
			log.info("[headless-login] welcome 'Click here to play': synthesizing click at {},{} (attempt {}/{})",
				WELCOME_X, WELCOME_Y, welcomeAttempts, MAX_ATTEMPTS);
			synthesizeClick(canvas, WELCOME_X, WELCOME_Y);
			return true;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static void selectWorld(Client client)
	{
		try
		{
			final World world = client.createWorld();
			world.setId(WORLD_ID);
			world.setAddress(WORLD_ADDRESS);
			world.setActivity("-");
			world.setPlayerCount(-1);
			// F2P = no MEMBERS type. (Same World construction the WorldHopper plugin uses for changeWorld.)
			world.setTypes(EnumSet.noneOf(WorldType.class));
			client.changeWorld(world);
			log.info("[headless-login] changeWorld -> {} ({})", WORLD_ID, WORLD_ADDRESS);
		}
		catch (RuntimeException ex)
		{
			log.warn("[headless-login] changeWorld to {} ({}) failed", WORLD_ID, WORLD_ADDRESS, ex);
		}
	}

	private static void synthesizeClick(Canvas canvas, int x, int y)
	{
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();

		// Some gamepacks ignore mouse input unless the canvas believes it has focus. There is no native
		// focus owner headless, so assert it explicitly before the click.
		q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));

		// Move the pointer onto the target first so the gamepack's recorded mouse position is over the button
		// when it samples the press on the next game tick.
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));

		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3,
			0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4,
			0, x, y, 1, false, MouseEvent.BUTTON1));
	}
}
