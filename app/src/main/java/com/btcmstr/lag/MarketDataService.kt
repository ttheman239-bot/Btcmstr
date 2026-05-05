package com.btcmstr.lag

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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

    data class HistoricalFetch(
        val btc: List<LagFormula.Bar>,
        val mstr: List<LagFormula.Bar>,
        val btcSource: String,
        val sourcesTried: List<String>,
    )

    /**
     * Pages Binance klines back in time. Returns empty on geo-block / parse fail
     * so the fallback chain can try the next source.
     */
    fun fetchBtcKlinesRangeBinance(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<LagFormula.Bar> {
        return try {
            val interval = when (intervalSec) {
                60 -> "1m"; 300 -> "5m"; 900 -> "15m"; 3600 -> "1h"; else -> "5m"
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
                if (body.isBlank() || body.trimStart().startsWith("{")) break
                val arr = try { JSONArray(body) } catch (_: Exception) { break }
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
            all
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Kraken public OHLC. Free, no key, generally not geo-blocked in Asia.
     * Pages with `since` parameter; each call returns up to ~720 bars.
     */
    fun fetchBtcKlinesRangeKraken(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<LagFormula.Bar> {
        val intervalMin = intervalSec / 60
        if (intervalMin !in listOf(1, 5, 15, 30, 60, 240, 1440)) return emptyList()
        return try {
            val all = ArrayList<LagFormula.Bar>()
            val seen = HashSet<Long>()
            var sinceSec = startTimeMs / 1000L
            val endSec = endTimeMs / 1000L
            var hops = 0
            while (sinceSec < endSec && hops < 40) {
                val url = "https://api.kraken.com/0/public/OHLC" +
                    "?pair=XBTUSDT&interval=$intervalMin&since=$sinceSec"
                val body = httpGet(url) ?: break
                val obj = try { JSONObject(body) } catch (_: Exception) { break }
                val errors = obj.optJSONArray("error")
                if (errors != null && errors.length() > 0) break
                val result = obj.optJSONObject("result") ?: break
                val key = result.keys().asSequence().firstOrNull { it != "last" } ?: break
                val arr = result.optJSONArray(key) ?: break
                if (arr.length() == 0) break
                var maxTs = sinceSec
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    val tsSec = k.getLong(0)
                    if (tsSec * 1000L > endTimeMs) continue
                    if (!seen.add(tsSec)) continue
                    val open = k.getString(1).toDouble()
                    val close = k.getString(4).toDouble()
                    val volume = k.getString(6).toDouble()
                    all.add(LagFormula.Bar(tsSec * 1000L, close, volume, open))
                    if (tsSec > maxTs) maxTs = tsSec
                }
                if (maxTs <= sinceSec) break
                sinceSec = maxTs + intervalSec
                hops++
            }
            all.sortBy { it.timestampMs }
            all
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Coinbase Exchange public candles. Free, no key.
     * Granularity must be one of {60, 300, 900, 3600, 21600, 86400}.
     */
    fun fetchBtcKlinesRangeCoinbase(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<LagFormula.Bar> {
        if (intervalSec !in listOf(60, 300, 900, 3600, 21600, 86400)) return emptyList()
        return try {
            val all = ArrayList<LagFormula.Bar>()
            val seen = HashSet<Long>()
            val windowMs = intervalSec * 1000L * 290L
            var cursor = startTimeMs
            var hops = 0
            while (cursor < endTimeMs && hops < 80) {
                val end = minOf(cursor + windowMs, endTimeMs)
                val url = "https://api.exchange.coinbase.com/products/BTC-USD/candles" +
                    "?granularity=$intervalSec&start=${isoUtc(cursor)}&end=${isoUtc(end)}"
                val body = httpGet(url)
                if (body == null) {
                    cursor = end; hops++; continue
                }
                val arr = try { JSONArray(body) } catch (_: Exception) {
                    cursor = end; hops++; continue
                }
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONArray(i)
                    val tsSec = k.getLong(0)
                    if (!seen.add(tsSec)) continue
                    val open = k.getDouble(3)
                    val close = k.getDouble(4)
                    val volume = k.getDouble(5)
                    all.add(LagFormula.Bar(tsSec * 1000L, close, volume, open))
                }
                cursor = end
                hops++
            }
            all.sortBy { it.timestampMs }
            all
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Public wrapper used by older callers — defaults to Binance. */
    fun fetchBtcKlinesRange(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<LagFormula.Bar> = fetchBtcKlinesRangeBinance(intervalSec, startTimeMs, endTimeMs)

    /**
     * Tries Binance → Kraken → Coinbase in order, returns the first source
     * whose payload covers at least [minBars] bars together with its name.
     */
    private fun tryBtcSources(
        intervalSec: Int,
        startTimeMs: Long,
        endTimeMs: Long,
        minBars: Int = 50,
    ): Pair<List<LagFormula.Bar>, String> {
        val attempts = listOf<Pair<String, () -> List<LagFormula.Bar>>>(
            "Binance" to { fetchBtcKlinesRangeBinance(intervalSec, startTimeMs, endTimeMs) },
            "Kraken" to { fetchBtcKlinesRangeKraken(intervalSec, startTimeMs, endTimeMs) },
            "Coinbase" to { fetchBtcKlinesRangeCoinbase(intervalSec, startTimeMs, endTimeMs) },
        )
        var best: Pair<List<LagFormula.Bar>, String> = emptyList<LagFormula.Bar>() to "(none)"
        for ((name, fetch) in attempts) {
            val bars = fetch()
            if (bars.size >= minBars) return bars to name
            if (bars.size > best.first.size) best = bars to name
        }
        return best
    }

    /**
     * Pulls a long aligned history for backtesting. Falls back across BTC
     * sources if the primary is geo-blocked or empty.
     */
    fun fetchHistoricalAlignedMulti(
        intervalSec: Int = 300,
        lookbackDays: Int = 30,
    ): HistoricalFetch {
        val days = lookbackDays.coerceIn(2, 60)
        val mstr = fetchMstrBarsRange(intervalSec, days)
        if (mstr.isEmpty()) return HistoricalFetch(emptyList(), emptyList(), "(none)", listOf("Yahoo failed"))
        val firstTs = mstr.first().timestampMs
        val lastTs = mstr.last().timestampMs + intervalSec * 1000L
        val (btc, source) = tryBtcSources(intervalSec, firstTs, lastTs)
        val (alignedBtc, alignedMstr) = alignByBucket(btc, mstr, intervalSec)
        return HistoricalFetch(alignedBtc, alignedMstr, source, listOf("Binance", "Kraken", "Coinbase"))
    }

    /** Backwards-compatible wrapper that drops the source tag. */
    fun fetchHistoricalAligned(
        intervalSec: Int = 300,
        lookbackDays: Int = 30,
    ): Pair<List<LagFormula.Bar>, List<LagFormula.Bar>> {
        val r = fetchHistoricalAlignedMulti(intervalSec, lookbackDays)
        return r.btc to r.mstr
    }

    private fun isoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
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
