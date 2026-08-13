import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import path from "node:path";
import type { NextApiRequest, NextApiResponse } from "next";

import { CONTENT_TYPES, STORED_NAME, UPLOAD_DIR } from "~/server/uploads";

export const config = { api: { responseLimit: false } };

export default async function handler(
  req: NextApiRequest,
  res: NextApiResponse,
) {
  const name = typeof req.query.name === "string" ? req.query.name : "";
  const contentType = CONTENT_TYPES[path.extname(name).toLowerCase()];
  if (!STORED_NAME.test(name) || !contentType) {
    return res.status(404).end();
  }

  const file = path.join(UPLOAD_DIR, name);
  let size: number;
  try {
    size = (await stat(file)).size;
  } catch {
    return res.status(404).end();
  }

  res.setHeader("Content-Type", contentType);
  res.setHeader("Accept-Ranges", "bytes");
  // The name is a uuid, so the bytes behind it never change.
  res.setHeader("Cache-Control", "public, max-age=31536000, immutable");

  // Browsers seek by asking for a byte range; without this the scrubber is dead.
  const match = /^bytes=(\d*)-(\d*)$/.exec(req.headers.range ?? "");
  if (match) {
    const start = match[1] ? Number(match[1]) : 0;
    const end = match[2] ? Math.min(Number(match[2]), size - 1) : size - 1;
    if (start >= size || end < start) {
      res.setHeader("Content-Range", `bytes */${size}`);
      return res.status(416).end();
    }
    res.setHeader("Content-Range", `bytes ${start}-${end}/${size}`);
    res.setHeader("Content-Length", end - start + 1);
    res.status(206);
    return void createReadStream(file, { start, end }).pipe(res);
  }

  res.setHeader("Content-Length", size);
  res.status(200);
  createReadStream(file).pipe(res);
}
