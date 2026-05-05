package com.btcmstr.lag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.btcmstr.lag.LagFormula
import com.btcmstr.lag.MarketDataService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

data class DashboardState(
    val loading: Boolean = false,
    val lastUpdateMs: Long = 0L,
    val errorMessage: String? = null,
    val btcPrice: Double = 0.0,
    val mstrPrice: Double = 0.0,
    val intervalSec: Int = 300,
    val maxLagBars: Int = 24,
    val barsUsed: Int = 0,
    val lag: LagFormula.LagResult? = null,
    val phi: LagFormula.PhiResult? = null,
    val mNavMedian: Double = 0.0,
    val mNavCurrent: Double = 0.0,
    val alphaCalibration: Double = 1.0,
    val rawRho: Double = 0.0,
    val sharesOutstanding: Double = 348_300_000.0,
    val btcHeld: Double = 818_334.0,
)

class MainViewModel : ViewModel() {

    private val service = MarketDataService()
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling(intervalSec: Int = 300, maxLagBars: Int = 24, refreshSec: Long = 60) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                refreshOnce(intervalSec, maxLagBars)
                delay(refreshSec * 1000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshOnce(
        intervalSec: Int = _state.value.intervalSec,
        maxLagBars: Int = _state.value.maxLagBars,
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            try {
                val (btc, mstr) = withContext(Dispatchers.IO) {
                    service.fetchHistoricalAligned(intervalSec = intervalSec, lookbackDays = 5)
                }
                if (btc.size < maxLagBars * 3 + 30) {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = "ข้อมูลซ้อนทับไม่พอ (BTC=${btc.size}, MSTR=${mstr.size}). " +
                            "ตลาด NYSE อาจปิด — รอช่วงตลาดเปิด หรือกด refresh ใหม่"
                    )
                    return@launch
                }
                val s = _state.value
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = mstr.last().timestampMs
                val utcHour = cal.get(Calendar.HOUR_OF_DAY)
                val currentIdx = btc.size - 1
                val ctx = LagFormula.computePhiAt(
                    btc = btc,
                    mstr = mstr,
                    currentIdx = currentIdx,
                    trainStart = 0,
                    maxLagBars = maxLagBars,
                    barIntervalSec = intervalSec,
                    sharesOutstanding = s.sharesOutstanding,
                    btcHeld = s.btcHeld,
                    currentUtcHour = utcHour,
                )
                if (ctx == null) {
                    _state.value = s.copy(
                        loading = false,
                        errorMessage = "training window สั้นเกินไป (ต้องการ > ${maxLagBars * 2 + 30} bars)"
                    )
                    return@launch
                }
                _state.value = s.copy(
                    loading = false,
                    lastUpdateMs = System.currentTimeMillis(),
                    btcPrice = btc[currentIdx].close,
                    mstrPrice = mstr[currentIdx].close,
                    intervalSec = intervalSec,
                    maxLagBars = maxLagBars,
                    barsUsed = btc.size,
                    lag = ctx.lag,
                    phi = ctx.phi,
                    mNavMedian = ctx.mNavMedian,
                    mNavCurrent = ctx.mNavCurrent,
                    alphaCalibration = ctx.alpha,
                    rawRho = ctx.rawRho,
                    errorMessage = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "ดึงข้อมูลล้มเหลว: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }
}
