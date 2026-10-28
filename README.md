# Phisherman

On-device phishing detection and prevention for Android — Hacksploit 2026, Problem Statement 4.

Phisherman scores links, SMS and call transcripts with one shared risk engine, warns **before** a suspicious link opens, and gives the user a one-tap path to report (cybercrime.gov.in, 1930).

## Layout

```
ml/        Python: features, training, evaluation, exports to android assets
android/   Kotlin + Jetpack Compose app
docs/      slides, demo script, backup video
```

## Datasets

Every dataset used, with exact name, version, source and date. Raw files live in `ml/data/raw/` and are not committed.

### Public datasets

| Dataset | Size / classes | Used for | Version / date | Licence | Source | Status |
|---|---|---|---|---|---|---|
| URL-Phish (`Dataset.csv`) | 116,600 URLs: 100,000 benign / 16,600 phishing. Phishing from PhishTank (Nov 2024 – Sep 2025), benign from a Zenodo research-organisation list | Link model: training and held-out test (split by registrable domain) | v2, downloaded 2026-10-28 | CC BY 4.0 (Mendeley Data default; verify on page) | https://data.mendeley.com/datasets/65z9twcx3r/2 | Downloaded |
| pirocheto/phishing-url (Hannousse & Yahiouche, 2021) | 11,430 URLs: 5,715 legitimate / 5,715 phishing; 62% of benign URLs have real paths | Link model: training and held-out test, added to offset URL-Phish path leakage | HF `data/train.parquet` + `data/test.parquet`, downloaded 2026-10-28 | CC BY 4.0 | https://huggingface.co/datasets/pirocheto/phishing-url | Downloaded |
| PhiUSIIL Phishing URL Dataset | 235,795 URLs: 100,945 phishing / 134,850 legitimate. **`label` is inverted: 1 = legitimate, 0 = phishing; flip it on load** | Link model: unseen-dataset test only (never used for fitting or tuning) | UCI id 967, downloaded 2026-10-28 | CC BY 4.0 | https://archive.ics.uci.edu/dataset/967 | Downloaded |
| Phishing.Database: `phishing-domains-ACTIVE.txt` | Phishing domains only, no benign | On-device blocklist layer: 392,151 domains in a 0.70 MB Bloom filter (`blocklist.bloom`). Allowlisted, official, shortener and shared-platform domains are removed | master, downloaded 2026-10-28 | MIT | https://github.com/Phishing-Database/Phishing.Database | Downloaded, built |
| Phishing.Database: `phishing-links-ACTIVE.txt` | ~66 MB of phishing URLs, no benign | Fresh-phishing recall check (model-only numbers reported separately from blocklist hits). Processed as text only; never opened | master | MIT | same as above | Planned |
| UCI SMS Spam Collection | 5,574 English SMS: ham / spam | SMS model: training and test (English volume) | v1, downloaded 2026-10-28 | CC BY 4.0 | https://archive.ics.uci.edu/dataset/228 | Downloaded |

### Hand-made datasets (ours)

| Dataset | Contents | Used for | Status |
|---|---|---|---|
| Hinglish / Indian SMS set | At least 100 messages: 40 phishing-style (fake KYC, bank block, reward, courier fee, electricity cut-off, loan / job), 30 genuine transactional (OTP delivery, debit / credit alerts, delivery updates), 30 promotional. Every row marked `synthetic` | SMS model (3 classes: normal / promotional / phishing) and test set | To create (Phase 3) |
| `ml/hard_negatives.txt` | 40 benign URLs on official sites: bank and government login portals, OAuth sign-in pages, long queries | Link FPR on hard negatives | Done |
| `ml/parity_urls.txt` | 30 URLs (10 safe, 10 phishing-like, 10 tricky) | Python vs Kotlin feature and probability parity test; expected values in `parity_expected.csv` | Done |
| `allowlist.json` | 66 popular / official registrable domains, never applied to free-hosting platforms | Instant SAFE verdicts, fewer false alarms | Done; verify entries |
| `ml/test_links.txt` | 10 device test links L1–L10 from PS4 §13.2; phishing-like ones use non-resolving `.example` domains | Device acceptance tests | Done (not committed) |
| Voice scripts | About 12 scam-call scripts and 12 normal-call scripts, recorded in our own voices | Voice detection rate, time to amber / red, false alarms | To create (Phase 4) |
| `brands.json` | Brand tokens, official registrable domains, homoglyph map | URL features 16–17 and SMS rule R_BRAND_LINK | Done; every official domain still needs manual verification |
| `voice_rules.json` | Phrase categories and weights in English / Hindi / Hinglish | Voice scorer | To create (Phase 4) |

### Known leakage: URL-Phish

Phishing URLs come from PhishTank; benign URLs come from a research-organisation list, so the classes differ in shape, not just intent:

| | benign | phishing |
|---|---|---|
| median URL length | 26 | 38 |
| median path length | 1 | 6 |
| share with a real path | 23% | 56% |
| median is_https | 0 | 1 |

A model can learn "has a path / uses https" instead of phishing behaviour. Mitigations: add benign URLs with realistic paths, split by registrable domain, and report the PhiUSIIL (unseen) result alongside the in-dataset result.

## Link model (Phase 1)

### Rebuild

```
cd ml
python -m pytest -q test_features.py   # 22 feature tests
python train_link.py                   # trains, evaluates, exports link_model.onnx, feature_order.json, thresholds.json
python build_blocklist.py              # blocklist.bloom + blocklist.json
python eval_link_extras.py             # parity file, hard negatives, test links, full-engine unseen check
```

Results are written to `ml/results_link.json`. Decision order, mirrored in Kotlin (`ml/link_engine.py` is the reference): non-http(s) ignored → allowlist → blocklist → shortener (always SUSPICIOUS) → Random Forest over 19 URL features (two thresholds) → brand lookalike on a non-official domain raises SAFE to SUSPICIOUS.

### Results (2026-10-28, positive class = phishing, FDR = recall)

| Test set | Level (threshold) | Precision | Recall (FDR) | FPR | N (phishing) |
|---|---|---|---|---|---|
| Held-out domains (URL-Phish + pirocheto) | Suspicious (0.679) | 0.900 | 0.833 | 2.13% | 25,156 (4,718) |
| Held-out domains | Dangerous (0.899) | 0.980 | 0.524 | 0.24% | 25,156 (4,718) |
| Unseen: PhiUSIIL, training domains removed | Suspicious | 0.981 | 0.316 | 0.32% | 184,543 (63,007) |
| Unseen: PhiUSIIL | Dangerous | 0.997 | 0.149 | 0.02% | 184,543 (63,007) |
| Unseen sample, full engine (model + lists + rules) | Suspicious | 0.975 | 0.354 | 0.47% | 40,000 |
| Hard negatives, model + rules without lists | any warning | | | 37.5% | 40 |
| Hard negatives, full engine | any warning | | | 0% | 40 |
| Device test links L1–L10 | | 10 / 10 pass | | | 10 |

What these numbers mean:
- **Unseen recall is low.** Most PhiUSIIL phishing URLs are bare domains (`www.photopayz.com`) with nothing suspicious in the string; no URL-only model can catch them. The blocklist adds coverage for known campaigns; content (SMS) and user friction cover the rest.
- **The model alone over-flags real login pages** (GitHub, Microsoft sign-in, Google Forms) because the training benign data has few login pages. The allowlist removes these false alarms on major sites; long-tail legitimate login pages remain a known weakness.
- Thresholds are chosen on the held-out test set for precision ≥ 0.90 (suspicious) and ≥ 0.98 (dangerous).

## Android app: link protection (Phase 2)

How it works on the phone:
1. Phisherman is set as the phone's browser app ("Turn on link protection" on the home screen). Links can also be shared to Phisherman, or pasted into the home-screen box.
2. `LinkRouterActivity` (invisible) receives the tap, scores it on device, and either forwards a safe link to the real browser (Chrome by default, changeable on the home screen) or opens `WarningActivity`.
3. The warning shows the real registrable domain in bold, a 0–100 risk meter, up to three reasons (English / Hindi), a dominant **Go back** button, **Report this link** (Phase 5), and a small **Open anyway** that needs a second confirmation.
4. Every verdict goes to a local Room log (time, level, score, host, reason ids, latency, user action). No message text or full URL is stored.

Code: `link/UrlParser.kt`, `UrlFeatures.kt` (Kotlin twin of `features.py`), `Blocklist.kt`, `LinkDecider.kt` (twin of `link_engine.py`), `LinkReasons.kt`, `LinkAnalyser.kt` (assets, ONNX, cache, fail-safe), `LinkRouterActivity.kt`, `Forwarder.kt`, `ui/WarningActivity.kt`, `data/Decision.kt`.

Tests:

```
cd android
.\gradlew testDebugUnitTest            # JVM: 19 features within 1e-4 of Python on 30 URLs, Bloom filter, rule decisions
.\gradlew connectedDebugAndroidTest    # on the phone: ONNX probability within 0.01 of Python, same level + reason for all 30
```

Results on the demo phone (S21 FE, Android 14), 2026-10-28:

| Check | Result |
|---|---|
| JVM parity (features, blocklist, rules) | 3 / 3 tests pass |
| On-device parity (ONNX + engine, 30 URLs) | pass |
| Device links via a real VIEW intent | L1–L5 and L9 warn, L6–L7 forwarded to Chrome (L8 and L10 checked in the parity tests; `tel:` never reaches Phisherman because it only registers for http/https) |
| Scoring latency (decision log) | median 2 ms, max 110 ms (first call after load) |
| Tap-to-screen time (`am start -W`) | ~150–400 ms, warning or forward |

## External dependencies

### Toolchain

| Tool | Version | Notes |
|---|---|---|
| Android Studio | 2026.2.1.8 | Installed via `winget install Google.AndroidStudio` |
| JDK | OpenJDK 25.0.3 (Android Studio bundled JBR) | `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` |
| Android SDK Platform | API 37.2 (`platforms;android-37.2`) and API 36 | `compileSdk` 37.2 (required by core-ktx 1.19 and other current AndroidX), `targetSdk` 36, `minSdk` 26; APK built for `arm64-v8a` only |
| Android SDK Build-Tools | 36.0.0 | |
| Android SDK Platform-Tools (adb) | 37.0.1 | |
| Android SDK Command-line Tools | 12.0 (`commandlinetools-win-11076708`) | `sdkmanager` |
| Gradle (wrapper) | 9.8.2 | `android/gradlew` |
| Python | 3.12.10 | ML training and evaluation |
| Git | system | |

### Android libraries (`android/gradle/libs.versions.toml`)

| Library / plugin | Version | Used for |
|---|---|---|
| Android Gradle Plugin `com.android.application` | 9.4.1 | Build |
| Kotlin (`kotlin.plugin.compose`, `kotlin.plugin.serialization`) | 2.4.21 | Language, Compose compiler, JSON |
| KSP `com.google.devtools.ksp` | 2.3.12 | Room annotation processing |
| `androidx.core:core-ktx` | 1.19.1 | Android helpers |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | `lifecycleScope` for off-main-thread scoring |
| `androidx.activity:activity-compose` | 1.13.0 | Compose activities |
| `androidx.compose:compose-bom` (ui, material3, tooling) | 2026.09.00 | UI |
| `androidx.room:room-runtime` / `room-ktx` / `room-compiler` | 2.8.5 | Local decision log |
| `com.microsoft.onnxruntime:onnxruntime-android` | 1.31.1 | Runs `link_model.onnx` on device |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.12.0 | Loads JSON assets (SMS model, thresholds, rules, results) |
| `junit:junit` | 4.13.2 | Kotlin unit and parity tests |
| `com.alphacephei:vosk-android` | not added | Optional offline speech fallback (Phase 4, only if on-device recognition is poor) |

### Python packages (`ml/`)

| Package | Version | Used for |
|---|---|---|
| scikit-learn | 1.9.1 | Random Forest (links), TF-IDF + logistic regression (SMS), metrics |
| skl2onnx | 1.20.0 | Export link model to ONNX |
| onnx | 1.23.2 | ONNX model format |
| onnxruntime | 1.31.1 | Check exported model reproduces sklearn probabilities |
| pandas | 3.1.0 | Data loading and cleaning |
| numpy | 2.5.4 | Feature arrays |
| pytest | 9.2.0 | Feature unit tests |
| matplotlib | 3.11.2 | PR curve and confusion matrix plots |
| jupyter / notebook | 7.6.3 | Exploration notebooks |
| pyarrow | 26.0.0 | Reading the pirocheto Parquet files |

### Platform services and external endpoints

| Dependency | Used for | Network? |
|---|---|---|
| Android `SpeechRecognizer` (on-device recogniser preferred, `en-IN` / `hi-IN`) | Call guard transcription | On-device where the phone supports it |
| Android default-browser role, SMS broadcast, notifications, foreground service | Link interception, SMS analysis, warnings, call guard | No |
| National Cyber Crime Reporting Portal: https://cybercrime.gov.in | "Report now" button (opened in the browser) | Opened by the user only |
| National cyber-fraud helpline 1930 | "Call 1930" button (`ACTION_DIAL`; the user presses call) | No |
| Shortened-link expansion (HEAD request, 2 s timeout, max 3 redirects) | Optional, opt-in only | Yes, opt-in |

Core detection makes no network calls. Verify the portal address and helpline number from official government sources on demo day.

## Demo phone

| Model | Android version | Default browser before setup |
|---|---|---|
| Samsung Galaxy S21 FE (SM-G990E), arm64-v8a | Android 14 (API 34), One UI 6.1 | Chrome (`com.android.chrome`), restore it after the demo |
