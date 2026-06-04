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

	private static final int CONTROLS_H = 23;   // the chat tab-row (CONTROLS) height, from the layout dump

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
				// OSRS-Mobile tabs-on-top: the desktop chat stacks the tab row (CONTROLS, 23px) UNDER the message
				// area (CHATAREA, 142px). Flip it — force the tab row to the chat's top and push the messages down
				// by the tab height — so the layout reads tabs -> messages -> input like the mobile reference.
				forcePos(c, InterfaceID.Chatbox.CONTROLS, chatX, chatY);
				forcePos(c, InterfaceID.Chatbox.CHATAREA, chatX, chatY + CONTROLS_H);
				if (transparentChat)
				{
					// Deterministic transparency: hide the brown chatbox background sprites directly (the
					// CHATBOX_TRANSPARENCY varbit alone doesn't re-render an already-built chat). Re-applied each
					// frame. The text + tab buttons stay; only the opaque box behind them goes.
					hide(c, InterfaceID.Chatbox.CHAT_BACKGROUND);
					hide(c, InterfaceID.Chatbox.CONTROLS_BACKGROUND);
					hide(c, InterfaceID.Chatbox.CONTROLS_BACKGROUND_GRAPHIC);
					if (c.getVarbitValue(VarbitID.CHATBOX_TRANSPARENCY) != 1)
					{
						c.setVarbit(VarbitID.CHATBOX_TRANSPARENCY, 1);
					}
				}
				dumpChatLayoutOnce(c);
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

	private static void hide(Client c, int id)
	{
		try
		{
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

	private static void forcePos(Client c, int id, int x, int y)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w != null)
			{
				w.setForcedPosition(x, y);
				w.revalidate();
			}
		}
		catch (RuntimeException ignore)
		{
		}
	}

	// One-time dump of the chatbox sub-widget layout, so the native side can plan moving the tab row (CONTROLS)
	// above the message area (CHATAREA/CHATDISPLAY) to match the OSRS-Mobile tabs-on-top layout.
	private static boolean dumped;

	private static void dumpChatLayoutOnce(Client c)
	{
		if (dumped)
		{
			return;
		}
		dumped = true;
		dumpOne(c, "CONTROLS", InterfaceID.Chatbox.CONTROLS);
		dumpOne(c, "CHATAREA", InterfaceID.Chatbox.CHATAREA);
		dumpOne(c, "CHATDISPLAY", InterfaceID.Chatbox.CHATDISPLAY);
		dumpOne(c, "CHAT_BACKGROUND", InterfaceID.Chatbox.CHAT_BACKGROUND);
		dumpOne(c, "INPUT_CLICKAREA", InterfaceID.Chatbox.INPUT_CLICKAREA);
		dumpOne(c, "MES_LAYER", InterfaceID.Chatbox.MES_LAYER);
		dumpOne(c, "SCROLLAREA", InterfaceID.Chatbox.SCROLLAREA);
		dumpOne(c, "CHAT_ALL", InterfaceID.Chatbox.CHAT_ALL);
	}

	private static void dumpOne(Client c, String name, int id)
	{
		try
		{
			final Widget w = c.getWidget(id);
			if (w == null)
			{
				log.info("[veneer] chat-layout {} = null", name);
				return;
			}
			final java.awt.Rectangle b = w.getBounds();
			log.info("[veneer] chat-layout {} bounds={} orig=({},{},{}x{}) relX={} relY={} hidden={}",
				name, b, w.getOriginalX(), w.getOriginalY(), w.getOriginalWidth(), w.getOriginalHeight(),
				w.getRelativeX(), w.getRelativeY(), w.isHidden());
		}
		catch (RuntimeException ex)
		{
			log.info("[veneer] chat-layout {} threw {}", name, String.valueOf(ex));
		}
	}
}
