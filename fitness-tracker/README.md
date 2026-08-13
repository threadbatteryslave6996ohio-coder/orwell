# Fitness Tracker

A small workout log built on the T3 stack: **Next.js (pages router) + TypeScript + tRPC + Drizzle
ORM on SQLite + Tailwind**. Backend and frontend are one TypeScript project — the API is a tRPC
router, so the pages call it with full end-to-end type inference and no hand-written fetch layer.

This app is standalone; it is not part of the Maven build in the rest of this repo.

## The model

```
Workout  (name, date, notes)
└── Exercise  (name, ordered)
    └── Set  (description, weight in kg, video)
```

A set's video is either a link you paste or a file you upload. Deleting a workout cascades to its
exercises and their sets.

## Run it

```bash
npm install
npm run dev            # http://localhost:3000
```

The SQLite file (`fitness.db`) and its tables are created on first boot — the pending migrations in
`drizzle/` are applied when the server starts, so there is no setup step.

```bash
npm run build && npm run start   # production
npm run typecheck                # tsc --noEmit
npm run db:studio                # browse the data
```

After changing `src/server/db/schema.ts`, run `npx drizzle-kit generate` to write a new migration;
it is applied on the next start.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `DATABASE_URL` | `./fitness.db` | SQLite file path |
| `UPLOAD_DIR` | `./uploads` | where uploaded videos are written |

## Layout

| Path | What |
|---|---|
| `src/server/db/schema.ts` | Drizzle tables and relations |
| `src/server/db/index.ts` | connection; runs migrations on boot |
| `src/server/api/routers/workout.ts` | every query and mutation |
| `src/server/api/root.ts` | the router the client's types come from |
| `src/pages/api/trpc/[trpc].ts` | tRPC HTTP handler |
| `src/pages/api/upload.ts` | streams a video upload to `UPLOAD_DIR` |
| `src/pages/api/video/[name].ts` | serves a stored video, with range requests so seeking works |
| `src/pages/index.tsx` | workout list, create, delete |
| `src/pages/workouts/[id].tsx` | one workout: exercises and their sets |

Videos are deliberately **not** kept in `public/`: a production `next start` only serves the static
files that existed at build time, so anything uploaded afterwards would 404. They go to `UPLOAD_DIR`
and are read back through `/api/video/<name>`, which only accepts the UUID filenames it minted
itself.

## Not included

No authentication — the log is single-user and every visitor sees the same data. Deleting a set
removes the row but leaves its uploaded file on disk.
