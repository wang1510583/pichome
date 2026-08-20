(function () {
  "use strict";

  const SETTINGS_KEY = "pichome-sync-settings-v4";
  const V3_SETTINGS_KEY = "pichome-sync-settings-v3";
  const V2_SETTINGS_KEY = "pichome-sync-settings-v2";
  const DEFAULT_SERVER = "https://wang.tail26a65a.ts.net:8452/";
  const AUTO_INTERVAL_MS = 60 * 1000;
  const PAGE_SIZE = 25;
  const CONFLICT_WINDOW_MS = 10 * 1000;
  const CLIENT_ID_KEY = "pichome-sync-client-id";
  const elements = {};
  let remoteLibraries = [];
  let syncRunning = false;
  let initialized = false;
  let autoTimer = null;

  function defaultSettings() {
    return {
      enabled: false,
      autoMode: "off",
      serverUrl: DEFAULT_SERVER,
      selectedLibrary: "",
      cursors: {},
      autoCursors: {},
      imported: {},
      localUploads: {},
      syncBaselines: {},
      conflicts: {}
    };
  }

  function readSettings() {
    try {
      const current = JSON.parse(localStorage.getItem(SETTINGS_KEY));
      if (current) return { ...defaultSettings(), ...current };
      const previous = JSON.parse(localStorage.getItem(V3_SETTINGS_KEY)) || JSON.parse(localStorage.getItem(V2_SETTINGS_KEY));
      if (previous) return { ...defaultSettings(), ...previous };
    } catch (_) {}
    return defaultSettings();
  }

  function saveSettings(next) {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({ ...readSettings(), ...next }));
  }

  function clientId() {
    let value = localStorage.getItem(CLIENT_ID_KEY);
    if (!value) {
      value = `eagle-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
      localStorage.setItem(CLIENT_ID_KEY, value);
    }
    return value;
  }

  function normalizedServer() {
    const raw = elements.serverUrl?.value || readSettings().serverUrl || DEFAULT_SERVER;
    const url = new URL(raw.trim() || DEFAULT_SERVER);
    if (!/^https?:$/.test(url.protocol)) throw new Error("服务器地址必须使用 HTTP 或 HTTPS");
    url.pathname = url.pathname.replace(/\/+$/, "") + "/";
    url.search = "";
    url.hash = "";
    return url.href;
  }

  async function api(pathname) {
    const response = await fetch(new URL(pathname.replace(/^\//, ""), normalizedServer()).href, {
      method: "GET",
      cache: "no-store",
      redirect: "follow"
    });
    if (!response.ok) throw new Error(`同步服务返回 HTTP ${response.status}`);
    return response.json();
  }

  function setResult(element, message, type) {
    if (!element) return;
    element.textContent = message;
    element.className = `result${type ? ` ${type}` : ""}`;
  }

  function clampPercent(value) {
    return Math.max(0, Math.min(100, Math.round(Number(value) || 0)));
  }

  function setOverallProgress(current, total) {
    const safeTotal = Math.max(0, Number(total) || 0);
    const safeCurrent = Math.max(0, Math.min(safeTotal, Number(current) || 0));
    const percent = safeTotal > 0 ? clampPercent((safeCurrent / safeTotal) * 100) : 0;
    elements.progressBar.style.width = `${percent}%`;
    elements.progressBar.parentElement.setAttribute("aria-valuenow", String(percent));
    elements.overallProgressText.textContent = safeTotal > 0 ? `${safeCurrent} / ${safeTotal} · ${percent}%` : "等待清单";
  }

  function setItemProgress(name, loaded = 0, total = 0, complete = false) {
    const label = String(name || "等待同步");
    const safeTotal = Math.max(0, Number(total) || 0);
    const percent = complete ? 100 : safeTotal > 0 ? clampPercent((Number(loaded) / safeTotal) * 100) : 0;
    elements.currentItemName.textContent = `当前素材：${label}`;
    elements.currentItemName.title = label;
    elements.itemProgressBar.style.width = `${percent}%`;
    elements.itemProgressBar.parentElement.setAttribute("aria-valuenow", String(percent));
    elements.itemProgressText.textContent = complete ? "100%" : safeTotal > 0 ? `${percent}%` : "准备中";
  }

  function currentModeLabel(settings = readSettings()) {
    if (!settings.enabled) return "总开关已关闭";
    if (settings.autoMode === "one-way") return "自动单向同步中";
    if (settings.autoMode === "two-way") return "自动双向同步中";
    return "仅手动同步";
  }

  function renderState() {
    const settings = readSettings();
    elements.syncToggle.checked = Boolean(settings.enabled);
    elements.oneWayToggle.checked = settings.autoMode === "one-way";
    elements.twoWayToggle.checked = settings.autoMode === "two-way";
    elements.oneWayButton.classList.toggle("active", settings.enabled && settings.autoMode === "one-way");
    elements.twoWayButton.classList.toggle("active", settings.enabled && settings.autoMode === "two-way");
    elements.oneWayButton.setAttribute("aria-pressed", String(settings.enabled && settings.autoMode === "one-way"));
    elements.twoWayButton.setAttribute("aria-pressed", String(settings.enabled && settings.autoMode === "two-way"));
    elements.oneWayButton.querySelector(".mode-state").textContent = settings.enabled && settings.autoMode === "one-way" ? "运行中" : "开启";
    elements.twoWayButton.querySelector(".mode-state").textContent = settings.enabled && settings.autoMode === "two-way" ? "运行中" : "开启";
    elements.syncStatus.textContent = currentModeLabel(settings);
    elements.statusHint.textContent = !settings.enabled
      ? "同步已暂停；设置和进度会保留。"
      : settings.autoMode === "two-way"
        ? "每分钟同步新增、分类移动和可恢复删除；冲突会暂停处理。"
        : settings.autoMode === "one-way"
          ? "每分钟自动检查 Pichome，并导入新增素材。"
          : "可按自定义数量手动导入，不会回写 Pichome。";
    elements.modeHint.textContent = settings.autoMode === "off"
      ? "未开启自动同步"
      : settings.autoMode === "one-way"
        ? "Pichome → Eagle，首次连续追平，之后每 1 分钟巡检"
        : "Pichome ↔ Eagle，首次连续追平，之后每 1 分钟巡检全部变更";
    updateSyncButton();
  }

  function updateSyncButton() {
    if (!elements.syncButton) return;
    elements.syncButton.disabled = syncRunning || !elements.librarySelect.value || !window.eagle;
    elements.oneWayButton.disabled = syncRunning;
    elements.twoWayButton.disabled = syncRunning;
  }

  async function detectEagle() {
    if (!window.eagle || !eagle.library || !eagle.item) {
      elements.runtimeBadge.textContent = "未检测到 Eagle API";
      elements.runtimeBadge.className = "pill error";
      elements.pluginState.textContent = "请在 Eagle 内打开此插件";
      elements.libraryName.textContent = "不可用";
      elements.libraryPath.textContent = "不可用";
      return;
    }
    elements.runtimeBadge.textContent = "Eagle API 正常";
    elements.runtimeBadge.className = "pill ok";
    elements.pluginState.textContent = "后台同步服务已载入";
    try {
      const info = await eagle.library.info();
      elements.libraryName.textContent = info?.name || eagle.library.name || "已识别";
      elements.libraryPath.textContent = info?.path || eagle.library.path || "已识别";
      elements.libraryPath.title = elements.libraryPath.textContent;
    } catch (_) {
      elements.libraryName.textContent = eagle.library.name || "读取失败";
      elements.libraryPath.textContent = eagle.library.path || "读取失败";
    }
    updateSyncButton();
    await refreshItemCounts(false);
  }

  async function testConnection(silent = false) {
    if (!silent) {
      elements.testButton.disabled = true;
      setResult(elements.connectionResult, "正在连接 Tailscale 同步桥接……");
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 10000);
    try {
      const base = normalizedServer();
      const response = await fetch(new URL("api/v1/health", base).href, { cache: "no-store", signal: controller.signal });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const health = await response.json();
      const libraryData = await api("api/v1/libraries");
      remoteLibraries = libraryData.libraries || [];
      elements.librarySelect.innerHTML = "";
      const settings = readSettings();
      for (const library of remoteLibraries) {
        const option = document.createElement("option");
        option.value = library.id;
        option.textContent = `${library.name}（${library.itemCount} 项）`;
        elements.librarySelect.appendChild(option);
      }
      const selected = remoteLibraries.some((item) => item.id === settings.selectedLibrary)
        ? settings.selectedLibrary
        : remoteLibraries[0]?.id || "";
      elements.librarySelect.value = selected;
      elements.librarySelect.disabled = !remoteLibraries.length;
      elements.remoteCount.textContent = remoteLibraries.length ? `${health.library.itemCount} 项素材` : "没有可用库";
      saveSettings({ serverUrl: base, selectedLibrary: selected });
      setResult(elements.connectionResult, `连接正常：${health.library.name}，桥接服务 ${health.version || "可用"}。`, "success");
      updateSyncButton();
      await refreshItemCounts(false);
      return true;
    } catch (error) {
      if (!silent) {
        remoteLibraries = [];
        elements.librarySelect.innerHTML = '<option value="">连接失败</option>';
        elements.librarySelect.disabled = true;
        elements.remoteCount.textContent = "未连接";
      }
      const reason = error?.name === "AbortError" ? "连接超时" : error.message;
      setResult(elements.connectionResult, `${reason}。请确认两台电脑均已连接同一 Tailscale。`, "error");
      updateSyncButton();
      return false;
    } finally {
      clearTimeout(timeout);
      if (!silent) elements.testButton.disabled = false;
    }
  }

  function flattenLocalFolders(folders, output = []) {
    for (const folder of folders || []) {
      output.push(folder);
      flattenLocalFolders(folder.children, output);
    }
    return output;
  }

  async function refreshItemCounts(refreshRemote = false) {
    const libraryId = elements.librarySelect?.value || readSettings().selectedLibrary;
    try {
      if (refreshRemote) {
        const libraryData = await api("api/v1/libraries");
        remoteLibraries = libraryData.libraries || remoteLibraries;
      }
      const remoteLibrary = remoteLibraries.find((entry) => entry.id === libraryId);
      elements.pichomeItemCount.textContent = remoteLibrary ? String(remoteLibrary.itemCount) : "--";
    } catch (_) {
      elements.pichomeItemCount.textContent = "--";
    }

    if (!window.eagle || !libraryId) {
      elements.eagleItemCount.textContent = "--";
      return;
    }
    try {
      const folders = flattenLocalFolders(await eagle.folder.getAll());
      const root = folders.find((folder) => String(folder.description || "").includes(`pichome-sync:library:${libraryId}`));
      if (!root) {
        elements.eagleItemCount.textContent = "0";
        return;
      }
      const managedIds = new Set([root.id]);
      let changed = true;
      while (changed) {
        changed = false;
        for (const folder of folders) {
          const parentId = typeof folder.parent === "string" ? folder.parent : folder.parent?.id;
          if (managedIds.has(parentId) && !managedIds.has(folder.id)) {
            managedIds.add(folder.id);
            changed = true;
          }
        }
      }
      const items = await eagle.item.getAll();
      const count = (items || []).filter((item) => !item.isDeleted && itemFolderIds(item).some((id) => managedIds.has(id))).length;
      elements.eagleItemCount.textContent = String(count);
    } catch (_) {
      elements.eagleItemCount.textContent = "--";
    }
  }

  async function ensureFolderTargets(library, sourceFolders, requiredIds) {
    const localFolders = flattenLocalFolders(await eagle.folder.getAll());
    const localByMarker = new Map();
    for (const folder of localFolders) {
      const marker = String(folder.description || "").match(/pichome-sync:(?:library|folder):([A-Za-z0-9_-]+)/);
      if (marker) localByMarker.set(marker[1], folder);
    }
    let root = localByMarker.get(library.id);
    if (!root) {
      root = await eagle.folder.create({ name: `Pichome · ${library.name}`, description: `pichome-sync:library:${library.id}` });
      localByMarker.set(library.id, root);
    }
    const sourceById = new Map(sourceFolders.map((folder) => [folder.id, folder]));
    const creating = new Map();
    async function ensureSourceFolder(sourceId) {
      if (localByMarker.has(sourceId)) return localByMarker.get(sourceId);
      if (creating.has(sourceId)) return creating.get(sourceId);
      const source = sourceById.get(sourceId);
      if (!source) return root;
      const promise = (async () => {
        const parent = source.parent ? await ensureSourceFolder(source.parent) : root;
        const created = await eagle.folder.create({
          name: source.name,
          description: `${source.description ? `${source.description}\n` : ""}pichome-sync:folder:${source.id}`,
          parent: parent.id
        });
        localByMarker.set(sourceId, created);
        return created;
      })();
      creating.set(sourceId, promise);
      return promise;
    }
    const result = new Map();
    for (const sourceId of requiredIds) result.set(sourceId, await ensureSourceFolder(sourceId));
    return { root, sourceToLocal: result };
  }

  function downloadFile(url, targetPath, onProgress, expectedSize = 0) {
    const fs = require("fs");
    const transport = url.startsWith("https:") ? require("https") : require("http");
    return new Promise((resolve, reject) => {
      const request = transport.get(url, (response) => {
        if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
          response.resume();
          return downloadFile(new URL(response.headers.location, url).href, targetPath, onProgress, expectedSize).then(resolve, reject);
        }
        if (response.statusCode < 200 || response.statusCode >= 300) {
          response.resume();
          return reject(new Error(`素材下载失败：HTTP ${response.statusCode}`));
        }
        const total = Number(response.headers["content-length"]) || Number(expectedSize) || 0;
        let received = 0;
        const file = fs.createWriteStream(targetPath, { flags: "wx" });
        response.on("data", (chunk) => {
          received += chunk.length;
          if (onProgress) onProgress(received, total);
        });
        response.pipe(file);
        file.on("finish", () => file.close(() => {
          if (onProgress) onProgress(total || received, total || received);
          resolve();
        }));
        file.on("error", reject);
      });
      request.setTimeout(120000, () => request.destroy(new Error("素材下载超时")));
      request.on("error", reject);
    });
  }

  async function importItem(source, folderIds, onProgress) {
    const fs = require("fs");
    const os = require("os");
    const path = require("path");
    const crypto = require("crypto");
    const safeExt = /^[A-Za-z0-9]{1,12}$/.test(source.ext) ? source.ext : "bin";
    const tempPath = path.join(os.tmpdir(), `pichome-sync-${crypto.randomBytes(10).toString("hex")}.${safeExt}`);
    try {
      await downloadFile(source.downloadUrl, tempPath, onProgress, source.size);
      const localId = await eagle.item.addFromPath(tempPath, {
        name: source.name,
        website: source.website || normalizedServer(),
        tags: source.tags || [],
        folders: folderIds,
        annotation: `${source.annotation || ""}${source.annotation ? "\n\n" : ""}[Pichome Sync ID: ${source.id}]`
      });
      if (source.star) {
        const localItem = await eagle.item.getById(localId);
        if (localItem) { localItem.star = source.star; await localItem.save(); }
      }
      return localId;
    } finally {
      try { if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath); } catch (_) {}
    }
  }

  async function syncFromPichome({ requested, automatic = false }) {
    if (syncRunning || !readSettings().enabled) return { added: 0, skipped: 0 };
    const libraryId = elements.librarySelect.value || readSettings().selectedLibrary;
    const library = remoteLibraries.find((entry) => entry.id === libraryId);
    if (!library) throw new Error("请先连接并选择素材库");

    syncRunning = true;
    updateSyncButton();
    setOverallProgress(0, library.itemCount || 0);
    setItemProgress("正在读取素材清单…");
    let added = 0;
    let skipped = 0;
    let scanned = 0;
    try {
      const folderData = await api(`api/v1/libraries/${libraryId}/folders`);
      let settings = readSettings();
      const cursorField = automatic ? "autoCursors" : "cursors";
      const cursors = { ...(settings[cursorField] || {}) };
      let cursor = Number(cursors[libraryId] || 0);
      const imported = { ...(settings.imported || {}) };
      const libraryImported = { ...(imported[libraryId] || {}) };
      const maxScan = Number.MAX_SAFE_INTEGER;
      let reachedEnd = false;
      let cancelled = false;
      let totalItems = Number(library.itemCount) || 0;
      setOverallProgress(cursor, totalItems);

      while (added < requested && scanned < maxScan && !reachedEnd && !cancelled) {
        const page = await api(`api/v1/libraries/${libraryId}/items?cursor=${cursor}&limit=${PAGE_SIZE}`);
        totalItems = Number(page.total) || totalItems;
        setOverallProgress(cursor, totalItems);
        if (!page.items.length) { reachedEnd = true; break; }
        const neededFolders = new Set(page.items.flatMap((item) => item.folders || []));
        const targets = await ensureFolderTargets(library, folderData.folders || [], neededFolders);
        for (const source of page.items) {
          if (automatic) {
            const currentSettings = readSettings();
            if (!currentSettings.enabled || currentSettings.autoMode === "off") {
              cancelled = true;
              break;
            }
          }
          scanned += 1;
          cursor += 1;
          setResult(elements.syncResult, `${automatic ? "自动同步" : "手动同步"}：正在处理 ${source.name}`);
          setItemProgress(source.name);

          const knownLocalId = libraryImported[source.id];
          if (knownLocalId) {
            try {
              if (await eagle.item.getById(knownLocalId)) {
                skipped += 1;
                setItemProgress(source.name, 1, 1, true);
                setOverallProgress(cursor, totalItems);
                continue;
              }
            } catch (_) {}
            if (readSettings().autoMode === "two-way") {
              skipped += 1;
              setItemProgress(source.name, 1, 1, true);
              setOverallProgress(cursor, totalItems);
              continue;
            }
            delete libraryImported[source.id];
          }
          const folderIds = (source.folders || []).map((id) => targets.sourceToLocal.get(id)?.id).filter(Boolean);
          if (!folderIds.length) folderIds.push(targets.root.id);
          libraryImported[source.id] = await importItem(source, folderIds, (loaded, total) => setItemProgress(source.name, loaded, total));
          setItemProgress(source.name, 1, 1, true);
          added += 1;
          setOverallProgress(cursor, totalItems);
          imported[libraryId] = libraryImported;
          cursors[libraryId] = cursor;
          saveSettings({ [cursorField]: cursors, imported });
          if (added >= requested) break;
        }
        reachedEnd = page.nextCursor === null;
      }

      cursors[libraryId] = automatic && reachedEnd ? 0 : cursor;
      imported[libraryId] = libraryImported;
      saveSettings({ [cursorField]: cursors, imported });
      setOverallProgress(reachedEnd ? totalItems : cursor, totalItems);
      if (cancelled) setItemProgress("同步已停止");
      const ending = cancelled ? "已按同步开关停止" : reachedEnd ? "已追平 Pichome 素材库" : `下次从第 ${cursor + 1} 项继续`;
      setResult(elements.syncResult, `完成：新增 ${added} 项，跳过 ${skipped} 项；${ending}。`, "success");
      return { added, skipped };
    } finally {
      syncRunning = false;
      updateSyncButton();
      await refreshItemCounts(false);
    }
  }

  function uploadFile(filePath, metadata, onProgress) {
    const fs = require("fs");
    const url = new URL(`api/v1/libraries/${elements.librarySelect.value || readSettings().selectedLibrary}/items`, normalizedServer());
    const transport = url.protocol === "https:" ? require("https") : require("http");
    const size = fs.statSync(filePath).size;
    const encodedMetadata = Buffer.from(JSON.stringify(metadata), "utf8").toString("base64url");
    return new Promise((resolve, reject) => {
      const request = transport.request(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/octet-stream",
          "Content-Length": size,
          "X-Pichome-Metadata": encodedMetadata
        }
      }, (response) => {
        const chunks = [];
        response.on("data", (chunk) => chunks.push(chunk));
        response.on("end", () => {
          let body = {};
          try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); } catch (_) {}
          if (response.statusCode < 200 || response.statusCode >= 300) return reject(new Error(body.message || `回传失败：HTTP ${response.statusCode}`));
          resolve(body);
        });
      });
      request.setTimeout(30 * 60 * 1000, () => request.destroy(new Error("素材回传超时")));
      request.on("error", reject);
      let uploaded = 0;
      const stream = fs.createReadStream(filePath);
      stream.on("data", (chunk) => {
        uploaded += chunk.length;
        if (onProgress) onProgress(uploaded, size);
      });
      stream.on("error", reject);
      stream.on("end", () => { if (onProgress) onProgress(size, size); });
      stream.pipe(request);
    });
  }

  function itemFolderIds(item) {
    return (item.folders || []).map((folder) => typeof folder === "string" ? folder : folder?.id).filter(Boolean);
  }

  function sortedUnique(values) {
    return [...new Set((values || []).filter(Boolean).map(String))].sort();
  }

  function arraysEqual(left, right) {
    const a = sortedUnique(left);
    const b = sortedUnique(right);
    return a.length === b.length && a.every((value, index) => value === b[index]);
  }

  async function mutateRemoteItem(libraryId, sourceId, payload) {
    const response = await fetch(new URL(`api/v1/libraries/${libraryId}/items/${encodeURIComponent(sourceId)}`, normalizedServer()).href, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...payload, clientTime: payload.clientTime || Date.now(), clientId: clientId() })
    });
    let body = {};
    try { body = await response.json(); } catch (_) {}
    if (response.status === 409) {
      const error = new Error(body.error || "远端素材同时发生变化");
      error.conflict = true;
      error.remoteState = body.state;
      throw error;
    }
    if (!response.ok) throw new Error(body.message || body.error || `远端变更失败：HTTP ${response.status}`);
    return body.state;
  }

  async function buildFolderContext(library, remoteFolders) {
    const requiredIds = new Set((remoteFolders || []).map((folder) => folder.id));
    await ensureFolderTargets(library, remoteFolders || [], requiredIds);
    const folders = flattenLocalFolders(await eagle.folder.getAll());
    const root = folders.find((folder) => String(folder.description || "").includes(`pichome-sync:library:${library.id}`));
    const remoteByLocal = new Map();
    const localByRemote = new Map();
    const managedLocalIds = new Set(root ? [root.id] : []);
    for (const folder of folders) {
      const marker = String(folder.description || "").match(/pichome-sync:folder:([A-Za-z0-9_-]+)/);
      if (marker) {
        remoteByLocal.set(folder.id, marker[1]);
        localByRemote.set(marker[1], folder.id);
        managedLocalIds.add(folder.id);
      }
    }
    return { root, remoteByLocal, localByRemote, managedLocalIds };
  }

  function localStateForItem(item, folderContext) {
    if (!item) return { missing: true, isDeleted: false, modifiedAt: 0, folders: [] };
    return {
      missing: false,
      isDeleted: Boolean(item.isDeleted),
      modifiedAt: Number(item.modifiedAt || 0),
      folders: sortedUnique(itemFolderIds(item).map((id) => folderContext.remoteByLocal.get(id)).filter(Boolean))
    };
  }

  function makeBaseline(remoteState, localState) {
    return {
      remoteVersion: String(remoteState.version || "missing"),
      remoteFolders: sortedUnique(remoteState.folders),
      remoteDeleted: Boolean(remoteState.isDeleted),
      localModifiedAt: Number(localState.modifiedAt || 0),
      localFolders: sortedUnique(localState.folders),
      localDeleted: Boolean(localState.isDeleted)
    };
  }

  async function syncBidirectionalChanges(limit = 30) {
    if (syncRunning) return { moved: 0, deleted: 0, conflicts: 0, initialized: 0 };
    const libraryId = elements.librarySelect.value || readSettings().selectedLibrary;
    const library = remoteLibraries.find((entry) => entry.id === libraryId);
    if (!library) return { moved: 0, deleted: 0, conflicts: 0, initialized: 0 };
    syncRunning = true;
    updateSyncButton();
    try {
      const [remoteStateData, remoteFolderData] = await Promise.all([
        api(`api/v1/libraries/${libraryId}/state`),
        api(`api/v1/libraries/${libraryId}/folders`)
      ]);
      const remoteById = new Map((remoteStateData.items || []).map((item) => [item.id, item]));
      const folderContext = await buildFolderContext(library, remoteFolderData.folders || []);
      const localItems = await eagle.item.getAll();
      const localById = new Map((localItems || []).map((item) => [item.id, item]));
      const settings = readSettings();
      const imported = settings.imported || {};
      const libraryImported = imported[libraryId] || {};
      const syncBaselines = { ...(settings.syncBaselines || {}) };
      const baselines = { ...(syncBaselines[libraryId] || {}) };
      const allConflicts = { ...(settings.conflicts || {}) };
      const conflicts = { ...(allConflicts[libraryId] || {}) };
      let moved = 0;
      let deleted = 0;
      let conflictCount = 0;
      let initializedCount = 0;
      let processed = 0;
      let checkedCount = 0;
      const totalTargets = Object.keys(libraryImported).length;
      setOverallProgress(0, totalTargets);
      setItemProgress("正在检查双向变更…");

      for (const [sourceId, localId] of Object.entries(libraryImported)) {
        if (processed >= limit) break;
        let localItem = localById.get(localId) || null;
        if (!localItem) {
          try { localItem = await eagle.item.getById(localId); } catch (_) {}
        }
        checkedCount += 1;
        const itemName = localItem?.name || sourceId;
        setOverallProgress(checkedCount, totalTargets);
        setItemProgress(itemName);
        let remoteState = remoteById.get(sourceId) || { id: sourceId, folders: [], isDeleted: true, version: "missing", modifiedAt: 0 };
        let localState = localStateForItem(localItem, folderContext);
        if (localState.missing) {
          conflicts[sourceId] = { sourceId, localId, detectedAt: Date.now(), reason: "Eagle 未返回该素材，无法确认是删除还是暂时不可用，因此没有传播删除" };
          conflictCount += 1;
          setItemProgress(itemName, 1, 1, true);
          continue;
        }
        const baseline = baselines[sourceId];
        if (!baseline) {
          baselines[sourceId] = makeBaseline(remoteState, localState);
          delete conflicts[sourceId];
          initializedCount += 1;
          setItemProgress(itemName, 1, 1, true);
          continue;
        }

        const remoteChanged = String(remoteState.version || "missing") !== String(baseline.remoteVersion || "missing") ||
          Boolean(remoteState.isDeleted) !== Boolean(baseline.remoteDeleted) ||
          !arraysEqual(remoteState.folders, baseline.remoteFolders);
        const localChanged = Boolean(localState.isDeleted) !== Boolean(baseline.localDeleted) ||
          !arraysEqual(localState.folders, baseline.localFolders);
        if (!remoteChanged && !localChanged) {
          setItemProgress(itemName, 1, 1, true);
          continue;
        }

        const finalStatesMatch = Boolean(remoteState.isDeleted) === Boolean(localState.isDeleted) &&
          (remoteState.isDeleted || arraysEqual(remoteState.folders, localState.folders));
        if (remoteChanged && localChanged && finalStatesMatch) {
          baselines[sourceId] = makeBaseline(remoteState, localState);
          delete conflicts[sourceId];
          setItemProgress(itemName, 1, 1, true);
          continue;
        }

        let winningSide = remoteChanged ? "remote" : "local";
        if (remoteChanged && localChanged) {
          const remoteChangedAt = Number(remoteState.syncEvent?.eventTime || remoteState.modifiedAt || 0);
          const localChangedAt = Number(localState.modifiedAt || 0);
          if (!remoteChangedAt || !localChangedAt || Math.abs(remoteChangedAt - localChangedAt) < CONFLICT_WINDOW_MS) {
            conflicts[sourceId] = { sourceId, localId, detectedAt: Date.now(), reason: "两端在接近的时间修改了同一状态，无法可靠判断先后" };
            conflictCount += 1;
            setItemProgress(itemName, 1, 1, true);
            continue;
          }
          winningSide = remoteChangedAt > localChangedAt ? "remote" : "local";
        }

        try {
          if (winningSide === "remote") {
            if (remoteState.isDeleted && localItem && !localState.isDeleted) {
              setResult(elements.syncResult, `双向同步：正在将 ${localItem.name} 移入 Eagle 回收站`);
              await localItem.moveToTrash();
              deleted += 1;
            } else if (!remoteState.isDeleted && localState.isDeleted) {
              if (!localItem || typeof localItem.restoreFromTrash !== "function") {
                throw Object.assign(new Error("较新的 Pichome 操作需要恢复 Eagle 素材，但当前 Eagle 版本不支持插件自动恢复"), { conflict: true });
              }
              setResult(elements.syncResult, `双向同步：正在恢复 ${localItem.name}`);
              await localItem.restoreFromTrash();
              deleted += 1;
              try { localItem = await eagle.item.getById(localId); } catch (_) {}
            }
            if (localItem && !remoteState.isDeleted && !arraysEqual(remoteState.folders, localState.folders)) {
              const localFolders = sortedUnique((remoteState.folders || []).map((id) => folderContext.localByRemote.get(id)).filter(Boolean));
              if (!localFolders.length && folderContext.root) localFolders.push(folderContext.root.id);
              const unmanagedFolders = itemFolderIds(localItem).filter((id) => !folderContext.managedLocalIds.has(id));
              setResult(elements.syncResult, `双向同步：正在更新 ${localItem.name} 的分类`);
              localItem.folders = sortedUnique([...unmanagedFolders, ...localFolders]);
              await localItem.save();
              moved += 1;
            }
          } else {
            setResult(elements.syncResult, `双向同步：正在回写 ${localItem?.name || sourceId} 的${localState.isDeleted ? "删除状态" : "分类"}`);
            remoteState = await mutateRemoteItem(libraryId, sourceId, {
              baseVersion: remoteState.version || baseline.remoteVersion,
              folders: localState.folders,
              isDeleted: localState.isDeleted,
              clientTime: localState.modifiedAt || Date.now()
            });
            if (localState.isDeleted !== baseline.localDeleted) deleted += 1;
            else moved += 1;
          }
          try { localItem = await eagle.item.getById(localId); } catch (_) { localItem = null; }
          localState = localStateForItem(localItem, folderContext);
          baselines[sourceId] = makeBaseline(remoteState, localState);
          delete conflicts[sourceId];
          processed += 1;
          setItemProgress(itemName, 1, 1, true);
        } catch (error) {
          if (!error.conflict) throw error;
          conflicts[sourceId] = { sourceId, localId, detectedAt: Date.now(), reason: error.message };
          conflictCount += 1;
          setItemProgress(itemName, 1, 1, true);
        }
      }

      syncBaselines[libraryId] = baselines;
      allConflicts[libraryId] = conflicts;
      saveSettings({ syncBaselines, conflicts: allConflicts });
      const totalConflicts = Object.keys(conflicts).length;
      if (moved || deleted || conflictCount) {
        setResult(elements.syncResult, `双向变更完成：分类移动 ${moved} 项，删除 ${deleted} 项，待处理冲突 ${totalConflicts} 项。`, totalConflicts ? "error" : "success");
      } else if (initializedCount) {
        setResult(elements.syncResult, `已为 ${initializedCount} 项建立双向同步基线；本轮不会应用历史移动或删除。`, "success");
      }
      return { moved, deleted, conflicts: totalConflicts, initialized: initializedCount };
    } finally {
      syncRunning = false;
      updateSyncButton();
      await refreshItemCounts(true);
    }
  }

  async function syncNewLocalItems(limit = 3) {
    if (syncRunning) return { uploaded: 0 };
    const libraryId = elements.librarySelect.value || readSettings().selectedLibrary;
    if (!libraryId) return { uploaded: 0 };
    syncRunning = true;
    updateSyncButton();
    try {
      const folders = flattenLocalFolders(await eagle.folder.getAll());
      const root = folders.find((folder) => String(folder.description || "").includes(`pichome-sync:library:${libraryId}`));
      if (!root) return { uploaded: 0 };
      const managedIds = new Set();
      const collectManaged = (folder) => {
        if (!folder || managedIds.has(folder.id)) return;
        managedIds.add(folder.id);
        for (const child of folder.children || []) collectManaged(child);
      };
      collectManaged(root);
      const remoteFolderByLocal = new Map();
      let changed = true;
      while (changed) {
        changed = false;
        for (const folder of folders) {
          const parentId = typeof folder.parent === "string" ? folder.parent : folder.parent?.id;
          if (managedIds.has(parentId) && !managedIds.has(folder.id)) { managedIds.add(folder.id); changed = true; }
        }
      }
      for (const folder of folders) {
        if (!managedIds.has(folder.id)) continue;
        const marker = String(folder.description || "").match(/pichome-sync:folder:([A-Za-z0-9_-]+)/);
        if (marker) remoteFolderByLocal.set(folder.id, marker[1]);
      }

      const allItems = await eagle.item.getAll();
      const settings = readSettings();
      const imported = { ...(settings.imported || {}) };
      const libraryImported = { ...(imported[libraryId] || {}) };
      const knownLocalIds = new Set(Object.values(libraryImported));
      const localUploads = { ...(settings.localUploads || {}) };
      let uploaded = 0;

      for (const item of allItems || []) {
        const marker = String(item.annotation || "").match(/\[Pichome Sync ID: ([A-Za-z0-9_-]+)\]/);
        if (marker) {
          libraryImported[marker[1]] = item.id;
          knownLocalIds.add(item.id);
        }
      }

      const candidates = [];
      for (const item of allItems || []) {
        const foldersForItem = itemFolderIds(item);
        if (!foldersForItem.some((id) => managedIds.has(id))) continue;
        if (knownLocalIds.has(item.id) || localUploads[item.id]) continue;
        const filePath = item.filePath;
        if (!filePath || !require("fs").existsSync(filePath)) continue;
        const remoteFolderId = foldersForItem.map((id) => remoteFolderByLocal.get(id)).find(Boolean) || "";
        const ext = item.ext || require("path").extname(filePath).slice(1);
        const filename = `${item.name || require("path").basename(filePath, require("path").extname(filePath))}.${ext}`;
        candidates.push({ item, filePath, remoteFolderId, filename });
        if (candidates.length >= limit) break;
      }

      setOverallProgress(0, candidates.length);
      for (const { item, filePath, remoteFolderId, filename } of candidates) {
        setResult(elements.syncResult, `双向同步：正在回传 ${filename}`);
        setItemProgress(filename);
        const response = await uploadFile(filePath, { filename, folderId: remoteFolderId }, (loaded, total) => setItemProgress(filename, loaded, total));
        setItemProgress(filename, 1, 1, true);
        const sourceId = response.item?.id;
        if (!sourceId) throw new Error("回传成功但未收到 Pichome 素材 ID");
        libraryImported[sourceId] = item.id;
        localUploads[item.id] = sourceId;
        knownLocalIds.add(item.id);
        uploaded += 1;
        setOverallProgress(uploaded, candidates.length);
        imported[libraryId] = libraryImported;
        saveSettings({ imported, localUploads });
      }
      imported[libraryId] = libraryImported;
      saveSettings({ imported, localUploads });
      if (uploaded) setResult(elements.syncResult, `双向同步完成：已回传 ${uploaded} 项新素材。`, "success");
      return { uploaded };
    } finally {
      syncRunning = false;
      updateSyncButton();
      await refreshItemCounts(true);
    }
  }

  async function runAutoCycle() {
    const settings = readSettings();
    if (!settings.enabled || settings.autoMode === "off" || syncRunning || !window.eagle) return;
    try {
      if (!remoteLibraries.length && !(await testConnection(true))) return;
      await syncFromPichome({ requested: Number.MAX_SAFE_INTEGER, automatic: true });
      if (readSettings().autoMode === "two-way") {
        await syncNewLocalItems(3);
        await syncBidirectionalChanges(30);
      }
    } catch (error) {
      setResult(elements.syncResult, `自动同步暂停本轮：${error.message}`, "error");
    }
  }

  async function syncManual() {
    const requested = Math.floor(Number(elements.batchSize.value));
    if (!Number.isSafeInteger(requested) || requested < 1) return setResult(elements.syncResult, "请输入大于 0 的整数。", "error");
    if (!readSettings().enabled) {
      saveSettings({ enabled: true, autoMode: "off" });
      renderState();
    }
    try { await syncFromPichome({ requested, automatic: false }); }
    catch (error) { setResult(elements.syncResult, `同步停止：${error.message}`, "error"); }
  }

  function setAutoMode(mode) {
    const settings = readSettings();
    saveSettings({ autoMode: mode, enabled: mode === "off" ? settings.enabled : true });
    renderState();
    if (mode !== "off") runAutoCycle();
  }

  function initialize() {
    if (initialized) return;
    initialized = true;
    [
      "runtimeBadge", "syncToggle", "syncStatus", "statusHint", "serverUrl", "saveButton", "testButton",
      "connectionResult", "pluginState", "libraryName", "libraryPath", "librarySelect", "batchSize",
      "syncButton", "syncResult", "remoteCount", "progressBar", "overallProgressText", "currentItemName",
      "itemProgressBar", "itemProgressText", "pichomeItemCount", "eagleItemCount",
      "oneWayToggle", "twoWayToggle", "modeHint",
      "oneWayButton", "twoWayButton"
    ].forEach((id) => { elements[id] = document.getElementById(id); });

    const settings = readSettings();
    elements.serverUrl.value = settings.serverUrl || DEFAULT_SERVER;
    renderState();
    elements.syncToggle.addEventListener("change", () => { saveSettings({ enabled: elements.syncToggle.checked }); renderState(); if (elements.syncToggle.checked) runAutoCycle(); });
    elements.oneWayToggle.addEventListener("change", () => setAutoMode(elements.oneWayToggle.checked ? "one-way" : "off"));
    elements.twoWayToggle.addEventListener("change", () => {
      if (!elements.twoWayToggle.checked) return setAutoMode("off");
      const confirmed = window.confirm(
        "确认开启自动双向同步？\n\n" +
        "• Pichome 新素材会自动导入 Eagle\n" +
        "• 放入“Pichome · 参考库”文件夹树的新素材会回传 Pichome\n" +
        "• 两端的分类移动会互相同步\n" +
        "• 删除会同步到另一端的回收站\n" +
        "• 两端同时变化时采用较新的明确操作；时间过近才暂停冲突\n\n" +
        "此操作可能移动或删除素材。请确认两端均有可靠备份，并且仅在可信的 Tailscale 网络中使用。"
      );
      if (confirmed) setAutoMode("two-way");
      else { elements.twoWayToggle.checked = false; renderState(); }
    });
    elements.oneWayButton.addEventListener("click", () => {
      const settings = readSettings();
      setAutoMode(settings.enabled && settings.autoMode === "one-way" ? "off" : "one-way");
    });
    elements.twoWayButton.addEventListener("click", () => {
      const settings = readSettings();
      if (settings.enabled && settings.autoMode === "two-way") return setAutoMode("off");
      const confirmed = window.confirm(
        "确认开启自动双向同步？\n\n" +
        "• 两端新增素材会互相同步\n" +
        "• 分类移动会同步到另一端\n" +
        "• 删除会同步到另一端的回收站\n" +
        "• 两端同时变化时采用较新的明确操作；时间过近才暂停冲突\n\n" +
        "请确认两端均有可靠备份。"
      );
      if (confirmed) setAutoMode("two-way");
    });
    elements.saveButton.addEventListener("click", () => {
      try {
        const serverUrl = normalizedServer();
        elements.serverUrl.value = serverUrl;
        saveSettings({ serverUrl });
        setResult(elements.connectionResult, "设置已保存。", "success");
      } catch (error) { setResult(elements.connectionResult, error.message, "error"); }
    });
    elements.testButton.addEventListener("click", () => testConnection(false));
    elements.librarySelect.addEventListener("change", () => {
      saveSettings({ selectedLibrary: elements.librarySelect.value });
      updateSyncButton();
      refreshItemCounts(false);
    });
    elements.syncButton.addEventListener("click", syncManual);
    detectEagle();
    testConnection(true).then(() => setTimeout(runAutoCycle, 3000));
    autoTimer = setInterval(runAutoCycle, AUTO_INTERVAL_MS);
  }

  if (window.eagle && typeof eagle.onPluginCreate === "function") {
    eagle.onPluginCreate(initialize);
    eagle.onPluginRun(async () => {
      try { if (eagle.window?.show) await eagle.window.show(); } catch (_) {}
      detectEagle();
      runAutoCycle();
    });
    eagle.onLibraryChanged(() => { detectEagle(); runAutoCycle(); });
    if (typeof eagle.onPluginBeforeExit === "function") eagle.onPluginBeforeExit(() => { if (autoTimer) clearInterval(autoTimer); });
  } else {
    window.addEventListener("DOMContentLoaded", initialize, { once: true });
  }
})();
