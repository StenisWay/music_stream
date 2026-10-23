package dev.musicstream.core.ui.state

import dev.musicstream.core.common.AppError

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
