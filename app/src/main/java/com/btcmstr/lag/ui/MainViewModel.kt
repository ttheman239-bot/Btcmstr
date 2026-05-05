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
                    // 5-day window of 5m bars → ~390 RTH bars typically.
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
                val lag = LagFormula.detectLag(btc, mstr, maxLagBars, intervalSec)
                val alpha = LagFormula.alphaFromCurve(lag.curve)

                val s = _state.value
                val mNavSeries = LagFormula.mNavSeries(btc, mstr, s.sharesOutstanding, s.btcHeld)
                val mNavMedian = LagFormula.median(mNavSeries)

                val latestBtc = btc.last()
                val latestMstr = mstr.last()
                val mNavNow = LagFormula.mNav(
                    latestMstr.close, latestBtc.close, s.sharesOutstanding, s.btcHeld
                )

                // Each volume normalised by its OWN recent mean — the geometric
                // trick happens to preserve products but separate ratios are
                // easier to reason about.
                val tail = btc.size.coerceAtMost(60)
                val btcAvgVol = btc.takeLast(tail).map { it.volume }.average().coerceAtLeast(1.0)
                val mstrAvgVol = mstr.takeLast(tail).map { it.volume }.average().coerceAtLeast(1.0)
                val laggedIdx = (btc.size - 1 - lag.optimalLagBars).coerceIn(0, btc.size - 1)
                val laggedBtcVolNorm = btc[laggedIdx].volume / btcAvgVol
                val mstrVolNorm = latestMstr.volume / mstrAvgVol

                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                cal.timeInMillis = latestMstr.timestampMs
                val utcHour = cal.get(Calendar.HOUR_OF_DAY)

                val phi = LagFormula.phi(
                    lagResult = lag,
                    latestBtcVolumeAtLag = laggedBtcVolNorm,
                    latestMstrVolume = mstrVolNorm,
                    avgVolume = 1.0,
                    latestUtcHour = utcHour,
                    pMstr = latestMstr.close,
                    pBtc = latestBtc.close,
                    sharesOutstanding = s.sharesOutstanding,
                    btcHeld = s.btcHeld,
                    mNavMedian = mNavMedian,
                    alphaCalibration = alpha,
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
                    mNavMedian = mNavMedian,
                    mNavCurrent = mNavNow,
                    alphaCalibration = alpha,
                    rawRho = lag.rhoAtOptimalLag,
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
