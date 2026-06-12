package io.skrastrek.api.toolkit.http4k.openapi

import kotlinx.coroutines.runBlocking
import org.http4k.contract.ContractRoute
import org.http4k.contract.ContractRouteSpec0
import org.http4k.contract.ContractRouteSpec1
import org.http4k.contract.ContractRouteSpec2
import org.http4k.contract.ContractRouteSpec3
import org.http4k.contract.ContractRouteSpec4
import org.http4k.contract.ContractRouteSpec5
import org.http4k.contract.ContractRouteSpec6
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response

private fun suspending(fn: suspend (Request) -> Response): HttpHandler = { request -> runBlocking { fn(request) } }

infix fun ContractRouteSpec0.Binder.toSuspending(fn: suspend (Request) -> Response): ContractRoute = to(suspending(fn))

infix fun <A> ContractRouteSpec1<A>.Binder.toSuspending(fn: suspend (A, Request) -> Response): ContractRoute =
    to { a -> suspending { request -> fn(a, request) } }

infix fun <A, B> ContractRouteSpec2<A, B>.Binder.toSuspending(fn: suspend (A, B, Request) -> Response): ContractRoute =
    to { a, b -> suspending { request -> fn(a, b, request) } }

infix fun <A, B, C> ContractRouteSpec3<A, B, C>.Binder.toSuspending(fn: suspend (A, B, C, Request) -> Response): ContractRoute =
    to { a, b, c -> suspending { request -> fn(a, b, c, request) } }

infix fun <A, B, C, D> ContractRouteSpec4<A, B, C, D>.Binder.toSuspending(fn: suspend (A, B, C, D, Request) -> Response): ContractRoute =
    to { a, b, c, d -> suspending { request -> fn(a, b, c, d, request) } }

infix fun <A, B, C, D, E> ContractRouteSpec5<A, B, C, D, E>.Binder.toSuspending(
    fn: suspend (A, B, C, D, E, Request) -> Response,
): ContractRoute = to { a, b, c, d, e -> suspending { request -> fn(a, b, c, d, e, request) } }

infix fun <A, B, C, D, E, F> ContractRouteSpec6<A, B, C, D, E, F>.Binder.toSuspending(
    fn: suspend (A, B, C, D, E, F, Request) -> Response,
): ContractRoute = to { a, b, c, d, e, f -> suspending { request -> fn(a, b, c, d, e, f, request) } }
