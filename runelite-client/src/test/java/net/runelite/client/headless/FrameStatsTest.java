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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.slf4j.LoggerFactory;

/**
 * Pins {@link FrameStats}: the bucket formula fps-measure.sh decodes, the percentile read-out, and the exact
 * log-line shapes the harness parses. A format change here has to land with the parser change.
 */
public class FrameStatsTest
{
	private static final long MS = 1_000_000L;

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(FrameStats.class);

	@Before
	public void attach()
	{
		appender.start();
		logger.addAppender(appender);
	}

	@After
	public void detach()
	{
		logger.detachAppender(appender);
	}

	@Test
	public void bucketsAreTwentyPerDecadeFromATenthOfAMillisecond()
	{
		assertEquals(0, FrameStats.bucket(0));
		assertEquals(0, FrameStats.bucket(99));
		assertEquals(0, FrameStats.bucket(100));     // 0.1 ms
		assertEquals(20, FrameStats.bucket(1_000));  // 1 ms
		assertEquals(44, FrameStats.bucket(16_667)); // 60 Hz: 20 * log10(166.67) = 44.4
		assertEquals(60, FrameStats.bucket(100_000));
		assertEquals(80, FrameStats.bucket(1_000_000));
		assertEquals(80, FrameStats.bucket(4_000_000));
		assertEquals(17.78, FrameStats.upperEdgeMs(44), 0.005);
	}

	@Test
	public void percentilesReadTheUpperEdgeCappedAtTheMax()
	{
		long[] bins = new long[FrameStats.BUCKETS];
		bins[44] = 99;  // 99 frames at ~16.7 ms
		bins[60] = 1;   // one at 100-112 ms
		assertEquals(17.78, FrameStats.percentileMs(bins, 0.50, 105_000), 0.005);
		assertEquals(17.78, FrameStats.percentileMs(bins, 0.99, 105_000), 0.005);
		// rank 100 of 100 is the slow frame; its bucket edge (112.2) is above the exact max, so the max wins
		assertEquals(105.0, FrameStats.percentileMs(bins, 0.999, 105_000), 0.005);
		assertEquals(0.0, FrameStats.percentileMs(new long[FrameStats.BUCKETS], 0.5, 0), 0.0);
		assertEquals("44:99,60:1", FrameStats.bins(bins));
		assertEquals("-", FrameStats.bins(new long[FrameStats.BUCKETS]));
	}

	@Test
	public void logsTheHarnessLineShapes()
	{
		Client client = mock(Client.class);
		when(client.getFPS()).thenReturn(60);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		FrameStats fs = new FrameStats();

		// 11 s of frames: 16 ms, then 17 ms, alternating, plus one 40 ms hitch and one 8 s suspension
		long t = 1_000 * MS;
		fs.onFrame(t, client);
		for (int i = 0; i < 660; i++)
		{
			t += (i == 300 ? 40 : (i % 2 == 0 ? 16 : 17)) * MS;
			fs.onFrame(t, client);
		}
		t += 8_000 * MS;
		fs.onFrame(t, client);
		for (int i = 0; i < 120; i++)
		{
			fs.onFrame(t += 16 * MS, client); // the next [framehist] is due 10 s after the last, inside these
		}

		List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
		assertTrue(lines.toString(), lines.contains("[fpsprobe] fps=60 gs=LOGGED_IN"));
		String probe = lines.stream().filter(l -> l.startsWith("[frameprobe]")).findFirst().orElse("");
		assertTrue(probe, probe.matches("\\[frameprobe\\] n=\\d+ min=16\\.00 avg=16\\.\\d\\d p95=17\\.00 max=17\\.00 gs=LOGGED_IN"));
		String bins = lines.stream().filter(l -> l.startsWith("[framebins]")).findFirst().orElse("");
		// 16 ms -> bucket 44, 17 ms -> bucket 44 too (17.78 edge); |16-17| = 1 ms -> bucket 20
		assertTrue(bins, bins.matches("\\[framebins\\] dt=44:\\d+ jit=20:\\d+ max=17\\.00 jitMax=1\\.00 gcN=-?\\d+ gcMs=-?\\d+ heapMB=\\d+/\\d+ gs=LOGGED_IN"));
		String hist = lines.stream().filter(l -> l.startsWith("[framehist]")).reduce((a, b) -> b).orElse("");
		assertTrue(hist, hist.matches("\\[framehist\\] n=\\d+ p50=17\\.78 p95=17\\.78 p99=17\\.78 p999=40\\.00 max=40\\.00 "
			+ "jit50=1\\.12 jit95=1\\.12 jit99=1\\.12 pauses=1 gcN=-?\\d+ gcMs=-?\\d+ up=\\d+s scope=LOGGED_IN"));
	}

	@Test
	public void secondsOutsideTheWorldStayOutOfTheSession()
	{
		Client client = mock(Client.class);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		FrameStats fs = new FrameStats();
		long t = 1_000 * MS;
		for (int i = 0; i < 700; i++)
		{
			fs.onFrame(t += 16 * MS, client);
		}
		assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().startsWith("[framehist]")));
		assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().endsWith("gs=LOGIN_SCREEN")));
	}
}
