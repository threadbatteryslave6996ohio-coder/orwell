# fitness-tracker — Next.js 15 (pages router) + Drizzle on better-sqlite3.
#
# better-sqlite3 is a native module, so the build and runtime stages share the same
# base image: the binding compiled in `deps` is the one that gets copied into `runner`.

FROM node:22-bookworm-slim AS deps
WORKDIR /app
# node-gyp's toolchain, for when better-sqlite3 has no prebuild for this platform.
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY package.json package-lock.json ./
RUN npm ci

FROM deps AS builder
WORKDIR /app
COPY . .
RUN npm run build
# Drop devDependencies but keep the already-compiled better-sqlite3 binding.
RUN npm prune --omit=dev

FROM node:22-bookworm-slim AS runner
WORKDIR /app
ENV NODE_ENV=production \
    PORT=3000 \
    HOSTNAME=0.0.0.0 \
    DATABASE_URL=/data/fitness.db \
    UPLOAD_DIR=/data/uploads

COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/.next ./.next
COPY --chown=node:node package.json next.config.mjs ./
# Read at boot by src/server/db/index.ts, which migrates before serving.
COPY --chown=node:node drizzle ./drizzle

# The SQLite file and the uploaded videos both live on the volume, and both are written at
# runtime by USER node. src/server/uploads.ts defaults UPLOAD_DIR to ./uploads, which resolves to
# the root-owned /app — so without UPLOAD_DIR above, every upload failed its mkdir with EACCES and
# answered 500. Putting it on /data also keeps the videos across a container replace, which
# /app/uploads would not have.
RUN mkdir -p /data/uploads && chown -R node:node /data
VOLUME ["/data"]

USER node
EXPOSE 3000
CMD ["npm", "run", "start"]
