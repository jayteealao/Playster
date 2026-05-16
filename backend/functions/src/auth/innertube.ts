import * as admin from "firebase-admin";
import { Innertube } from "youtubei.js";

const TOKEN_DOC_PATH = "tokens/innertube";

/**
 * Store YouTube browser cookies in Firestore for InnerTube authentication.
 */
export async function storeCookies(cookies: string): Promise<void> {
  await admin.firestore().doc(TOKEN_DOC_PATH).set({
    cookies,
    updated_at: admin.firestore.FieldValue.serverTimestamp(),
  });
}

/**
 * Initialize and return an authenticated Innertube client using stored cookies.
 */
export async function getInnertubeClient(): Promise<Innertube> {
  const doc = await admin.firestore().doc(TOKEN_DOC_PATH).get();
  if (!doc.exists) {
    throw new Error(
      "No InnerTube cookies found. Store cookies via the setCookies endpoint first.",
    );
  }

  const { cookies } = doc.data() as { cookies: string };

  const innertube = await Innertube.create({
    cookie: cookies,
  });

  return innertube;
}
