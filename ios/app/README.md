# ios/app — the native iOS app

Placeholder. The Xcode project and native sources land here across the build phases (see
`../REALJVM-DEV-PLAN.md`):

- **Phase 1 (W1):** embed OpenJDK 21 + `dlopen(libjli)` → `JLI_Launch`; confirm JIT on device.
- **Phase 3 (W1):** AWT backend (Caciocavallo + fake-xawt) so the engine's `Canvas`/`BufferProvider` init.
- **Phase 4 (W1/W3):** present the framebuffer (Metal texture upload) + touch/keyboard input.
- **Phase 6 (W3):** the Jagex-Mobile-style UI shell (tabs, on-screen controls, native plugin config).

Reference patterns (GPLv3 — read only, don't copy) in `AngelAuraMC/Amethyst-iOS`: `Natives/JavaLauncher.m`,
`Natives/awt_xawt/xawt_fake.m`, `Natives/external/ballpa1n/HostManager.c`, `Natives/SurfaceViewController.m`.
