# Echchara (ಎಚ್ಚರ) – scam shield for Karnataka

Echchara is an Android app that warns people about phishing and fraud **before** they click, pay or share a code. It works in **Kannada and English**, and every check runs on the phone. The website also reads **Hindi, Tamil, Telugu and Malayalam**, including messages that mix them with English.

Built for **Build4Karnataka 2026 · PS4: AI-Powered Phishing and Online Fraud Detection**.

## Download

**[⬇ Download the latest Echchara.apk](../../releases/latest/download/Echchara.apk)** (Android 10 or newer, about 2 MB)

**🌐 Website:** https://manishshetty-17.github.io/echchara/ — check SMS, WhatsApp messages, emails, links, websites, screenshots, QR codes and job offers in the browser, plus the Learn & Play scam simulator. The website source is in [`docs/index.html`](docs/index.html).

**🔊 Voice help (Kannada and English):** the **Listen** button on every page reads the page and every result aloud, and **Speak the message** lets someone say a message instead of typing it. Built for people who find reading hard. Kannada speech needs a Kannada voice on the device: Chrome on Android and Microsoft Edge on computers have one.

## What it does

| Feature | How it works |
|---|---|
| **Call Shield** | When a number that is **not in your contacts** calls, a pop-up appears over the call screen. After you answer (on speaker), the caller's words are turned into text and every sentence is checked. If the call turns into fraud, the pop-up turns red: *"Likely fraud call · posing as MESCOM · Hang up now"*. |
| **SMS Shield** | Every incoming SMS is checked automatically. Scams such as fake KYC, UPI collect requests, power-cut threats and job offers trigger a warning notification. |
| **Check a message** | Paste any message or link, or share a WhatsApp message to Echchara, and get a verdict with the reasons and what to do next. |
| **Payment pause** (Phone Shield) | During a call from an unknown number, and for 20 minutes after a scam call, SMS or chat, opening GPay, PhonePe, Paytm, BHIM or a bank app first shows *"Pause before you pay"* with **Call 1930**. "Continue" unlocks after 5 seconds, to break the scammer's rush. |
| **Screen-share guard** (Phone Shield) | The same pause appears before AnyDesk, TeamViewer QuickSupport, RustDesk or AirDroid opens during an unknown call or after a scam warning. |
| **Chat check** (Phone Shield) | With notification access, WhatsApp, Telegram, Instagram, Messenger, Signal and Gmail messages go through the same engine, including `.apk` files sent as wedding invitations or bills. |
| **App check** (Phone Shield) | Flags newly installed apps from outside an app store that can read SMS or notifications, use Accessibility or become a device admin, with an **Uninstall** button. **Check my apps now** scans the whole phone. |
| **SIM alarm** (Phone Shield) | If the SIM loses service while Wi-Fi still works, which is what a SIM swap looks like from the phone, Echchara warns you: after about 90 seconds if a SIM or eSIM scam was flagged in the last 6 hours, otherwise after 15 minutes. |
| **OTP warning** | If an OTP arrives while you are on a call with an unknown number, the Call Shield pop-up turns red: *"Don't read out this OTP"*. |
| **Family code word** | When a call or message claims to be a relative or your boss, the warning reminds you to call back on the saved number or ask for your family code word. The Protection tab helps you message your family to set one. |
| **First-hour kit** | Lost money or shared an OTP? A checklist with 1930, your bank, cybercrime.gov.in, removing the caller's app, keeping evidence and reporting on Chakshu. |
| **Alerts** | A history of every suspicious SMS, chat, call and risky app that Echchara caught. |
| **Learns from you** | "Was this right?" feedback retrains the on-device classifier. |
| **Report it** | Scam alerts in the app and on the website have **Report on Chakshu**: the message is copied and the government's Chakshu portal (Sanchar Saathi) opens, ready to paste. |

## How it decides

1. **Pattern engine**: 24 scam rules written for Karnataka (ESCOM power cuts, guarantee-scheme fees, Gulf job visas, e-challan APKs, digital arrest, UPI collect requests, KYC/PAN, fake investment and trading groups, .apk files sent as wedding invitations or bills, eSIM and SIM-swap requests, money-mule recruitment and "boss" impersonation), plus a link analyser that catches look-alike domains, misspellings, fake addresses placed in front of the real domain, IP links, risky domain endings and app downloads. It trusts RBI's `.bank.in` bank domains and checks that investment payments go to SEBI's `@valid` UPI IDs.
2. **ML classifier**: a Naive Bayes model trained on English, Kannada and Kanglish messages.
3. **Explanation engine**: a plain-language summary in Kannada or English, the scam type, and who the scammer is pretending to be.

Echchara itself uploads nothing and stores no audio. Speech-to-text uses the phone's speech recognition service, which on many phones works online unless offline speech packs are installed.

**Phone Shield permissions.** *Usage access* lets Echchara see which app comes to the front, and it only looks during an unknown call or in the 20 minutes after a scam warning. *Notification access* is used only for the chat and mail apps listed above. *See all apps* is used by the app check. Everything stays on the phone.

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

Every push that changes the app is also built by GitHub Actions: open **Actions → Build Android app → the latest run → Echchara-apk**. An APK built on a different computer is signed with a different key, so uninstall an older Echchara before installing it.

## Limits

- Android does not let regular apps hear call audio directly, so Call Shield works best with the call on **speaker**. Phone makers and telecom operators can build the same check in without this limit.
- Some phone makers block apps from opening screens from the background. Echchara then draws the pause as an overlay; if neither appears, allow Echchara to *display pop-up windows while running in the background* (Xiaomi, Redmi, POCO) or turn off battery optimisation for it.
- The SIM alarm can't tell a SIM swap from a place with no signal, so ordinary alarms come at most once every three days. After a SIM or eSIM scam warning it always warns.
- The training data is a small demo set; production training would use the UCI SMS Spam Collection, PhishTank and Kaggle phishing datasets plus 1930 complaint samples.
- Echchara gives guidance, not a guarantee. If you lost money, call **1930** or report at **cybercrime.gov.in**.
