package dev.musicstream.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.musicstream.core.ui.component.LoadingState
import dev.musicstream.feature.session.domain.model.SessionState
import dev.musicstream.feature.session.domain.usecase.ObserveSessionStateUseCase
import dev.musicstream.feature.session.presentation.login.LoginScreen
import dev.musicstream.feature.session.presentation.pair.PairScreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Decides which top-level flow to show from persisted session state (null while loading). */
@HiltViewModel
class RootViewModel @Inject constructor(observeSession: ObserveSessionStateUseCase) : ViewModel() {
    val state: StateFlow<SessionState?> = observeSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@Composable
fun AppRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (state) {
        null -> LoadingState()
        SessionState.NEEDS_PAIRING -> PairScreen()
        SessionState.NEEDS_LOGIN -> LoginScreen()
        SessionState.READY -> MainScaffold()
    }
}
