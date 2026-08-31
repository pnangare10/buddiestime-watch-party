// FCM push, mirroring livekit.js's shape: env-configured creds, a READY flag, graceful no-op when unset.
const admin = require("firebase-admin");
const { getMessaging } = require("firebase-admin/messaging");

const SERVICE_ACCOUNT_JSON = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
const FCM_READY = !!SERVICE_ACCOUNT_JSON;

if (!FCM_READY) {
  console.warn(
    "[PUSH] FIREBASE_SERVICE_ACCOUNT_JSON missing — nudge notifications will fail closed",
  );
} else {
  admin.initializeApp({
    credential: admin.cert(JSON.parse(SERVICE_ACCOUNT_JSON)),
  });
  console.log("[PUSH] Firebase Admin ready");
}

async function sendNudge(fcmToken, body) {
  if (!FCM_READY) return { ok: false, reason: "push-not-configured" };
  try {
    await getMessaging().send({
      token: fcmToken,
      notification: { title: "Watch Party 💗", body },
      data: { type: "nudge" },
    });
    return { ok: true };
  } catch (e) {
    console.warn(`[PUSH] send failed: ${e.message}`);
    return { ok: false, reason: "send-failed" };
  }
}

module.exports = { FCM_READY, sendNudge };
