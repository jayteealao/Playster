import { defineSecret } from "firebase-functions/params";

export const SUMMARIZER_URL = defineSecret("SUMMARIZER_URL");
export const SUMMARIZER_API_KEY = defineSecret("SUMMARIZER_API_KEY");

export const summarizerSecrets = [SUMMARIZER_URL, SUMMARIZER_API_KEY];
