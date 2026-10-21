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

import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RouteCGpuStarter}'s first-launch vsync default. MobileUiBridge's statics are mocked, so the
 * result doesn't depend on that class's flag or on which test loaded it first. {@code ENABLED} is read once at class
 * load; the static initialiser sets it first, and no other test loads the class. The starter's one-shot state is
 * static, so each test resets it by reflection.
 */
public class RouteCGpuStarterTest
{
	private static final String OK = "{\"ok\":true,\"writeId\":1}";

	static
	{
		System.setProperty("runelite.routecgpu", "true");
	}

	@Before
	public void reset() throws Exception
	{
		for (String name : new String[]{"done", "uiModeForced"})
		{
			final Field f = RouteCGpuStarter.class.getDeclaredField(name);
			f.setAccessible(true);
			f.setBoolean(null, false);
		}
	}

	@Test
	public void seedsVsyncOnTheFirstLaunch()
	{
		try (MockedStatic<MobileUiBridge> ui = mockStatic(MobileUiBridge.class))
		{
			ui.when(() -> MobileUiBridge.setConfig(anyString(), anyString(), anyString())).thenReturn(OK);
			RouteCGpuStarter.tick(gpuNotYetActive());
			ui.verify(() -> MobileUiBridge.setConfig("gpu", "vsyncMode", "ON"));
			ui.verify(() -> MobileUiBridge.setConfig("gpu", "mobileVsyncSeeded", "1"));
			ui.verify(MobileUiBridge::flushConfigQueued);
		}
	}

	@Test
	public void leavesTheUsersVsyncChoiceAloneOnceSeeded()
	{
		try (MockedStatic<MobileUiBridge> ui = mockStatic(MobileUiBridge.class))
		{
			ui.when(() -> MobileUiBridge.setConfig(anyString(), anyString(), anyString())).thenReturn(OK);
			ui.when(() -> MobileUiBridge.getConfig("gpu", "mobileVsyncSeeded")).thenReturn("1");
			RouteCGpuStarter.tick(gpuNotYetActive());
			ui.verify(() -> MobileUiBridge.setConfig("gpu", "uiScalingMode", "LINEAR"));
			ui.verify(() -> MobileUiBridge.setConfig(eq("gpu"), eq("vsyncMode"), anyString()), never());
		}
	}

	@Test
	public void seedsOnlyOnceTheClientThreadTakesWrites()
	{
		try (MockedStatic<MobileUiBridge> ui = mockStatic(MobileUiBridge.class))
		{
			ui.when(() -> MobileUiBridge.setConfig(anyString(), anyString(), anyString()))
				.thenReturn("{\"ok\":false,\"reason\":\"not-ready\"}");
			RouteCGpuStarter.tick(gpuNotYetActive());
			ui.verify(() -> MobileUiBridge.setConfig(eq("gpu"), eq("vsyncMode"), anyString()), never());

			ui.when(() -> MobileUiBridge.setConfig(anyString(), anyString(), anyString())).thenReturn(OK);
			RouteCGpuStarter.tick(gpuNotYetActive());
			ui.verify(() -> MobileUiBridge.setConfig("gpu", "vsyncMode", "ON"));
		}
	}

	private static Client gpuNotYetActive()
	{
		final Client c = mock(Client.class);
		when(c.isGpu()).thenReturn(false);
		when(c.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		return c;
	}
}
