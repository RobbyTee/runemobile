# Phase 1 — Slimming / Dead-code / APK-size

> Audit of the Android `:app` APK. Baseline: **111 MB** target release APK (the on-disk `app-release.apk` is **116 MB but STALE/un-bootable** — see SLM-JAR-06 / SLM-PKG-07 / SLM-DEP-02; a correct rebuild ships gpu.jar+rlhd.jar+librlawt.so and is the real baseline). All savings below are against a *correctly-built* release APK.
> Every finding keeps its stable id; later phases + ROADMAP cite these ids verbatim. Findings were adversarially verified (CONFIRM / ADJUST / OVERTURN); corrected saving/risk are used throughout.

## 1. TL;DR

**Realistic APK DOWNLOAD-size reduction:**

- **Conservative: ~58 MB** (the three high-confidence, low/medium-risk levers that need no new toolchain artifact: drop dead shaded.jar from Android assets **−29.4**, allow asset compression by narrowing `noCompress` **−20.0** [JRE `.so` 16.0 + boot jars 3.4 + lib `.so` 1.1, all download-only], drop `jdk.localedata` **+** the debug/agent modules as part of any JRE re-link **−~10–13**). The localedata/JRE-module win is gated on sourcing a jmods-bearing bionic JDK (see §4).
- **Optimistic: ~70 MB** (above **+** the full jlink keep-set re-link landing cleanly, which makes the localedata saving the headline of a ~13 MB in-APK / ~33 MB uncompressed JRE-module trim, and assumes the `noCompress` change is taken in full).
- **INSTALLED/unpacked (`filesDir`) saving is much larger** where the JRE trims: dropping `jdk.localedata` alone is ~29 MB uncompressed off the ~199 MB unpacked tree; a full jlink is ~47 MB uncompressed — this also shrinks the ~30 s first-launch unpack stall.

> Note on additivity: the shaded-jar download saving (SLM-JAR-01 / SLM-PKG-01 / SLM-DEP-01 are **the same lever**, three readers) and the "compress the jars" saving (SLM-JAR-04 / SLM-PKG-03) partially overlap — if shaded.jar is dropped, its ~3.1 MB compressible delta leaves the compression calc. The conservative number already accounts for this (shaded counted once, at its STORED 29.4 MB; the jar-compression figure excludes shaded).

**Top 5 levers (punchlist):**

1. **Stop staging `shaded.jar` into Android `assets/jvm/`** — it is never on the Android boot classpath. **−29.4 MB** download+installed, **low** risk. (SLM-JAR-01 / SLM-PKG-01 / SLM-DEP-01)
2. **Narrow `noCompress` to `["so","dat","bin"]` (drop `"jar"`) and clear it for assets where safe** — lets the JRE `.so` and boot jars DEFLATE in the APK; all are copied to `filesDir` before use so it is runtime-neutral. **−~20 MB** download-only. (SLM-PKG-02 + SLM-PKG-03 + SLM-PKG-05 / SLM-JAR-04)
3. **Drop `jdk.localedata`** from the JRE (en-only is safe; no `-Duser.language` set) — biggest single safely-droppable module. **−~10–11 MB** download / **~29 MB** installed. (SLM-JRE-02, within SLM-JRE-01/04)
4. **jlink the full 45-module JRE to a ~24-module keep-set** (+ drop debug/agent/JFR/RMI/JVMCI modules and their `.so`). **−~13 MB** in-APK / **~33–47 MB** uncompressed installed. Gated on a jmods-bearing bionic JDK21. (SLM-JRE-01 / SLM-JRE-04 / SLM-JRE-03)
5. **Fix the stale/un-bootable on-disk release APK** (P0 correctness, 0 MB) — prerequisite to trusting every size number and to a clean release. (SLM-JAR-06 / SLM-PKG-07 / SLM-DEP-02)

---

## 2. APK composition recap

*(release, uncompressed; JRE + jars ship STORED via `noCompress`; from 00-map §3.5)*

| Component | Uncompressed | Compressible in-APK? | Why it exists |
|---|---|---|---|
| `assets/jvm/jre/lib/modules` | **110.8 MB** | Yes (DEFLATEs ~65% → ~38.5 MB) — currently STORED | OpenJDK 21 jimage; dominates the APK |
| `assets/jvm/client-…-shaded.jar` | 29.4 MB | already internally deflated | Surgery source + iOS boot; **not an Android boot target** |
| `assets/jvm/client-…-gpu.jar` | 28.8 MB | already internally deflated | **Every** Android boot (CPU+GPU); full client + LWJGL facade |
| `assets/jvm/jre/lib/server/libjvm.so` | 18.5 MB | Yes (→ ~5.8 MB) — currently STORED | HotSpot C2 JIT; dlopen by path (load-bearing) |
| `classes.dex` + `classes2.dex` | 13.3 + 5.6 = 19 MB | (DEX) | Compose/AndroidX/Kotlin shell (engine is in asset jars, not dex) |
| `assets/jvm/client-…-rlhd.jar` | 9.1 MB | PNG/JSON already compressed | 117HD delta (rs117/hd data); rides every classpath |
| JRE native `.so` (×33: libfontmanager 2.2, libfreetype 0.68, libawt 0.59, …) | ~5.8 MB | Yes (→ ~1.9 MB) — STORED | bionic JRE native layer |
| JRE fonts `*.ttf` (Lucida ×~6) | ~2 MB | No | JRE bundled fonts |
| `lib/arm64-v8a/librlawt.so` | 1.26 MB | Yes (→ 0.35 MB) — STORED | EGL/GLES backing (gpu/rlhd) |
| `lib/arm64-v8a/libhelloshell.so` | 0.42 MB (rel) | Yes (→ 0.18 MB) — STORED | JNI host |
| `resources.arsc` + Compose res | ~0.6 MB | n/a | App resources |
| `assets/jvm/probes.jar` | 0.14 MB | already deflated | Headless entrypoints + CACIO folded in |
| `assets/jvm/jre/lib/jrt-fs.jar` | 0.11 MB | — | JRT filesystem provider |
| dev jars: `gpu-smoke.jar` 26 KB, `hello.jar` 1.5 KB | ~0.03 MB | — | dev smoke; no prod caller |
| `assets/tokens.json` | 1 KB | — | **Live Jagex session secret packaged into the APK** (SLM-PKG-08) |

---

## 3. Ranked findings

Ranked by (saving × confidence ÷ effort/risk): big APK-size wins first, then dead-code hygiene, then deps. Saving = corrected (verified) download-MB unless noted. The three shaded-jar readers (SLM-JAR-01/SLM-PKG-01/SLM-DEP-01) are one lever counted once.

| id | title | scope | est saving MB | effort | risk | sev | verdict |
|---|---|---|---|---|---|---|---|
| SLM-JAR-01 / SLM-PKG-01 / SLM-DEP-01 | Drop shaded.jar from Android assets (never a boot target) | android | **29.4** | S–M | low–med | P1 | CONFIRM/ADJUST |
| SLM-PKG-02 | Compress JRE asset `.so` (clear/narrow `noCompress`) | android | **16.0** | M | med | P1 | ADJUST |
| SLM-JRE-04 / SLM-JRE-01 | jlink-strip the JRE module image (localedata + debug/agent/etc.) | android | **13** | L | high | P1 | ADJUST/CONFIRM |
| SLM-JRE-02 | Drop `jdk.localedata` (subset of jlink; highest-value single module) | android | **10** | M | low | P1 | CONFIRM |
| SLM-PKG-03 / SLM-JAR-04 | Compress boot jars (gpu/rlhd/probes) | android | **3.4** | S | low | P3 | CONFIRM |
| SLM-JRE-03 | Drop debug/agent/JFR/RMI/JVMCI modules + their `.so` | android | **3** | S | low | P2 | CONFIRM |
| SLM-PKG-05 | Compress app `lib/` `.so` (symbol-strip already done) | android | **1.1** | S | low | P3 | CONFIRM |
| SLM-JRE-05 | legal/ KEEP for attribution (do not delete) | android | 0.5 (do-not-pursue) | S | low | P3 | ADJUST |
| SLM-PROBE-00 | Remove 22 dormant headless probe files | shared | **0.259** | M | low | P2 | ADJUST |
| SLM-PROBE-05 | CACIO de-risk probe cluster (14 files) | shared | 0.136 | M | low | P3 | CONFIRM |
| SLM-PROBE-04 | WorldMap probe cluster (2 files) | shared | 0.044 | S | low | P2 | CONFIRM |
| SLM-PROBE-02 | MobileUiSmokeRunner (largest single probe; iOS comment-glue) | shared/iOS | 0.037 | M | med | P2 | ADJUST |
| SLM-JAR-05 / SLM-JAR-02 / SLM-PKG-10 | gpu-smoke.jar dev-only asset | android | 0.025 | S | low | P3 | CONFIRM/ADJUST |
| SLM-PROBE-03 | MenuPeek probe (downgrade 3 javadoc links) | shared | 0.021 | S | low | P2 | CONFIRM |
| SLM-PROBE-01 | GPU-diag cluster (GpuHandshakeProbe footgun) | shared | 0.017 | S | low | P1 | CONFIRM |
| SLM-PROBE-06 | ShiftFeedSpike (WelcomePlayProbe OVERTURNED) | shared | 0.008 | S | low | P3 | OVERTURN(partial) |
| SLM-JAR-03 / SLM-PKG-10 | hello.jar dev vestige | android | 0.0015 | S | low | P3 | CONFIRM |
| SLM-PKG-08 | tokens.json (live secret) packaging exclude | android | 0.001 | S | low | P1 | CONFIRM |
| SLM-DEP-03 | material-icons-core (transitive, unused) | android | 0.08 | S | high | P3 | ADJUST → no-action |
| SLM-DFLAG-01 | headless.capture armed in release | shared | 0 (perf) | S | low | P1 | CONFIRM |
| SLM-DFLAG-02 | gpuframedump armed in release | shared | 0 (perf) | S | low | P1 | CONFIRM |
| SLM-DFLAG-03 | fpsprobe armed on 117HD release | shared | 0 (perf) | S | low | P2 | CONFIRM |
| SLM-DFLAG-04 | runSeconds/captureTargetFrames sentinels | shared | 0 | S | low | P3 | ADJUST |
| SLM-PROBE-01(kt) | dev probe boot-gates ship in release | android | 0 (LOC) | M | low | P2 | CONFIRM |
| SLM-MARKER-01 | marker-file boot-trigger sprawl (8 stat/boot) | android | 0 | M | med | P3 | CONFIRM |
| SLM-NATIVE-01 | librlawt dev diagnostics + Phase-4 caps probe | android | 0 | M | med | P3 | ADJUST |
| SLM-RECON-01 / SLM-DEP-05 / SLM-PKG-09 | :recon second APK (no `:app` edge) | android | 0 | S | low | P2/P3 | CONFIRM |
| SLM-JAR-06 / SLM-PKG-07 / SLM-DEP-02 | stale/un-bootable release APK | android | 0 (P0 correctness) | M/S | low–med | P0 | CONFIRM/ADJUST |
| SLM-PKG-06 | R8/minify OFF is correct (~4 MB dex, unsafe) | android | 4 (do-not-pursue) | M | med | P3 | CONFIRM |
| SLM-PROBE-07 | HudDataBridge KEEP (live iOS consumer) | shared | 0 | S | low | P3 | CONFIRM |
| SLM-DEP-04 | appcompat required (not removable) | android | 0 | M | med | P3 | CONFIRM |
| SLM-GITIGNORE-01 / SLM-COMMENT-01 / SLM-DEBUG-01 / SLM-LATCH-01 / SLM-PKG-11 / SLM-PKG-12 / SLM-JRE-04(strip) / SLM-DEP-06 / SLM-DEP-07 | hygiene / verified-correct / negative findings | mixed | 0 | S | low | P3 | CONFIRM |

---

### Category A — JRE (embedded OpenJDK) — the installed-size mass

#### SLM-JRE-01 — jlink-strip the full 45-module image to a ~24-module keep-set  *(CONFIRM, ~20 MB download / ~47 MB installed, L, med)*
- **Evidence:** `tools/stage-android-jre.sh:107` is `cp -R "$SRC"/. "$JRE_DEST"/` — a whole-tree copy, **no jlink**. `jre/lib/modules` = 110,789,498 B; in-APK `Defl:N 42,653,574` (debug) / `38,475,877` (release, 65%). 45 legal dirs / MODULES line. `jdeps --print-module-deps` on the actual boot jars (gpu+rlhd+probes) → `java.base, java.desktop, java.instrument, java.naming, java.sql, jdk.httpserver, jdk.jshell, jdk.jsobject, jdk.management, jdk.unsupported`. A host jlink of a conservative keep-set = 24 modules, `lib/modules` raw 63.9 MB (vs 110.8), deflate-modeled ~19.6 MB.
- **Proposed fix (exact):** replace the `cp -R` with a jlinked runtime. `jlink --module-path $JDK/jmods --add-modules java.base,java.desktop,java.datatransfer,java.logging,java.management,java.naming,java.prefs,java.sql,java.xml,jdk.httpserver,jdk.jsobject,jdk.management,jdk.unsupported,java.net.http,java.scripting,jdk.dynalink,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.charsets,java.security.jgss,jdk.security.auth,jdk.zipfs,java.instrument,jdk.naming.dns --no-header-files --no-man-pages --strip-debug --output jre-slim`, then graft the hand-built `libawt_xawt.so` as today and bump `runtime.tag`. **KEEP** (do not drop): java.base, java.desktop (35 client files use `java.awt.Font`), java.datatransfer, java.logging, java.management (RuntimeMXBean/MemoryMXBean), java.naming, java.prefs, java.sql, java.xml, jdk.httpserver (**SessionManager loopback OAuth**, live on Android), jdk.jsobject, jdk.management, jdk.unsupported (Unsafe), java.instrument, java.net.http, jdk.crypto.cryptoki + jdk.crypto.ec (**SunEC TLS for runelite.net/Jagex HTTPS**), jdk.charsets, java.security.jgss + jdk.security.auth, jdk.zipfs, jdk.naming.dns. **jdk.jshell / java.compiler are ALREADY ABSENT** from the shipped image (jshell autocomplete is dead-on-arrival on Android) → omitting them is no regression.
- **Saving derivation:** download ~20–21 MB (42.65 → ~21–22 MB by the zlib model scaled to the APK-actual factor). Unpacked/installed ~47 MB off `lib/modules` (110.8 → 63.9 MB raw) → also shrinks the AssetInstaller first-launch copy + `filesDir` footprint.
- **Risk + validate:** a too-aggressive keep-set → ClassNotFound/ServiceConfigurationError in reflective/ServiceLoader/TLS paths jdeps can't see. Mitigated: crypto/charsets/scripting/jgss kept even though jdeps doesn't surface them; jdeps run *without* `--ignore-missing-deps`. **Hard blocker:** the AngelAuraMC `jre21-android-arm64` tarball ships an image only (no `.jmod`/`jdk/` tree) → jlink cannot relink a bare `lib/modules` jimage. A **jmods-bearing bionic JDK21 matching the runtime must be sourced first**, else the saving is unrealizable. Validate: boot CPU+GPU+117HD, Jagex OAuth (TLS), RuneLite-account sync (HTTPS), no module-resolution crash in logcat.
- **Scope:** android-only — iOS ships a separate Mach-O JRE; `stage-android-jre.sh` touches no iOS path.
- **Verification note:** caught a keep-set gap (jshell) that is in fact a no-op because the module is already absent from the shipped image; the in-APK saving understated by the partner finding SLM-JRE-04 (~13 MB for the droppable subset alone).

#### SLM-JRE-04 — full-image trim, in-APK saving corrected up  *(ADJUST, 13 MB download / ~33 MB uncompressed, L, high)*
- Same lever as SLM-JRE-01 read from the module-image side. **Corrections from verification:** (1) the **only** safe drop-set (everything NOT in the SLM-JRE-01 keep-set) is `{jdk.localedata, jdk.jfr, jdk.internal.vm.ci, java.sql.rowset, java.rmi, java.management.rmi, java.naming.rmi, jdk.sctp, jdk.management.agent, java.xml.crypto}` — removing these removes **~32 MB uncompressed**; in-APK saving is **~13 MB** (jdk.localedata alone gzip-9s to 10.8 MB and is ~90% of it), **not 8 MB**. (2) jlink **not executable** with the current artifact (no jmods). (3) **DROP-LIST HAZARD (load-bearing modules that an earlier draft wrongly listed as droppable — DO NOT DROP, they are in the SLM-JRE-01 keep-set):** `jdk.httpserver` (**`SessionManager.java:221` `HttpServer.create(localhost:0)` for the RuneLite-account OAuth `/oauth` loopback — the shipped account-sync flow on Android**), `jdk.crypto.ec` (**SunEC ECDHE/ECDSA TLS for runelite.net + Jagex HTTPS**), `java.sql` (in the jdeps boot-jar deps), `jdk.dynalink`+`java.scripting` (Nashorn/dynalink graph kept by SLM-JRE-01), `jdk.naming.dns` (DNS SPI). The biggest-lever claim stands (localedata ~28 MB uncompressed / ~11 MB in-APK, en-only safe); the net safe in-APK figure is unchanged because the wrongly-listed modules are tiny.

#### SLM-JRE-02 — drop `jdk.localedata`  *(CONFIRM, 10 MB download / ~29 MB installed, M, low)*
- **Evidence:** jimage per-module sum = `jdk.localedata` 29,386,575 B uncompressed — 2nd only to java.base, larger than java.desktop. No `-Duser.language/.country/.locale` in boot opts (only `-Duser.home` at MainActivity `:2546,:2665`); no `Locale.forLanguageTag/FRENCH/GERMAN/setDefault` in headless bridges or shell. Isolated jlink delta: with-localedata raw 92.7 MB / deflate ~29.7 MB vs without raw 63.9 / ~19.6 → **~10.1 MB compressed, ~28.8 MB unpacked.**
- **Fix:** exclude `jdk.localedata` from `--add-modules` (it is not in the keep-set). Fallback if the full re-link is blocked: pin `-Duser.language=en -Duser.country=US` belt-and-suspenders.
- **Risk:** only breaks if a path needs non-English CLDR (collation/currency/date). RuneLite headless does not localize via JRE locale services; java.base already carries `en`+`root`. Validate on a non-en system locale (no MissingResourceException).
- **Scope:** android-only (subset of SLM-JRE-01 — do not double-count if the full jlink lands).

#### SLM-JRE-03 — drop debug/agent/JFR/RMI/JVMCI modules + their `.so`  *(CONFIRM, 3 MB download, S, low)*
- **Evidence:** jimage bytes — jdk.jfr 1,689,247; jdk.internal.vm.ci 960,661; java.rmi+management.rmi+naming.rmi 698,004; jdk.management.agent 153,191; java.sql.rowset 541,162; java.xml.crypto 1,377,114; jdk.sctp 139,717; etc. None in the jdeps boot-jar set. JVMCI unused (libjvm.so = full C1+C2 server VM; `-XX:+UseJVMCICompiler` not set). Droppable `.so`, **Stored (Defl:N 0%) in the APK** so full bytes drop: `libjdwp.so` 254,944 + `libsctp.so` 21,784 + `libdt_socket.so` 22,312 + `libj2gss.so` 38,904 + `libmanagement_agent.so` 4,448 ≈ 0.342 MB direct.
- **Fix:** already excluded by the SLM-JRE-01 keep-set. **Independently of jlink**, delete the debug `.so` (`libjdwp.so`, `libdt_socket.so`) in `stage-android-jre.sh` post-stage — no `-agentlib:jdwp`/`-Xdebug`/`-XX:StartFlightRecording` in boot opts (confirmed).
- **Risk:** java.management is **KEPT** (in jdeps). Largely subsumed by JRE-01; standalone direct value is the ~0.3 MB Stored `.so`.
- **Scope:** android-only.

#### SLM-JRE-05 — legal/ KEEP for attribution  *(ADJUST, do-not-pursue, S, low)*
- legal/ compressed footprint corrected **0.1 → 0.5 MB** (162 entries, ~514,720 B; finder under-estimated ~4×). **Recommendation unchanged:** do NOT manually `rm` legal/ — license non-compliance (ICU/CLDR/public_suffix/Unicode + GPLv2+CE attribution). **jlink regenerates a correct minimal legal/** for exactly the kept modules — the right way to shrink it. `conf/` (148 KB) + `lib/jfr/*.jfc` (80 KB, drops only as a consequence of dropping jdk.jfr) too small to chase. **Flag the legal-retention requirement to Phase 6 (legal/licensing).**

---

### Category B — Client jars (gpu / rlhd / shaded)

#### SLM-JAR-01 = SLM-PKG-01 = SLM-DEP-01 — drop shaded.jar from Android assets  *(CONFIRM/ADJUST, 29.4 MB, S–M, low–med)*
- **Evidence:** `client-…-shaded.jar` = 30,796,676 B staged / 30,647,137 B in release APK (Method=Stored, 0%). Android boot classpath `MainActivity.kt:2618` = `[client-*-gpu.jar : client-*-rlhd.jar : probes.jar]`, `useGpuJar=true` unconditional (`:2584`); comment `:2582-2583` "not a boot target". `gpu.jar` IS the full client: `comm -12` of jar-tf(shaded) vs jar-tf(gpu) → gpu = shaded minus exactly 955 entries, **all of which are the stripped LWJGL + 1 facade class** (RuneLite.class, RuneLiteHeadless.class, Guice, slf4j all present in gpu.jar; DeviceHeadlessBoot in probes.jar). So `[gpu:rlhd:probes]` is a complete boot closure **without** shaded. `AssetInstaller.copyAssetDir('jvm')` currently unpacks shaded into the ~199 MB `filesDir` tree too.
- **Proposed fix (exact):** **prefer the packaging-exclude variant** over moving the file: in `app/build.gradle.kts` add an `androidComponents { onVariants }` / packaging exclusion for `assets/jvm/client-*-shaded.jar`, OR in `tools/stage-mobile-runtime.sh` `stage_one` skip the shaded `copy_jar` for the **Android** dest only. **Keep `SHADED_SHA` in the runtime.tag fold** (computed from `runelite-client/build/libs`, line 47/118 — NOT from assets) so the tag stays byte-stable and AssetInstaller still re-unpacks on a real jar change. **Do NOT** move shaded out of the assets dir without also updating `bridge-census/run-census-desktop.sh`, which defaults to globbing `assets/jvm/client-*-shaded.jar`.
- **Saving:** STORED → download == on-disk == ~29.4 MB; removes it from both the APK and the `filesDir` tree.
- **Risk + validate:** the surgery scripts (`stage-android-gpu-jar.sh:24`, `stage-rlhd-zone-jar.sh:30`) read shaded from `build/libs`, NOT assets — safe. Only break vector: a future flip of `useGpuJar=false` (guard with a build assertion). Validate: `assembleRelease`, `unzip -l | grep shaded` → empty, boot CPU+GPU+117HD.
- **Scope: android-only.** **iOS genuinely boots shaded.jar** (`JVMLauncher.m:961`, `project.yml:56`, `serve-ipa.sh:70`) — the Android-only exclude / `stage_one 'Android'` skip must NOT touch the iOS bundle or the iOS `stage_one` copy.
- **Verification note:** ADJUST only on fix-detail — surgery source is `build/libs`, not assets (so no script repoint needed); the 30 MB is a steady-state saving against a *correctly-built* APK and is realizable only **after** the stale-APK defect (SLM-JAR-06) is fixed so the rebuilt APK actually ships gpu+rlhd+librlawt.

#### SLM-JAR-04 = SLM-PKG-03 — compress the boot jars  *(CONFIRM, 3.4 MB download, S, low)*
- **Evidence:** jars are Stored solely via `noCompress "jar"` (`build.gradle.kts:41`) but their entries are already internally DEFLATEd (gpu.jar = 12,451 deflated entries vs 637 stored). gzip-9: gpu 28.84 → 25.54 MB (−3.30), rlhd 9.10 → 9.03 MB (−0.067, PNG/JSON pre-compressed), probes −0.015. Sum (gpu+rlhd+probes, excl shaded) = **~3.4 MB**.
- **Fix:** one-line — `noCompress += listOf("so","dat","bin")` (drop `"jar"`). No code change. `AssetInstaller.copyAssetDir` uses `ctx.assets.open()` which transparently decompresses → runtime-neutral (the 110 MB modules image already DEFLATEs 62% in-APK and boots through the same path, proving safety at the largest scale).
- **Risk:** download-only win; longer first-unzip CPU (one-time, on tag change). No mmap/openFd path reads jars in-place. Validate jar SHA after first-launch copy; boot 117HD (needs rlhd.jar intact).
- **Scope:** android-only. **Do NOT over-credit jar compression** — content is pre-deflated; this is small. Combined with SLM-PKG-02 the single `noCompress` change yields ~20 MB download.

#### SLM-JAR-03 — gpu/rlhd cannot share a common base  *(CONFIRM, 0 MB, negative finding)*
- `comm -12` of gpu vs rlhd entries = **0 real shared entries**. rlhd.jar (590 entries) is pure `rs117/hd/**` data (gamevals.json 4.0 MB, model_overrides.json 2.3 MB, lights.json 0.89 MB, + ~30 normal-map PNGs) + 1 `RLHDProbe.class` — **no `net/runelite/client/*`**. They are *already* base(client in gpu.jar) + delta(rlhd.jar). **No further dedup available.** Optional out-of-scope future track: minify rlhd scene JSON / recompress PNG normal maps (measure separately; preserve exact `model_overrides` values).

---

### Category C — Packaging / compression

#### SLM-PKG-02 — compress JRE asset `.so`  *(ADJUST, 16.0 MB download, M, med)*
- **Evidence:** 33 JRE `.so` under `assets/jvm/jre`, all Stored 0%, total 24,268,184 B; `libjvm.so` 18,497,776 B Stored. gzip-9: libjvm → 5,836,519; aggregate 24,268,184 → 8,262,508 = **16,005,676 B saved (~16.0 MB, corrected from 16.7)**. NOT in `lib/` — `AssetInstaller.copyAssetDir` recursively copies the whole jvm tree to `filesDir` first; `jvm_bootstrap.cpp` dlopens by absolute `filesDir` path.
- **Fix:** narrow/clear `noCompress` so it stops matching JRE asset `.so` — `noCompress += listOf("so","dat","bin")` keeps the dlopen-by-path `.so` protected if you only want to compress jars; to capture the 16 MB you must **let the JRE `.so` compress** (they are still extracted to `filesDir` before dlopen, so safe). `noCompress` also guards `.dat` (public_suffix_list 229 KB, tzdb 103 KB) — both copied to filesDir then read normally, safe to compress.
- **Risk: med** (clearing `noCompress` is a global packaging-policy change). No `openFd/AssetFileDescriptor/mmap/AAssetManager` direct in-APK read anywhere (grep clean). Download-only; `filesDir` footprint unchanged. Validate sha256 of each `filesDir/jvm/.so` vs source post first-launch + boot all 3 renderers + measure cold-start unpack delta.
- **Scope:** android-only.

#### SLM-PKG-05 — compress app `lib/` `.so` (symbol-strip closed)  *(CONFIRM, 1.1 MB download, S, low)*
- `librlawt.so` / `libhelloshell.so` already `nm`-stripped (0 symbols) → **symbol-strip sub-lever = 0 MB**. `lib/arm64-v8a/*.so` Stored; gzip-9 delta ~1.1 MB (release). `extractNativeLibs=true` (`AndroidManifest.xml:28`) is **REQUIRED** for the JRE dlopen-from-filesDir story — do NOT flip to false. With it true, lib `.so` decompress on install → download-only, runtime-neutral. Lowest-value lever.

#### SLM-PKG-06 — R8/minify OFF is correct  *(CONFIRM, 4 MB do-not-pursue, M, med)*
- `release isMinifyEnabled=false, isShrinkResources=false` (`:56-67`). `classes.dex` 13.33 + `classes2.dex` 5.63 = ~19 MB is **Compose/AndroidX/Kotlin shell only** (`strings ... | grep Lnet/runelite/client/headless` = 0 hits) — the engine is in asset jars, untouchable by R8. R8 on (full Compose keep rules) → ~4 MB dex win but **unsafe**: 129 `Java_dev_audaro_helloshell_JvmLauncher_native*` JNI entry points by exact symbol + 15 `FindClass("net/runelite/client/headless/<Bridge>")` literals + `UnsafePokeActivation`/`DeviceHeadlessBoot` by name would break under rename/shrink. ~4 MB not worth the risk on a personal sideload. **Keep OFF.** Defer to Phase 5/7 only if ever Play-bound.

#### SLM-PKG-08 — tokens.json (live secret) in the APK  *(CONFIRM, 0.001 MB, S, low; security severity owned by security phase)*
- `assets/tokens.json` (1091 B, mode `-rw-------`, gitignored) is packed into the APK regardless of gitignore (AGP packs all of `src/main/assets`). `JagexTokens.readSource` treats bundled `assets/tokens.json` as a lowest-priority token source → **a live Jagex session secret ships to anyone with the APK.**
- **Fix:** `packaging { resources { excludes += "assets/tokens.json" } }` OR move the dev session into `src/debug/assets` so release never carries it; first launch then gates via `LoginActivity`. **Coordinate severity with the security phase.**

#### SLM-PKG-09 = SLM-RECON-01 = SLM-DEP-05 — :recon second APK  *(CONFIRM, 0 MB to :app, S, low)*
- `settings.gradle.kts:18-19` includes `:app` + `:recon`; **no dependency edge** (`grep 'project(":recon")'` = 0). `recon-debug.apk` = 2,412,529 B, separate `applicationId=dev.audaro.glesrecon`, own MAIN/LAUNCHER, no NDK/JVM. `app-release.apk` contains **zero** glesrecon/gpu-shaders bytes → **0 MB :app bloat.** Build-hygiene only: bare `./gradlew assemble` builds both APKs. **Fix:** target `:app:assembleRelease` explicitly (already excludes recon); optionally gate `include(":recon")` behind a gradle property. **Keep the module** — removing it breaks the Firebase GLES-recon flow (`fb-gate.sh`). Today's deploy paths already scope to `:app:…` so recon is never built by accident.

#### SLM-PKG-11 — ABI is clean (arm64-v8a only)  *(CONFIRM, 0 MB, negative finding)*
- `ndk.abiFilters += "arm64-v8a"`; no x86/i386/amd64/armeabi leakage (grep clean). **Forward-looking:** a second ABI ≈ **+132 MB JRE + ~18 MB libjvm + native** per extra ABI — never ship a fat universal APK; if x86_64 is ever needed (Intel-host emulator), use a separate variant/APK split. Keep arm64-only.

#### SLM-PKG-12 — .aab vs single-ABI APK  *(CONFIRM, 0 MB, informational/deferred)*
- Single ABI + single-density Compose UI + the unsplittable `assets/jvm` payload → a base `.aab` download ≈ the single-ABI APK. `jdk.localedata` is inside the JRE jimage (not Android res/) so language-split can't reach it. The only Play lever (Play Asset Delivery to move ~146 MB off base) is moot — app is **NOT Play/Apple distributable** (per the project's risk policy: sideload-only, ban-risk personal build). **Defer to Phase 5/7.**

---

### Category D — Shared-bridge probes (dead Kotlin/Java headless paths)

> SLM-PROBE-00 is the master removal unit; SLM-PROBE-01..06 are the per-cluster edits. **22 of 23** probe files are safe to remove; **WelcomePlayProbe is NOT** (see SLM-PROBE-06 / Rejected). All probes live in `runelite-client/.../headless/` and are compiled into **gpu.jar (boot) + shaded.jar (build-side)**; jars ship STORED so removing a `.class` drops its uncompressed bytes 1:1.

#### SLM-PROBE-00 — master plan, 22 dormant probe files  *(ADJUST, 0.259 MB, M, low)*
- **Evidence:** 23 probe `.java` files = 9,739 LOC / 324,855 B source → 43 `.class` (incl. nested) = 277,186 B uncompressed in gpu.jar. Probes absent from rlhd.jar and probes.jar. Reference audit (all four surfaces) clean: native cpp = 0 probe FindClass; Kotlin = only `worldmapprobe.mode` marker (`MainActivity.kt:2748-2750`) + `ShiftFeedSpike` comment (`:577`); shared-contract `baseline.txt` = 0 probe flags (extract-contract.py uses an explicit 20-file list excluding probes); iOS source = 0. Each probe is referenced only by `HeadlessSeams` `if (X.isEnabled()) guard(...)` blocks + javadoc.
- **Fix:** phased `git rm` by cluster (delete file(s) + their `HeadlessSeams.isEnabled()` blocks; same-package so no import lines to remove). After all: re-stage jars (`:client:shadowJar` → `stage-android-gpu-jar.sh` + `stage-rlhd-zone-jar.sh` + `stage-mobile-runtime.sh`), run `check-contract.sh` (expected PASS), validate each phase with `:client:compileJava`.
- **Saving:** **corrected 0.264 → 0.259 MB** — WelcomePlayProbe (5,783 B) is **LIVE on iOS** and must stay; gross-achievable = 277,186 − 5,783 = 271,403 B (0.259 MB). **Of that, MobileUiSmokeRunner (38,884 B, SLM-PROBE-02) is shared/iOS-impacting (iOS comment-glue) and is NOT in the android-only-safe batch** → the clean android-only-safe set = 271,403 − 38,884 = **232,519 B (0.222 MB)**; the remaining 0.037 MB is realizable only as a deliberate iOS-coordinated change.
- **Risk:** low (no JNI-by-name/reflection/Class.forName/Guice/main reaches any of the 22). The compile-breakers: HeadlessSeams blocks; the GpuSceneDiag↔TracingDrawCallbacks hard instantiation cluster; MenuPeekBridge's 3 `{@link MenuPeek}` javadoc; RuneLiteHeadless `MobileUiSmokeRunner.arm()`; the WorldMapProbe Kotlin marker-arm. **Verification correction:** the "no dev-tools harness refs" claim was FALSE — ~22 `ios/dev-tools/headless/run-*.sh` scripts arm these flags; they don't break the build (not on classpath) but become **dead harnesses to orphan/clean knowingly**.
- **Scope: shared/iOS-impacting** (iOS shares the jar but arms no probe flag except WelcomePlayProbe; references no probe class).

#### SLM-PROBE-01 — GPU-diag cluster (GpuSceneDiag + GpuHandshakeProbe + TracingDrawCallbacks)  *(CONFIRM, 0.017 MB, S, low; P1 footgun)*
- 3 files / 4 `.class` / 18,117 B. **HARD compile coupling:** `GpuSceneDiag:131 new TracingDrawCallbacks(cb)`, `GpuHandshakeProbe:130 new TracingDrawCallbacks(new NoOpCallbacks())` → delete as one unit. HeadlessSeams hooks: GpuHandshakeProbe `:152-159`; GpuSceneDiag `:161-166` (tick) **AND `:393-398` (onDrawScene — second hook, easy to miss)**. **P1** not for size but because GpuHandshakeProbe is a state-mutating footgun the codebase warns "must NEVER be armed on device" (mutates `setDrawCallbacks/setGpuFlags/setExpandedMapLoading`) — removing it kills the risk that a stray `-Drunelite.gpuhandshake` corrupts the real GpuPlugin handshake. Validate `:client:compileJava` after removing both seam blocks.

#### SLM-PROBE-02 — MobileUiSmokeRunner  *(ADJUST → CONFIRM-with-iOS-coordination, 0.037 MB, M, med; shared/iOS-impacting)*
- Largest single probe: 39,779 B / 1,118 LOC; 4 `.class` = 38,884 B. Gate `runelite.mobileui.smoke` never set on Android. **Unique wiring:** not in HeadlessSeams — `RuneLiteHeadless.java:51 MobileUiSmokeRunner.arm()` is called unconditionally on the **shared** boot path, but inert without the flag (`:48-50`).
- **iOS-impact correction (verification gap caught in re-review):** the iOS glue is **not** clean of this class — `ios/app/HelloShell/JVMLauncher.m:153,:159` document the `MobileUiSmokeRunner.nativeColorReadback` byte contract (`0xFF123456 == new Color(0x12,0x34,0x56,255).getRGB()`), and `:1096` is a commented self-test re-enable that references it ("To re-run the self-test, re-add: … the MobileUiSmokeRunner bytes"). The **active** iOS path does not arm it (so no runtime break), but deleting the Java class **orphans the iOS self-test re-enable path and staleifies the byte-contract comments** → this is a coordinated **shared/iOS-impacting** change, NOT a clean android-only delete.
- **Fix (only if taken):** `git rm` the file + delete `RuneLiteHeadless.java:51` + the `:48-50` comment **AND** in the same change scrub the dead iOS references (`JVMLauncher.m:153,:159,:1096`) so the contract doc doesn't dangle. **Recommendation: do NOT include in the android-only safe batch — either KEEP it (dormant, 0.037 MB) or defer to a deliberate iOS-coordinated cleanup.** Behavior is preserved on both ports either way; the risk is documentation/dev-path drift, not a boot break.

#### SLM-PROBE-03 — MenuPeek (probe, ≠ MenuPeekBridge)  *(CONFIRM, 0.021 MB, S, low)*
- 27,373 B / 829 LOC; 3 `.class` = 21,944 B. Gate `runelite.menupeek.probe` (`:78`) is **DISTINCT** from the LIVE `runelite.menupeek` (MenuPeekBridge). **Coupling:** the LIVE-SHARED `MenuPeekBridge` has 3 `{@link MenuPeek}` javadoc refs (`:36,:165,:254`). **Fix:** `git rm MenuPeek.java`; delete HeadlessSeams `:319-324`; downgrade the 3 `{@link MenuPeek}` → `{@code MenuPeek}` (cosmetic; javadoc not on the compile gate). **Do NOT touch the live `runelite.menupeek` flag / MenuPeekBridge** (P0 touch-targeting; native FindClass targets MenuPeekBridge only). iOS arms `-Drunelite.menupeek=true` (live) but not `.probe`.

#### SLM-PROBE-04 — WorldMap cluster (WorldMapProbe + WorldMapOrbsFix)  *(CONFIRM, 0.044 MB, S, low)*
- 2 files / 4 `.class` = 46,619 B (largest cluster win). WorldMapProbe 43,198 B / 1,406 LOC is the largest probe by LOC. Gates `runelite.worldmapprobe` / `runelite.orbsfix` (orbsfix armed nowhere). **Unique:** WorldMapProbe is the only probe with a real shell arm path — `MainActivity.kt:2748-2750` sets `-Drunelite.worldmapprobe=true` iff a pushed `worldmapprobe.mode` marker exists (default-absent dev-only). **Fix:** `git rm` both; delete HeadlessSeams `:189-193` + `:195-202`; **delete the Kotlin dev-arm `MainActivity.kt:2745-2751`** (only reader of the marker). WorldMapOrbsFix references WorldMapProbe only as `{@code}`/comment (no compile coupling). iOS `run-worldmap-probe*.sh` harnesses go dead on removal (clean up).

#### SLM-PROBE-05 — CACIO de-risk cluster (14 files)  *(CONFIRM, 0.136 MB, M, low)*
- 14 files / 24 `.class` / 142,503 B — the bulk of the slimming. Stone/ChromeHide/Minimap/MobileChrome-misc/EdgeToEdge/ChatRelocate desktop bring-up experiments, all flag-gated, dormant both ports, **productized into the LIVE HudVeneerBridge/MobileChromeBridge**. Coupling is **javadoc-only intra-group** (MinimapRelocateProbe `:50,:53`; HudIsolationTest `:51`) — vanishes when all 14 go together; **no LIVE bridge `{@link}`s any of them.** No hard `new XProbe`. **Fix:** `git rm` all 14 + their HeadlessSeams blocks (`:222-303` region); recommend 5 sub-batches (one per cluster) for reviewable diffs. ~12 iOS `run-*.sh` harnesses go dead (clean up). Lowest-risk hygiene.

#### SLM-PROBE-06 — ShiftFeedSpike  *(partial OVERTURN, 0.008 MB, S, low for the safe half)*
- **ShiftFeedSpike IS removable** (9,286 B/273 LOC; gate `runelite.shiftfeed` armed nowhere; only comments at `MainActivity.kt:577` + `JavaBridge.java:2157`; standalone). `git rm ShiftFeedSpike.java` + HeadlessSeams `:326-330`. Saving = 8,576 B = 0.008 MB. **WelcomePlayProbe is OVERTURNED — see Rejected candidates.**

#### SLM-PROBE-07 — HudDataBridge: KEEP (scope guard)  *(CONFIRM, 0 MB)*
- **DO NOT REMOVE.** HudDataBridge.java (497 LOC) is LIVE-SHARED and pinned in `baseline.txt` (FLAG `runelite.huddata` + 4 METHODs). On Android it is census-only (`MainActivity.kt:2741`), but on **iOS it is an unconditional LIVE PRODUCTION consumer** — `JVMLauncher.m:1070` sets `-Drunelite.huddata=true` unconditionally; `[JVMLauncher hudSnapshot]`/`hudSeq` feed `ViewController.updateHudLayout` (`ViewController.m:3250`) at ~10 Hz for the native chat-at-top + HUD veneer. Deleting it would break the shared contract, NPE the iOS HUD veneer at boot, and lose Android census diagnostics. Explicit boundary around the safe probe set.

---

### Category E — Dead Kotlin / native paths + dev flags (0-MB, hygiene/perf)

#### SLM-DFLAG-01 — headless.capture armed in release  *(CONFIRM, 0 MB / perf, S, low)*
- `MainActivity.kt:2667/2669/2670` unconditionally append `-Drunelite.headless.capture.dir/.interval=10/.preLoginMax=40` on EVERY boot. Shared `HeadlessCapture` then runs per-frame `drawCount` bookkeeping for the whole session + writes up to ~26 PNGs. **Fix:** gate behind `BuildConfig.DEBUG || censusMode` in `MainActivity.kt:2667-2670` (Android-side flag → android-only change; iOS sets its own dir and **already #if-DEBUG-gates** these — proven mirror). Absent dir → `HeadlessCapture` fully inert (`if(!ENABLED) return`). Note: day-to-day deploy is the **debug** apk so this changes only `assembleRelease` behavior.

#### SLM-DFLAG-02 — gpuframedump armed on GPU+117HD release  *(CONFIRM, 0 MB / perf, S, low)*
- `MainActivity.kt:2760-2762` (GPU) + `:2772-2774` (117HD) append `-Drunelite.gpuframedump=true` + `.dir`. `JavaBridge:280 FRAME_DUMP` → `maybeDumpFrame` writes up to 8 PNGs to external storage. Both flags in the same `opts.addAll` → atomic gate. **Fix:** gate behind `BuildConfig.DEBUG` (Firebase `fb-gate.sh` uses the debug apk so CI evidence flow preserved). Flags ARE in `baseline.txt:6-7` but the **consumer** read (JavaBridge) is untouched → contract stays green.

#### SLM-DFLAG-03 — fpsprobe armed on 117HD release  *(CONFIRM, 0 MB / perf, S, low)*
- `MainActivity.kt:2777` appends `-Drunelite.fpsprobe=true` in the `if(rlhd)` block. `Hooks.java:103/291/304` → once/sec `[fpsprobe]` logcat + a per-frame fps branch. NOT in `baseline.txt`. **Fix:** gate behind `BuildConfig.DEBUG`.

#### SLM-DFLAG-04 — runSeconds/captureTargetFrames sentinels  *(ADJUST, 0 MB, S, low)*
- `MainActivity.kt:2671-2672` append `headless.runSeconds=86400` + `captureTargetFrames=999999`. **Risk corrected med → low:** the only consumer is `DeviceHeadlessBoot.main` (poll loop, returns when `frames>=targetFrames`); when it returns, `jvm_bootstrap.cpp:1373-1379` logs "VM stays alive (engine threads own the run)" then parks forever, and the engine runs on its own non-daemon threads. **Proof:** iOS runs with `runSeconds=240/captureTargetFrames=999` and is a live indefinite session. Removing these → DeviceHeadlessBoot loops 60 s (default) then parks; **no early session death.** In `baseline.txt:1-2` but the consumer read is unchanged → contract green. **Fix:** move into the same DEBUG/census branch as the capture flags; still run the multi-minute soak the finding prescribes.

#### SLM-PROBE-01 (Kotlin) — dev probe boot-gates ship in release  *(CONFIRM, 0 MB / LOC, M, low)*
- `MainActivity.onCreate` early-returns into dev/CI probe paths that stat markers on EVERY boot: `netprobe.mode` `:807`, `gpuSmoke` intent `:812`, `rlhdprobe` `:823-824`, `worldmapprobe.mode` `:2748`, census `:801` (+ `runA4/A6SeamSelfTest` `:2804-2807`/`pollEnabled`). All triggers are dev/CI (intent extras / adb markers). **Fix:** wrap `:807-830`, `:2748-2751`, the census self-test `:2804-2807` + impls in `if (BuildConfig.DEBUG) { … }` — R8 is off so this relies on **DEBUG const-folding** (pattern already used at `:323,:588,:632`; Kotlin elides `if(false){}`). Every probe-triggering harness uses the **debug** apk so no documented path breaks. **Caution:** the `censusMode` VARIABLE (`:801`) must NOT be wrapped (it feeds the login gate `:893`); only the self-test INVOCATION + early-return gates. **Keep renderer markers `gpu.mode/rlhd.mode/rlhd.once/cpu.mode`** ungated (legit Firebase-Robo/TV triggers).

#### SLM-MARKER-01 — marker-file boot-trigger sprawl  *(CONFIRM, 0 MB, M, med)*
- 8 marker files `stat()`'d per boot: `netprobe.mode`, `rlhdprobe.mode`, `gpu.mode`, `rlhd.mode`, `rlhd.once`, `cpu.mode`, `rlhd_prelogin.mode`, `worldmapprobe.mode` (6 on the onCreate UI thread, 2 deferred to the boot thread). **Fix:** (1) move the **pure-dev** markers (netprobe/rlhdprobe/worldmapprobe/rlhd_prelogin) behind `BuildConfig.DEBUG` with SLM-PROBE-01; (2) **KEEP** `gpu.mode/rlhd.mode/rlhd.once/cpu.mode` (load-bearing for Firebase Game-Loop/Robo/TV renderer selection — Robo can't pass intent extras) but centralize the reads into one `resolveRendererTriggers()` helper (precedence: cookie → probe → intent → marker → TV → pref). **Do NOT** remove the crash-cookie or the persisted `renderer/mode` pref (the real selector/safety). All harness APKs are `assembleDebug` so the DEBUG-gate is a no-op loss in CI; risk med = the only way to break is mis-gating the load-bearing four (the fix forbids it).

#### SLM-NATIVE-01 — librlawt dev diagnostics + Phase-4 caps probe  *(ADJUST, 0 MB, M, med)*
- `setprop debug.routec.*`-gated diagnostics (`gputime/drawcount/swapinterval/clipcontrol/shaderdump`) + `rcProbePhase4Caps()` called once-per-context-create (`:828`, not per-frame, ~0 perf) + no-op `rc_glMultiDrawArraysIndirect/rc_glBufferStorage_*` forwarders + dead legacy CPU-readback path (`:1034-1071`). Phase 4 is CLOSED. **Fix:** wrap the setprop diagnostics in `#ifdef RLAWT_DEBUG` (compile out of release, keep source); `#ifdef`-out the `rcProbePhase4Caps()` call. **Size correction:** the finding said "librlawt.so ~0.42 MB" — that is *libhelloshell.so*; the real **librlawt.so is 1.26 MB** (and is absent from the stale release APK; debug only). Saving still 0 MB (a few KB of code). **Risk softened:** dropping the no-op forwarders is actually call-safe today (caps reported FALSE → never invoked; UnsatisfiedLinkError only if a removed native is *called*) — but **KEEP the bindings** (conservative; the gpu-zone facade still declares them). Do NOT touch the clip-control self-test / reverse-Z. iOS uses a SEPARATE JNI (`routec_zone_jni.m`) → editing librlawt.cpp can't ripple to iOS.

#### Hygiene / verified-correct / negative (all 0 MB, CONFIRM)
- **SLM-GITIGNORE-01** — `.gitignore:17 app/src/main/assets/jvm/cacio/` references a dir that no longer exists (CACIO folded into probes.jar). Delete the line + fix the `:14` "CACIO jars" comment. Pure hygiene.
- **SLM-COMMENT-01** — `PanelView.kt:69-71` historical comment names removed `PanelList/NativeRow`; **not a dangling code ref** (symbols genuinely gone). Optional trim.
- **SLM-DEBUG-01** — `SET_HD_CONFIG`/`SET_RECONNECT_OVERLAY` BroadcastReceivers + `reconnectTestOverride` are correctly `BuildConfig.DEBUG`-gated (`:323-369`); no exported-receiver surface ships in release. No action; optional debug-source-set extraction.
- **SLM-LATCH-01** — one-shot `chromeUiLogged`/`chatTabsLogged` green-log latches emit one release logcat line each (`:1927-1928`, `:1970-1971`); ~0 perf. Optional `BuildConfig.DEBUG` wrap; bundle into a log scrub.
- **SLM-JAR-05 / SLM-PKG-10 / SLM-JAR-02** — `gpu-smoke.jar` (26,472 B) dev-only AG1a smoke (only reached via `--ez gpuSmoke true`, `MainActivity.kt:812/2461-2492`); git-tracked, **DOES ship in a fresh release** (no packaging exclude — the stale release APK lacks it only by staleness). **Fix:** move to `src/debug/assets` or gate behind `BuildConfig.DEBUG`; keep git-tracked (copy-on-demand from assets). 0.025 MB.
- **SLM-JAR-03(dup) / SLM-PKG-10** — `hello.jar` (1,526 B) Milestone-A vestige; zero callers; explicitly un-ignored at `android/.gitignore:32`; ships in every APK. `git rm` + drop the `!hello.jar` line. 0.0015 MB.

---

### Category F — Gradle deps

#### SLM-DEP-03 — material-icons-core (transitive, unused)  *(ADJUST, 0.08 MB, S, high → NO ACTION)*
- Pulled transitively by material3 1.3.0 (icons-core 1.7.0); zero `Icons.*` usage in app source. **But the saving estimate was wrong (thin set):** icons-core 1.7.0 is **293 classes / 1.9 MB uncompressed / ~465 KB dex / ~81 KB deflated in-APK** (bundles the full default icon set). R8 is OFF so all 293 classes are dexed regardless of usage. **Risk corrected med → high:** 8 material3 classes (ExposedDropdownMenuDefaults, DatePicker, Snackbar, SegmentedButtonDefaults) `getstatic` icons-core fields → `exclude(module="material-icons-core")` is a **guaranteed compile/link break**, not a maybe-runtime NoClassDefFound. **NO ACTION** — sub-0.1 MB download for a high-risk exclude, dwarfed by the 110 MB JRE.

#### SLM-DEP-04 — appcompat required (not removable)  *(CONFIRM, 0 MB, M, med)*
- Hard dependency: both Activities `: AppCompatActivity()` (`MainActivity.kt:66`, `LoginActivity.kt:40`); manifest `Theme.AppCompat.NoActionBar` (no custom themes.xml); appcompat `AlertDialog` at 3 sites. Heavily linked (472 androidx/appcompat refs in dex). Theoretical removal (→ ComponentActivity + theme swap + AlertDialog migration) nets **well under 1 MB** vs migration risk to the immersive/cutout window setup + dialogs. **NO ACTION;** record the coupling.

#### SLM-DEP-06 — compose-bom 2024.09.00 is current  *(CONFIRM, 0 MB, no action)*
- BOM → Compose UI 1.7.0 + material3 1.3.0, matched to `compileSdk 35`; AGP 8.6.0 + Kotlin 2.0.20 + compose-compiler 2.0.20 mutually consistent. Edge-to-edge handled manually. No currency action; bump only when compileSdk is raised.

#### SLM-DEP-07 — all direct deps VERIFIED used  *(CONFIRM, 0 MB, negative finding)*
- core-ktx, appcompat, browser, activity-compose, material3, compose-ui, kotlinx-coroutines-android all have live consumers. `androidx.browser` is used **beyond** Custom-Tab login (`MainActivity.kt:1830` engine outbound Wiki/HiScore links, reachable via `nextLink()` `:1758-1760`). **No removable direct dependency.** (Evidence text undercounts deps and miscategorizes appcompat as transitive — it is a heavily-used DIRECT dep; this only reinforces "nothing removable".)

#### SLM-JAR-06 = SLM-PKG-07 = SLM-DEP-02 — stale/un-bootable release APK  *(CONFIRM/ADJUST, 0 MB, P0)*
- The on-disk `app-release.apk` (116 MB, built Jun 8) contains only `shaded.jar + hello.jar + jrt-fs.jar + probes.jar` and **NO gpu.jar/rlhd.jar/librlawt.so** → as built it **cannot boot** (`clientJar==null → booted=false` at `MainActivity.kt:2605-2609`; `librlawt` dlopen'd at `jvm_bootstrap.cpp:1287/1328`). The debug APK (Jun 22) is correct. **It is a stale local build output, NOT git-tracked** (SLM-DEP-02 verification: `build/` is gitignored; the "don't commit build outputs / .gitignore them" fix is already implemented — there is nothing to remove from git). The defect is **build-reproducibility**: the GPU-variant staging runs manually and was not part of the assembled release. **Fix:** make `stage-android-gpu-jar.sh` + `stage-rlhd-zone-jar.sh` + the librlawt CMake target part of the canonical release pipeline (in §3.5 order so SHAs fold into `runtime.tag`); add an `assembleRelease` assertion that fails if `assets/jvm` lacks `client-*-gpu.jar` or `lib/` lacks `librlawt.so`. **This blocks a clean release and contaminates every release-APK size measurement** — re-baseline from a fresh consistent `assembleRelease`. (Path nit: `stage-android-jre.sh`/`stage-mobile-runtime.sh` are at `tools/`, the gpu/rlhd staging at `android/dev-tools/gpu/`.)

---

## 4. Safe-removal sequencing

**Stage 0 — unblock the baseline (P0, do FIRST):**
- **SLM-JAR-06/SLM-PKG-07/SLM-DEP-02** — wire GPU-variant staging into the canonical release build + add the `assembleRelease` asset/lib assertion; re-baseline sizes from a fresh consistent build. Every download-size number below is meaningful only against this. (med risk in the wiring; mitigate with the assertion + the runtime.tag fold-in.)

**Stage 1 — high-value, no new toolchain artifact (do next; android-only, no contract bump):**
- **SLM-JAR-01/SLM-PKG-01/SLM-DEP-01** — drop shaded.jar from Android assets (packaging-exclude variant; keep `SHADED_SHA` fold; iOS `stage_one` untouched). **−29.4 MB.**
- **SLM-PKG-02 + SLM-PKG-03 + SLM-PKG-05 (= SLM-JAR-04)** — narrow `noCompress` to `["so","dat","bin"]` and let assets compress. **−~20 MB download.** (med risk — global packaging change; sha256-validate `filesDir` `.so` post first-launch + boot all 3 + measure cold-start.)
- **SLM-PKG-08** — exclude `tokens.json` from packaging (coordinate with security phase).
- **SLM-JAR-05/SLM-PKG-10** (gpu-smoke), **SLM-JAR-03(dup)** (hello.jar), **SLM-GITIGNORE-01**, **SLM-COMMENT-01** — trivial hygiene; bundle with the shaded staging cleanup.

**Stage 2 — dev-flag gating (android-only changes; consumers shared but reads unchanged → contract stays green; mostly perf/hygiene):**
- **SLM-DFLAG-01/02/03/04**, **SLM-PROBE-01(Kotlin)**, **SLM-MARKER-01**, **SLM-LATCH-01** — gate dev capture/dump/probe paths behind `BuildConfig.DEBUG`. Keep renderer markers + censusMode variable ungated. Re-confirm Firebase/Robo flows (all `assembleDebug`, so unaffected).

**Stage 3 — shared-contract bump + device re-verification (probe removals; needs `:client:compileJava` + `check-contract.sh`, then device batch):**
- **SLM-PROBE-00** master unit, executed as **SLM-PROBE-05** (14-file CACIO cluster, 5 sub-batches) → **SLM-PROBE-04** (worldmap + Kotlin dev-arm) → **SLM-PROBE-02** (MobileUiSmokeRunner + shared RuneLiteHeadless edit) → **SLM-PROBE-03** (MenuPeek + javadoc downgrade) → **SLM-PROBE-01** (GPU-diag footgun) → **SLM-PROBE-06** (ShiftFeedSpike only). **−0.259 MB**, 22 files. These edit **shared `headless/`** code (rebuild jars, re-stage, refresh `runtime.tag`) and orphan ~22 iOS `run-*.sh` harnesses (clean up). **KEEP** WelcomePlayProbe (SLM-PROBE-06 Rejected) and HudDataBridge (SLM-PROBE-07). Re-verify all 3 renderers on device (end-of-audit device batch).
- **SLM-NATIVE-01** — `#ifdef RLAWT_DEBUG` the librlawt diagnostics + drop the Phase-4 caps probe call; **device re-verify GPU+117HD render** (it edits the shipping `.so`).

**Stage 4 — JRE jlink (the big installed-size lever; gated on a jmods-bearing bionic JDK21):**
- **SLM-JRE-01 / SLM-JRE-04 / SLM-JRE-02 / SLM-JRE-03** — produce a jlinked runtime (keep-set above; drop localedata + debug/agent/JFR/RMI/JVMCI; KEEP httpserver + crypto.ec). **−~13–21 MB download / ~33–47 MB installed.** **BLOCKER:** source/build a bionic JDK21 *with `.jmod`s* matching the runtime first. Full device validation: 3 renderers + Jagex OAuth (TLS) + RuneLite-account sync (HTTPS), no module-resolution crash. Goes to the **end-of-audit device batch.**

**Gated on Phase 6 (legal/licensing):**
- **SLM-JRE-05** — legal/ retention. Do NOT manually delete (ICU/CLDR/public_suffix/Unicode + GPLv2+CE attribution); let jlink regenerate the minimal legal/ for the kept modules. Phase 6 confirms the attribution requirement before any aggressive trim.

**Deferred to Phase 5/7 (not pursued now):**
- **SLM-PKG-06** (R8, ~4 MB, unsafe vs FindClass/JNI), **SLM-PKG-12** (PAD/.aab, app not distributable), **SLM-DEP-03** (icons-core, high-risk compile break), **SLM-DEP-04** (appcompat migration, sub-1 MB).

---

## 5. Rejected / refuted candidates

What was checked and found **unsafe or non-existent** (recorded so later phases don't re-chase):

| id | candidate | verdict | refutation |
|---|---|---|---|
| **SLM-PROBE-06 (WelcomePlayProbe half)** | remove WelcomePlayProbe as a dead spike | **OVERTURN** | `-Drunelite.headless.welcomeplay=true` is armed **LIVE and uncommented on the production iOS launch path** (`JVMLauncher.m:1195`, inside `if(autologin)` on every Jagex-token boot). It is the load-bearing iOS black-scene fix (commit 413e028634): `WelcomePlayProbe.tick()` clicks the real `WelcomeScreen.PLAY` widget (InterfaceID 378) to enter the world. Deleting it breaks the iOS COMPILE (HeadlessSeams ref) AND changes live iOS world-entry; the `HeadlessLogin.tryDismissWelcome()` pixel-fallback is not a proven static substitute. **KEEP** (5,783 B). Risk med. |
| SLM-JRE-04 (strip-debug sub-lever, formerly SLM-JRE-04 P3) | `strip`/`--strip-debug` the JRE `.so` for a symbol-saving | refuted (0 MB) | `file libjvm.so`/`libfontmanager.so` → already `stripped`; a strip pass yields ~0. And `lib/server` (18.5 MB C2 JIT libjvm) is load-bearing — do NOT swap to Zero (kills JIT/fps). |
| SLM-PKG-05 (symbol-strip sub-lever) | strip symbols from app `.so` | refuted (0 MB) | `librlawt.so`/`libhelloshell.so` already `nm`-stripped (0 symbols); NDK release strips. The lib-compression sub-lever survives at ~1.1 MB but symbol-strip is closed. |
| SLM-JAR-03 / SLM-PROBE (rlhd dedup) | extract a shared client base from gpu.jar + rlhd.jar | refuted (0 MB) | `comm -12` = 0 real shared entries; rlhd.jar is pure `rs117/hd` data with no `net/runelite/client/*` — already base+delta. Nothing to factor out. |
| SLM-PROBE-07 (HudDataBridge) | remove "dormant" HudDataBridge | **KEEP (scope guard)** | LIVE-SHARED: pinned in `baseline.txt`; unconditional iOS consumer (`JVMLauncher.m:1070` → `ViewController.m:3250` HUD veneer at ~10 Hz). Removal breaks the contract + iOS HUD at boot. |
| SLM-DEP-03 (material-icons-core exclude) | `exclude(module="material-icons-core")` | refuted (high-risk) | 8 material3 classes `getstatic` icons-core fields → **guaranteed compile/link break**, not a runtime maybe. ~81 KB download not worth it. NO ACTION. |
| SLM-DEP-04 (appcompat) | drop appcompat (Compose UI) | refuted (0 MB) | Hard dep: both Activities extend `AppCompatActivity`, manifest `Theme.AppCompat`, 3 appcompat `AlertDialog` sites. Migration nets sub-1 MB vs window/dialog regression risk. |
| SLM-DEP-07 (any unused direct dep) | find an unused direct Gradle dep to drop | refuted (negative) | All direct deps have live consumers; `androidx.browser` used beyond login (engine outbound links). None removable. |
| SLM-DEP-06 (compose-bom currency) | bump/replace the Compose BOM | refuted (no action) | BOM 2024.09.00 is current and consistent for compileSdk 35; bumping risks behavior changes in the un-themed UI. |
| SLM-PKG-11 (ABI trim) / SLM-PKG-12 (.aab) | trim ABIs / ship .aab for size | refuted (0 MB) | Already arm64-only (no leakage); single-ABI/asset-heavy app gets ~0 from .aab; PAD moot (app not Play-distributable). |
| SLM-DEP-02 (".gitignore the APK") | stop committing the stale APK to git | refuted (already done) | No APK is git-tracked (`build/` gitignored). The real defect is build-reproducibility, folded into SLM-JAR-06/SLM-PKG-07. |
| SLM-DEBUG-01 (debug receivers) | remove `SET_HD_CONFIG`/`SET_RECONNECT_OVERLAY` receivers | refuted (already correct) | Both `BuildConfig.DEBUG`-gated; never registered in release. No-action verification. |

**Rejected/refuted count: 11** (1 hard OVERTURN — WelcomePlayProbe; 2 KEEP scope-guards — HudDataBridge, lib/server libjvm; 8 negative/already-correct/unsafe-to-pursue candidates).
