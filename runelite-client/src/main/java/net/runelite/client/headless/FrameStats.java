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

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Frame-pacing probe behind {@code -Drunelite.fpsprobe=true} (each port's diagnostics switch sets it).
 * {@code Hooks.frame} hands it every engine frame on the client thread; once a second it logs
 * <pre>
 *   [fpsprobe]   fps=N gs=STATE                                     the engine's smoothed fps
 *   [frameprobe] n=N min=.. avg=.. p95=.. max=.. gs=STATE           this second's frame intervals, exact, ms
 *   [framebins]  dt=I:C,.. jit=I:C,.. max=.. jitMax=.. gcN=N gcMs=N heapMB=U/C gs=STATE
 * </pre>
 * and every 10 s a summary since the first frame, over the seconds that ended LOGGED_IN ({@code up} counts from
 * that frame):
 * <pre>
 *   [framehist]  n=N p50=.. p95=.. p99=.. p999=.. max=.. jit50=.. jit95=.. jit99=.. pauses=N gcN=N gcMs=N up=Ns scope=LOGGED_IN
 * </pre>
 *
 * <p>A per-second p95 cannot be averaged into a capture's p95, and fifty frames have no p99. So each second
 * also carries its frame intervals ({@code dt}) and pacing jitter ({@code jit} = |dt_i - dt_(i-1)|) as sparse
 * {@code bucket:count} pairs over fixed log-spaced buckets. Summing them over any set of seconds gives
 * percentiles over exactly that set, to within a bucket. Bucket {@code i} holds
 * {@code [0.1 ms * 10^(i/20), 0.1 ms * 10^((i+1)/20))}: 20 per decade, each 12% wide, anything under 0.1 ms in
 * bucket 0 and anything from 1 s up in the last. A percentile is its bucket's upper edge, capped at the exact
 * max ({@code max}/{@code jitMax} ride along for that). {@code android/dev-tools/gpu/fps-measure.sh} decodes the
 * same formula.
 *
 * <p>An interval of 5 s or more is the app suspended, not a slow frame: it is counted in {@code pauses} and kept
 * out of the buckets. {@code gcN}/{@code gcMs} are collector pauses from the GC MXBeans (per second in
 * [framebins], since JVM start in [framehist]), so a GC lands on the same timeline as the frames it stalled;
 * both read -1 if the runtime has no java.management. The cost per frame is a nanoTime, two log10 and a few
 * array stores; nothing is allocated except at the once-a-second log.
 */
@Slf4j
public final class FrameStats
{
	static final int BUCKETS = 81;
	private static final int PER_DECADE = 20;
	private static final double BASE_MS = 0.1;
	private static final long PAUSE_US = 5_000_000L;
	private static final long SECOND_NS = 1_000_000_000L;
	private static final long SESSION_EVERY_NS = 10 * SECOND_NS;

	// This second. windowUs keeps the exact intervals for [frameprobe]; sized for ~1 s at high refresh, it stops
	// sampling when full (a true-but-truncated picture beats unbounded growth). The buckets never truncate.
	private final long[] windowUs = new long[1024];
	private int windowCount;
	private long windowMaxUs;
	private long windowJitMaxUs;
	private final long[] windowDt = new long[BUCKETS];
	private final long[] windowJit = new long[BUCKETS];

	// The session, LOGGED_IN seconds only.
	private final long[] sessionDt = new long[BUCKETS];
	private final long[] sessionJit = new long[BUCKETS];
	private long sessionMaxUs;
	private long sessionJitMaxUs;
	private long pauses;

	private final List<GarbageCollectorMXBean> gcBeans = gcBeans();
	private long gcCountSeen;
	private long gcMsSeen;

	// nanoTime has an arbitrary origin (it may be 0 or negative), so "no frame yet" is a flag, not a 0 sentinel
	private boolean started;
	private long startNs;
	private long lastFrameNs;
	private long lastDtUs = -1;
	private long lastSecondNs;
	private long lastSessionNs;

	public FrameStats()
	{
		long[] gc = gcTotals();
		gcCountSeen = gc[0];
		gcMsSeen = gc[1];
	}

	/** Once per engine frame, on the client thread. */
	public void onFrame(long nowNs, Client client)
	{
		if (!started)
		{
			started = true;
			startNs = lastSecondNs = lastSessionNs = nowNs;
		}
		else
		{
			record((nowNs - lastFrameNs) / 1000L);
		}
		lastFrameNs = nowNs;

		if (nowNs - lastSecondNs >= SECOND_NS)
		{
			lastSecondNs = nowNs;
			logSecond(client.getFPS(), client.getGameState());
		}
		if (nowNs - lastSessionNs >= SESSION_EVERY_NS)
		{
			lastSessionNs = nowNs;
			logSession(nowNs);
		}
	}

	void record(long dtUs)
	{
		if (dtUs >= PAUSE_US)
		{
			pauses++;
			lastDtUs = -1;
			return;
		}
		if (windowCount < windowUs.length)
		{
			windowUs[windowCount++] = dtUs;
		}
		windowDt[bucket(dtUs)]++;
		windowMaxUs = Math.max(windowMaxUs, dtUs);
		if (lastDtUs >= 0)
		{
			long jitUs = Math.abs(dtUs - lastDtUs);
			windowJit[bucket(jitUs)]++;
			windowJitMaxUs = Math.max(windowJitMaxUs, jitUs);
		}
		lastDtUs = dtUs;
	}

	private void logSecond(int fps, GameState gs)
	{
		log.info("[fpsprobe] fps={} gs={}", fps, gs);
		if (windowCount > 0)
		{
			long[] s = Arrays.copyOf(windowUs, windowCount);
			Arrays.sort(s);
			long sum = 0;
			for (long v : s)
			{
				sum += v;
			}
			// nearest-rank 95th percentile
			int idx95 = Math.min(s.length - 1, Math.max(0, (int) Math.ceil(0.95 * s.length) - 1));
			log.info("[frameprobe] n={} min={} avg={} p95={} max={} gs={}",
				s.length, ms(s[0] / 1000.0), ms(sum / (double) s.length / 1000.0), ms(s[idx95] / 1000.0),
				ms(s[s.length - 1] / 1000.0), gs);
		}
		long[] gc = gcTotals();
		if (sum(windowDt) > 0)
		{
			Runtime rt = Runtime.getRuntime();
			long committedMb = rt.totalMemory() >> 20;
			long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
			log.info("[framebins] dt={} jit={} max={} jitMax={} gcN={} gcMs={} heapMB={}/{} gs={}",
				bins(windowDt), bins(windowJit), ms(windowMaxUs / 1000.0), ms(windowJitMaxUs / 1000.0),
				delta(gc[0], gcCountSeen), delta(gc[1], gcMsSeen), usedMb, committedMb, gs);
		}
		gcCountSeen = gc[0];
		gcMsSeen = gc[1];

		// The second counts toward the session if it ended in the world, the same tag [frameprobe] carries.
		if (gs == GameState.LOGGED_IN)
		{
			for (int i = 0; i < BUCKETS; i++)
			{
				sessionDt[i] += windowDt[i];
				sessionJit[i] += windowJit[i];
			}
			sessionMaxUs = Math.max(sessionMaxUs, windowMaxUs);
			sessionJitMaxUs = Math.max(sessionJitMaxUs, windowJitMaxUs);
		}
		windowCount = 0;
		windowMaxUs = 0;
		windowJitMaxUs = 0;
		Arrays.fill(windowDt, 0);
		Arrays.fill(windowJit, 0);
	}

	private void logSession(long nowNs)
	{
		long n = sum(sessionDt);
		if (n == 0)
		{
			return;
		}
		long[] gc = gcTotals();
		log.info("[framehist] n={} p50={} p95={} p99={} p999={} max={} jit50={} jit95={} jit99={} pauses={} gcN={} gcMs={} up={}s scope=LOGGED_IN",
			n,
			ms(percentileMs(sessionDt, 0.50, sessionMaxUs)),
			ms(percentileMs(sessionDt, 0.95, sessionMaxUs)),
			ms(percentileMs(sessionDt, 0.99, sessionMaxUs)),
			ms(percentileMs(sessionDt, 0.999, sessionMaxUs)),
			ms(sessionMaxUs / 1000.0),
			ms(percentileMs(sessionJit, 0.50, sessionJitMaxUs)),
			ms(percentileMs(sessionJit, 0.95, sessionJitMaxUs)),
			ms(percentileMs(sessionJit, 0.99, sessionJitMaxUs)),
			pauses, gc[0], gc[1], (nowNs - startNs) / SECOND_NS);
	}

	static int bucket(long us)
	{
		if (us < 100)
		{
			return 0;
		}
		int i = (int) Math.floor(PER_DECADE * Math.log10(us / 100.0));
		return Math.min(i, BUCKETS - 1);
	}

	static double upperEdgeMs(int bucket)
	{
		return BASE_MS * Math.pow(10, (bucket + 1) / (double) PER_DECADE);
	}

	/** Nearest-rank percentile, reported as its bucket's upper edge capped at the exact max (0 when empty). */
	static double percentileMs(long[] bins, double p, long maxUs)
	{
		long n = sum(bins);
		if (n == 0)
		{
			return 0;
		}
		long rank = Math.max(1, (long) Math.ceil(p * n));
		long cum = 0;
		for (int i = 0; i < bins.length; i++)
		{
			cum += bins[i];
			if (cum >= rank)
			{
				// the last bucket has no upper edge; the max is the only honest bound for it
				return i == BUCKETS - 1 ? maxUs / 1000.0 : Math.min(upperEdgeMs(i), maxUs / 1000.0);
			}
		}
		return maxUs / 1000.0;
	}

	/** Sparse "bucket:count,..." of the non-empty buckets, or "-". */
	static String bins(long[] counts)
	{
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < counts.length; i++)
		{
			if (counts[i] != 0)
			{
				if (sb.length() > 0)
				{
					sb.append(',');
				}
				sb.append(i).append(':').append(counts[i]);
			}
		}
		return sb.length() == 0 ? "-" : sb.toString();
	}

	private static long sum(long[] a)
	{
		long s = 0;
		for (long v : a)
		{
			s += v;
		}
		return s;
	}

	private static long delta(long now, long before)
	{
		return now < 0 || before < 0 ? -1 : now - before;
	}

	// Locale.ROOT: a device locale with a decimal comma would otherwise print "16,67" and break every parser.
	private static String ms(double ms)
	{
		return String.format(Locale.ROOT, "%.2f", ms);
	}

	private static List<GarbageCollectorMXBean> gcBeans()
	{
		try
		{
			return ManagementFactory.getGarbageCollectorMXBeans();
		}
		catch (Throwable t)
		{
			// a runtime linked without java.management; the frame stats still work
			log.info("[framebins] no GC MXBeans ({}); gcN/gcMs read -1", t.toString());
			return null;
		}
	}

	/** {total collections, total collection ms} over every collector (G1: young, old and concurrent-cycle pauses). */
	private long[] gcTotals()
	{
		if (gcBeans == null)
		{
			return new long[]{-1, -1};
		}
		long count = 0;
		long ms = 0;
		for (GarbageCollectorMXBean b : gcBeans)
		{
			count += Math.max(0, b.getCollectionCount());
			ms += Math.max(0, b.getCollectionTime());
		}
		return new long[]{count, ms};
	}
}
