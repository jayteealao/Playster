import { z } from "zod";

export const urlJobSchema = z.object({
  url: z.string().url(),
  options: z
    .object({
      model: z.string().optional(),
      format: z.enum(["markdown", "md", "text"]).optional(),
      length: z.enum(["short", "medium", "long", "xl", "xxl"]).optional(),
      language: z.string().optional(),
      /**
       * Accepted for backward compatibility; ignored by the daemon.
       * The daemon always operates in url-runner mode for v1; this field
       * does NOT map to the daemon's internal `mode` enum (url|page|auto)
       * and is never forwarded. Removal is deferred per PO decision.
       */
      mode: z.enum(["auto", "website", "youtube", "media"]).optional(),
      prompt: z.string().optional(),
      /**
       * Forwarded to the daemon (DAEMON_FORWARD_KEYS): feed the timed
       * transcript to the model so the summary carries timestamped
       * "Key moments". Without this schema entry zod strips the key at
       * route validation and the flag silently never reaches the daemon.
       */
      timestamps: z.boolean().optional(),
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
