package dev.musicstream.feature.session.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.ui.component.DetailScaffold
import dev.musicstream.core.ui.component.ErrorState
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.core.ui.state.UiState
import dev.musicstream.core.ui.state.asUiState
import dev.musicstream.feature.session.R
import dev.musicstream.feature.session.domain.model.ServerInfo
import dev.musicstream.feature.session.domain.usecase.GetServerInfoUseCase
import dev.musicstream.feature.session.domain.usecase.LogoutUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getServerInfo: GetServerInfoUseCase,
    private val logout: LogoutUseCase,
) : ViewModel() {
    private val _info = MutableStateFlow<UiState<ServerInfo>>(UiState.Loading)
    val info: StateFlow<UiState<ServerInfo>> = _info

    init { load() }

    private var loading: Job? = null

    fun load() {
        loading?.cancel()
        loading = getServerInfo().asUiState()
            .onEach { if (it !is UiState.Loading || _info.value !is UiState.Success) _info.value = it }
            .launchIn(viewModelScope)
    }

    fun logout(forgetServer: Boolean) {
        logout.invoke(forgetServer).launchIn(viewModelScope)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val info by vm.info.collectAsStateWithLifecycle()
    DetailScaffold(stringResource(R.string.settings), onBack) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.server_section), style = MaterialTheme.typography.titleMedium)
            when (val s = info) {
                UiState.Loading -> LoadingState()
                is UiState.Error -> ErrorState(s.error, vm::load, Modifier.padding(0.dp))
                is UiState.Success -> {
                    Text(stringResource(R.string.server_version, s.data.version))
                    Text(stringResource(R.string.server_tracks, s.data.trackCount))
                    if (s.data.scanning) Text(stringResource(R.string.server_scanning))
                    Text(stringResource(if (s.data.transcodeAvailable) R.string.server_transcode_on else R.string.server_transcode_off))
                }
            }
            Spacer(Modifier.width(16.dp))
            TextButton(onClick = { vm.logout(false) }) { Text(stringResource(R.string.logout)) }
            TextButton(onClick = { vm.logout(true) }) { Text(stringResource(R.string.forget_server), color = MaterialTheme.colorScheme.error) }
        }
    }
}
