import path from "node:path";
import Database from "better-sqlite3";
import { drizzle } from "drizzle-orm/better-sqlite3";
import { migrate } from "drizzle-orm/better-sqlite3/migrator";

import * as schema from "./schema";

const globalForDb = globalThis as unknown as {
  sqlite: Database.Database | undefined;
};

function connect() {
  const sqlite = new Database(process.env.DATABASE_URL ?? "./fitness.db");
  // SQLite needs this per-connection, otherwise ON DELETE CASCADE is ignored.
  sqlite.pragma("foreign_keys = ON");
  sqlite.pragma("journal_mode = WAL");
  return sqlite;
}

// Reuse the connection across dev hot reloads instead of leaking one per reload.
const sqlite = globalForDb.sqlite ?? connect();
if (process.env.NODE_ENV !== "production") globalForDb.sqlite = sqlite;

export const db = drizzle(sqlite, { schema });

// Bring the file up to date on boot so `npm run dev` works on a fresh checkout.
migrate(db, { migrationsFolder: path.join(process.cwd(), "drizzle") });
