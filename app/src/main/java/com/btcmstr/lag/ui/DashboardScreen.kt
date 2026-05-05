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
import com.btcmstr.lag.LagFormula
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startPolling(intervalSec = 300, maxLagBars = 24, refreshSec = 60)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BTC × MSTR Lag", fontWeight = FontWeight.Bold)
                        Text(
                            "Φ(t,τ) Lead-Lag Detector",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshOnce() }) {
                        Text("⟳", fontSize = 22.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading && state.lag == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.errorMessage?.let { ErrorBanner(it) }
            PriceRow(state)
            SignalCard(state)
            ComponentsCard(state)
            LagCard(state)
            CorrelationCurve(state.lag)
            FormulaCard(state)
            FooterText(state)
        }
    }
}

@Composable
private fun ErrorBanner(msg: String) {
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
private fun PriceRow(state: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PriceBlock("BTC/USD", state.btcPrice, state.btcSource, Modifier.weight(1f))
        PriceBlock("MSTR", state.mstrPrice, "Yahoo", Modifier.weight(1f))
    }
}

@Composable
private fun PriceBlock(label: String, price: Double, source: String, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                if (price > 0) "$" + formatNumber(price) else "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SignalCard(state: DashboardState) {
    val phi = state.phi
    val signal = phi?.signal
    val color = when (signal) {
        LagFormula.Signal.STRONG_LONG -> Color(0xFF1B873F)
        LagFormula.Signal.STRONG_SHORT -> Color(0xFFB3261E)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val text = when (signal) {
        LagFormula.Signal.STRONG_LONG -> "STRONG LONG"
        LagFormula.Signal.STRONG_SHORT -> "STRONG SHORT"
        else -> "ไม่เทรด"
    }
    val explanation = signalExplanation(state)
    Surface(
        color = color,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "สัญญาณ",
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text,
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Φ = " + (phi?.phi?.let { formatSigned(it, 4) } ?: "—") +
                    "   threshold ±0.70",
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                explanation,
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp,
            )
        }
    }
}

private fun signalExplanation(state: DashboardState): String {
    val phi = state.phi ?: return "กำลังรอข้อมูล…"
    val absPhi = abs(phi.phi)
    val rhoSig = state.lag?.isSignificant ?: false
    return when {
        phi.sessionWeight < 1.0 ->
            "ตลาด NYSE ปิด/หลังเลิก (ω=${"%.2f".format(phi.sessionWeight)}) — ระบบไม่เทรดช่วงนี้"
        !rhoSig ->
            "ρ(τ) ยังไม่มีนัยสำคัญทางสถิติ — รอข้อมูลเพิ่ม"
        absPhi < 0.30 ->
            "Φ อ่อนมาก (|Φ|=${"%.2f".format(absPhi)}) — รอสัญญาณแรงกว่านี้"
        absPhi < 0.70 ->
            "Φ ระดับกลาง (|Φ|=${"%.2f".format(absPhi)}) — ยังไม่ถึง threshold 0.70"
        phi.phi > 0 ->
            "BTC ขยับขึ้นก่อน MSTR ${formatLag(state.lag!!.optimalLagSeconds)} — คาดว่า MSTR จะตามขึ้น"
        else ->
            "BTC ขยับลงก่อน MSTR ${formatLag(state.lag!!.optimalLagSeconds)} — คาดว่า MSTR จะตามลง"
    }
}

@Composable
private fun ComponentsCard(state: DashboardState) {
    val phi = state.phi ?: return
    val rhoRaw = state.rawRho
    val alpha = state.alphaCalibration
    val rhoCal = alpha * rhoRaw

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ที่มาของ Φ (ทีละขั้น)", fontWeight = FontWeight.SemiBold)
            Text(
                "Φ = α · ρ · ω · V · penalty",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            ComponentRow("α (calibration)", "%.3f".format(alpha),
                "ปรับให้ ρ เฉลี่ย ≈ 1.0 (= 1 ÷ ค่าเฉลี่ย |ρ| ตลอดเส้นโค้ง)")
            ComponentRow("ρ raw", formatSigned(rhoRaw, 4),
                "Correlation ที่ lag ดีที่สุด (ก่อน α)")
            ComponentRow("α × ρ", formatSigned(rhoCal, 3),
                "ค่า ρ หลัง calibration")
            ComponentRow("ω session", "%.2f".format(phi.sessionWeight),
                sessionDescription(phi.sessionWeight))
            ComponentRow("V volume", "%.3f".format(phi.volumeFactor),
                "(BTC vol ÷ avg) × (MSTR vol ÷ avg)")
            ComponentRow("mNAV ปัจจุบัน", "%.3f".format(state.mNavCurrent),
                "= (P_mstr × หุ้นทั้งหมด) ÷ (BTC ที่ถือ × P_btc)")
            ComponentRow("mNAV median (ข้อมูล)", "%.3f".format(state.mNavMedian),
                "Median จากข้อมูล ${state.barsUsed} bars (ไม่ hardcode แล้ว)")
            ComponentRow("penalty exp(-β|Δ|)", "%.3f".format(phi.mNavPenalty),
                "β=2.0 — ยิ่ง mNAV ห่าง median ยิ่งกด penalty")
            Divider(Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Φ สุดท้าย", fontWeight = FontWeight.Bold)
                Text(
                    formatSigned(phi.phi, 4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (abs(phi.phi) >= 0.7)
                        if (phi.phi > 0) Color(0xFF1B873F) else Color(0xFFB3261E)
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            // Identify the bottleneck
            bottleneckHint(phi, alpha, rhoRaw, state.lag?.isSignificant ?: false)?.let {
                Spacer(Modifier.height(2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(label: String, value: String, hint: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

private fun sessionDescription(omega: Double): String = when {
    omega >= 1.4 -> "NYSE RTH (เปิด)"
    omega >= 0.95 -> "Premarket"
    omega >= 0.65 -> "After-hours"
    else -> "ตลาดปิด (Asia hours)"
}

private fun bottleneckHint(
    phi: LagFormula.PhiResult,
    alpha: Double,
    rhoRaw: Double,
    significant: Boolean,
): String? {
    if (abs(phi.phi) >= 0.7) return null
    val candidates = mutableListOf<String>()
    if (phi.sessionWeight < 1.0) candidates += "ω=${"%.2f".format(phi.sessionWeight)} (ตลาดยังไม่เปิด)"
    if (phi.mNavPenalty < 0.3) candidates += "penalty=${"%.2f".format(phi.mNavPenalty)} (mNAV ห่าง median เกินไป)"
    if (abs(rhoRaw) < 0.05) candidates += "ρ raw แค่ ${"%.3f".format(rhoRaw)} (ความสัมพันธ์อ่อน)"
    if (!significant) candidates += "ρ ยังไม่ผ่าน Z-test"
    if (phi.volumeFactor < 0.3) candidates += "volume เบามาก (V=${"%.2f".format(phi.volumeFactor)})"
    if (candidates.isEmpty()) return "Φ < 0.7 แต่ทุกองค์ประกอบดูปกติ — รอ ρ แรงขึ้นที่ lag ที่ดีที่สุด"
    return "ตัวที่กดสัญญาณ: " + candidates.joinToString("; ")
}

@Composable
private fun LagCard(state: DashboardState) {
    val lag = state.lag
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lead-Lag Detection", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            KeyValueRow("τ ที่ดีที่สุด", lag?.let { formatLag(it.optimalLagSeconds) } ?: "—")
            KeyValueRow("ρ ที่ τ*", lag?.rhoAtOptimalLag?.let { formatSigned(it, 4) } ?: "—")
            KeyValueRow(
                "Z-score",
                lag?.let {
                    val z = formatSigned(it.zScore, 2)
                    if (it.isSignificant) "$z (มีนัยสำคัญ)" else "$z (อ่อน)"
                } ?: "—"
            )
            KeyValueRow("จำนวนบาร์", state.barsUsed.toString())
            KeyValueRow("ความถี่บาร์", "${state.intervalSec / 60} นาที")
        }
    }
}

@Composable
private fun CorrelationCurve(lag: LagFormula.LagResult?) {
    if (lag == null || lag.curve.isEmpty()) return
    val points = lag.curve
    val maxAbs = points.maxOf { abs(it.rho) }.coerceAtLeast(0.05)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("ρ(τ) cross-correlation", fontWeight = FontWeight.SemiBold)
            Text(
                "Peak ที่ τ = ${formatLag(lag.optimalLagSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            ) {
                val w = size.width
                val h = size.height
                val n = points.size
                if (n < 2) return@Canvas
                val midY = h / 2f
                drawLine(
                    color = outline,
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1f,
                )
                val peakIdx = points.indexOfFirst { it.lagBars == lag.optimalLagBars }.coerceAtLeast(0)
                val peakX = peakIdx.toFloat() / (n - 1) * w
                drawLine(
                    color = primary.copy(alpha = 0.4f),
                    start = Offset(peakX, 0f),
                    end = Offset(peakX, h),
                    strokeWidth = 2f,
                )
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = i.toFloat() / (n - 1) * w
                    val y = midY - (p.rho / maxAbs).toFloat() * (h * 0.45f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = onSurface, style = Stroke(width = 3f))
            }
        }
    }
}

@Composable
private fun FormulaCard(state: DashboardState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("สูตร", fontWeight = FontWeight.SemiBold)
            Text(
                "Φ(t,τ) = α · ρ(τ) · ω(t) · V_btc(t-τ)·V_mstr(t)/V̄² · " +
                    "exp(-β|mNAV(t) - mNAV̄|) · 1[|ρ|>ε]",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(6.dp))
            KeyValueRow("β", "2.0")
            KeyValueRow("ε", "5e-4")
            KeyValueRow("EWMA λ", "0.94")
            KeyValueRow("BTC ที่ถือ", formatNumber(state.btcHeld, 0))
            KeyValueRow("หุ้นทั้งหมด", formatNumber(state.sharesOutstanding, 0))
        }
    }
}

@Composable
private fun FooterText(state: DashboardState) {
    val ts = if (state.lastUpdateMs == 0L) "ยังไม่เคย"
        else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.lastUpdateMs))
    Text(
        "อัพเดทล่าสุด: $ts · refresh ทุก 60 วินาที · MSTR delayed via Yahoo",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun KeyValueRow(k: String, v: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun formatNumber(v: Double, digits: Int = 2): String {
    return "%,.${digits}f".format(v)
}

private fun formatSigned(v: Double, digits: Int = 4): String {
    if (!v.isFinite()) return if (v > 0) "+∞" else "-∞"
    val sign = if (v >= 0) "+" else ""
    return sign + "%.${digits}f".format(v)
}

private fun formatLag(seconds: Long): String {
    val s = abs(seconds)
    val sign = if (seconds < 0) "-" else if (seconds > 0) "+" else ""
    return when {
        s < 60 -> "${sign}${s}s"
        s < 3600 -> "${sign}${s / 60}m ${s % 60}s"
        else -> "${sign}${s / 3600}h ${(s % 3600) / 60}m"
    }
}
