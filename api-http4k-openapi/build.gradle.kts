plugins {
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.http4k.core)
    api(libs.http4k.api.openapi)
    api(libs.http4k.format.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    api(project(":api-http4k-errors"))
    api(project(":api-openapi-annotations"))
    testImplementation(kotlin("test"))
}
