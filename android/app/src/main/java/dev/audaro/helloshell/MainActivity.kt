package dev.audaro.helloshell

import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Milestone A host: brings up the embedded HotSpot VM and runs the Hello entrypoint, streaming a status
 * line to the screen. Milestone B replaces the TextView with a SurfaceView and drives DeviceHeadlessBoot.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 12f; setPadding(24, 48, 24, 24) }
        setContentView(ScrollView(this).apply { addView(logView) })

        thread(name = "jvm-boot") { bootJvm() }
    }

    private fun bootJvm() {
        val javaHome = AssetInstaller.ensureInstalled(this)
        val libjvm = File(javaHome, "lib/server/libjvm.so")
        if (!libjvm.exists()) {
            post("ERROR: embedded JRE not installed.\nExpected $libjvm\n" +
                 "Stage the Android arm64 JRE into assets/jvm/jre + jniLibs (see android/README.md).")
            return
        }

        // Milestone A: only a couple of jars are needed; the full client classpath comes in Milestone B.
        val classpath = listOf("hello.jar")
            .joinToString(":") { File(File(filesDir, "jvm"), it).absolutePath }
        val libraryPath = applicationInfo.nativeLibraryDir

        post("Embedded JRE found.\nStarting HotSpot…")
        val rc = JvmLauncher.startJvm(
            javaHome = javaHome.absolutePath,
            classpath = classpath,
            libraryPath = libraryPath,
            mainClass = "Hello",
            extraOpts = arrayOf("-XX:+PrintCompilation"),
        )
        Log.i("MainActivity", "startJvm rc=$rc")
        post("\nHotSpot exited (JNI_CreateJavaVM rc=$rc). Check logcat for Hello output.")
    }

    private fun post(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
    }
}
