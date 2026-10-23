package dev.musicstream.core.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Wraps every value in [Result.success] and turns a failure into one final
 * [Result.failure] holding an [AppError], so collectors never see exceptions.
 * Cancellation is not caught: `catch` rethrows it.
 */
fun <T> Flow<T>.asResult(): Flow<Result<T>> =
    map { Result.success(it) }
        .catch { e -> emit(Result.failure(e as? AppError ?: AppError.Unknown(e))) }
