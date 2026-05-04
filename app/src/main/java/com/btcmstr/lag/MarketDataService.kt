package com.btcmstr.lag

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Pulls aligned OHLCV bars for BTC and MSTR.
 *
 * BTC: Binance public REST (no key required).
 * MSTR: Yahoo Finance v8 chart API (public, no key) for delayed quotes / minute bars.
 *
 * For production, swap the MSTR side for IBKR / Polygon. The demo path keeps
 * everything self-contained so the app runs without credentials.
 */
class MarketDataService {

    fun fetchBtcKlines(intervalSec: Int, limit: Int): List<LagFormula.Bar> {
        val interval = when (intervalSec) {
            60 -> "1m"
            300 -> "5m"
            900 -> "15m"
            3600 -> "1h"
            else -> "1m"
        }
        val url = "https://api.binance.com/api/v3/klines" +
            "?symbol=BTCUSDT&interval=$interval&limit=$limit"
        val body = httpGet(url) ?: return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<LagFormula.Bar>(arr.length())
        for (i in 0 until arr.length()) {
            val k = arr.getJSONArray(i)
            // [openTime, open, high, low, close, volume, closeTime, ...]
            val openTime = k.getLong(0)
            val open = k.getString(1).toDouble()
            val close = k.getString(4).toDouble()
            val volume = k.getString(5).toDouble()
            out.add(LagFormula.Bar(openTime, close, volume, open))
        }
        return out
    }

    fun fetchMstrBars(intervalSec: Int, limit: Int): List<LagFormula.Bar> {
        val interval = when (intervalSec) {
            60 -> "1m"
            300 -> "5m"
            900 -> "15m"
            3600 -> "60m"
            else -> "1m"
        }
        // Yahoo: range must cover `limit` bars. 1m supports up to 7d.
        val rangeDays = ((limit * intervalSec) / 86_400) + 2
        val range = when {
            rangeDays <= 1 -> "1d"
            rangeDays <= 5 -> "5d"
            rangeDays <= 7 -> "7d"
            rangeDays <= 30 -> "1mo"
            else -> "3mo"
        }
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MSTR" +
            "?interval=$interval&range=$range&includePrePost=true"
        val body = httpGet(url) ?: return emptyList()
        return parseYahooChart(body, limit)
    }

    private fun parseYahooChart(body: String, limit: Int): List<LagFormula.Bar> {
        val root = org.json.JSONObject(body)
        val chart = root.optJSONObject("chart") ?: return emptyList()
        val results = chart.optJSONArray("result") ?: return emptyList()
        if (results.length() == 0) return emptyList()
        val r0 = results.getJSONObject(0)
        val timestamps = r0.optJSONArray("timestamp") ?: return emptyList()
        val indicators = r0.optJSONObject("indicators") ?: return emptyList()
        val quoteArr = indicators.optJSONArray("quote") ?: return emptyList()
        if (quoteArr.length() == 0) return emptyList()
        val quote = quoteArr.getJSONObject(0)
        val closes = quote.optJSONArray("close") ?: return emptyList()
        val opens = quote.optJSONArray("open")
        val volumes = quote.optJSONArray("volume")

        val n = timestamps.length()
        val bars = ArrayList<LagFormula.Bar>(n)
        var lastClose = Double.NaN
        for (i in 0 until n) {
            val tsMs = timestamps.getLong(i) * 1000L
            val rawClose = if (closes.isNull(i)) Double.NaN else closes.getDouble(i)
            val close = if (rawClose.isNaN()) lastClose else rawClose
            if (close.isNaN()) continue
            lastClose = close
            val open = if (opens != null && !opens.isNull(i)) opens.getDouble(i) else close
            val vol = if (volumes != null && !volumes.isNull(i)) volumes.getDouble(i) else 0.0
            bars.add(LagFormula.Bar(tsMs, close, vol, open))
        }
        return if (bars.size > limit) bars.subList(bars.size - limit, bars.size) else bars
    }

    /**
     * Pages Binance klines back in time so we can cover ranges longer than
     * the 1000-bar single-call limit. Returns chronological order.
     */
    fun fetchBtcKlinesRange(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<LagFormula.Bar> {
        val interval = when (intervalSec) {
            60 -> "1m"
            300 -> "5m"
            900 -> "15m"
            3600 -> "1h"
            else -> "5m"
        }
        val all = ArrayList<LagFormula.Bar>()
        val seen = HashSet<Long>()
        var cursor = startTimeMs
        var hops = 0
        while (cursor < endTimeMs && hops < 30) {
            val url = "https://api.binance.com/api/v3/klines" +
                "?symbol=BTCUSDT&interval=$interval&limit=1000" +
                "&startTime=$cursor&endTime=$endTimeMs"
            val body = httpGet(url) ?: break
            val arr = JSONArray(body)
            if (arr.length() == 0) break
            var lastClose = cursor
            for (i in 0 until arr.length()) {
                val k = arr.getJSONArray(i)
                val openTime = k.getLong(0)
                if (!seen.add(openTime)) continue
                val open = k.getString(1).toDouble()
                val close = k.getString(4).toDouble()
                val volume = k.getString(5).toDouble()
                all.add(LagFormula.Bar(openTime, close, volume, open))
                lastClose = k.getLong(6)
            }
            if (lastClose <= cursor) break
            cursor = lastClose + 1
            hops++
        }
        all.sortBy { it.timestampMs }
        return all
    }

    /**
     * Pulls a long aligned history for backtesting. lookbackDays caps at
     * Yahoo's 5m maximum (60d) to keep one MSTR call simple.
     */
    fun fetchHistoricalAligned(
        intervalSec: Int = 300,
        lookbackDays: Int = 30,
    ): Pair<List<LagFormula.Bar>, List<LagFormula.Bar>> {
        val days = lookbackDays.coerceIn(2, 60)
        val mstr = fetchMstrBarsRange(intervalSec, days)
        if (mstr.isEmpty()) return Pair(emptyList(), emptyList())
        val firstTs = mstr.first().timestampMs
        val lastTs = mstr.last().timestampMs + intervalSec * 1000L
        val btc = fetchBtcKlinesRange(intervalSec, firstTs, lastTs)
        return alignByBucket(btc, mstr, intervalSec)
    }

    private fun fetchMstrBarsRange(intervalSec: Int, days: Int): List<LagFormula.Bar> {
        val interval = when (intervalSec) {
            60 -> "1m"
            300 -> "5m"
            900 -> "15m"
            3600 -> "60m"
            else -> "5m"
        }
        val range = when {
            days <= 5 -> "5d"
            days <= 7 -> "7d"
            days <= 30 -> "1mo"
            days <= 60 -> "3mo"
            else -> "3mo"
        }
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/MSTR" +
            "?interval=$interval&range=$range&includePrePost=false"
        val body = httpGet(url) ?: return emptyList()
        return parseYahooChart(body, Int.MAX_VALUE)
    }

    private fun alignByBucket(
        btc: List<LagFormula.Bar>,
        mstr: List<LagFormula.Bar>,
        intervalSec: Int,
    ): Pair<List<LagFormula.Bar>, List<LagFormula.Bar>> {
        if (btc.isEmpty() || mstr.isEmpty()) return Pair(btc, mstr)
        val bucketMs = intervalSec * 1000L
        val mstrByTs = HashMap<Long, LagFormula.Bar>(mstr.size)
        for (b in mstr) mstrByTs[b.timestampMs / bucketMs] = b
        val btcOut = ArrayList<LagFormula.Bar>()
        val mstrOut = ArrayList<LagFormula.Bar>()
        for (b in btc) {
            val m = mstrByTs[b.timestampMs / bucketMs] ?: continue
            btcOut.add(b)
            mstrOut.add(m)
        }
        return Pair(btcOut, mstrOut)
    }

    /**
     * Returns BTC and MSTR bar lists aligned by timestamp. Drops bars where
     * MSTR has no print (market closed) so the lag estimator only sees overlap.
     */
    fun fetchAligned(intervalSec: Int, limit: Int): Pair<List<LagFormula.Bar>, List<LagFormula.Bar>> {
        val btc = fetchBtcKlines(intervalSec, limit)
        val mstr = fetchMstrBars(intervalSec, limit)
        if (btc.isEmpty() || mstr.isEmpty()) return Pair(btc, mstr)

        val mstrByTs = HashMap<Long, LagFormula.Bar>(mstr.size)
        val tolerance = (intervalSec * 1000L) / 2L
        for (b in mstr) {
            val bucket = b.timestampMs / (intervalSec * 1000L)
            mstrByTs[bucket] = b
        }
        val btcOut = ArrayList<LagFormula.Bar>()
        val mstrOut = ArrayList<LagFormula.Bar>()
        for (b in btc) {
            val bucket = b.timestampMs / (intervalSec * 1000L)
            val m = mstrByTs[bucket]
            if (m != null) {
                btcOut.add(b)
                mstrOut.add(m)
            }
        }
        // tolerance unused once bucketed, kept for clarity
        @Suppress("UNUSED_VARIABLE") val _t = tolerance
        return Pair(btcOut, mstrOut)
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TimeUnit.SECONDS.toMillis(8).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(12).toInt()
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android) BtcMstrLag/1.0"
                )
                setRequestProperty("Accept", "application/json,text/plain,*/*")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}
