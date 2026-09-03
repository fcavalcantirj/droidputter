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

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(copyDemoFixture)
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.github.mik3y:usb-serial-for-android:3.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
