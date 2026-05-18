import { z } from "zod";

export const urlJobSchema = z.object({
  url: z.string().url(),
  options: z
    .object({
      model: z.string().optional(),
      format: z.enum(["markdown", "md", "text"]).optional(),
      length: z.enum(["short", "medium", "long", "xl", "xxl"]).optional(),
      language: z.string().optional(),
      // NOTE: `mode` is informational only. It is NOT forwarded to the daemon —
      // the daemon's own `mode` enum (url|page|auto) does not map to ours
      // (auto|website|youtube|media). Cleanup is deferred (see shape doc).
      mode: z.enum(["auto", "website", "youtube", "media"]).optional(),
      prompt: z.string().optional(),
    })
    .optional(),
  // Webhook callback contract (Stripe-style signing on terminal SSE events).
  // If `webhook_url` is set, `webhook_secret` is required (enforced by the
  // route, not the schema, so the error message is friendlier).
  webhook_url: z.string().url().optional(),
  webhook_secret: z.string().min(16).optional(),
  client_job_id: z.string().min(1).max(256).optional(),
});

export const rssJobSchema = z.object({
  rss: z.string().url(),
  item: z.union([z.string(), z.number()]).optional().default("latest"),
  options: z
    .object({
      model: z.string().optional(),
      format: z.enum(["markdown", "md", "text"]).optional(),
      length: z.enum(["short", "medium", "long", "xl", "xxl"]).optional(),
      language: z.string().optional(),
    })
    .optional(),
});

export type UrlJobInput = z.infer<typeof urlJobSchema>;
export type RssJobInput = z.infer<typeof rssJobSchema>;
