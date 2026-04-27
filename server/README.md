# SMS-to-WhatsApp local bridge

A small Node.js + Baileys server that receives forwarded SMS messages from the
Android app over HTTP and delivers them to a named WhatsApp group via the
multi-device protocol.

Designed to run **on the same Android phone via Termux**, but it works anywhere
Node.js 18+ runs (Raspberry Pi, VPS, laptop). The Android app just needs to be
able to reach the bridge URL.

## What it provides

- `POST /forward` — body `{ "target": "Group name", "message": "text" }` —
  resolves the target by name and sends the message
- `GET /health` — returns `{ ready, groups }`
- `GET /groups` — lists the names of every group your account belongs to

## One-time setup on your Android phone (Termux)

1. **Install Termux** from F-Droid: <https://f-droid.org/en/packages/com.termux/>
   *(The Play Store version is outdated — F-Droid is the canonical build.)*

2. **Install Node.js + git** inside Termux:

   ```sh
   pkg update && pkg upgrade -y
   pkg install -y nodejs git
   ```

3. **Clone this repo** (or just the `server/` directory):

   ```sh
   cd ~
   git clone https://github.com/Appenza-Main-Org/sms-to-whatsapp.git
   cd sms-to-whatsapp/server
   ```

4. **Install dependencies:**

   ```sh
   npm install
   ```

5. **Run the server.** Two pairing modes — pick whichever fits:

   **Pairing-code mode (recommended for single-phone setups)** — no
   camera/QR needed. Substitute your WhatsApp phone number in E.164
   form (digits only, country code first; example below is Egypt):

   ```sh
   PHONE_NUMBER=201234567890 npm start
   ```

   The server prints an 8-character code like `ABCD-EFGH`. On the same
   phone, open WhatsApp → **Settings → Linked Devices → Link a Device**
   → tap **"Link with phone number instead"** at the bottom → enter the
   code. Pairing credentials are saved in `./auth/` so subsequent
   starts skip this.

   **QR mode** — only practical if you have a second device showing the
   QR while you scan from your phone:

   ```sh
   npm start
   ```

   Once paired you should see:

   ```
   ✅ WhatsApp connected
   🔄 Cached 24 group(s)
   🚀 Bridge listening on http://127.0.0.1:3000
   ```

6. **Keep Termux alive** so the server doesn't get killed:

   ```sh
   # Inside Termux, run once:
   termux-wake-lock
   ```

   Then in Android settings, exclude Termux from battery optimization
   (Settings → Apps → Termux → Battery → Unrestricted).

## Auto-start on boot (optional but recommended)

Inside Termux:

```sh
pkg install termux-services
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/start-bridge <<'SH'
#!/data/data/com.termux/files/usr/bin/sh
termux-wake-lock
cd ~/sms-to-whatsapp/server
# After first pairing the auth/ dir holds the session, so PHONE_NUMBER
# isn't strictly needed on later starts — but leaving it set is harmless.
node index.js >> ~/bridge.log 2>&1 &
SH
chmod +x ~/.termux/boot/start-bridge
```

Install **Termux:Boot** from F-Droid:
<https://f-droid.org/en/packages/com.termux.boot/> — open it once, then the
bridge will auto-launch at every reboot.

## Verifying it works from the Android app

1. In the **SMS to WhatsApp** app, leave **Bridge URL** as `http://127.0.0.1:3000`.
2. Tap **Test Bridge Connection** — you should see
   `✅ Bridge ready — N groups linked`.
3. Tap **Start Listener**.
4. Send yourself a test SMS containing `IPN`.

The Android app POSTs `{target: "MAHFOUZ IPN instapay revise", message: "..."}`
to the bridge; the bridge resolves the group name to its JID and sends. Works
whether the screen is locked, off, or in another app — no UI automation
required.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Bridge unreachable` from the Android app | Termux is closed, or the server crashed. Re-open Termux and `npm start`. |
| `Bridge running but WhatsApp not paired yet` | First-run QR wasn't scanned, or session expired. Check the Termux logs for a fresh QR. |
| `group "XYZ" not found` | The exact group name doesn't match. Run `curl http://127.0.0.1:3000/groups` from Termux to see your linked group names. |
| Connection drops every few hours | Normal — Baileys auto-reconnects. The Android app's queue + retry will redrive any forwards that hit during the gap. |
| Logged out unexpectedly | Delete `./auth/` and re-pair. Happens if you tap "Log out from all linked devices" on your phone. |

## Security notes

- The bridge listens on **127.0.0.1 only** — nothing on your local network can
  reach it. Override via `HOST=0.0.0.0` if you really need remote access (then
  put it behind a reverse proxy with auth).
- The auth state in `./auth/` is the equivalent of a logged-in WhatsApp Web
  session. Don't share or back up that directory to anywhere public.
- This is **not an official WhatsApp API**. It's a third-party client. Keep
  message volume reasonable and behave normally to avoid the rare account
  ban.
