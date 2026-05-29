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
 * Gate 1c-ii spike: the minimal slice of Phase-4 input injection needed to cross the
 * {@code LOGIN_SCREEN → LOGGED_IN} boundary headless.
 *
 * <p>The {@code JX_*} launcher handoff auto-<em>authenticates</em> the account — the gamepack renders the
 * "Welcome to RuneScape / Play Now / &lt;name&gt;" welcome screen ({@code loginIndex == 10}) — but does
 * <em>not</em> auto-<em>login</em>; it parks at {@link GameState#LOGIN_SCREEN} waiting for a click on the
 * Play Now button. There is no programmatic shortcut ({@code setGameState(LOGGED_IN)} doesn't drive the
 * gamepack's network login), so we synthesize the click.
 *
 * <p>This answers a real Phase-4 kill-risk: does the window-less engine accept a synthesized AWT mouse
 * event at all (it may gate input on real focus, which has no peer headless)? We post a focus-gain plus a
 * full enter/move/press/release/click sequence to the gamepack {@link Canvas} via the toolkit
 * {@link EventQueue} — the same mechanism a UIKit-touch → AWT-{@code MouseEvent} bridge would use on iOS.
 *
 * <p>Entirely inert unless {@code -Drunelite.headless.autologin=true}. The click point defaults to the
 * Play Now button centre and is overridable with {@code -Drunelite.headless.autologin.point=x,y}. Re-arms
 * up to {@code maxAttempts} times if the state hasn't advanced, so a coordinate miss is visible (the
 * captured frames + the attempt log tell us whether the click landed).
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

	private static final int CLICK_X;
	private static final int CLICK_Y;

	// Optional: switch to a specific world before clicking Play Now. A throwaway free-to-play account is
	// rejected ("You need a members' account to use this world") if the remembered world is members, so we
	// point it at an F2P world first. World W's host is oldschool{W-300}.runescape.com.
	private static final int WORLD_ID = Integer.getInteger("runelite.headless.autologin.world", 0);
	private static final String WORLD_ADDRESS = System.getProperty("runelite.headless.autologin.worldAddress");

	private static int draws;
	private static int attempts;
	private static int lastAttemptDraw;
	private static boolean worldSelected;
	private static boolean done;

	static
	{
		int x = Constants.GAME_FIXED_WIDTH / 2;   // 382 — Play Now is horizontally centred
		int y = 248;                              // ~middle of the welcome panel (765x503 fixed login)
		final String p = System.getProperty("runelite.headless.autologin.point");
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
				log.warn("[headless-login] bad autologin.point '{}', using default {},{}", p, x, y);
			}
		}
		CLICK_X = x;
		CLICK_Y = y;
		if (ENABLED)
		{
			log.info("[headless-login] ENABLED clickPoint={},{} welcomeLoginIndex={} maxAttempts={} world={} worldAddress={}",
				CLICK_X, CLICK_Y, WELCOME_LOGIN_INDEX, MAX_ATTEMPTS, WORLD_ID, WORLD_ADDRESS);
		}
	}

	private HeadlessLogin()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Called once per {@code Hooks.draw} (only when enabled). Synthesizes a Play Now click when the client
	 * is parked on the authenticated welcome screen, and re-arms until the state advances.
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
			// Advanced past the login screen — the click (or the engine) carried us off it. Stop trying.
			if (attempts > 0)
			{
				log.info("[headless-login] state now {} after {} attempt(s) — stopping injection", state, attempts);
			}
			done = true;
			return;
		}

		if (loginIndex != WELCOME_LOGIN_INDEX || draws < WARMUP_DRAWS)
		{
			return;
		}

		if (attempts >= MAX_ATTEMPTS)
		{
			if (!done)
			{
				log.warn("[headless-login] still LOGIN_SCREEN after {} click attempt(s) at {},{} — giving up "
					+ "(wrong coords, or the engine rejects synthesized input headless)", attempts, CLICK_X, CLICK_Y);
				done = true;
			}
			return;
		}

		// Cooldown only gates re-arms — the first click fires as soon as we're warmed up on the welcome screen.
		if (attempts > 0 && draws - lastAttemptDraw < COOLDOWN_DRAWS)
		{
			return;
		}

		final Canvas canvas = client.getCanvas();
		if (canvas == null)
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

		attempts++;
		lastAttemptDraw = draws;
		log.info("[headless-login] attempt {}/{}: synthesizing Play Now click at {},{} (canvas {}x{} displayable={})",
			attempts, MAX_ATTEMPTS, CLICK_X, CLICK_Y, canvas.getWidth(), canvas.getHeight(), canvas.isDisplayable());
		clickPlayNow(canvas);
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

	private static void clickPlayNow(Canvas canvas)
	{
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();

		// Some gamepacks ignore mouse input unless the canvas believes it has focus. There is no native
		// focus owner headless, so assert it explicitly before the click.
		q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));

		// Move the pointer onto the button first so the gamepack's recorded mouse position is over Play Now
		// when it samples the press on the next game tick.
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, CLICK_X, CLICK_Y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, CLICK_X, CLICK_Y, 0, false));

		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, CLICK_X, CLICK_Y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3,
			0, CLICK_X, CLICK_Y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4,
			0, CLICK_X, CLICK_Y, 1, false, MouseEvent.BUTTON1));
	}
}
