plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.http4k.core)
    api(libs.http4k.realtime.core)
    implementation(libs.kotlinx.serialization.json)
    api(project(":model"))
    testImplementation(kotlin("test"))
}
