# api-toolkit

[![CI](https://github.com/skrastrek/api-toolkit/actions/workflows/ci.yml/badge.svg)](https://github.com/skrastrek/api-toolkit/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.skrastrek/api-model)](https://central.sonatype.com/search?q=g:io.skrastrek)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

Kotlin utilities for API development — error handling, HTTP caching, JWT/HMAC authentication, OpenAPI schema generation, and AWS Lambda integration.

## Modules

| Module | Description |
|---|---|
| `api-model` | Core interfaces: `Error`, `Versionable`, `Modifiable` |
| `api-openapi-annotations` | `@Format`, `@MinLength`, `@MaxLength`, `@Pattern`, `@Minimum`, `@Maximum`, `@MinItems`, `@MaxItems` |
| `api-http4k-errors` | `Error.responseFilter`, `sseResponseFilter`, `toError()` / `toResponse()` |
| `api-http4k-caching` | `ETag`, conditional GET (`ifNoneMatch`, `ifModifiedSince`) |
| `api-http4k-openapi` | Custom http4k OpenAPI renderer for kotlinx.serialization, `KotlinxSerialization`, `contractRenderer`, `errorResponseMeta` |
| `api-http4k-auth` | Generic `JwtValidator`, `HmacAuthTokenGenerator/Validator`, `TokenValidator` |
| `api-http4k-lambda` | AWS API Gateway REST Lambda handlers (`ApiGatewayRestPolyLambdaFunction`, `ApiGatewayRestSseLambdaFunction`) |

## Installation

Replace `<version>` with the latest release.

**Gradle (Kotlin DSL)**

```kotlin
dependencies {
    implementation("io.skrastrek:api-model:<version>")
    implementation("io.skrastrek:api-http4k-auth:<version>")
    implementation("io.skrastrek:api-http4k-caching:<version>")
    implementation("io.skrastrek:api-http4k-errors:<version>")
    implementation("io.skrastrek:api-http4k-openapi:<version>")
    implementation("io.skrastrek:api-http4k-lambda:<version>")
    implementation("io.skrastrek:api-openapi-annotations:<version>")
}
```

## License

Apache-2.0 — see [LICENSE](LICENSE).
