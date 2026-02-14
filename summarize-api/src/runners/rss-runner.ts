import Parser from "rss-parser";
import type Database from "better-sqlite3";
import type { Config } from "../config.js";
import type { Job } from "../db/jobs.js";
import { updateJobStatus } from "../db/jobs.js";
import type { EventStore } from "../events/event-store.js";
import { validateUrl } from "../security/ssrf.js";
import { runUrlJob } from "./url-runner.js";

export async function runRssJob(
  job: Job,
  config: Config,
  eventStore: EventStore,
  db: Database.Database,
): Promise<void> {
  const feedUrl = job.source;
  const options = job.options ? JSON.parse(job.options) : {};
  const targetItem: string | undefined = options.item;

  try {
    // Step 1: Mark running
    updateJobStatus(job.id, "running");

    // Step 2: Emit status event
    eventStore.addEvent(job.id, {
      type: "status",
      data: { message: "Fetching RSS feed..." },
      timestamp: new Date().toISOString(),
    });

    // Step 3: Validate the RSS feed URL using SSRF protection
    const validation = await validateUrl(feedUrl);
    if (!validation.safe) {
      throw new Error(`Blocked RSS feed URL: ${validation.error}`);
    }

    // Step 4: Fetch and parse the RSS feed
    const parser = new Parser();
    const feed = await parser.parseURL(feedUrl);

    // Step 5: Resolve the target item
    let resolved: Parser.Item | undefined;

    if (!targetItem || targetItem === "latest") {
      resolved = feed.items[0];
    } else if (/^\d+$/.test(targetItem)) {
      resolved = feed.items[parseInt(targetItem, 10)];
    } else {
      resolved = feed.items.find((i) => i.guid === targetItem);
    }

    if (!resolved) {
      throw new Error(
        `RSS item not found (feed has ${feed.items.length} items)`,
      );
    }

    // Step 6: Extract URL from the resolved feed item
    const itemUrl = resolved.link || resolved.enclosure?.url;
    if (!itemUrl) {
      throw new Error(
        `RSS item "${resolved.title ?? "untitled"}" has no link or enclosure URL`,
      );
    }

    const itemTitle = resolved.title ?? "Untitled";
    const itemGuid = resolved.guid ?? "";

    // Step 7: Emit status event for summarization
    eventStore.addEvent(job.id, {
      type: "status",
      data: { message: `Summarizing: ${itemTitle}`, url: itemUrl },
      timestamp: new Date().toISOString(),
    });

    // Step 8: Delegate to the URL runner
    const modifiedJob: Job = {
      ...job,
      type: "url",
      source: itemUrl,
      metadata: JSON.stringify({
        ...(job.metadata ? JSON.parse(job.metadata) : {}),
        rss_feed: feedUrl,
        item_title: itemTitle,
        item_guid: itemGuid,
      }),
    };

    await runUrlJob(modifiedJob, config, eventStore, db);
  } catch (err: unknown) {
    const message = err instanceof Error ? err.message : String(err);
    updateJobStatus(job.id, "failed", {
      error: { message },
    });
    eventStore.addEvent(job.id, {
      type: "error",
      data: { message },
      timestamp: new Date().toISOString(),
    });
  }
}
