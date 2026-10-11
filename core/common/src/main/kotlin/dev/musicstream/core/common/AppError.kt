package dev.musicstream.core.common

/**
 * Failures the UI can explain to the user. Repositories translate transport
 * exceptions into these so presentation never sees HTTP or IO types.
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class ServerUnreachable(cause: Throwable? = null) : AppError("server unreachable", cause)
    class Network(cause: Throwable? = null) : AppError("network error", cause)
    class Unauthorized : AppError("unauthorized")
    class InvalidCredentials : AppError("invalid credentials")
    class TooManyAttempts : AppError("too many attempts")
    class NotFound : AppError("not found")
    class Invalid(message: String) : AppError(message)
    class CertificateMismatch : AppError("server certificate does not match the pinned key")
    class Server(val code: Int) : AppError("server error $code")
    class Unknown(cause: Throwable) : AppError(cause.message ?: "unknown error", cause)
}
