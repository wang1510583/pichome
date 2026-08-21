import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import os from "node:os";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const HOST = process.env.SYNC_HOST || "127.0.0.1";
const PORT = Number(process.env.SYNC_PORT || 43127);
const LIBRARY_ID = process.env.SYNC_LIBRARY_ID || "zOGqBF";
const LIBRARY_NAME = process.env.SYNC_LIBRARY_NAME || "参考库";
const LIBRARY_PATH = process.env.SYNC_LIBRARY_PATH || "/mnt/photobackup/eagle-libraries/参考库.library";
const SIGNING_KEY = process.env.SYNC_SIGNING_KEY || crypto.randomBytes(32).toString("hex");
const IMAGES_PATH = path.join(LIBRARY_PATH, "images");
const LIBRARY_METADATA_PATH = path.join(LIBRARY_PATH, "metadata.json");
const LIBRARY_MTIME_PATH = path.join(LIBRARY_PATH, "mtime.json");
const WRITE_HELPER = fileURLToPath(new URL("./pichome-write.php", import.meta.url));
const STATE_FILE_PATH = process.env.SYNC_STATE_FILE || "/var/lib/pichome-eagle-sync/sync-state.json";
const execFileAsync = promisify(execFile);
const MAX_UPLOAD_BYTES = 8 * 1024 * 1024 * 1024;
const ALLOWED_UPLOAD_EXTENSIONS = new Set(["jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "mp4", "mov", "mkv", "webm", "avi", "m4v"]);

let cache = { fingerprint: "", items: [], byId: new Map(), folders: [] };
let uploadQueue = Promise.resolve();
const eventClients = new Set();
let changeRevision = Date.now();
let changeBroadcastTimer = null;
let fallbackFingerprint = "";

function broadcastLibraryChange(reason = "library-change") {
  clearTimeout(changeBroadcastTimer);
  changeBroadcastTimer = setTimeout(() => {
    try { fallbackFingerprint = fingerprint(); } catch (_) {}
    changeRevision = Date.now();
    const message = `event: change\nid: ${changeRevision}\ndata: ${JSON.stringify({ libraryId: LIBRARY_ID, revision: changeRevision, reason })}\n\n`;
    for (const client of [...eventClients]) {
      try { client.write(message); } catch (_) { eventClients.delete(client); }
    }
  }, 700);
}

function sendEventStream(req, res) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    "Connection": "keep-alive",
    "Access-Control-Allow-Origin": "*",
    "X-Accel-Buffering": "no"
  });
  res.write(`event: ready\ndata: ${JSON.stringify({ libraryId: LIBRARY_ID, revision: changeRevision })}\n\n`);
  eventClients.add(res);
  const heartbeat = setInterval(() => {
    try { res.write(`: heartbeat ${Date.now()}\n\n`); } catch (_) {}
  }, 25000);
  req.on("close", () => {
    clearInterval(heartbeat);
    eventClients.delete(res);
  });
}

function startLibraryWatchers() {
  const watchTargets = [LIBRARY_METADATA_PATH, LIBRARY_MTIME_PATH, IMAGES_PATH];
  for (const target of watchTargets) {
    try {
      const watcher = fs.watch(target, { persistent: false }, () => broadcastLibraryChange(path.basename(target)));
      watcher.on("error", (error) => console.warn(JSON.stringify({ event: "watch-error", target, error: error.message })));
    } catch (error) {
      console.warn(JSON.stringify({ event: "watch-unavailable", target, error: error.message }));
    }
  }
  try { fallbackFingerprint = fingerprint(); } catch (_) {}
  setInterval(() => {
    try {
      const next = fingerprint();
      if (fallbackFingerprint && next !== fallbackFingerprint) broadcastLibraryChange("fingerprint-fallback");
      fallbackFingerprint = next;
    } catch (_) {}
  }, 2000).unref();
}

fs.mkdirSync(path.dirname(STATE_FILE_PATH), { recursive: true });
function loadSyncStore() {
  try {
    const parsed = JSON.parse(fs.readFileSync(STATE_FILE_PATH, "utf8"));
    return { version: 1, nextSeq: 1, items: {}, events: [], ...parsed };
  } catch (_) {
    return { version: 1, nextSeq: 1, items: {}, events: [] };
  }
}

let syncStore = loadSyncStore();
let syncStoreDirty = false;

function persistSyncStore() {
  if (!syncStoreDirty) return;
  const temporary = `${STATE_FILE_PATH}.tmp.${process.pid}`;
  const descriptor = fs.openSync(temporary, "w", 0o660);
  try {
    fs.writeFileSync(descriptor, JSON.stringify(syncStore));
    fs.fsyncSync(descriptor);
  } finally {
    fs.closeSync(descriptor);
  }
  fs.renameSync(temporary, STATE_FILE_PATH);
  syncStoreDirty = false;
}

function normalizedEventTime(value, fallback) {
  const time = Number(value);
  const now = Date.now();
  if (!Number.isFinite(time) || time <= 0 || Math.abs(time - now) > 365 * 24 * 60 * 60 * 1000) return Number(fallback) || now;
  return Math.round(time);
}

function observeRemoteState(item, options = {}) {
  const folders = JSON.stringify([...new Set((item.folders || []).map(String))].sort());
  const deleted = item.isDeleted ? 1 : 0;
  const stateKey = `${LIBRARY_ID}:${item.id}`;
  const previous = syncStore.items[stateKey];
  if (!previous) {
    syncStore.items[stateKey] = {
      remoteVersion: String(item.version || "missing"),
      remoteModifiedAt: Number(item.modifiedAt) || 0,
      remoteDeleted: deleted,
      remoteFolders: folders,
      lastEvent: null
    };
    syncStoreDirty = true;
    return null;
  }
  const changed = String(previous.remoteVersion) !== String(item.version || "missing") ||
    Number(previous.remoteDeleted) !== deleted || String(previous.remoteFolders) !== folders;
  if (!changed) return previous.lastEvent || null;

  let kind = options.kind;
  if (!kind) {
    if (!previous.remoteDeleted && deleted) kind = "delete";
    else if (previous.remoteDeleted && !deleted) kind = "restore";
    else kind = "folders";
  }
  const eventTime = normalizedEventTime(options.eventTime, item.modifiedAt);
  const event = {
    seq: Number(syncStore.nextSeq++),
    libraryId: LIBRARY_ID,
    itemId: item.id,
    side: options.side || "pichome",
    kind,
    eventTime,
    serverTime: Date.now(),
    clientId: String(options.clientId || "").slice(0, 160) || null,
    state: { version: item.version, modifiedAt: item.modifiedAt, isDeleted: Boolean(item.isDeleted), folders: JSON.parse(folders) }
  };
  syncStore.events.push(event);
  if (syncStore.events.length > 20000) syncStore.events.splice(0, syncStore.events.length - 20000);
  syncStore.items[stateKey] = {
    remoteVersion: String(item.version || "missing"),
    remoteModifiedAt: Number(item.modifiedAt) || 0,
    remoteDeleted: deleted,
    remoteFolders: folders,
    lastEvent: { seq: event.seq, side: event.side, kind: event.kind, eventTime: event.eventTime, serverTime: event.serverTime, clientId: event.clientId }
  };
  syncStoreDirty = true;
  return syncStore.items[stateKey].lastEvent;
}

function json(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-Pichome-Metadata",
    "Access-Control-Allow-Methods": "GET, POST, PATCH, OPTIONS"
  });
  res.end(body);
}

function flattenFolders(folders, parent = null, output = []) {
  for (const [position, folder] of (folders || []).entries()) {
    output.push({
      id: String(folder.id || ""),
      name: String(folder.name || "未命名文件夹"),
      description: String(folder.description || ""),
      parent,
      position
    });
    flattenFolders(folder.children, String(folder.id || ""), output);
  }
  return output;
}

function safeOriginalFile(itemDir, metadata) {
  const exact = path.join(itemDir, `${metadata.name}.${metadata.ext}`);
  if (fs.existsSync(exact) && fs.statSync(exact).isFile()) return exact;

  const files = fs.readdirSync(itemDir, { withFileTypes: true })
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .filter((name) => name !== "metadata.json")
    .filter((name) => !/_thumbnail\.[^.]+$/i.test(name) && !/_preview\.[^.]+$/i.test(name));
  const extMatch = files.find((name) => path.extname(name).slice(1).toLowerCase() === String(metadata.ext || "").toLowerCase());
  return extMatch ? path.join(itemDir, extMatch) : null;
}

function fingerprint() {
  const libraryStat = fs.statSync(LIBRARY_METADATA_PATH);
  const mtimeStat = fs.existsSync(LIBRARY_MTIME_PATH) ? fs.statSync(LIBRARY_MTIME_PATH) : libraryStat;
  return `${libraryStat.mtimeMs}:${libraryStat.size}:${mtimeStat.mtimeMs}:${mtimeStat.size}`;
}

function refreshCache(force = false) {
  const nextFingerprint = fingerprint();
  if (!force && cache.fingerprint === nextFingerprint) return cache;

  const libraryMetadata = JSON.parse(fs.readFileSync(LIBRARY_METADATA_PATH, "utf8"));
  const items = [];
  const byId = new Map();

  for (const entry of fs.readdirSync(IMAGES_PATH, { withFileTypes: true })) {
    if (!entry.isDirectory() || !entry.name.endsWith(".info")) continue;
    const itemDir = path.join(IMAGES_PATH, entry.name);
    const metadataPath = path.join(itemDir, "metadata.json");
    if (!fs.existsSync(metadataPath)) continue;

    try {
      const metadata = JSON.parse(fs.readFileSync(metadataPath, "utf8"));
      if (!metadata.id || metadata.isDeleted) continue;
      const originalPath = safeOriginalFile(itemDir, metadata);
      if (!originalPath) continue;
      const stat = fs.statSync(originalPath);
      const item = {
        id: String(metadata.id),
        name: String(metadata.name || path.basename(originalPath, path.extname(originalPath))),
        ext: String(metadata.ext || path.extname(originalPath).slice(1)),
        tags: Array.isArray(metadata.tags) ? metadata.tags.map(String) : [],
        folders: Array.isArray(metadata.folders) ? metadata.folders.map(String) : [],
        annotation: String(metadata.annotation || ""),
        website: String(metadata.url || ""),
        star: Number(metadata.star || 0),
        size: Number(metadata.size || stat.size),
        modifiedAt: Number(metadata.lastModified || metadata.modificationTime || metadata.mtime || stat.mtimeMs),
        importedAt: Number(metadata.btime || metadata.modificationTime || stat.birthtimeMs),
        originalPath
      };
      items.push(item);
      byId.set(item.id, item);
    } catch (error) {
      console.warn(JSON.stringify({ event: "metadata-skip", directory: entry.name, error: error.message }));
    }
  }

  items.sort((a, b) => b.importedAt - a.importedAt || a.id.localeCompare(b.id));
  cache = {
    fingerprint: nextFingerprint,
    items,
    byId,
    folders: flattenFolders(libraryMetadata.folders)
  };
  console.log(JSON.stringify({ event: "index-refreshed", items: items.length, folders: cache.folders.length }));
  return cache;
}

function publicItem(item, origin) {
  const expires = Math.floor(Date.now() / 1000) + 3600;
  const payload = `${LIBRARY_ID}:${item.id}:${expires}`;
  const signature = crypto.createHmac("sha256", SIGNING_KEY).update(payload).digest("hex");
  return {
    id: item.id,
    name: item.name,
    ext: item.ext,
    tags: item.tags,
    folders: item.folders,
    annotation: item.annotation,
    website: item.website,
    star: item.star,
    size: item.size,
    modifiedAt: item.modifiedAt,
    importedAt: item.importedAt,
    downloadUrl: `${origin}/api/v1/libraries/${LIBRARY_ID}/items/${encodeURIComponent(item.id)}/file?expires=${expires}&signature=${signature}`
  };
}

function isValidSignature(itemId, expires, signature) {
  const expiration = Number(expires);
  if (!Number.isFinite(expiration) || expiration < Math.floor(Date.now() / 1000)) return false;
  const expected = crypto.createHmac("sha256", SIGNING_KEY)
    .update(`${LIBRARY_ID}:${itemId}:${expiration}`)
    .digest("hex");
  const given = String(signature || "");
  return given.length === expected.length && crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
}

function requestOrigin(req) {
  const protocol = String(req.headers["x-forwarded-proto"] || "https").split(",")[0].trim();
  return `${protocol}://${req.headers.host}`;
}

function sendFile(req, res, item) {
  const stat = fs.statSync(item.originalPath);
  const range = req.headers.range;
  const encodedName = encodeURIComponent(`${item.name}.${item.ext}`).replace(/['()]/g, escape);
  const baseHeaders = {
    "Content-Type": "application/octet-stream",
    "Content-Disposition": `attachment; filename*=UTF-8''${encodedName}`,
    "Accept-Ranges": "bytes",
    "Cache-Control": "private, max-age=300",
    "Access-Control-Allow-Origin": "*"
  };

  if (!range) {
    res.writeHead(200, { ...baseHeaders, "Content-Length": stat.size });
    return fs.createReadStream(item.originalPath).pipe(res);
  }

  const match = /^bytes=(\d*)-(\d*)$/.exec(range);
  if (!match) {
    res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
    return res.end();
  }
  const start = match[1] ? Number(match[1]) : 0;
  const end = match[2] ? Math.min(Number(match[2]), stat.size - 1) : stat.size - 1;
  if (start > end || start >= stat.size) {
    res.writeHead(416, { "Content-Range": `bytes */${stat.size}` });
    return res.end();
  }
  res.writeHead(206, {
    ...baseHeaders,
    "Content-Length": end - start + 1,
    "Content-Range": `bytes ${start}-${end}/${stat.size}`
  });
  fs.createReadStream(item.originalPath, { start, end }).pipe(res);
}

function parseUploadMetadata(req) {
  const encoded = String(req.headers["x-pichome-metadata"] || "");
  if (!encoded || encoded.length > 16384) throw new Error("缺少上传元数据");
  let metadata;
  try {
    metadata = JSON.parse(Buffer.from(encoded, "base64url").toString("utf8"));
  } catch (_) {
    throw new Error("上传元数据无效");
  }
  const rawName = path.basename(String(metadata.filename || "upload"));
  const ext = path.extname(rawName).slice(1).toLowerCase();
  if (!ALLOWED_UPLOAD_EXTENSIONS.has(ext)) throw new Error("只允许上传常见图片和视频文件");
  const stem = path.basename(rawName, path.extname(rawName)).replace(/[\\/:*?"<>|\x00-\x1f]/g, "_").trim().slice(0, 180) || "upload";
  return {
    filename: `${stem}.${ext}`,
    folderId: String(metadata.folderId || "").replace(/[^A-Za-z0-9_-]/g, "").slice(0, 80)
  };
}

async function receiveUpload(req, res) {
  let metadata;
  try {
    metadata = parseUploadMetadata(req);
  } catch (error) {
    req.resume();
    return json(res, 400, { error: "invalid_upload", message: error.message });
  }

  const declaredSize = Number(req.headers["content-length"] || 0);
  if (declaredSize > MAX_UPLOAD_BYTES) {
    req.resume();
    return json(res, 413, { error: "upload_too_large" });
  }

  const tempPath = path.join(os.tmpdir(), `pichome-sync-upload-${crypto.randomUUID()}.${path.extname(metadata.filename).slice(1)}`);
  let received = 0;
  try {
    await new Promise((resolve, reject) => {
      const output = fs.createWriteStream(tempPath, { flags: "wx", mode: 0o660 });
      req.on("data", (chunk) => {
        received += chunk.length;
        if (received > MAX_UPLOAD_BYTES) req.destroy(new Error("upload_too_large"));
      });
      req.on("error", reject);
      output.on("error", reject);
      output.on("finish", resolve);
      req.pipe(output);
    });
    if (!received) throw new Error("上传内容为空");

    const operation = async () => {
      // Pichome's Eagle importer can fail while creating the initial folder
      // relation. Create the item unclassified first, then use our tested
      // mutation path to attach the requested folder exactly once.
      const result = await invokeWriteHelper(["upload", LIBRARY_ID, tempPath, metadata.filename, ""]);
      if (!result.ok || !result.sourceId) throw new Error(result.error || "Pichome 未返回素材 ID");
      if (metadata.folderId) {
        const stateResult = await invokeWriteHelper(["state", LIBRARY_ID]);
        const createdState = (stateResult.items || []).find((item) => String(item.id) === String(result.sourceId));
        if (!createdState) throw new Error("素材已写入，但无法读取新素材状态");
        const mutationPayload = Buffer.from(JSON.stringify({
          baseVersion: createdState.version,
          folders: [metadata.folderId],
          isDeleted: false,
          clientTime: Date.now(),
          clientId: "eagle-upload"
        }), "utf8").toString("base64url");
        const mutationResult = await invokeWriteHelper(["mutate", LIBRARY_ID, result.sourceId, mutationPayload]);
        if (!mutationResult.ok) throw new Error(mutationResult.error || "新素材分类关联失败");
      }
      const current = refreshCache(true);
      const item = current.byId.get(result.sourceId);
      if (!item) throw new Error("素材已写入，但同步索引尚未就绪");
      return { item, folderMapped: Boolean(result.folderMapped) };
    };
    const queued = uploadQueue.then(operation, operation);
    uploadQueue = queued.catch(() => {});
    const result = await queued;
    return json(res, 201, { ok: true, item: publicItem(result.item, requestOrigin(req)), folderMapped: result.folderMapped });
  } catch (error) {
    console.error(JSON.stringify({ event: "upload-error", error: error.stack || error.message }));
    if (!res.headersSent) return json(res, error.message === "upload_too_large" ? 413 : 500, { error: "upload_failed", message: error.message });
  } finally {
    try { fs.unlinkSync(tempPath); } catch (_) {}
  }
}

function parseHelperOutput(stdout) {
  const lines = String(stdout || "").trim().split(/\r?\n/).filter(Boolean);
  try { return JSON.parse(lines.at(-1) || "{}"); }
  catch (_) { throw new Error("Pichome 写入器返回了无效结果"); }
}

async function invokeWriteHelper(args) {
  try {
    const { stdout } = await execFileAsync("/usr/bin/php", [WRITE_HELPER, ...args], {
      timeout: 30 * 60 * 1000,
      maxBuffer: 32 * 1024 * 1024
    });
    return parseHelperOutput(stdout);
  } catch (error) {
    let result = null;
    try { result = parseHelperOutput(error.stdout || ""); } catch (_) {}
    if (result?.conflict) return result;
    const stderr = String(error.stderr || "").trim();
    const stdout = String(error.stdout || "").trim();
    throw new Error(result?.error || stderr || stdout || error.message || "Pichome 写入失败");
  }
}

async function readJsonBody(req, maxBytes = 64 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > maxBytes) throw new Error("请求内容过大");
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"); }
  catch (_) { throw new Error("JSON 请求无效"); }
}

async function sendLibraryState(res) {
  try {
    const result = await invokeWriteHelper(["state", LIBRARY_ID]);
    if (!result.ok) throw new Error(result.error || "读取同步状态失败");
    const current = refreshCache();
    const presentIds = new Set();
    const items = (result.items || []).map((item) => {
      presentIds.add(String(item.id));
      const syncEvent = observeRemoteState(item);
      const cached = current.byId.get(String(item.id));
      return { ...item, importedAt: Number(cached?.importedAt || 0), syncEvent, tombstone: false };
    });
    const prefix = `${LIBRARY_ID}:`;
    for (const [stateKey, stored] of Object.entries(syncStore.items || {})) {
      if (!stateKey.startsWith(prefix) || !stored?.remoteDeleted) continue;
      const itemId = stateKey.slice(prefix.length);
      if (!itemId || presentIds.has(itemId)) continue;
      let folders = [];
      try { folders = JSON.parse(stored.remoteFolders || "[]"); } catch (_) {}
      const syncEvent = stored.lastEvent || {
        seq: 0,
        side: "pichome",
        kind: "delete",
        eventTime: Number(stored.remoteModifiedAt) || 0,
        serverTime: Number(stored.remoteModifiedAt) || 0,
        clientId: null
      };
      items.push({
        id: itemId,
        folders,
        isDeleted: true,
        modifiedAt: Number(stored.remoteModifiedAt) || 0,
        importedAt: 0,
        version: String(stored.remoteVersion || `tombstone:${syncEvent.seq || 0}`),
        syncEvent,
        tombstone: true
      });
    }
    persistSyncStore();
    return json(res, 200, { items, generatedAt: Date.now() });
  } catch (error) {
    return json(res, 500, { error: "state_failed", message: error.message });
  }
}

async function mutateItem(req, res, sourceId) {
  try {
    const payload = await readJsonBody(req);
    const safePayload = {
      baseVersion: String(payload.baseVersion || ""),
      folders: Array.isArray(payload.folders) ? payload.folders.map(String) : undefined,
      isDeleted: typeof payload.isDeleted === "boolean" ? payload.isDeleted : undefined,
      clientTime: normalizedEventTime(payload.clientTime, Date.now()),
      clientId: String(payload.clientId || "eagle-client").slice(0, 160)
    };
    if (safePayload.folders === undefined && safePayload.isDeleted === undefined) {
      return json(res, 400, { error: "empty_mutation", message: "没有可应用的变更" });
    }
    const encoded = Buffer.from(JSON.stringify(safePayload), "utf8").toString("base64url");
    const operation = () => invokeWriteHelper(["mutate", LIBRARY_ID, sourceId, encoded]);
    const queued = uploadQueue.then(operation, operation);
    uploadQueue = queued.catch(() => {});
    const result = await queued;
    if (result.conflict) return json(res, 409, result);
    if (!result.ok) throw new Error(result.error || "素材变更失败");
    const eventKind = safePayload.isDeleted === true ? "delete" : safePayload.isDeleted === false ? "restore" : "folders";
    const syncEvent = observeRemoteState(result.state, {
      side: "eagle",
      kind: eventKind,
      eventTime: safePayload.clientTime,
      clientId: safePayload.clientId
    });
    persistSyncStore();
    refreshCache(true);
    return json(res, 200, { ...result, state: { ...result.state, syncEvent } });
  } catch (error) {
    return json(res, 500, { error: "mutation_failed", message: error.message });
  }
}

async function reconcileSourceFromPichome(res) {
  try {
    const operation = () => invokeWriteHelper(["reconcile", LIBRARY_ID]);
    const queued = uploadQueue.then(operation, operation);
    uploadQueue = queued.catch(() => {});
    const result = await queued;
    if (!result.ok) throw new Error(result.error || "Pichome 索引校准失败");
    refreshCache(true);
    if (Number(result.changed) > 0) broadcastLibraryChange("pichome-index-reconciled");
    return json(res, 200, result);
  } catch (error) {
    return json(res, 500, { error: "reconcile_failed", message: error.message });
  }
}

const server = http.createServer(async (req, res) => {
  const started = Date.now();
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const identity = req.headers["tailscale-user-login"] || req.headers["x-forwarded-for"] || "tailnet-client";
  res.on("finish", () => {
    console.log(JSON.stringify({ event: "request", method: req.method, path: url.pathname, status: res.statusCode, ms: Date.now() - started, identity, userAgent: req.headers["user-agent"] || "" }));
  });

  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type, X-Pichome-Metadata",
      "Access-Control-Allow-Methods": "GET, POST, PATCH, OPTIONS"
    });
    return res.end();
  }
  if (req.method === "POST" && url.pathname === `/api/v1/libraries/${LIBRARY_ID}/reconcile-source`) {
    return reconcileSourceFromPichome(res);
  }
  if (req.method === "POST" && url.pathname === `/api/v1/libraries/${LIBRARY_ID}/items`) {
    return receiveUpload(req, res);
  }
  const mutationMatch = new RegExp(`^/api/v1/libraries/${LIBRARY_ID}/items/([A-Za-z0-9_-]+)$`).exec(url.pathname);
  if (req.method === "PATCH" && mutationMatch) return mutateItem(req, res, mutationMatch[1]);
  if (req.method !== "GET") return json(res, 405, { error: "method_not_allowed" });
  if (url.pathname === "/api/v1/events") return sendEventStream(req, res);

  try {
    const current = refreshCache();
    if (url.pathname === "/") {
      return json(res, 200, {
        ok: true,
        service: "Pichome Sync Bridge",
        version: "0.7.3",
        message: "Tailscale connection is working. Use the Eagle plugin to synchronize.",
        library: { id: LIBRARY_ID, name: LIBRARY_NAME, itemCount: current.items.length }
      });
    }
    if (url.pathname === "/api/v1/health") {
      return json(res, 200, { ok: true, service: "pichome-eagle-sync", version: "0.7.3", capabilities: { download: true, upload: true, delete: true, folderSync: true, unclassifiedMapping: true, folderOrderMetadata: true, realtimeEvents: true, forceMirror: true, pichomeIndexAuthority: true, sourceReconcile: true, conflictProtection: true, operationLog: true, latestOperationWins: true }, library: { id: LIBRARY_ID, name: LIBRARY_NAME, itemCount: current.items.length } });
    }
    if (url.pathname === "/api/v1/libraries") {
      return json(res, 200, { libraries: [{ id: LIBRARY_ID, name: LIBRARY_NAME, itemCount: current.items.length, folderCount: current.folders.length, version: current.fingerprint }] });
    }
    if (url.pathname === `/api/v1/libraries/${LIBRARY_ID}/folders`) {
      return json(res, 200, { folders: current.folders });
    }
    if (url.pathname === `/api/v1/libraries/${LIBRARY_ID}/state`) return sendLibraryState(res);
    if (url.pathname === `/api/v1/libraries/${LIBRARY_ID}/items`) {
      const cursor = Math.max(0, Number(url.searchParams.get("cursor") || 0));
      const limit = Math.min(25, Math.max(1, Number(url.searchParams.get("limit") || 5)));
      const selected = current.items.slice(cursor, cursor + limit).map((item) => publicItem(item, requestOrigin(req)));
      const nextCursor = cursor + selected.length < current.items.length ? cursor + selected.length : null;
      return json(res, 200, { items: selected, cursor, nextCursor, total: current.items.length });
    }

    const fileMatch = new RegExp(`^/api/v1/libraries/${LIBRARY_ID}/items/([A-Za-z0-9_-]+)/file$`).exec(url.pathname);
    if (fileMatch) {
      const itemId = fileMatch[1];
      if (!isValidSignature(itemId, url.searchParams.get("expires"), url.searchParams.get("signature"))) {
        return json(res, 403, { error: "invalid_or_expired_signature" });
      }
      const item = current.byId.get(itemId);
      if (!item) return json(res, 404, { error: "item_not_found" });
      return sendFile(req, res, item);
    }
    return json(res, 404, { error: "not_found" });
  } catch (error) {
    console.error(JSON.stringify({ event: "request-error", path: url.pathname, error: error.stack || error.message }));
    return json(res, 500, { error: "internal_error" });
  }
});

server.listen(PORT, HOST, () => {
  const current = refreshCache(true);
  startLibraryWatchers();
  console.log(JSON.stringify({ event: "server-started", host: HOST, port: PORT, library: LIBRARY_NAME, items: current.items.length }));
});
