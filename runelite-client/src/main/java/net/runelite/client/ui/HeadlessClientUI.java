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
package net.runelite.client.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.input.MouseManager;

/**
 * Gate 1c: a window-less replacement for {@link ClientUI}.
 *
 * <p>The stock {@code ClientUI} builds the full RuneLite desktop chrome — {@code ContainableFrame},
 * sidebar, toolbar, tray icon, custom L&amp;F, OSX adapters. None of that exists on iOS. This subclass
 * keeps exactly one thing the engine needs to actually render: a minimal AWT {@link Frame} that hosts
 * the gamepack {@code Component} and is realized so its {@code Canvas} acquires a peer. Without a
 * realized canvas the engine's render loop passes {@code null} to {@code Hooks.draw} and nothing
 * composites — that is the precise kill risk this gate settles.
 *
 * <p>On the desktop native toolkit the frame is a tiny real window (a bring-up crutch). Under the CACIO
 * CTC toolkit the same frame is realized to an offscreen virtual display — no OS window — which is the
 * honest iOS-shaped proxy. Everything else (focus, cursor, tray, taskbar, navigation, sidebar overlay)
 * is overridden to a no-op.
 *
 * <p>Bound in place of {@code ClientUI} by {@code net.runelite.client.headless.HeadlessModule}.
 */
@Slf4j
@Singleton
public class HeadlessClientUI extends ClientUI
{
	private final Client rsClient;

	private Frame frame;

	@Inject
	HeadlessClientUI(
		RuneLiteConfig config,
		MouseManager mouseManager,
		Client client,
		ConfigManager configManager,
		Provider<ClientThread> clientThreadProvider,
		EventBus eventBus,
		@Named("safeMode") boolean safeMode,
		@Named("runelite.title") String title
	)
	{
		super(config, mouseManager, client, configManager, clientThreadProvider, eventBus, safeMode, title);
		this.rsClient = client;
	}

	@Override
	public void init() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			frame = new Frame("RuneLite (headless)");
			frame.setLayout(new BorderLayout());

			final Component clientComponent = (Component) rsClient;
			clientComponent.setSize(Constants.GAME_FIXED_SIZE);
			clientComponent.setMinimumSize(Constants.GAME_FIXED_SIZE);
			clientComponent.setPreferredSize(Constants.GAME_FIXED_SIZE);

			frame.add(clientComponent, BorderLayout.CENTER);
			frame.setSize(Constants.GAME_FIXED_SIZE);
			frame.pack();
		});

		log.info("[headless] init done: frame={} gc={} toolkit={}",
			frame, frame.getGraphicsConfiguration(),
			java.awt.Toolkit.getDefaultToolkit().getClass().getName());
	}

	@Override
	public void show()
	{
		SwingUtilities.invokeLater(() ->
		{
			// Realize the peer hierarchy so the gamepack Canvas can hand the engine a non-null Graphics.
			frame.setVisible(true);
			log.info("[headless] show done: visible={} canvas={} displayable={}",
				frame.isVisible(), rsClient.getCanvas(),
				rsClient.getCanvas() != null && rsClient.getCanvas().isDisplayable());
		});
	}

	@Override
	public GraphicsConfiguration getGraphicsConfiguration()
	{
		if (frame != null)
		{
			return frame.getGraphicsConfiguration();
		}

		return GraphicsEnvironment.getLocalGraphicsEnvironment()
			.getDefaultScreenDevice()
			.getDefaultConfiguration();
	}

	@Override
	public Point getCanvasOffset()
	{
		return new Point(0, 0);
	}

	@Override
	public Insets getInsets()
	{
		return frame != null ? frame.getInsets() : new Insets(0, 0, 0, 0);
	}

	@Override
	public int getWidth()
	{
		return frame != null ? frame.getWidth() : Constants.GAME_FIXED_WIDTH;
	}

	@Override
	public int getHeight()
	{
		return frame != null ? frame.getHeight() : Constants.GAME_FIXED_HEIGHT;
	}

	// --- The render seam draws the real overlays via OverlayRenderer; this hook only paints the
	// desktop sidebar toggle button, which has no headless equivalent. ---
	@Override
	public void paintOverlays(Graphics2D graphics)
	{
	}

	@Override
	public void paint(Graphics graphics)
	{
	}

	// --- Focus / taskbar: no window to focus or flash. ---
	@Override
	public boolean isFocused()
	{
		return true;
	}

	@Override
	public void requestFocus()
	{
	}

	@Override
	public void forceFocus()
	{
	}

	@Override
	public void flashTaskbar()
	{
	}

	// --- Cursor: no window cursor to manage. ---
	@Override
	public Cursor getCurrentCursor()
	{
		return Cursor.getDefaultCursor();
	}

	@Override
	public Cursor getDefaultCursor()
	{
		return Cursor.getDefaultCursor();
	}

	@Override
	public void setCursor(BufferedImage image, String name)
	{
	}

	@Override
	public void setCursor(Cursor cursor)
	{
	}

	@Override
	public void resetCursor()
	{
	}

	// --- Navigation / sidebar: no toolbar or sidebar exists headless. ---
	@Override
	void addNavigation(NavigationButton navBtn)
	{
	}

	@Override
	void removeNavigation(NavigationButton navBtn)
	{
	}

	@Override
	void openPanel(NavigationButton navBtn, boolean showSidebar)
	{
	}
}
