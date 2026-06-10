package io.skrastrek.api.toolkit.http4k.openapi

import io.skrastrek.api.toolkit.http4k.errors.ErrorResponse
import io.skrastrek.api.toolkit.http4k.errors.toError
import io.skrastrek.api.toolkit.http4k.errors.toResponse
import io.skrastrek.api.toolkit.http4k.openapi.KotlinxSerialization.auto
import io.skrastrek.api.toolkit.model.Error
import org.http4k.contract.ContractRenderer
import org.http4k.contract.ResponseMeta
import org.http4k.core.Body
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.LensFailure

val errorResponse: BiDiBodyLens<ErrorResponse> by lazy { Body.auto<ErrorResponse>().toLens() }

fun Response.body(error: ErrorResponse): Response = with(errorResponse of error)

fun errorResponseMeta(status: Status): ResponseMeta =
    ResponseMeta(status.description, Response(status).body(ErrorResponse(status.description)), "Error")

fun Error.Companion.contractRenderer(delegate: ContractRenderer): ContractRenderer =
    object : ContractRenderer by delegate {
        override fun badRequest(lensFailure: LensFailure): Response = lensFailure.toError().toResponse()
    }
