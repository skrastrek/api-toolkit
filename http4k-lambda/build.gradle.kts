dependencies {
    // api: SuspendingRequestStreamHandler and ApiGatewayProxyV1Serializers are public supertypes of
    // the lambda functions here, and subclasses override `json` through the latter.
    api(libs.aws.lambda.kotlin.coroutines)
    api(libs.aws.lambda.kotlin.events)
    api(libs.http4k.core)
    api(libs.http4k.realtime.core)
    implementation(libs.http4k.serverless.lambda)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
