# BTC × MSTR Lag — Android App

Implements the Φ(t,τ) lead-lag detector from the BTC/MSTR research note as a
self-contained Android app. The app pulls aligned BTC and MSTR bars, runs a
weighted cross-correlation sweep over τ ∈ [-60, +60] minutes, computes the
mNAV-adjusted Φ signal, and shows a live dashboard.

## Build

Local build (needs Android SDK with `platforms;android-34` and
`build-tools;34.0.0`):

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
```

CI build (GitHub Actions): every push to a branch builds an unsigned debug
APK and uploads it as a workflow artifact. Pushes to `claude/*` branches also
publish a prerelease with the APK attached, so the latest binary is always
downloadable from the **Releases** tab.

## Architecture

```
app/src/main/java/com/btcmstr/lag/
├── LagFormula.kt          // Φ(t,τ) math: log returns, EWMA σ, ρ(τ), Z-score
├── MarketDataService.kt   // BTC via Binance public REST, MSTR via Yahoo v8 chart
├── MainActivity.kt        // Compose entry + dynamic Material 3 theme
└── ui/
    ├── MainViewModel.kt   // Polling + state holder
    └── DashboardScreen.kt // Cards: prices, signal, lag, ρ(τ) curve, formula
```

## Formula

Φ(t,τ) = α · ρ(τ) · ω(t) · V_btc(t-τ)·V_mstr(t)/V̄² · exp(-β|mNAV(t) - mNAV̄|) · 1[|ρ|>ε]

Defaults: β = 2.0, ε = 5e-4, EWMA λ = 0.94, mNAV̄ = 1.85,
N_btc = 818,334, S_out = 348.3M.

Signals: Φ > +0.7 → STRONG_LONG, Φ < -0.7 → STRONG_SHORT, otherwise NO_TRADE.
