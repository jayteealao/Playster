export const allowFirefoxExtensionTests = process.env.ALLOW_FIREFOX_EXTENSION_TESTS === "1";
export const allowYouTubeE2E = process.env.ALLOW_YOUTUBE_E2E === "1";

const youtubeEnvUrls =
  typeof process.env.SUMMARIZE_YOUTUBE_URLS === "string"
    ? process.env.SUMMARIZE_YOUTUBE_URLS.split(",").map((value) => value.trim())
    : [];

const defaultYouTubeUrls = [
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "https://www.youtube.com/watch?v=jNQXAC9IVRw",
];

const defaultYouTubeSlidesUrls = [
  "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "https://www.youtube.com/watch?v=jNQXAC9IVRw",
];

export const youtubeTestUrls =
  youtubeEnvUrls.filter((value) => value.length > 0).length > 0
    ? youtubeEnvUrls.filter((value) => value.length > 0)
    : defaultYouTubeUrls;

const youtubeSlidesEnvUrls =
  typeof process.env.SUMMARIZE_YOUTUBE_SLIDES_URLS === "string"
    ? process.env.SUMMARIZE_YOUTUBE_SLIDES_URLS.split(",").map((value) => value.trim())
    : [];

export const youtubeSlidesTestUrls =
  youtubeSlidesEnvUrls.filter((value) => value.length > 0).length > 0
    ? youtubeSlidesEnvUrls.filter((value) => value.length > 0)
    : defaultYouTubeSlidesUrls;
