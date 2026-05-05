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

            val newSignal = ctx.phi.signal
            val prev = prefs.getString(KEY_LAST_SIGNAL, LagFormula.Signal.NO_TRADE.name)
                ?.let { runCatching { LagFormula.Signal.valueOf(it) }.getOrDefault(LagFormula.Signal.NO_TRADE) }
                ?: LagFormula.Signal.NO_TRADE
            val barTimeMs = mstr[currentIdx].timestampMs

            if (newSignal != prev) {
                postTransitionNotification(prev, newSignal, ctx, btc[currentIdx].close, mstr[currentIdx].close, barTimeMs, fetch.btcSource)
                prefs.edit()
                    .putString(KEY_LAST_SIGNAL, newSignal.name)
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

    private fun postTransitionNotification(
        prev: LagFormula.Signal,
        next: LagFormula.Signal,
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

        when {
            prev == LagFormula.Signal.NO_TRADE && next == LagFormula.Signal.STRONG_LONG ->
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🟢 ENTRY LONG MSTR",
                    body = "เข้า LONG ที่ราคาเปิดแท่งถัดไป (~$%.2f)\n".format(mstrPrice) +
                        "Φ=$phiStr · ρ=$rhoStr · τ*=$lagStr · BTC=$%.0f ($btcSource)\n".format(btcPrice) +
                        "เวลาบาร์: $ts",
                    kind = Notifier.SignalChangeKind.ENTRY_LONG,
                )
            prev == LagFormula.Signal.NO_TRADE && next == LagFormula.Signal.STRONG_SHORT ->
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🔴 ENTRY SHORT MSTR",
                    body = "เข้า SHORT ที่ราคาเปิดแท่งถัดไป (~$%.2f)\n".format(mstrPrice) +
                        "Φ=$phiStr · ρ=$rhoStr · τ*=$lagStr · BTC=$%.0f ($btcSource)\n".format(btcPrice) +
                        "เวลาบาร์: $ts",
                    kind = Notifier.SignalChangeKind.ENTRY_SHORT,
                )
            (prev == LagFormula.Signal.STRONG_LONG || prev == LagFormula.Signal.STRONG_SHORT) &&
                next == LagFormula.Signal.NO_TRADE ->
                Notifier.postSignalChange(
                    applicationContext,
                    title = "⚪ EXIT (signal faded)",
                    body = "ปิดออเดอร์ (signal กลับเป็น NO_TRADE) ที่ราคาเปิดแท่งถัดไป (~$%.2f)\n".format(mstrPrice) +
                        "Φ=$phiStr · ρ=$rhoStr\n" +
                        "เวลาบาร์: $ts",
                    kind = Notifier.SignalChangeKind.EXIT,
                )
            (prev == LagFormula.Signal.STRONG_LONG && next == LagFormula.Signal.STRONG_SHORT) ||
                (prev == LagFormula.Signal.STRONG_SHORT && next == LagFormula.Signal.STRONG_LONG) -> {
                val newDir = if (next == LagFormula.Signal.STRONG_LONG) "LONG" else "SHORT"
                Notifier.postSignalChange(
                    applicationContext,
                    title = "🔁 FLIP → $newDir",
                    body = "สัญญาณกลับด้าน — ปิดเก่า + เปิดใหม่ฝั่ง $newDir ที่ราคาเปิดแท่งถัดไป (~$%.2f)\n".format(mstrPrice) +
                        "Φ=$phiStr · ρ=$rhoStr · τ*=$lagStr\n" +
                        "เวลาบาร์: $ts",
                    kind = Notifier.SignalChangeKind.FLIP,
                )
            }
            else -> Unit
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
