// DeviceHeadlessBoot — W1 P3 §6.2 entrypoint: boot real RuneLite headless on the device JIT JVM
// under CACIO CTC, capturing the engine's mainBufferProvider buffer.
//
// This is the W1<->W2 join. The desktop headless harness (CacioHeadlessTest) activates CTC via the
// byte-buddy CacioExtension (a JUnit extension) — which is OUT for the device (no jdk.attach, no
// self-attach). Here we instead use the device-proven UnsafePokeActivation.activate() (no agent, no
// attach, no JVMTI; §6.0/§6.1 GREEN on device), then call RuneLiteHeadless.main verbatim.
//
// Desktop pre-check (the W2 "develop on desktop first" rule): compile against the shaded client jar +
// cacio, run with the headless capture props; it must boot to the live-OSRS login screen and dump a
// frame-*.png, proving the activation SWAP (UnsafePoke instead of CacioExtension) works with the real
// client before any device round. Then the same class is the device entrypoint (callMain'd by the
// JVMLauncher), with the shaded jar bundled and the capture dir = the app's Documents.
//
// RuneLiteHeadless.main returns after start() (the engine runs on its own non-daemon threads); the
// keep-alive sleep holds the run open long enough for HeadlessCapture to dump frames.

public class DeviceHeadlessBoot {

    public static void main(String[] args) throws Exception {
        System.out.println("==== DeviceHeadlessBoot (W1 P3 §6.2 — real RuneLite under UnsafePoke CTC) ====");

        // 1. Install CTC with no instrumentation (the device-proven §6.0 recipe).
        UnsafePokeActivation.activate();

        System.out.println("[boot] toolkit      = " + java.awt.Toolkit.getDefaultToolkit().getClass().getName());
        System.out.println("[boot] graphicsenv  = " + java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getClass().getName());
        System.out.println("[boot] headless     = " + java.awt.GraphicsEnvironment.isHeadless());

        // 2. Boot the real client, window-less (HeadlessClientUI), GPU off. HeadlessCapture dumps
        //    mainBufferProvider to -Drunelite.headless.capture.dir when set.
        System.out.println("[boot] launching RuneLiteHeadless --safe-mode");
        net.runelite.client.headless.RuneLiteHeadless.main(new String[]{"--safe-mode"});

        long runSeconds = Long.getLong("headless.runSeconds", 60L);
        System.out.println("[boot] RuneLite.main returned; keep-alive " + runSeconds + "s for capture");

        // Per-tick keep-alive: log elapsed seconds + whether the engine is actually rendering
        // (HeadlessCapture.draw counter) + frames written, every 5 s. This makes the run
        // SELF-DIAGNOSING — the next device log shows exactly how long the process survived, whether
        // Hooks.draw was ever driven (draw#>0 ⇒ engine render loop alive), and how many PNGs landed.
        // If the log stops mid-loop with no "[boot] DeviceHeadlessBoot done", the process was
        // terminated externally (jetsam/detach) at the last printed tick.
        final long tickMs = 5000L;
        final long ticks = Math.max(1, (runSeconds * 1000L) / tickMs);
        for (long t = 1; t <= ticks; t++)
        {
            Thread.sleep(tickMs);
            int draws = -1, frames = -1, nullG = -1;
            String state = "?";
            try
            {
                draws = net.runelite.client.headless.HeadlessCapture.getDrawCount();
                frames = net.runelite.client.headless.HeadlessCapture.getCapturedFrames()
                       + net.runelite.client.headless.HeadlessCapture.getLoggedInFrames();
                nullG = net.runelite.client.headless.HeadlessCapture.getNullGraphicsCount();
                java.lang.Object gs = net.runelite.client.headless.HeadlessCapture.getLastState();
                state = gs == null ? "null" : gs.toString();
            }
            catch (Throwable ignore) { /* capture class may be absent in non-§6.2 probe builds */ }
            System.out.println("[boot] keep-alive tick " + (t * tickMs / 1000L) + "s/"
                + runSeconds + "s  draws=" + draws + " nullGraphics=" + nullG
                + " frames=" + frames + " state=" + state);
        }
        System.out.println("[boot] DeviceHeadlessBoot done (reached keep-alive end, NOT externally killed)");
    }
}
