package com.meticulouscreations.homesafe.finance.data

import com.meticulouscreations.homesafe.AppVisibility
import com.meticulouscreations.homesafe.domain.platform.IdentityCheck
import com.meticulouscreations.homesafe.domain.repository.ConnectionRepository
import com.meticulouscreations.homesafe.finance.domain.FinanceAccess
import com.meticulouscreations.homesafe.text.LocalizedException
import com.meticulouscreations.homesafe.text.UiText
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import homesafe.shared.generated.resources.Res
import homesafe.shared.generated.resources.finance_lock_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.StringResource

/**
 * [FinanceAccess] from the connection as [ConnectionRepository] reports it: online while there is
 * a session and the server was answering when last asked, which is exactly when the top bar's
 * badge names a route rather than saying "Offline".
 *
 * [online] and [locked] are worked out from the connection where they are read from ([settle]),
 * not only as it reports changes: a repository that has just had the connection look again reads
 * the answer on its next line, not a dispatch later.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FinanceAccessImpl(
    private val connectionRepository: ConnectionRepository,
    private val identityCheck: IdentityCheck,
    private val appScope: CoroutineScope,
) : FinanceAccess {

    /**
     * Whether a fingerprint is standing in for the server, and how many times that has been
     * taken back ([epoch]): a prompt answered after the lock went back on is answering a question
     * nobody is asking any more, and must not unlock what came after.
     */
    private data class Unlock(val epoch: Int = 0, val unlocked: Boolean = false)

    private val unlock = MutableStateFlow(Unlock())
    private val _online = MutableStateFlow(connected())
    private val _locked = MutableStateFlow(!connected())

    override val online: StateFlow<Boolean> = _online.asStateFlow()
    override val locked: StateFlow<Boolean> = _locked.asStateFlow()

    override val canUnlock: Boolean get() = identityCheck.isAvailable

    override val unlockMethod: StringResource get() = identityCheck.methodName

    private val checking = Mutex()
    private var recheck: Job? = null

    init {
        appScope.launch {
            combine(connectionRepository.activeConnection, connectionRepository.serverUnreachable) { _, _ -> }.collect { settle() }
        }
        // Only while the app is being looked at: put down and picked up again, it asks again.
        appScope.launch { AppVisibility.inForeground.collect { if (!it) lock() } }
    }

    private fun connected(): Boolean = connectionRepository.activeConnection.value != null && !connectionRepository.serverUnreachable.value

    private fun relock() = unlock.update { Unlock(it.epoch + 1, unlocked = false) }

    /** Brings [online] and [locked] up to what is true this moment. */
    private fun settle() {
        val connected = connected()
        // A fingerprint stands in for the server only while there is none: once it answers, the
        // next time it stops answering asks again.
        if (connected && unlock.value.unlocked) relock()
        _online.value = connected
        _locked.value = !connected && !unlock.value.unlocked
    }

    override suspend fun unlock(): Result<Unit> {
        settle()
        if (!_locked.value) return Result.success(Unit)
        val asked = unlock.value.epoch
        identityCheck.confirm().onFailure { return Result.failure(it) }
        // The prompt can outlast what it was asked for: the app closed or left the screen under
        // it, or the server came back. Only an answer to the lock that is still on counts.
        var counted = false
        if (AppVisibility.inForeground.value) {
            unlock.update {
                counted = it.epoch == asked
                if (counted) it.copy(unlocked = true) else it
            }
        }
        settle()
        return if (!_locked.value) Result.success(Unit) else Result.failure(LocalizedException(UiText.of(Res.string.finance_lock_failed), technical = "The lock was put back on while the prompt was up"))
    }

    override fun lock() {
        relock()
        settle()
    }

    override suspend fun serverUnanswered() {
        // Several pages find the server gone at once; one look answers them all.
        checking.withLock {
            if (connected()) connectionRepository.checkServer()
            settle()
        }
        if (connectionRepository.activeConnection.value == null || connected() || recheck?.isActive == true) return
        // Nothing else is certain to look again (no network changed, the app didn't leave the
        // screen), and while the server is known to be gone the finance app doesn't ask it either.
        recheck = appScope.launch {
            while (connectionRepository.activeConnection.value != null && connectionRepository.serverUnreachable.value) {
                delay(RECHECK_MS)
                connectionRepository.checkServer()
            }
            settle()
        }
    }

    private companion object {
        const val RECHECK_MS = 15_000L
    }
}
