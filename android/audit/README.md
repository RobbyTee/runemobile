# Android deep audit — index

> **INTERNAL analysis. Do not publish** — this whole `android/audit/` directory is on the public-repo
> exclude-list (Phase 6, `OSS-REPO-DOCS-07`) and never ships in any public artifact.

**What this is:** a senior-level, multi-phase, **adversarially-verified** (every finding graded
CONFIRM / ADJUST / OVERTURN) deep audit of the **Android-only** port — the `:app` native shell plus the
shared headless engine seam it leans on. Scope is the Android tree; iOS-impacting findings are flagged but
not the subject. **Date:** 2026-06.

**Headline verdict:** a **mature, feature-rich ALPHA** — the hard engineering is done, productization and
release-engineering have not started. **Not publicly shippable today** (un-bootable clean `assembleRelease`,
a bundled live account secret, a fatal proprietary-sprite IP blocker). The APK is **~58–70 MB slimmable**.
The shell is a competent desktop-RuneLite-on-a-phone with a thin native veneer — there is a real
**OSRS-Mobile-class UX gap** (onboarding, the Compose config/panel layer, long transitions, safe-area/a11y).

## Deliverables

| File | Phase | What's inside | Headline finding / number |
|---|---|---|---|
| `00-map.md` | 0 — Cartography | Authoritative architecture map: Kotlin shell, native layer, shared bridges, build graph, login, renderers; seeds Phase 1 | The full subsystem map + consolidated Phase-1 removal candidates |
| `01-slimming.md` | 1 — Slimming / dead-code / APK-size | Ranked APK-size + dead-code + dep findings vs a *correctly-built* release APK | **~58–70 MB reducible** (shaded.jar −29.4, `noCompress` −20, JRE jlink/localedata −10–13) |
| `02-performance.md` | 2 — Performance & efficiency | Cold-start, frame-pacing, Compose recompose, memory/thermal, battery/network — 34 findings | **~30 s first-launch unpack** is the headline cost; thermal + background drain are the steady risks |
| `03-ux-qa.md` | 3 — UX / UI quality | Heuristic + OSRS-Mobile/desktop-parity audit of the native shell | **44 open UX defects** (P1=5, P2=12, P3=27) + 4 refuted |
| `04-enhancements.md` | 4 — Enhancements | Anchored backlog: surface un-surfaced features + mobile-first re-presentations | **24 anchored items** (`ENH-A##`/`ENH-B##`), each tied to a plugin + an OSRS-Mobile pattern |
| `05-production.md` | 5 — Production readiness | Go/no-go: security, release-eng, distribution, privacy/legal, crash, QA gate — 42 findings | **7 release-blockers** (3 P0, incl. `PROD-SECURITY-01` bundled live token) |
| `06-open-source.md` | 6 — Open-source prep | License/attribution, secrets-history, AI-scrub, repo-docs, build-from-clean — 31 findings | **FATAL** committed-Jagex-sprite blocker (`OSS-LICENSE-ATTRIBUTION-01`); source otherwise BSD-2-clean |
| `07-repo-and-release-track.md` | 7 — Repo structure & release track | Public-Android / private-iOS split (Option B) + alpha-vs-beta with exit criteria | **ALPHA verdict**; recommended repo split + Alpha→Beta→GA gates |

## How to read this

- **Start with `ROADMAP.md`** — its executive summary is the consolidated entry point that threads all
  seven phases together. (If `ROADMAP.md` is mid-write: it is the cross-cutting synthesis + exec summary,
  ordering every phase's findings into a single prioritized track.)
- **Finding ids are the cross-reference currency.** Every finding carries a stable id
  (`SLM-*`, `PERF-*`, `UX-*`, `ENH-*`, `PROD-*`, `OSS-*`); later phases and the ROADMAP cite those ids
  verbatim. Aliased findings (one lever, several reader-areas) are noted and counted once.
- Severity throughout is the **verified-corrected** value, not the finder's first pass.

**Device / needs-you batch:** the consolidated set of items that require on-device measurement or an
explicit owner go-ahead (thermal soak, background drain, cold-start timing, the gated history-scrub/visibility
flip) is collected in **`ROADMAP.md` §5** — work that batch from there, not from the per-phase files.
