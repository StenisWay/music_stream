package dev.musicstream.core.ui.state

import dev.musicstream.core.common.AppError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** Standard screen state for one-shot loads. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val error: AppError) : UiState<Nothing>
}

fun <T> Result<T>.toUiState(): UiState<T> = fold(
    onSuccess = { UiState.Success(it) },
    onFailure = { UiState.Error(it as? AppError ?: AppError.Unknown(it)) },
)

/** Maps a result flow to screen state, starting with [UiState.Loading]. */
fun <T> Flow<Result<T>>.asUiState(): Flow<UiState<T>> =
    map { it.toUiState() }.onStart { emit(UiState.Loading) }
