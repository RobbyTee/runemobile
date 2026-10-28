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

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;

/**
 * Large portrait: a phone in portrait draws the game {@code W} px wide ({@code W < 765}, 600 on a phone), so the world,
 * the inventory, the tab buttons and every window show {@code 765/W} times bigger, while the chat and the minimap keep
 * the size they have on a 765-wide canvas.
 *
 * <p>The shell makes the canvas {@link #STRIP_H} px taller than the screen and shows only its top {@code visibleH}
 * rows. Each frame, on the client thread:
 * <ol>
 *   <li>{@link #pin} holds the game view ({@code VIEWPORT}, which confines the 3D viewport) and the two HUD trackers to
 *   the visible rows, puts a filled {@link #KEY} rectangle over the strip below them, and pins {@code MAINMODAL} and
 *   {@code FLOATER} into the band between the chat and the inventory, full width, so no window is clipped;</li>
 *   <li>{@link HudVeneerBridge} pins the chat and the minimap at their full size into the strip
 *   ({@link #chatSlotY}, {@link #mapSlotY});</li>
 *   <li>{@link #composite} copies them back up into the frame, scaled by {@code W/765}, to where the shell asked for
 *   them, with the key rectangle see-through. It runs at {@code postComposite}, after the overlays and before both the
 *   CPU frame snapshot and the GPU and 117HD interface upload (the buffer it writes is the interface layer they
 *   draw), so all three renderers show it.</li>
 * </ol>
 *
 * <p>The shells work in <em>shown</em> coordinates only. A point the shell sends is moved into the strip by
 * {@link #toEngine} when it lands on a copy, and a rect the bridge publishes is moved out by {@link #toShown}. While
 * the engine's menu is open over the strip, {@link #shownMenu} slides it up to the finger instead.
 *
 * <p>Off (identity everywhere) until a shell calls {@code HudVeneerBridge.setHudStrip}, and only on Resizable - Modern
 * (164) with a canvas narrower than 765 and at least {@link #STRIP_H} rows below the visible ones.
 */
@Slf4j
final class HudStrip
{
	/** The canvas width the chat and the minimap are sized for on screen; the copies scale by {@code W/765}. */
	static final int DESIGN_W = 765;
	/** The rows a shell adds below the screen. The chat slot, the minimap (207 tall) and the margins need 396. */
	static final int STRIP_H = 400;
	/** The strip's fill. Copied pixels of exactly this colour are see-through. */
	static final int KEY = 0x010203;
	// The margin around each slot, and the chat's slot height: the desktop chatbox's 165, so the official 145-tall
	// chat and anything drawn just past it fit.
	private static final int PAD = 8;
	private static final int CHAT_SLOT_H = 165;
	// Slack around a widget's bounds when copying it: the chat's scrollbar sits at x=-1.
	private static final int EDGE = 2;

	private static final int VIEWPORT = InterfaceID.ToplevelPreEoc.VIEWPORT;                  // 164:88
	private static final int TRACKER_BACK = InterfaceID.ToplevelPreEoc.VIEWPORT_TRACKER_BACK; // 164:89
	private static final int TRACKER_FRONT = InterfaceID.ToplevelPreEoc.VIEWPORT_TRACKER_FRONT; // 164:91

	// Set from native.
	private static volatile int visibleH;
	private static volatile int bandTop;
	private static volatile int bandBottom;

	// Client thread only.
	private static boolean on;
	private static boolean loggedOn;
	private static final Map<Integer, Saved> saved = new HashMap<>();

	/** Where the copies are, published each frame by {@link #composite}; null = off (identity). */
	private static volatile Layout layout;
	/** {@code W/765} while the strip is laid out, else 1: how much smaller than drawn the copies show. */
	private static volatile float shownScale = 1f;
	/** The copy a drag stream started on, {src, dst}; {@link #NO_COPY} when it started off both; null between drags. */
	private static volatile int[][] drag;
	private static final int[][] NO_COPY = new int[0][];
	/** The open engine menu's shift into view, {dx, dy, shown x, y, w, h}; null when no menu is shifted. */
	private static volatile int[] menuShift;

	private HudStrip()
	{
	}

	/** One frame's copies: each {@code src} (strip, engine px) shows at {@code dst} (screen rows, canvas px). */
	static final class Layout
	{
		final int[] chatSrc, chatDst, mapSrc, mapDst;

		Layout(int[] chatSrc, int[] chatDst, int[] mapSrc, int[] mapDst)
		{
			this.chatSrc = chatSrc;
			this.chatDst = chatDst;
			this.mapSrc = mapSrc;
			this.mapDst = mapDst;
		}
	}

	/** A widget's own layout before the first pin, so turning the strip off can give it back. */
	private static final class Saved
	{
		final int xMode, yMode, wMode, hMode, x, y, w, h;

		Saved(Widget wd)
		{
			xMode = wd.getXPositionMode();
			yMode = wd.getYPositionMode();
			wMode = wd.getWidthMode();
			hMode = wd.getHeightMode();
			x = wd.getOriginalX();
			y = wd.getOriginalY();
			w = wd.getOriginalWidth();
			h = wd.getOriginalHeight();
		}
	}

	/** Native, via {@code HudVeneerBridge.setHudStrip}. {@code visibleHeight <= 0} turns it off. */
	static void set(int visibleHeight, int windowTop, int windowBottom)
	{
		bandTop = windowTop;
		bandBottom = windowBottom;
		visibleH = Math.max(0, visibleHeight);
	}

	/** True while the strip is laid out. Client thread; settled by {@link #pin} before anything is drawn. */
	static boolean isOn()
	{
		return on;
	}

	/** The canvas rows that reach the screen: the visible ones while the strip is laid out, else all of them. */
	static int shownRows(int canvasH)
	{
		return on ? Math.min(canvasH, visibleH) : canvasH;
	}

	/** The chat container's top-left in the strip. Client thread, while {@link #isOn}. */
	static int chatSlotY()
	{
		return visibleH + PAD;
	}

	/** The minimap cluster's top-left in the strip. Client thread, while {@link #isOn}. */
	static int mapSlotY()
	{
		return visibleH + PAD + CHAT_SLOT_H + PAD;
	}

	static int slotX()
	{
		return PAD;
	}

	/** How much smaller the copies show than the engine draws them: {@code W/765} while on, else 1. Any thread. */
	static float shownScale()
	{
		return shownScale;
	}

	/** The copies as of the last frame, or null. Any thread. */
	static Layout layout()
	{
		return layout;
	}

	// ---- Layout (client thread, BeforeRender) -------------------------------------------------------------------

	/** Logged out: the toplevel and the key rectangle are gone with it, so only forget them. Client thread. */
	static void loggedOut()
	{
		if (on || !saved.isEmpty())
		{
			on = false;
			saved.clear();
			layout = null;
			menuShift = null;
			shownScale = 1f;
			loggedOn = false;
		}
	}

	/**
	 * Lay the strip out, or give the widgets back once it is no longer wanted. Run from {@code BeforeRender}, the last
	 * word before the frame rasters, and a no-op per widget once it sits where it is pinned. While the world map is
	 * open the HUD is hidden, so its window takes the whole visible screen instead of the band.
	 */
	static void pin(Client c, boolean preEoc, boolean mapOpen)
	{
		final int vis = visibleH;
		final int w = c.getCanvasWidth();
		final int h = c.getCanvasHeight();
		final boolean want = vis > 0 && preEoc && w > 0 && w < DESIGN_W && h - vis >= STRIP_H;
		if (want != loggedOn)
		{
			loggedOn = want;
			log.info("[strip] {} (canvas {}x{}, visible {}, windows {}..{})", want ? "on" : "off", w, h, vis,
				bandTop, bandBottom);
		}
		if (!want)
		{
			if (on)
			{
				restore(c);
			}
			on = false;
			layout = null;
			shownScale = 1f;
			return;
		}
		on = true;
		shownScale = w / (float) DESIGN_W;
		// No band from the shell (or an empty one) leaves the windows the whole visible screen.
		int top = Math.max(0, Math.min(bandTop, vis));
		int bottom = Math.min(bandBottom, vis);
		if (bottom <= top || mapOpen)
		{
			top = 0;
			bottom = vis;
		}
		// Parents first: each place re-lays its own subtree.
		place(c, TRACKER_BACK, 0, 0, w, h);                // holds the key rectangle, so it spans the strip
		place(c, TRACKER_FRONT, 0, 0, w, vis);
		place(c, InterfaceID.ToplevelPreEoc.HUD_CONTAINER_BACK, 0, 0, w, vis);
		place(c, VIEWPORT, 0, 0, w, vis);                  // the 3D viewport follows it
		// The windows' parent. It ends where the band does, so what anchors to its bottom (the engine's HUD and the
		// bottom snap corners) stays above the inventory, as the engine's own side-panel reserve keeps it.
		place(c, InterfaceID.ToplevelPreEoc.HUD_CONTAINER_FRONT, 0, 0, w, bottom);
		place(c, InterfaceID.ToplevelPreEoc.MAINMODAL, 0, top, w, bottom - top);
		place(c, InterfaceID.ToplevelPreEoc.FLOATER, 0, top, w, bottom - top);
		keyRect(c, w, vis, h);
	}

	/** Force {@code id} to an absolute rect and re-lay its subtree, unless it already sits there. */
	private static void place(Client c, int id, int x, int y, int w, int h)
	{
		final Widget wd = c.getWidget(id);
		if (wd == null)
		{
			return;
		}
		if (wd.getXPositionMode() == 0 && wd.getYPositionMode() == 0 && wd.getWidthMode() == 0
			&& wd.getHeightMode() == 0 && wd.getOriginalX() == x && wd.getOriginalY() == y
			&& wd.getOriginalWidth() == w && wd.getOriginalHeight() == h
			&& wd.getRelativeX() == x && wd.getRelativeY() == y && wd.getWidth() == w && wd.getHeight() == h)
		{
			return;
		}
		saved.computeIfAbsent(id, k -> new Saved(wd));
		wd.setXPositionMode(0).setYPositionMode(0).setWidthMode(0).setHeightMode(0);
		wd.setOriginalX(x).setOriginalY(y).setOriginalWidth(w).setOriginalHeight(h);
		// revalidateScroll, not revalidate: revalidate leaves the children laid out for the old size.
		wd.revalidateScroll();
	}

	/** The strip's fill: a dynamic child of the back tracker, which draws before the minimap and the chat. */
	private static void keyRect(Client c, int w, int vis, int h)
	{
		final Widget parent = c.getWidget(TRACKER_BACK);
		if (parent == null)
		{
			return;
		}
		Widget key = findKey(parent);
		if (key == null)
		{
			key = parent.createChild(-1, WidgetType.RECTANGLE);
			key.setFilled(true);
			key.setTextColor(KEY);
			key.setOpacity(0);
			key.setXPositionMode(0).setYPositionMode(0).setWidthMode(0).setHeightMode(0);
		}
		if (key.isSelfHidden())
		{
			key.setHidden(false);
		}
		if (key.getOriginalX() != 0 || key.getOriginalY() != vis || key.getOriginalWidth() != w
			|| key.getOriginalHeight() != h - vis || key.getRelativeY() != vis || key.getWidth() != w)
		{
			key.setOriginalX(0).setOriginalY(vis).setOriginalWidth(w).setOriginalHeight(h - vis);
			key.revalidate();
		}
	}

	private static Widget findKey(Widget parent)
	{
		final Widget[] kids = parent.getDynamicChildren();
		if (kids != null)
		{
			for (Widget k : kids)
			{
				if (k != null && k.getType() == WidgetType.RECTANGLE && k.getTextColor() == KEY && k.isFilled())
				{
					return k;
				}
			}
		}
		return null;
	}

	/** Give every pinned widget its own layout back and hide the key. */
	private static void restore(Client c)
	{
		for (Map.Entry<Integer, Saved> e : saved.entrySet())
		{
			final Widget wd = c.getWidget(e.getKey());
			if (wd == null)
			{
				continue;
			}
			final Saved s = e.getValue();
			wd.setXPositionMode(s.xMode).setYPositionMode(s.yMode).setWidthMode(s.wMode).setHeightMode(s.hMode);
			wd.setOriginalX(s.x).setOriginalY(s.y).setOriginalWidth(s.w).setOriginalHeight(s.h);
			wd.revalidateScroll();
		}
		saved.clear();
		final Widget parent = c.getWidget(TRACKER_BACK);
		final Widget key = parent == null ? null : findKey(parent);
		if (key != null)
		{
			key.setHidden(true);
		}
		menuShift = null;
		log.info("[strip] widgets restored");
	}

	// ---- The copy (client thread, postComposite) ---------------------------------------------------------------

	/**
	 * Copy the chat and the minimap from the strip to where the shell shows them, scaled by {@code W/765}, and
	 * publish where they went for the coordinate maps. {@code (chatX, chatY)} and {@code (mapX, mapY)} are the shown
	 * top-left corners the shell asked for ({@code < 0} = not shown).
	 */
	static void composite(Client c, int[] px, int w, int h, int chatX, int chatY, int mapX, int mapY, boolean mapOpen)
	{
		if (!on || mapOpen || px == null || w != c.getCanvasWidth() || h != c.getCanvasHeight() || px.length < w * h)
		{
			layout = null;
			return;
		}
		final int vis = visibleH;
		final float k = w / (float) DESIGN_W;
		final boolean gpu = c.isGpu();
		int[] chatSrc = null, chatDst = null, mapSrc = null, mapDst = null;
		final Widget chat = chatX >= 0 ? c.getWidget(InterfaceID.ToplevelPreEoc.CHAT_CONTAINER) : null;
		if (chat != null && !chat.isHidden())
		{
			chatSrc = source(chat, vis, w, h);
			chatDst = chatSrc == null ? null : dest(chatSrc, chat, chatX, chatY, k);
		}
		final Widget map = mapX >= 0 ? c.getWidget(InterfaceID.ToplevelPreEoc.MAP_CONTAINER) : null;
		if (map != null && !map.isHidden())
		{
			mapSrc = source(map, vis, w, h);
			mapDst = mapSrc == null ? null : dest(mapSrc, map, mapX, mapY, k);
		}
		if (chatSrc != null)
		{
			copyScaled(px, w, vis, chatSrc, chatDst, gpu);
		}
		if (mapSrc != null)
		{
			copyScaled(px, w, vis, mapSrc, mapDst, gpu);
		}
		layout = chatSrc == null && mapSrc == null ? null : new Layout(chatSrc, chatDst, mapSrc, mapDst);
	}

	/** {@code w}'s bounds with a little slack, kept inside the strip; null unless it sits in the strip. */
	private static int[] source(Widget wd, int vis, int canvasW, int canvasH)
	{
		final java.awt.Rectangle r = wd.getBounds();
		if (r == null || r.width <= 0 || r.height <= 0 || r.y < vis)
		{
			return null;
		}
		final int x0 = Math.max(0, r.x - EDGE);
		final int y0 = Math.max(vis, r.y - EDGE);
		final int x1 = Math.min(canvasW, r.x + r.width + EDGE);
		final int y1 = Math.min(canvasH, r.y + r.height + EDGE);
		return x1 > x0 && y1 > y0 ? new int[]{x0, y0, x1 - x0, y1 - y0} : null;
	}

	/** Where {@code src} shows: the widget's own corner lands on {@code (x, y)}, and everything scales by {@code k}. */
	private static int[] dest(int[] src, Widget wd, int x, int y, float k)
	{
		final java.awt.Rectangle r = wd.getBounds();
		final int dx = x + Math.round((src[0] - r.x) * k);
		final int dy = y + Math.round((src[1] - r.y) * k);
		return new int[]{dx, dy, Math.max(1, Math.round(src[2] * k)), Math.max(1, Math.round(src[3] * k))};
	}

	/**
	 * Area-average {@code src} down into {@code dst} over what is already there. Pixels of {@link #KEY} are
	 * see-through; edges blend. The buffer is opaque RGB on the CPU renderer and premultiplied ARGB on GPU and 117HD
	 * (both draw it with {@code GL_ONE, GL_ONE_MINUS_SRC_ALPHA}), so the blend is premultiplied either way and only the
	 * source alpha differs. Rows at or below {@code visH} are never written; {@code src} lives there.
	 */
	static void copyScaled(int[] px, int w, int visH, int[] src, int[] dst, boolean gpu)
	{
		final int sw = src[2], sh = src[3], dw = dst[2], dh = dst[3];
		if (sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0)
		{
			return;
		}
		final int[][] xs = spans(sw, dw);
		final int[][] ys = spans(sh, dh);
		for (int j = 0; j < dh; j++)
		{
			final int ty = dst[1] + j;
			if (ty < 0 || ty >= visH)
			{
				continue;
			}
			final int[] yw = ys[j];
			final int sy0 = src[1] + yw[0];
			for (int i = 0; i < dw; i++)
			{
				final int tx = dst[0] + i;
				if (tx < 0 || tx >= w)
				{
					continue;
				}
				final int[] xw = xs[i];
				final int sx0 = src[0] + xw[0];
				long a = 0, r = 0, g = 0, b = 0;
				for (int v = 1; v < yw.length; v++)
				{
					final int row = (sy0 + v - 1) * w;
					final int wy = yw[v];
					for (int u = 1; u < xw.length; u++)
					{
						final int p = px[row + sx0 + u - 1];
						if ((p & 0xffffff) == KEY)
						{
							continue;
						}
						final int wt = wy * xw[u];
						a += (long) (gpu ? p >>> 24 : 0xff) * wt;
						r += (long) ((p >> 16) & 0xff) * wt;
						g += (long) ((p >> 8) & 0xff) * wt;
						b += (long) (p & 0xff) * wt;
					}
				}
				final int sa = (int) ((a + HALF) >> SHIFT2);
				if (sa == 0)
				{
					continue;
				}
				final int sr = (int) ((r + HALF) >> SHIFT2);
				final int sg = (int) ((g + HALF) >> SHIFT2);
				final int sb = (int) ((b + HALF) >> SHIFT2);
				final int at = ty * w + tx;
				final int d = px[at];
				final int inv = 255 - sa;
				final int or = Math.min(255, sr + mul(d >> 16 & 0xff, inv));
				final int og = Math.min(255, sg + mul(d >> 8 & 0xff, inv));
				final int ob = Math.min(255, sb + mul(d & 0xff, inv));
				final int oa = gpu ? Math.min(255, sa + mul(d >>> 24, inv)) : d >>> 24;
				px[at] = oa << 24 | or << 16 | og << 8 | ob;
			}
		}
	}

	// Each axis weight is out of 1 << SHIFT, so a tap's weight is out of 1 << SHIFT2.
	private static final int SHIFT = 8;
	private static final int SHIFT2 = SHIFT * 2;
	private static final long HALF = 1L << (SHIFT2 - 1);

	private static int mul(int c, int a)
	{
		final int t = c * a + 128;
		return (t + (t >> 8)) >> 8;
	}

	/**
	 * For each of {@code dn} destination pixels along one axis: {@code [first source index, weight...]}, the
	 * weights being the share of each source pixel the destination pixel covers, out of {@code 1 << SHIFT}.
	 */
	static int[][] spans(int sn, int dn)
	{
		final int[][] out = new int[dn][];
		final double step = sn / (double) dn;
		final int one = 1 << SHIFT;
		for (int i = 0; i < dn; i++)
		{
			final double lo = i * step;
			final double hi = Math.min(sn, (i + 1) * step);
			final int first = (int) Math.floor(lo);
			final int last = Math.min(sn - 1, (int) Math.ceil(hi) - 1);
			final int n = Math.max(1, last - first + 1);
			final int[] s = new int[n + 1];
			s[0] = first;
			int sum = 0;
			for (int q = 0; q < n; q++)
			{
				final double cover = Math.min(hi, first + q + 1) - Math.max(lo, first + q);
				s[q + 1] = (int) Math.round(Math.max(0, cover) / step * one);
				sum += s[q + 1];
			}
			// Rounding leaves the sum a little off; put the difference on the biggest share.
			int big = 1;
			for (int q = 2; q <= n; q++)
			{
				if (s[q] > s[big])
				{
					big = q;
				}
			}
			s[big] += one - sum;
			out[i] = s;
		}
		return out;
	}

	// ---- Coordinate maps (any thread) ---------------------------------------------------------------------------

	/** A shown canvas point, moved into the strip when it lands on a copy; packed {@code x << 32 | y}. */
	static long toEngine(int x, int y)
	{
		final Layout l = layout;
		if (l != null)
		{
			if (inside(l.chatDst, x, y))
			{
				return inverse(l.chatSrc, l.chatDst, x, y);
			}
			if (inside(l.mapDst, x, y))
			{
				return inverse(l.mapSrc, l.mapDst, x, y);
			}
		}
		return pack(x, y);
	}

	/**
	 * The first point of a drag stream ({@code JavaBridge.onTouch}): maps as {@link #toEngine} does, and the copy it
	 * lands on (or none) holds for the rest of the stream, so a chat scrollbar dragged past the copy's edge keeps
	 * scrolling instead of jumping out of the strip.
	 */
	static long dragStart(int x, int y)
	{
		final Layout l = layout;
		int[][] m = NO_COPY;
		if (l != null && inside(l.chatDst, x, y))
		{
			m = new int[][]{l.chatSrc, l.chatDst};
		}
		else if (l != null && inside(l.mapDst, x, y))
		{
			m = new int[][]{l.mapSrc, l.mapDst};
		}
		drag = m;
		return m == NO_COPY ? pack(x, y) : inverse(m[0], m[1], x, y);
	}

	/** A later point of the drag {@link #dragStart} began; {@code end} closes it. Off the copy it extrapolates. */
	static long dragTo(int x, int y, boolean end)
	{
		final int[][] m = drag;
		if (end)
		{
			drag = null;
		}
		if (m == null)
		{
			return toEngine(x, y);
		}
		if (m == NO_COPY)
		{
			return pack(x, y);
		}
		final int[] src = m[0], dst = m[1];
		return pack(src[0] + (int) Math.floor((x - dst[0] + 0.5) * src[2] / dst[2]),
			src[1] + (int) Math.floor((y - dst[1] + 0.5) * src[3] / dst[3]));
	}

	static int x(long p)
	{
		return (int) (p >> 32);
	}

	static int y(long p)
	{
		return (int) p;
	}

	private static long pack(int x, int y)
	{
		return ((long) x << 32) | (y & 0xffffffffL);
	}

	private static long inverse(int[] src, int[] dst, int x, int y)
	{
		final int ex = src[0] + (int) ((x - dst[0] + 0.5) * src[2] / dst[2]);
		final int ey = src[1] + (int) ((y - dst[1] + 0.5) * src[3] / dst[3]);
		return pack(Math.min(src[0] + src[2] - 1, ex), Math.min(src[1] + src[3] - 1, ey));
	}

	/**
	 * A published {@code [x, y, w, h]} as the player sees it: moved out of the strip when its centre is on the chat
	 * or the minimap there, else returned as is. Null stays null.
	 */
	static int[] toShown(int[] b)
	{
		final Layout l = layout;
		if (b == null || b.length < 4 || l == null)
		{
			return b;
		}
		final int cx = b[0] + b[2] / 2, cy = b[1] + b[3] / 2;
		if (inside(l.chatSrc, cx, cy))
		{
			return forward(l.chatSrc, l.chatDst, b);
		}
		if (inside(l.mapSrc, cx, cy))
		{
			return forward(l.mapSrc, l.mapDst, b);
		}
		return b;
	}

	private static int[] forward(int[] src, int[] dst, int[] b)
	{
		final double kx = dst[2] / (double) src[2], ky = dst[3] / (double) src[3];
		final int x0 = dst[0] + (int) Math.round((b[0] - src[0]) * kx);
		final int y0 = dst[1] + (int) Math.round((b[1] - src[1]) * ky);
		final int x1 = dst[0] + (int) Math.round((b[0] + b[2] - src[0]) * kx);
		final int y1 = dst[1] + (int) Math.round((b[1] + b[3] - src[1]) * ky);
		final int[] out = b.clone();
		out[0] = x0;
		out[1] = y0;
		out[2] = Math.max(1, x1 - x0);
		out[3] = Math.max(1, y1 - y0);
		return out;
	}

	private static boolean inside(int[] r, int x, int y)
	{
		return r != null && x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
	}

	// ---- The engine menu --------------------------------------------------------------------------------------

	/**
	 * The engine menu's rect as the shells should place it, {@code [x, y, w, h]}. A menu opened on a copy opens in
	 * the strip, out of sight; it slides by the same offset that moves the pressed point back to the finger, then into
	 * the visible rows. The offset holds until the menu closes, and {@link #menuToEngine} takes it back off the
	 * points the shell selects with. Client thread; {@code open == false} drops the shift.
	 */
	static int[] shownMenu(boolean open, int x, int y, int w, int h, int mouseX, int mouseY, int canvasW)
	{
		if (!open)
		{
			menuShift = null;
			return new int[]{x, y, w, h};
		}
		int[] s = menuShift;
		if (s == null)
		{
			final Layout l = layout;
			final int vis = visibleH;
			if (l == null || y + h <= vis)
			{
				return new int[]{x, y, w, h};
			}
			int dx = 0, dy = 0;
			final int[] at = toShown(new int[]{mouseX, mouseY, 1, 1});
			if (at[0] != mouseX || at[1] != mouseY)
			{
				dx = at[0] - mouseX;
				dy = at[1] - mouseY;
			}
			dx = clampShift(x, w, dx, canvasW);
			dy = clampShift(y, h, dy, vis);
			s = new int[]{dx, dy, x + dx, y + dy, w, h};
			menuShift = s;
			log.info("[strip] menu {}x{} at ({},{}) shown at ({},{})", w, h, x, y, x + dx, y + dy);
		}
		return new int[]{x + s[0], y + s[1], w, h};
	}

	/** {@code d}, adjusted so {@code [p + d, p + d + n)} sits inside {@code [0, limit)} where it can. */
	private static int clampShift(int p, int n, int d, int limit)
	{
		final int to = Math.max(0, Math.min(limit - n, p + d));
		return to - p;
	}

	/**
	 * A shown point on the open, shifted menu, back where the engine has it. A point off the shown menu stays as is
	 * (it closes the menu), so it can never land on the menu's real, unseen rect. Any thread.
	 */
	static long menuToEngine(int x, int y)
	{
		final int[] s = menuShift;
		if (s == null || x < s[2] || y < s[3] || x >= s[2] + s[4] || y >= s[3] + s[5])
		{
			return toEngine(x, y);
		}
		return pack(x - s[0], y - s[1]);
	}

	/** For tests: publish a layout without a client. */
	static void testLayout(Layout l, int vis)
	{
		layout = l;
		visibleH = vis;
		menuShift = null;
		drag = null;
	}
}
