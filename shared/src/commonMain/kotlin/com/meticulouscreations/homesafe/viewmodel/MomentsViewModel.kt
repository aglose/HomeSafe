package com.meticulouscreations.homesafe.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meticulouscreations.homesafe.domain.model.MomentCategory
import com.meticulouscreations.homesafe.domain.model.MomentEvent
import com.meticulouscreations.homesafe.domain.usecase.ObserveMomentsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class MomentsViewModel(observeMomentsUseCase: ObserveMomentsUseCase) : ViewModel() {

    private val _selectedCategory = MutableStateFlow(MomentCategory.ALL)
    val selectedCategory: StateFlow<MomentCategory> = _selectedCategory.asStateFlow()

    val groupedMoments: StateFlow<Map<Pair<String, String>, List<MomentEvent>>> = combine(
        observeMomentsUseCase(),
        _selectedCategory,
    ) { events, category ->
        events
            .filter { category == MomentCategory.ALL || it.category == category }
            .groupBy { it.dateGroup to it.dateSubLabel }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun selectCategory(category: MomentCategory) {
        _selectedCategory.value = category
    }
}
