# Echchara (ಎಚ್ಚರ) – scam shield for Karnataka

Echchara is an Android app that warns people about phishing and fraud **before** they click, pay or share a code. It works in **Kannada and English**, and every check runs on the phone.

Built for **Build4Karnataka 2026 · PS4: AI-Powered Phishing and Online Fraud Detection**.

## Download

**[⬇ Download the latest Echchara.apk](../../releases/latest/download/Echchara.apk)** (Android 10 or newer, about 2 MB)

## What it does

| Feature | How it works |
|---|---|
| **Call Shield** | When a number that is **not in your contacts** calls, a pop-up appears over the call screen. After you answer (on speaker), the caller's words are turned into text and every sentence is checked. If the call turns into fraud, the pop-up turns red: *"Likely fraud call · posing as MESCOM · Hang up now"*. |
| **SMS Shield** | Every incoming SMS is checked automatically. Scams such as fake KYC, UPI collect requests, power-cut threats and job offers trigger a warning notification. |
| **Check a message** | Paste any message or link, or share a WhatsApp message to Echchara, and get a verdict with the reasons and what to do next. |
| **Alerts** | A history of every suspicious SMS and call that Echchara caught. |
| **Learns from you** | "Was this right?" feedback retrains the on-device classifier. |

## How it decides

1. **Pattern engine**: 20 scam rules written for Karnataka (ESCOM power cuts, guarantee-scheme fees, Gulf job visas, e-challan APKs, digital arrest, UPI collect requests, KYC/PAN), plus a link analyser that catches look-alike domains, misspellings, IP links, risky domain endings and app downloads.
2. **ML classifier**: a Naive Bayes model trained on English, Kannada and Kanglish messages.
3. **Explanation engine**: a plain-language summary in Kannada or English, the scam type, and who the scammer is pretending to be.

Nothing is uploaded and no audio is stored. Speech-to-text uses the phone's built-in speech recognition.

## Install

1. Open the download link above on an Android phone.
2. If Android asks, allow installing apps from your browser, then tap **Install** (and **Install anyway** if Play Protect warns, because the app is not on the Play Store yet).
3. Open Echchara and tap **Turn on** for every row on the **Protection** tab until it says **"You are protected"**.
4. Tap **Kannada power-cut call** under *Try Call Shield now* to see the pop-up in action.

If a permission says **"Restricted setting"**: Settings → Apps → Echchara → ⋮ → **Allow restricted settings**.

## Build from source

Open this folder in Android Studio, or run:

```
gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.

## Limits

- Android does not let regular apps hear call audio directly, so Call Shield works best with the call on **speaker**. Phone makers and telecom operators can build the same check in without this limit.
- The training data is a small demo set; production training would use the UCI SMS Spam Collection, PhishTank and Kaggle phishing datasets plus 1930 complaint samples.
- Echchara gives guidance, not a guarantee. If you lost money, call **1930** or report at **cybercrime.gov.in**.
