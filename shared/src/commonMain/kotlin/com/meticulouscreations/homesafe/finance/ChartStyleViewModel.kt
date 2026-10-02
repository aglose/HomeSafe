package com.meticulouscreations.homesafe.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.finance.domain.ChartStyle
import com.meticulouscreations.homesafe.finance.domain.ChartStyleRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The finance charts' shared look and feel. Every chart in the finance app draws with [style]
 * (handed down through the theme), and the chart settings page changes it with [update].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class ChartStyleViewModel(private val repository: ChartStyleRepository) : ViewModel() {

    private val _style = MutableStateFlow(ChartStyle.DEFAULT)
    val style: StateFlow<ChartStyle> = _style.asStateFlow()

    init {
        viewModelScope.launch { repository.observe().collect { _style.value = it } }
    }

    fun update(style: ChartStyle) {
        // Shown at once, so a tap on the settings page lands in the preview before the write does.
        _style.value = style
        viewModelScope.launch { repository.update(style) }
    }
}
