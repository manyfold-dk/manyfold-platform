# Backstage backend image, which also serves the frontend bundle.
#
# Build from this directory: docker build -t backstage .
# The installation's CI builds and tags the published image from this source.

# Stage 1: install every workspace, type-check, and build the backend bundle.
FROM docker.io/library/node:24-bookworm-slim AS build
# Native modules (better-sqlite3, isolated-vm) compile when no prebuilt binary matches.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 g++ build-essential \
    && rm -rf /var/lib/apt/lists/*
ENV PYTHON=/usr/bin/python3
RUN corepack enable
WORKDIR /app
COPY package.json yarn.lock .yarnrc.yml backstage.json ./
COPY packages/app/package.json packages/app/package.json
COPY packages/backend/package.json packages/backend/package.json
RUN yarn install --immutable
COPY . .
RUN yarn tsc && yarn build:backend

# Stage 2: the backend's production dependencies, from the bundle's skeleton.
FROM docker.io/library/node:24-bookworm-slim AS deps
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 g++ build-essential \
    && rm -rf /var/lib/apt/lists/*
ENV PYTHON=/usr/bin/python3
RUN corepack enable
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/yarn.lock /app/package.json /app/.yarnrc.yml /app/backstage.json ./
COPY --from=build /app/packages/backend/dist/skeleton.tar.gz ./
RUN tar xzf skeleton.tar.gz --no-same-owner --no-same-permissions --no-overwrite-dir \
    && rm skeleton.tar.gz
RUN yarn workspaces focus --all --production && yarn cache clean --all

# Stage 3: runtime, without compilers or the package manager's cache. The files stay
# owned by root and read-only to the process; it writes only to /tmp.
FROM docker.io/library/node:24-bookworm-slim
LABEL org.opencontainers.image.source="https://github.com/manyfold-dk/manyfold-platform"
LABEL org.opencontainers.image.description="Backstage developer portal for the platform"
WORKDIR /app
ENV NODE_ENV=production
COPY --from=deps /app ./
COPY --from=build /app/packages/backend/dist/bundle.tar.gz ./
# The archive's root entry is mode 0700; keep /app as it is.
RUN tar xzf bundle.tar.gz --no-same-owner --no-same-permissions --no-overwrite-dir \
    && rm bundle.tar.gz
COPY app-config.yaml app-config.production.yaml ./
USER 1000
EXPOSE 7007
CMD ["node", "packages/backend", "--config", "app-config.yaml", "--config", "app-config.production.yaml"]
