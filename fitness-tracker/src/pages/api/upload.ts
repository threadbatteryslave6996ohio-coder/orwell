import { randomUUID } from "node:crypto";
import { once } from "node:events";
import { createWriteStream } from "node:fs";
import { mkdir, unlink } from "node:fs/promises";
import path from "node:path";
import type { NextApiRequest, NextApiResponse } from "next";

import {
  CONTENT_TYPES,
  UPLOAD_DIR,
  VIDEO_URL_PREFIX,
} from "~/server/uploads";

/** The raw file is streamed straight through, so keep Next's parser out of it. */
export const config = { api: { bodyParser: false } };

const MAX_BYTES = 200 * 1024 * 1024;
const TOO_LARGE = "Video is too large.";

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

  // The cheap rejection, and the one that covers a browser file picker: refuse on the declared
  // length before a byte is read or a file is created.
  const declared = Number(req.headers["content-length"]);
  if (Number.isFinite(declared) && declared > MAX_BYTES) {
    return res.status(413).json({ error: TOO_LARGE });
  }

  const filename = `${randomUUID()}${ext}`;
  const target = path.join(UPLOAD_DIR, filename);
  await mkdir(UPLOAD_DIR, { recursive: true });

  const sink = createWriteStream(target);
  let written = 0;
  let tooLarge = false;

  // A write stream with no persistent 'error' listener turns a disk-full, EACCES or EPIPE into an
  // *uncaught* exception, which takes the whole Next process down rather than returning a 500.
  // once(sink, "drain") attaches one only while we are waiting for drain, which is the uncommon
  // path — on the common path there was nothing listening at all. Held in an object so the checks
  // below are not narrowed away: nothing in this function body assigns it.
  const failure: { error: Error | null } = { error: null };
  sink.on("error", (error: Error) => {
    failure.error ??= error;
  });

  try {
    // Consumed by hand rather than with pipeline(), and the request stream is never destroyed.
    // Destroying it tears down the socket, so the 413 below could never be written — the uploader
    // saw ECONNRESET and this handler believed it had answered. Past the cap we stop writing to
    // disk and keep draining, so the file is bounded and the client still gets a real status.
    for await (const chunk of req) {
      if (failure.error) {
        throw failure.error;
      }
      written += (chunk as Buffer).length;
      if (tooLarge) {
        continue;
      }
      if (written > MAX_BYTES) {
        tooLarge = true;
        sink.destroy();
        continue;
      }
      if (!sink.write(chunk)) {
        await once(sink, "drain");
      }
    }
    if (failure.error) {
      throw failure.error;
    }
    if (!tooLarge) {
      await new Promise<void>((resolve, reject) =>
        sink.end((error?: Error | null) => (error ? reject(error) : resolve())),
      );
    }
  } catch (error) {
    sink.destroy();
    // A half-written file is worse than none — the page would show a broken clip.
    await unlink(target).catch(() => undefined);
    const message = error instanceof Error ? error.message : "Upload failed.";
    return res.status(500).json({ error: message });
  }

  if (tooLarge) {
    await unlink(target).catch(() => undefined);
    return res.status(413).json({ error: TOO_LARGE });
  }

  return res.status(201).json({ url: `${VIDEO_URL_PREFIX}${filename}` });
}
