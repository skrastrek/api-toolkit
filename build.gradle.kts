import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED
import org.gradle.api.tasks.testing.logging.TestLogEvent.PASSED
import org.gradle.api.tasks.testing.logging.TestLogEvent.SKIPPED
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension

allprojects {
    group = "io.skrastrek"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
    repositories {
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.ktlint).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "com.vanniktech.maven.publish")

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            optIn = listOf("kotlin.time.ExperimentalTime")
        }
    }

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()

        coordinates("io.skrastrek", project.name, project.version.toString())

        pom {
            name = project.name
            description = "api-toolkit — ${project.name}"
            url = "https://github.com/skrastrek/api-toolkit"
            inceptionYear = "2025"

            scm {
                url = "https://github.com/skrastrek/api-toolkit"
                connection = "scm:git://github.com:skrastrek/api-toolkit.git"
                developerConnection = "scm:git://github.com:skrastrek/api-toolkit.git"
            }

            licenses {
                license {
                    name = "Apache-2.0"
                    url = "https://opensource.org/licenses/Apache-2.0"
                }
            }

            developers {
                developer {
                    id = "sebramsland"
                    name = "Sebastian Ramsland"
                    email = "sebastian@skrastrek.io"
                    organizationUrl = "https://skrastrek.io"
                }
            }
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events = setOf(FAILED, PASSED, SKIPPED)
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        filePermissions { unix("644") }
        dirPermissions { unix("755") }
    }

    extensions.configure<KtlintExtension> {
        version = "1.8.0"
        outputToConsole = true
        verbose = true
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.AMAZON)
        }
    }
}
