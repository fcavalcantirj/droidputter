plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlinx.kover")
}

dependencies {
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
