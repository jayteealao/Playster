import * as admin from "firebase-admin";
import { ClientType, Innertube } from "youtubei.js";

// Structural duplicate of youtubei.js's ICache. Inlined because the package's
// ICache is a type-only export under the Types namespace (not a top-level
// named export from 'youtubei.js'), so importing it directly is non-idiomatic.
// The v17 ICache interface fields are identical to this local copy.
interface ICache {
  cache_dir: string;
  get(key: string): Promise<ArrayBuffer | undefined>;
  set(key: string, value: ArrayBuffer): Promise<void>;
  remove(key: string): Promise<void>;
}

const TV_OAUTH_DOC_PATH = "tokens/innertube-oauth";
const OAUTH_CREDS_KEY = "youtubei_oauth_credentials";

interface TvOauthCredentials {
  access_token: string;
  refresh_token: string;
  scope?: string;
  token_type?: string;
  expiry_date?: string;
}

/**
 * Persists youtubei.js OAuth credentials to Firestore. Used both by the cache
 * adapter (on token refresh) and by the public HTTP setter (during onboarding).
 */
export async function saveTvOauthCredentials(
  creds: TvOauthCredentials,
): Promise<void> {
  await admin
    .firestore()
    .doc(TV_OAUTH_DOC_PATH)
    .set({
      access_token: creds.access_token,
      refresh_token: creds.refresh_token,
      scope: creds.scope ?? null,
      token_type: creds.token_type ?? "Bearer",
      expiry_date: creds.expiry_date ?? null,
      updated_at: admin.firestore.FieldValue.serverTimestamp(),
    });
}

/**
 * youtubei.js ICache backed by Firestore for the OAuth credentials key, and an
 * in-memory map for everything else. We only need to persist the credentials
 * blob — session_data is harmless to lose on cold start.
 */
class FirestoreOAuthCache implements ICache {
  private mem = new Map<string, ArrayBuffer>();

  async get(key: string): Promise<ArrayBuffer | undefined> {
    if (key !== OAUTH_CREDS_KEY) return this.mem.get(key);

    const doc = await admin.firestore().doc(TV_OAUTH_DOC_PATH).get();
    if (!doc.exists) return undefined;
    const data = doc.data() ?? {};
    if (!data.refresh_token) return undefined;
    const creds: TvOauthCredentials = {
      access_token: data.access_token,
      refresh_token: data.refresh_token,
      scope: data.scope ?? undefined,
      token_type: data.token_type ?? "Bearer",
      expiry_date: data.expiry_date ?? undefined,
    };
    return new TextEncoder().encode(JSON.stringify(creds)).buffer;
  }

  async set(key: string, value: ArrayBuffer): Promise<void> {
    if (key !== OAUTH_CREDS_KEY) {
      this.mem.set(key, value);
      return;
    }
    const json = new TextDecoder().decode(value);
    const creds: TvOauthCredentials = JSON.parse(json);
    await saveTvOauthCredentials(creds);
  }

  async remove(key: string): Promise<void> {
    if (key !== OAUTH_CREDS_KEY) {
      this.mem.delete(key);
      return;
    }
    await admin.firestore().doc(TV_OAUTH_DOC_PATH).delete();
  }

  get cache_dir(): string {
    return ":firestore:";
  }
}

/**
 * Build an authenticated Innertube client using TV-OAuth credentials stored in
 * Firestore. Used for Watch Later (and any other personal account data) — the
 * regular cookie path no longer reliably works.
 */
export async function getInnertubeTvClient(): Promise<Innertube> {
  // ClientType.TV resolves to "TVHTML5" in v17 — the correct wire name.
  // No post-create context patch needed (was required in v13.4.0).
  const innertube = await Innertube.create({
    cache: new FirestoreOAuthCache(),
    client_type: ClientType.TV,
  });

  // youtubei.js doesn't auto-persist refreshed tokens — we have to call
  // cacheCredentials() ourselves on each refresh event.
  innertube.session.on("update-credentials", async () => {
    await innertube.session.oauth.cacheCredentials();
  });

  await innertube.session.signIn();

  return innertube;
}
