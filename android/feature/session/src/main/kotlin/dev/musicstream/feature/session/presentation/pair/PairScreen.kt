package dev.musicstream.feature.session.presentation.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.common.AppError
import dev.musicstream.core.ui.error.asAppError
import dev.musicstream.core.ui.error.message
import dev.musicstream.feature.session.R
import dev.musicstream.feature.session.domain.usecase.PairServerUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

data class PairUiState(val busy: Boolean = false, val error: AppError? = null)

@HiltViewModel
class PairViewModel @Inject constructor(private val pair: PairServerUseCase) : ViewModel() {
    private val _state = MutableStateFlow(PairUiState())
    val state: StateFlow<PairUiState> = _state

    fun connect(code: String) = run(pair(code))
    fun connectManual(host: String, pin: String) = run(pair.manual(host, pin))

    private fun run(attempt: Flow<Result<Unit>>) {
        attempt
            .onStart { _state.value = PairUiState(busy = true) }
            // Success flips RootViewModel to the login screen automatically.
            .onEach { result ->
                result.onFailure { _state.value = PairUiState(error = it.asAppError()) }
                    .onSuccess { _state.value = PairUiState() }
            }
            .launchIn(viewModelScope)
    }
}

@Composable
fun PairScreen(vm: PairViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.pair_description), style = MaterialTheme.typography.bodyMedium)
        if (!manual) {
            OutlinedTextField(
                value = code, onValueChange = { code = it.trim() },
                label = { Text(stringResource(R.string.pair_code_label)) },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
        } else {
            OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.pair_manual_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin, { pin = it.trim() }, label = { Text(stringResource(R.string.pair_manual_pin)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        state.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { if (manual) vm.connectManual(host, pin) else vm.connect(code) },
            enabled = !state.busy && (if (manual) host.isNotBlank() else code.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.pair_connect))
        }
        TextButton(onClick = { manual = !manual }) {
            Text(stringResource(if (manual) R.string.pair_use_code else R.string.pair_manual))
        }
    }
}
