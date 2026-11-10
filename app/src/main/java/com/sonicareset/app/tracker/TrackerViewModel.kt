package com.sonicareset.app.tracker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Exposes the tracked brush heads as a [StateFlow] and mediates mutations
 * through [TrackerRepository]. The flow is refreshed after every mutation.
 */
class TrackerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TrackerRepository(getApplication())

    private val _heads = MutableStateFlow(repository.list())
    val heads: StateFlow<List<BrushHead>> = _heads.asStateFlow()

    /** Add a new head installed now, with a fresh random id. */
    fun addHead(name: String, targetDays: Int, notes: String) {
        val head = BrushHead(
            id = UUID.randomUUID().toString(),
            name = name,
            installedAtEpochMillis = System.currentTimeMillis(),
            targetDays = targetDays,
            notes = notes
        )
        repository.add(head)
        refresh()
    }

    fun removeHead(id: String) {
        repository.remove(id)
        refresh()
    }

    /** Mark a new head mounted on an existing record by resetting its install date. */
    fun resetInstallDate(id: String) {
        val existing = repository.list().firstOrNull { it.id == id } ?: return
        repository.update(existing.copy(installedAtEpochMillis = System.currentTimeMillis()))
        refresh()
    }

    /** Reload from persistence into the exposed flow. */
    fun refresh() {
        _heads.value = repository.list()
    }
}
