package com.btcmstr.lag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btcmstr.lag.Backtester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun BacktestScreen(viewModel: BacktestViewModel) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Walk-forward backtest",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Refits ρ(τ) on a rolling window using only past bars, then trades " +
                "the next bar's open. Charges round-trip cost on every closed trade.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        ParametersCard(state, viewModel)
        if (state.running) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(state.progress.ifEmpty { "Running…" }, fontSize = 13.sp)
                }
            }
        }
        state.error?.let { ErrorBox(it) }
        state.result?.let { ResultBlock(it) }
    }
}

@Composable
private fun ParametersCard(state: BacktestUiState, vm: BacktestViewModel) {
    val p = state.params
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Parameters", fontWeight = FontWeight.SemiBold)
            SliderRow(
                label = "Lookback (days)",
                value = state.lookbackDays.toFloat(),
                valueRange = 5f..60f,
                steps = 54,
                display = "${state.lookbackDays}d",
                onChange = { vm.setLookbackDays(it.toInt()) }
            )
            SliderRow(
                label = "Φ threshold",
                value = p.phiThreshold.toFloat(),
                valueRange = 0.1f..1.5f,
                steps = 27,
                display = "%.2f".format(p.phiThreshold),
                onChange = { vm.setPhiThreshold(it.toDouble()) }
            )
            SliderRow(
                label = "Max hold (5m bars)",
                value = p.maxHoldBars.toFloat(),
                valueRange = 1f..48f,
                steps = 46,
                display = "${p.maxHoldBars} bars (${p.maxHoldBars * 5}m)",
                onChange = { vm.setHoldBars(it.toInt()) }
            )
            SliderRow(
                label = "Cost (bps/side)",
                value = p.costBpsPerSide.toFloat(),
                valueRange = 0f..30f,
                steps = 29,
                display = "%.1f bps".format(p.costBpsPerSide),
                onChange = { vm.setCostBps(it.toDouble()) }
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.runBacktest() },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.running) "Running…" else "Run backtest") }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(display, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        Slider(
            value = value,
            valueRange = valueRange,
            steps = steps,
            onValueChange = onChange,
        )
    }
}

@Composable
private fun ErrorBox(msg: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            msg,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ResultBlock(result: Backtester.Result) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricsCard(result.metrics)
        EquityCurveCard(result.equityCurve)
        TradeSummaryCard(result)
        TradesListCard(result.trades.takeLast(20))
    }
}

@Composable
private fun MetricsCard(m: Backtester.Metrics) {
    val days = ((m.sampleEndMs - m.sampleStartMs) / 86_400_000.0)
    val color = if (m.totalReturnPct >= 0) Color(0xFF1B873F) else Color(0xFFB3261E)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Total return ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    formatSigned(m.totalReturnPct, 2) + "%",
                    color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                "Over %.1f days · %d 5m bars · annualized %s%%".format(
                    days, ((m.sampleEndMs - m.sampleStartMs) / (m.barIntervalSec * 1000L)).toInt(),
                    formatSigned(m.annualizedReturnPct, 1)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Divider(Modifier.padding(vertical = 4.dp))
            MetricRow("Sharpe (ann.)", "%.2f".format(m.sharpe))
            MetricRow("Sortino (ann.)", "%.2f".format(m.sortino))
            MetricRow("Max drawdown", "-%.2f%%".format(m.maxDrawdownPct))
            MetricRow("Win rate", "%.1f%%".format(m.winRate * 100))
            MetricRow(
                "Profit factor",
                if (m.profitFactor.isInfinite()) "∞"
                else "%.2f".format(m.profitFactor),
            )
            MetricRow("Avg trade", formatSigned(m.avgTradePct, 3) + "%")
            MetricRow("Median trade", formatSigned(m.medianTradePct, 3) + "%")
            MetricRow("# trades", "${m.numTrades} (L=${m.numLong}, S=${m.numShort})")
            MetricRow("Avg hold", "%.1f bars".format(m.avgBarsHeld))
        }
    }
}

@Composable
private fun MetricRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
    }
}

@Composable
private fun EquityCurveCard(curve: List<Backtester.EquityPoint>) {
    if (curve.size < 2) return
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.tertiary
    val maxEq = curve.maxOf { it.equity }
    val minEq = curve.minOf { it.equity }
    val span = (maxEq - minEq).coerceAtLeast(1e-6)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Equity curve (start = 1.00)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            ) {
                val w = size.width
                val h = size.height
                val n = curve.size
                // baseline at equity = 1
                val baselineY = h - ((1.0 - minEq) / span * h * 0.9 + h * 0.05).toFloat()
                drawLine(
                    color = baseline.copy(alpha = 0.4f),
                    start = Offset(0f, baselineY),
                    end = Offset(w, baselineY),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = outline,
                    start = Offset(0f, h - 1),
                    end = Offset(w, h - 1),
                    strokeWidth = 1f,
                )
                val path = Path()
                curve.forEachIndexed { i, p ->
                    val x = i.toFloat() / (n - 1) * w
                    val y = h - (((p.equity - minEq) / span) * h * 0.9 + h * 0.05).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = onSurface, style = Stroke(width = 3f))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    fmtDate(curve.first().timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    "min=%.4f  max=%.4f".format(minEq, maxEq),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    fmtDate(curve.last().timeMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun TradeSummaryCard(result: Backtester.Result) {
    val byReason = result.trades.groupingBy { it.exitReason }.eachCount()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Exit reason breakdown", fontWeight = FontWeight.SemiBold)
            for (r in Backtester.ExitReason.entries) {
                MetricRow(r.name, (byReason[r] ?: 0).toString())
            }
        }
    }
}

@Composable
private fun TradesListCard(trades: List<Backtester.Trade>) {
    if (trades.isEmpty()) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Last ${trades.size} trades",
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            for (tr in trades) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        (if (tr.direction > 0) "L " else "S ") + fmtDate(tr.entryTimeMs),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    )
                    Text(
                        "${tr.barsHeld}b · " + formatSigned(tr.netPnlPct, 2) + "%",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (tr.netPnlPct >= 0) Color(0xFF1B873F) else Color(0xFFB3261E),
                    )
                }
            }
        }
    }
}

private fun fmtDate(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))

private fun formatSigned(v: Double, digits: Int): String {
    if (!v.isFinite()) return if (v > 0) "+∞" else "-∞"
    val sign = if (v >= 0) "+" else ""
    return sign + "%.${digits}f".format(v)
}
