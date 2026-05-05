package com.btcmstr.lag

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import java.util.Calendar
import java.util.TimeZone

/**
 * Walk-forward backtester for the Φ(t,τ) signal.
 *
 * The signal generation is delegated to [LagFormula.computePhiAt] — the same
 * function the Live tab calls — so by construction the backtest reproduces
 * exactly what a trader watching Live would have seen at every historical bar.
 *
 *   • Lag fit, α calibration, mNAV median, and volume averages are all computed
 *     on the rolling window `[t - trainBars, t)`. No look-ahead.
 *   • Entry rule: open when `phi.signal == STRONG_LONG / STRONG_SHORT`
 *     (i.e., |Φ| > LagFormula.STRONG_THRESHOLD = 0.7) — identical to Live.
 *   • Exit rule: close when the live signal would change — the new signal is
 *     either NO_TRADE (Φ falls below threshold) or the opposite STRONG. The
 *     two safety nets are: max-hold-bars cap, and forced flat at NYSE close
 *     unless `allowOvernight = true`.
 *   • Execution: trades fill at the *next* bar's open and round-trip cost is
 *     deducted on the closed trade.
 */
class Backtester(
    private val params: BacktestParams = BacktestParams(),
) {

    data class BacktestParams(
        val trainBars: Int = 240,
        val maxLagBars: Int = 24,
        val barIntervalSec: Int = 300,
        val maxHoldBars: Int = 12,
        val costBpsPerSide: Double = 5.0,
        val allowOvernight: Boolean = false,
        val sharesOutstanding: Double = 348_300_000.0,
        val btcHeld: Double = 818_334.0,
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

    enum class ExitReason { SIGNAL_FLIP, MAX_HOLD, SESSION_END, EOD }

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
                warning = "ต้องการอย่างน้อย ${params.trainBars + 50} bars แต่มีแค่ $n",
            )
        }

        val trades = ArrayList<Trade>()
        val equity = ArrayList<EquityPoint>(n)
        var cash = 1.0
        var openTrade: OpenTrade? = null

        for (t in params.trainBars until n - 1) {
            val utcHour = utcHour(mstr[t].timestampMs)
            val ctx = LagFormula.computePhiAt(
                btc = btc,
                mstr = mstr,
                currentIdx = t,
                trainStart = t - params.trainBars,
                maxLagBars = params.maxLagBars,
                barIntervalSec = params.barIntervalSec,
                sharesOutstanding = params.sharesOutstanding,
                btcHeld = params.btcHeld,
                currentUtcHour = utcHour,
            ) ?: continue

            val signal = ctx.phi.signal
            val tNext = t + 1

            // Exit logic — react on next bar's open.
            val curOpen = openTrade
            if (curOpen != null) {
                val held = tNext - curOpen.entryIdx
                val sessionEnd = !params.allowOvernight && isLastSessionBarUtc(mstr, tNext)
                val flipped = signal != LagFormula.Signal.NO_TRADE &&
                    signal.dirSign() != curOpen.direction
                val faded = signal == LagFormula.Signal.NO_TRADE
                val exhausted = held >= params.maxHoldBars
                val dataEnd = tNext == n - 1
                val shouldClose = exhausted || sessionEnd || flipped || faded || dataEnd
                if (shouldClose) {
                    val exitPrice = mstr[tNext].open
                    val gross = ln(exitPrice / curOpen.entryPrice) * curOpen.direction
                    val cost = 2.0 * params.costBpsPerSide / 10_000.0
                    val net = gross - cost
                    cash *= exp(net)
                    val reason = when {
                        flipped || faded -> ExitReason.SIGNAL_FLIP
                        exhausted -> ExitReason.MAX_HOLD
                        sessionEnd -> ExitReason.SESSION_END
                        else -> ExitReason.EOD
                    }
                    trades.add(
                        Trade(
                            entryTimeMs = curOpen.entryTimeMs,
                            exitTimeMs = mstr[tNext].timestampMs,
                            direction = curOpen.direction,
                            entryPrice = curOpen.entryPrice,
                            exitPrice = exitPrice,
                            phiAtEntry = curOpen.phiAtEntry,
                            rhoAtEntry = curOpen.rhoAtEntry,
                            lagSecondsAtEntry = curOpen.lagSecondsAtEntry,
                            grossPnlPct = (exp(gross) - 1.0) * 100.0,
                            netPnlPct = (exp(net) - 1.0) * 100.0,
                            barsHeld = held,
                            exitReason = reason,
                        )
                    )
                    openTrade = null
                }
            }

            // Entry logic — exact same condition as Live's banner.
            if (openTrade == null && tNext < n - 1 &&
                signal != LagFormula.Signal.NO_TRADE
            ) {
                openTrade = OpenTrade(
                    entryIdx = tNext,
                    entryTimeMs = mstr[tNext].timestampMs,
                    entryPrice = mstr[tNext].open,
                    direction = signal.dirSign(),
                    phiAtEntry = ctx.phi.phi,
                    rhoAtEntry = ctx.phi.rho,
                    lagSecondsAtEntry = ctx.lag.optimalLagSeconds,
                )
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

    private fun LagFormula.Signal.dirSign(): Int = when (this) {
        LagFormula.Signal.STRONG_LONG -> 1
        LagFormula.Signal.STRONG_SHORT -> -1
        LagFormula.Signal.NO_TRADE -> 0
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
