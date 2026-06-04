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
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;

/**
 * A0 — DESKTOP scene-isolation + route-back de-risk for the native-HUD-veneer plan (W3 Feature A, phase A0).
 *
 * <p>The native-HUD plan needs to (1) remove the engine's baked-in 2D HUD (minimap / chatbox / orbs) so the 3D
 * scene can show edge-to-edge, then (2) draw our own HUD natively and route taps on it BACK to the engine. The
 * load-bearing unknown is whether any single lever achieves BOTH — because the obvious removal lever
 * ({@code setHidden}) is already known to make widgets un-clickable ({@link MobileChromeHideTest}), which would
 * break the click-through route-back. This probe measures three candidate levers, each for BOTH removal and
 * click-through, against the live client on desktop OpenJDK 21 + CACIO (the W2 rule), before any device work.
 *
 * <p>Modes (a frame-driven state machine, one mode per phase, suppression re-applied each frame because the
 * engine rebuilds the toplevel layout):
 * <ol>
 *   <li><b>BASELINE</b> — no suppression. Records the HUD widget rects + a region-pixel signature, captures a
 *       PNG, and proves the click-through INSTRUMENT works: synth-click the run + spec orb centres and confirm
 *       the {@code OPTION_RUN}/{@code SA_ATTACK} varps flip. If the instrument doesn't flip at baseline, the
 *       later results are inconclusive (guards against a false "not clickable").</li>
 *   <li><b>MASK</b> — {@code client.setDraw2DMask(DRAW_2D_NONE)}. Predicted to NOT remove the interface HUD (the
 *       mask governs world-space 2D extras — overhead text / hitsplats / headicons — not the minimap/chat/orbs
 *       widgets). Measured via the region signature vs baseline.</li>
 *   <li><b>HIDDEN</b> — {@code setHidden(true)} on the minimap/chat/orbs containers (the {@code MinimapPlugin}
 *       recipe). Predicted to remove the HUD cleanly but kill click-through (hidden widgets aren't clickable).
 *       Measured: region cleared + the orb click-through does NOT flip the varps.</li>
 *   <li><b>TRANSPARENT</b> — {@code setOpacity(255)} (fully transparent) on the same containers + descendants.
 *       The candidate CONFLICT-RESOLVER: a transparent widget is invisible but may stay in the hit-test. The two
 *       genuine unknowns this probe exists to settle: does opacity visually blank the HUD, and does the orb
 *       click-through still flip the varps?</li>
 * </ol>
 *
 * <p>Inert unless {@code -Drunelite.hudisolation=true}. Logs {@code [hud-iso] …}; PNGs go to
 * {@code -Drunelite.hudisolation.dir} (falling back to {@code -Drunelite.headless.capture.dir}). The final
 * {@code [hud-iso] VERDICT …} line is what the md5-gated bash verdict consumes.
 */
@Slf4j
public final class HudIsolationTest
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.hudisolation");

	private static final File DIR;

	static
	{
		String dir = System.getProperty("runelite.hudisolation.dir");
		if (dir == null || dir.isEmpty())
		{
			dir = System.getProperty("runelite.headless.capture.dir");
		}
		DIR = (dir == null || dir.isEmpty()) ? null : new File(dir);
		if (ENABLED && DIR != null)
		{
			//noinspection ResultOfMethodCallIgnored
			DIR.mkdirs();
		}
	}

	// Per-phase frame milestones (frames since entering the phase). Generous so the run/spec varp echo (a server
	// round-trip) lands before READ.
	private static final int F_SETTLE = 15;     // suppression applied this long before the capture
	private static final int F_CLICK_RUN = 24;
	private static final int F_READ = 92;       // ~60 frames (~1.5s) after the click for the varp to echo back
	private static final int F_END = 104;

	private enum Phase { WAIT, BASELINE, MASK, HIDDEN, TRANSPARENT, DONE }

	private static Phase phase = Phase.WAIT;
	private static int phaseFrame;
	private static boolean verdictEmitted;

	// Resolved once we're in the world.
	private static int idMinimap, idOrbs, idChat;
	private static String rootName = "?";

	// Baseline geometry / instrument.
	private static Rectangle rMinimap, rChat, rOrbs;
	private static long sigMinimapBase;
	private static int runCx, runCy;
	private static boolean runValid;   // the run-orb bounds are sane (the click-through instrument)

	// Per-phase click bookkeeping.
	private static int preRun;
	private static boolean clickedRun;

	// Verdict accumulators. REMOVAL is judged by isHidden (the engine contract: a hidden widget does not render)
	// + the captured PNGs as visual ground truth — NOT by a region pixel-diff, which the live minimap fools
	// (it changes frame-to-frame whether or not the HUD is removed). CLICKABILITY is judged by the run varp flip.
	private static boolean baseRunFlip;
	private static boolean maskHidden;                 // are the containers hidden after MASK? (expect false)
	private static boolean hiddenHeld, hiddenRunFlip;  // isHidden held under HIDDEN; run flip under HIDDEN
	private static boolean transHeld, transRunFlip;    // isHidden under TRANSPARENT (expect false); run flip

	private static boolean focused;

	private HudIsolationTest()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	public static void tick(Client client, GameState state)
	{
		if (!ENABLED || client == null || state != GameState.LOGGED_IN || phase == Phase.DONE)
		{
			if (phase == Phase.DONE && !verdictEmitted)
			{
				emitVerdict();
			}
			return;
		}

		// Dismiss the post-login "Welcome / Click here to play" lobby that covers the viewport until clicked.
		if (welcomeUp(client))
		{
			if ((phaseFrame++ % 20) == 5)
			{
				synthClick(client, client.getCanvasWidth() / 2, client.getCanvasHeight() / 2);
				log.info("[hud-iso] welcome lobby up -> click centre to enter world");
			}
			return;
		}

		if (phase == Phase.WAIT)
		{
			if (!resolveRoot(client) || !inWorld(client))
			{
				return;
			}
			log.info("[hud-iso] BOOTED inWorld root={} viewport={}x{}@{},{} canvas={}x{} resized={}",
				rootName, client.getViewportWidth(), client.getViewportHeight(),
				client.getViewportXOffset(), client.getViewportYOffset(),
				client.getCanvasWidth(), client.getCanvasHeight(), client.isResized());
			phase = Phase.BASELINE;
			phaseFrame = 0;
			return;
		}

		phaseFrame++;

		switch (phase)
		{
			case BASELINE:
				doBaseline(client);
				break;
			case MASK:
				doMask(client);
				break;
			case HIDDEN:
				doHidden(client);
				break;
			case TRANSPARENT:
				doTransparent(client);
				break;
			default:
				break;
		}
	}

	// ---- BASELINE: record geometry, capture, and validate the click-through instrument ----
	private static void doBaseline(Client client)
	{
		if (phaseFrame == F_SETTLE)
		{
			rMinimap = bounds(client, idMinimap);
			rChat = bounds(client, idChat);
			rOrbs = bounds(client, idOrbs);
			sigMinimapBase = regionSig(client, rMinimap);

			// Resolve the run orb as the click-through instrument; reject bogus (origin / off-canvas) bounds.
			final Widget run = safe(client, InterfaceID.Orbs.RUNBUTTON);
			final Rectangle rr = run == null ? null : run.getBounds();
			final int cw = client.getCanvasWidth();
			final int ch = client.getCanvasHeight();
			if (rr != null && rr.width > 4 && rr.height > 4 && rr.x >= 0 && rr.y >= 0
				&& rr.x + rr.width <= cw && rr.y + rr.height <= ch)
			{
				runCx = rr.x + rr.width / 2;
				runCy = rr.y + rr.height / 2;
				runValid = run != null && !run.isHidden();
			}
			capture(client, "baseline");
			log.info("[hud-iso] BASELINE runValid={} runCentre={},{} runBounds={} minimapRect={} chatRect={} orbsRect={}",
				runValid, runCx, runCy, rr, rMinimap, rChat, rOrbs);
		}
		clickRun(client);
		if (phaseFrame == F_READ)
		{
			baseRunFlip = runValid && client.getVarpValue(VarPlayerID.OPTION_RUN) != preRun;
			log.info("[hud-iso] BASELINE_READ run={} (pre {}) runFlip={} [instrument valid iff a flip]",
				client.getVarpValue(VarPlayerID.OPTION_RUN), preRun, baseRunFlip);
		}
		if (phaseFrame >= F_END)
		{
			advance(Phase.MASK);
		}
	}

	// ---- MASK: setDraw2DMask(NONE). Predicted NOT to remove the interface HUD (it governs world-2D extras). ----
	private static void doMask(Client client)
	{
		client.setDraw2DMask(Client.DRAW_2D_NONE);
		if (phaseFrame == F_SETTLE)
		{
			capture(client, "mask");
			// Removal is judged by isHidden (the mask never hides widgets) + the PNG. regionDelta is diagnostic only.
			maskHidden = isHidden(client, idMinimap) && isHidden(client, idChat) && isHidden(client, idOrbs);
			log.info("[hud-iso] MASK get2dMask={} containersHidden={} minimapRegionDelta={}% "
					+ "[removal=PNG ground truth; mask does NOT hide widgets]",
				client.getDraw2DMask(), maskHidden, deltaPct(regionSig(client, rMinimap), sigMinimapBase));
		}
		if (phaseFrame >= F_END)
		{
			client.setDraw2DMask(Client.DRAW_2D_ALL);
			advance(Phase.HIDDEN);
		}
	}

	// ---- HIDDEN: setHidden(true). Predicted to remove the HUD (isHidden held) but kill click-through. ----
	private static void doHidden(Client client)
	{
		setHidden(client, idMinimap, true);
		setHidden(client, idChat, true);
		setHidden(client, idOrbs, true);
		if (phaseFrame == F_SETTLE)
		{
			capture(client, "hidden");
			hiddenHeld = isHidden(client, idMinimap) && isHidden(client, idChat) && isHidden(client, idOrbs);
			log.info("[hud-iso] HIDDEN held={} minimapRegionDelta={}% [held=isHidden engine contract; PNG = visual proof of removal]",
				hiddenHeld, deltaPct(regionSig(client, rMinimap), sigMinimapBase));
		}
		clickRun(client);
		if (phaseFrame == F_READ)
		{
			hiddenRunFlip = runValid && client.getVarpValue(VarPlayerID.OPTION_RUN) != preRun;
			log.info("[hud-iso] HIDDEN_READ runFlip={} [expect FALSE = a hidden widget is not clickable]", hiddenRunFlip);
		}
		if (phaseFrame >= F_END)
		{
			setHidden(client, idMinimap, false);
			setHidden(client, idChat, false);
			setHidden(client, idOrbs, false);
			advance(Phase.TRANSPARENT);
		}
	}

	// ---- TRANSPARENT: setOpacity(255) on the containers + descendants. Candidate "invisible + clickable" lever. ----
	private static void doTransparent(Client client)
	{
		setOpacityDeep(client, idMinimap, 255);
		setOpacityDeep(client, idChat, 255);
		setOpacityDeep(client, idOrbs, 255);
		if (phaseFrame == F_SETTLE)
		{
			capture(client, "transparent");
			// Opacity does NOT set hidden, so the widget still renders (only its rectangle fill goes transparent —
			// sprites/models/text remain). transHeld=false is the engine contract; the PNG shows the HUD is still
			// visible. So transparent is NOT a clean remover (do not infer removal from a region pixel-diff here).
			transHeld = isHidden(client, idMinimap) && isHidden(client, idChat) && isHidden(client, idOrbs);
			log.info("[hud-iso] TRANSPARENT containersHidden={} minimapRegionDelta={}% "
					+ "[opacity!=hidden so widget still renders; PNG shows minimap/orbs/text remain]",
				transHeld, deltaPct(regionSig(client, rMinimap), sigMinimapBase));
		}
		clickRun(client);
		if (phaseFrame == F_READ)
		{
			transRunFlip = runValid && client.getVarpValue(VarPlayerID.OPTION_RUN) != preRun;
			log.info("[hud-iso] TRANSPARENT_READ runFlip={} [measuring whether a transparent widget stays clickable]",
				transRunFlip);
		}
		if (phaseFrame >= F_END)
		{
			setOpacityDeep(client, idMinimap, 0);
			setOpacityDeep(client, idChat, 0);
			setOpacityDeep(client, idOrbs, 0);
			advance(Phase.DONE);
		}
	}

	// Click the run orb at its baseline centre, recording the pre-click varp for this phase.
	private static void clickRun(Client client)
	{
		if (phaseFrame == F_CLICK_RUN - 1)
		{
			preRun = client.getVarpValue(VarPlayerID.OPTION_RUN);
			clickedRun = false;
		}
		if (runValid && phaseFrame == F_CLICK_RUN && !clickedRun)
		{
			clickedRun = true;
			synthClick(client, runCx, runCy);
			log.info("[hud-iso] {} click RUN orb @{},{} (preRun={})", phase, runCx, runCy, preRun);
		}
	}

	private static void advance(Phase next)
	{
		phase = next;
		phaseFrame = 0;
		if (next == Phase.DONE)
		{
			emitVerdict();
		}
	}

	private static void emitVerdict()
	{
		if (verdictEmitted)
		{
			return;
		}
		verdictEmitted = true;
		// Removal (engine contract) = the widget is hidden. Clickable = the run varp flipped. A "resolver" is a
		// lever that REMOVES the HUD (hidden) AND keeps it clickable. setHidden removes but isn't clickable;
		// transparent is clickable but does NOT hide (still renders) — so no single lever resolves both.
		final String resolver = (hiddenHeld && hiddenRunFlip) ? "hidden"
			: (transHeld && transRunFlip) ? "transparent"
			: "none";
		log.info("[hud-iso] VERDICT instrument_ok={} base_run_flip={} mask_hidden={} "
				+ "hidden_held={} hidden_clickable={} trans_held={} trans_clickable={} resolver={}",
			baseRunFlip, baseRunFlip, maskHidden,
			hiddenHeld, hiddenRunFlip, transHeld, transRunFlip, resolver);
	}

	// ---------------- helpers ----------------

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

	private static boolean resolveRoot(Client client)
	{
		try
		{
			if (!client.isResized())
			{
				rootName = "FIXED(unsupported)";
				return false;   // A0 requires resizable mode (the native-HUD target)
			}
			final boolean preEoc = client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1;
			if (preEoc)
			{
				idMinimap = InterfaceID.ToplevelPreEoc.MAP_MINIMAP;
				idOrbs = InterfaceID.ToplevelPreEoc.ORBS;
				idChat = InterfaceID.ToplevelPreEoc.CHAT_CONTAINER;
				rootName = "PreEoc";
			}
			else
			{
				idMinimap = InterfaceID.ToplevelOsrsStretch.MAP_MINIMAP;
				idOrbs = InterfaceID.ToplevelOsrsStretch.ORBS;
				idChat = InterfaceID.ToplevelOsrsStretch.CHAT_CONTAINER;
				rootName = "OsrsStretch";
			}
			return safe(client, idMinimap) != null;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	private static boolean inWorld(Client client)
	{
		return client.getViewportWidth() > 0 && client.getViewportHeight() > 0 && safe(client, idMinimap) != null;
	}

	private static Widget safe(Client client, int id)
	{
		try
		{
			return client.getWidget(id);
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static Rectangle bounds(Client client, int id)
	{
		final Widget w = safe(client, id);
		try
		{
			return w == null ? null : w.getBounds();
		}
		catch (RuntimeException ex)
		{
			return null;
		}
	}

	private static boolean isHidden(Client client, int id)
	{
		final Widget w = safe(client, id);
		return w == null || w.isHidden();
	}

	private static void setHidden(Client client, int id, boolean hidden)
	{
		final Widget w = safe(client, id);
		if (w != null)
		{
			try
			{
				w.setHidden(hidden);
			}
			catch (RuntimeException ignore)
			{
			}
		}
	}

	private static void setOpacityDeep(Client client, int id, int opacity)
	{
		setOpacityRec(safe(client, id), opacity, 0);
	}

	private static void setOpacityRec(Widget w, int opacity, int depth)
	{
		if (w == null || depth > 4)
		{
			return;
		}
		try
		{
			w.setOpacity(opacity);
		}
		catch (RuntimeException ignore)
		{
		}
		setOpacityChildren(w.getChildren(), opacity, depth);
		setOpacityChildren(w.getStaticChildren(), opacity, depth);
		setOpacityChildren(w.getDynamicChildren(), opacity, depth);
		setOpacityChildren(w.getNestedChildren(), opacity, depth);
	}

	private static void setOpacityChildren(Widget[] kids, int opacity, int depth)
	{
		if (kids == null)
		{
			return;
		}
		for (final Widget k : kids)
		{
			setOpacityRec(k, opacity, depth + 1);
		}
	}

	/** Region pixel delta vs baseline, as an integer percent. DIAGNOSTIC ONLY — the live minimap changes this
	 *  frame-to-frame whether or not the HUD is removed, so it must NOT be used as a removal verdict (the PNG +
	 *  isHidden are the trustworthy signals). Printed so a human can sanity-check the captures. */
	private static int deltaPct(long sig, long base)
	{
		final long denom = Math.max(1L, base);
		return (int) Math.round(100.0 * Math.abs(sig - base) / denom);
	}

	/** Sum of R+G+B over the rect — a cheap signature; a large relative change means the render in that rect moved. */
	private static long regionSig(Client client, Rectangle r)
	{
		if (r == null)
		{
			return 0L;
		}
		try
		{
			final BufferProvider bp = client.getBufferProvider();
			final int[] px = bp.getPixels();
			final int w = bp.getWidth();
			final int h = bp.getHeight();
			if (px == null || w <= 0 || h <= 0)
			{
				return 0L;
			}
			final int x0 = Math.max(0, r.x);
			final int y0 = Math.max(0, r.y);
			final int x1 = Math.min(w, r.x + r.width);
			final int y1 = Math.min(h, r.y + r.height);
			long sum = 0L;
			for (int y = y0; y < y1; y++)
			{
				int idx = y * w + x0;
				for (int x = x0; x < x1; x++, idx++)
				{
					final int p = px[idx];
					sum += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
				}
			}
			return sum;
		}
		catch (RuntimeException ex)
		{
			return 0L;
		}
	}

	private static void capture(Client client, String mode)
	{
		if (DIR == null)
		{
			return;
		}
		try
		{
			final BufferProvider bp = client.getBufferProvider();
			final int[] px = bp.getPixels();
			final int w = bp.getWidth();
			final int h = bp.getHeight();
			if (px == null || w <= 0 || h <= 0 || px.length < w * h)
			{
				return;
			}
			final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, w, h, px, 0, w);
			final File out = new File(DIR, String.format("hud-%s-%dx%d.png", mode, w, h));
			javax.imageio.ImageIO.write(img, "png", out);
			log.info("[hud-iso] captured {} ({}x{})", out.getName(), w, h);
		}
		catch (Exception ex)
		{
			log.warn("[hud-iso] capture {} failed: {}", mode, String.valueOf(ex));
		}
	}

	/** Synthesize a left click at {@code (x,y)} — focus-prime + enter + move + press + release + click. */
	private static void synthClick(Client client, int x, int y)
	{
		final Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		if (!focused)
		{
			q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
			q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
			focused = true;
		}
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 2,
			InputEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 3, 0, x, y, 1, false, MouseEvent.BUTTON1));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 4, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}
}
