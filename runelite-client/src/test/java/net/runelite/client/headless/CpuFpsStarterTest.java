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

import net.runelite.api.Client;
import net.runelite.api.GameState;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CpuFpsStarter}: the CPU renderer's frame-cap experiment. Its target is read once at class load, so it is
 * set in the static initialiser before the class loads (nothing else in the module references it). The applied
 * state is shared and static, so each test starts with a GPU frame, which clears it.
 */
public class CpuFpsStarterTest
{
	static
	{
		System.setProperty("runelite.cpu.unlockfps", "60");
	}

	private static Client client(boolean gpu, GameState state)
	{
		final Client c = mock(Client.class);
		when(c.isGpu()).thenReturn(gpu);
		when(c.getGameState()).thenReturn(state);
		return c;
	}

	@Test
	public void unlocksOnceLoggedInOnTheCpuRenderer()
	{
		CpuFpsStarter.tick(client(true, GameState.LOGGED_IN));
		final Client c = client(false, GameState.LOGGED_IN);

		assertTrue(CpuFpsStarter.isEnabled());
		CpuFpsStarter.tick(c);
		CpuFpsStarter.tick(c);

		verify(c, times(1)).setUnlockedFps(true);
		verify(c, times(1)).setUnlockedFpsTarget(60);
	}

	@Test
	public void waitsForTheWorld()
	{
		CpuFpsStarter.tick(client(true, GameState.LOGGED_IN));
		final Client c = client(false, GameState.LOGIN_SCREEN);

		CpuFpsStarter.tick(c);

		verify(c, never()).setUnlockedFps(anyBoolean());
	}

	@Test
	public void leavesAGpuRendererAloneAndReappliesAfterIt()
	{
		final Client gpu = client(true, GameState.LOGGED_IN);
		CpuFpsStarter.tick(gpu);
		verify(gpu, never()).setUnlockedFps(anyBoolean());
		verify(gpu, never()).setUnlockedFpsTarget(anyInt());

		// GpuPlugin's shutdown turns the unlock off; back on the CPU renderer it is asked for again.
		final Client cpu = client(false, GameState.LOGGED_IN);
		CpuFpsStarter.tick(cpu);
		verify(cpu).setUnlockedFps(true);
		verify(cpu).setUnlockedFpsTarget(60);
	}
}
