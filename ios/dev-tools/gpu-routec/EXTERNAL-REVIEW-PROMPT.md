# External review prompt — GPU Route C black-scene diagnosis

Paste the block below into an external code-review tool. It is self-contained and carries the guardrails that
keep a fresh reviewer from re-deriving the wrong root cause (an earlier review did, by treating a CPU-mode log as
GPU evidence). The companion artifact is `BLACK-SCENE-DIAGNOSIS.md` in this directory.

```
You are reviewing a COMPLETED root-cause diagnosis (not proposing a fix from scratch). Branch `gpu` of a
private RuneLite fork (`runelite-ios`). The project runs real RuneLite + the unmodified base `gpu` plugin on
an on-device iOS JVM, with the gpu plugin's LWJGL/rlawt GL calls served by a custom JNI "Route C" GL bridge
(EAGL GLES3). Full write-up is committed at ios/dev-tools/gpu-routec/BLACK-SCENE-DIAGNOSIS.md — read it. The
diagnostic code is runelite-client/src/main/java/net/runelite/client/headless/{GpuSceneDiag,TracingDrawCallbacks}.java
and the engine path was confirmed against a decompile of the injected gamepack.

GUARDRAILS (the previous review went wrong by ignoring these — do not repeat):
1. The GL bridge is PROVEN working end-to-end on device (registration, EAGL ES3 context, OpenGL33==true,
   scene+UI shaders compile/link, UI composites correctly, glReadPixels readback + color/orientation correct,
   C3 thread identity confirmed). Do NOT propose bridge fixes or re-audit it.
2. viewport=0x0 is a PROVEN RED HERRING. The CPU/software path renders the full 3D scene at the SAME
   viewport=0x0 (every reading), and in the injected scene-render method `ev.dk` the viewport getters are read
   ONLY when `!isGpu`. Do NOT resurrect any "viewport gates the scene render" theory.
3. The previous review derived a wrong RCA by treating a CPU-mode HUD-diagnostic log as GPU evidence. Reason
   ONLY from the GPU-run callback counts below.
4. Fixes must land in W2 (runelite-client headless/Hooks), the bridge (ios/dev-tools/gpu-routec), or boot/config
   — NOT the base gpu plugin source (kept unmodified by design).

DEVICE-VERIFIED EVIDENCE (iPhone 17 Pro Max, GPU mode, logged in, ~25s):
- Per-second state: state=LOGGED_IN, isGpu=true, getDrawCallbacks()=net.runelite.client.plugins.gpu.GpuPlugin
  on the normal AppClassLoader, resized=true, topLevelIf=165, getRealDimensions=1304x600, widgetRoots=2,
  getScene()!=null, viewport=0x0, canvas=1304x600. GpuPlugin startUp succeeded (no "Error starting GPU plugin").
- A faithful delegating DrawCallbacks (TracingDrawCallbacks) counted EVERY engine callback:
    loadScene=1  loadScene(WorldView,Scene)=1  swapScene=1  despawnWorldView=1  draw(int)=15247
    preSceneDraw=0  drawZoneOpaque=0  drawZoneAlpha=0  drawPass=0  postSceneDraw=0  drawScene(double..)=0
- GL trace: the only draw is glDrawArrays(GL_TRIANGLE_FAN,0,4) (the UI quad) every frame; glMultiDrawArrays
  (zone draw) and glBlitFramebuffer (scene-FBO blit) NEVER fire.
- Also tested GPU with NO headless resize (canvas stays fixed 765x503): still drawScene=0 (GPU-mode-intrinsic,
  not resize/resizable-layout related).

DIAGNOSIS (the render-gap):
The GPU scene IS uploaded (loadScene+swapScene fire once at login → geometry on the GPU), and the composite
draw(int) runs ~60fps — but the engine NEVER dispatches the per-frame 3D zone render, so it blits an empty scene
FBO → black 3D, correct UI. The same scene renders fine in CPU/software mode.

ENGINE CALL-GRAPH (from the decompile of the obfuscated gamepack):
- Per-frame scene render = `scene.ak() → ev.mm() → ev.ic(true,…) → DrawCallbacks.preSceneDraw … drawZoneOpaque …
  postSceneDraw`. (A second path `client.oy → ev.dk → ev.ic` exists but `oy` has no callers — dead.)
- In ev.dk/ev.mm the ZBUF branch is exactly:
    if ((client.ly & 0x10) != 0) { ic(true,…); gq(…); zd(null); getCallbacks().drawScene(); }
  This WOULD fire all the zone callbacks if the method were called; there is no early return before it.
- Our Hooks.drawScene() counter (the Callbacks.drawScene fired at the end of ic/dk/mm in BOTH branches) = 0 ⇒
  the per-frame scene-render method is simply NEVER INVOKED in GPU mode (in CPU mode it is).
- ALL 19 isGpu() call sites are INSIDE the scene-render methods (dk/mm/ic/zt/sq/as/fw.us/fw.au), NOT on the
  decision to CALL them. And even fully decompiled, the scene-render entry (em.ak / ev.mm / ee.av) has NO
  statically-resolvable caller — it's invoked via the obfuscator's computed/opaque dispatch. So the gate that
  suppresses the per-frame scene render in GPU mode cannot be found by reading code.

RULED OUT: viewport-gate theory (falsified), the bridge, headless resize, callback registration, classloader
mismatch (callbacks = real GpuPlugin on AppClassLoader), canvas displayability (displayable=true), render threads
(GpuPlugin sets none even on desktop; rl23 workers only process queued zone tasks, downstream of the un-called
render), and scene-not-loaded (loadScene/swapScene fired).

LEADING HYPOTHESIS (unconfirmed): on desktop, rlawt binds the GL context to the real AWT canvas and the engine
drives the GPU scene render through that canvas/surface pass; our Route C bridge uses an OFFSCREEN FBO not tied
to the canvas, and our headless render loop only exercises the raster/BufferProvider path (which is why CPU mode
works). So the GPU scene-render pass is never triggered.

TWO QUESTIONS:
1. Is the render-gap conclusion sound, or is there a hole in the chain "loadScene/swapScene fire + draw(int)
   fires + isGpu=true + canvas displayable, yet preSceneDraw/postSceneDraw never fire ⇒ the engine's per-frame
   scene-render method is never invoked in GPU mode headless"?
2. RuneLite's gpu plugin is open-source and its GPU scene-render handshake with the engine is well-known. Do you
   know WHAT makes the engine invoke the per-frame scene render (the method that calls preSceneDraw → drawZone*
   → postSceneDraw) when isGpu+ZBUF are set — and what could suppress that specific dispatch in a headless/CACIO
   (windowless, offscreen) environment while CPU/software rendering and loadScene/swapScene still work? If you
   know the trigger/condition, name it concretely (class/field/call/state) so we can skip a desktop reproduction
   and go straight to a W2/bridge fix. If you don't, say so plainly rather than guessing.

Be concrete and cite RuneLite source (runelite-client gpu plugin, runelite-api DrawCallbacks) or the committed
diagnosis doc. Flag anything in the diagnosis you think is wrong. Do not hedge a guess as fact.
```
