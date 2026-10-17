package com.nutomic.syncthingandroid.activities

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/** The value chosen on a [SingleChoiceDialogActivity]; it starts as the intent's value. */
internal class SingleChoiceViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    /** The chosen value, starting from the intent extra [initialKey]. */
    fun selected(initialKey: String): StateFlow<String?> =
        savedState.getStateFlow(SELECTED, savedState.get<String>(initialKey))

    fun select(value: String) {
        savedState[SELECTED] = value
    }

    private companion object {
        const val SELECTED = "selected"
    }
}

/**
 * The parameters on [VersioningDialogActivity]: the intent's string extras, with the defaults of
 * their type filled in.
 */
internal class VersioningViewModel(private val savedState: SavedStateHandle) : ViewModel() {

    val params: StateFlow<Map<String, String>>

    init {
        if (!savedState.contains(PARAMS)) {
            // The intent extras are the handle's initial entries.
            val initial = HashMap<String, String>()
            for (key in savedState.keys()) {
                savedState.get<Any?>(key).let { if (it is String) initial[key] = it }
            }
            fillVersioningDefaults(initial)
            savedState[PARAMS] = initial
        }
        params = savedState.getStateFlow(PARAMS, HashMap())
    }

    fun changeType(type: String) {
        update {
            it[KEY_TYPE] = type
            fillVersioningDefaults(it)
        }
    }

    fun changeParam(key: String, value: String) {
        update { it[key] = value }
    }

    private fun update(change: (HashMap<String, String>) -> Unit) {
        savedState[PARAMS] = HashMap(params.value).also(change)
    }

    private companion object {
        const val PARAMS = "params"
    }
}
