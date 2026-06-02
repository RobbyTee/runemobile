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

import com.google.inject.Injector;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.MainBufferProvider;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.VarClientInt;
import net.runelite.api.WorldView;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Phase-4 (W3) sub-goal B: the native&lt;-&gt;Java facade for a <em>real interactive</em> client on iOS.
 *
 * <p>This is the one class the native shell calls into via reverse JNI. It exposes two seams:
 * <ul>
 *   <li><b>Live framebuffer blit</b> — {@link #getFrameWidth()}/{@link #getFrameHeight()}/
 *       {@link #getFramePixels()} hand the engine's composited {@code mainBufferProvider} pixels straight
 *       to the native side each screen refresh. This replaces the §6.2 PNG-capture-to-disk display path
 *       (which was choppy, disk-bound and budget-capped) with a direct, smooth blit.</li>
 *   <li><b>Touch ingress</b> — {@link #onTouch(int, int, int, int)} turns a UIKit touch (already mapped to
 *       game coordinates by the native side) into AWT {@link MouseEvent}s posted to the toolkit
 *       {@link EventQueue} with the gamepack {@link Canvas} as source. Sub-goal A proved the on-device CTC
 *       EventQueue accepts off-window posts, so this is the same proven path HeadlessLogin uses, now driven
 *       by real touches instead of a scripted click.</li>
 * </ul>
 *
 * <p>{@link #register(Client)} (top of {@code Hooks.draw}) keeps the bridge pointed at the live client,
 * and {@link #snapshot(MainBufferProvider)} (after the overlays composite) publishes each complete frame.
 * Entirely inert unless {@code -Drunelite.bridge=true}, so it adds nothing to the stock desktop client.
 *
 * <p>Thread model: {@code register} runs on the engine/AWT thread; the native side calls the getters,
 * {@link #onTouch} and {@link #onClick} from the UI thread (JNI-attached). {@code EventQueue.postEvent}
 * is thread-safe, and the pixel array is the buffer's live backing store — reading it off-thread can
 * momentarily tear, which is acceptable for a display.
 *
 * <p><b>Input contract (Phase 5).</b> The native side owns gesture <em>recognition</em> (UIKit gesture
 * recognizers: tap, long-press, pan, pinch) and this facade exposes only the raw AWT primitives:
 * <ul>
 *   <li>{@link #onClick(int, int, int)} — a discrete <em>tap</em>: the full focus-prime + move + press +
 *       release + click sequence at one point (the proven {@code HeadlessLogin} click shape). Button 1 =
 *       a left tap (walk/interact / menu-entry select); button 3 = a long-press (open the right-click
 *       menu).</li>
 *   <li>{@link #onTouch(int, int, int, int)} — a <em>drag</em> stream: press / drag / release with NO
 *       synthesized click on release. This is the camera / scroll primitive; a release here is just a
 *       {@code MOUSE_RELEASED}. Earlier (sub-goal B) this method auto-clicked on every UP, which produced
 *       a phantom click at the end of every drag and long-press — removed.</li>
 * </ul>
 */
@Slf4j
public final class JavaBridge
{
	private static final boolean ENABLED = Boolean.getBoolean("runelite.bridge");

	// Touch event types — kept in lockstep with the native side (the pan recognizer's began/changed/ended).
	public static final int TOUCH_DOWN = 0;
	public static final int TOUCH_MOVE = 1;
	public static final int TOUCH_UP = 2;

	private static volatile Client client;

	// Touch forgiveness (snap-to-target). Wired from HeadlessClientUI.init; used to resolve a left tap onto
	// the nearest entity ON THE CLIENT THREAD. Off by -Drunelite.touch.snap=false; radius (game px) tunable
	// via -Drunelite.touch.snapRadius. snapCount is diagnostic (confirm snapping fired on device).
	private static volatile ClientThread clientThread;
	private static final boolean SNAP_ENABLED =
		Boolean.parseBoolean(System.getProperty("runelite.touch.snap", "true"));
	// Gate snap resolution on the world-viewport bounds: only a tap that lands inside the 3D scene viewport
	// may snap to a world entity. Without this, resolveSnap fires for EVERY in-game left tap regardless of
	// where it lands — so a tap on the minimap / tabs / chat / inventory (all OUTSIDE the viewport), and in
	// particular under the coming native edge-tab chrome, can drag a click onto a distant world entity. The
	// literal click still routes (we never swallow it), so minimap tap-to-walk and inventory taps are
	// unaffected; only the snap-to-entity resolution is suppressed outside the scene. A/B via the property
	// (false = legacy snap-everywhere).
	private static final boolean SNAP_VIEWPORT_GATE =
		Boolean.parseBoolean(System.getProperty("runelite.touch.snapViewportGate", "true"));
	// ~30 game px on the 1189-wide canvas ≈ ~4 mm on the device panel ≈ a fingertip contact radius.
	private static final int SNAP_RADIUS = Integer.getInteger("runelite.touch.snapRadius", 30);
	private static volatile int snapCount;

	// Selection feedback, drawn in Java straight into the game frame (so it's tight + tracks the entity, unlike
	// a native polygon of the loose convex hull). A left tap that resolves onto an NPC/object flashes a tight
	// MODEL OUTLINE on it for SEL_FLASH_MS. (Walk destination is left to RuneLite's own Tile Indicators
	// plugin — "Highlight destination tile" — rather than drawing our own marker.) Set + consumed on the
	// client thread (the tap resolution and {@link #drawSelection}).
	private static final long SEL_FLASH_MS = Long.getLong("runelite.touch.selFlashMs", 1500L);
	private static Actor selActor;            // selected NPC/player to outline (null = none)
	private static TileObject selObject;      // selected scene object to outline (null = none)
	private static long selUntil;             // outline fades after this timestamp
	private static ModelOutlineRenderer outliner;   // lazily resolved from the injector (tight model outline)
	// Smart snap (priority-aware): instead of blindly nudging a left tap onto the nearest clickbox (the scene is
	// clickbox-saturated, so "nearest" is near-arbitrary), let the ENGINE decide. Move to the literal tap and
	// read its top menu action: if it's already a real interact, click there (never hijack a direct hit). Only
	// if the tap would merely "Walk here" (you missed) do we nudge to the nearest entity — and we VALIDATE the
	// nudge yields an interact before committing, else fall back to walking the literal point. Off by
	// -Drunelite.touch.snapSmart=false (legacy proactive geometric snap).
	private static final boolean SMART_SNAP =
		Boolean.parseBoolean(System.getProperty("runelite.touch.snapSmart", "true"));

	// Published complete frames for the live blit. The native side reads at the display refresh (up to
	// 120 Hz) but the engine composites at ~50 fps, so reading mainBufferProvider directly catches it
	// mid-clear/mid-draw -> fast flashing. Instead we snapshot ONLY at the post-overlay-composite hook
	// (when the frame is whole) into a double buffer, and publish via the volatile `frame` ref so the
	// native reader always copies out a complete, stable frame. `frameSeq` lets the native side skip the
	// (costly) CGImage rebuild when the engine hasn't composited a new frame since the last display tick.
	private static volatile int[] frame;
	private static volatile int frameW;
	private static volatile int frameH;
	private static volatile int frameSeq;
	private static int[] bufA;
	private static int[] bufB;
	private static boolean useA = true;

	// Input bookkeeping (only ever touched from the single native UI thread that drives onTouch/onClick).
	private static boolean focused;       // FocusEvent gained — keyboard/mouse accepted
	private static volatile boolean entered;  // MOUSE_ENTERED posted — pointer currently "present" (volatile:
	                                      // clearHover may run from the client thread after a snap tap)
	private static boolean pressed;
	private static int pressButton = MouseEvent.BUTTON1;

	// Camera (two-finger pan -> direct yaw/pitch target). Sub-pixel remainders so a slow drag still turns
	// (a small per-event pixel delta would otherwise truncate to 0 yaw units).
	private static final float YAW_UNITS_PER_PX = 1.5f;    // ~a screen-width drag ≈ half a turn
	private static final float PITCH_UNITS_PER_PX = 1.2f;
	private static final int PITCH_MIN = 128;              // RS camera-pitch clamp (normal range)
	private static final int PITCH_MAX = 383;
	private static float yawRemainder;
	private static float pitchRemainder;

	private JavaBridge()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/** Called at the top of {@code Hooks.draw} each frame to keep the bridge pointed at the live client. */
	public static void register(Client c)
	{
		client = c;
	}

	/**
	 * Wire the {@link ClientThread} used to resolve touch-snap on the game thread. Called once from
	 * {@code HeadlessClientUI.init}. Without it, left taps fall back to the literal point (no snap).
	 */
	public static void setClientThread(ClientThread ct)
	{
		clientThread = ct;
	}

	/**
	 * Called from {@code Hooks.draw} AFTER the overlays composite (the same point {@code HeadlessCapture}
	 * dumps PNGs) — i.e. when {@code mainBufferProvider} holds a <em>complete</em> frame. Copies it into a
	 * double buffer and publishes it for the live blit. Snapshotting here (not at the native read) is what
	 * eliminates the mid-composite flashing.
	 */
	public static void snapshot(MainBufferProvider b)
	{
		if (!ENABLED || b == null)
		{
			return;
		}
		final int[] px = b.getPixels();
		final int w = b.getWidth();
		final int h = b.getHeight();
		if (px == null || w <= 0 || h <= 0)
		{
			return;
		}
		final int n = w * h;
		if (px.length < n)
		{
			return;
		}
		int[] dst = useA ? bufA : bufB;
		if (dst == null || dst.length != n)
		{
			dst = new int[n];
			if (useA)
			{
				bufA = dst;
			}
			else
			{
				bufB = dst;
			}
		}
		System.arraycopy(px, 0, dst, 0, n);
		frameW = w;
		frameH = h;
		frame = dst;        // publish (volatile)
		frameSeq++;         // bump LAST so a native reader that sees the new seq also sees this frame
		useA = !useA;       // next snapshot fills the OTHER buffer, so this one stays stable a full cycle
	}

	// ---- Live framebuffer blit (native pulls these each screen refresh) --------------------------------

	public static int getFrameWidth()
	{
		return frameW;
	}

	public static int getFrameHeight()
	{
		return frameH;
	}

	/**
	 * Monotonically increasing counter bumped once per published frame. The native side caches the last
	 * value it rendered and skips the {@code CGImage} rebuild when it is unchanged (the display refreshes
	 * faster than the engine composites), so an idle login screen costs almost nothing per tick.
	 */
	public static int getFrameSeq()
	{
		return frameSeq;
	}

	/**
	 * The latest <em>complete</em> composited frame as packed {@code 0xAARRGGBB} ints (a stable snapshot,
	 * not the live back buffer). Returns {@code null} before the first frame.
	 */
	public static int[] getFramePixels()
	{
		return frame;
	}

	// ---- Input ingress (native recognizes the gesture, then calls these with game coords) --------------

	/** Resolve the live gamepack canvas, or {@code null} if the bridge is off / the client isn't ready. */
	private static Canvas liveCanvas()
	{
		if (!ENABLED)
		{
			return null;
		}
		final Client c = client;
		return c == null ? null : c.getCanvas();
	}

	/**
	 * Assert canvas focus once. There is no native focus owner headless, so the gamepack ignores mouse
	 * <em>and</em> keyboard input until it believes the canvas has focus.
	 */
	private static void ensureFocus(EventQueue q, Canvas canvas)
	{
		if (!focused)
		{
			q.postEvent(new FocusEvent(canvas, FocusEvent.FOCUS_GAINED, false, null));
			focused = true;
		}
	}

	/**
	 * Assert focus + pointer <em>presence</em> for an interaction: focus once, then a {@code MOUSE_ENTERED}
	 * if the pointer is currently "absent" (we exit it after taps / on camera drags so nothing stays
	 * highlighted — see {@link #clearHover()}). Mirrors what {@code HeadlessLogin} primes before a click.
	 */
	private static void primeFocus(EventQueue q, Canvas canvas, long t, int x, int y)
	{
		ensureFocus(q, canvas);
		if (!entered)
		{
			q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_ENTERED, t, 0, x, y, 0, false));
			entered = true;
		}
	}

	/**
	 * Make the engine's pointer "absent" — post a {@code MOUSE_EXITED} so nothing stays hovered/highlighted.
	 * On a touch device there is no persistent cursor: a finger is only "on" the screen during a tap. Without
	 * this, the engine's mouse stays frozen at the last tap point, and a one-finger <em>camera</em> drag
	 * (which moves the world, not the pointer) rotates scenery <em>under</em> that frozen point — so random
	 * objects light up and a stale left-click action builds there, which the next walk-tap then triggers.
	 * The native shell calls this when a camera/pinch gesture begins (and we exit after each tap), so the
	 * highlight only ever appears at the point you are actually tapping.
	 */
	public static void clearHover()
	{
		if (!entered)
		{
			return;
		}
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_EXITED, t, 0, -1, -1, 0, false));
		entered = false;
		log.debug("[snap] clearHover (camera/pinch begin) -> MOUSE_EXITED");
	}

	/**
	 * A discrete tap at {@code (x,y)} (gamepack-canvas pixels): the full focus-prime + move + press +
	 * release + click sequence — the proven {@code HeadlessLogin.synthesizeClick} shape. {@code button} is
	 * 1 (left tap: walk / interact / select a menu entry) or 3 (long-press: open the right-click menu).
	 *
	 * <p>This is the ONLY path that synthesizes {@code MOUSE_CLICKED}; the native side calls it solely for
	 * a recognized tap or long-press, so a drag (which streams through {@link #onTouch}) never produces a
	 * spurious click.
	 *
	 * <p><b>Touch forgiveness (Phase 6).</b> A finger is far fatter than a mouse pixel, so a left tap that
	 * lands <em>near</em> an interactable (but misses the exact clickbox) is snapped onto it — the
	 * touch-equivalent of OSRS Mobile's enlarged interaction. We cannot change the gamepack's exact-pixel
	 * hit test (it is the unmodified engine), but we own the {@code (x,y)} we feed it: for a left tap in-game
	 * we resolve the nearest entity on the <em>client thread</em> (scene geometry is only safe to read there)
	 * and then synthesize the click at the resolved point — ~1 frame later, imperceptible for a tap. If the
	 * tap already lands on a clickbox, or nothing interactable is within {@link #snapRadius()}, the original
	 * point is used (so a deliberate tap on empty ground still walks). Right-clicks (long-press) are left
	 * unsnapped — that menu already has slide-to-select forgiveness.
	 */
	public static void onClick(int x, int y, int button)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final ClientThread ct = clientThread;
		final Client c = client;
		if (button == MouseEvent.BUTTON1 && ct != null
			&& c != null && c.getGameState() == GameState.LOGGED_IN)
		{
			// In-game left tap, resolved on the client thread (scene/menu reads are only safe there).
			ct.invoke(() -> startLeftTap(canvas, ct, c, x, y));
			return;
		}
		synthesizeClick(canvas, x, y, button);
	}

	/**
	 * In-game left-tap resolution (client thread). Always moves the pointer to the target a cycle before the
	 * click (so the engine recomputes the left-click action FOR that point — posting move+click in one batch
	 * fires the action for the PREVIOUS cursor position, the old "rotate then walk-tap mis-clicks" bug).
	 *
	 * <p>Snapping only applies inside the world viewport ({@link #SNAP_VIEWPORT_GATE}) — a tap on the UI never
	 * snaps. With {@link #SMART_SNAP} the resolution is <b>entity-aware</b> ({@link #resolveSnapTarget}):
	 * strongly prefer actors over the carpet of ground/object clickboxes, click the entity's own (live) hull,
	 * and arm a tight model-outline flash on it ({@link #drawSelection}). A tap that resolves to walk arms the
	 * destination-tile marker instead. Otherwise the legacy proactive geometric nudge is used.
	 */
	private static void startLeftTap(Canvas canvas, ClientThread ct, Client c, int x, int y)
	{
		final boolean snap = SNAP_ENABLED && (!SNAP_VIEWPORT_GATE || inWorldViewport(c, x, y));
		if (SMART_SNAP && snap)
		{
			final SnapTarget t = resolveSnapTarget(c, x, y);
			if (t != null)
			{
				armEntityOutline(t.actor, t.object);   // tight model outline, drawn in-frame
				final int cx = t.point[0];
				final int cy = t.point[1];
				if (!t.literal)
				{
					snapCount++;
				}
				postMove(canvas, cx, cy);
				ct.invokeLater(() -> postClickButtons(canvas, cx, cy, MouseEvent.BUTTON1));
				log.debug("[snap] smart: tap {},{} -> {} {},{} ({})",
					x, y, t.literal ? "hit" : "SNAP", cx, cy, t.actor != null ? "actor" : "object");
				return;
			}
			// Nothing interactable within reach -> a deliberate walk. Clear any lingering outline; the walk
			// destination is shown by RuneLite's Tile Indicators plugin, not us.
			selActor = null;
			selObject = null;
			selUntil = 0;
			postMove(canvas, x, y);
			ct.invokeLater(() -> postClickButtons(canvas, x, y, MouseEvent.BUTTON1));
			log.debug("[snap] smart: tap {},{} -> walk (no entity within {}px)", x, y, snapRadius());
			return;
		}
		final int[] p = snap ? resolveSnap(c, x, y) : new int[]{x, y};
		postMove(canvas, p[0], p[1]);
		ct.invokeLater(() -> postClickButtons(canvas, p[0], p[1], MouseEvent.BUTTON1));
	}

	/** Arm a tight outline flash on the selected entity. */
	private static void armEntityOutline(Actor actor, TileObject object)
	{
		selActor = actor;
		selObject = object;
		selUntil = System.currentTimeMillis() + SEL_FLASH_MS;
	}

	/** The chosen tap target: the entity refs to outline, the {@code point} to click, and how it was hit. */
	private static final class SnapTarget
	{
		final Actor actor;      // the selected NPC/player (null if an object)
		final TileObject object;// the selected scene object (null if an actor)
		final int[] point;      // click point: the literal tap (a direct hit) or the entity's hull centroid
		final boolean literal;  // true = tapped inside the hull (click the literal point, engine hits it)

		SnapTarget(Actor actor, TileObject object, int[] point, boolean literal)
		{
			this.actor = actor;
			this.object = object;
			this.point = point;
			this.literal = literal;
		}
	}

	/** Accumulates, per category, the entity the tap is inside (if any) and the nearest entity within radius. */
	private static final class Pick
	{
		Object contain;          // owner (Actor/TileObject) whose shape contains the tap
		Shape containShape;
		Object nearest;          // owner of the nearest shape within radius
		Shape nearestShape;
		double nearestD = Double.MAX_VALUE;

		void consider(Object owner, Shape s, int x, int y, int r)
		{
			if (s == null)
			{
				return;
			}
			if (s.contains(x, y))
			{
				if (contain == null)
				{
					contain = owner;
					containShape = s;
				}
				return;
			}
			final Rectangle b = s.getBounds();
			if (b.width <= 0 || b.height <= 0)
			{
				return;
			}
			final double d = distToRect(x, y, b);
			if (d <= r && d < nearestD)
			{
				nearestD = d;
				nearest = owner;
				nearestShape = s;
			}
		}
	}

	/**
	 * Entity-aware tap resolution (client thread). Considers actors (NPCs/players, by convex hull) and scene
	 * objects (by clickbox) separately, and resolves with a <b>strong actor preference</b>: a containing actor
	 * beats a containing object, and the nearest actor within radius beats the nearest object. This is the fix
	 * for "snapping works but not for NPCs" — in a busy scene the ground/wall/decorative clickboxes blanket the
	 * floor, so a plain nearest-shape search almost always snapped to an object and walked past the NPC. Clicks
	 * the entity's own hull centroid (so a near-miss lands ON the moving NPC, not a stale pixel). Returns
	 * {@code null} if nothing interactable is within {@link #snapRadius()} (a deliberate walk).
	 */
	static SnapTarget resolveSnapTarget(Client c, int x, int y)
	{
		try
		{
			final WorldView wv = c.getTopLevelWorldView();
			if (wv == null)
			{
				return null;
			}
			final int r = snapRadius();
			final Pick actors = new Pick();
			final Pick objects = new Pick();

			for (Actor a : wv.npcs())
			{
				actors.consider(a, a.getConvexHull(), x, y, r);
			}
			for (Actor a : wv.players())
			{
				actors.consider(a, a.getConvexHull(), x, y, r);
			}

			final Scene scene = wv.getScene();
			final int plane = wv.getPlane();
			final Tile[][][] tiles = scene == null ? null : scene.getTiles();
			if (tiles != null && plane >= 0 && plane < tiles.length)
			{
				for (Tile[] row : tiles[plane])
				{
					if (row == null)
					{
						continue;
					}
					for (Tile tile : row)
					{
						if (tile == null)
						{
							continue;
						}
						final GameObject[] gos = tile.getGameObjects();
						if (gos != null)
						{
							for (GameObject go : gos)
							{
								if (go != null)
								{
									objects.consider(go, go.getClickbox(), x, y, r);
								}
							}
						}
						considerObjPick(objects, tile.getWallObject(), x, y, r);
						considerObjPick(objects, tile.getGroundObject(), x, y, r);
						considerObjPick(objects, tile.getDecorativeObject(), x, y, r);
					}
				}
			}

			// Actor preference: containing actor > containing object > nearest actor > nearest object.
			if (actors.contain != null)
			{
				return new SnapTarget((Actor) actors.contain, null, new int[]{x, y}, true);
			}
			if (objects.contain != null)
			{
				return new SnapTarget(null, (TileObject) objects.contain, new int[]{x, y}, true);
			}
			if (actors.nearest != null)
			{
				return new SnapTarget((Actor) actors.nearest, null, hullCentroid(actors.nearestShape), false);
			}
			if (objects.nearest != null)
			{
				return new SnapTarget(null, (TileObject) objects.nearest, hullCentroid(objects.nearestShape), false);
			}
			return null;
		}
		catch (RuntimeException ex)
		{
			log.debug("[snap] resolveSnapTarget failed", ex);
			return null;
		}
	}

	private static void considerObjPick(Pick p, TileObject o, int x, int y, int r)
	{
		if (o != null)
		{
			p.consider(o, o.getClickbox(), x, y, r);
		}
	}

	/** Lazily resolve the model-outline renderer (the tight silhouette RuneLite's NPC outline mode uses). */
	private static ModelOutlineRenderer outliner()
	{
		if (outliner == null)
		{
			final Injector inj = RuneLite.getInjector();
			if (inj != null)
			{
				try
				{
					outliner = inj.getInstance(ModelOutlineRenderer.class);
				}
				catch (RuntimeException ex)
				{
					log.debug("[snap] no ModelOutlineRenderer", ex);
				}
			}
		}
		return outliner;
	}

	// Tight entity outline colour (yellow), matching the OSRS-Mobile selection feel.
	private static final Color SEL_OUTLINE = new Color(255, 243, 77, 235);

	/**
	 * Draw the selection feedback INTO the game frame (client thread, from {@code Hooks.draw} after the overlays
	 * composite, before the blit snapshot): a tight model outline on the just-selected entity for a beat.
	 * Tighter than a native polygon of the convex hull and it tracks the moving entity, since it's the engine's
	 * own model silhouette. (The walk destination tile is left to RuneLite's Tile Indicators plugin.)
	 */
	public static void drawSelection(Client c)
	{
		if (!ENABLED || c == null)
		{
			return;
		}
		try
		{
			if (System.currentTimeMillis() < selUntil)
			{
				final ModelOutlineRenderer o = outliner();
				if (o != null)
				{
					if (selActor != null)
					{
						o.drawOutline(selActor, 2, SEL_OUTLINE, 4);
					}
					else if (selObject != null)
					{
						o.drawOutline(selObject, 2, SEL_OUTLINE, 4);
					}
				}
			}
			else
			{
				selActor = null;
				selObject = null;
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("[snap] drawSelection failed", ex);
		}
	}

	/**
	 * A <em>precise</em> left/right click at exactly {@code (x,y)} — same proven move+click shape as
	 * {@link #onClick}, but with <b>no snap-to-entity resolution</b>. This is for deliberate UI proxies (the
	 * native chrome tapping a tab stone / orb / widget), where the coordinate is already the exact target and
	 * touch-forgiveness must NOT redirect it: with a full-screen world viewport, snap would otherwise pull a
	 * tab-stone click onto a nearby world entity. Keeps the two-cycle move→click (so the engine re-targets the
	 * widget before the click) but feeds the literal point.
	 */
	public static void onClickPrecise(int x, int y, int button)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final ClientThread ct = clientThread;
		final Client c = client;
		if (button == MouseEvent.BUTTON1 && ct != null
			&& c != null && c.getGameState() == GameState.LOGGED_IN)
		{
			ct.invoke(() ->
			{
				postMove(canvas, x, y);
				ct.invokeLater(() -> postClickButtons(canvas, x, y, MouseEvent.BUTTON1));
			});
			return;
		}
		synthesizeClick(canvas, x, y, button);
	}

	/** Post the focus-prime + move + press + release + click sequence at {@code (x,y)} in one batch. */
	private static void synthesizeClick(Canvas canvas, int x, int y, int awtButton)
	{
		postMove(canvas, x, y);
		postClickButtons(canvas, x, y, awtButton);
	}

	/** Prime focus/presence and move the pointer to {@code (x,y)} (no buttons) — lets the engine re-target. */
	private static void postMove(Canvas canvas, int x, int y)
	{
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
	}

	/** Post press + release + click at {@code (x,y)} for {@code awtButton} (no preceding move). */
	private static void postClickButtons(Canvas canvas, int x, int y, int awtButton)
	{
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		final int downMask = awtButton == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t,
			downMask, x, y, 1, false, awtButton));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t + 1,
			0, x, y, 1, false, awtButton));
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_CLICKED, t + 2,
			0, x, y, 1, false, awtButton));
	}

	// Mutable accumulator for the nearest snap candidate (kept off the hot path; one per resolveSnap call).
	// Also carries diagnostics so a single device run explains every tap (why it did / didn't snap).
	private static final class Nearest
	{
		Shape shape;            // best candidate within radius
		double dist = Double.MAX_VALUE;
		int considered;         // candidates with a non-null clickbox/hull
		double nearestAny = Double.MAX_VALUE;  // closest candidate distance REGARDLESS of radius (for tuning)
		boolean exact;          // the tap already lands inside a clickbox -> don't snap, engine hits it
	}

	/**
	 * Is {@code (x,y)} (gamepack-canvas pixels) inside the live 3D-scene viewport? The engine reports the
	 * viewport rect via {@code getViewportXOffset/YOffset/Width/Height}; a negative offset or non-positive
	 * extent means the viewport is not currently drawn (e.g. a full-screen interface is up), in which case
	 * nothing should snap. Read on the client thread (the caller already is). Used to keep snap from firing on
	 * taps that land on the UI rather than the world.
	 */
	private static boolean inWorldViewport(Client c, int x, int y)
	{
		try
		{
			final int vx = c.getViewportXOffset();
			final int vy = c.getViewportYOffset();
			final int vw = c.getViewportWidth();
			final int vh = c.getViewportHeight();
			if (vx < 0 || vy < 0 || vw <= 0 || vh <= 0)
			{
				return false;
			}
			return x >= vx && x < vx + vw && y >= vy && y < vy + vh;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * Touch-forgiveness resolver — MUST run on the client thread (scene geometry is only safe to read there).
	 * Returns the point to click: the original {@code (x,y)} if it already lands inside a clickbox or nothing
	 * interactable is within {@link #snapRadius()}, else the nearest candidate's clickbox centroid. Considers
	 * NPCs, players, and scene objects (game/wall/ground/decorative — trees, rocks, doors, banks, …) on the
	 * current plane. Ground items are intentionally excluded (their left-click default is "Walk here" anyway).
	 */
	static int[] resolveSnap(Client c, int x, int y)
	{
		final int[] original = {x, y};
		try
		{
			final WorldView wv = c.getTopLevelWorldView();
			if (wv == null)
			{
				return original;
			}
			final Nearest near = new Nearest();
			final int r = snapRadius();

			// Actors first (NPCs then players) — on a distance tie an actor outranks scenery.
			for (Actor a : wv.npcs())
			{
				consider(a.getConvexHull(), x, y, r, near);
			}
			for (Actor a : wv.players())
			{
				consider(a.getConvexHull(), x, y, r, near);
			}

			// Scene objects on the current plane.
			final Scene scene = wv.getScene();
			final int plane = wv.getPlane();
			final Tile[][][] tiles = scene == null ? null : scene.getTiles();
			if (tiles != null && plane >= 0 && plane < tiles.length)
			{
				for (Tile[] row : tiles[plane])
				{
					if (row == null)
					{
						continue;
					}
					for (Tile tile : row)
					{
						if (tile == null)
						{
							continue;
						}
						final GameObject[] gos = tile.getGameObjects();
						if (gos != null)
						{
							for (GameObject go : gos)
							{
								if (go != null)
								{
									consider(go.getClickbox(), x, y, r, near);
								}
							}
						}
						considerObj(tile.getWallObject(), x, y, r, near);
						considerObj(tile.getGroundObject(), x, y, r, near);
						considerObj(tile.getDecorativeObject(), x, y, r, near);
					}
				}
			}

			if (near.exact)
			{
				log.debug("[snap] tap {},{} -> exact-hit, no snap (considered={})", x, y, near.considered);
				return original;
			}
			if (near.shape != null)
			{
				final int[] cen = hullCentroid(near.shape);
				snapCount++;
				log.debug("[snap] tap {},{} -> SNAP {},{} dist={}px (considered={} nearestAny={}px radius={} #{})",
					x, y, cen[0], cen[1], (int) near.dist, near.considered,
					(int) (near.nearestAny == Double.MAX_VALUE ? -1 : near.nearestAny), snapRadius(), snapCount);
				return cen;
			}
			// No snap: at debug only (the device-tuning round is done; the scene is clickbox-saturated so
			// "nearestAny" is ~always 0 and snap-to-nearest is near-redundant — priority, not radius, is the
			// real lever if we revisit forgiveness).
			log.debug("[snap] tap {},{} -> none (considered={} nearestAny={}px radius={})",
				x, y, near.considered,
				(int) (near.nearestAny == Double.MAX_VALUE ? -1 : near.nearestAny), snapRadius());
		}
		catch (RuntimeException ex)
		{
			// any scene-read hiccup -> fall back to the literal tap (never drop the click)
			log.debug("[bridge] snap resolve failed, using literal point", ex);
		}
		return original;
	}

	/** {@link TileObject} overload of {@link #consider(Shape, int, int, int, Nearest)} (uses its clickbox). */
	private static void considerObj(net.runelite.api.TileObject o, int x, int y, int r, Nearest near)
	{
		if (o != null)
		{
			consider(o.getClickbox(), x, y, r, near);
		}
	}

	/** Fold one candidate clickbox into {@code near} (tracks exact-hit, nearest-within-radius, diagnostics). */
	private static void consider(Shape s, int x, int y, int r, Nearest near)
	{
		if (s == null)
		{
			return;
		}
		if (s.contains(x, y))
		{
			near.exact = true;
			return;
		}
		final Rectangle b = s.getBounds();
		if (b.width <= 0 || b.height <= 0)
		{
			return;
		}
		final double d = distToRect(x, y, b);
		near.considered++;
		if (d < near.nearestAny)
		{
			near.nearestAny = d;
		}
		if (d <= r && d < near.dist)
		{
			near.dist = d;
			near.shape = s;
		}
	}

	/** Centroid of a convex hull's vertices — guaranteed inside the (convex) shape, unlike the bbox centre. */
	private static int[] hullCentroid(Shape hull)
	{
		final java.awt.geom.PathIterator it = hull.getPathIterator(null);
		final double[] coords = new double[6];
		double sx = 0;
		double sy = 0;
		int n = 0;
		while (!it.isDone())
		{
			final int seg = it.currentSegment(coords);
			if (seg == java.awt.geom.PathIterator.SEG_MOVETO || seg == java.awt.geom.PathIterator.SEG_LINETO)
			{
				sx += coords[0];
				sy += coords[1];
				n++;
			}
			it.next();
		}
		if (n == 0)
		{
			final Rectangle b = hull.getBounds();
			return new int[]{(int) b.getCenterX(), (int) b.getCenterY()};
		}
		return new int[]{(int) (sx / n), (int) (sy / n)};
	}

	/** Euclidean distance from a point to a rectangle (0 if inside). */
	private static double distToRect(int px, int py, Rectangle b)
	{
		final int dx = Math.max(Math.max(b.x - px, px - (b.x + b.width)), 0);
		final int dy = Math.max(Math.max(b.y - py, py - (b.y + b.height)), 0);
		return Math.sqrt((double) dx * dx + (double) dy * dy);
	}

	/** Finger radius in game-canvas pixels: how far from an entity a tap may land and still snap onto it. */
	public static int snapRadius()
	{
		return SNAP_RADIUS;
	}

	/**
	 * Move the pointer to {@code (x,y)} with no button held — a plain {@code MOUSE_MOVED}. This is the
	 * "hover" primitive: it updates the gamepack's recorded mouse position so the open right-click menu
	 * highlights the entry under the finger as it slides. Distinct from a drag (which holds a button and
	 * posts {@code MOUSE_DRAGGED} via {@link #onTouch}) so the menu-track gesture can't be confused for one.
	 */
	public static void onMove(int x, int y)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t + 1, 0, x, y, 0, false));
	}

	/**
	 * Scroll the wheel by {@code rotation} notches at {@code (x,y)} — a {@link MouseWheelEvent}, which
	 * {@code Hooks.mouseWheelMoved} routes through {@code mouseManager} exactly like a {@link MouseEvent}.
	 * Pinch-zoom maps to this: negative {@code rotation} = wheel up = zoom in. {@code (x,y)} is the pinch
	 * centroid so the gamepack zooms about it.
	 */
	public static void onWheel(int x, int y, int rotation)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null || rotation == 0)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		primeFocus(q, canvas, t, x, y);
		q.postEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL, t + 1, 0, x, y, 0, false,
			MouseWheelEvent.WHEEL_UNIT_SCROLL, Math.abs(rotation), rotation));
	}

	/**
	 * Rotate the camera by a two-finger pan: apply the raw pixel delta {@code (dxPixels,dyPixels)} to the
	 * camera yaw/pitch <em>targets</em> directly via the client API (no synthesized middle-mouse, so it is
	 * independent of the "middle mouse moves camera" setting). Yaw wraps mod 2048; pitch clamps to the
	 * normal RS range. Only acts in-game. The native side sends raw pixel deltas; sensitivity + sub-pixel
	 * accumulation live here.
	 */
	public static void onCameraDrag(int dxPixels, int dyPixels)
	{
		final Client c = ENABLED ? client : null;
		if (c == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final float yawF = dxPixels * YAW_UNITS_PER_PX + yawRemainder;
		final int yawStep = (int) yawF;
		yawRemainder = yawF - yawStep;
		if (yawStep != 0)
		{
			c.setCameraYawTarget((c.getCameraYawTarget() + yawStep) & 2047);
		}

		final float pitchF = dyPixels * PITCH_UNITS_PER_PX + pitchRemainder;
		final int pitchStep = (int) pitchF;
		pitchRemainder = pitchF - pitchStep;
		if (pitchStep != 0)
		{
			// Drag down -> camera tilts down (pitch decreases toward the horizon).
			int p = c.getCameraPitchTarget() - pitchStep;
			p = Math.max(PITCH_MIN, Math.min(PITCH_MAX, p));
			c.setCameraPitchTarget(p);
		}
	}

	/**
	 * Type committed text — for each character post {@code KEY_PRESSED} + {@code KEY_TYPED} +
	 * {@code KEY_RELEASED}. The gamepack's text fields (chat, search, username/password) consume the
	 * {@code KEY_TYPED} char, which carries the exact character (and case) the iOS keyboard committed.
	 * Called from the hidden {@code UITextField}'s change callback, so autocorrect/paste arrive as one
	 * coherent string.
	 */
	public static void onText(String s)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null || s == null || s.isEmpty())
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		long t = System.currentTimeMillis();
		ensureFocus(q, canvas);
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			final int vk = KeyEvent.getExtendedKeyCodeForChar(c);
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t, 0, vk, c));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, t + 1, 0, KeyEvent.VK_UNDEFINED, c));
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t + 2, 0, vk, c));
			t += 3;
		}
	}

	/**
	 * Whether the gamepack currently has a text field open and awaiting input — a search box (bank/GE/world
	 * map), a private-message recipient, an "enter amount/name" dialog, etc. ({@code VarClientInt.INPUT_TYPE}
	 * is non-zero). The native shell polls this to auto-raise the soft keyboard when the game asks for text
	 * and dismiss it when the field closes, so typing is summoned from inside the game, not a chrome button.
	 */
	public static boolean isTextInputOpen()
	{
		final Client c = ENABLED ? client : null;
		if (c == null)
		{
			return false;
		}
		try
		{
			return c.getVarcIntValue(VarClientInt.INPUT_TYPE) != 0;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * Press + release one non-text key by its AWT virtual-keycode ({@code KeyEvent.VK_*}) — backspace,
	 * enter, escape, tab, arrows, etc. Backspace/enter/tab also emit the matching {@code KEY_TYPED} control
	 * char (a gamepack text field deletes/submits on those). Posted from the hidden field's delete/return
	 * callbacks and any on-screen key.
	 */
	public static void onKeyStroke(int vk)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}
		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		ensureFocus(q, canvas);
		char kc = KeyEvent.CHAR_UNDEFINED;
		if (vk == KeyEvent.VK_BACK_SPACE)
		{
			kc = '\b';
		}
		else if (vk == KeyEvent.VK_ENTER)
		{
			kc = '\n';
		}
		else if (vk == KeyEvent.VK_TAB)
		{
			kc = '\t';
		}
		q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED, t, 0, vk, kc));
		if (kc != KeyEvent.CHAR_UNDEFINED)
		{
			q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, t + 1, 0, KeyEvent.VK_UNDEFINED, kc));
		}
		q.postEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, t + 2, 0, vk, kc));
	}

	/**
	 * One phase of a drag stream — {@link #TOUCH_DOWN}/{@link #TOUCH_MOVE}/{@link #TOUCH_UP} at
	 * {@code (x,y)}, {@code button} 1 or 3. DOWN presses, MOVE drags (or hovers when not pressed), UP
	 * releases — with NO synthesized click (unlike a tap, which goes through {@link #onClick}). Used by the
	 * camera / scroll gestures, where a release must not register as a walk-here click.
	 */
	public static void onTouch(int type, int x, int y, int button)
	{
		final Canvas canvas = liveCanvas();
		if (canvas == null)
		{
			return;
		}

		final EventQueue q = Toolkit.getDefaultToolkit().getSystemEventQueue();
		final long t = System.currentTimeMillis();
		final int awtButton = button == 3 ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1;
		final int downMask = awtButton == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;

		switch (type)
		{
			case TOUCH_DOWN:
				primeFocus(q, canvas, t, x, y);
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_PRESSED, t + 1,
					downMask, x, y, 1, false, awtButton));
				pressed = true;
				pressButton = awtButton;
				break;

			case TOUCH_MOVE:
				if (pressed)
				{
					q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, t,
						pressButton == MouseEvent.BUTTON3 ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK,
						x, y, 0, false, MouseEvent.NOBUTTON));
				}
				else
				{
					q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, t, 0, x, y, 0, false));
				}
				break;

			case TOUCH_UP:
				// Release only — a drag is NOT a click. (The pre-Phase-5 auto-MOUSE_CLICKED here was the
				// phantom-click bug: every camera/scroll drag ended with a stray walk-here click.)
				q.postEvent(new MouseEvent(canvas, MouseEvent.MOUSE_RELEASED, t,
					0, x, y, 1, false, pressed ? pressButton : awtButton));
				pressed = false;
				break;

			default:
				log.warn("[bridge] onTouch unknown type {}", type);
				break;
		}
	}
}
