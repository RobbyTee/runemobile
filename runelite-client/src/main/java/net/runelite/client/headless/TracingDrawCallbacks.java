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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * GPU Route C diagnostic: a read-only delegating {@link DrawCallbacks} that counts every method the engine
 * invokes, then forwards verbatim to the real (gpu plugin) callbacks. Installed by {@link GpuSceneDiag} BEFORE
 * the login scene-load, so the engine's scene-loader (which captures {@code client.getDrawCallbacks()} at its
 * construction, then dispatches {@code loadScene}/{@code swapScene} only when that captured ref still equals
 * {@code client}'s current callbacks) captures THIS wrapper — keeping the equality intact and dispatch unbroken.
 *
 * <p>Rounds 1-2 proved the engine never dispatches the 3D scene render in GPU mode ({@code drawScene=0}) while
 * the composite {@code draw(int)} runs ~60fps. This wrapper resolves the remaining fork the GL trace can't: of
 * the scene callbacks the gpu plugin relies on — {@code loadScene}/{@code swapScene} (GPU scene upload),
 * {@code preSceneDraw}/{@code drawZoneOpaque}/{@code postSceneDraw} (per-frame zone render) — which (if any) does
 * the engine actually call? That pinpoints whether the scene is never uploaded, never per-frame-dispatched, or
 * both. Mutates nothing about behaviour (faithful delegation); only counts.
 */
public final class TracingDrawCallbacks implements DrawCallbacks
{
	private static final ConcurrentHashMap<String, LongAdder> COUNTS = new ConcurrentHashMap<>();

	private final DrawCallbacks d;

	public TracingDrawCallbacks(DrawCallbacks delegate)
	{
		this.d = delegate;
	}

	public DrawCallbacks delegate()
	{
		return d;
	}

	private static void hit(String m)
	{
		COUNTS.computeIfAbsent(m, k -> new LongAdder()).increment();
	}

	/** Compact "name=count name=count …" summary, ordered to read as the render pipeline. */
	public static String summary()
	{
		final String[] order = {
			"loadScene", "loadSceneWV", "swapScene", "swapSub", "despawnWV",
			"preSceneDraw", "preSceneDrawDep", "drawScene6", "drawZoneOpaque", "drawZoneAlpha",
			"drawPass", "drawDynamic", "drawTemp", "postSceneDraw", "postDrawScene",
			"draw1", "drawRenderable", "animate", "tileInFrustum", "zoneInFrustum",
		};
		final StringBuilder sb = new StringBuilder();
		for (String k : order)
		{
			final LongAdder a = COUNTS.get(k);
			if (a != null)
			{
				sb.append(k).append('=').append(a.sum()).append(' ');
			}
		}
		return sb.length() == 0 ? "(no callbacks yet)" : sb.toString().trim();
	}

	// --- per-frame composite (known to fire) -------------------------------------------------------------

	@Override
	public void draw(int overlayColor)
	{
		hit("draw1");
		d.draw(overlayColor);
	}

	// --- scene upload ------------------------------------------------------------------------------------

	@Override
	public void loadScene(Scene scene)
	{
		hit("loadScene");
		d.loadScene(scene);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		hit("loadSceneWV");
		d.loadScene(worldView, scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		hit("swapScene");
		d.swapScene(scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		hit("despawnWV");
		d.despawnWorldView(worldView);
	}

	// --- per-frame zone render ---------------------------------------------------------------------------

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		hit("preSceneDraw");
		d.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		hit("preSceneDrawDep");
		d.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		hit("drawZoneOpaque");
		d.drawZoneOpaque(entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		hit("drawZoneAlpha");
		d.drawZoneAlpha(entityProjection, scene, level, zx, zz);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		hit("drawPass");
		d.drawPass(entityProjection, scene, pass);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		hit("postSceneDraw");
		d.postSceneDraw(scene);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable r, Model m, int orient, int x, int y, int z)
	{
		hit("drawDynamic");
		d.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable r, Model m, int orient, int x, int y, int z)
	{
		hit("drawDynamic");
		d.drawDynamic(worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m,
		int orient, int x, int y, int z)
	{
		hit("drawTemp");
		d.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		hit("invalidateZone");
		d.invalidateZone(scene, zx, zz);
	}

	// --- legacy / per-renderable scene callbacks ---------------------------------------------------------

	@Override
	@SuppressWarnings("deprecation")
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		hit("drawScene6");
		d.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		hit("postDrawScene");
		d.postDrawScene();
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		hit("drawRenderable");
		d.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		hit("drawScenePaint");
		d.drawScenePaint(scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		hit("drawSceneTileModel");
		d.drawSceneTileModel(scene, model, tileX, tileZ);
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		hit("animate");
		d.animate(texture, diff);
	}

	// --- frustum predicates (must return the delegate's answer) ------------------------------------------

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos,
		int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		hit("tileInFrustum");
		return d.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY)
	{
		hit("zoneInFrustum");
		return d.zoneInFrustum(zoneX, zoneZ, maxY, minY);
	}
}
