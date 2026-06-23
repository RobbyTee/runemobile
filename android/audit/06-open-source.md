# Phase 6 — Open-source preparation

> **INTERNAL-ONLY DOCUMENT — do NOT publish.** This is the scrub-planning deliverable: by necessity it *names* the exact target strings (in `git grep`/exclude-list form) that must be removed before a public release, so the file itself contains those strings. The entire `android/audit/` directory is internal analysis and is on the public-repo exclude-list (see §5 exclude-list + `OSS-REPO-DOCS-07`); none of it ships. The doc plans the removal of AI-tooling traces; it does not attribute the work.
>
> Synthesis of 31 adversarially-verified findings across license-attribution, secrets-history, ai-scrub, repo-docs, and build-from-clean. Stable ids are load-bearing (the ROADMAP cites them). Execution of any history-rewrite / deletion / visibility change is **GATED** behind a single explicit go-ahead (see §7).

## 1. TL;DR — can this be open-sourced?

**The SOURCE repo: YES — after a bounded, well-scoped cleanup.** The tree is a BSD-2-Clause fork of RuneLite plus the author's own `android/` + `runelite-client/.../headless/` code, which may ship under the same BSD-2. There is **no fatal GPL incompatibility**: the only copyleft components (OpenJDK 21 + Caciocavallo) are GPLv2-**with-Classpath-Exception** and are *not committed* — they are fetched/staged at build time. No OSRS game cache or game data is in-tree (RuneLite streams it live). Git history reads as the author's own work: across all 17,025 commits there is **zero AI attribution** in any author/committer/message field, and **no live secret was ever committed** (no `tokens.json`, no keystore/`.p12`/`.pem`/private key, no `.apk`).

**The single biggest gate (the one FATAL source-repo blocker): proprietary Jagex OSRS UI sprites are committed to the tree.** `OSS-LICENSE-ATTRIBUTION-01` — the tab-stone / skill-icon / chat-button PNGs under `android/app/src/main/assets/tabassets/` (20 files: 14 icons of varied sizes 20×20–27×32, `stone-active/-inactive`, `chatbtn`/`-new`/`-selected`) **and the identical iOS tree** `ios/app/HelloShell/tabassets/` (23 tracked assets, incl. an extra `chatreport.png`). These are pixel-for-pixel derivatives of Jagex client interface art with no license grant; BSD-2 covers code, not game art (upstream RuneLite never commits these — it streams them from the live cache). They are committed across multiple commits on **both** ports, so removing them from HEAD does **not** remove them from history. **Do not publish with these committed.** This is the only hard blocker; everything else is attribution, doc-drafting, or a small AI-phrase scrub.

**The APK BINARY distribution is a separate, heavier (but still lawful) gate** — out of scope here, owned by Phase 5 (production). The Classpath Exception makes the combined binary distributable, but it additionally obliges a GPLv2 source-offer (`-05`/`-06`), the actual sprite replacement in shipped assets, and the Phase-5 P0s (strip `tokens.json`, real release key). **Recommendation: publish the SOURCE repo first; treat the binary as a separate, later, risk-flagged GitHub Release.** Do not conflate them (`OSS-LICENSE-ATTRIBUTION-07`).

**Bottom line:** No proprietary-game-*data* and no GPL clash bar release. The one stop-ship is the committed Jagex *sprite art* (on both ports). **Redraw it with original chrome art — removal alone is a functional blocker** (`ChatPillView.kt:97-99` loads the chat buttons; `ChromeOverlayView.kt:71-74` loads the stones + `icon-$i` tab icons, so deleting them breaks the chat pill + tab-grid/stone chrome), then purge the proprietary originals across HEAD + history, ship a `LICENSE` (unchanged) + `THIRD-PARTY-NOTICES`, scrub a handful of review-tool phrases from tracked docs/3 commit messages, and the source can go public under BSD-2.

## 2. MANDATORY-BEFORE-PUBLIC checklist (P0/P1)

| id | title | area | what must be true | gated? |
|----|-------|------|-------------------|--------|
| OSS-LICENSE-ATTRIBUTION-01 | Jagex OSRS sprites committed (both ports) | license | `tabassets/*.png` **redrawn/replaced** (removal alone breaks the chat pill + tab/stone chrome), then proprietary originals purged from HEAD **and** history, **android/ AND ios/** | **YES** |
| OSS-LICENSE-ATTRIBUTION-02 | Fan fonts CC-BY-NC-SA + trademark name | license | `runescape*.ttf` (×1 Android, ×3 iOS) removed-or-attributed+renamed; NC term escalates toward removal for any distributed binary | no |
| OSS-LICENSE-ATTRIBUTION-03 | Correct LICENSE = BSD-2 | license | root `LICENSE` kept verbatim; author stanza appended; per-file SPDX optional | no |
| OSS-LICENSE-ATTRIBUTION-04 | THIRD-PARTY-NOTICES authored | license | aggregate notice exists (sections A–I); Apache NOTICE files propagated | no |
| OSS-LICENSE-ATTRIBUTION-05 | Caciocavallo GPLv2+CE attributed | license | listed under GPLv2+CE; POM license confirmed; covered by binary source-offer | no |
| OSS-LICENSE-ATTRIBUTION-06 | OpenJDK 21 GPLv2+CE | license | README notes JRE fetched at build (not in-tree); binary carries `jre/legal/` + 3-yr source-offer | no |
| OSS-LICENSE-ATTRIBUTION-07 | Separate source vs binary gates | license | source-repo plan gates `-01`+`-02` across **both** trees; binary deferred to Phase 5 | no |
| OSS-SECRETS-HISTORY-02 | Token-mint script tracked on master | secrets | `ios/dev-tools/fetch-shield-tokens.sh` `git rm` from HEAD (history purge gated) | **YES** (history leg) |
| OSS-AISCRUB-01 | Local review tooling dir excluded | ai-scrub | `dev-tools/second-opinion/` stays untracked (verify at publish) | no |
| OSS-AISCRUB-02 | 'second-opinion'/'independent review' in tracked docs | ai-scrub | repo-wide grep empty (≥13 docs, not 2) | no |
| OSS-AISCRUB-03 | 3 commit messages name the tool | ai-scrub | message text scrubbed (rewrite or squash) | **YES** |
| OSS-AISCRUB-04 | `.gitignore` comment names tooling+secrets | ai-scrub | lines 43–44 dropped/neutralised (history via squash) | no (HEAD) |
| OSS-AISCRUB-06 | Squash-vs-surgical path decision | ai-scrub | one path chosen + executed; full publish-gate grep empty | **YES** |
| OSS-AISCRUB-07 | Local AI/instruction files untracked | ai-scrub | project-instructions file, `.claude/`, handoffs verified untracked; rules re-encoded in public `.gitignore` | no |
| OSS-REPO-DOCS-01 | Public README | docs | honest unofficial/sideload/ban-risk README + build-from-clean recipe | no |
| OSS-REPO-DOCS-04 | SECURITY.md | docs | private vuln channel + token-surface scope exists | no |
| OSS-REPO-DOCS-06 | THIRD-PARTY-NOTICES backs README pointer | docs | aggregate notice (font attributed to WolfieMario, 117HD named BSD-2) | no |
| OSS-REPO-DOCS-07 | Internal `*.md` notes not promoted as docs | docs | tracked internal notes removed/scrubbed; public surface = drafted set only | **YES** |
| OSS-BUILD-FROM-CLEAN-01 | Fresh clone is buildable | build | README documents the 4 staging scripts in order (JRE→runtime→gpu→rlhd) | no |

(P2/P3 affirmative-clearance and hygiene items — `-08`, `SECRETS-HISTORY-01/03/04`, `AI-SCRUB-01`, `AISCRUB-05/08`, `REPO-DOCS-02/03/05` — are tracked in their sections but do not block.)

## 3. LICENSE & ATTRIBUTION

### Chosen license — BSD-2-Clause (`OSS-LICENSE-ATTRIBUTION-03`, `-08`)
The repo is a permissive derivative of RuneLite (BSD-2) incorporating the 117HD/rs117 renderer (also BSD-2, © 2021 117). BSD-2 permits a permissively-licensed derivative; it only requires the upstream copyright + 2-clause text be **retained** in source and reproduced in binaries. The author's `android/` + `headless/` code ships under the same BSD-2. **No copyleft pulls on the source** — the GPL contact points are binary-only and Classpath-Exempt.

**Action:** keep the root `LICENSE` UNCHANGED (BSD-2, `Copyright (c) 2016-2017, Adam <Adam@sigterm.info>` — §1 retention is mandatory). Append the author's stanza under the same grant:

```
Copyright (c) 2016-2017, Adam <Adam@sigterm.info>   (RuneLite — upstream, unchanged)
Copyright (c) 2026, Jordan Simpson <sayhijordy@gmail.com>

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
  1. Redistributions of source code must retain the above copyright notice,
     this list of conditions and the following disclaimer.
  2. Redistributions in binary form must reproduce the above copyright notice,
     this list of conditions and the following disclaimer in the documentation
     and/or other materials provided with the distribution.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" ...
```
Per-file SPDX headers (`SPDX-License-Identifier: BSD-2-Clause`) in new `android/` files are recommended, not required.

### THIRD-PARTY-NOTICES (`OSS-LICENSE-ATTRIBUTION-04`, `OSS-REPO-DOCS-06`) — faithful draft
No aggregate notice file is tracked today (only the root `LICENSE`). Note the 117HD BSD-2 retention is currently **UNMET in-tree** — `rlhd-src/` is gitignored, so its notice exists only on disk while its code is recompiled into the shipped rlhd jar; the notice file is what discharges this. Create `THIRD-PARTY-NOTICES.md`:

- **A. RuneLite** — BSD-2-Clause, © 2016-2017 Adam + contributors (reproduce root LICENSE text).
- **B. 117HD / rs117** — **BSD-2-Clause**, © 2021 117 (recompiled into `client-*-rlhd.jar`).
- **C. net.runelite.rlawt AWTContext** — BSD-2 (RuneLite).
- **D. Apache-2.0 group** (reproduce the 2.0 text once + propagate Guava/Guice/Gson NOTICE files): Guava 23.2-jre, Guice 4.1.0 no_aop, Gson 2.8.5, FlatLaf (net.runelite 3.2.5-rl4), commons-text 1.2, commons-compress 1.10, JNA 5.9.0 (+platform; Apache-2.0/LGPL-2.1 dual — elect Apache), jsr305 3.0.2, JetBrains annotations 23.0.0, tomlj 1.1.0, AndroidX core 1.13.1 / appcompat 1.7.0 / browser 1.8.0 / compose-bom 2024.09.00 / material3 / activity-compose 1.9.2, kotlinx-coroutines-android 1.8.1.
- **E. MIT group:** SLF4J 1.7.25, jopt-simple 5.0.1.
- **F. BSD-3-Clause group:** LWJGL 3.3.2 (core/opengl/opencl — **note**: natives stripped on Android, `librlawt.so` replaces them, but the Java facade ships → keep attribution), protobuf-javalite 3.21.12, ANTLR4 runtime 4.13.1.
- **G. Caciocavallo Silano 1.18** — GPLv2 + Classpath Exception; source pointer `github.com/caciocavallosilano/cacio`.
- **H. OpenJDK 21.0.1** — GPLv2 + Classpath Exception; bundled sub-notices ICU / CLDR / Unicode / public_suffix(Mozilla) / AES / ASM / c-libutl + the Lucida fonts; binary sourced from the SHA-pinned AngelAuraMC bionic build (`tools/stage-android-jre.sh`).
- **I. Fonts (`-02`):** `runescape.ttf` = "RuneScape Chat 07" by **WolfieMario, 2013** (FontStruct #855573), license embedded in the binary as **CC BY-NC-SA**; iOS adds `runescape_bold.ttf` ("Bucket 2010", different author/fontstruction) + `runescape_small.ttf` (WolfieMario, CC BY-NC-SA). Attribute to the actual authors + FontStruct — **not** under the Jagex statement.
- **J. Jagex trademark/asset statement:** "RuneScape and Old School RuneScape, and the tab/stone/icon/chat-button interface art, are property of Jagex Ltd. This is an unofficial fan client; these assets are not covered by this project's license." (covers the sprites **only**, which must be replaced — `-01`).

Pin versions exactly as in the root `/libs.versions.toml`. Document the GPL conclusion in NOTICE/README: *"The embedded OpenJDK runtime and Caciocavallo are GPLv2 with the Classpath Exception, which permits distribution combined with this BSD-2 software; the GPL does not extend to the application code."*

### Source vs binary (`OSS-LICENSE-ATTRIBUTION-05/06/07`)
**SOURCE repo:** no GPL obligation attaches — JRE/cacio are gitignored and fetched at build. README states they are fetched from the SHA-pinned upstream and are not this repo's licensed code. **BINARY (later, Phase 5):** keep `assets/jvm/jre/legal/` intact in the APK; add a Release-page **written offer of source valid 3 years** for OpenJDK 21.0.1 (`openjdk.org` tag `jdk-21.0.1` + the AngelAuraMC build repo) and Caciocavallo; confirm the cacio jar POM license from `META-INF`.

## 4. SECRETS & GIT-HISTORY

**Proven clean (`OSS-SECRETS-HISTORY-01/03`, `OSS-AI-SCRUB-01`):**
- `git log --all --oneline -- CLAUDE.md dev-tools/second-opinion tokens.json '**/tokens.json'` → **EMPTY** (never committed).
- `git ls-files | grep -iE 'tokens\.json$|\.(jks|keystore|p12|pem|pfx|bks)$'` → **EMPTY**.
- `git log --all --diff-filter=A --name-only | grep '\.apk$'` → **EMPTY** (no APK ever).
- Pickaxe on the live values (session_id 22ch / otl_token 53ch) → **NEVER committed**; no `BEGIN PRIVATE KEY` block; only the `.example` BrowserStack template exists. `rev-list --all --count` = 17,025 scanned.
- The three live `tokens.json` copies (`ios/dev-tools/`, `android/app/src/main/assets/`, `android/app/build/intermediates/.../mergeDebugAssets/`) are working-tree-only, all gitignored (`ios/.gitignore:2`, `android/.gitignore:20`, `android/.gitignore:5`).
- The deleted-in-history `travis/runelite.key.enc` is **upstream RuneLite's own** encrypted Travis key (not on master, already public upstream) — leave it.

**Found / needs action:**
- **`OSS-SECRETS-HISTORY-02` (P1):** `ios/dev-tools/fetch-shield-tokens.sh` is **tracked on master** (added `1b3556cef6`, present in every commit of its path). It holds only **published** OAuth client ids (`com_jagex_auth_desktop_launcher`, consent id `1fddee4e-...` — also a shipping constant in `JagexLogin.kt:34`/`JagexLoginViewController.m:9`, **not secret**), but it ships the spoofed-launcher OAuth/OTL mint methodology against a non-approved client and the guardrails class it never-public. Also reconsider `android/dev-tools/a3-login/set-legacy-login.sh` (prompts for legacy user/pass; writes outside the repo — no hardcoded secret).
- **PII (caught in `OSS-AISCRUB-06` verification):** the throwaway account's display name `MarkingEclip` + characterId `368557716` are committed and tracked in **15 `ios/app/evidence/*.log`** files. No session secret, so not catastrophic, but account-identifying — `git rm` from HEAD; expunge from history only if a perfectly-clean history is demanded.

### Remediation — present BOTH options (all GATED for the history leg)
- **(a) Surgical `git filter-repo`** — purge specific paths from all history, then force-push:
  ```
  git filter-repo --invert-paths \
    --path android/app/src/main/assets/tabassets/ \
    --path ios/app/HelloShell/tabassets/ \
    --path ios/dev-tools/fetch-shield-tokens.sh \
    --path-glob 'ios/app/evidence/*.log'
  # then rewrite the 3 commit messages: git filter-repo --message-callback (replace 'second-opinion'→'review pass')
  ```
  Scope: rewrites those blobs/messages + all descendant hashes. (`git-filter-repo` is **not installed** on this machine — surgical needs an install first.)
- **(b) Squash to a fresh clean initial public commit (RECOMMENDED, `OSS-AISCRUB-06`):**
  ```
  git reset --soft d9023f9361     # the verified upstream merge-base (Adam, 'worldhopper: fix region filter')
  # remove the proprietary sprites/font/script/evidence/internal-notes from the index here, then:
  git commit --author='Jordan Simpson <sayhijordy@gmail.com>' -m '<neutral message>'
  ```
  Collapses the 844-commit private narrative (which authored 100% as Jordan Simpson) into **one** authored commit on top of preserved upstream history. This makes `OSS-AISCRUB-03` (3 messages) **and** `-04` (old `.gitignore` blob) moot in one move, eliminates all internal session/handoff/process phrasing from message history, and leaves the upstream 475 `Co-authored-by` trailers (legitimate BSD-2 provenance — `OSS-AISCRUB-05`) intact below the boundary. **Note:** the squash collapses files out of *message* history but not out of the squashed *tree* unless they are removed from the index pre-commit — so the sprite/font/PII removal must happen in the same operation.

**Recommendation:** **squash (b)** — lower-risk, single-step, simpler to verify, no `filter-repo` install, and the contamination is tiny (no blob-level secret to chase). Confirm the result still builds (coordinate with build-from-clean §6). Either path is **GATED**.

**`OSS-SECRETS-HISTORY-04` (pre-emptive, non-gated):** add to `.gitignore` before any release key is generated: `*.jks *.keystore *.p12 *.pfx *.bks *.pem *.p8 keystore.properties **/release-signing*.properties` (`local.properties` already ignored).

## 5. AI-TOOLING & PROVENANCE SCRUB

**History metadata is clean (`OSS-AI-SCRUB-01`, `OSS-AISCRUB-08`):** `git log --all --pretty='%an|%ae|%cn|%ce' | grep -iE 'claude|anthropic|codex|openai'` → EMPTY; no `Generated with` / `Co-Authored-By: Claude`. 844 commits Jordan Simpson, rest upstream. ~475 `Co-authored-by` trailers are all **human** upstream contributors → **PRESERVE** (`OSS-AISCRUB-05`; never match `co-authored` or `\bglm\b` — `glm` collides with `glMapBuffer`). No literal AI-vendor name in any Android/native/headless source comment (the only `GENERATED` banners are RouteCGen + the Gradle wrapper — keep).

**EXCLUDE (never tracked — verify, no scrub):**
- `dev-tools/second-opinion/` — local review tooling; `git ls-files | grep -i second-opinion` EMPTY, 0 commits (`OSS-AISCRUB-01`, P0-verify).
- the local project-instructions file, `.claude/`, `*-HANDOFF.md`/`*-PROMPT.md`/`ANDROID-QA-*.md` siblings, `*.bak` — untracked, in `.git/info/exclude` (`OSS-AISCRUB-07`). **`.git/info/exclude` is LOCAL and not pushed** → re-encode these as `.gitignore` rules in the public repo, or simply don't copy the files.

**SCRUB (tracked content — HEAD edits, no rewrite needed unless perfectly-clean history wanted):**
- `.gitignore:43-44` — drop both lines (the dir is untracked anyway); they name the tooling + "provider secrets" (`OSS-AISCRUB-04`).
- `'second-opinion' / 'independent review'` phrasing — **repo-wide, ≥13 tracked docs, not 2** (`OSS-AISCRUB-02`/`-03` verification OVERTURNED the "only 2" claim): `android/ANDROID-GPU-117HD-AUDIT.md:154,169,171`, `android/ANDROID-GPU-117HD-BLUEPRINT.md:5,159`, plus `AUDIT-BRIEF-repo-structure-dual-port.md:3`, `android/AUDIT-BRIEF-mobileui-native-bridge.md:3`, `android/BUGFIX-INVESTIGATION.md:126`, `android/PORT-PHASING-REVIEW.md:3`, and 7× `ios/AUDIT-BRIEF-*.md`. The literal "Codex" tells (`ios/AUDIT-BRIEF-w1-phase3-awt.md:5,49`) and `ios/dev-tools/gpu-routec/WATER-SHIMMER-DEEP-DIVE-PROMPT.md:203` (the rule text itself).
- **Cleanest path:** the wholesale removal of the internal `*.md` notes (`OSS-AISCRUB-03` doc set + `OSS-REPO-DOCS-07`) deletes most hits in one move. For any doc kept public, reword `second-opinion / independent review / Codex audit` → `a follow-up review pass`.

**HISTORY work (GATED, `OSS-AISCRUB-03`/`-06`):** 3 commit-message bodies on master name `second-opinion` — `79aeb0ed25` ("gitignore dev-tools/second-opinion/ wholesale ... provider secrets"), `38e8f2f223` ("Reconcile second-opinion: the >1000ms garbage guard..."), `e9dfcd8751` ("a reconciled second-opinion adjustments pass"). Only a rewrite/squash reaches message text. The **squash (§4 option b) closes these + the old `.gitignore` blob in one step** — preferred over per-commit reword.

**Exclude-list (do not copy / keep untracked):** `dev-tools/second-opinion/`, the local project-instructions file, `.claude/`, all `*-HANDOFF.md`, `*-PROMPT.md`, `*-DEEPDIVE.md`, `ANDROID-QA-*.md`, `*.bak`, the tracked internal `*.md` notes (`OSS-REPO-DOCS-07` list), `ios/app/evidence/*.log`, `ios/dev-tools/fetch-shield-tokens.sh`.

**Publish gate (must all return empty):**
```
git log --all --format=%B | grep -niE 'second.opinion|claude|codex|anthropic|generated with'
git grep -niE 'second.opinion|independent review|claude|codex|anthropic' -- ':(exclude)android/audit/*'
git ls-files | grep -iE 'tokens\.json$|legacy_login\.json$|\.(jks|keystore|p12|pem|pfx|bks)$'
git grep -lE 'MarkingEclip|368557716'
```

## 6. REPO DOCS

Public doc surface = exactly: `README.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`, `THIRD-PARTY-NOTICES.md`, `.github/` templates. Internal `android/*.md` notes are **not** promoted (`OSS-REPO-DOCS-07`). Full drafts live in the findings; tight ready-to-commit outlines:

- **README.md (`OSS-REPO-DOCS-01`, P1):** title "RuneLite for Android (unofficial)"; top disclaimer — *unofficial, not affiliated with / endorsed by Jagex or RuneLite; sideload only; use at your own risk incl. account ban (not on Jagex's approved-client list)*. Sections: **What it is** (native app runs real unmodified RuneLite on an in-process embedded arm64 OpenJDK 21 via `JNI_CreateJavaVM`; full plugin ecosystem; CPU/GPU/117HD renderers; no streaming) · **Status/devices** (Pixel 7a Mali-G710 + arm64 emulator; arm64-v8a only; minSdk 28 / target 35; accelerated renderers auto-promoted only after a live caps probe, fall back to CPU) · **Install (sideload)** (signed APK from Releases; not on Play/F-Droid) · **Build from source** (the build-from-clean recipe below) · **Architecture** (ART process hosts a 2nd HotSpot JVM; C++ `dlopen`s `libjvm.so`; Kotlin/Compose `SurfaceView` host; the `headless/*` Java bridge is shared verbatim with iOS) · **License & attribution** (→ LICENSE + THIRD-PARTY-NOTICES; assets/trademarks remain Jagex's). **Replaces the false "Jagex-greenlit" premise** with the honest unofficial framing (per Phase 5). One-line cross-ref: first-launch login depends on the Phase-5 P0 (no `tokens.json` shipped).
- **Build-from-clean (`OSS-BUILD-FROM-CLEAN-01`, P2):** a fresh clone lacks the JRE + 3 client jars (gitignored — each regenerated by a **tracked, SHA-pinned** staging script). The two app `.so`s (`libhelloshell.so` / `librlawt.so`) are also absent from a checkout, but are **auto-built by Gradle/CMake from tracked source** (no manual step — see the correction below), so they are NOT part of the staging-script list. Documented staging order (from repo root):
  ```
  tools/stage-android-jre.sh                          # SHA-pinned arm64 OpenJDK 21 → jniLibs + assets/jvm/jre
  android/dev-tools/gpu/stage-android-gpu-jar.sh       # → client-*-gpu.jar   (run BEFORE the runtime stage so
  android/dev-tools/gpu/stage-rlhd-zone-jar.sh         # → client-*-rlhd.jar     their SHAs fold into runtime.tag)
  tools/stage-mobile-runtime.sh --android-only         # gradle :client:shadowJar → client-*-shaded.jar + probes.jar
  cd android && ./gradlew :app:assembleRelease         # never bare `assemble` (also builds the :recon probe app)
  ```
  **Correction (verification ADJUST):** `librlawt.so` is **not** a stage-script output — it is a normal CMake target (`android/app/src/main/cpp/CMakeLists.txt:16`) built automatically by the **`:app` `externalNativeBuild` (CMake)** wired in `build.gradle.kts` from tracked `librlawt.cpp`, NOT by `:client:shadowJar` (which builds the Java client jar). There is no `build-rlawt.sh` and nothing pre-staged in `jniLibs` for it; do not list it as a manual step. The current `android/README.md` documents only steps 1–2 and omits the gpu/rlhd jars → a clone following it alone builds an **un-bootable** APK (`MainActivity.kt:2584 useGpuJar=true`). Call out the single third-party JRE host + pinned SHA `8d41ec40...bbfe` and recommend mirroring it.
- **CONTRIBUTING.md (`OSS-REPO-DOCS-02`, P2):** replaces the upstream 2-line stub. Build setup → README. **Shared-contract rule** (load-bearing): any change under `runelite-client/.../headless/` is resolved by name at runtime and shared with iOS — run `tools/shared-contract/check-contract.sh` (0=no change, 1=additive→update baseline, 2=breaking→not allowed); install `tools/git-hooks/install.sh`. Conventions (Kotlin/Compose shell, C++17 native, keep Android work under `android/`, R8/minify intentionally OFF, never attach the ART UI thread to the JVM). PR process + commit style framed as **plain project style** (author's own name, squash WIP, plain prose, no machine-generated trailers — never names any tool). Contributions licensed BSD-2.
- **SECURITY.md (`OSS-REPO-DOCS-04`, P2):** private reporting (GitHub Security Advisories + maintainer email), latest-release-only, single-maintainer best-effort. Scope = Android shell + native layer + on-device login/token handling (Jagex OAuth + RuneLite-account sync) + embedded-JVM/plugin trust boundary. **Out of scope (accepted, not vulns):** irreducible ban risk (use a throwaway account), in-process hub plugins run with full JVM privileges, tokens in app-private storage. *"Never paste a real session_id/id_token/token/password; redact display names; no live tokens.json ships."*
- **CODE_OF_CONDUCT.md (`OSS-REPO-DOCS-03`, P3):** vendor Contributor Covenant v2.1 verbatim; enforcement contact `sayhijordy@gmail.com`.
- **.github templates (`OSS-REPO-DOCS-05`, P3):** **replace** upstream's `ISSUE_TEMPLATE/bug-issue.yml` + `config.yml` (point at this repo's Discussions, not `runelite/runelite`). Add `bug_report.yml` (device/Android/renderer/Release-tag/redacted-logcat + throwaway-account checkbox), `feature_request.yml` (touches shared bridge? y/n), and `PULL_REQUEST_TEMPLATE.md` (checkboxes: `:app:assembleRelease` builds · tested on device/emulator · shared contract unchanged OR `check-contract.sh` passes + baseline updated). `FUNDING.yml` is `patreon: runelite` (upstream) → remove/blank.

## 7. GATED ACTIONS (require explicit go-ahead — ready, NOT run)

Nothing below has been executed. Each is a history-rewrite, deletion of tracked files, or visibility change. **Recommended single move: the squash (G3) executed with the sprite/font/script/evidence/internal-note removals staged in the same commit** — this subsumes G2, G4, G5, G6, and G7's history leg in one step.

1. **G1 — Expunge proprietary sprites from history (both ports)** (`OSS-LICENSE-ATTRIBUTION-01`). After redrawing original chrome art:
   `git filter-repo --invert-paths --path android/app/src/main/assets/tabassets/ --path ios/app/HelloShell/tabassets/` (or stage-remove in the G3 squash). Then force-push.
2. **G2 — Purge the token-mint script from history** (`OSS-SECRETS-HISTORY-02`). Non-gated minimum: `git rm ios/dev-tools/fetch-shield-tokens.sh`. Gated full purge: `git filter-repo --path ios/dev-tools/fetch-shield-tokens.sh --invert-paths` + force-push.
3. **G3 — Squash our 844 commits to one clean public commit (RECOMMENDED)** (`OSS-AISCRUB-06`): `git reset --soft d9023f9361` → remove sprites/fonts/script/evidence/internal-notes from the index → `git commit --author='Jordan Simpson <sayhijordy@gmail.com>'` with a neutral message. Preserves upstream history + 475 trailers below the boundary; makes G4/G5 moot.
4. **G4 — Surgical message rewrite (alternative to G3)** (`OSS-AISCRUB-03`): install `git-filter-repo`, then `--message-callback` replacing only `second-opinion`→`review pass` across `79aeb0ed25`, `38e8f2f223`, `e9dfcd8751` (never touch `co-authored`/`glm`).
5. **G5 — Purge PII evidence logs from history** (`OSS-AISCRUB-06` verification): `git rm 'ios/app/evidence/*.log'` (HEAD); history purge via G3 squash or `git filter-repo --path-glob 'ios/app/evidence/*.log' --invert-paths`.
6. **G6 — Remove tracked internal `*.md` notes** (`OSS-REPO-DOCS-07`, `OSS-AISCRUB-03`): `git rm` the internal audit/blueprint/parity/handoff/`android/audit/*.md` set (and the iOS equivalents if the whole tree is the public root). Tracked-file deletion → gated.
7. **G7 — Replace/remove upstream-pointing `.github` files** (`OSS-REPO-DOCS-05`): overwrite `ISSUE_TEMPLATE/bug-issue.yml`+`config.yml`, remove/blank `FUNDING.yml`. Tracked-file edits → ride the same go-ahead.
8. **G8 — Force-push / new public remote / visibility flip:** after the chosen rewrite, `git push --force` (or push the squashed branch to a **new** public repo). Visibility change is the final gated action; run the §5 publish-gate greps first (all must be empty).
