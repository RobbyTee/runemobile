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
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;

/**
 * W3 touch-targeting: the <b>read-only</b> menu-peek surface the native press-and-peek UI polls each display
 * tick. It is the productization of the {@link MenuPeek} de-risk probe (which proved the primitive GREEN on
 * desktop): the engine rebuilds its menu every client cycle from the current pointer position, and the
 * <em>last</em> entry of {@link Client#getMenuEntries()} is the top/left-click action (see
 * {@code MenuOpened.getFirstEntry()} = {@code menuEntries[length-1]}). So while the native side streams the
 * finger position through {@code JavaBridge.onMove} (a plain {@code MOUSE_MOVED}), this bridge reads the menu
 * the engine computed for that point and publishes it for the native reticle/chip.
 *
 * <p>Thread model mirrors {@link MobileChromeBridge}: {@link #tick(Client)} runs on the client thread (from
 * {@code Hooks.draw}), where {@code getMenuEntries()} is safe to read, and publishes an escaped JSON snapshot
 * into a volatile; the native JNI thread reads {@link #getSnapshot()} lock-free. Entirely inert unless
 * {@code -Drunelite.menupeek=true}.
 *
 * <p><b>This class never invokes anything.</b> It only reads. The native side commits a chosen action with a
 * <em>real</em> click (the existing {@code JavaBridge.onClickPrecise} at the release point for the top entry,
 * or the existing engine right-click menu for disambiguation) so RuneLite custom {@code onClick} callbacks and
 * {@code worldViewId} are honoured — {@code client.menuAction} alone would drop both. The snapshot carries the
 * full entry fields (option/target/type/id/param0/param1/itemId/worldViewId/deprioritized/forceLeftClick) so a
 * native chooser can be built later, plus the pointer position + a publish sequence so the native side can
 * confirm a release-time read is fresh and corresponds to the lift point (release-revalidation — the case the
 * probe showed matters for moving targets).
 *
 * <p>Entries are emitted in <b>display order</b> (index 0 = top = the array tail), so {@code entries[0]} is the
 * left-click action.
 */
@Slf4j
public final class MenuPeekBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.menupeek");

	// How many entries (from the display top down) to publish — enough for a chip + a native chooser.
	private static final int MAX_ENTRIES = Integer.getInteger("runelite.menupeek.maxEntries", 10);

	// Published lock-free: the native JNI thread reads these; the client thread writes them in tick().
	private static volatile String snapshotJson = "{\"active\":false}";
	private static volatile String topFingerprint = "EMPTY";
	private static volatile long publishSeq;

	private MenuPeekBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Build the menu-peek snapshot for this frame on the client thread. Reads the menu the engine computed for
	 * the current pointer position (which the native side moved via {@code JavaBridge.onMove}) and publishes it.
	 * Touches nothing — pure read.
	 */
	public static void tick(Client client)
	{
		if (!ENABLED || client == null)
		{
			return;
		}

		try
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				topFingerprint = "EMPTY";
				snapshotJson = "{\"active\":true,\"loggedIn\":false}";
				publishSeq++;
				return;
			}

			final MenuEntry[] e = client.getMenuEntries();
			final int count = e == null ? 0 : e.length;
			final MenuEntry top = count > 0 ? e[count - 1] : null;   // display top = array tail
			topFingerprint = fingerprint(top);

			final Point m = safeMouse(client);
			final boolean menuOpen = safeBool(client::isMenuOpen);
			final long seq = publishSeq + 1;

			final StringBuilder sb = new StringBuilder(512);
			sb.append("{\"active\":true,\"loggedIn\":true")
				.append(",\"seq\":").append(seq)
				.append(",\"mouseX\":").append(m == null ? -1 : m.getX())
				.append(",\"mouseY\":").append(m == null ? -1 : m.getY())
				.append(",\"menuOpen\":").append(menuOpen)
				.append(",\"menuScrollable\":").append(safeBool(client::isMenuScrollable))
				.append(",\"menuX\":").append(safeInt(client::getMenuX))
				.append(",\"menuY\":").append(safeInt(client::getMenuY))
				.append(",\"menuW\":").append(safeInt(client::getMenuWidth))
				.append(",\"menuH\":").append(safeInt(client::getMenuHeight))
				.append(",\"count\":").append(count)
				.append(",\"entries\":[");

			// Display order: tail first (entries[0] = top = left-click action).
			final int n = Math.min(MAX_ENTRIES, count);
			for (int k = 0; k < n; k++)
			{
				final MenuEntry me = e[count - 1 - k];
				if (k > 0)
				{
					sb.append(',');
				}
				appendEntry(sb, me);
			}
			sb.append("]}");

			snapshotJson = sb.toString();
			publishSeq = seq;   // publish the seq LAST so a native reader that sees it also sees this JSON
		}
		catch (RuntimeException ex)
		{
			// Never let a read hiccup disturb the frame; keep the last good snapshot.
			log.debug("[menupeek-bridge] tick read failed", ex);
		}
	}

	/** Latest menu-peek snapshot as escaped JSON (native reads this each display tick). */
	public static String getSnapshot()
	{
		return snapshotJson;
	}

	/**
	 * Fingerprint of the current top (left-click) entry — {@code option|target|type|id|p0|p1|itemId} with tags
	 * stripped. The native side caches this to detect whether the top action changed between the press preview
	 * and release (release-revalidation), and the {@link MenuPeek} probe cross-checks it against ground truth.
	 */
	public static String getTopFingerprint()
	{
		return topFingerprint;
	}

	/** Monotonic counter bumped once per published snapshot (native freshness check). */
	public static long getPublishSeq()
	{
		return publishSeq;
	}

	// ---- internals -------------------------------------------------------------------------------------

	private static void appendEntry(StringBuilder sb, MenuEntry e)
	{
		sb.append("{\"opt\":\"").append(jsonEsc(e.getOption()))
			.append("\",\"tgt\":\"").append(jsonEsc(stripTags(e.getTarget())))
			.append("\",\"type\":\"").append(jsonEsc(String.valueOf(e.getType())))
			.append("\",\"id\":").append(e.getIdentifier())
			.append(",\"p0\":").append(e.getParam0())
			.append(",\"p1\":").append(e.getParam1())
			.append(",\"item\":").append(e.getItemId())
			.append(",\"wv\":").append(e.getWorldViewId())
			.append(",\"deprio\":").append(e.isDeprioritized())
			.append(",\"force\":").append(e.isForceLeftClick())
			.append('}');
	}

	/** Same format as {@link MenuPeek}'s fingerprint, so the probe's cross-check is a plain string compare. */
	static String fingerprint(MenuEntry e)
	{
		if (e == null)
		{
			return "EMPTY";
		}
		return e.getOption() + "|" + stripTags(e.getTarget()) + "|" + e.getType()
			+ "|" + e.getIdentifier() + "|" + e.getParam0() + "|" + e.getParam1() + "|" + e.getItemId();
	}

	private static Point safeMouse(Client c)
	{
		try
		{
			return c.getMouseCanvasPosition();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static String stripTags(String s)
	{
		return s == null ? "" : s.replaceAll("<[^>]*>", "");
	}

	private static String jsonEsc(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		final StringBuilder b = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			switch (c)
			{
				case '"':
					b.append("\\\"");
					break;
				case '\\':
					b.append("\\\\");
					break;
				case '\n':
				case '\r':
				case '\t':
					b.append(' ');
					break;
				default:
					b.append(c);
					break;
			}
		}
		return b.toString();
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
}
