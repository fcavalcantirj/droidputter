plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(layout.buildDirectory.dir("generated/demoAssets"))
            assets.srcDir(layout.buildDirectory.dir("generated/catalogAssets"))
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// Demo mode (no USB) replays fixtures/pense-bem/boot.{bin,jsonl} through FixtureTransport;
// bundle it as an asset from the repo's tracked copy instead of committing a second one under
// android/ (the repo .gitignore blankets *.bin except fixtures/**/*.bin).
val demoFixtureSrc = rootProject.projectDir.parentFile.resolve("fixtures/pense-bem")

val copyDemoFixture by tasks.registering(Copy::class) {
    from(demoFixtureSrc) {
        include("boot.bin", "boot.jsonl")
    }
    into(layout.buildDirectory.dir("generated/demoAssets/fixtures/pense-bem"))
}

// Catalog screen: bundles apps/catalog.json plus, for whichever entries have been built
// locally (apps/*/.pio/build/<env>/*.bin), their actual bin parts, so the share-to-flasher
// action has real bytes to hand off. An entry whose build_dir doesn't exist on this machine
// yet (fresh clone, or a board that hasn't been built this session) just ships without its
// bin files -- CatalogRepository treats a missing asset as "not available to share".
val catalogJsonSrc = rootProject.projectDir.parentFile.resolve("apps/catalog.json")

data class CatalogAssetSource(val assetDirName: String, val buildDir: File, val files: List<String>)

val catalogAssetSources: List<CatalogAssetSource> = if (catalogJsonSrc.isFile) {
    @Suppress("UNCHECKED_CAST")
    val entries = groovy.json.JsonSlurper().parse(catalogJsonSrc) as List<Map<String, Any?>>
    entries.map { entry ->
        val name = entry["name"] as String
        val env = entry["env"] as String
        val buildDir = (entry["build_dir"] as? String)
            ?.let { rootProject.projectDir.parentFile.resolve(it) }
            ?: rootProject.projectDir.parentFile.resolve("apps/$name/.pio/build/$env")
        @Suppress("UNCHECKED_CAST")
        val files = (entry["parts"] as List<Map<String, Any?>>).map { it["file"] as String }
        CatalogAssetSource("$name-$env", buildDir, files)
    }
} else {
    emptyList()
}

val verdictsJsonSrc = rootProject.projectDir.parentFile.resolve("apps/verdicts.json")

val copyCatalogManifest by tasks.registering(Copy::class) {
    from(catalogJsonSrc)
    // Community verdicts: the app fetches the live file from GitHub; this copy is the offline seed.
    if (verdictsJsonSrc.isFile) from(verdictsJsonSrc)
    into(layout.buildDirectory.dir("generated/catalogAssets/catalog"))
}

// boot_app0.bin (arduino-esp32's fixed OTA-data image, see docs/FLASHING.md) isn't a per-app
// PlatformIO build output -- it lives in the toolchain package, not any app's build_dir -- so
// every entry needing it (tools/make_catalog.py's PARTS table) gets it copied from there too.
val bootApp0Src = File(
    System.getProperty("user.home"),
    ".platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin",
)

val copyCatalogBins by tasks.registering(Copy::class) {
    for (source in catalogAssetSources) {
        if (source.buildDir.isDirectory) {
            from(source.buildDir) {
                include(source.files)
                into(source.assetDirName)
            }
        }
        if (source.files.contains("boot_app0.bin") && bootApp0Src.isFile) {
            from(bootApp0Src) {
                into(source.assetDirName)
            }
        }
    }
    into(layout.buildDirectory.dir("generated/catalogAssets/catalog"))
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(copyDemoFixture, copyCatalogManifest, copyCatalogBins)
    }
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
