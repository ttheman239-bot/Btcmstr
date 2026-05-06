package com.btcmstr.lag

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Periodic worker that polls the same computePhiAt pipeline that Live uses.
 * Posts a notification whenever the signal *transitions* between
 * NO_TRADE / STRONG_LONG / STRONG_SHORT — i.e., entry, exit, and flip events.
 *
 * Uses the trainBars window (240) so its signal is byte-for-byte identical to
 * what the Backtester would have produced at the same bar boundary.
 */
class SignalMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val service = MarketDataService()
            val fetch = service.fetchHistoricalAlignedMulti(intervalSec = 300, lookbackDays = 5)
            val btc = fetch.btc
            val mstr = fetch.mstr
            if (btc.size < TRAIN_BARS + 30) {
                return Result.success()
            }
            val nowMs = System.currentTimeMillis()
            val lastClosedIdx = btc.indexOfLast { it.timestampMs + 300_000L <= nowMs - 30_000 }
            val currentIdx = if (lastClosedIdx >= 0) lastClosedIdx else btc.size - 1
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = mstr[currentIdx].timestampMs
            val utcHour = cal.get(Calendar.HOUR_OF_DAY)
            val trainStart = (currentIdx - TRAIN_BARS).coerceAtLeast(0)
            val ctx = LagFormula.computePhiAt(
                btc = btc,
                mstr = mstr,
                currentIdx = currentIdx,
                trainStart = trainStart,
                maxLagBars = 24,
                barIntervalSec = 300,
                sharesOutstanding = SHARES_OUT,
                btcHeld = BTC_HELD,
                currentUtcHour = utcHour,
            ) ?: return Result.success()

            val barTimeMs = mstr[currentIdx].timestampMs
            val mstrPrice = mstr[currentIdx].close
            val btcPrice = btc[currentIdx].close

            // (1) Run paper trader
            val store = PaperTradeStore(context)
            val portfolio = store.load()
            val tickResult = PaperTrader.tick(
                ctx = ctx,
                mstrPriceNow = mstrPrice,
                currentBarTimeMs = barTimeMs,
                currentTimeMs = nowMs,
                params = PaperTrader.Params(),
                portfolio = portfolio,
                sourceUsed = fetch.btcSource,
            )
            store.save(tickResult.portfolio)

            // (2) Notify on action
            if (tickResult.action != PaperTrader.Action.NONE) {
                postActionNotification(
                    action = tickResult.action,
                    closedTrade = tickResult.closedTrade,
                    portfolioAfter = tickResult.portfolio,
                    ctx = ctx,
                    btcPrice = btcPrice,
                    mstrPrice = mstrPrice,
                    barTimeMs = barTimeMs,
                    btcSource = fetch.btcSource,
                )
                prefs.edit()
                    .putString(KEY_LAST_SIGNAL, ctx.phi.signal.name)
                    .putLong(KEY_LAST_TRANSITION_MS, nowMs)
                    .putLong(KEY_LAST_BAR_MS, barTimeMs)
                    .apply()
            }
            prefs.edit().putLong(KEY_LAST_CHECK_MS, nowMs).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun postActionNotification(
        action: PaperTrader.Action,
        closedTrade: PaperTrader.Trade?,
        portfolioAfter: PaperTrader.Portfolio,
        ctx: LagFormula.PhiContext,
        btcPrice: Double,
        mstrPrice: Double,
        barTimeMs: Long,
        btcSource: String,
    ) {
        val ts = SimpleDateFormat("HH:mm 'UTC' (yyyy-MM-dd)", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(barTimeMs))
        val phiStr = "%+.3f".format(ctx.phi.phi)
        val rhoStr = "%+.3f".format(ctx.rawRho)
        val lagStr = formatLagSeconds(ctx.lag.optimalLagSeconds)
        val equityUsd = portfolioAfter.startEquity * portfolioAfter.equity
        val totalRet = (portfolioAfter.equity - 1.0) * 100.0
        val equityLine = "Paper: $%,.2f (%s%%)".format(equityUsd, "%+.2f".format(totalRet))

        when (action) {
            PaperTrader.Action.OPENED_LONG ->
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🟢 Paper เข้า LONG MSTR",
                    body = "เข้า LONG @ $%.2f · Φ=$phiStr · ρ=$rhoStr · τ*=$lagStr\n".format(mstrPrice) +
                        "BTC=$%.0f ($btcSource) · $ts\n".format(btcPrice) +
                        equityLine,
                    kind = Notifier.SignalChangeKind.ENTRY_LONG,
                )
            PaperTrader.Action.OPENED_SHORT ->
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🔴 Paper เข้า SHORT MSTR",
                    body = "เข้า SHORT @ $%.2f · Φ=$phiStr · ρ=$rhoStr · τ*=$lagStr\n".format(mstrPrice) +
                        "BTC=$%.0f ($btcSource) · $ts\n".format(btcPrice) +
                        equityLine,
                    kind = Notifier.SignalChangeKind.ENTRY_SHORT,
                )
            PaperTrader.Action.CLOSED -> {
                val t = closedTrade ?: return
                val side = if (t.direction > 0) "LONG" else "SHORT"
                Notifier.postSignalChange(
                    applicationContext,
                    title = "⚪ Paper ปิด $side: %s%%".format("%+.2f".format(t.netPnlPct)),
                    body = "เข้า $%.2f → ออก $%.2f (ถือ %d แท่ง)\n".format(t.entryPrice, t.exitPrice, t.barsHeld) +
                        "Net %s%% (gross %s%%, fee 0.10%%)\n".format(
                            "%+.2f".format(t.netPnlPct), "%+.2f".format(t.grossPnlPct),
                        ) +
                        "เหตุผล: ${t.exitReason} · $ts\n" +
                        equityLine,
                    kind = Notifier.SignalChangeKind.EXIT,
                )
            }
            PaperTrader.Action.FLIPPED_LONG, PaperTrader.Action.FLIPPED_SHORT -> {
                val t = closedTrade ?: return
                val newSide = if (action == PaperTrader.Action.FLIPPED_LONG) "LONG" else "SHORT"
                val oldSide = if (t.direction > 0) "LONG" else "SHORT"
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🔁 Paper FLIP $oldSide → $newSide",
                    body = "ปิด $oldSide: $%.2f → $%.2f (%s%%)\n".format(
                        t.entryPrice, t.exitPrice, "%+.2f".format(t.netPnlPct),
                    ) +
                        "เปิด $newSide ใหม่ @ $%.2f · Φ=$phiStr\n".format(mstrPrice) +
                        "$ts · $equityLine",
                    kind = Notifier.SignalChangeKind.FLIP,
                )
            }
            PaperTrader.Action.NONE -> Unit
        }
    }

    companion object {
        const val WORK_NAME = "btcmstr_signal_monitor"
        const val PREFS = "btcmstr_signal_state"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_SIGNAL = "last_signal"
        const val KEY_LAST_TRANSITION_MS = "last_transition_ms"
        const val KEY_LAST_CHECK_MS = "last_check_ms"
        const val KEY_LAST_BAR_MS = "last_bar_ms"
        const val TRAIN_BARS = 240
        const val SHARES_OUT = 348_300_000.0
        const val BTC_HELD = 818_334.0

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<SignalMonitorWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            prefs(context).edit().putBoolean(KEY_ENABLED, true).apply()
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            prefs(context).edit().putBoolean(KEY_ENABLED, false).apply()
        }

        fun isEnabled(context: Context): Boolean =
            prefs(context).getBoolean(KEY_ENABLED, false)

        fun lastCheckMs(context: Context): Long =
            prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)

        fun lastTransitionMs(context: Context): Long =
            prefs(context).getLong(KEY_LAST_TRANSITION_MS, 0L)

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun formatLagSeconds(seconds: Long): String {
            val s = kotlin.math.abs(seconds)
            val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
            return when {
                s < 60 -> "${sign}${s}s"
                s < 3600 -> "${sign}${s / 60}m"
                else -> "${sign}${s / 3600}h${(s % 3600) / 60}m"
            }
        }
    }
}
