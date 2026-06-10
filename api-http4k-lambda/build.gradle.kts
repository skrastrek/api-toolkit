dependencies {
    implementation(libs.aws.lambda.kotlin.events)
    api(libs.http4k.core)
    api(libs.http4k.realtime.core)
    implementation(libs.http4k.serverless.lambda)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}
