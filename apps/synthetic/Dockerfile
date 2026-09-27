# The in-cluster browser runner. The edge Worker in src/edge deploys with wrangler, not here.
FROM mcr.microsoft.com/playwright:v1.60.0-noble
WORKDIR /app
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN npm install -g pnpm@10.28.0 && pnpm install --frozen-lockfile --prod
COPY tsconfig.json playwright.config.ts ./
COPY src/shared ./src/shared
COPY src/runner ./src/runner
USER pwuser
ENTRYPOINT ["npx", "playwright", "test"]
