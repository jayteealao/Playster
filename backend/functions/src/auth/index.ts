// OAuth 2.0 utilities (regular YouTube playlists)
export {
  getAuthUrl,
  handleCallback,
  getAuthenticatedClient,
  oauthSecrets,
} from "./oauth";

// InnerTube / cookie auth (legacy — kept for compatibility, no longer used by WL)
export { storeCookies, getInnertubeClient } from "./innertube";

// InnerTube / TV-OAuth auth (Watch Later)
export { getInnertubeTvClient, saveTvOauthCredentials } from "./tv-oauth";

// HTTP endpoint handlers
export {
  authRedirect,
  authCallback,
  setCookies,
  setTvOauthCredentials,
} from "./handlers";

// Allowlist gate for callable functions
export { requireAllowlistedUid, allowlistedCall, ALLOWED_UID } from "./verify";
