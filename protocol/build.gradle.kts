plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("junit:junit:4.13.2")
}

// A command-line test client that speaks to a real Sill host (see tools/README in the repo root).
application {
    mainClass.set("dev.remotemac.protocol.cli.TestClientKt")
}
