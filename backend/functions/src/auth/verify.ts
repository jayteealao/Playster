import { defineString } from "firebase-functions/params";
import {
  HttpsError,
  onCall,
  type CallableOptions,
  type CallableRequest,
  type CallableFunction,
} from "firebase-functions/v2/https";

/**
 * Single-tenant enforcement — there are TWO places that gate access by uid.
 * Both must be updated together for any multi-tenant migration:
 *
 *   1. THIS FILE — `ALLOWED_UID` param drives `requireAllowlistedUid` for
 *      all `onCall` functions.
 *   2. `backend/firestore.rules` — the `isAllowlisted()` function bakes the
 *      same uid into Firestore security rules for direct client reads.
 *
 * A multi-tenant migration would need to: change `ALLOWED_UID` (string) →
 * `ALLOWED_UIDS` (array), update `requireAllowlistedUid`, and switch rules
 * to check a custom claim set by the Admin SDK instead of a hardcoded uid.
 * See `docs/operations/bootstrap-allowlisted-uid.md` for the bootstrap procedure.
 */

/**
 * Single-tenant allowlist: the operator's Firebase Auth uid. Set via env file
 * (e.g. `.env.playster-406121`) or `firebase functions:secrets:set` workflow.
 * Defaults to the sentinel `__BOOTSTRAP_UID__` so first-deploy denies every
 * caller until the operator captures their real uid and redeploys.
 */
export const ALLOWED_UID = defineString("ALLOWED_UID", {
  default: "__BOOTSTRAP_UID__",
});

interface AuthLike {
  uid?: string;
}

/**
 * Throws `unauthenticated` when no Firebase Auth context is attached, and
 * `permission-denied` when the caller's uid does not match the allowlist.
 * Returns the verified uid on success.
 */
export async function requireAllowlistedUid(
  auth?: AuthLike | null,
): Promise<string> {
  if (!auth || !auth.uid) {
    throw new HttpsError(
      "unauthenticated",
      "Sign in is required to call this function.",
    );
  }
  const expected = ALLOWED_UID.value();
  if (auth.uid !== expected) {
    throw new HttpsError(
      "permission-denied",
      "Caller is not in the operator allowlist.",
    );
  }
  return auth.uid;
}

/**
 * Wraps `onCall` with the allowlist gate. The handler receives the verified
 * request and only runs after `requireAllowlistedUid` succeeds.
 */
export function allowlistedCall<TIn = unknown, TOut = unknown>(
  opts: CallableOptions,
  handler: (req: CallableRequest<TIn>) => Promise<TOut> | TOut,
): CallableFunction<TIn, Promise<TOut>> {
  return onCall<TIn, Promise<TOut>>(opts, async (req) => {
    await requireAllowlistedUid(req.auth);
    return await handler(req);
  });
}
