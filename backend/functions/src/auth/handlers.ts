import { onRequest } from "firebase-functions/v2/https";
import * as admin from "firebase-admin";
import { getAuthUrl, handleCallback, oauthSecrets } from "./oauth";
import { storeCookies } from "./innertube";

// Ensure Firebase Admin is initialized
if (!admin.apps.length) {
  admin.initializeApp();
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
  if (req.method !== "POST") {
    res.status(405).send("Method not allowed. Use POST.");
    return;
  }

  const cookies =
    typeof req.body === "string" ? req.body : req.body?.cookies;

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
