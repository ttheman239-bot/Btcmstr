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
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.startPolling(intervalSec = 60, maxLagBars = 60, refreshSec = 60)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BTC × MSTR Lag", fontWeight = FontWeight.Bold)
                        Text(
                            "Φ(t,τ) Lead-Lag Detector",
                            style = MaterialTheme.typography.labelSmall
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.loading && state.lag == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.errorMessage?.let { ErrorBanner(it) }
            PriceRow(state)
            SignalCard(state)
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
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            msg,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PriceRow(state: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PriceBlock("BTC/USDT", state.btcPrice, "Binance", Modifier.weight(1f))
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
                fontWeight = FontWeight.Bold
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
        else -> "NO TRADE"
    }
    Surface(
        color = color,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Signal",
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text,
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 28.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Φ = " + (phi?.phi?.let { formatSigned(it, 4) } ?: "—"),
                color = if (signal == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun LagCard(state: DashboardState) {
    val lag = state.lag
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Lead-Lag Detection", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            KeyValueRow("Optimal lag τ*", lag?.let { formatLag(it.optimalLagSeconds) } ?: "—")
            KeyValueRow("ρ at τ*", lag?.rhoAtOptimalLag?.let { formatSigned(it, 4) } ?: "—")
            KeyValueRow(
                "Z-score",
                lag?.let {
                    val z = formatSigned(it.zScore, 2)
                    if (it.isSignificant) "$z (significant)" else "$z (weak)"
                } ?: "—"
            )
            KeyValueRow("Bars used", state.barsUsed.toString())
            KeyValueRow("Bar interval", "${state.intervalSec}s")
            state.phi?.let { p ->
                Divider(Modifier.padding(vertical = 4.dp))
                KeyValueRow("Session ω(t)", formatNumber(p.sessionWeight, 2))
                KeyValueRow("Volume factor", formatNumber(p.volumeFactor, 3))
                KeyValueRow("mNAV(t)", formatNumber(p.mNav, 3))
                KeyValueRow("mNAV penalty", formatNumber(p.mNavPenalty, 3))
            }
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
                "Peak at τ = ${formatLag(lag.optimalLagSeconds)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
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
                    strokeWidth = 1f
                )
                // peak marker
                val peakIdx = points.indexOfFirst { it.lagBars == lag.optimalLagBars }.coerceAtLeast(0)
                val peakX = peakIdx.toFloat() / (n - 1) * w
                drawLine(
                    color = primary.copy(alpha = 0.4f),
                    start = Offset(peakX, 0f),
                    end = Offset(peakX, h),
                    strokeWidth = 2f
                )
                val path = Path()
                points.forEachIndexed { i, p ->
                    val x = i.toFloat() / (n - 1) * w
                    val y = midY - (p.rho / maxAbs).toFloat() * (h * 0.45f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = onSurface,
                    style = Stroke(width = 3f)
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "τ = ${formatLag(points.first().lagBars * (lag.optimalLagSeconds / max(lag.optimalLagBars, 1).toLong().coerceAtLeast(1)))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "τ = ${formatLag(points.last().lagBars * (lag.optimalLagSeconds / max(lag.optimalLagBars, 1).toLong().coerceAtLeast(1)))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun FormulaCard(state: DashboardState) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Formula", fontWeight = FontWeight.SemiBold)
            Text(
                "Φ(t,τ) = α · ρ(τ) · ω(t) · V_btc(t-τ)·V_mstr(t)/V̄² · " +
                    "exp(-β|mNAV(t) - mNAV̄|) · 1[|ρ|>ε]",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            KeyValueRow("β", "2.0")
            KeyValueRow("ε", "5e-4")
            KeyValueRow("EWMA λ", "0.94")
            KeyValueRow("BTC held N", formatNumber(state.btcHeld, 0))
            KeyValueRow("Shares S_out", formatNumber(state.sharesOutstanding, 0))
            KeyValueRow("mNAV̄ (median)", formatNumber(state.mNavMedian, 3))
        }
    }
}

@Composable
private fun FooterText(state: DashboardState) {
    val ts = if (state.lastUpdateMs == 0L) "never" else
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(state.lastUpdateMs))
    Text(
        "Last update: $ts · auto-refresh 60s · MSTR delayed via Yahoo",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun KeyValueRow(k: String, v: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun formatNumber(v: Double, digits: Int = 2): String {
    return "%,.${digits}f".format(v)
}

private fun formatSigned(v: Double, digits: Int = 4): String {
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
