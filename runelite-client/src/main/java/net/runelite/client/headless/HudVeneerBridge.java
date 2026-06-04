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
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * W3 Feature-A — the mutating sibling of the read-only {@link HudDataBridge}: it SUPPRESSES the engine HUD
 * elements that the native veneers replace. A0 proved {@code setHidden} is the only clean remover but must be
 * re-applied (the engine rebuilds the toplevel on {@code TOPLEVEL_REDRAW}); this re-applies the requested hides
 * each frame on the client thread.
 *
 * <p>For A3 the only element hidden is the <b>chatbox</b> (the native chat-at-top veneer replaces it, fully
 * native — chat needs no engine route-back). The minimap and orbs are NOT hidden: A0/minimap-walk showed they
 * are cover-in-place (kept live + covered + proxied), so they stay rendered. The desired state is set from the
 * native shell via {@link #setChatHidden(boolean)}.
 *
 * <p>Inert unless {@code -Drunelite.hudveneer=true}. Mutates only the requested containers; never reads back to
 * native.
 */
@Slf4j
public final class HudVeneerBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.hudveneer");

	// Desired suppression state, set from the native shell (reverse JNI), applied on the client thread.
	private static volatile boolean hideChat;

	private HudVeneerBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Native shell requests the engine chatbox be hidden (the native chat veneer is up) or shown. */
	public static void setChatHidden(boolean hidden)
	{
		hideChat = hidden;
	}

	public static boolean isChatHidden()
	{
		return hideChat;
	}

	/** Re-apply the requested hides each frame (the engine un-hides on toplevel rebuild). Client thread. */
	public static void tick(Client c)
	{
		if (!ENABLED || c == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!hideChat)
		{
			return;
		}
		try
		{
			if (!c.isResized())
			{
				return;   // native veneers target resizable mode
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int id = preEoc ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;
			final Widget w = c.getWidget(id);
			if (w != null && !w.isHidden())
			{
				w.setHidden(true);
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}
}
