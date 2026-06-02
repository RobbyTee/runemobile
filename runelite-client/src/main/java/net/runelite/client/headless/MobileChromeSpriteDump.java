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

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;

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
		log.info("[sprite-dump] SPRITE_DUMP wrote={} of={}", ok, ICON_NAMES.length + 2);
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
