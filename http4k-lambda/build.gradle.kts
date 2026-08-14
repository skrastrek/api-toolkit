dependencies {
    // api: SuspendingRequestStreamHandler is a public supertype of the lambda functions here.
    api(libs.aws.lambda.kotlin.coroutines)
    implementation(libs.aws.lambda.kotlin.events)
    api(libs.http4k.core)
    api(libs.http4k.realtime.core)
    implementation(libs.http4k.serverless.lambda)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
