import { google } from "googleapis";
import { defineSecret } from "firebase-functions/params";
import * as admin from "firebase-admin";

const OAUTH_CLIENT_ID = defineSecret("OAUTH_CLIENT_ID");
const OAUTH_CLIENT_SECRET = defineSecret("OAUTH_CLIENT_SECRET");

const SCOPES = ["https://www.googleapis.com/auth/youtube.readonly"];
const TOKEN_DOC_PATH = "tokens/youtube";

function createOAuth2Client(redirectUri?: string) {
  return new google.auth.OAuth2(
    OAUTH_CLIENT_ID.value(),
    OAUTH_CLIENT_SECRET.value(),
    redirectUri,
  );
}

/**
 * Generate the Google OAuth consent URL for YouTube readonly access.
 */
export function getAuthUrl(redirectUri: string): string {
  const oauth2Client = createOAuth2Client(redirectUri);
  return oauth2Client.generateAuthUrl({
    access_type: "offline",
    scope: SCOPES,
    prompt: "consent",
  });
}

/**
 * Exchange an authorization code for tokens and store the refresh token in Firestore.
 */
export async function handleCallback(
  code: string,
  redirectUri: string,
): Promise<void> {
  const oauth2Client = createOAuth2Client(redirectUri);
  const { tokens } = await oauth2Client.getToken(code);

  await admin
    .firestore()
    .doc(TOKEN_DOC_PATH)
    .set(
      {
        access_token: tokens.access_token ?? null,
        refresh_token: tokens.refresh_token ?? null,
        expiry_date: tokens.expiry_date ?? null,
        token_type: tokens.token_type ?? null,
        scope: tokens.scope ?? null,
        updated_at: admin.firestore.FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
}

/**
 * Return an authenticated OAuth2 client using stored tokens from Firestore.
 * Automatically refreshes expired tokens and persists the new access token.
 */
export async function getAuthenticatedClient() {
  const doc = await admin.firestore().doc(TOKEN_DOC_PATH).get();
  if (!doc.exists) {
    throw new Error(
      "No YouTube OAuth tokens found. Complete the OAuth flow first.",
    );
  }

  const data = doc.data()!;
  const oauth2Client = createOAuth2Client();

  oauth2Client.setCredentials({
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expiry_date: data.expiry_date,
    token_type: data.token_type,
    scope: data.scope,
  });

  // Listen for token refresh events and persist the new tokens
  oauth2Client.on("tokens", async (tokens) => {
    const update: Record<string, unknown> = {
      updated_at: admin.firestore.FieldValue.serverTimestamp(),
    };
    if (tokens.access_token) update.access_token = tokens.access_token;
    if (tokens.expiry_date) update.expiry_date = tokens.expiry_date;
    await admin.firestore().doc(TOKEN_DOC_PATH).update(update);
  });

  return oauth2Client;
}

/** Secrets that must be bound to HTTP functions using these utilities. */
export const oauthSecrets = [OAUTH_CLIENT_ID, OAUTH_CLIENT_SECRET];
