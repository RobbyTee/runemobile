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
// (which is why -Djava.awt.graphicsenv is ignored on JDK 21). With no instrumentation we
// OVERWRITE that static-final field via Unsafe after the holder initializes; since the
// accessor re-reads INSTANCE every call, subsequent calls return our CTC GE.
//
// THE ONE-SHOT SurfaceManagerFactory CONTENTION (the bug that first shipped): both the
// platform CGraphicsEnvironment.<clinit> AND `new CTCToolkit()` call
// sun.java2d.SurfaceManagerFactory.setInstance(), which throws IllegalStateException if
// already set. Whichever runs second blows up (it took down LocalGE.<clinit> ->
// NoClassDefFoundError -> FAILED). Fix: reset SurfaceManagerFactory.instance = null
// (reflection) right BEFORE each constructor that calls setInstance, so each install
// succeeds. (This is exactly why CacioExtension nulls the SMF too.)
//
// Sequence (ORDER IS LOAD-BEARING):
//   1. GraphicsEnvironment.headless / .defaultHeadless = Boolean.FALSE
//   2. SMF.instance=null, then force LocalGE.<clinit> (platform createGE installs platform SMF)
//   3. SMF.instance=null, then new CTCToolkit() + Toolkit.toolkit = it
//   4. SMF.instance=null, then CTCGraphicsEnvironment.getInstance()
//   5. Unsafe-overwrite the static-final LocalGE.INSTANCE = that CTC GE
//   6. SMF.instance = new CTCSurfaceManagerFactory() (final state, direct field write)
//
// Step 2 forces the platform createGE() once (throwaway). On desktop that builds a
// CGraphicsEnvironment; on the iOS jre21 it runs that build's PlatformGraphicsInfo.createGE()
// — the one DEVICE unknown this probe cannot answer offline (flagged for the device confirm).
//
// Requires the JPMS set we control in the device JNI_CreateJavaVM args, including
// `--add-exports java.desktop/sun.awt.image=ALL-UNNAMED` (CTC's CTCVolatileSurfaceManager
// extends sun.awt.image.VolatileSurfaceManager) plus the sun.awt/sun.java2d exports and the
// java.awt / sun.java2d / java.lang.reflect opens.
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
    // Reset the one-shot SurfaceManagerFactory so the next setInstance() call succeeds.
    static void nullSMF() {
        try { setStatic("sun.java2d.SurfaceManagerFactory", "instance", null); }
        catch (Throwable t) { System.out.println("nullSMF FAILED: " + t); }
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

        // 2. Force the holder to init FIRST (SMF virgin -> platform setInstance succeeds).
        try {
            nullSMF();
            Class.forName("java.awt.GraphicsEnvironment$LocalGE"); // initialize=true -> <clinit>
            System.out.println("forced LocalGE.<clinit> (platform GE built) OK");
        } catch (Throwable t) {
            System.out.println("LocalGE.<clinit> FAILED: " + t);
            t.printStackTrace(System.out);
        }

        // 3. CTC Toolkit (its construction calls SMF.setInstance -> reset first).
        try {
            nullSMF();
            setStatic(java.awt.Toolkit.class, "toolkit", ctcToolkit.getDeclaredConstructor().newInstance());
            System.out.println("set Toolkit.toolkit = CTCToolkit OK");
        } catch (Throwable t) { System.out.println("Toolkit.toolkit set FAILED: " + t); }

        // 4. Build the CTC GE (reset SMF first in case its init also installs one).
        Object ctcGEInstance = null;
        try {
            nullSMF();
            ctcGEInstance = ctcGE.getMethod("getInstance").invoke(null);
            System.out.println("built CTCGraphicsEnvironment.getInstance() OK");
        } catch (Throwable t) {
            System.out.println("CTC GE build FAILED: " + t);
            t.printStackTrace(System.out);
        }

        // 5. Unsafe-overwrite the static-final LocalGE.INSTANCE with the CTC GE.
        try {
            Class<?> localGE = Class.forName("java.awt.GraphicsEnvironment$LocalGE");
            Field instance = localGE.getDeclaredField("INSTANCE");
            Object base = mBase.invoke(U, instance);
            long off = (Long) mOffset.invoke(U, instance);
            mPutObj.invoke(U, base, off, ctcGEInstance);
            System.out.println("Unsafe-overwrote LocalGE.INSTANCE = CTCGraphicsEnvironment OK");
        } catch (Throwable t) {
            System.out.println("LocalGE.INSTANCE overwrite FAILED: " + t);
            t.printStackTrace(System.out);
        }

        // 6. Final SMF state = CTC factory (direct field write bypasses the setInstance guard).
        try {
            setStatic("sun.java2d.SurfaceManagerFactory", "instance",
                      ctcSMF.getDeclaredConstructor().newInstance());
            System.out.println("set SurfaceManagerFactory.instance = CTCSurfaceManagerFactory OK");
        } catch (Throwable t) { System.out.println("SMF set FAILED: " + t); }

        AwtActivation.main(args); // the no-pixel gate + greppable AWT-ACTIVATION verdict
    }
}
