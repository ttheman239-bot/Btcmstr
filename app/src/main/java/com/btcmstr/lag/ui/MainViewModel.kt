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
    val intervalSec: Int = 60,
    val maxLagBars: Int = 60,
    val barsUsed: Int = 0,
    val lag: LagFormula.LagResult? = null,
    val phi: LagFormula.PhiResult? = null,
    val mNavMedian: Double = 1.85, // rough rolling median; user can override later
    val sharesOutstanding: Double = 348_300_000.0,
    val btcHeld: Double = 818_334.0,
)

class MainViewModel : ViewModel() {

    private val service = MarketDataService()
    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling(intervalSec: Int = 60, maxLagBars: Int = 60, refreshSec: Long = 60) {
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

    fun refreshOnce(intervalSec: Int = _state.value.intervalSec, maxLagBars: Int = _state.value.maxLagBars) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, errorMessage = null)
            try {
                val limit = (maxLagBars * 6).coerceAtLeast(240).coerceAtMost(720)
                val (btc, mstr) = withContext(Dispatchers.IO) {
                    service.fetchAligned(intervalSec, limit)
                }
                if (btc.size < 30 || mstr.size < 30) {
                    _state.value = _state.value.copy(
                        loading = false,
                        errorMessage = "Not enough overlap (BTC=${btc.size}, MSTR=${mstr.size}). " +
                            "Wait for NYSE session to accumulate bars."
                    )
                    return@launch
                }
                val lag = LagFormula.detectLag(btc, mstr, maxLagBars, intervalSec)

                val avgVol = run {
                    val bv = btc.takeLast(60).map { it.volume }.average().coerceAtLeast(1.0)
                    val mv = mstr.takeLast(60).map { it.volume }.average().coerceAtLeast(1.0)
                    sharedAvg(bv, mv)
                }
                val latestBtc = btc.last()
                val latestMstr = mstr.last()
                val laggedBtcVol = run {
                    val idx = (btc.size - 1 - lag.optimalLagBars).coerceIn(0, btc.size - 1)
                    btc[idx].volume
                }
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = latestMstr.timestampMs
                val utcHour = cal.get(Calendar.HOUR_OF_DAY)
                val s = _state.value
                val phi = LagFormula.phi(
                    lagResult = lag,
                    latestBtcVolumeAtLag = laggedBtcVol / avgVol,
                    latestMstrVolume = latestMstr.volume / avgVol,
                    avgVolume = 1.0,
                    latestUtcHour = utcHour,
                    pMstr = latestMstr.close,
                    pBtc = latestBtc.close,
                    sharesOutstanding = s.sharesOutstanding,
                    btcHeld = s.btcHeld,
                    mNavMedian = s.mNavMedian,
                    alphaCalibration = 1.0,
                )
                _state.value = s.copy(
                    loading = false,
                    lastUpdateMs = System.currentTimeMillis(),
                    btcPrice = latestBtc.close,
                    mstrPrice = latestMstr.close,
                    intervalSec = intervalSec,
                    maxLagBars = maxLagBars,
                    barsUsed = btc.size,
                    lag = lag,
                    phi = phi,
                    errorMessage = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    errorMessage = "Fetch failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    private fun sharedAvg(a: Double, b: Double): Double {
        // geometric mean keeps the volume normalization symmetric
        return Math.sqrt(a * b)
    }
}
