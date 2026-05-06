package com.btcmstr.lag.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.btcmstr.lag.PaperTrader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private val LongColor = Color(0xFF1B873F)
private val ShortColor = Color(0xFFB3261E)

@Composable
fun PaperScreen(viewModel: PaperViewModel) {
    val portfolio by viewModel.state.collectAsState()
    val enabled by viewModel.enabled.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Live Paper Trading",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "ระบบจะเปิด/ปิดออเดอร์จำลองให้อัตโนมัติเมื่อ Φ ผ่าน threshold ใน background — " +
                "ไม่ต้องเปิดแอป ไม่มีเงินจริง ใช้เทียบกับ Backtest ได้",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        WorkerStatusCard(enabled, viewModel)
        SummaryCard(portfolio)
        portfolio.open?.let { OpenPositionCard(it, portfolio.startEquity * portfolio.equity) }
        TradeHistoryCard(portfolio.history)
        ResetButton(portfolio.history.size, portfolio.open != null) { viewModel.reset() }
    }
}

@Composable
private fun WorkerStatusCard(enabled: Boolean, vm: PaperViewModel) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) vm.enableWorker()
    }
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Background worker", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (enabled) "✓ ทำงานอยู่ — ตรวจ Φ ทุก 15 นาที"
                        else "ปิดอยู่ — เปิดเพื่อให้ระบบเทรดอัตโนมัติ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = enabled && hasPermission,
                    onCheckedChange = { wantOn ->
                        if (wantOn) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission) {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                vm.enableWorker()
                            }
                        } else {
                            vm.disableWorker()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(p: PaperTrader.Portfolio) {
    val totalRetPct = (p.equity - 1.0) * 100.0
    val curEquity = p.startEquity * p.equity
    val color = if (totalRetPct >= 0) LongColor else ShortColor
    val sinceText = if (p.startTimeMs == 0L) "—" else fmtDate(p.startTimeMs)
    val daysRunning = if (p.startTimeMs > 0) {
        ((System.currentTimeMillis() - p.startTimeMs) / 86_400_000.0).coerceAtLeast(0.0)
    } else 0.0
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("พอร์ตจำลอง", fontWeight = FontWeight.SemiBold)
            Text(
                "เริ่มต้น $%,.2f → ตอนนี้ $%,.2f".format(p.startEquity, curEquity),
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            )
            Text(
                signed(totalRetPct, 2) + "%",
                color = color, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp,
            )
            Text(
                "เริ่มเมื่อ $sinceText (รัน %.1f วัน)".format(daysRunning),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (p.lastTickMs > 0) {
                Text(
                    "ตรวจล่าสุด: ${fmtDateTime(p.lastTickMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (p.history.isNotEmpty()) {
                val wins = p.history.count { it.netPnlPct > 0 }
                val winRate = wins.toDouble() / p.history.size * 100.0
                Text(
                    "Trade: ${p.history.size}  Win: $wins (%.1f%%)".format(winRate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@Composable
private fun OpenPositionCard(open: PaperTrader.Position, currentEquity: Double) {
    val sideColor = if (open.direction > 0) LongColor else ShortColor
    val sideLabel = if (open.direction > 0) "LONG" else "SHORT"
    val heldMin = open.barsHeld * 5
    Surface(
        color = sideColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 2.dp,
                color = sideColor,
                shape = RoundedCornerShape(16.dp),
            ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = sideColor, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        sideLabel,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("กำลังถือ", fontWeight = FontWeight.SemiBold)
            }
            KvLine("เข้าเมื่อ", fmtDateTime(open.entryTimeMs))
            KvLine("ราคาเข้า", "$%.2f".format(open.entryPrice))
            KvLine("ถือ", "${open.barsHeld} แท่ง (${heldMin} นาที)")
            KvLine("Φ ตอนเข้า", signed(open.phiAtEntry, 3))
            KvLine("ρ ตอนเข้า", signed(open.rhoAtEntry, 3))
            KvLine("τ* ตอนเข้า", fmtLag(open.lagSecondsAtEntry))
            KvLine("BTC source", open.sourceUsed)
        }
    }
}

@Composable
private fun TradeHistoryCard(history: List<PaperTrader.Trade>) {
    if (history.isEmpty()) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("ยังไม่มี trade ที่ปิดแล้ว", fontWeight = FontWeight.SemiBold)
                Text(
                    "รอให้ Φ ผ่าน threshold ในช่วงตลาด NYSE เปิด ระบบจะเข้า/ออกให้เอง",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        return
    }
    var showAll by remember { mutableStateOf(false) }
    val display = (if (showAll) history else history.takeLast(20)).reversed()
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ประวัติ trade (ใหม่ → เก่า)", fontWeight = FontWeight.SemiBold)
                if (history.size > 20) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "ย่อ 20" else "ดูทั้งหมด ${history.size}")
                    }
                }
            }
            display.forEachIndexed { i, t ->
                TradeRow(history.size - i, t)
                if (i < display.size - 1) Divider()
            }
        }
    }
}

@Composable
private fun TradeRow(index: Int, t: PaperTrader.Trade) {
    val sideColor = if (t.direction > 0) LongColor else ShortColor
    val side = if (t.direction > 0) "LONG" else "SHORT"
    val pnlColor = if (t.netPnlPct >= 0) LongColor else ShortColor
    val priceChangePct = (t.exitPrice - t.entryPrice) / t.entryPrice * 100.0
    val reasonTh = when (t.exitReason) {
        "FLIP" -> "สัญญาณพลิก"
        "FADE" -> "สัญญาณหายลงต่ำกว่า threshold"
        "MAX_HOLD" -> "ครบเวลาถือ"
        else -> t.exitReason
    }
    Column(
        Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = sideColor, shape = RoundedCornerShape(4.dp)) {
                    Text(
                        side,
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
                signed(t.netPnlPct, 3) + "%",
                color = pnlColor,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Text("เข้า", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(fmtDateTime(t.entryTimeMs), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("$%.2f".format(t.entryPrice), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth()) {
            Text("ออก", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(fmtDateTime(t.exitTimeMs), Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("$%.2f".format(t.exitPrice), fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "ถือ ${t.barsHeld} แท่ง · ราคา ${signed(priceChangePct, 3)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Text(
                "$reasonTh · Φ=${signed(t.phiAtEntry, 2)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ResetButton(historySize: Int, hasOpen: Boolean, onReset: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { confirm = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Reset paper portfolio")
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("ล้างพอร์ตจำลอง?") },
            text = {
                Text(
                    "จะลบ $historySize trade " +
                        (if (hasOpen) "และตำแหน่งที่ถืออยู่ " else "") +
                        "และเริ่มที่ $100,000 ใหม่ — ใช้เทียบกับ Backtest ตั้งแต่จุดนี้"
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = false; onReset() }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text("ยกเลิก") }
            },
        )
    }
}

@Composable
private fun KvLine(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

private fun fmtDate(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))

private fun fmtDateTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm 'UTC'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(ts))

private fun fmtLag(seconds: Long): String {
    val s = abs(seconds)
    val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
    return when {
        s < 60 -> "${sign}${s}s"
        s < 3600 -> "${sign}${s / 60}m"
        else -> "${sign}${s / 3600}h${(s % 3600) / 60}m"
    }
}

private fun signed(v: Double, digits: Int): String {
    if (!v.isFinite()) return if (v > 0) "+∞" else "-∞"
    val sign = if (v >= 0) "+" else ""
    return sign + "%.${digits}f".format(v)
}
