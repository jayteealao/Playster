import Fastify from "fastify";

const port = Number(process.env["PORT"] ?? 3000);
const host = process.env["HOST"] ?? "0.0.0.0";

const server = Fastify({
  logger: true,
});

server.get("/health", async () => {
  return { status: "ok" };
});

async function start() {
  try {
    await server.listen({ port, host });
  } catch (err) {
    server.log.error(err);
    process.exit(1);
  }
}

start();
