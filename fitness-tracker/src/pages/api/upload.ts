import { randomUUID } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, unlink } from "node:fs/promises";
import path from "node:path";
import { pipeline } from "node:stream/promises";
import type { NextApiRequest, NextApiResponse } from "next";

import {
  CONTENT_TYPES,
  UPLOAD_DIR,
  VIDEO_URL_PREFIX,
} from "~/server/uploads";

/** The raw file is streamed straight through, so keep Next's parser out of it. */
export const config = { api: { bodyParser: false } };

const MAX_BYTES = 200 * 1024 * 1024;

export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse<{ url: string } | { error: string }>,
) {
  if (req.method !== "POST") {
    res.setHeader("Allow", "POST");
    return res.status(405).json({ error: "Use POST." });
  }

  const given = typeof req.query.name === "string" ? req.query.name : "";
  const ext = path.extname(given).toLowerCase();
  if (!(ext in CONTENT_TYPES)) {
    return res.status(400).json({
      error: `Unsupported video type "${ext || given}". Allowed: ${Object.keys(
        CONTENT_TYPES,
      ).join(", ")}.`,
    });
  }

  const filename = `${randomUUID()}${ext}`;
  const target = path.join(UPLOAD_DIR, filename);
  await mkdir(UPLOAD_DIR, { recursive: true });

  let written = 0;
  req.on("data", (chunk: Buffer) => {
    written += chunk.length;
    if (written > MAX_BYTES) req.destroy(new Error("Video is too large."));
  });

  try {
    await pipeline(req, createWriteStream(target));
  } catch (error) {
    // A half-written file is worse than none — the page would show a broken clip.
    await unlink(target).catch(() => undefined);
    const message = error instanceof Error ? error.message : "Upload failed.";
    return res.status(written > MAX_BYTES ? 413 : 500).json({ error: message });
  }

  return res.status(201).json({ url: `${VIDEO_URL_PREFIX}${filename}` });
}
