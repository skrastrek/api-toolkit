plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.http4k.core)
    api(libs.http4k.realtime.core)
    api(project(":model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
