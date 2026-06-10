@file:OptIn(ExperimentalSerializationApi::class)

package io.skrastrek.api.openapi.annotations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PrimitiveKind.BYTE
import kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE
import kotlinx.serialization.descriptors.PrimitiveKind.FLOAT
import kotlinx.serialization.descriptors.PrimitiveKind.INT
import kotlinx.serialization.descriptors.PrimitiveKind.LONG
import kotlinx.serialization.descriptors.PrimitiveKind.SHORT
import kotlinx.serialization.descriptors.PrimitiveKind.STRING
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind.LIST

interface OpenApiConstraint {
    val applicableTo: Set<SerialKind>
}

private val NUMERIC = setOf<SerialKind>(INT, LONG, SHORT, BYTE, FLOAT, DOUBLE)

/**
 * Documents the OpenAPI `format` keyword for a string property (e.g. `"date-time"`, `"uri"`).
 *
 * For documentation purposes only — does not enforce or validate the format at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Format(
    val value: String,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(STRING)
    }
}

/**
 * Documents the OpenAPI `minLength` constraint for a string property.
 *
 * For documentation purposes only — does not enforce the minimum length at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MinLength(
    val value: Int,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(STRING)
    }
}

/**
 * Documents the OpenAPI `maxLength` constraint for a string property.
 *
 * For documentation purposes only — does not enforce the maximum length at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxLength(
    val value: Int,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(STRING)
    }
}

/**
 * Documents the OpenAPI `pattern` constraint for a string property.
 *
 * For documentation purposes only — does not enforce the pattern at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Pattern(
    val value: String,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(STRING)
    }
}

/**
 * Documents the OpenAPI `minimum` constraint for a numeric property.
 *
 * For documentation purposes only — does not enforce the minimum value at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Minimum(
    val value: Double,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = NUMERIC
    }
}

/**
 * Documents the OpenAPI `maximum` constraint for a numeric property.
 *
 * For documentation purposes only — does not enforce the maximum value at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Maximum(
    val value: Double,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = NUMERIC
    }
}

/**
 * Documents the OpenAPI `minItems` constraint for an array property.
 *
 * For documentation purposes only — does not enforce the minimum item count at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MinItems(
    val value: Int,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(LIST)
    }
}

/**
 * Documents the OpenAPI `maxItems` constraint for an array property.
 *
 * For documentation purposes only — does not enforce the maximum item count at runtime.
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxItems(
    val value: Int,
) {
    companion object : OpenApiConstraint {
        override val applicableTo = setOf<SerialKind>(LIST)
    }
}
