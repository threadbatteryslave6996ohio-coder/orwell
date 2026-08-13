import path from "node:path";

/**
 * Videos are written at runtime, so they cannot live under `public/` — in a
 * production build Next only serves the files that existed when it started.
 * They go in a plain directory and are read back through /api/video/<name>.
 */
export const UPLOAD_DIR = path.resolve(process.env.UPLOAD_DIR ?? "./uploads");

export const VIDEO_URL_PREFIX = "/api/video/";

export const CONTENT_TYPES: Record<string, string> = {
  ".mp4": "video/mp4",
  ".m4v": "video/mp4",
  ".mov": "video/quicktime",
  ".webm": "video/webm",
  ".ogg": "video/ogg",
};

/** Only names this route itself minted — keeps `..` out of the path. */
export const STORED_NAME = /^[0-9a-f-]{36}\.[a-z0-9]{2,5}$/;
