package com.btcmstr.lag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcmstr.lag.Backtester
import com.btcmstr.lag.MarketDataService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BacktestUiState(
    val running: Boolean = false,
    val progress: String = "",
    val error: String? = null,
    val params: Backtester.BacktestParams = Backtester.BacktestParams(),
    val lookbackDays: Int = 30,
    val result: Backtester.Result? = null,
)

class BacktestViewModel : ViewModel() {

    private val service = MarketDataService()
    private val _state = MutableStateFlow(BacktestUiState())
    val state: StateFlow<BacktestUiState> = _state.asStateFlow()

    fun setLookbackDays(days: Int) {
        _state.value = _state.value.copy(lookbackDays = days.coerceIn(5, 60))
    }

    fun setHoldBars(h: Int) {
        val p = _state.value.params.copy(maxHoldBars = h.coerceIn(1, 96))
        _state.value = _state.value.copy(params = p)
    }

    fun setCostBps(c: Double) {
        val p = _state.value.params.copy(costBpsPerSide = c.coerceIn(0.0, 50.0))
        _state.value = _state.value.copy(params = p)
    }

    fun runBacktest() {
        if (_state.value.running) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                running = true,
                error = null,
                progress = "ดึงข้อมูล ${_state.value.lookbackDays} วัน (5m bars)…",
            )
            try {
                val days = _state.value.lookbackDays
                val (btc, mstr) = withContext(Dispatchers.IO) {
                    service.fetchHistoricalAligned(intervalSec = 300, lookbackDays = days)
                }
                if (btc.size < 300) {
                    _state.value = _state.value.copy(
                        running = false,
                        error = "ได้ข้อมูลแค่ ${btc.size} bars — ลองช่วงเวลาอื่นหรือรอข้อมูลเพิ่ม"
                    )
                    return@launch
                }
                _state.value = _state.value.copy(
                    progress = "ได้ ${btc.size} bars · กำลัง walk-forward…"
                )
                val result = withContext(Dispatchers.Default) {
                    Backtester(_state.value.params).run(btc, mstr)
                }
                _state.value = _state.value.copy(
                    running = false,
                    progress = "",
                    result = result,
                    error = result.warning,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    error = "Backtest ล้มเหลว: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }
}
