// Root project: no build logic of its own, only aggregates :core and :app.
plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlinx.kover") version "0.8.3" apply false
}
