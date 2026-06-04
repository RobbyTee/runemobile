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
 * W3 Feature-A (A3) — controls the engine chatbox so the chat-at-top look matches the OSRS Mobile reference.
 * A desktop probe ({@code ChatRelocateProbe}) proved the engine's own chatbox can be {@code setForcedPosition}'d
 * to the top and STILL renders there (not occluded by the 3D viewport — unlike tab stones). So instead of
 * reproducing the chat natively, we relocate the engine's pixel-perfect chat (tabs / scrollback / colours /
 * input all stay live, since it IS the real chat) and flip it to OSRS transparent-chat mode.
 *
 * <p>The desired state is set from the native shell (reverse JNI) and re-applied each frame on the client thread
 * (the engine rebuilds the toplevel on {@code TOPLEVEL_REDRAW}). Two modes: {@link #relocateChat} (move the
 * chatbox + optional transparency) or {@link #setChatHidden} (hide it outright). Inert unless
 * {@code -Drunelite.hudveneer=true}.
 */
@Slf4j
public final class HudVeneerBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.hudveneer");

	// Desired state, set from native, applied on the client thread. chatX < 0 = not relocating.
	private static volatile int chatX = -1;
	private static volatile int chatY = -1;
	private static volatile boolean transparentChat;
	private static volatile boolean hideChat;

	private HudVeneerBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Native: move the engine chatbox to {@code (x,y)} (top-left of the screen) and optionally flip it to OSRS
	 *  transparent-chat mode. {@code x < 0} stops relocating. */
	public static void relocateChat(int x, int y, boolean transparent)
	{
		chatX = x;
		chatY = y;
		transparentChat = transparent;
	}

	/** Native: hide the engine chatbox outright (used when the native veneer fully replaces it). */
	public static void setChatHidden(boolean hidden)
	{
		hideChat = hidden;
	}

	/** Re-apply the requested chatbox state each frame. Client thread. */
	public static void tick(Client c)
	{
		if (!ENABLED || c == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		try
		{
			if (!c.isResized())
			{
				return;   // native veneers + transparent chat are resizable-mode only
			}
			final boolean preEoc = c.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			final int chatId = preEoc ? InterfaceID.ToplevelPreEoc.CHAT_CONTAINER : InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;

			if (chatX >= 0)
			{
				final Widget w = c.getWidget(chatId);
				if (w != null)
				{
					w.setForcedPosition(chatX, chatY);
					w.revalidate();
				}
				// OSRS transparent-chat mode (renders the chat without the opaque brown box) to match the
				// mobile reference. Set only when it differs so we don't spam varbit-changed each frame.
				if (transparentChat && c.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) != 1)
				{
					c.setVarbit(VarbitID.CHATBOX_TRANSPARENCY, 1);
				}
			}
			else if (hideChat)
			{
				final Widget w = c.getWidget(chatId);
				if (w != null && !w.isHidden())
				{
					w.setHidden(true);
				}
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}
}
