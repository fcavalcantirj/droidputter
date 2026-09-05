plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Demo mode (no USB) replays fixtures/pense-bem/boot.{bin,jsonl} through FixtureTransport;
// bundle it as an asset from the repo's tracked copy instead of committing a second one under
// android/ (the repo .gitignore blankets *.bin except fixtures/**/*.bin).
val demoFixtureSrc = rootProject.projectDir.parentFile.resolve("fixtures/pense-bem")

val copyDemoFixture by tasks.registering(Copy::class) {
    from(demoFixtureSrc) {
        include("boot.bin", "boot.jsonl")
        into("fixtures/pense-bem")
    }
    into(layout.buildDirectory.dir("generated/demoAssets"))
}

// Catalog screen: bundles apps/catalog.json (+ apps/verdicts.json) only as the offline SEED of the
// live index the app fetches from GitHub at run time (CatalogRepository / VerdictRepository). No
// firmware binaries ship in the APK (Felipe, 2026-09-03: "hold data only; download at flash time") --
// BinStore downloads each part by its catalog url when the user flashes or shares, into a
// sha256-verified cache under filesDir/bins.
val catalogJsonSrc = rootProject.projectDir.parentFile.resolve("apps/catalog.json")
val verdictsJsonSrc = rootProject.projectDir.parentFile.resolve("apps/verdicts.json")

// Sync, not Copy: the destination is an assets srcDir, so anything stale in it (the bundled bins of
// builds before 2026-09-04) would still ship. Sync leaves exactly these two seed files.
val copyCatalogManifest by tasks.registering(Sync::class) {
    from(catalogJsonSrc) { into("catalog") }
    if (verdictsJsonSrc.isFile) from(verdictsJsonSrc) { into("catalog") }
    into(layout.buildDirectory.dir("generated/catalogAssets"))
}

android {
    namespace = "com.droidputter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.droidputter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Build proxy origin override for LAN spikes: `./gradlew assembleDebug -PproxyBaseUrl=http://<mac>:8787`.
        // Empty = the app's default (BuildProxy.DEFAULT_BASE_URL, the deployed proxy).
        buildConfigField("String", "PROXY_BASE_URL", "\"${project.findProperty("proxyBaseUrl") ?: ""}\"")
        // USB reader buffer for A/B runs: `-PusbReadBuffer=0` keeps the library default (the endpoint's 64 B max
        // packet, one USB request per packet); the default here is the 16 KB measured on stellar-map 2026-09-05.
        buildConfigField("int", "USB_READ_BUFFER", "${project.findProperty("usbReadBuffer") ?: "16384"}")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Dev-key signed until Felipe supplies a release keystore (a store secret is his call). CI signs with
            // the runner's throwaway debug key, so a CI-built release APK is re-signed on the Mac with
            // ~/.android/debug.keystore (apksigner) before `adb install -r` over the debug build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            // Providers derived from the copy tasks, not plain directories: every consumer of the assets
            // (mergeAssets, lintVital's model writer, ...) then depends on the copy tasks implicitly -- the
            // release CI run of 2026-09-05 failed on "uses this output without declaring a dependency".
            assets.srcDir(copyDemoFixture.map { it.destinationDir })
            assets.srcDir(copyCatalogManifest.map { it.destinationDir })
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
