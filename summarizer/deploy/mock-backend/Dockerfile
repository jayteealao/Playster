# Updated digest by running: docker manifest inspect node:22-slim
FROM node:22-slim@sha256:7af03b14a13c8cdd38e45058fd957bf00a72bbe17feac43b1c15a689c029c732

WORKDIR /app
COPY server.js ./

RUN chown -R node:node /app

EXPOSE 9000

USER node

CMD ["node", "server.js"]
