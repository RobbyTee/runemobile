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

/**
 * Experiment, off by default: lift the engine's own frame cap on the CPU renderer. Without unlocked fps the client
 * draws on its 20 ms game timer, about 50 fps, and the only caller of {@code setUnlockedFps} in the tree is
 * GpuPlugin. 50 fps does not divide a 60 or 120 Hz panel, so the CPU renderer's frames land on an uneven 2/2/3
 * vsync cadence. With {@code -Drunelite.cpu.unlockfps=N} the CPU renderer asks the engine for N fps instead, once
 * logged in.
 *
 * <p>Whether the software rasteriser's loop honours it is what the experiment finds out; the gamepack's loop is
 * not in this repo. Measure with the perf probe ({@code [fpsprobe]} draws per second and the frame-time spread),
 * and watch the CPU load: the thermal governor throttles only 117HD.
 *
 * <p>Only while no GPU renderer runs. GpuPlugin and 117HD set the unlock and its target from their own sync mode,
 * and GpuPlugin's shutdown turns it off, so it is re-applied if the client is ever back on the CPU renderer. The
 * ports pass the flag only when CPU is the chosen renderer. Client thread.
 */
@Slf4j
public final class CpuFpsStarter
{
	private static final int TARGET = Integer.getInteger("runelite.cpu.unlockfps", 0);

	private static boolean applied;

	private CpuFpsStarter()
	{
	}

	public static boolean isEnabled()
	{
		return TARGET > 0;
	}

	/** Per frame, from the post-composite seam: once logged in on the CPU renderer, unlock the frame rate to the target. */
	public static void tick(Client client)
	{
		if (TARGET <= 0 || client == null)
		{
			return;
		}
		if (client.isGpu())
		{
			applied = false;   // a GPU renderer owns the unlock while it runs
			return;
		}
		if (applied || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		applied = true;
		client.setUnlockedFps(true);
		client.setUnlockedFpsTarget(TARGET);
		log.info("[cpufps] CPU renderer unlocked to {} fps", TARGET);
	}
}
