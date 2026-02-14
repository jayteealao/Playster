import { z } from "zod";

export const urlJobSchema = z.object({
  url: z.string().url(),
  options: z
    .object({
      model: z.string().optional(),
      format: z.enum(["markdown", "md", "text"]).optional(),
      length: z.enum(["short", "medium", "long", "xl", "xxl"]).optional(),
      language: z.string().optional(),
      mode: z.enum(["auto", "website", "youtube", "media"]).optional(),
      prompt: z.string().optional(),
    })
    .optional(),
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
