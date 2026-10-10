package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.AppVisibility
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * [FinanceAccess] from the connection as [ConnectionRepository] reports it: online while there is
 * a session and the server was answering when last asked, which is exactly when the top bar's
 * badge names a route rather than saying "Offline".
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FinanceAccessImpl(
    connectionRepository: ConnectionRepository,
    private val identityCheck: IdentityCheck,
    appScope: CoroutineScope,
) : FinanceAccess {

    private val unlocked = MutableStateFlow(false)

    override val online: StateFlow<Boolean> =
        combine(connectionRepository.activeConnection, connectionRepository.serverUnreachable) { connection, unreachable -> connection != null && !unreachable }
            .stateIn(appScope, SharingStarted.Eagerly, connectionRepository.activeConnection.value != null && !connectionRepository.serverUnreachable.value)

    override val locked: StateFlow<Boolean> =
        combine(online, unlocked) { online, unlocked -> !online && !unlocked }.stateIn(appScope, SharingStarted.Eagerly, !online.value)

    override val canUnlock: Boolean get() = identityCheck.isAvailable

    override val unlockMethod: StringResource get() = identityCheck.methodName

    init {
        // A fingerprint stands in for the server only while there is none: once it answers, the
        // next time it stops answering asks again.
        appScope.launch { online.collect { if (it) unlocked.value = false } }
        // And only while the app is being looked at: put down and picked up again, it asks again.
        appScope.launch { AppVisibility.inForeground.collect { if (!it) unlocked.value = false } }
    }

    override suspend fun unlock(): Result<Unit> {
        if (online.value || unlocked.value) return Result.success(Unit)
        return identityCheck.confirm().onSuccess { unlocked.value = true }
    }

    override fun lock() {
        unlocked.value = false
    }
}
