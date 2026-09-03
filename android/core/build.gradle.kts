plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("droidputter.fixturesDir", rootProject.projectDir.parentFile.resolve("fixtures").absolutePath)
    systemProperty("droidputter.appsDir", rootProject.projectDir.parentFile.resolve("apps").absolutePath)
}

kover {
    reports {
        verify {
            rule {
                minBound(80)
            }
        }
    }
}
