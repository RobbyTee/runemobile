plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.audaro.helloshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.audaro.helloshell"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            // Emulator on Apple Silicon runs arm64 natively; the embedded HotSpot
            // JRE is an arm64 (bionic) build, so we ship only this ABI for now.
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The embedded JRE + client jars are unpacked to filesDir on first launch (AssetInstaller uses
    // ctx.assets.open(), which transparently inflates), and the app .so are extracted on install
    // (extractNativeLibs=true) — so none are ever read in-place from the APK. Letting the jars + .so
    // DEFLATE in the APK is therefore a download-size win (~20 MB) and runtime-neutral; the big modules
    // jimage already compresses + boots through the same path. Only the tiny .dat/.bin stay stored.
    androidResources {
        noCompress += listOf("dat", "bin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8/minify stays OFF on purpose: the embedded RuneLite engine + our headless bridges are resolved
            // by NAME via JNI FindClass (net.runelite.client.headless.*, DeviceHeadlessBoot, UnsafePokeActivation)
            // and RuneLite leans heavily on reflection internally — shrinking/renaming would break the engine. The
            // APK size is dominated by the unshrinkable native JRE + jars anyway, so R8 would save little.
            isMinifyEnabled = false
            isShrinkResources = false
            // Personal/technical sideload (never shipped to others): sign release with the debug
            // key so `assembleRelease` yields an installable, signed APK with no secret keystore to manage. Swap in
            // a dedicated release keystore here if this ever needs real distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // A4: Jetpack Compose for the data-driven plugin UI (the live overlays stay classic Views — see
    // PORT-PHASING.md §4.2 hybrid decision).
    buildFeatures {
        compose = true
        buildConfig = true   // A8: BuildConfig.DEBUG gates dev-only logging (stdio pipe, token PII, ext storage)
    }
}

// PROD-SECURITY-01 defense-in-depth: tokens.json is a live, replayable Jagex session secret. It lives ONLY under
// src/debug/assets (so release never bundles it), but src/main/assets is merged into EVERY variant — a stray copy
// left there locally (the path is gitignored, not absent) would silently ship in a release artifact and leak an
// account. This finalizer fails the release build fast if the secret ever reaches the merged release assets. It has
// no inputs/outputs, so it always runs (never UP-TO-DATE) whenever a release asset merge is in the graph.
val verifyNoReleaseSecret = tasks.register("verifyNoReleaseSecret") {
    description = "Fail the release build if the tokens.json session secret is present in the merged release assets."
    doLast {
        val merged = layout.buildDirectory.dir("intermediates/assets/release").get().asFile
        if (merged.exists()) {
            val leaked = merged.walkTopDown().filter { it.isFile && it.name == "tokens.json" }.toList()
            if (leaked.isNotEmpty()) {
                throw GradleException(
                    "PROD-SECURITY-01: tokens.json must never ship in a release artifact — found " +
                        leaked.joinToString { it.relativeTo(merged).path } +
                        ". Delete app/src/main/assets/tokens.json; the bundled session secret belongs only under " +
                        "app/src/debug/assets (debug builds).",
                )
            }
        }
    }
}
tasks.matching { it.name == "mergeReleaseAssets" }.configureEach { finalizedBy(verifyNoReleaseSecret) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.browser:browser:1.8.0")   // AP-0 outbound links + A3 on-device login (consent flow in a Custom Tab)

    // Compose (BOM-versioned) for the A4 plugin list / config UI.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
