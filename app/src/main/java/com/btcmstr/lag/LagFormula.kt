package com.btcmstr.lag

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Implements the Φ(t,τ) signal from the BTC-MSTR Lead-Lag Detection System.
 *
 *   Φ(t,τ) = α · ρ_xy(τ) · ω(t) · (V_btc(t-τ)·V_mstr(t)) / V̄² · exp(-β|mNAV(t)-mNAV̄|) · 1[|ρ|>ε]
 *
 * Inputs are aligned bar series (BTC and MSTR sampled at identical Δt) plus metadata.
 */
object LagFormula {

    const val EPSILON = 0.0005
    const val BETA_M_NAV_PENALTY = 2.0
    const val EWMA_LAMBDA = 0.94
    const val Z_SIGNIFICANT = 2.58

    data class Bar(
        val timestampMs: Long,
        val close: Double,
        val volume: Double,
        val open: Double = close,
    )

    data class CorrelationPoint(val lagBars: Int, val rho: Double)

    data class LagResult(
        val optimalLagBars: Int,
        val optimalLagSeconds: Long,
        val rhoAtOptimalLag: Double,
        val zScore: Double,
        val isSignificant: Boolean,
        val curve: List<CorrelationPoint>,
    )

    data class PhiResult(
        val phi: Double,
        val rho: Double,
        val sessionWeight: Double,
        val volumeFactor: Double,
        val mNav: Double,
        val mNavPenalty: Double,
        val signal: Signal,
    )

    enum class Signal { STRONG_LONG, STRONG_SHORT, NO_TRADE }

    /** Log returns r(t) = ln(P(t)/P(t-1)) */
    fun logReturns(bars: List<Bar>): DoubleArray {
        if (bars.size < 2) return DoubleArray(0)
        val out = DoubleArray(bars.size - 1)
        for (i in 1 until bars.size) {
            val p0 = bars[i - 1].close
            val p1 = bars[i].close
            out[i - 1] = if (p0 > 0.0 && p1 > 0.0) ln(p1 / p0) else 0.0
        }
        return out
    }

    /** EWMA volatility σ²(t) = λ·σ²(t-1) + (1-λ)·r²(t) */
    fun ewmaVolatility(returns: DoubleArray, lambda: Double = EWMA_LAMBDA): DoubleArray {
        if (returns.isEmpty()) return DoubleArray(0)
        val sigma2 = DoubleArray(returns.size)
        var prev = returns[0] * returns[0]
        sigma2[0] = prev
        for (i in 1 until returns.size) {
            prev = lambda * prev + (1 - lambda) * returns[i] * returns[i]
            sigma2[i] = prev
        }
        return DoubleArray(returns.size) { sqrt(sigma2[it].coerceAtLeast(1e-12)) }
    }

    /** Volatility-normalized returns: r̃(t) = (r(t) - μ) / σ(t) */
    fun normalize(returns: DoubleArray, sigma: DoubleArray): DoubleArray {
        if (returns.isEmpty()) return DoubleArray(0)
        val mu = returns.average()
        return DoubleArray(returns.size) { i ->
            val s = sigma[i].coerceAtLeast(1e-9)
            (returns[i] - mu) / s
        }
    }

    /**
     * Session weight ω(t) — heavier during NYSE RTH because MSTR only meaningfully
     * reprices when its market is open.
     */
    fun sessionWeight(utcHour: Int): Double = when {
        utcHour in 14..19 -> 1.5            // 13:30–20:00 RTH (rounded to int hours)
        utcHour in 8..13 -> 1.0             // 08:00–13:30 premarket
        utcHour in 20..23 -> 0.7            // 20:00–24:00 after-hours
        else -> 0.3                          // 00:00–08:00 Asia/closed
    }

    /**
     * Weighted cross-correlation at integer-bar lag τ.
     * τ > 0 → BTC leads MSTR by τ bars.
     */
    fun weightedCrossCorr(
        rBtc: DoubleArray,
        rMstr: DoubleArray,
        weights: DoubleArray,
        lag: Int,
    ): Double {
        val n = minOf(rBtc.size, rMstr.size, weights.size)
        if (n <= abs(lag) + 2) return 0.0

        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        val start = maxOf(0, lag)
        val end = n + minOf(0, lag)
        for (t in start until end) {
            val w = weights[t]
            val x = rBtc[t - lag]
            val y = rMstr[t]
            num += w * x * y
            dx += w * x * x
            dy += w * y * y
        }
        val denom = sqrt(dx) * sqrt(dy)
        return if (denom > 1e-12) num / denom else 0.0
    }

    /** Sweep τ ∈ [-maxLag, +maxLag] and pick the |ρ|-maximizing lag. */
    fun detectLag(
        btcBars: List<Bar>,
        mstrBars: List<Bar>,
        maxLagBars: Int,
        barIntervalSec: Int,
    ): LagResult {
        require(btcBars.size == mstrBars.size) {
            "btcBars and mstrBars must be aligned (same length, same timestamps)"
        }
        val rBtcRaw = logReturns(btcBars)
        val rMstrRaw = logReturns(mstrBars)
        val sigBtc = ewmaVolatility(rBtcRaw)
        val sigMstr = ewmaVolatility(rMstrRaw)
        val rBtc = normalize(rBtcRaw, sigBtc)
        val rMstr = normalize(rMstrRaw, sigMstr)
        val weights = DoubleArray(rBtc.size) { i ->
            val ts = btcBars[i + 1].timestampMs
            val hour = ((ts / 3_600_000L) % 24L).toInt()
            sessionWeight(hour)
        }

        val curve = ArrayList<CorrelationPoint>(2 * maxLagBars + 1)
        var bestLag = 0
        var bestAbs = -1.0
        var bestRho = 0.0
        for (lag in -maxLagBars..maxLagBars) {
            val rho = weightedCrossCorr(rBtc, rMstr, weights, lag)
            curve.add(CorrelationPoint(lag, rho))
            val a = abs(rho)
            if (a > bestAbs) {
                bestAbs = a
                bestLag = lag
                bestRho = rho
            }
        }

        // Bartlett approximation: SE ≈ 1/√n for white noise; conservative.
        val n = rBtc.size.coerceAtLeast(2)
        val se = 1.0 / sqrt(n.toDouble())
        val z = if (se > 0) bestRho / se else 0.0

        return LagResult(
            optimalLagBars = bestLag,
            optimalLagSeconds = bestLag.toLong() * barIntervalSec,
            rhoAtOptimalLag = bestRho,
            zScore = z,
            isSignificant = abs(z) > Z_SIGNIFICANT,
            curve = curve,
        )
    }

    /** mNAV(t) = (P_mstr · S_out) / (N_btc · P_btc) */
    fun mNav(pMstr: Double, pBtc: Double, sharesOutstanding: Double, btcHeld: Double): Double {
        val denom = btcHeld * pBtc
        return if (denom > 0.0) (pMstr * sharesOutstanding) / denom else 0.0
    }

    /**
     * Full Φ(t,τ) signal at the most recent bar.
     *
     * @param alphaCalibration α — typically 1 / mean(|ρ|) from history; 1.0 is fine for live signal sign.
     * @param mNavMedian rolling median of mNAV (e.g., 30-day).
     * @param avgVolume V̄ — average normalized volume across both legs.
     */
    fun phi(
        lagResult: LagResult,
        latestBtcVolumeAtLag: Double,
        latestMstrVolume: Double,
        avgVolume: Double,
        latestUtcHour: Int,
        pMstr: Double,
        pBtc: Double,
        sharesOutstanding: Double,
        btcHeld: Double,
        mNavMedian: Double,
        alphaCalibration: Double = 1.0,
    ): PhiResult {
        val rho = lagResult.rhoAtOptimalLag
        val omega = sessionWeight(latestUtcHour)
        val vNorm = if (avgVolume > 0.0) {
            (latestBtcVolumeAtLag * latestMstrVolume) / (avgVolume * avgVolume)
        } else 0.0
        val nav = mNav(pMstr, pBtc, sharesOutstanding, btcHeld)
        val penalty = exp(-BETA_M_NAV_PENALTY * abs(nav - mNavMedian))
        val gate = if (abs(rho) > EPSILON) 1.0 else 0.0

        val phi = alphaCalibration * rho * omega * vNorm * penalty * gate
        val signal = when {
            phi > 0.7 -> Signal.STRONG_LONG
            phi < -0.7 -> Signal.STRONG_SHORT
            else -> Signal.NO_TRADE
        }
        return PhiResult(
            phi = phi,
            rho = rho,
            sessionWeight = omega,
            volumeFactor = vNorm,
            mNav = nav,
            mNavPenalty = penalty,
            signal = signal,
        )
    }
}
