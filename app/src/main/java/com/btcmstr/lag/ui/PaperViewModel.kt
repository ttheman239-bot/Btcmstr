package com.btcmstr.lag.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.btcmstr.lag.PaperTradeStore
import com.btcmstr.lag.PaperTrader
import com.btcmstr.lag.SignalMonitorWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PaperViewModel(app: Application) : AndroidViewModel(app) {

    private val store = PaperTradeStore(app)
    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<PaperTrader.Portfolio> = _state.asStateFlow()

    private val _enabled = MutableStateFlow(SignalMonitorWorker.isEnabled(app))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        // poll the on-disk store so worker writes show up without manual refresh
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(15_000L)
            }
        }
    }

    fun refresh() {
        _state.value = store.load()
        _enabled.value = SignalMonitorWorker.isEnabled(getApplication())
    }

    fun reset() {
        store.reset()
        refresh()
    }

    fun enableWorker() {
        SignalMonitorWorker.enable(getApplication())
        _enabled.value = true
    }

    fun disableWorker() {
        SignalMonitorWorker.disable(getApplication())
        _enabled.value = false
    }
}
