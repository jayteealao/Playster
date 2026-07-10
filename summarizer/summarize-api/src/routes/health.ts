import type { FastifyInstance } from "fastify";
import type { Config } from "../config.js";

export async function healthRoutes(
  app: FastifyInstance,
  opts: { config: Config },
) {
  app.get("/health", async (_request, _reply) => {
    let daemon: "reachable" | "unreachable" = "unreachable";

    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), 5000);
      const res = await fetch(`${opts.config.daemonUrl}/health`, {
        signal: controller.signal,
      });
      clearTimeout(timeout);
      if (res.ok) {
        daemon = "reachable";
      }
    } catch {
      // daemon unreachable
    }

    const statusCode = daemon === "reachable" ? 200 : 503;
    return _reply.code(statusCode).send({
      status: daemon === "reachable" ? "ok" : "degraded",
      daemon,
      timestamp: new Date().toISOString(),
    });
  });
}
