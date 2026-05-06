package com.btcmstr.lag

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the paper-trading portfolio across worker runs and app restarts.
 * Uses SharedPreferences + a single JSON blob — small enough that this is
 * faster than a database for our use case (one writer, capped 500 trades).
 */
class PaperTradeStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): PaperTrader.Portfolio {
        val json = prefs.getString(KEY, null) ?: return empty()
        return try {
            parsePortfolio(JSONObject(json))
        } catch (_: Exception) {
            empty()
        }
    }

    fun save(p: PaperTrader.Portfolio) {
        prefs.edit().putString(KEY, encodePortfolio(p).toString()).apply()
    }

    fun reset(initialEquity: Double = 100_000.0) {
        prefs.edit().remove(KEY).apply()
        save(empty(initialEquity))
    }

    private fun empty(initialEquity: Double = 100_000.0) = PaperTrader.Portfolio(
        startTimeMs = System.currentTimeMillis(),
        startEquity = initialEquity,
        equity = 1.0,
        open = null,
        history = emptyList(),
        lastTickMs = 0L,
        lastBarMs = 0L,
    )

    private fun encodePortfolio(p: PaperTrader.Portfolio): JSONObject =
        JSONObject().apply {
            put("startTimeMs", p.startTimeMs)
            put("startEquity", p.startEquity)
            put("equity", p.equity)
            put("lastTickMs", p.lastTickMs)
            put("lastBarMs", p.lastBarMs)
            put("open", p.open?.let { encodePosition(it) } ?: JSONObject.NULL)
            val arr = JSONArray()
            p.history.forEach { arr.put(encodeTrade(it)) }
            put("history", arr)
        }

    private fun encodePosition(pos: PaperTrader.Position): JSONObject = JSONObject().apply {
        put("direction", pos.direction)
        put("entryTimeMs", pos.entryTimeMs)
        put("entryBarTimeMs", pos.entryBarTimeMs)
        put("entryPrice", pos.entryPrice)
        put("barsHeld", pos.barsHeld)
        put("phiAtEntry", pos.phiAtEntry)
        put("rhoAtEntry", pos.rhoAtEntry)
        put("lagSecondsAtEntry", pos.lagSecondsAtEntry)
        put("sourceUsed", pos.sourceUsed)
    }

    private fun encodeTrade(t: PaperTrader.Trade): JSONObject = JSONObject().apply {
        put("entryTimeMs", t.entryTimeMs)
        put("exitTimeMs", t.exitTimeMs)
        put("entryPrice", t.entryPrice)
        put("exitPrice", t.exitPrice)
        put("direction", t.direction)
        put("barsHeld", t.barsHeld)
        put("grossPnlPct", t.grossPnlPct)
        put("netPnlPct", t.netPnlPct)
        put("phiAtEntry", t.phiAtEntry)
        put("exitReason", t.exitReason)
    }

    private fun parsePortfolio(o: JSONObject): PaperTrader.Portfolio {
        val openVal = o.opt("open")
        val openJson = if (openVal is JSONObject) openVal else null
        val historyArr = o.optJSONArray("history") ?: JSONArray()
        val history = ArrayList<PaperTrader.Trade>(historyArr.length())
        for (i in 0 until historyArr.length()) {
            history.add(parseTrade(historyArr.getJSONObject(i)))
        }
        return PaperTrader.Portfolio(
            startTimeMs = o.optLong("startTimeMs", System.currentTimeMillis()),
            startEquity = o.optDouble("startEquity", 100_000.0),
            equity = o.optDouble("equity", 1.0),
            open = openJson?.let { parsePosition(it) },
            history = history,
            lastTickMs = o.optLong("lastTickMs", 0L),
            lastBarMs = o.optLong("lastBarMs", 0L),
        )
    }

    private fun parsePosition(o: JSONObject) = PaperTrader.Position(
        direction = o.getInt("direction"),
        entryTimeMs = o.getLong("entryTimeMs"),
        entryBarTimeMs = o.optLong("entryBarTimeMs", o.getLong("entryTimeMs")),
        entryPrice = o.getDouble("entryPrice"),
        barsHeld = o.optInt("barsHeld", 0),
        phiAtEntry = o.optDouble("phiAtEntry", 0.0),
        rhoAtEntry = o.optDouble("rhoAtEntry", 0.0),
        lagSecondsAtEntry = o.optLong("lagSecondsAtEntry", 0L),
        sourceUsed = o.optString("sourceUsed", "?"),
    )

    private fun parseTrade(o: JSONObject) = PaperTrader.Trade(
        entryTimeMs = o.getLong("entryTimeMs"),
        exitTimeMs = o.getLong("exitTimeMs"),
        entryPrice = o.getDouble("entryPrice"),
        exitPrice = o.getDouble("exitPrice"),
        direction = o.getInt("direction"),
        barsHeld = o.getInt("barsHeld"),
        grossPnlPct = o.getDouble("grossPnlPct"),
        netPnlPct = o.getDouble("netPnlPct"),
        phiAtEntry = o.optDouble("phiAtEntry", 0.0),
        exitReason = o.optString("exitReason", "?"),
    )

    companion object {
        const val PREFS = "btcmstr_paper"
        const val KEY = "portfolio"
    }
}
