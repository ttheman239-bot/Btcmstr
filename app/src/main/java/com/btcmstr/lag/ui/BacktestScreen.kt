package com.btcmstr.lag.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private val LongColor = Color(0xFF1B873F)
private val ShortColor = Color(0xFFB3261E)

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
            "Backtest ย้อนหลัง",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        RulesCard()
        ParametersCard(state, viewModel)
        if (state.running) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(state.progress.ifEmpty { "กำลังรัน…" }, fontSize = 13.sp)
                }
            }
        }
        state.error?.let { ErrorBox(it) }
        state.result?.let { ResultBlock(it, state.params) }
    }
}

@Composable
private fun RulesCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("วิธีการเทรด (กฎของระบบ)", fontWeight = FontWeight.SemiBold)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "✓ Backtest นี้ใช้ฟังก์ชัน computePhiAt() ตัวเดียวกับแท็บ Live — " +
                        "สัญญาณที่เห็นใน Live ตอนนี้ คือสัญญาณเดียวกับที่ Backtest ใช้ตัดสินเข้า/ออกในอดีต",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
            BulletLine("1.", "ทุก ๆ 5 นาที ระบบคำนวณค่า Φ(t) จาก BTC ↔ MSTR ย้อนหลัง (window 240 bars = 20 ชั่วโมง)")
            BulletLine("2.", "ถ้า Φ > +0.70 (= STRONG_LONG) → เปิด LONG MSTR ที่ราคา open ของแท่งถัดไป")
            BulletLine("3.", "ถ้า Φ < −0.70 (= STRONG_SHORT) → เปิด SHORT MSTR ที่ราคา open ของแท่งถัดไป")
            BulletLine("4.", "ปิดออเดอร์เมื่อ:")
            Text(
                "      • Φ พลิกข้าม threshold (สัญญาณกลับด้าน)\n" +
                    "      • Φ ลดลงต่ำกว่า ±0.70 = NO_TRADE (สัญญาณอ่อน)\n" +
                    "      • ถือครบจำนวนแท่งสูงสุด (safety net)\n" +
                    "      • ใกล้ปิดตลาด NYSE (ไม่ถือข้ามคืน)\n" +
                    "      • ข้อมูล backtest หมด",
                style = MaterialTheme.typography.bodySmall,
            )
            BulletLine("5.", "ค่าธรรมเนียม: หักทุก trade ทั้งขาเข้า + ขาออก (default 5 bps × 2 = 0.10%)")
            BulletLine("6.", "เปิดออเดอร์ได้เฉพาะช่วงตลาด NYSE เปิดเท่านั้น (เพราะ ω(t) < 1 ตอนตลาดปิด → กด Φ ต่ำกว่า threshold)")
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "หมายเหตุ: ราคาเข้า/ออกใช้ราคา open ของ \"แท่งถัดไป\" หลังจากเห็นสัญญาณ — " +
                        "ตรงกับสถานการณ์จริงที่เทรดเดอร์เห็นแท่งปิดเสร็จแล้วค่อย submit order ที่ตลาดเปิดแท่งใหม่",
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun BulletLine(num: String, text: String) {
    Row {
        Text(num, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ParametersCard(state: BacktestUiState, vm: BacktestViewModel) {
    val p = state.params
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("พารามิเตอร์", fontWeight = FontWeight.SemiBold)
            SliderRow(
                label = "ดูย้อนหลัง (วัน)",
                value = state.lookbackDays.toFloat(),
                valueRange = 5f..60f,
                steps = 54,
                display = "${state.lookbackDays} วัน",
                onChange = { vm.setLookbackDays(it.toInt()) },
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "Threshold = ±0.70 (fixed, = LagFormula.STRONG_THRESHOLD เดียวกับ Live)",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            SliderRow(
                label = "ถือสูงสุด (แท่ง 5 นาที)",
                value = p.maxHoldBars.toFloat(),
                valueRange = 1f..48f,
                steps = 46,
                display = "${p.maxHoldBars} แท่ง (${p.maxHoldBars * 5} นาที)",
                onChange = { vm.setHoldBars(it.toInt()) },
            )
            SliderRow(
                label = "ค่าธรรมเนียม (bps ต่อขา)",
                value = p.costBpsPerSide.toFloat(),
                valueRange = 0f..30f,
                steps = 29,
                display = "%.1f bps (รวม 2 ขา = %.2f%%)".format(
                    p.costBpsPerSide, p.costBpsPerSide * 2 / 100.0
                ),
                onChange = { vm.setCostBps(it.toDouble()) },
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { vm.runBacktest() },
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.running) "กำลังรัน…" else "▶ เริ่ม Backtest") }
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
            Text(display, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Slider(value = value, valueRange = valueRange, steps = steps, onValueChange = onChange)
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
private fun ResultBlock(result: Backtester.Result, params: Backtester.BacktestParams) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryHeader(result)
        MetricsCard(result.metrics)
        EquityCurveCard(result.equityCurve)
        TradesListCard(result.trades, params)
        ExitReasonsCard(result.trades)
    }
}

@Composable
private fun SummaryHeader(result: Backtester.Result) {
    val m = result.metrics
    val startEquity = 100_000.0
    val endEquity = startEquity * (1.0 + m.totalReturnPct / 100.0)
    val color = if (m.totalReturnPct >= 0) LongColor else ShortColor
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("สรุปผลรวม", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                "เริ่มต้น $%,.0f → จบที่ $%,.2f".format(startEquity, endEquity),
                fontFamily = FontFamily.Monospace, fontSize = 14.sp,
            )
            Text(
                formatSigned(m.totalReturnPct, 2) + "%",
                color = color, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp,
            )
            Text(
                "(${formatSigned(m.annualizedReturnPct, 1)}% ต่อปี)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun MetricsCard(m: Backtester.Metrics) {
    val days = ((m.sampleEndMs - m.sampleStartMs) / 86_400_000.0)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "ตัวชี้วัด (ทดสอบ %.1f วัน)".format(days),
                fontWeight = FontWeight.SemiBold,
            )
            Divider(Modifier.padding(vertical = 4.dp))
            MetricRow("Sharpe (ann.)", "%.2f".format(m.sharpe), "ยิ่งสูงยิ่งดี > 1 ถือว่าโอเค")
            MetricRow("Sortino (ann.)", "%.2f".format(m.sortino), "Sharpe เน้นเฉพาะ downside")
            MetricRow("Max drawdown", "-%.2f%%".format(m.maxDrawdownPct), "ขาดทุนสูงสุดจากจุดสูงสุด")
            MetricRow("Win rate", "%.1f%%".format(m.winRate * 100), "% trade ที่กำไร")
            MetricRow(
                "Profit factor",
                if (m.profitFactor.isInfinite()) "∞" else "%.2f".format(m.profitFactor),
                "Σกำไร / |Σขาดทุน| > 1 = ระบบชนะ",
            )
            MetricRow("Avg trade", formatSigned(m.avgTradePct, 3) + "%", null)
            MetricRow("Median trade", formatSigned(m.medianTradePct, 3) + "%", null)
            MetricRow("# trades", "${m.numTrades}  (LONG ${m.numLong} / SHORT ${m.numShort})", null)
            MetricRow("Avg hold", "%.1f แท่ง (~%.0f นาที)".format(m.avgBarsHeld, m.avgBarsHeld * 5), null)
        }
    }
}

@Composable
private fun MetricRow(k: String, v: String, hint: String?) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, style = MaterialTheme.typography.bodyMedium)
            Text(v, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
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
            Text("กราฟ Equity (เริ่มต้น = 1.00)", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            ) {
                val w = size.width
                val h = size.height
                val n = curve.size
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
private fun TradesListCard(allTrades: List<Backtester.Trade>, params: Backtester.BacktestParams) {
    if (allTrades.isEmpty()) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ไม่มี trade เลย", fontWeight = FontWeight.SemiBold)
                Text(
                    "ลองลด Φ threshold หรือเพิ่มจำนวนวัน lookback",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }
    var showAll by remember { mutableStateOf(false) }
    val trades = if (showAll) allTrades.reversed() else allTrades.takeLast(20).reversed()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "รายการเทรด (ใหม่ → เก่า)",
                    fontWeight = FontWeight.SemiBold,
                )
                if (allTrades.size > 20) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "ย่อ 20 อัน" else "แสดงทั้งหมด ${allTrades.size}")
                    }
                }
            }
            allTrades.lastOrNull()?.let { last ->
                if (last.exitReason == Backtester.ExitReason.EOD) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            "⚠ Trade สุดท้ายถูกบังคับปิดที่ขอบข้อมูล (ในชีวิตจริงอาจยังถือต่อ)",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            trades.forEachIndexed { vIdx, tr ->
                TradeRow(
                    index = allTrades.size - vIdx,
                    trade = tr,
                    costBps = params.costBpsPerSide,
                )
                if (vIdx < trades.size - 1) Divider()
            }
        }
    }
}

@Composable
private fun TradeRow(index: Int, trade: Backtester.Trade, costBps: Double) {
    val sideColor = if (trade.direction > 0) LongColor else ShortColor
    val sideLabel = if (trade.direction > 0) "LONG" else "SHORT"
    val pnlColor = if (trade.netPnlPct >= 0) LongColor else ShortColor
    val held = trade.barsHeld
    val heldMin = held * 5
    val priceChangePct = (trade.exitPrice - trade.entryPrice) / trade.entryPrice * 100.0
    val reasonTh = when (trade.exitReason) {
        Backtester.ExitReason.MAX_HOLD -> "ครบเวลาถือ (max hold)"
        Backtester.ExitReason.SIGNAL_FLIP -> "สัญญาณเปลี่ยน (flip/fade)"
        Backtester.ExitReason.SESSION_END -> "ใกล้ปิดตลาด"
        Backtester.ExitReason.EOD -> "หมดข้อมูล backtest"
    }
    val grossPct = trade.grossPnlPct
    val costPct = costBps * 2 / 100.0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = sideColor,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text(
                        sideLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("#$index", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                formatSigned(trade.netPnlPct, 3) + "%",
                color = pnlColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            )
        }
        // Entry
        TradeLine(
            label = "เข้าซื้อ",
            time = fmtDateTime(trade.entryTimeMs),
            price = "$%.2f".format(trade.entryPrice),
        )
        // Exit
        TradeLine(
            label = "ปิดออเดอร์",
            time = fmtDateTime(trade.exitTimeMs),
            price = "$%.2f".format(trade.exitPrice),
        )
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "ถือ %d แท่ง (%d นาที)".format(held, heldMin),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "ราคาเปลี่ยน " + formatSigned(priceChangePct, 3) + "%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            "PnL ก่อนหัก: %s%% − ค่าธรรมเนียม %.2f%% = %s%%".format(
                formatSigned(grossPct, 3), costPct, formatSigned(trade.netPnlPct, 3),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            "เหตุปิด: $reasonTh · Φ ตอนเข้า = ${formatSigned(trade.phiAtEntry, 3)} · " +
                "lag τ* = ${fmtLag(trade.lagSecondsAtEntry)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TradeLine(label: String, time: String, price: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.width(80.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            time,
            modifier = Modifier.weight(1f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Text(
            price,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ExitReasonsCard(trades: List<Backtester.Trade>) {
    if (trades.isEmpty()) return
    val byReason = trades.groupingBy { it.exitReason }.eachCount()
    val pnlByReason = trades.groupBy { it.exitReason }.mapValues { (_, ts) -> ts.sumOf { it.netPnlPct } }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("สรุปเหตุผลการปิด", fontWeight = FontWeight.SemiBold)
            for (r in Backtester.ExitReason.entries) {
                val n = byReason[r] ?: 0
                val totalPnl = pnlByReason[r] ?: 0.0
                val labelTh = when (r) {
                    Backtester.ExitReason.MAX_HOLD -> "ครบเวลาถือ"
                    Backtester.ExitReason.SIGNAL_FLIP -> "สัญญาณเปลี่ยน (flip/fade)"
                    Backtester.ExitReason.SESSION_END -> "ใกล้ปิดตลาด"
                    Backtester.ExitReason.EOD -> "หมดข้อมูล"
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(labelTh, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$n ครั้ง · รวม ${formatSigned(totalPnl, 2)}%",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun fmtDate(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))

private fun fmtDateTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

private fun fmtLag(seconds: Long): String {
    val s = abs(seconds)
    val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
    return when {
        s < 60 -> "${sign}${s}s"
        s < 3600 -> "${sign}${s / 60}m"
        else -> "${sign}${s / 3600}h${(s % 3600) / 60}m"
    }
}

private fun formatSigned(v: Double, digits: Int): String {
    if (!v.isFinite()) return if (v > 0) "+∞" else "-∞"
    val sign = if (v >= 0) "+" else ""
    return sign + "%.${digits}f".format(v)
}
