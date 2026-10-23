package dev.musicstream.core.common

import kotlinx.coroutines.CancellationException

/** Wraps a block so that every failure surfaces as an [AppError]. */
suspend fun <T> runCatchingApp(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: AppError) {
    Result.failure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(AppError.Unknown(e))
}
