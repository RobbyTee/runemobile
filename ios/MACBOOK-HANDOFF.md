# MacBook Handoff — iOS GPU device kit (the EAGL-vs-ANGLE decision)

> Self-contained brief to continue on the MacBook. Branch `gpu`. Everything referenced here is pushed to
> `origin/gpu` (origin/gpu = cdcde3487). The project memory (auto-loaded each session) has the full history;
> this is the focused "what to do next."

## 0. One command to get set up (run on the MacBook)

```sh
scp jordanmm@100.71.129.1:macbook-transition.sh /tmp/t.sh && bash /tmp/t.sh
```

Pulls `origin/gpu` + the local-state bundle (tokens / JRE / cacio jars / memory) from the mini over Tailscale,
rebuilds the gitignored jars, regenerates the **EAGL** Xcode project, compile-checks, builds + installs the app.
Idempotent. (sshd on the mini is confirmed listening; keep the mini awake. `SKIP_BUNDLE=1` skips the 91 MB transfer
on re-runs.) Details: `ios/dev-tools/macbook-transition.sh`.

## 1. Where we are (the 60-second picture)

- The base RuneLite **`gpu` plugin already renders the live OSRS 3D world on the iPhone** via the Route C JNI GL
  bridge. This is not a from-scratch effort — it's down to one strategic decision.
- The goal is **117HD on iOS**. Verdict from the investigation: **do it on native EAGL ES 3.0**, not ANGLE — ANGLE
  is hard-capped at ES 3.0 too (no compute), so it buys nothing 117HD-structural over EAGL, while it drags in a
  "wedge bug" (degenerate triangles) on the only obtainable (stale) ANGLE build. EAGL sidesteps that entirely.
- EAGL has exactly two deficits vs desktop: depth precision (no `glClipControl`) and MSAA. Two Simulator harnesses
  (both md5-sealed GREEN, off-device) settled the open questions:
  - **`gpu-clipremap`**: `gl_FragDepth` is the only full-precision EAGL depth fix (64×); the cheaper vertex remap
    only gets 4×. **The one catch:** writing `gl_FragDepth` disables Apple's TBDR early-Z — a perf cost a software
    renderer can't measure. **That is the single thing this device session resolves.**
  - **`gpu-tbo2d`**: 117HD blocker #1 (the `textureFaces` `GL_RGB32I` texture-buffer → 2D repack) is byte-exact on
    iOS ES 3.0, validated on RLHD's real schema; the downporter transform is spec'd. Backend-independent.

## 2. Your task — the gl_FragDepth early-Z A/B (one StikDebug session)

Full steps + the mode-push/pull commands: **`ios/dev-tools/gpu-routec/DEVICE-KIT.md`**. In short:

1. Build is **EAGL** (default now). App is installed by the transition script.
2. **Run 1 — baseline:** push `routec-water-mode.txt = baseline` (early-Z ON, no gl_FragDepth), attach StikDebug →
   tap "▶ Run GPU (Route C)" → autologin → walk to the **Grand Exchange** (needs heavy overdraw) → stand still ~60s.
3. **Run 2 — fragdepth:** push `routec-water-mode.txt = fragdepth` (early-Z OFF), relaunch, **same GE spot**, ~60s.
4. Pull `Documents/routec-perf.log` (it appends across both launches).

Push a mode / pull the log:
```sh
DEV=<iphone-uuid>      # xcrun devicectl list devices
printf baseline > /tmp/mode.txt    # or: fragdepth
xcrun devicectl device copy to --device "$DEV" --domain-type appDataContainer \
  --domain-identifier dev.audaro.helloshell --source /tmp/mode.txt --destination Documents/routec-water-mode.txt
# … after both runs …
xcrun devicectl device copy from --device "$DEV" --domain-type appDataContainer \
  --domain-identifier dev.audaro.helloshell --source Documents/routec-perf.log --destination ./routec-perf.log
```

You'll also get a one-shot line per launch: `[probe] RGB32I_DEVICE_SAMPLEABLE=YES/NO …` (the blocker-#1 device
confirm — expect YES).

## 3. The decision rule

Compare **`gpuFinish` avg + p95** between the two `[perf] mode=…` lines on the same busy scene (ignore `copy`/engine
fps — those are the readback path, not the depth choice):

- **fragdepth ≈ baseline (within ~15%)** → early-Z loss is cheap on the A19 → **EAGL + gl_FragDepth wins.** The wedge
  bug is moot; no ANGLE. **Next: start iOS Zone B0** (the TBO transform is already spec'd in `gpu-tbo2d/README.md`).
- **fragdepth materially worse (>~1.5×, or p95 spikes / fps tanks under load)** → the early-Z cost is real → depth
  must come from **ANGLE `clip_control`**, which puts the ANGLE near-plane **wedge `w`-guard** back on the path.

Paste `routec-perf.log` into a fresh session and it'll read the numbers and call it.

## 4. Key files

- `ios/dev-tools/gpu-routec/DEVICE-KIT.md` — the run procedure + decision rule (authoritative).
- `ios/dev-tools/gpu-routec/RouteCBridge.{h,m}`, `jni/routec_gl_jni.m` — the device kit lives here (grep `DEVICE KIT`).
- `ios/dev-tools/gpu-clipremap/`, `ios/dev-tools/gpu-tbo2d/` — the two sealed off-device proofs (+ their READMEs).
- `ios/HD-PORT-BLUEPRINT.md` §5 — the full ES-3.0 blocker list for Zone B0.
- `ios/dev-tools/macbook-transition.sh` — the setup script.

## 5. Resume a fresh session on the MacBook

The project memory auto-loads, so a new session already has the history. Just say, e.g.:

> "I'm on my MacBook, ran the transition, did the device-kit A/B. Here's routec-perf.log: «paste». Call EAGL vs
> ANGLE and do the next step."

or, if you hit a snag setting up: "the transition script failed at step N: «paste output»".

## Gotchas

- **Tokens:** if OSRS login fails, the Jagex tokens are stale — re-mint with `ios/dev-tools/fetch-shield-tokens.sh`
  (interactive, throwaway account).
- **JIT:** StikDebug attach is per-launch (both runs need it). There is no painless no-StikDebug path on A19/26.5 —
  that was investigated and closed.
- **Backend:** EAGL is the active default (`ios/app/project.yml`: `RC_BACKEND_ANGLE` commented + `OpenGLES.framework`
  linked). To switch to ANGLE later, the toggle is documented in `DEVICE-KIT.md`.
- **Camera parity:** the A/B is only meaningful if both runs view the same busy scene — stand on the same tile.
