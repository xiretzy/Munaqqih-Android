# Munaqqih

**On-device data cleaning and quality assessment for Android, with Arabic-aware processing.**
Graduation Project — Group 2.

## Why Munaqqih?

Most data cleaning tools assume you're sitting at a desktop with Python installed, or that you're willing to upload your files to someone else's server. Munaqqih takes a different approach: it brings the data-science stack directly to your phone.

* **Fully on-device.** No internet permission is requested. Your CSV files never leave the phone.
* **Arabic-aware by design.** The processing pipeline is built with Arabic text in mind, not adapted as an afterthought.
* **Built for non-technical users.** Open a file, see what's missing, and run anomaly detection without writing a single line of code.

This repository contains the **feasibility prototype** (Assignment 1), which verifies that Python data-science libraries can run on Android and measures their performance.

## Features

- Open a CSV file and load it with **Pandas**
- Missing-value percentage for every column
- **Isolation Forest** (scikit-learn) anomaly detection on a selected numeric column
- Built-in benchmark: generates 1,000 / 10,000 / 100,000-row CSV files, runs each size three times, and reports average load time, missing-value analysis time, ML time, and peak memory
- Test-environment panel (device, RAM, Android, Python and library versions)
- Fully offline: no Internet permission

## Tech Stack

| Component | Version |
|---|---|
| Kotlin / Jetpack Compose | Kotlin 2.2.0 |
| Chaquopy (Python on Android) | 17.1.0 |
| Python (on device) | 3.10 |
| pandas / NumPy / scikit-learn | 2.1.3 / 1.23.3 / 1.3.2 |
| Android Gradle Plugin / Gradle | 8.13.0 / 8.13 |
| minSdk / targetSdk | 24 / 36 |

## Project Structure
app/src/main/python/analyzer.py Data processing (Pandas, scikit-learn)
app/src/main/java/.../PythonBridge.kt Kotlin–Python bridge and memory sampler
app/src/main/java/.../MainScreen.kt User interface
app/src/main/java/.../MainActivity.kt Entry point; starts the Python runtime
data/ Collected Arabic datasets


## Benchmark Methodology

- **Time:** `time.perf_counter()` around each stage (load, missing-value analysis, Isolation Forest) inside Python.
- **Memory:** peak resident set size (VmRSS) of the whole app process, sampled every 10 ms from `/proc/self/status` on a separate Kotlin thread.
- Python libraries are imported once at startup so import time is excluded.
- Each file size is run three times and the mean is reported. Generated files contain 5% missing values and 1% injected outliers; Isolation Forest runs on the `salary` column with `random_state=42`.

## Build

1. Install Android Studio and **Python 3.10** (required by Chaquopy at build time).
2. Open the project and let Gradle sync.
3. Build ▸ Build APK(s). Keep the Android Gradle Plugin at 8.13.0.

A prebuilt APK is available under **Releases**.

## Roadmap

KNN imputation, Arabic text normalization, edit-distance and agglomerative clustering for spelling variants, Human-in-the-Loop recommendations with explanations, and a reusable cleaning pipeline.

## Disclaimer

This prototype is provided as-is for research and development purposes. It is not intended for production use with sensitive or personal data.
