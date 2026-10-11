package dev.musicstream.core.ui.error

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.musicstream.core.common.AppError
import dev.musicstream.core.ui.R

/** User-facing text for a domain error. */
@Composable
fun AppError.message(): String = when (this) {
    is AppError.ServerUnreachable -> stringResource(R.string.error_server_unreachable)
    is AppError.Network -> stringResource(R.string.error_network)
    is AppError.Unauthorized -> stringResource(R.string.error_unauthorized)
    is AppError.InvalidCredentials -> stringResource(R.string.error_invalid_credentials)
    is AppError.TooManyAttempts -> stringResource(R.string.error_too_many_attempts)
    is AppError.NotFound -> stringResource(R.string.error_not_found)
    is AppError.CertificateMismatch -> stringResource(R.string.error_certificate)
    is AppError.Server -> stringResource(R.string.error_server, code)
    is AppError.Invalid -> stringResource(R.string.error_invalid, message.orEmpty())
    is AppError.Unknown -> stringResource(R.string.error_unknown)
}

fun Throwable.asAppError(): AppError = this as? AppError ?: AppError.Unknown(this)

/** Resolves error strings outside of composition (for snackbars). */
object ErrorTexts {
    @Composable
    fun rememberAll(): (AppError) -> String {
        val unreachable = stringResource(R.string.error_server_unreachable)
        val network = stringResource(R.string.error_network)
        val unauthorized = stringResource(R.string.error_unauthorized)
        val notFound = stringResource(R.string.error_not_found)
        val cert = stringResource(R.string.error_certificate)
        val unknown = stringResource(R.string.error_unknown)
        val context = LocalContext.current
        return { e ->
            when (e) {
                is AppError.ServerUnreachable -> unreachable
                is AppError.Network -> network
                is AppError.Unauthorized -> unauthorized
                is AppError.NotFound -> notFound
                is AppError.CertificateMismatch -> cert
                is AppError.Server -> context.getString(R.string.error_server, e.code)
                is AppError.Invalid -> context.getString(R.string.error_invalid, e.message.orEmpty())
                else -> unknown
            }
        }
    }
}
