import { onRequest, type Request } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { getAuthUrl, handleCallback, oauthSecrets } from "./oauth";
import { storeCookies } from "./innertube";
import { saveTvOauthCredentials } from "./tv-oauth";
import { ALLOWED_UID } from "./verify";

// Ensure Firebase Admin is initialized
if (!admin.apps.length) {
  admin.initializeApp();
}

/**
 * Verifies the Firebase ID token in the `Authorization: Bearer <token>` header
 * and confirms the caller's uid matches the configured ALLOWED_UID.
 *
 * Returns an `{ status, message }` error for the caller to send when the check
 * fails, or `null` when the caller is the authorized operator. Callers do:
 * `const authError = await requireOperator(req); if (authError) { ... return; }`.
 */
async function requireOperator(
  req: Request,
): Promise<{ status: number; message: string } | null> {
  const authHeader = req.headers["authorization"] ?? "";
  const match = /^Bearer\s+(\S+)$/i.exec(authHeader);
  if (!match) {
    return {
      status: 401,
      message: "Unauthorized: missing or malformed Authorization header.",
    };
  }
  const idToken = match[1];
  let uid: string;
  try {
    const decoded = await admin.auth().verifyIdToken(idToken);
    uid = decoded.uid;
  } catch {
    return {
      status: 401,
      message: "Unauthorized: invalid or expired ID token.",
    };
  }
  if (uid !== ALLOWED_UID.value()) {
    return {
      status: 403,
      message: "Forbidden: caller is not in the operator allowlist.",
    };
  }
  return null;
}

/**
 * Redirects the user to the Google OAuth consent screen for YouTube access.
 */
export const authRedirect = onRequest({ secrets: oauthSecrets }, (req, res) => {
  const redirectUri = `https://${req.hostname}/authCallback`;
  const url = getAuthUrl(redirectUri);
  res.redirect(url);
});

/**
 * Handles the OAuth callback from Google, exchanges the code for tokens,
 * and stores the refresh token in Firestore.
 */
export const authCallback = onRequest(
  { secrets: oauthSecrets },
  async (req, res) => {
    const code = req.query.code as string | undefined;
    if (!code) {
      res.status(400).send("Missing authorization code.");
      return;
    }

    try {
      const redirectUri = `https://${req.hostname}/authCallback`;
      await handleCallback(code, redirectUri);
      res.status(200).send("Authorization successful! Tokens stored.");
    } catch (error) {
      console.error("OAuth callback error:", error);
      res.status(500).send("Failed to exchange authorization code.");
    }
  },
);

/**
 * Accepts a POST request with YouTube browser cookies and stores them
 * in Firestore for InnerTube (Watch Later) access.
 */
export const setCookies = onRequest(async (req, res) => {
  const authError = await requireOperator(req);
  if (authError) {
    res.status(authError.status).send(authError.message);
    return;
  }

  if (req.method !== "POST") {
    res.status(405).send("Method not allowed. Use POST.");
    return;
  }

  const cookies = typeof req.body === "string" ? req.body : req.body?.cookies;

  if (!cookies || typeof cookies !== "string") {
    res
      .status(400)
      .send('Missing cookies. Send as raw body or JSON { "cookies": "..." }.');
    return;
  }

  try {
    await storeCookies(cookies);
    res.status(200).send("Cookies stored successfully.");
  } catch (error) {
    console.error("setCookies error:", error);
    res.status(500).send("Failed to store cookies.");
  }
});

/**
 * Accepts a POST with TV-OAuth credentials (output of scripts/setup-tv-oauth.mjs)
 * and stores them in Firestore for InnerTube-via-TV-OAuth access to Watch Later.
 */
export const setTvOauthCredentials = onRequest(async (req, res) => {
  const authError = await requireOperator(req);
  if (authError) {
    res.status(authError.status).send(authError.message);
    return;
  }

  if (req.method !== "POST") {
    res.status(405).send("Method not allowed. Use POST.");
    return;
  }

  const body =
    typeof req.body === "string" ? safeJsonParse(req.body) : req.body;

  if (!body || typeof body !== "object" || !body.refresh_token) {
    res
      .status(400)
      .send(
        "Missing TV OAuth credentials. POST JSON: " +
          "{ access_token, refresh_token, scope, token_type, expiry_date }",
      );
    return;
  }

  try {
    await saveTvOauthCredentials(body);
    res.status(200).send("TV OAuth credentials stored.");
  } catch (error) {
    console.error("setTvOauthCredentials error:", error);
    res.status(500).send("Failed to store credentials.");
  }
});

function safeJsonParse(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}
