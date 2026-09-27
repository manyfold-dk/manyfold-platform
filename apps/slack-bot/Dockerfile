FROM docker.io/library/node:24-alpine AS builder
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN corepack enable && corepack prepare pnpm@10.28.0 --activate && pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM docker.io/library/node:24-alpine
# OCI labels for GitHub Container Registry repository linking
LABEL org.opencontainers.image.source="https://github.com/manyfold-dk/manyfold-platform"
LABEL org.opencontainers.image.description="Slack bot for interactive platform integration (manyfold.dk)"
WORKDIR /app
COPY --from=builder /app/dist ./dist
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./
USER 1000
EXPOSE 3000
CMD ["node", "dist/app.js"]
