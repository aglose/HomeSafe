package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.data.ConnectionHistoryDao
import com.meticulouscreations.homesafe.data.ConnectionHistoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class SecureConnectionViewModel(
    private val connectionHistoryDao: ConnectionHistoryDao,
) : ViewModel() {

    val mostRecentConnection: StateFlow<ConnectionHistoryEntity?> =
        connectionHistoryDao.mostRecentAsFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalTime::class)
    fun recordConnection(serverUrl: String) {
        viewModelScope.launch {
            connectionHistoryDao.insert(
                ConnectionHistoryEntity(
                    serverUrl = serverUrl,
                    connectedAtEpochMillis = Clock.System.now().toEpochMilliseconds(),
                ),
            )
        }
    }
}
