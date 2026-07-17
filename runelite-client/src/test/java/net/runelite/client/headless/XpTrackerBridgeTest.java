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

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/**
 * Unit tests for {@link XpTrackerBridge#parseDuration(String)} — the colon-separated {@code timeTillGoalShort}
 * to whole-seconds conversion. This helper became package-private in P1-9 precisely so BOTH XP facades reuse it:
 * {@code XpTrackerBridge.appendSnapshotFields} (Android's panel) and {@code MobileUiBridge.xpTrackerJson} (iOS's
 * panel) now emit {@code timeToLevelSec} from this single source of truth, so their time-to-level can never
 * drift apart. These cases pin the parse (incl. the "&lt;2 parts" and infinity/empty guards) that both rely on.
 */
public class XpTrackerBridgeTest
{
	@Test
	public void parseDurationConvertsHmmssAndMmss()
	{
		assertEquals(5025, XpTrackerBridge.parseDuration("1:23:45")); // ((1*60)+23)*60+45
		assertEquals(750, XpTrackerBridge.parseDuration("12:30"));    // 12*60+30
		assertEquals(0, XpTrackerBridge.parseDuration("0:00"));
	}

	@Test
	public void parseDurationRejectsUnderTwoPartsAndBlanks()
	{
		assertEquals(-1, XpTrackerBridge.parseDuration("45"));   // a lone value is not a duration
		assertEquals(-1, XpTrackerBridge.parseDuration(""));
		assertEquals(-1, XpTrackerBridge.parseDuration(null));
	}

	@Test
	public void parseDurationRejectsTheInfinityGlyph()
	{
		// XpTracker renders an unreachable goal as "∞"; a non-numeric part must fall back to -1, not throw.
		assertEquals(-1, XpTrackerBridge.parseDuration("∞"));
	}
}
