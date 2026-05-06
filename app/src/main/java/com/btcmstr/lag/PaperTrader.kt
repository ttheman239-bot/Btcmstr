package com.btcmstr.lag

import kotlin.math.exp
import kotlin.math.ln

/**
 * Live paper-trading engine. Mirrors Backtester's entry/exit logic but runs
 * one bar at a time as new data arrives, instead of looping over history.
 *
 *   • Same threshold: STRONG_LONG / STRONG_SHORT from LagFormula.phi.signal.
 *   • Same execution price approximation: most-recent-closed-bar close.
 *     (The "next bar's open" used by Backtester ≈ this close in liquid markets.)
 *   • Same exit triggers: signal flip, signal fade to NO_TRADE, or max-hold cap.
 *   • Idempotent on repeated calls within the same bar — uses lastBarMs to skip.
 */
object PaperTrader {

    data class Position(
        val direction: Int,
        val entryTimeMs: Long,
        val entryBarTimeMs: Long,
        val entryPrice: Double,
        val barsHeld: Int,
        val phiAtEntry: Double,
        val rhoAtEntry: Double,
        val lagSecondsAtEntry: Long,
        val sourceUsed: String,
    )

    data class Trade(
        val entryTimeMs: Long,
        val exitTimeMs: Long,
        val entryPrice: Double,
        val exitPrice: Double,
        val direction: Int,
        val barsHeld: Int,
        val grossPnlPct: Double,
        val netPnlPct: Double,
        val phiAtEntry: Double,
        val exitReason: String,
    )

    data class Portfolio(
        val startTimeMs: Long,
        val startEquity: Double,
        val equity: Double,         // multiplier on startEquity (1.0 = unchanged)
        val open: Position?,
        val history: List<Trade>,
        val lastTickMs: Long,
        val lastBarMs: Long,
    )

    data class Params(
        val maxHoldBars: Int = 12,
        val costBpsPerSide: Double = 5.0,
        val maxHistoryTrades: Int = 500,
    )

    enum class Action { NONE, OPENED_LONG, OPENED_SHORT, CLOSED, FLIPPED_LONG, FLIPPED_SHORT }

    data class TickResult(
        val portfolio: Portfolio,
        val action: Action,
        val closedTrade: Trade?,
    )

    fun tick(
        ctx: LagFormula.PhiContext,
        mstrPriceNow: Double,
        currentBarTimeMs: Long,
        currentTimeMs: Long,
        params: Params,
        portfolio: Portfolio,
        sourceUsed: String,
    ): TickResult {
        // Skip if this bar was already processed (worker may run within the same 5m window).
        if (currentBarTimeMs <= portfolio.lastBarMs) {
            return TickResult(
                portfolio.copy(lastTickMs = currentTimeMs),
                Action.NONE,
                null,
            )
        }

        val sigDir = when (ctx.phi.signal) {
            LagFormula.Signal.STRONG_LONG -> 1
            LagFormula.Signal.STRONG_SHORT -> -1
            LagFormula.Signal.NO_TRADE -> 0
        }

        var equity = portfolio.equity
        var open = portfolio.open?.let { it.copy(barsHeld = it.barsHeld + 1) }
        var action = Action.NONE
        var closedTrade: Trade? = null
        var history = portfolio.history

        // (1) Exit logic
        if (open != null) {
            val exhausted = open.barsHeld >= params.maxHoldBars
            val flipped = sigDir != 0 && sigDir != open.direction
            val faded = sigDir == 0
            if (exhausted || flipped || faded) {
                val gross = ln(mstrPriceNow / open.entryPrice) * open.direction
                val cost = 2.0 * params.costBpsPerSide / 10_000.0
                val net = gross - cost
                equity *= exp(net)
                val reason = when {
                    flipped -> "FLIP"
                    faded -> "FADE"
                    else -> "MAX_HOLD"
                }
                val trade = Trade(
                    entryTimeMs = open.entryTimeMs,
                    exitTimeMs = currentBarTimeMs,
                    entryPrice = open.entryPrice,
                    exitPrice = mstrPriceNow,
                    direction = open.direction,
                    barsHeld = open.barsHeld,
                    grossPnlPct = (exp(gross) - 1.0) * 100.0,
                    netPnlPct = (exp(net) - 1.0) * 100.0,
                    phiAtEntry = open.phiAtEntry,
                    exitReason = reason,
                )
                history = (history + trade).takeLast(params.maxHistoryTrades)
                closedTrade = trade
                open = null
                action = Action.CLOSED
            }
        }

        // (2) Entry logic — runs after exit so a flip both closes and reopens
        if (open == null && sigDir != 0) {
            open = Position(
                direction = sigDir,
                entryTimeMs = currentBarTimeMs,
                entryBarTimeMs = currentBarTimeMs,
                entryPrice = mstrPriceNow,
                barsHeld = 0,
                phiAtEntry = ctx.phi.phi,
                rhoAtEntry = ctx.rawRho,
                lagSecondsAtEntry = ctx.lag.optimalLagSeconds,
                sourceUsed = sourceUsed,
            )
            action = if (closedTrade != null) {
                if (sigDir > 0) Action.FLIPPED_LONG else Action.FLIPPED_SHORT
            } else {
                if (sigDir > 0) Action.OPENED_LONG else Action.OPENED_SHORT
            }
        }

        return TickResult(
            portfolio = portfolio.copy(
                equity = equity,
                open = open,
                history = history,
                lastTickMs = currentTimeMs,
                lastBarMs = currentBarTimeMs,
            ),
            action = action,
            closedTrade = closedTrade,
        )
    }
}
