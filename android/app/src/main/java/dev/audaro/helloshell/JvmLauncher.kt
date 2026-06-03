package dev.audaro.helloshell

/**
 * Thin Kotlin facade over the native JNI Invocation-API bootstrap.
 *
 * The app process is hosted by ART, but the game runs on a *second*, in-process HotSpot VM that the
 * native layer brings up with JNI_CreateJavaVM (dlopen of the embedded JRE's libjvm.so). This mirrors
 * the proven iOS bootstrap (JVMLauncher.m) minus every iOS-JIT workaround — Android grants JIT for free.
 */
object JvmLauncher {

    init {
        System.loadLibrary("helloshell")
    }

    /**
     * Bring up the embedded HotSpot VM and run [mainClass].main(args) on it (blocking; call off the UI
     * thread). Returns the JNI_CreateJavaVM result (0 == JNI_OK).
     *
     * @param javaHome    filesDir path of the unpacked JRE (contains lib/server/libjvm.so).
     * @param classpath   ':'-joined jar list on the embedded VM's classpath.
     * @param libraryPath ':'-joined java.library.path (the APK native-lib dir, for libawt etc.).
     * @param mainClass   binary name of the entrypoint class to invoke.
     * @param extraOpts   additional -X / -D / --add-* VM options.
     */
    external fun startJvm(
        javaHome: String,
        classpath: String,
        libraryPath: String,
        mainClass: String,
        extraOpts: Array<String>,
    ): Int
}
