import Fastify from "fastify";
import fastifyMultipart from "@fastify/multipart";
import fastifyRateLimit from "@fastify/rate-limit";
import fastifyCors from "@fastify/cors";
import { loadConfig } from "./config.js";
import { getDb, closeDb } from "./db/index.js";
import { eventStore } from "./events/event-store.js";
import { requestIdHook } from "./middleware/request-id.js";
import { authHook } from "./middleware/auth.js";
import { healthRoutes } from "./routes/health.js";
import { jobRoutes } from "./routes/jobs.js";

async function start() {
  const config = loadConfig();

  const server = Fastify({ logger: true });

  // Initialize database
  const db = getDb();

  // Register plugins
  await server.register(fastifyMultipart, {
    limits: { fileSize: config.maxUploadSize },
  });

  await server.register(fastifyRateLimit, {
    max: config.rateLimitMax,
    timeWindow: config.rateLimitWindow,
  });

  await server.register(fastifyCors, { origin: false });

  // Hooks
  server.addHook("onRequest", requestIdHook);
  server.addHook("onRequest", authHook(config));

  // Routes
  await server.register(healthRoutes, { config });
  await server.register(jobRoutes, { config, eventStore, db });

  // Graceful shutdown
  const shutdown = async () => {
    await server.close();
    eventStore.dispose();
    closeDb();
  };

  process.on("SIGTERM", shutdown);
  process.on("SIGINT", shutdown);

  try {
    await server.listen({ port: config.port, host: config.host });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
}

start();
