const { sendJson, requireLocalToken } = require("./_auth.js");

/**
 * Public (internet-reachable) config endpoint so the glasses app can resolve the current
 * luma-api base URL WITHOUT an APK rebuild.
 *
 * Why this exists: luma-api only lives on the home LAN (Mac mini, 192.168.35.170:8010).
 * Outside the LAN it is reached through a cloudflared quick tunnel, whose public URL
 * changes every time the tunnel restarts (`ops/luma-tunnel.sh` re-publishes the new URL
 * here on each restart via `LUMA_BASE_URL`). Baking that URL into BuildConfig meant every
 * tunnel restart required a full APK rebuild+reinstall; this endpoint breaks that coupling
 * — the app polls it at boot and falls back to the BuildConfig value on any failure.
 *
 * SECURITY: gated by the same X-Lumella-Local-Token shared secret as realtime-token.js
 * (constant-time compare, fail-closed 503 when unset) — this must not be publicly
 * readable, since it would leak the current internal luma-api address to anyone.
 *
 *   GET /api/config   header: X-Lumella-Local-Token: <secret>
 *   200 -> { lumaBaseUrl: string|null, schemaRev: 1 }
 */
module.exports = async function handler(request, response) {
  response.setHeader("Cache-Control", "no-store");

  if (request.method === "OPTIONS") {
    response.statusCode = 204;
    response.end();
    return;
  }
  if (request.method !== "GET") {
    response.setHeader("Allow", "GET, OPTIONS");
    sendJson(response, 405, { error: "method_not_allowed" });
    return;
  }

  if (!requireLocalToken(request, response)) return;

  const lumaBaseUrl = process.env.LUMA_BASE_URL || null;
  sendJson(response, 200, { lumaBaseUrl, schemaRev: 1 });
};
