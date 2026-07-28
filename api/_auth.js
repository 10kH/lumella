const crypto = require("node:crypto");

/** Writes a JSON response with the standard no-store cache header. */
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
 * Shared fail-closed gate for the `X-Lumella-Local-Token` shared secret, used by every
 * internet-reachable endpoint under `api/` (realtime-token, config, ...).
 *
 * SECURITY: never returns "authorized" just because config is missing — an unset
 * LUMELLA_LOCAL_TOKEN fails closed with 503 rather than acting as an open faucet.
 *
 * On failure this writes the response (503 when the secret is unconfigured, 401 when the
 * presented header is missing/wrong) and returns `false`; callers must `return` immediately.
 * On success it writes nothing and returns `true`.
 */
function requireLocalToken(request, response) {
  const expectedSecret = process.env.LUMELLA_LOCAL_TOKEN;
  if (!expectedSecret) {
    sendJson(response, 503, {
      error: "server_not_configured",
      message: "LUMELLA_LOCAL_TOKEN is not configured.",
    });
    return false;
  }

  const presented = request.headers["x-lumella-local-token"];
  if (!secretsMatch(Array.isArray(presented) ? presented[0] : presented, expectedSecret)) {
    sendJson(response, 401, { error: "unauthorized" });
    return false;
  }

  return true;
}

module.exports = { sendJson, secretsMatch, requireLocalToken };
