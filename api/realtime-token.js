const crypto = require("node:crypto");

const OPENAI_REALTIME_CLIENT_SECRETS_URL =
  "https://api.openai.com/v1/realtime/client_secrets";

function sendJson(response, statusCode, payload) {
  response.statusCode = statusCode;
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.setHeader("Cache-Control", "no-store");
  response.end(JSON.stringify(payload));
}

/** Constant-time compare that does not leak length via early return. */
function secretsMatch(a, b) {
  if (typeof a !== "string" || typeof b !== "string") return false;
  const ab = Buffer.from(a, "utf8");
  const bb = Buffer.from(b, "utf8");
  if (ab.length !== bb.length) {
    // Still burn a comparison so timing does not distinguish "wrong length".
    crypto.timingSafeEqual(ab, ab);
    return false;
  }
  return crypto.timingSafeEqual(ab, bb);
}

/**
 * Public (internet-reachable) minter for short-lived OpenAI Realtime client
 * secrets, so the glasses work outside the home LAN without the Mac.
 *
 * SECURITY: unlike the LEGACY ELLA endpoint (which had NO auth and would mint a
 * token for any anonymous caller — an open faucet on the account's credit), this
 * one REQUIRES a shared secret header and fails closed when it is unset.
 *
 * Contract mirrors the local token-service so the Android client is unchanged:
 *   POST /api/realtime-token   header: X-Lumella-Local-Token: <secret>
 *   200 -> { token, expiresAt, model }     expiresAt is epoch MILLISECONDS
 */
module.exports = async function handler(request, response) {
  response.setHeader("Cache-Control", "no-store");

  if (request.method === "OPTIONS") {
    response.statusCode = 204;
    response.end();
    return;
  }
  if (request.method !== "POST") {
    response.setHeader("Allow", "POST, OPTIONS");
    sendJson(response, 405, { error: "method_not_allowed" });
    return;
  }

  const expectedSecret = process.env.LUMELLA_LOCAL_TOKEN;
  if (!expectedSecret) {
    // Fail closed: never mint anonymously just because config is missing.
    sendJson(response, 503, {
      error: "server_not_configured",
      message: "LUMELLA_LOCAL_TOKEN is not configured.",
    });
    return;
  }

  const presented = request.headers["x-lumella-local-token"];
  if (!secretsMatch(Array.isArray(presented) ? presented[0] : presented, expectedSecret)) {
    sendJson(response, 401, { error: "unauthorized" });
    return;
  }

  const apiKey = process.env.OPENAI_API_KEY;
  if (!apiKey) {
    sendJson(response, 503, {
      error: "server_not_configured",
      message: "OPENAI_API_KEY is not configured.",
    });
    return;
  }

  const model = process.env.OPENAI_REALTIME_MODEL || "gpt-realtime";

  try {
    const upstream = await fetch(OPENAI_REALTIME_CLIENT_SECRETS_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ session: { type: "realtime", model } }),
    });

    if (!upstream.ok) {
      // Never forward the upstream body: it can echo account details.
      console.error(`[lumella-token] upstream status=${upstream.status}`);
      sendJson(response, 502, { error: "upstream_mint_failed" });
      return;
    }

    const payload = await upstream.json();
    const token = payload?.client_secret?.value ?? payload?.value ?? null;
    const rawExpires =
      payload?.client_secret?.expires_at ?? payload?.expires_at ?? null;

    if (!token) {
      console.error("[lumella-token] upstream response missing client secret");
      sendJson(response, 502, { error: "upstream_mint_failed" });
      return;
    }

    // CONTRACT: expiresAt is epoch MILLISECONDS. OpenAI emits epoch seconds
    // (~1.7e9); normalize so the Kotlin client's millisecond clock comparison
    // is unit-consistent (this mismatch previously made every token look expired).
    const expiresAt =
      typeof rawExpires === "number" && rawExpires > 0 && rawExpires < 1e12
        ? rawExpires * 1000
        : rawExpires;

    console.log(`[lumella-token] minted tokenLength=${token.length} expiresAt=${expiresAt}`);
    sendJson(response, 200, { token, expiresAt, model });
  } catch (error) {
    console.error(`[lumella-token] mint failed: ${error?.name ?? "Error"}`);
    sendJson(response, 502, { error: "upstream_mint_failed" });
  }
};
