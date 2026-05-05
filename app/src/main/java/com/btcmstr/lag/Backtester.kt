package com.btcmstr.lag

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt
import java.util.Calendar
import java.util.TimeZone

/**
 * Walk-forward backtester for the Φ(t,τ) signal.
 *
 * Correctness guarantees:
 *  • The lag fit at time t uses only bars [t - trainBars .. t-1] (no look-ahead).
 *  • Φ(t) is computed from the *current* close-bar; trades execute at the
 *    next bar's open. This emulates a trader who sees a bar print and reacts
 *    on the following bar's open auction.
 *  • Costs are charged round-trip on each closed trade.
 *  • Trades are gated to NYSE RTH (ω(t) ≥ 1.0) and are force-closed before the
 *    daily session boundary so the strategy never holds overnight gap risk
 *    unless the user explicitly enables `allowOvernight`.
 */
class Backtester(
    private val params: BacktestParams = BacktestParams(),
) {

    data class BacktestParams(
        val trainBars: Int = 240,            // window used to fit ρ(τ)
        val refitEveryBars: Int = 24,        // re-fit cadence
        val maxLagBars: Int = 24,            // sweep ±24 bars (~2h on 5m)
        val barIntervalSec: Int = 300,       // 5-minute bars
        val phiThreshold: Double = 0.5,      // |Φ| entry trigger
        val maxHoldBars: Int = 6,            // 30 min on 5m bars
        val costBpsPerSide: Double = 5.0,    // 0.05% per side ≈ retail MSTR
        val allowOvernight: Boolean = false,
        val sharesOutstanding: Double = 348_300_000.0,
        val btcHeld: Double = 818_334.0,
        val mNavMedian: Double = 1.85,
    )

    data class Trade(
        val entryTimeMs: Long,
        val exitTimeMs: Long,
        val direction: Int,
        val entryPrice: Double,
        val exitPrice: Double,
        val phiAtEntry: Double,
        val rhoAtEntry: Double,
        val lagSecondsAtEntry: Long,
        val grossPnlPct: Double,
        val netPnlPct: Double,
        val barsHeld: Int,
        val exitReason: ExitReason,
    )

    enum class ExitReason { MAX_HOLD, OPPOSITE_SIGNAL, SESSION_END, EOD }

    data class EquityPoint(val timeMs: Long, val equity: Double)

    data class Metrics(
        val totalReturnPct: Double,
        val annualizedReturnPct: Double,
        val sharpe: Double,
        val sortino: Double,
        val maxDrawdownPct: Double,
        val winRate: Double,
        val profitFactor: Double,
        val avgTradePct: Double,
        val medianTradePct: Double,
        val numTrades: Int,
        val numLong: Int,
        val numShort: Int,
        val avgBarsHeld: Double,
        val sampleStartMs: Long,
        val sampleEndMs: Long,
        val barIntervalSec: Int,
    )

    data class Result(
        val params: BacktestParams,
        val trades: List<Trade>,
        val equityCurve: List<EquityPoint>,
        val metrics: Metrics,
        val warning: String? = null,
    )

    private data class OpenTrade(
        val entryIdx: Int,
        val entryTimeMs: Long,
        val entryPrice: Double,
        val direction: Int,
        val phiAtEntry: Double,
        val rhoAtEntry: Double,
        val lagSecondsAtEntry: Long,
    )

    fun run(btc: List<LagFormula.Bar>, mstr: List<LagFormula.Bar>): Result {
        require(btc.size == mstr.size) { "BTC and MSTR series must be aligned" }
        val n = btc.size
        if (n < params.trainBars + 50) {
            return Result(
                params = params,
                trades = emptyList(),
                equityCurve = listOf(EquityPoint(System.currentTimeMillis(), 1.0)),
                metrics = emptyMetrics(),
                warning = "Need at least ${params.trainBars + 50} bars; have $n",
            )
        }

        val trades = ArrayList<Trade>()
        val equity = ArrayList<EquityPoint>(n)
        var cash = 1.0
        var openTrade: OpenTrade? = null
        var currentLag: LagFormula.LagResult? = null
        var currentAlpha: Double = 1.0
        var currentMNavMedian: Double = params.mNavMedian
        var lastFitIdx = -1

        for (t in params.trainBars until n - 1) {
            // (1) Refit ρ(τ), α, and mNAV median on the lookback window — strictly historical.
            if (currentLag == null || (t - lastFitIdx) >= params.refitEveryBars) {
                val winBtc = btc.subList(t - params.trainBars, t)
                val winMstr = mstr.subList(t - params.trainBars, t)
                currentLag = LagFormula.detectLag(
                    winBtc, winMstr,
                    maxLagBars = params.maxLagBars,
                    barIntervalSec = params.barIntervalSec,
                )
                currentAlpha = LagFormula.alphaFromCurve(currentLag.curve)
                val nav = LagFormula.mNavSeries(
                    winBtc, winMstr, params.sharesOutstanding, params.btcHeld
                )
                currentMNavMedian = LagFormula.median(nav)
                lastFitIdx = t
            }
            val lag = currentLag!!

            // Volume averages from the same training window — keeps everything causal.
            val winStart = t - params.trainBars
            val btcAvgVol = (winStart until t).map { btc[it].volume }.average().coerceAtLeast(1.0)
            val mstrAvgVol = (winStart until t).map { mstr[it].volume }.average().coerceAtLeast(1.0)

            // (2) Compute Φ(t) using only the latest closed bar.
            val utcHour = utcHour(mstr[t].timestampMs)
            val laggedIdx = (t - lag.optimalLagBars).coerceIn(0, n - 1)
            val phi = LagFormula.phi(
                lagResult = lag,
                latestBtcVolumeAtLag = btc[laggedIdx].volume / btcAvgVol,
                latestMstrVolume = mstr[t].volume / mstrAvgVol,
                avgVolume = 1.0,
                latestUtcHour = utcHour,
                pMstr = mstr[t].close,
                pBtc = btc[t].close,
                sharesOutstanding = params.sharesOutstanding,
                btcHeld = params.btcHeld,
                mNavMedian = currentMNavMedian,
                alphaCalibration = currentAlpha,
            )

            // (3) Exit logic — we react on *next* bar open.
            val tNext = t + 1
            val held = openTrade?.let { tNext - it.entryIdx } ?: 0
            val open = openTrade
            if (open != null) {
                val sessionEnd = !params.allowOvernight && isLastSessionBarUtc(mstr, tNext)
                val opposite = sign(phi.phi) * open.direction.toDouble() < 0 &&
                    abs(phi.phi) > params.phiThreshold
                val exhausted = held >= params.maxHoldBars
                if (exhausted || sessionEnd || opposite || tNext == n - 1) {
                    val exitPrice = mstr[tNext].open
                    val gross = ln(exitPrice / open.entryPrice) * open.direction
                    val cost = 2.0 * params.costBpsPerSide / 10_000.0
                    val net = gross - cost
                    cash *= exp(net)
                    trades.add(
                        Trade(
                            entryTimeMs = open.entryTimeMs,
                            exitTimeMs = mstr[tNext].timestampMs,
                            direction = open.direction,
                            entryPrice = open.entryPrice,
                            exitPrice = exitPrice,
                            phiAtEntry = open.phiAtEntry,
                            rhoAtEntry = open.rhoAtEntry,
                            lagSecondsAtEntry = open.lagSecondsAtEntry,
                            grossPnlPct = (exp(gross) - 1.0) * 100.0,
                            netPnlPct = (exp(net) - 1.0) * 100.0,
                            barsHeld = held,
                            exitReason = when {
                                exhausted -> ExitReason.MAX_HOLD
                                opposite -> ExitReason.OPPOSITE_SIGNAL
                                sessionEnd -> ExitReason.SESSION_END
                                else -> ExitReason.EOD
                            },
                        )
                    )
                    openTrade = null
                }
            }

            // (4) Entry logic — only during NYSE RTH (ω≥1.0); enter at next-bar open.
            if (openTrade == null && tNext < n - 1) {
                val canEnter = phi.sessionWeight >= 1.0 && abs(phi.phi) > params.phiThreshold
                if (canEnter) {
                    val dir = if (phi.phi > 0) 1 else -1
                    openTrade = OpenTrade(
                        entryIdx = tNext,
                        entryTimeMs = mstr[tNext].timestampMs,
                        entryPrice = mstr[tNext].open,
                        direction = dir,
                        phiAtEntry = phi.phi,
                        rhoAtEntry = phi.rho,
                        lagSecondsAtEntry = lag.optimalLagSeconds,
                    )
                }
            }

            equity.add(EquityPoint(mstr[t].timestampMs, cash))
        }

        return Result(
            params = params,
            trades = trades,
            equityCurve = equity,
            metrics = computeMetrics(trades, equity),
        )
    }

    private fun emptyMetrics() = Metrics(
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0.0, 0L, 0L,
        params.barIntervalSec
    )

    private fun computeMetrics(trades: List<Trade>, equity: List<EquityPoint>): Metrics {
        if (trades.isEmpty() || equity.isEmpty()) return emptyMetrics()
        val finalEquity = equity.last().equity
        val totalReturnPct = (finalEquity - 1.0) * 100.0
        val durationMs = (equity.last().timeMs - equity.first().timeMs).coerceAtLeast(1L)
        val years = durationMs.toDouble() / (365.25 * 86_400_000.0)
        val cagrPct = if (years > 0.001 && finalEquity > 0)
            (Math.pow(finalEquity, 1.0 / years) - 1.0) * 100.0
        else
            0.0

        // Per-bar log returns from equity curve for Sharpe/Sortino
        val perBarLogReturns = ArrayList<Double>(equity.size)
        for (i in 1 until equity.size) {
            val r = ln(equity[i].equity / equity[i - 1].equity.coerceAtLeast(1e-9))
            if (r.isFinite()) perBarLogReturns.add(r)
        }
        val barsPerYear = (365.25 * 86_400.0 / params.barIntervalSec).coerceAtLeast(1.0)
        val sharpe = annualizedSharpe(perBarLogReturns, barsPerYear)
        val sortino = annualizedSortino(perBarLogReturns, barsPerYear)
        val maxDDPct = maxDrawdownPct(equity)

        val wins = trades.count { it.netPnlPct > 0 }
        val losses = trades.size - wins
        val grossProfit = trades.filter { it.netPnlPct > 0 }.sumOf { it.netPnlPct }
        val grossLoss = trades.filter { it.netPnlPct <= 0 }.sumOf { -it.netPnlPct }
        val pf = if (grossLoss > 1e-9) grossProfit / grossLoss else
            if (grossProfit > 0) Double.POSITIVE_INFINITY else 0.0
        val avgPnl = trades.sumOf { it.netPnlPct } / trades.size
        val medianPnl = trades.map { it.netPnlPct }.sorted().let {
            if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
        }
        val avgHold = trades.sumOf { it.barsHeld }.toDouble() / trades.size

        return Metrics(
            totalReturnPct = totalReturnPct,
            annualizedReturnPct = cagrPct,
            sharpe = sharpe,
            sortino = sortino,
            maxDrawdownPct = maxDDPct,
            winRate = wins.toDouble() / trades.size,
            profitFactor = pf,
            avgTradePct = avgPnl,
            medianTradePct = medianPnl,
            numTrades = trades.size,
            numLong = trades.count { it.direction > 0 },
            numShort = trades.count { it.direction < 0 },
            avgBarsHeld = avgHold,
            sampleStartMs = equity.first().timeMs,
            sampleEndMs = equity.last().timeMs,
            barIntervalSec = params.barIntervalSec,
        )
    }

    private fun annualizedSharpe(logRets: List<Double>, barsPerYear: Double): Double {
        if (logRets.size < 2) return 0.0
        val mu = logRets.average()
        val variance = logRets.sumOf { (it - mu) * (it - mu) } / (logRets.size - 1)
        val sd = sqrt(variance)
        return if (sd > 1e-12) mu / sd * sqrt(barsPerYear) else 0.0
    }

    private fun annualizedSortino(logRets: List<Double>, barsPerYear: Double): Double {
        if (logRets.size < 2) return 0.0
        val mu = logRets.average()
        val downside = logRets.filter { it < 0 }
        if (downside.isEmpty()) return 0.0
        val downVar = downside.sumOf { it * it } / downside.size
        val downSd = sqrt(downVar)
        return if (downSd > 1e-12) mu / downSd * sqrt(barsPerYear) else 0.0
    }

    private fun maxDrawdownPct(eq: List<EquityPoint>): Double {
        var peak = eq.first().equity
        var maxDD = 0.0
        for (p in eq) {
            if (p.equity > peak) peak = p.equity
            val dd = (peak - p.equity) / peak
            if (dd > maxDD) maxDD = dd
        }
        return maxDD * 100.0
    }

    private fun isLastSessionBarUtc(bars: List<LagFormula.Bar>, idx: Int): Boolean {
        if (idx + 1 >= bars.size) return true
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = bars[idx].timestampMs
        val day = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = bars[idx + 1].timestampMs
        return cal.get(Calendar.DAY_OF_YEAR) != day
    }

    private fun utcHour(ts: Long): Int {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = ts
        return cal.get(Calendar.HOUR_OF_DAY)
    }
}
