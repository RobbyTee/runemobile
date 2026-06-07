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

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * W3 clean-native-chrome asset extractor — one-shot, DESKTOP-only. Pulls the exact tab sprite set RuneLite
 * names ({@code SpriteID}), with proper alpha, and writes each to PNG so it can be bundled into the iOS app and
 * composited natively (stone background + icon glyph) — authentic feathered tabs with no crop fragility.
 *
 * <p>Set extracted:
 * <ul>
 *   <li>{@code 1180} {@code RESIZEABLE_MODE_TAB_STONE_MIDDLE} — inactive stone background.</li>
 *   <li>{@code 1181} {@code RESIZEABLE_MODE_TAB_STONE_MIDDLE_SELECTED} — active stone background.</li>
 *   <li>{@code 774..787} — the 14 tab icon glyphs in tab-index order (combat, stats, quests, inventory,
 *       equipment, prayer, magic, clan/friends-chat, friends, ignores, logout, options, emotes, music).</li>
 * </ul>
 *
 * <p>Inert unless {@code -Drunelite.mobilechrome.spritedump=true}. Writes into the capture dir
 * ({@code runelite.headless.capture.dir}); fires once on the first {@code LOGGED_IN} frame.
 */
@Slf4j
public final class MobileChromeSpriteDump
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.mobilechrome.spritedump");

	// Stone backgrounds + the 14 tab icons (774..787), each as {spriteId, name}.
	private static final int STONE_INACTIVE = 1180;
	private static final int STONE_ACTIVE = 1181;
	private static final String[] ICON_NAMES = {
		"combat", "stats", "quests", "inventory", "equipment", "prayer", "magic",
		"clan", "friends", "ignores", "logout", "options", "emotes", "music",
	};
	private static final int ICON_BASE = 774;   // 774 + tabIndex

	// Chat tab-button backgrounds (the CONTROLS row sprites). The native chat-tab veneer scales these up so the
	// recreated buttons match the official mobile chat buttons exactly. {spriteId, name}.
	private static final int[][] CHAT_SPRITES = {
		{3051, 0}, // CHATBOX_BUTTON               — normal (idle) tab background
		{3052, 1}, // CHATBOX_BUTTON_HOVERED       — (mouse-over; unused on touch but kept for completeness)
		{3053, 2}, // CHATBOX_BUTTON_SELECTED      — active/selected tab background
		{3054, 3}, // CHATBOX_BUTTON_SELECTED_HOVERED
		{3055, 4}, // CHATBOX_BUTTON_NEW_MESSAGES  — tab with unread messages
		{3057, 5}, // CHATBOX_REPORT_BUTTON        — the far-right Report button
		{3058, 6}, // CHATBOX_REPORT_BUTTON_HOVERED
		{1018, 7}, // CHATBOX_BUTTONS_BACKGROUND_STONES — the row backing strip
	};
	private static final String[] CHAT_SPRITE_NAMES = {
		"chatbtn", "chatbtn-hover", "chatbtn-selected", "chatbtn-selected-hover",
		"chatbtn-new", "chatreport", "chatreport-hover", "chatrow-bg",
	};

	private static boolean done;

	private MobileChromeSpriteDump()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || done || client == null || state != GameState.LOGGED_IN)
		{
			return;
		}
		done = true;
		final String dir = System.getProperty("runelite.headless.capture.dir");
		if (dir == null)
		{
			log.info("[sprite-dump] no capture dir set — nothing written");
			return;
		}
		int ok = 0;
		ok += dump(client, dir, STONE_INACTIVE, "stone-inactive");
		ok += dump(client, dir, STONE_ACTIVE, "stone-active");
		for (int i = 0; i < ICON_NAMES.length; i++)
		{
			ok += dump(client, dir, ICON_BASE + i, "icon-" + i + "-" + ICON_NAMES[i]);
		}
		int chatOk = 0;
		for (int i = 0; i < CHAT_SPRITES.length; i++)
		{
			chatOk += dump(client, dir, CHAT_SPRITES[i][0], CHAT_SPRITE_NAMES[CHAT_SPRITES[i][1]]);
		}
		log.info("[sprite-dump] SPRITE_DUMP wrote={} of={} chatWrote={} of={}",
			ok, ICON_NAMES.length + 2, chatOk, CHAT_SPRITES.length);
		logChatLayout(client);
	}

	// One-shot desktop census of the 7 chat tab buttons (+ Report) and their children: canvas bounds, the
	// GRAPHIC sprite id (3051 idle / 3053 selected / 3055 new-messages), the TEXT label, and the FILTER
	// sub-label text + colour. This is the ground truth the native chat-tab veneer is laid out + coloured from.
	private static final int[] CHAT_TABS = {
		InterfaceID.Chatbox.CHAT_ALL, InterfaceID.Chatbox.CHAT_GAME, InterfaceID.Chatbox.CHAT_PUBLIC,
		InterfaceID.Chatbox.CHAT_PRIVATE, InterfaceID.Chatbox.CHAT_FRIENDSCHAT, InterfaceID.Chatbox.CHAT_CLAN,
		InterfaceID.Chatbox.CHAT_TRADE, InterfaceID.Chatbox.REPORTABUSE,
	};
	private static final String[] CHAT_TAB_LABELS = {
		"All", "Game", "Public", "Private", "Channel", "Clan", "Trade", "Report",
	};

	private static void logChatLayout(Client client)
	{
		for (int i = 0; i < CHAT_TABS.length; i++)
		{
			final boolean hasFilter = i != 0 && i != CHAT_TABS.length - 1;   // ALL + Report have no filter
			final int base = CHAT_TABS[i];
			final String b = bounds(client, base);
			final int gfxSpr = spriteId(client, base + 1);
			final String txt = text(client, base + 2);
			final String flt = hasFilter ? text(client, base + 3) : "(none)";
			final int fltCol = hasFilter ? textColor(client, base + 3) : -1;
			log.info("[sprite-dump] CHAT_LAYOUT {} btn={} gfxSpr={} text='{}' filter='{}' filterColor={}",
				CHAT_TAB_LABELS[i], b, gfxSpr, txt, flt, fltCol);
		}
	}

	private static String bounds(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				return "null";
			}
			final Rectangle r = w.getBounds();
			return r == null ? "noBounds" : ("[" + r.x + "," + r.y + "," + r.width + "x" + r.height + " hidden=" + w.isHidden() + "]");
		}
		catch (RuntimeException ex)
		{
			return "err";
		}
	}

	private static int spriteId(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return w == null ? -1 : w.getSpriteId();
		}
		catch (RuntimeException ex)
		{
			return -2;
		}
	}

	private static String text(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return w == null ? "null" : String.valueOf(w.getText());
		}
		catch (RuntimeException ex)
		{
			return "err";
		}
	}

	private static int textColor(Client c, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			return w == null ? -1 : w.getTextColor();
		}
		catch (RuntimeException ex)
		{
			return -2;
		}
	}

	private static int dump(Client client, String dir, int spriteId, String label)
	{
		try
		{
			final SpritePixels[] sp = client.getSprites(client.getIndexSprites(), spriteId, 0);
			if (sp == null || sp.length == 0 || sp[0] == null)
			{
				log.info("[sprite-dump] {} spr={} -> NONE", label, spriteId);
				return 0;
			}
			final BufferedImage img = sp[0].toBufferedImage();
			final File out = new File(dir, "tabasset-" + label + "-spr" + spriteId + ".png");
			ImageIO.write(img, "png", out);
			log.info("[sprite-dump] {} spr={} -> {}x{} {}", label, spriteId, img.getWidth(), img.getHeight(), out.getName());
			return 1;
		}
		catch (Exception ex)
		{
			log.info("[sprite-dump] {} spr={} threw: {}", label, spriteId, String.valueOf(ex));
			return 0;
		}
	}
}
