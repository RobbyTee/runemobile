// UnsafePokeActivation — the DEVICE-RELEVANT activation recipe for W1 Phase 3 §6.0.
//
// Activates CACIO's CTC toolkit + graphicsenv with NO instrumentation: no byte-buddy,
// no jdk.attach / attach-self, no JVMTI RedefineClasses. The embedded iOS jre21 lacks
// jdk.attach + libattach.dylib (offline-confirmed), so the desktop CacioExtension agent
// path is OUT for the device — this path needs none of those capabilities.
//
// WHY THE NAIVE FIELD-POKE FAILED (FieldPokeActivation.java, mode d): there is no
// `GraphicsEnvironment.localEnv` field. getLocalGraphicsEnvironment() returns
// `GraphicsEnvironment$LocalGE.INSTANCE` — a static-final singleton computed in the
// holder's <clinit> via the BUILD-TIME-hardcoded sun.awt.PlatformGraphicsInfo.createGE()
// (which is exactly why -Djava.awt.graphicsenv is ignored on JDK 21, and why the desktop
// agent has to byte-buddy-REDEFINE that method). With no instrumentation we instead
// OVERWRITE that static-final field via Unsafe after the holder initializes; since the
// accessor re-reads INSTANCE every call, subsequent calls return our CTC GE.
//
// Sequence (order matters — seed cached singletons BEFORE AWT reads them):
//   1. sun.java2d.SurfaceManagerFactory.instance = new CTCSurfaceManagerFactory()
//        (so SunGraphicsEnvironment's init finds the CTC factory already set)
//   2. java.awt.GraphicsEnvironment.headless / .defaultHeadless = Boolean.FALSE
//   3. java.awt.Toolkit.toolkit = new CTCToolkit()          (plain field; non-final)
//   4. force LocalGE <clinit>, then Unsafe-overwrite LocalGE.INSTANCE = CTC GE
//
// Step 4 forces the platform createGE() to run once (throwaway) before we clobber it.
// On desktop that builds a CGraphicsEnvironment; on the iOS jre21 it runs that JRE's
// PlatformGraphicsInfo.createGE() — the one DEVICE unknown this probe cannot answer
// offline (flagged for the one short device confirm). Everything else is standard
// java.desktop 21 and identical on device.
//
// Requires the JPMS set we control in the device JNI_CreateJavaVM args:
//   --add-opens java.desktop/java.awt, sun.java2d ; java.base/java.lang.reflect
//   --add-exports java.desktop/sun.awt, sun.java2d, ... (CacioToolkit extends sun.awt.SunToolkit)
//
// Unsafe is reached purely via reflection (no `sun.misc.*` import) so this still compiles
// `javac --release 8` and bundles into the device probe jar, same as the other probes.

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class UnsafePokeActivation {

    static void setStatic(String cls, String field, Object v) throws Exception {
        setStatic(Class.forName(cls), field, v);
    }
    static void setStatic(Class<?> c, String field, Object v) throws Exception {
        Field f = c.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, v);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("==== UnsafePokeActivation (no byte-buddy / no attach / no JVMTI) ====");

        Class<?> ctcToolkit = Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCToolkit");
        Class<?> ctcGE      = Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment");
        Class<?> ctcSMF     = Class.forName("com.github.caciocavallosilano.cacio.ctc.CTCSurfaceManagerFactory");

        // --- reflective Unsafe handle (release-8-safe; no sun.misc.* import) ---
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object U = theUnsafe.get(null);
        Method mBase   = unsafeCls.getMethod("staticFieldBase", Field.class);
        Method mOffset = unsafeCls.getMethod("staticFieldOffset", Field.class);
        Method mPutObj = unsafeCls.getMethod("putObject", Object.class, long.class, Object.class);

        // 1. Non-headless flags (so createGE doesn't wrap Headless* and isHeadless()==false).
        try {
            setStatic(java.awt.GraphicsEnvironment.class, "headless", Boolean.FALSE);
            setStatic(java.awt.GraphicsEnvironment.class, "defaultHeadless", Boolean.FALSE);
            System.out.println("set GraphicsEnvironment.headless/defaultHeadless = false OK");
        } catch (Throwable t) { System.out.println("headless set FAILED: " + t); }

        // 2. CTC Toolkit (Toolkit.toolkit is a plain, non-final static field).
        try {
            setStatic(java.awt.Toolkit.class, "toolkit", ctcToolkit.getDeclaredConstructor().newInstance());
            System.out.println("set Toolkit.toolkit = CTCToolkit OK");
        } catch (Throwable t) { System.out.println("Toolkit.toolkit set FAILED: " + t); }

        // 3. Force the lazy holder to initialize FIRST. Its <clinit> runs the platform
        //    createGE() once (on mac CGraphicsEnvironment.<clinit>, which itself calls
        //    SurfaceManagerFactory.setInstance(platform)). We must NOT pre-set the SMF here:
        //    setInstance throws IllegalStateException if already set, which would fail
        //    LocalGE.<clinit> (ExceptionInInitializerError -> NoClassDefFoundError). So the
        //    platform SMF gets installed first; we overwrite it (and INSTANCE) afterwards.
        try {
            Class.forName("java.awt.GraphicsEnvironment$LocalGE"); // initialize=true -> <clinit>
            System.out.println("forced LocalGE.<clinit> (platform GE built) OK");
        } catch (Throwable t) {
            System.out.println("LocalGE.<clinit> FAILED: " + t);
            t.printStackTrace(System.out);
        }

        // 4. Now that the platform SMF is set, OVERWRITE the field directly (bypasses the
        //    setInstance guard) with the CTC factory — needed once CTC draws real pixels.
        try {
            setStatic("sun.java2d.SurfaceManagerFactory", "instance",
                      ctcSMF.getDeclaredConstructor().newInstance());
            System.out.println("overwrote SurfaceManagerFactory.instance = CTCSurfaceManagerFactory OK");
        } catch (Throwable t) { System.out.println("SMF overwrite FAILED: " + t); }

        // 5. Overwrite the GE singleton (static-final) in the now-initialized holder via
        //    Unsafe. getLocalGraphicsEnvironment() re-reads INSTANCE each call -> returns CTC.
        try {
            Class<?> localGE = Class.forName("java.awt.GraphicsEnvironment$LocalGE");
            Object ctcGEInstance = ctcGE.getMethod("getInstance").invoke(null);
            Field instance = localGE.getDeclaredField("INSTANCE");
            Object base = mBase.invoke(U, instance);
            long off = (Long) mOffset.invoke(U, instance);
            mPutObj.invoke(U, base, off, ctcGEInstance);
            System.out.println("Unsafe-overwrote LocalGE.INSTANCE = CTCGraphicsEnvironment OK");
        } catch (Throwable t) {
            System.out.println("LocalGE.INSTANCE overwrite FAILED: " + t);
            t.printStackTrace(System.out);
        }

        AwtActivation.main(args); // the no-pixel gate + greppable AWT-ACTIVATION verdict
    }
}
