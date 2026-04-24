# SMS to WhatsApp Forwarder

Android app that listens for incoming SMS messages from specific senders (e.g., CIB bank IPN transfers) and forwards the message content to a WhatsApp **contact or group** using an intent.

Tested target: **Samsung Galaxy S22 Ultra, Android 16 (One UI 8.0)**.
Compatible: Android 7.0 (API 24) → Android 16 (API 36).

## Features

- Monitors incoming SMS in the background using a foreground service
- Filters by sender with **multiple comma-separated sender IDs** (e.g., `CIB,InstaPay,IPN`)
- Forwards to **individual WhatsApp contact** OR **WhatsApp group** (via share picker)
- Duplicate-message guard (5-second window) to avoid re-forwarding the same SMS
- Auto-restarts after device reboot
- Battery optimization exemption support
- Works alongside the default SMS app — does not replace it

## CIB Egypt IPN Example

Given an incoming SMS like:

```
IPN transfer received with amount of EGP 450.00 on 8407 on 24/04 at 09:19 PM. Ref# ce2a3ff2. For more details call 19700.
```

The app will forward to WhatsApp as:

```
📩 SMS from CIB

IPN transfer received with amount of EGP 450.00 on 8407 on 24/04 at 09:19 PM. Ref# ce2a3ff2. For more details call 19700.
```

---

## Recipient Modes

### Mode 1: GROUP (recommended for your use case)

Set **Recipient** to the literal word `GROUP`. When a matching SMS arrives, WhatsApp's share sheet opens with the message pre-filled. Tap the target group, then tap Send.

> **Why can't it open the group directly?**
> WhatsApp provides no public URL scheme to open a specific group by name or ID. The `ACTION_SEND` share intent is the only supported way without the Business API. Fully automated group sending requires the WhatsApp Business Cloud API.

### Mode 2: Individual contact

Set **Recipient** to a phone number with country code (no `+`, no spaces), e.g. `201234567890`. The app opens the direct chat with the message pre-filled — tap Send.

---

## Building the APK (Cloud — No Local Setup Required)

### Option 1: Codemagic (Recommended)

1. Push this repo to GitHub / GitLab / Bitbucket
2. Sign up at [codemagic.io](https://codemagic.io)
3. Connect your repo
4. Codemagic auto-detects `codemagic.yaml`
5. Click **Start new build** → select `android-debug-workflow`
6. Download the APK from the artifacts when done

### Option 2: GitHub Actions

1. Push to GitHub
2. The workflow runs automatically on push to `main`
3. Download the APK from Actions → workflow run → Artifacts → `app-debug`

### Option 3: Android Studio (Local)

1. Open the project in Android Studio Ladybug or newer
2. Let it sync (the IDE auto-generates `gradle-wrapper.jar` on first sync)
3. **Build → Build APK(s)**
4. APK will be at `app/build/outputs/apk/debug/app-debug.apk`

---

## Installation and First-Time Setup

1. Transfer `app-debug.apk` to your phone (email, Drive, USB)
2. Tap to install — you'll need to allow "Install unknown apps" for the source
3. Open **SMS to WhatsApp**
4. Enter:
   - **Sender filter**: `CIB,InstaPay,IPN` (comma-separated, substrings, case-insensitive)
   - **Recipient**: `GROUP` for group share, OR a phone number like `201234567890`
5. Tap **Start Listener**
6. Grant permissions when prompted: SMS, Notifications
7. Tap **Disable Battery Optimization** and confirm the system dialog
8. Leave the app running. The persistent notification confirms the service is active.

### Samsung One UI 8.0 Specific (Your Device)

Samsung applies aggressive app sleeping on One UI. After installation:

1. **Settings → Battery → Background usage limits → Never sleeping apps** → add **SMS to WhatsApp**
2. **Settings → Apps → SMS to WhatsApp → Battery → Unrestricted**
3. **Settings → Apps → SMS to WhatsApp → Mobile data** → enable *Allow background data usage* and *Allow data usage while Data saver is on*

Without these settings, Samsung will kill the service after a few hours of idle.

---

## How It Works

1. `SMSReceiver` listens for `android.provider.Telephony.SMS_RECEIVED`
2. On receipt, it parses the sender against the comma-separated filter list
3. If matched, `WhatsAppIntentHelper` chooses between:
   - **Share intent** (`ACTION_SEND`) when recipient is `GROUP`
   - **Direct chat** (`wa.me` deeplink) when recipient is a phone number
4. `SMSListenerService` runs as a foreground service with a persistent notification to prevent kill
5. `BootReceiver` auto-restarts the service after reboot

## Project Structure

```
SMSToWhatsApp/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/ghareeb/smstowhatsapp/
│   │   │   ├── MainActivity.kt
│   │   │   ├── SMSReceiver.kt
│   │   │   ├── SMSListenerService.kt
│   │   │   ├── WhatsAppIntentHelper.kt
│   │   │   └── BootReceiver.kt
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       └── values/{strings,themes,colors}.xml
│   └── build.gradle.kts
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/android.yml
├── codemagic.yaml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── README.md
```

## Build Toolchain

- Android Gradle Plugin **8.9.1**
- Kotlin **2.0.21**
- Gradle **8.11.1**
- JDK **17**
- compileSdk / targetSdk **36** (Android 16)
- minSdk **24** (Android 7.0)

## Customization

### Filter by message content as well as sender

Edit `SMSReceiver.kt` inside `onReceive`:

```kotlin
if (matchesFilter(senderStr, senderFilter)
    && fullBody.contains("IPN transfer", ignoreCase = true)) {
    // forward
}
```

### Parse and reformat IPN messages nicely

Modify `formatMessage` in `SMSReceiver.kt` to extract amount, ref, time via regex:

```kotlin
private fun formatMessage(sender: String, body: String): String {
    val amount = Regex("EGP\\s*([0-9,.]+)").find(body)?.groupValues?.get(1)
    val ref = Regex("Ref#\\s*([a-f0-9]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
    return if (amount != null && ref != null) {
        "💰 CIB IPN\nAmount: EGP $amount\nRef: $ref\n\nFull: $body"
    } else {
        "📩 SMS from $sender\n\n$body"
    }
}
```

### Upgrade to WhatsApp Business Cloud API (zero-tap, true group send)

Replace `WhatsAppIntentHelper` with an HTTP POST:

```
POST https://graph.facebook.com/v21.0/{phone-number-id}/messages
Headers: Authorization: Bearer <ACCESS_TOKEN>
Body: {
  "messaging_product": "whatsapp",
  "to": "<group-id-or-number>",
  "type": "text",
  "text": { "body": "<message>" }
}
```

Requires Meta Business account + approved WhatsApp Business number.

## Permissions

| Permission | Reason |
|---|---|
| `RECEIVE_SMS` | Catch incoming SMS broadcasts |
| `READ_SMS` | Read SMS body/sender |
| `INTERNET` | Launch WhatsApp deeplink |
| `FOREGROUND_SERVICE` | Run persistent service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ foreground type |
| `POST_NOTIFICATIONS` | Show service notification (Android 13+) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Request Doze exemption |
| `RECEIVE_BOOT_COMPLETED` | Auto-restart service after reboot |
| `WAKE_LOCK` | Keep CPU awake briefly when processing SMS |

## Known Limitations

- **One-tap confirmation**: WhatsApp intent approach requires a tap to send. True automation = Business API.
- **No direct group open**: WhatsApp has no public URL scheme for groups.
- **OEM battery killers**: Xiaomi, Huawei, Oppo, Samsung have custom battery policies. Whitelist the app in vendor settings.
- **Not the default SMS app**: This app only listens; the default SMS app still handles message storage and the usual notification.

## License

MIT — use freely.

---

**Author**: Mohamed Ghareeb (`ghareeb@appenza-studio.com`)
