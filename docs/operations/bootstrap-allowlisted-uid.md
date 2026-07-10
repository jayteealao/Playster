# Bootstrap: Allowlisted UID

The operator's Firebase Auth uid does not exist until the first successful
Google sign-in. But the allowlist (`ALLOWED_UID` env param + the uid baked into
`firestore.rules`) must exist *before* sign-in is useful. We resolve this
chicken-and-egg with a **two-pass deploy**.

## First pass — deploy with the bootstrap sentinel

This pass installs an allowlist that matches nobody. All callables and Firestore
reads will deny — that's intentional. Sign-in itself still succeeds because
`signInWithCredential` does not require any allowlist entry; it only creates a
Firebase Auth user and emits a uid.

1. Confirm `backend/firestore.rules` contains the sentinel uid:

   ```js
   request.auth.uid == "__BOOTSTRAP_UID__"
   ```

2. Confirm `backend/functions/src/auth/verify.ts` defaults the param to the same
   sentinel:

   ```ts
   export const ALLOWED_UID = defineString("ALLOWED_UID", {
     default: "__BOOTSTRAP_UID__",
   });
   ```

3. Deploy rules + functions:

   ```bash
   firebase deploy --only firestore:rules,functions --project playster-406121
   ```

4. Install the new Android APK. Open it on the operator device and tap
   **Sign in with Google** (Credential Manager bottom sheet).
5. The Firebase Auth user is created on the first `signInWithCredential` call.
   Open the Firebase console → **Authentication → Users** for project
   `playster-406121` and copy the **User UID** column for the new user.

## Second pass — install the real uid

1. Replace the sentinel in `backend/firestore.rules`:

   ```js
   request.auth.uid == "<real-uid-from-firebase-console>"
   ```

2. Set the `ALLOWED_UID` env param. Pick one of these mechanisms:

   - **Env-file params** (recommended in Firebase Functions v7): create
     `backend/functions/.env.playster-406121` with

     ```env
     ALLOWED_UID=<real-uid-from-firebase-console>
     ```

     `.env.<projectId>` files are loaded at deploy time and ignored by git
     (already covered by repo `.gitignore` patterns for `*.env*` if not, add
     `backend/functions/.env.*` to `.gitignore` before this step).

   - **Manual secret** (alternate): treat the uid as a secret and set it via

     ```bash
     firebase functions:secrets:set ALLOWED_UID --project playster-406121
     ```

     Then change `verify.ts` to `defineSecret("ALLOWED_UID")` and bind it to
     each callable via `secrets: [ALLOWED_UID, ...]`. Only do this if you want
     the uid hidden from anyone with deploy access to env files.

3. Re-deploy rules + functions:

   ```bash
   firebase deploy --only firestore:rules,functions --project playster-406121
   ```

4. Re-open the app and pull-to-refresh on the Playlists screen. The
   `syncAllPlaylists` callable should now succeed, `lastSyncedAt` should
   advance on the Firestore playlist documents, and the LazyColumn should
   render the playlists fetched by the existing 6h `scheduledSync` cron.

## Verification

- Rules deny strangers: pick any *other* Google account, sign in from a second
  device, open the playlists screen → expect "permission-denied" in logcat and
  an empty list.
- `gcloud functions logs read syncAllPlaylists --limit 10 --project playster-406121`
  should show the verified uid as the `req.auth.uid` on every successful call.
- `gcloud firestore documents list --collection-group=playlists --project playster-406121`
  should return at least one playlist (populated by `scheduledSync`).

## Recovering from a bad deploy

If the second pass ships with the wrong uid:

- The app stays locked (every callable returns `permission-denied`). Sign-in
  itself still works, so the user is not "locked out" at the Auth layer — only
  the allowlist is misconfigured.
- Fix `firestore.rules` + `ALLOWED_UID` env value, then re-deploy. No state
  loss; Firestore data is untouched.

## Adding more operators later

For now the system is strictly single-tenant. If/when we add a second operator:

- Migrate `ALLOWED_UID` (string) → `ALLOWED_UIDS` (csv or json array).
- Update `requireAllowlistedUid` in `verify.ts` to check `uid` membership.
- Update `firestore.rules` to read a custom claim (set via Admin SDK) rather
  than baking the uid into the rules source. That's the standard scaling path.

This is out of scope for the current single-tenant deployment.
