# Cross-port shared-contract guard

iOS and Android embed the **same staged jars** and call into them by name (JNI/reflection). The only thing that
couples the two ports is that **host-facing contract**: the public-static API of the `net.runelite.client.headless.*`
bridges, the two boot/activation entry points the native hosts invoke (`DeviceHeadlessBoot.main`,
`UnsafePokeActivation.activate`), and the `-D` gate/property names those classes read. A change there can break the
*other* port even though none of its own files were touched.

This guard makes such changes impossible to miss.

## Pieces
- **`extract-contract.py`** — extracts the contract from source as sorted `METHOD …` / `FLAG …` lines (param
  names stripped, so only real signatures count).
- **`baseline.txt`** — the committed snapshot of the contract.
- **`check-contract.sh`** — diffs current vs baseline. Exit `0` unchanged, `1` additive-only, `2` removed/changed.
  `--update` rewrites the baseline.
- **`../git-hooks/pre-commit`** — on every commit: if staged files feed the shared jars (`runelite-client/`,
  `ios/dev-tools/awtsmoke/`, `tools/stage-*.sh`) it prints a flag; if the contract drifted it **blocks** until you
  stage a refreshed `baseline.txt` (your acknowledgement that you checked the other port).

## Install (once per clone — do this on every machine)
```
tools/git-hooks/install.sh        # sets core.hooksPath = tools/git-hooks
```

## When the hook blocks you
The contract changed. Decide if it's safe for the other port, then:
```
# 1. verify the OTHER port still satisfies the new contract
./android/dev-tools/bridge-census/run-census-desktop.sh     # Android: expect GREEN
#   iOS: rebuild the client jar + run the iOS bridge checks

# 2. acknowledge by refreshing + staging the baseline, then re-commit
./tools/shared-contract/check-contract.sh --update
git add tools/shared-contract/baseline.txt
```
Additive changes (a new bridge method) are safe — the other port just won't call it yet — but you still stage the
baseline so it stays truthful. Removed/renamed/re-signatured entries are the real danger and are called out
explicitly. Emergency bypass: `git commit --no-verify`.

## What it does and doesn't guarantee
It catches contract **drift** at commit time and forces a conscious cross-port check — it does not itself build
both apps. The actual "doesn't break the other port" proof is the Android bridge census (and the iOS bridge
checks) it tells you to run; this guard makes sure you never forget to.
