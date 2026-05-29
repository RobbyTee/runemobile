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
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.MainBufferProvider;

/**
 * Gate 1c instrumentation for the headless RuneLite render seam.
 *
 * <p>This is the decisive settling experiment for the "kill risk": does the real OSRS engine drive
 * {@code Hooks.draw} with a non-null {@link Graphics} when there is no real Swing window, and does the
 * composited frame actually land in {@code mainBufferProvider}? Two gated hooks are wired into
 * {@code net.runelite.client.callback.Hooks#draw}:
 * <ul>
 *   <li>{@link #onDraw(Graphics)} at the top of the callback — counts every invocation and how many
 *       arrive with a null Graphics (the engine's "no realized canvas" signal).</li>
 *   <li>{@link #capture(MainBufferProvider)} after the overlays composite — periodically dumps the
 *       engine buffer to PNG via the toolkit-independent {@code int[]} pixels.</li>
 * </ul>
 *
 * <p>Entirely inert unless {@code -Drunelite.headless.capture.dir=<dir>} is set, so it adds nothing to
 * the stock desktop client beyond a method call that returns immediately.
 */
@Slf4j
public final class HeadlessCapture
{
	private static final String DIR_PROP = "runelite.headless.capture.dir";
	private static final String INTERVAL_PROP = "runelite.headless.capture.interval";
	private static final String MAX_PROP = "runelite.headless.capture.max";

	private static final File DIR;
	private static final boolean ENABLED;
	private static final int INTERVAL;
	private static final int MAX_FRAMES;

	private static final AtomicInteger drawCount = new AtomicInteger();
	private static final AtomicInteger nullGraphicsCount = new AtomicInteger();
	private static final AtomicInteger capturedFrames = new AtomicInteger();

	static
	{
		final String dir = System.getProperty(DIR_PROP);
		ENABLED = dir != null && !dir.isEmpty();
		DIR = ENABLED ? new File(dir) : null;
		INTERVAL = Integer.getInteger(INTERVAL_PROP, 50);
		MAX_FRAMES = Integer.getInteger(MAX_PROP, 20);
		if (ENABLED)
		{
			//noinspection ResultOfMethodCallIgnored
			DIR.mkdirs();
			log.info("[headless-capture] ENABLED dir={} interval={} maxFrames={}", DIR, INTERVAL, MAX_FRAMES);
		}
	}

	private HeadlessCapture()
	{
	}

	public static boolean isEnabled()
	{
		return ENABLED;
	}

	/**
	 * Called at the very top of {@code Hooks.draw}, before the {@code graphics == null} guard.
	 * Counts total draw callbacks and how many arrive without a Graphics.
	 */
	public static void onDraw(Graphics graphics)
	{
		if (!ENABLED)
		{
			return;
		}

		final int n = drawCount.incrementAndGet();
		if (graphics == null)
		{
			nullGraphicsCount.incrementAndGet();
		}

		// First call, then once per second of frames-ish, log the running tally so the experiment is
		// readable from the console even without inspecting the PNGs.
		if (n == 1 || n % INTERVAL == 0)
		{
			log.info("[headless-capture] draw#{} nullGraphics={} capturedFrames={}",
				n, nullGraphicsCount.get(), capturedFrames.get());
		}
	}

	/**
	 * Called after overlays composite, only on the non-null-Graphics path. Dumps the engine buffer to
	 * PNG every {@code INTERVAL} draws, up to {@code MAX_FRAMES} files.
	 */
	public static void capture(MainBufferProvider mainBufferProvider)
	{
		if (!ENABLED || mainBufferProvider == null)
		{
			return;
		}

		final int n = drawCount.get();
		if (n % INTERVAL != 0)
		{
			return;
		}

		if (capturedFrames.get() >= MAX_FRAMES)
		{
			return;
		}

		try
		{
			final int[] pixels = mainBufferProvider.getPixels();
			final int w = mainBufferProvider.getWidth();
			final int h = mainBufferProvider.getHeight();
			if (pixels == null || w <= 0 || h <= 0 || pixels.length < w * h)
			{
				log.warn("[headless-capture] skipped: pixels={} w={} h={}",
					pixels == null ? "null" : pixels.length, w, h);
				return;
			}

			final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			img.setRGB(0, 0, w, h, pixels, 0, w);

			final int frame = capturedFrames.incrementAndGet();
			final File out = new File(DIR, String.format("frame-%03d-draw%05d.png", frame, n));
			ImageIO.write(img, "png", out);
			log.info("[headless-capture] wrote {} ({}x{})", out.getName(), w, h);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("[headless-capture] capture failed", ex);
		}
	}

	public static int getDrawCount()
	{
		return drawCount.get();
	}

	public static int getNullGraphicsCount()
	{
		return nullGraphicsCount.get();
	}

	public static int getCapturedFrames()
	{
		return capturedFrames.get();
	}
}
