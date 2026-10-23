package dev.musicstream.core.network

import dev.musicstream.core.common.AppError
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Translates transport failures into domain errors. */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is HttpException -> when (code()) {
        401 -> AppError.Unauthorized()
        404 -> AppError.NotFound()
        400, 409, 415 -> AppError.Invalid(message())
        429 -> AppError.TooManyAttempts()
        else -> AppError.Server(code())
    }
    is SSLException -> if (message?.contains("Certificate", ignoreCase = true) == true || cause is java.security.cert.CertificateException) {
        AppError.CertificateMismatch()
    } else {
        AppError.Network(this)
    }
    is ConnectException, is SocketTimeoutException, is UnknownHostException -> AppError.ServerUnreachable(this)
    is IOException -> AppError.Network(this)
    else -> AppError.Unknown(this)
}

/** Runs a network call, rethrowing cancellation and mapping everything else. */
suspend inline fun <T> apiCall(block: () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    throw e.toAppError()
}

/** One network call as a cold [Flow]: emits the result once, failures mapped like [apiCall]. */
fun <T> apiFlow(block: suspend () -> T): kotlinx.coroutines.flow.Flow<T> =
    kotlinx.coroutines.flow.flow { emit(apiCall { block() }) }
