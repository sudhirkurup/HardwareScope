# HardwareScope — Android hardware inspector

One APK that runs on **Android 5.0 through Android 15+** (minSdk 21, targetSdk 35). It uses only the
Android framework (no AndroidX/Jetpack), and every newer API is guarded at runtime, so the same build
behaves correctly on old and new phones, tablets and TV boxes.

## What it does

| Area | Details |
|---|---|
| **Discovers hardware, in order** | Device & OS → CPU → GPU → RAM → Storage → Battery → Thermal → Display → Sensors → Cameras → Audio → Wi-Fi/Cellular → Bluetooth/NFC/USB → GNSS → Biometrics/Security → Haptics/Flash/IR/Input → all system features. Cards appear one by one as the scan runs. |
| **Live performance** | Per-core CPU clocks and load, GPU load (where the chip exposes it), RAM, storage, battery current/voltage/power/temperature, thermal zones & throttling status, brightness & refresh rate, every sensor's live values and sample rate, network up/down speed, Wi-Fi signal/link speed, cell signal. Each card has a rolling graph. |
| **Tests** | CPU single/multi-core, memory bandwidth & latency, storage read/write/4K IOPS, GPU shader benchmark, measured display refresh rate, sensor sampling rates, network latency/download, battery drain; plus hands-on checks: vibration, flashlight, stereo speakers, microphone, GPS fix (TTFF, satellites, constellations, dual-frequency), multi-touch, dead pixels. |
| **Select / deselect** | **Select** button: choose which hardware groups to show and which ones update live. Choices are remembered. |
| **Download (tabular)** | **Share ▾ → Download table** saves a CSV (opens in Excel/Sheets) to `Download/HardwareScope`; **Download report** saves a formatted HTML table. |
| **Screenshot** | Visible screen, or the **full page** (whole scrolling list as one tall image) to `Pictures/HardwareScope`. |
| **Email** | **Share ▾ → Email report** attaches any of CSV, HTML, full-page screenshot and snapshot file, with test results in the body. |
| **Compare** | Save snapshots, import a snapshot from another phone, then compare any two (or live vs. saved). Differences are highlighted with % change, and the comparison exports to CSV. |

## Build the APK — option A: GitHub (no software to install)

1. Create a new repository on github.com and upload the contents of this folder (keep the `.github` folder).
2. GitHub builds automatically. Open **Actions → Build APK → latest run → Artifacts → HardwareScope-apk**.
3. Unzip, copy `app-release.apk` to the phone, open it and allow "Install unknown apps" when asked.

## Build the APK — option B: Android Studio

1. Install Android Studio (Ladybug 2024.2 or newer).
2. **File → Open** → select this folder. Let Gradle sync (it downloads the Android SDK pieces it needs).
3. Plug in a phone with USB debugging on and press **Run ▶**, or use **Build → Build APK(s)**.

The release build is signed with the debug key so it can be side-loaded straight away. Before publishing
on Google Play, create your own signing key (**Build → Generate Signed App Bundle**).

## Notes on what Android allows

* **CPU usage %** — Android 8+ blocks apps from reading system-wide CPU usage, so the app shows *clock load*
  (current ÷ max frequency per core) instead, which tracks real load closely. On Android 7 and older it shows true CPU usage.
* **GPU load, chip temperatures** — readable on many Qualcomm/Mali devices; some manufacturers hide them. The app says so instead of guessing.
* **Wi-Fi network name, 4G/5G type, GPS satellites, Bluetooth name** — need the optional permissions the app asks for on first launch (or via **⋮ → Grant permissions**). Nothing is sent anywhere unless you share or email a report; the only network traffic is the optional network speed test (Cloudflare).
* **Storage read speed** can be inflated by the phone's cache; write speed and 4K IOPS are reliable.

## Project layout

```
app/src/main/java/com/hardwarescope/app/
  MainActivity.kt   UI: live cards, tests, compare, select dialog, export/screenshot/email
  Probe.kt          All hardware detection + live readings (version-guarded)
  Tests.kt          Benchmarks and functional tests
  Gpu.kt            Off-screen OpenGL ES info and shader benchmark
  Reports.kt        CSV / HTML / JSON snapshot, compare, saving to Downloads/Pictures
  ReportProvider.kt Shares exported files with mail apps
  Ui.kt             Theme colours, cards, live graph, touch & pixel test views
  Model.kt          Categories, rows, helpers
```
