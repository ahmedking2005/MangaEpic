package org.koitharu.kotatsu.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.stats.data.StatsRepository
import javax.inject.Inject

/** Reading statistics for the Profile tab (Manga Epic). */
@HiltViewModel
class ProfileViewModel @Inject constructor(
	private val repository: StatsRepository,
) : ViewModel() {

	val todayDuration = MutableStateFlow<Long?>(null)

	/** Durations of the last 7 days, oldest first, today last. */
	val weekDurations = MutableStateFlow<List<Long>>(emptyList())
	val totalDuration = MutableStateFlow<Long?>(null)
	val chaptersRead = MutableStateFlow<Int?>(null)

	init {
		load()
	}

	fun load() {
		viewModelScope.launch {
			runCatching {
				val week = repository.getDailyDurations(WEEK_DAYS)
				weekDurations.value = week
				todayDuration.value = week.lastOrNull() ?: 0L
				totalDuration.value = repository.getTotalDuration()
				chaptersRead.value = repository.getTotalChaptersRead()
			}.onFailure {
				todayDuration.value = 0L
				totalDuration.value = 0L
				chaptersRead.value = 0
			}
		}
	}

	private companion object {
		const val WEEK_DAYS = 7
	}
}
