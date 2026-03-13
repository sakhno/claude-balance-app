# Claude Balance

Android home screen widget for monitoring your Anthropic API usage and estimated spend in real time.

## Features

- **Usage widget** — progress bar showing monthly token consumption as a percentage, estimated spend, and time until monthly reset
- **Resizable** — 4 responsive layouts (2×1 to 4×3) with no information loss; larger sizes reveal token-level breakdown
- **Light / dark / system theme** — follows system automatically or can be forced
- **Adjustable transparency** — 0–100% alpha slider for the widget background
- **Battery-efficient auto-update** — WorkManager with configurable intervals (15 min / 30 min / 1 h / 6 h); only syncs when network is available and battery is not low
- **Configurable alerts** — push notifications when usage % or estimated spend crosses your thresholds

## Screenshots

> Add screenshots here after first build.

## Getting Started

### 1. Get an Anthropic API key

Create one at [console.anthropic.com](https://console.anthropic.com) → API Keys.

### 2. Install the app

Download the latest APK from [Releases](../../releases) and install it (enable *Install from unknown sources* if needed), or build from source (see below).

### 3. Configure

1. Open **Claude Balance**
2. Paste your API key
3. Set your monthly budget limit
4. Choose sync interval and alert thresholds
5. Tap **Save Settings** → a background sync starts immediately

### 4. Add the widget

Long-press your home screen → **Widgets** → find **Claude Balance** → drag to place. Resize by long-pressing the widget.

## Build from Source

**Requirements:** Android Studio Hedgehog (2023.1) or newer, JDK 17.

```bash
git clone https://github.com/YOUR_USERNAME/claude-balance-app.git
cd claude-balance-app
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/
```

## CI / CD — GitHub Actions

Every push to `main` and every pull request triggers an automated build.

| Event | Artifact | Retention |
|---|---|---|
| Push / PR | `debug-apk` | 30 days |
| Push to `main` | `release-apk` (signed) | 90 days |
| `v*` tag | GitHub Release with both APKs | permanent |

### Signing the release APK

Add these four secrets to **Settings → Secrets and variables → Actions**:

| Secret | Description |
|---|---|
| `KEYSTORE_BASE64` | Base64-encoded `.jks` keystore: `base64 -w 0 release.jks` |
| `STORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Key password |

If the secrets are absent the release build runs unsigned (unsigned APKs can still be installed for testing).

### Creating a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

GitHub Actions will build, sign, and publish a GitHub Release automatically.

## Architecture

```
app/
└── src/main/java/com/anthropic/balanceapp/
    ├── api/                  # Retrofit client → Anthropic /v1/usage
    │   └── models/           # Response DTOs + cost calculation
    ├── data/                 # DataStore Preferences wrapper
    ├── widget/               # Jetpack Glance widget (4 responsive sizes)
    ├── worker/               # WorkManager periodic sync
    ├── notifications/        # Alert notifications
    └── ui/
        ├── settings/         # Settings screen (Compose + ViewModel)
        └── theme/            # Material 3 theme
```

**Key libraries:** Jetpack Glance 1.1, WorkManager 2.9, Retrofit 2, Moshi, DataStore, Compose Material 3.

## Cost Estimation

The app estimates spend using current Claude pricing:

| Token type | Rate |
|---|---|
| Input | $3.00 / 1M tokens |
| Output | $15.00 / 1M tokens |

This is an estimate. Check [console.anthropic.com](https://console.anthropic.com) for exact billing.

## Privacy

Your API key is stored locally in Android's encrypted DataStore and is never sent anywhere other than `api.anthropic.com`.

## License

MIT
