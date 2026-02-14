import type { FastifyRequest, FastifyReply } from "fastify";
import type { Config } from "../config.js";

const SKIP_AUTH_PATHS = new Set(["/health", "/v1/ping"]);

export function authHook(config: Config) {
  return async function authenticate(
    request: FastifyRequest,
    reply: FastifyReply,
  ): Promise<void> {
    if (SKIP_AUTH_PATHS.has(request.url)) {
      return;
    }

    const apiKey = request.headers["x-api-key"];

    if (!apiKey || typeof apiKey !== "string" || !config.apiKeys.includes(apiKey)) {
      reply.code(401).send({
        error: "Unauthorized",
        message: "Invalid or missing API key",
      });
      return;
    }
  };
}
