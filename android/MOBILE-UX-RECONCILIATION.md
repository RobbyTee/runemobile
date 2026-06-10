# Mobile-UX Audit — Reconciliation Note (read alongside `android/MOBILE-UX-AUDIT.md`)

> **Purpose:** `android/MOBILE-UX-AUDIT.md` was written **2026-06-08**, *before* the GPU/117HD/real-device
> work. Two of its load-bearing framings are now stale. Read this first so you plan from current reality
> (2026-06-10), not the snapshot. Nothing in the audit's UX findings/phasing is invalidated — only its
> GPU-status and device-verification assumptions changed (both for the better).

---

## ⚠️ TESTING POLICY — emulator-first, Shield only when unavoidable

**Default to the local emulator (`adb -s emulator-5554`) for ALL work unless using the Shield is absolutely
necessary.** The emulator runs locally on the mini, needs no device round-trip, and is authoritative for the
entire CPU-rendered client (Track A), all UI/Compose/Views work, plugin logic, the native bridges, input
plumbing logic, and class-load/wiring correctness. **Essentially the entire MOBILE-UX backlog is emulator
work** — AP-0, the config editor, plugin list, panels, notification *logic*, screenshot, links, overlays,
etc. are all CPU-client UX and verify on the emulator.

**Use the Shield (`adb -s 192.168.0.142:5555`) ONLY when the change genuinely requires it:**
- It touches the **GPU/117HD render path** (the emulator's SwiftShader physically cannot run Zone shaders).
- It needs **real-GPU or real-touch confirmation** the emulator can't give (final pinch/IME/haptic *feel*,
  perf/thermal/battery, a 117HD-active interaction).

If you're unsure whether a change needs the Shield, it almost certainly doesn't — ask first. Treat a Shield
run as the exception, scoped to a deliberate device-verification pass, not the per-iteration loop.

---

## What the Shield / GPU sessions CLEARED or CHANGED vs the 06-08 audit

1. **GPU track is essentially DONE — `I8` / Phase `AP-3` are obsolete.** The audit says *"no live GPU frame
   on screen, `rc_AWT_swapBuffers` is a no-op stub, AG2 is NEXT-not-done, post-v1 fast-follow, functionally
   missing today."* **Not true anymore.** AG2 (base-gpu present), AG3, **117HD (RLHD Zone)**, and a **direct
   EGL window-surface present** are all done and **playable on a real Nvidia Shield**. `rc_AWT_swapBuffers`
   is a full present path, not a stub. The GPU mountain is climbed — which is exactly why pivoting to this UX
   backlog is the right next move.

2. **AP-1 device-verification's scariest item is RETIRED.** The audit flags **`dlopen`-from-`filesDir`
   (B5 #21)** as the one risk that could force re-packaging the JRE under `jniLibs`. It **works on the
   Shield** — the embedded JVM comes up on real hardware. So the existential AP-1 risk (R1) is cleared.
   *Still genuinely unverified on a real phone:* touch-specific Track-A claims (pinch, IME composition,
   haptics, surface-recreate) and perf/thermal/battery — but these are emulator-testable for *logic*, and
   only need a device for final *feel/measurement*, not as a blocking gate.

3. **New durable constraint discovered: the GL bridge is a SINGLE thread-affine context.** Any RLHD/engine
   **multithreaded-GL feature must stay OFF** (we forced RLHD's `asyncModelProcessing=false` to fix
   player-model flashing — concurrent buffer writes from engine render-threads corrupt on a one-context
   bridge). Relevant if any future UX/render change touches threading or the draw path. First suspect for any
   intermittent rendering corruption.

## What is UNCHANGED (the audit's UX work stands)

- All 27 findings, the `[shared-core]`/`[native-Android]` tagging, the "Behind iOS / reuse iOS's shared half"
  leverage, the `I1–I12` infeasible list, and the open questions are all still valid.
- **OQ1 (the shift-feed spike) is still the single blocker** that decides ~5 findings (12/18/19/20 + overlay
  "Configure"). Run it early; it's cheap and its yes/no gates a whole branch.

## Recommended first steps for the next session

1. **Sync the branch first.** The blueprint + the iOS-side shared-core (which the audit says to *reuse*) live
   on `master`; the `android` branch doesn't have them yet. Merge `origin/master` **into** `android` so you
   have the blueprint and iOS's shared halves in your working tree. (Coordinate shared-core changes with the
   live iOS session — every Shared-S item needs the contract guard + dual-port re-census, per Risk R2.)
2. **Phase AP-0** — four small, zero-blocking-constraint, native-Android-only wins (iOS already proved the
   shared halves). Start with **Finding 4 (input-queue overflow guard)** — it's a real reliability bug
   (dropped touch-UP = stuck camera/button), not polish. Then Logout, chat scroll-by-drag, manual `⌨` button.
3. **Run the OQ1 shift spike** (separately, early) before planning any shift-dependent UX.
4. All of the above is **emulator work** — see the testing policy above.
