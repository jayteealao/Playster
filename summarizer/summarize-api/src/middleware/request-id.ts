import type { FastifyRequest, FastifyReply } from "fastify";
import { nanoid } from "nanoid";

export async function requestIdHook(
  request: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  const id = nanoid(21);
  request.id = id;
  reply.header("X-Request-Id", id);
  request.log = request.log.child({ requestId: id });
}
