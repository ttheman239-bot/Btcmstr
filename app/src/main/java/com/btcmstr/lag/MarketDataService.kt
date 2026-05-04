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
            val close = k.getString(4).toDouble()
            val volume = k.getString(5).toDouble()
            out.add(LagFormula.Bar(openTime, close, volume))
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
            val vol = if (volumes != null && !volumes.isNull(i)) volumes.getDouble(i) else 0.0
            bars.add(LagFormula.Bar(tsMs, close, vol))
        }
        return if (bars.size > limit) bars.subList(bars.size - limit, bars.size) else bars
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
