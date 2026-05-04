package com.btcmstr.lag

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.btcmstr.lag.ui.BacktestScreen
import com.btcmstr.lag.ui.BacktestViewModel
import com.btcmstr.lag.ui.DashboardScreen
import com.btcmstr.lag.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val liveVm: MainViewModel by viewModels()
    private val backtestVm: BacktestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BtcMstrTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RootTabs(liveVm, backtestVm)
                }
            }
        }
    }
}

@Composable
private fun RootTabs(liveVm: MainViewModel, backtestVm: BacktestViewModel) {
    var selected by remember { mutableIntStateOf(0) }
    val tabs = listOf("Live", "Backtest")
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, label ->
                Tab(
                    selected = selected == i,
                    onClick = { selected = i },
                    text = { Text(label) },
                )
            }
        }
        when (selected) {
            0 -> DashboardScreen(liveVm)
            1 -> BacktestScreen(backtestVm)
        }
    }
}

@Composable
private fun BtcMstrTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
