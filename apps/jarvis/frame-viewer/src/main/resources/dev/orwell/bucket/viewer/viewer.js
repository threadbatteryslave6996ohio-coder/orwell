'use strict';

/*
 * The frame viewer's page logic.
 *
 * Three things here are worth knowing before changing them:
 *
 * 1. Navigation is by frame id, never by array index. The server answers each step with the ids
 *    either side of the frame it returned, so the page never has to hold a list to know where it can
 *    go. That is what makes it correct while retention is deleting from the other end of the window:
 *    a step that lands on a frame that has since gone reads as "no older frame", not as an
 *    off-by-one into someone else's camera.
 *
 * 2. The URL is kept in sync with replaceState, not pushState. "Back" in this app means one frame
 *    older, and it is the button below the image. Pushing a history entry per frame would give the
 *    browser's own Back button the opposite meaning — return to the newer frame you just left — and
 *    two controls labelled back that disagree is worse than one. replaceState still leaves a URL
 *    that can be reloaded or shared.
 *
 * 3. The neighbours' images are prefetched. Stepping through history is only pleasant if the next
 *    frame is already in the browser cache, and the image route is served immutable with a year's
 *    max-age precisely so that works.
 */

const el = (id) => document.getElementById(id);

const view = {
  source: el('source'),
  live: el('live'),
  frame: el('frame'),
  empty: el('empty'),
  back: el('back'),
  forward: el('forward'),
  oldest: el('oldest'),
  latest: el('latest'),
  position: el('position'),
  status: el('status'),
  list: el('list'),
  listCount: el('listCount'),
  more: el('more'),
  openWarning: el('openWarning'),
  signOut: el('signOut'),
  upload: {
    panel: el('upload'),
    file: el('uploadFile'),
    source: el('uploadSource'),
    from: el('uploadFrom'),
    to: el('uploadTo'),
    fps: el('uploadFps'),
    start: el('uploadStart'),
    note: el('uploadNote'),
    status: el('uploadStatus'),
    bar: el('uploadBar'),
    fill: el('uploadBarFill'),
    list: el('uploads'),
  },
  meta: {
    id: el('metaId'),
    source: el('metaSource'),
    captured: el('metaCaptured'),
    ago: el('metaAgo'),
    index: el('metaIndex'),
    bytes: el('metaBytes'),
    sha: el('metaSha'),
  },
};

/** Everything the page knows. `current` is the frame on screen; the ids are what it may step to. */
const state = {
  source: '',
  current: null,
  olderId: null,
  newerId: null,
  nextBefore: null,
  hasMore: false,
  liveTimer: null,
};

const LIVE_INTERVAL_MILLIS = 2000;

// ---------------------------------------------------------------------------
// Talking to the server
// ---------------------------------------------------------------------------

async function getJson(path, parameters) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(parameters || {})) {
    if (value !== null && value !== undefined && value !== '') {
      query.set(key, value);
    }
  }
  const suffix = query.toString();
  const response = await fetch(suffix ? `${path}?${suffix}` : path, {
    headers: { Accept: 'application/json' },
  });
  if (response.status === 401) {
    // The session expired or was revoked. Reloading lands on the sign-in form, which the server
    // serves in place of this page — there is no second URL to send anyone to.
    signedOut();
    throw new Error('signed out');
  }
  const body = await response.json().catch(() => null);
  if (!response.ok || !body || body.success === false) {
    throw new Error((body && body.error) || `${response.status} from ${path}`);
  }
  return body;
}

/** Stops everything in flight and goes back to the sign-in form. Reload does both. */
let signingOut = false;
function signedOut() {
  if (signingOut) {
    return;   // several requests can 401 together; one reload is enough
  }
  signingOut = true;
  stopFollowing();
  location.reload();
}

function say(message, kind) {
  view.status.textContent = message || '';
  view.status.className = kind || '';
}

// ---------------------------------------------------------------------------
// Rendering one frame
// ---------------------------------------------------------------------------

/**
 * Swaps the image only once the new one has loaded, so a step shows the previous frame until the
 * next is ready rather than blanking the stage. After a prefetch that is the cache, so it is
 * immediate.
 */
function showImage(url) {
  const pending = new Image();
  pending.onload = () => {
    view.frame.src = url;
    view.frame.style.display = 'block';
    view.empty.style.display = 'none';
  };
  pending.onerror = () => {
    // The row is gone — almost always jarvis-retention-worker having trimmed the window while the
    // page was open. Nothing is broken, so this is a status line rather than an error.
    say('That frame is no longer stored; retention has trimmed it.', 'error');
  };
  pending.src = url;
}

function prefetch(frameId) {
  if (frameId === null || frameId === undefined) {
    return;
  }
  const pending = new Image();
  pending.src = `api/frames/${frameId}/image`;
}

function renderMeta(frame) {
  view.meta.id.textContent = frame.frameId;
  view.meta.source.textContent = frame.source;
  view.meta.captured.textContent = frame.capturedAt
    ? new Date(frame.capturedAt).toLocaleString()
    : '—';
  view.meta.ago.textContent = frame.capturedAt ? ago(frame.capturedAt) : '—';
  view.meta.index.textContent = frame.frameIndex === null ? 'not sent' : frame.frameIndex;
  view.meta.bytes.textContent = kilobytes(frame.bytes);
  view.meta.sha.textContent = frame.sha256 || '—';
}

function render(payload) {
  if (!payload.frame) {
    // Only reachable on an empty table or an empty source: a step that runs out of window is
    // prevented by the disabled button rather than by arriving here.
    view.frame.style.display = 'none';
    view.empty.style.display = 'block';
    view.position.textContent = '';
    view.back.disabled = true;
    view.forward.disabled = true;
    state.current = null;
    return;
  }

  state.current = payload.frame;
  state.olderId = payload.olderFrameId;
  state.newerId = payload.newerFrameId;

  showImage(payload.frame.imageUrl);
  renderMeta(payload.frame);
  view.position.textContent = `frame ${payload.position} of ${payload.total}`;
  view.back.disabled = payload.olderFrameId === null;
  view.forward.disabled = payload.newerFrameId === null;
  highlight(payload.frame.frameId);
  rememberInUrl();

  // The next step in either direction, fetched now so the button feels instant later.
  prefetch(payload.olderFrameId);
  prefetch(payload.newerFrameId);
}

function rememberInUrl() {
  const query = new URLSearchParams();
  if (state.source) {
    query.set('source', state.source);
  }
  if (state.current) {
    query.set('frame', state.current.frameId);
  }
  const suffix = query.toString();
  history.replaceState(null, '', suffix ? `?${suffix}` : location.pathname);
}

// ---------------------------------------------------------------------------
// Moving around
// ---------------------------------------------------------------------------

async function go(parameters, note) {
  try {
    say(note || 'loading…');
    render(await getJson('api/frame', { source: state.source, ...parameters }));
    say('');
  } catch (error) {
    say(error.message, 'error');
  }
}

const toLatest = () => go({});
const toOldest = () => go({ at: 'oldest' });
const toId = (frameId) => go({ id: frameId });

function back() {
  if (state.current === null || view.back.disabled) {
    return;
  }
  // Following the latest and stepping back are contradictory: whichever the operator asked for
  // last wins, and asking to go back is the clearer signal of the two.
  stopFollowing();
  go({ before: state.current.frameId });
}

function forward() {
  if (state.current === null || view.forward.disabled) {
    return;
  }
  go({ after: state.current.frameId });
}

// ---------------------------------------------------------------------------
// Following the latest frame
// ---------------------------------------------------------------------------

function stopFollowing() {
  view.live.checked = false;
  if (state.liveTimer !== null) {
    clearInterval(state.liveTimer);
    state.liveTimer = null;
  }
}

/**
 * Polls for the newest frame, and only re-renders when the id changes.
 *
 * <p>Polling rather than subscribing to the hub's SSE stream on purpose: this app is a reader of
 * stored frames and deliberately holds no connection to the hub. A poll every two seconds is two
 * index seeks; a subscription would mean receiving every frame of every camera to notice that one
 * of them changed.
 */
function startFollowing() {
  stopFollowing();
  view.live.checked = true;
  state.liveTimer = setInterval(async () => {
    try {
      const payload = await getJson('api/frame', { source: state.source });
      if (payload.frame && (!state.current || payload.frame.frameId !== state.current.frameId)) {
        render(payload);
        loadList(true);
      }
      say('following the latest frame', 'live');
    } catch (error) {
      say(error.message, 'error');
    }
  }, LIVE_INTERVAL_MILLIS);
  toLatest().then(() => say('following the latest frame', 'live'));
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

async function loadList(reset) {
  if (reset) {
    state.nextBefore = null;
    view.list.textContent = '';
  }
  try {
    // No limit sent: the server's own FRAME_VIEWER_PAGE_SIZE is both the default and the ceiling,
    // so naming a number here would be a second place to keep in step with it — and a 400 the day
    // an operator lowers it.
    const payload = await getJson('api/frames', {
      source: state.source,
      before: state.nextBefore,
    });
    for (const frame of payload.frames) {
      view.list.appendChild(row(frame));
    }
    state.nextBefore = payload.nextBefore;
    state.hasMore = payload.hasMore;
    view.more.hidden = !payload.hasMore;
    view.listCount.textContent = payload.hasMore
      ? `(${view.list.childElementCount}+)`
      : `(${view.list.childElementCount})`;
    if (state.current) {
      highlight(state.current.frameId);
    }
  } catch (error) {
    say(error.message, 'error');
  }
}

function row(frame) {
  const button = document.createElement('button');
  button.type = 'button';
  button.dataset.frameId = frame.frameId;

  const id = document.createElement('span');
  id.className = 'id';
  id.textContent = `#${frame.frameId}`;

  const when = document.createElement('span');
  when.className = 'when';
  // textContent, never innerHTML: source is a string a producer chose, and this page must not be
  // the place where it becomes markup.
  when.textContent = frame.capturedAt
    ? new Date(frame.capturedAt).toLocaleTimeString()
    : 'unknown time';

  const source = document.createElement('span');
  source.className = 'id';
  source.textContent = frame.source;

  button.append(id, when, source);
  button.addEventListener('click', () => {
    stopFollowing();
    toId(frame.frameId);
  });
  return button;
}

function highlight(frameId) {
  for (const button of view.list.children) {
    const current = Number(button.dataset.frameId) === Number(frameId);
    if (current) {
      button.setAttribute('aria-current', 'true');
      button.scrollIntoView({ block: 'nearest' });
    } else {
      button.removeAttribute('aria-current');
    }
  }
}

// ---------------------------------------------------------------------------
// Backfilling a video
// ---------------------------------------------------------------------------

/**
 * A `datetime-local` value is wall-clock with no zone. Interpreting it as local time and converting
 * to an instant is the whole reason this exists: `new Date('2026-08-17T10:00')` is 10:00 here, and
 * `toISOString()` turns that into the UTC the server wants.
 */
function instantFromLocal(value, label) {
  if (!value) {
    throw new Error(`${label} is required`);
  }
  const when = new Date(value);
  if (Number.isNaN(when.getTime())) {
    throw new Error(`${label} is not a time`);
  }
  return when.toISOString();
}

async function startUpload() {
  const file = view.upload.file.files[0];
  try {
    if (!file) {
      throw new Error('choose a video first');
    }
    const source = view.upload.source.value.trim();
    if (!source) {
      throw new Error('source is required — name the camera these frames belong to');
    }
    const from = instantFromLocal(view.upload.from.value, 'from');
    const to = instantFromLocal(view.upload.to.value, 'to');
    if (from >= to) {
      throw new Error('from must be before to');
    }

    const query = new URLSearchParams({ source, from, to });
    if (view.upload.fps.value) {
      query.set('fps', view.upload.fps.value);
    }

    view.upload.start.disabled = true;
    say('');
    uploadSays(`uploading ${kilobytes(file.size)}…`, '');
    // The File is the body. No FormData, no multipart: the server reads the request body as the
    // video and takes everything else from the query string.
    const response = await fetch(`api/uploads?${query}`, { method: 'POST', body: file });
    if (response.status === 401) {
      signedOut();
      throw new Error('signed out');
    }
    const body = await response.json().catch(() => null);
    if (!response.ok || !body || body.success === false) {
      throw new Error((body && body.error) || `${response.status} from the upload`);
    }
    await followUpload(body.upload.uploadId);
  } catch (error) {
    uploadSays(error.message, 'error');
    view.upload.start.disabled = false;
  }
}

/** Polls one backfill until it finishes, then folds what it created into the page. */
async function followUpload(uploadId) {
  view.upload.bar.hidden = false;
  for (;;) {
    let job;
    try {
      job = (await getJson(`api/uploads/${encodeURIComponent(uploadId)}`)).upload;
    } catch (error) {
      uploadSays(error.message, 'error');
      break;
    }

    const total = job.framesTotal || 0;
    const done = job.framesPushed || 0;
    view.upload.fill.style.width = total ? `${Math.round((done / total) * 100)}%` : '0';

    if (job.status === 'done') {
      const failed = job.framesFailed ? `, ${job.framesFailed} refused` : '';
      uploadSays(`stored ${done} frames as ${job.source}${failed}`, 'done');
      // The new frames change both the device list and the current source's history.
      await loadSources();
      if (job.firstFrameId !== null) {
        state.source = job.source;
        view.source.value = job.source;
        await toId(job.lastFrameId);
      }
      await loadList(true);
      break;
    }
    if (job.status === 'failed') {
      uploadSays(job.error || 'the backfill failed', 'error');
      break;
    }
    uploadSays(job.status === 'extracting'
      ? 'cutting the video into frames…'
      : `storing frames… ${done}/${total}`, '');
    await new Promise((resume) => setTimeout(resume, 1000));
  }
  view.upload.start.disabled = false;
  await loadUploads();
}

function uploadSays(message, kind) {
  view.upload.status.textContent = message;
  view.upload.status.className = kind || '';
}

/** The recent-backfills list, and whether the panel is usable at all. */
async function loadUploads() {
  let payload;
  try {
    payload = await getJson('api/uploads');
  } catch (error) {
    return;
  }
  if (!payload.uploadsEnabled) {
    // Disabled rather than hidden: a panel that is simply absent reads as a missing feature, and the
    // one thing worth saying is which setting turns it on.
    view.upload.start.disabled = true;
    view.upload.note.textContent =
      'Uploads are off — set FRAME_VIEWER_HUB_URL to the hub to turn them on.';
  } else if (!view.upload.fps.value) {
    view.upload.fps.value = payload.defaultFps;
  }

  view.upload.list.textContent = '';
  for (const job of payload.uploads) {
    const row = document.createElement('div');
    const when = job.from ? new Date(job.from).toLocaleString() : '';
    const name = document.createElement('b');
    name.textContent = job.source;
    row.append(name, document.createTextNode(
      ` · ${job.status} · ${job.framesPushed}/${job.framesTotal} frames · from ${when}`));
    if (job.error) {
      const why = document.createElement('div');
      why.textContent = job.error;
      row.appendChild(why);
    }
    view.upload.list.appendChild(row);
  }
  if (!payload.uploads.length) {
    const empty = document.createElement('div');
    empty.textContent = 'Nothing uploaded yet.';
    view.upload.list.appendChild(empty);
  }
}

// ---------------------------------------------------------------------------
// Formatting
// ---------------------------------------------------------------------------

function kilobytes(bytes) {
  if (bytes === null || bytes === undefined) {
    return '—';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

function ago(iso) {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000));
  if (seconds < 60) {
    return `${seconds}s ago`;
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m ${seconds % 60}s ago`;
  }
  const hours = Math.floor(seconds / 3600);
  return `${hours}h ${Math.floor((seconds % 3600) / 60)}m ago`;
}

// ---------------------------------------------------------------------------
// Wiring
// ---------------------------------------------------------------------------

async function loadSources() {
  const payload = await getJson('api/sources');
  // Rebuilt rather than diffed: "all sources" first, then a row per device with its frame count.
  view.source.textContent = '';
  view.source.appendChild(option('', 'all sources'));
  for (const source of payload.sources) {
    view.source.appendChild(option(source.source, `${source.source} (${source.frames})`));
  }
  view.source.value = state.source;
  // A source whose frames have all aged out is gone from the list, so a stale URL would select
  // nothing. Fall back to every source rather than showing an empty viewer.
  if (view.source.value !== state.source) {
    state.source = '';
    view.source.value = '';
  }
}

function option(value, label) {
  const element = document.createElement('option');
  element.value = value;
  element.textContent = label;
  return element;
}

view.back.addEventListener('click', back);
view.forward.addEventListener('click', forward);
view.oldest.addEventListener('click', () => {
  stopFollowing();
  toOldest();
});
view.latest.addEventListener('click', () => {
  stopFollowing();
  toLatest();
});
view.live.addEventListener('change', () => {
  if (view.live.checked) {
    startFollowing();
  } else {
    stopFollowing();
    say('');
  }
});
view.source.addEventListener('change', () => {
  state.source = view.source.value;
  stopFollowing();
  toLatest();
  loadList(true);
});
view.more.addEventListener('click', () => loadList(false));
view.upload.start.addEventListener('click', startUpload);
// A convenience worth having: choosing a file with no window filled in proposes the obvious one —
// ending now, starting the file's own duration ago — which is right for a clip just recorded and
// visibly wrong (and editable) for an archive.
view.upload.file.addEventListener('change', () => {
  if (view.upload.from.value || view.upload.to.value || !view.upload.file.files[0]) {
    return;
  }
  const video = document.createElement('video');
  video.preload = 'metadata';
  video.onloadedmetadata = () => {
    const seconds = Number.isFinite(video.duration) ? video.duration : 60;
    const end = new Date();
    view.upload.to.value = localValue(end);
    view.upload.from.value = localValue(new Date(end.getTime() - seconds * 1000));
    URL.revokeObjectURL(video.src);
  };
  video.src = URL.createObjectURL(view.upload.file.files[0]);
});

/** A Date as the `YYYY-MM-DDTHH:MM:SS` a datetime-local input wants, in local time. */
function localValue(when) {
  const pad = (value) => String(value).padStart(2, '0');
  return `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}`
    + `T${pad(when.getHours())}:${pad(when.getMinutes())}:${pad(when.getSeconds())}`;
}

document.addEventListener('keydown', (event) => {
  if (event.target instanceof HTMLInputElement || event.target instanceof HTMLSelectElement) {
    return;
  }
  const moves = { ArrowLeft: back, ArrowRight: forward, Home: () => { stopFollowing(); toLatest(); },
                  End: () => { stopFollowing(); toOldest(); } };
  const move = moves[event.key];
  if (move) {
    event.preventDefault();
    move();
  }
});

// The URL decides where it opens: a shared or reloaded link lands on the same frame, and a bare
// one lands on the latest, which is what somebody arriving without a link wants to see.
(async function open() {
  const parameters = new URLSearchParams(location.search);
  state.source = parameters.get('source') || '';
  // /health is the one unguarded route, and it says whether anything is guarding the others. The
  // page reaching this point already means the visitor is in, so this only picks which of the two
  // reminders to show: the warning banner, or the way out.
  try {
    const health = await getJson('health');
    view.openWarning.hidden = health.authRequired === true;
    view.signOut.hidden = health.authRequired !== true;
  } catch (error) {
    // Not worth a visible error: it decides which reminder to show, not whether the page works.
  }
  try {
    await loadSources();
  } catch (error) {
    say(error.message, 'error');
  }
  const frameId = parameters.get('frame');
  await (frameId ? toId(frameId) : toLatest());
  await loadList(true);
  await loadUploads();
})();
