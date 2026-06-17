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

import java.awt.Graphics;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MainBufferProvider;

/**
 * Single dispatch point for the fork's per-frame headless seams, invoked from
 * {@link net.runelite.client.callback.Hooks}. The fork wires ~30 headless bridges + dev probes into the
 * engine's draw cycle; keeping the whole chain here (instead of woven through {@code Hooks.draw}/{@code
 * drawScene}) shrinks the upstream-merge conflict surface in {@code Hooks} to three one-line calls — the
 * central friction point for tracking unmodified upstream RuneLite.
 *
 * <p>This is a behaviour-preserving relocation: every call, its guard, and the call order are identical to
 * the previous inline form (including the deliberate twice-per-frame {@code RouteCGpuStarter}/{@code
 * RLHDStarter} ticks — once pre-composite to flip {@code isGpu} on, once post-composite). All methods run on
 * the client (engine) thread. Being in the {@code headless} package, the bridges/probes are referenced
 * unqualified. The HUD pre-render seam is NOT here — it is a last-priority {@code BeforeRender} EventBus
 * subscriber registered in {@link net.runelite.client.ui.HeadlessClientUI} (see
 * {@link HudVeneerBridge.BeforeRenderListener}), so {@code Hooks.frame()} needs no fork edit at all.
 *
 * <p>Both the iOS and Android ports embed this jar and call the same bridge facades; they depend on these
 * ticks running once per frame in this order before the frame is published, not on where the calls live.
 */
public final class HeadlessSeams
{
	private HeadlessSeams()
	{
	}

	/**
	 * Pre-composite seam: the very top of {@code Hooks.draw}, before the {@code graphics == null} guard and
	 * before overlays composite.
	 */
	public static void draw(Client client, Graphics graphics)
	{
		HeadlessCapture.onDraw(graphics);
		if (HeadlessCapture.isEnabled())
		{
			HeadlessCapture.observe(
				client.getGameState(), client.getLoginIndex(), client.getLauncherDisplayName());
		}
		if (JavaBridge.isEnabled())
		{
			// Keep the native shell's touch bridge pointed at the current client (the frame snapshot is
			// taken post-composite, below).
			JavaBridge.register(client);
			// iOS load-in flash guard: re-arm the publish hold whenever we're not in a continuously-rendered
			// world — the login screen, world hopping, connection lost, etc. We deliberately do NOT re-arm on
			// plain LOADING, since that also fires during seamless in-play region streaming while running (where
			// the scene keeps rendering and there's no sky flash to hide). drawScene() clears it back to true
			// once geometry is rendered. See JavaBridge.sceneDrawn.
			GameState gs = client.getGameState();
			if (gs != GameState.LOGGED_IN && gs != GameState.LOADING)
			{
				JavaBridge.armSceneHold();
			}
		}
		if (MobileUiBridge.isEnabled())
		{
			// Snapshot UI-state the native chrome polls (chat-input bounds / soft-input) on the client thread.
			MobileUiBridge.tick(client);
		}
		if (HeadlessLogin.isEnabled())
		{
			HeadlessLogin.tick(client, client.getGameState(), client.getLoginIndex());
		}
		if (WelcomePlayProbe.isEnabled())
		{
			// GPU Route C: dismiss the post-login "Welcome to Gielinor / Click here to play" screen so the engine
			// actually enters the world render (without this, headless parks on the welcome screen and no scene
			// renders — in CPU or GPU mode). Gated -Drunelite.headless.welcomeplay=true.
			WelcomePlayProbe.tick(client, client.getGameState());
		}
		if (HeadlessResize.isEnabled())
		{
			HeadlessResize.tick(client, client.getGameState());
		}
		if (GpuHandshakeProbe.isEnabled())
		{
			// GPU Route C DESKTOP reproduction: replay GpuPlugin.startUp's engine handshake (setGpuFlags(GPU|ZBUF)
			// + a no-GL counting DrawCallbacks) with NO GL context, so a desktop CACIO run can test whether the
			// engine dispatches the per-frame GPU scene render headless (the device black-scene blocker). Mutates
			// client state — DESKTOP ONLY, never the device path. Runs before GpuSceneDiag so the diag sees it.
			// Gated -Drunelite.gpuhandshake=true.
			GpuHandshakeProbe.tick(client);
		}
		if (GpuSceneDiag.isEnabled())
		{
			// GPU Route C: read-only per-frame snapshot (isGpu / registered DrawCallbacks + classloader /
			// viewport / drawScene count) to localise the black-3D-scene blocker above the GL bridge. Runs
			// before the isGpu early-return below so it ticks in GPU mode. Gated -Drunelite.gpudiag=true.
			GpuSceneDiag.tick(client);
		}
		if (RouteCGpuStarter.isEnabled())
		{
			// GPU Route C: force-enable + start the gpu plugin once in-world (canvas valid), overriding a persisted
			// gpuplugin=false and the startUp-too-early canvas race so the plugin reliably creates its GL context
			// instead of silently falling back to the CPU renderer. Runs before the isGpu early-return.
			// Gated -Drunelite.routecgpu=true.
			RouteCGpuStarter.tick(client);
		}
		if (RLHDStarter.isEnabled())
		{
			// W3 GPU Zone B0.5: load + start the EXTERNAL 117HD plugin (rs117.hd.HdPlugin, in the staged rlhd jar)
			// once there's a drawing canvas, then force the canvas valid until it activates. HdPlugin conflicts=GPU
			// so it auto-disables the base gpu plugin (do NOT also arm RouteCGpuStarter in this mode).
			// Gated -Drunelite.routecrlhd=true.
			RLHDStarter.tick(client);
		}
		if (MobileToplevelProbe.isEnabled())
		{
			// W3 mobile-layout spike: one-shot, on the client thread, gated by -Drunelite.mobileui.toplevel=osm.
			MobileToplevelProbe.tick(client, client.getGameState());
		}
		if (WorldMapProbe.isEnabled())
		{
			// World-map mobile de-risk (WORLDMAP-MOBILE-PLAN.md Phase 0): drag-pan / wheel-zoom / search /
			// relocate / close, all headless. Gated -Drunelite.worldmapprobe=true.
			WorldMapProbe.tick(client, client.getGameState());
		}
		if (WorldMapOrbsFix.isEnabled())
		{
			// SC-9 groundwork (DORMANT): accounts with "Data orbs" OFF (ORBS_DISABLED) can't open the world map —
			// the orb's server if_button is discarded because the server never opened the orbs interface. Enabling
			// Data orbs via the settings UI is the fix, but auto-opening that UI from the shared core is blocked in
			// the mobile chrome (see WorldMapOrbsFix). Gated -Drunelite.orbsfix=true (NOT set by the app), so this
			// is a no-op unless armed; inert on desktop.
			WorldMapOrbsFix.tick(client, client.getGameState());
		}
		if (MobileChromeBridge.isEnabled())
		{
			// W3 hybrid-chrome read surface: snapshot desktop tab/panel/minimap/orb/chat/viewport geometry +
			// active-tab varc on the client thread for the native chrome. Read-only — mutates nothing.
			MobileChromeBridge.tick(client);
		}
		if (HudDataBridge.isEnabled())
		{
			// W3 Feature-A phase A2: read-only HUD DATA snapshot (chat lines / orb values / minimap positions)
			// the native HUD veneers reproduce. Client thread; mutates nothing. Gated -Drunelite.huddata=true.
			HudDataBridge.tick(client);
		}
		if (HudVeneerBridge.isEnabled())
		{
			// W3 Feature-A phase A3: suppress the engine HUD elements the native veneers replace (chatbox),
			// re-applied each frame. State set from native via setChatHidden. Gated -Drunelite.hudveneer=true.
			HudVeneerBridge.tick(client);
		}
		if (MobileChromeProbe.isEnabled())
		{
			// W3 hybrid-chrome de-risk: one-shot DESKTOP experiment (synth-click vs setVarcIntValue tab switch),
			// gated by -Drunelite.mobilechrome.probe=true. Mutates — never on the device launch path.
			MobileChromeProbe.tick(client, client.getGameState());
		}
		if (MobileChromeReposProbe.isEnabled())
		{
			// W3 authentic mobile layout: reposition the real desktop tab stones into vertical edge columns
			// (engine draws them — feathered, no native crop). Mutates widget positions; gated
			// -Drunelite.mobilechrome.repos=true. Desktop de-risk first.
			MobileChromeReposProbe.tick(client, client.getGameState());
		}
		if (MobileChromeSpriteDump.isEnabled())
		{
			// W3 clean-native-chrome: one-shot DESKTOP extractor for the tab sprite assets (stones 1180/1181 +
			// icons 774-787) -> PNG, to bundle + composite natively. Gated -Drunelite.mobilechrome.spritedump=true.
			MobileChromeSpriteDump.tick(client, client.getGameState());
		}
		if (MobileChromePanelProbe.isEnabled())
		{
			// W3 diagnostic: can the docked side panel be moved? Gated -Drunelite.mobilechrome.panelprobe=true.
			MobileChromePanelProbe.tick(client, client.getGameState());
		}
		if (MobileChromeHideTest.isEnabled())
		{
			// W3 diagnostic: do the stones hide cleanly + does setVarc switch the panel?
			// Gated -Drunelite.mobilechrome.hidetest=true.
			MobileChromeHideTest.tick(client, client.getGameState());
		}
		if (HudIsolationTest.isEnabled())
		{
			// W3 Feature-A phase A0: de-risk HUD suppression (mask vs setHidden vs transparent) AND route-back
			// click-through, on the client thread. Mutates widget state; gated -Drunelite.hudisolation=true.
			HudIsolationTest.tick(client, client.getGameState());
		}
		if (EdgeToEdgeProbe.isEnabled())
		{
			// W3 Feature-A phase A1 (desktop): hide the WHOLE engine 2D HUD at a wide aspect + prove the scene
			// fills the canvas edge-to-edge. Mutates widget state; gated -Drunelite.edge2edge=true.
			EdgeToEdgeProbe.tick(client, client.getGameState());
		}
		if (MinimapWalkProbe.isEnabled())
		{
			// W3 Feature-A: the hard route-back — does clicking the LIVE engine minimap walk (so a native minimap
			// covers it) vs hidden (dead)? Mutates (clicks/hides); gated -Drunelite.minimapwalk=true.
			MinimapWalkProbe.tick(client, client.getGameState());
		}
		if (ChatRelocateProbe.isEnabled())
		{
			// W3 Feature-A (A3): can the engine chatbox be RELOCATED to the top (exact OSRS skin) vs occluded?
			// Mutates widget position; gated -Drunelite.chatreloc=true. Desktop de-risk only.
			ChatRelocateProbe.tick(client, client.getGameState());
		}
		if (MinimapRelocateProbe.isEnabled())
		{
			// W3 Feature-A (A4): can the engine minimap be RELOCATED (inset) and still render + click-to-walk (the
			// cheap A3-style path) vs cover-in-place native redraw? Mutates position + clicks; gated
			// -Drunelite.minimapreloc=true. Desktop de-risk only.
			MinimapRelocateProbe.tick(client, client.getGameState());
		}
		if (TabScriptProbe.isEnabled())
		{
			// W3 Feature-A (A4): can a tab be opened by REPLAYING the stone's op script (so the stones can be
			// hidden + the gray cover removed)? Mutates tab state + hides stones; gated -Drunelite.tabscript=true.
			TabScriptProbe.tick(client, client.getGameState());
		}
		if (StoneHideProbe.isEnabled())
		{
			// W3 Feature-A (A4): setHidden vs off-screen forced-position — which actually removes the tab stones?
			// Mutates positions/hidden; gated -Drunelite.stonehide=true. Desktop de-risk only.
			StoneHideProbe.tick(client, client.getGameState());
		}
		if (StoneSuppressProbe.isEnabled())
		{
			// W3 Feature-A (A4): steady-state test of parent/grandparent hide+relocate to remove the re-pinned tab
			// row. Mutates widget state; gated -Drunelite.stonesuppress=true. Desktop de-risk only.
			StoneSuppressProbe.tick(client, client.getGameState());
		}
		if (MenuPeekBridge.isEnabled())
		{
			// W3 touch-targeting: publish the read-only menu-peek snapshot the native press-and-peek UI polls
			// (the menu the engine computed for the finger the native side streamed via JavaBridge.onMove).
			// Read-only; gated -Drunelite.menupeek=true. Runs BEFORE the probe so the probe can cross-check it.
			MenuPeekBridge.tick(client);
		}
		if (JavaBridge.isEnabled())
		{
			// W3 touch-targeting: advance the engine-resolved tap state machine (settle gate -> click ->
			// truth classification). Client thread, after the engine tick rebuilt the menu — the same settle
			// seam MenuPeekBridge reads from. Gated -Drunelite.bridge=true (inert on desktop).
			JavaBridge.tapTick(client);
		}
		if (MenuPeek.isEnabled())
		{
			// Touch-targeting de-risk PROBE: prove the press-and-peek primitive (synth MOUSE_MOVED -> read
			// getMenuEntries() a cycle later) + cross-check MenuPeekBridge's published top. Read-only; one-shot;
			// gated -Drunelite.menupeek.probe=true. Desktop-only diagnostic (never the device path).
			MenuPeek.tick(client, client.getGameState());
		}
		if (ShiftFeedSpike.isEnabled())
		{
			// Mobile-UX audit OQ1/#9: does a synthetic held KEY_PRESSED VK_SHIFT flip client.isKeyPressed(KC_SHIFT)
			// (the "Sticky Shift" feasibility gate)? Mutates input only; gated -Drunelite.shiftfeed=true. Desktop only.
			ShiftFeedSpike.tick(client, client.getGameState());
		}
	}

	/**
	 * Post-composite seam: after the ALWAYS_ON_TOP overlay + clientUI overlays composite into
	 * {@code mainBufferProvider}, before the {@code client.isGpu()} early-return.
	 */
	public static void postComposite(Client client, MainBufferProvider mainBufferProvider)
	{
		// GPU Route C (AG2): force-enable + start the base gpu plugin once in-world, overriding a persisted
		// gpuplugin=false and forcing the canvas valid so startUp's createGLContext fires. Inert (no-op) unless
		// -Drunelite.routecgpu=true; runs before the isGpu early-return because it is what flips isGpu on.
		if (RouteCGpuStarter.isEnabled())
		{
			RouteCGpuStarter.tick(client);
		}

		// Track G AG3-b: load + start the 117HD (RLHD) plugin (Zone) once in-world, overriding the base gpu plugin
		// (HdPlugin declares conflicts="GPU", so startPlugin disables it). Inert unless -Drunelite.routecrlhd=true.
		if (RLHDStarter.isEnabled())
		{
			RLHDStarter.tick(client);
		}

		// iOS "renderers as plugins": surface the GPU + 117HD plugins in the native panel (loaded, not started)
		// so they're selectable + configurable regardless of the active renderer. Inert unless
		// -Drunelite.rendererpanel=true; the active renderer is still started by the starters above.
		if (RendererPanelStarter.isEnabled())
		{
			RendererPanelStarter.tick(client);
		}

		// W3 touch-targeting: draw the tap selection feedback (tight entity model outline) into the frame, on
		// top of the scene/overlays, just before it's captured/blitted.
		if (JavaBridge.isEnabled())
		{
			JavaBridge.drawSelection(client);
		}

		// Headless capture seam: composited game + overlays now live in mainBufferProvider.getImage().
		// In GPU mode (Route C) mainBufferProvider is the UI-only layer (the game is on the GPU), so a CPU
		// capture here is both useless AND a per-frame ~1.5 MB BufferedImage allocation that OOMs the heap once
		// 117HD's ZoneRenderer is resident — gate it off, same as the JavaBridge snapshot below.
		if (!client.isGpu())
		{
			HeadlessCapture.capture(mainBufferProvider);
		}
		// Live-blit seam: snapshot the now-complete frame for the native shell (avoids mid-composite tearing).
		// In GPU mode (Route C) mainBufferProvider holds only the UI layer; the authoritative composited frame
		// is published later by the gpu plugin's swapBuffers shim, which is then the SOLE frameSeq publisher.
		// Gating the CPU snapshot off here prevents the UI-only frame from flashing between GPU frames.
		if (JavaBridge.isEnabled() && !client.isGpu())
		{
			JavaBridge.snapshot(mainBufferProvider);
		}
	}

	/**
	 * Scene seam: the top of {@code Hooks.drawScene}, before the ABOVE_SCENE overlay renders.
	 */
	public static void drawScene(Client client)
	{
		if (GpuSceneDiag.isEnabled())
		{
			// GPU Route C: count the per-frame scene-render callback (fires at the end of the injected scene
			// render `ev.dk`, both zone and legacy branches) to tell whether the engine drives the 3D scene
			// at all in GPU mode. Read-only; gated -Drunelite.gpudiag=true.
			GpuSceneDiag.onDrawScene();
		}

		// iOS load-in flash guard: count this 3D scene render. The GPU publish stays held until a few of these
		// have accumulated (zones still upload for a few frames after the first draw, so the first frames are bare
		// sky) — re-armed to 0 on non-world game states in draw(). See JavaBridge.sceneDrawCount.
		if (JavaBridge.isEnabled())
		{
			JavaBridge.noteSceneDraw();
		}
	}
}
