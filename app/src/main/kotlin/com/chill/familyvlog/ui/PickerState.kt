package com.chill.familyvlog.ui

sealed interface PickerAction {
    data object ShowDisclosure : PickerAction

    data object LaunchVideoPicker : PickerAction
}

data class PickerState(private val disclosureConfirmed: Boolean = false) {
    fun requestVideoPicker(): PickerAction = if (disclosureConfirmed) {
        PickerAction.LaunchVideoPicker
    } else {
        PickerAction.ShowDisclosure
    }

    fun confirmDisclosure(): PickerState = copy(disclosureConfirmed = true)
}
