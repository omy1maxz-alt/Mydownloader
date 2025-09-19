package com.omymaxz.download

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object MediaControlEvent {
    private val _events = MutableLiveData<String>()
    val events: LiveData<String> = _events

    fun send(command: String) {
        _events.postValue(command)
    }
}
