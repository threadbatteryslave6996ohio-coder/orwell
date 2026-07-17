# Stream worker — outstanding review follow-ups

These are the remaining **low-severity hardening items** from the code review of the
streaming/proxy unification (folding the standalone stream worker into the
`jarvis-bucket-proxy` app as `--mode=stream-worker`). None are correctness regressions or
release blockers — they only bite on pathological or large-frame input that the
default 640px-width MJPEG pipeline does not produce. Captured here so they aren't lost.

Source: `dev.orwell.bucket.proxy.streaming.AnalysisWorker` and `scripts/analyze_stream.sh`.

## 1. Oversize-frame guard discards the whole buffer instead of resyncing

**Where:** `AnalysisWorker.pollFrame()`, the `size - frameStart > MAX_FRAME_BYTES` branch
(`compact(size)`).

**Issue:** When an in-progress frame exceeds `MAX_FRAME_BYTES` (32 MB) without an end
marker, the guard drops the *entire* buffer. If a stray `0xFFD8` (SOI) with no matching
`0xFFD9` is followed within that 32 MB window by valid complete frames, those recoverable
frames are thrown away too.

**Fix idea:** `compact(frameStart + 2)` instead of `compact(size)`, so the scanner
resyncs to the next SOI after the stray one rather than nuking the whole window.

**Severity:** Low (pathological/corrupt input only).

## 2. `buf` never shrinks (memory high-water-mark retained for worker lifetime)

**Where:** `AnalysisWorker` frame iterator — `ensureCapacity()` grows `buf` by doubling
but nothing ever shrinks it.

**Issue:** A one-off large/corrupt frame can push `buf` to ~64 MB. After it is
consumed/dropped, `size` returns to a few KB but `buf` keeps the 64 MB capacity for the
worker's entire (continuously running) lifetime.

**Fix idea:** Shrink `buf` back toward its initial size when `size` falls well below
`buf.length`.

**Severity:** Low (at width 640, frames are tens of KB, so `buf` stays ~64 KB and this
never triggers).

## 3. Potential busy-spin if `InputStream.read()` returns 0 repeatedly

**Where:** `AnalysisWorker` iterator `hasNext()` — `int read = input.read(readBuffer);`

**Issue:** A 0-byte read appends nothing but never sets `finished`, so a stream that
returns 0 forever would spin at 100% CPU with no progress.

**Fix idea:** Guard/bound the `read == 0` case.

**Severity:** Very low / theoretical. Standard streams (stdin pipe, `ByteArrayInputStream`)
never return 0; this pattern is pre-existing.

## 4. `set -u` + empty override array on old bash

**Where:** `scripts/analyze_stream.sh` — `"${WORKER_MODE_ARGS[@]}"` expansion.

**Issue:** Under `set -u`, expanding an empty array errors ("unbound variable") on
bash < 4.4.

**Fix idea:** Latent only — the `${STREAM_ANALYSIS_WORKER_MODE:---mode=stream-worker}`
default keeps the array non-empty today. Revisit if the default is ever removed.

**Severity:** Very low (masked by the non-empty default).
