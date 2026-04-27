/**
 * SMS-to-WhatsApp local bridge.
 *
 * - On startup, connects to WhatsApp via the multi-device protocol (Baileys).
 *   First run prints a QR code in the terminal to pair with your phone's
 *   WhatsApp; subsequent runs reuse the saved auth state in ./auth.
 * - Exposes:
 *     POST /forward  { "target": "Group name", "message": "text" }
 *     GET  /health
 * - Listens on 127.0.0.1 by default — the Android app connects via localhost
 *   when running in Termux on the same device, so the bridge isn't reachable
 *   from anywhere else on the network.
 */

const {
  default: makeWASocket,
  DisconnectReason,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
} = require("baileys");
const Pino = require("pino");
const express = require("express");
const qrcode = require("qrcode-terminal");

const PORT = parseInt(process.env.PORT || "3000", 10);
const HOST = process.env.HOST || "127.0.0.1";
const AUTH_DIR = process.env.AUTH_DIR || "./auth";

let sock = null;
let connectionReady = false;
let groupCache = new Map(); // normalized name -> jid

function normalize(s) {
  if (!s) return "";
  return s
    .trim()
    .replace(/[\s   ]+/g, " ")
    .toLowerCase();
}

async function refreshGroupCache() {
  if (!sock) return;
  try {
    const groups = await sock.groupFetchAllParticipating();
    groupCache = new Map();
    for (const [jid, group] of Object.entries(groups)) {
      const name = normalize(group.subject || "");
      if (name) groupCache.set(name, jid);
    }
    console.log(`🔄 Cached ${groupCache.size} group(s)`);
  } catch (err) {
    console.error("Failed to fetch groups:", err.message);
  }
}

async function startWA() {
  const { state, saveCreds } = await useMultiFileAuthState(AUTH_DIR);
  const { version } = await fetchLatestBaileysVersion();
  console.log(`Using WhatsApp web version ${version.join(".")}`);

  sock = makeWASocket({
    version,
    auth: state,
    printQRInTerminal: false, // we render manually below for nicer output
    logger: Pino({ level: "silent" }),
    browser: ["SMS-to-WhatsApp", "Chrome", "1.0"],
  });

  sock.ev.on("creds.update", saveCreds);

  sock.ev.on("connection.update", async (update) => {
    const { connection, lastDisconnect, qr } = update;

    if (qr) {
      console.log("\n📱 Scan this QR code with your phone:");
      console.log("   WhatsApp → Settings → Linked Devices → Link a Device\n");
      qrcode.generate(qr, { small: true });
    }

    if (connection === "open") {
      connectionReady = true;
      console.log("✅ WhatsApp connected");
      await refreshGroupCache();
    } else if (connection === "close") {
      connectionReady = false;
      const code = lastDisconnect?.error?.output?.statusCode;
      const loggedOut = code === DisconnectReason.loggedOut;
      console.log(
        `⚠️  Connection closed (code=${code}). ${loggedOut ? "Logged out — delete ./auth and re-pair." : "Reconnecting..."}`
      );
      if (!loggedOut) {
        setTimeout(startWA, 2000);
      }
    }
  });

  // Refresh the group cache when group metadata changes
  sock.ev.on("groups.update", refreshGroupCache);
  sock.ev.on("group-participants.update", refreshGroupCache);
}

async function resolveGroupJid(target) {
  const normalized = normalize(target);
  if (!normalized) return null;

  // Try cache
  if (groupCache.has(normalized)) return groupCache.get(normalized);

  // Cache miss — refresh and try again
  await refreshGroupCache();
  if (groupCache.has(normalized)) return groupCache.get(normalized);

  // Try contains-match as a last resort
  for (const [name, jid] of groupCache.entries()) {
    if (name.includes(normalized) || normalized.includes(name)) {
      console.log(`Loose-matched "${target}" → "${name}"`);
      return jid;
    }
  }
  return null;
}

const app = express();
app.use(express.json({ limit: "64kb" }));

app.get("/health", (req, res) => {
  res.json({
    ready: connectionReady,
    groups: groupCache.size,
  });
});

app.get("/groups", async (req, res) => {
  if (!connectionReady) {
    return res.status(503).json({ error: "whatsapp not connected" });
  }
  await refreshGroupCache();
  res.json({
    groups: Array.from(groupCache.keys()).sort(),
  });
});

app.post("/forward", async (req, res) => {
  const { target, message } = req.body || {};
  if (!target || !message) {
    return res.status(400).json({ error: "target and message required" });
  }
  if (!connectionReady) {
    return res.status(503).json({ error: "whatsapp not connected" });
  }

  try {
    const jid = await resolveGroupJid(target);
    if (!jid) {
      return res
        .status(404)
        .json({ error: `group "${target}" not found in your chats` });
    }

    await sock.sendMessage(jid, { text: message });
    console.log(`✅ Forwarded to "${target}" (${jid})`);
    res.json({ ok: true, jid });
  } catch (err) {
    console.error("send failed:", err);
    res.status(500).json({ error: err.message });
  }
});

app.listen(PORT, HOST, () => {
  console.log(`🚀 Bridge listening on http://${HOST}:${PORT}`);
  console.log("   POST /forward  body: { target, message }");
  console.log("   GET  /health");
  console.log("   GET  /groups");
});

startWA().catch((err) => {
  console.error("Fatal startup error:", err);
  process.exit(1);
});
