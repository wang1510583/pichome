(function () {
  "use strict";

  const SETTINGS_KEY = "pichome-sync-settings-v4";
  const V3_SETTINGS_KEY = "pichome-sync-settings-v3";
  const V2_SETTINGS_KEY = "pichome-sync-settings-v2";
  const DEFAULT_SERVER = "https://wang.tail26a65a.ts.net:8452/";
  const AUTO_INTERVAL_MS = 2 * 60 * 60 * 1000;
  const PAGE_SIZE = 25;
  const CONFLICT_WINDOW_MS = 10 * 1000;
  const CLIENT_ID_KEY = "pichome-sync-client-id";
  const elements = {};
  let remoteLibraries = [];
  let syncRunning = false;
  let initialized = false;
  let autoTimer = null;
  let eventStream = null;
  let triggerTimer = null;
  let localWatchers = [];

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
      sortAligned: {},
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

  async function postApi(pathname, body = {}) {
    const response = await fetch(new URL(pathname.replace(/^\//, ""), normalizedServer()).href, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
    let result = {};
    try { result = await response.json(); } catch (_) {}
    if (!response.ok) throw new Error(result.message || result.error || `同步服务返回 HTTP ${response.status}`);
    return result;
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
    return "等待手动强制同步";
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
        ? "检测到操作后约 2 秒同步新增、分类移动和可恢复删除；每 2 小时兜底巡检。"
        : settings.autoMode === "one-way"
          ? "Pichome 发生变化后约 2 秒自动导入；每 2 小时兜底巡检。"
          : "可选择一个方向执行手动强制镜像；目标端多余素材会进入回收站。";
    elements.modeHint.textContent = settings.autoMode === "off"
      ? "未开启自动同步"
      : settings.autoMode === "one-way"
        ? "Pichome → Eagle，变化后约 2 秒同步，每 2 小时兜底"
        : "Pichome ↔ Eagle，变化后约 2 秒同步，每 2 小时兜底";
    updateSyncButton();
  }

  function updateSyncButton() {
    if (!elements.forceFromPichomeButton) return;
    const disabled = syncRunning || !elements.librarySelect.value || !window.eagle;
    elements.forceFromPichomeButton.disabled = disabled;
    elements.forceToPichomeButton.disabled = disabled;
    elements.oneWayButton.disabled = syncRunning;
    elements.twoWayButton.disabled = syncRunning;
  }

  function closeReactiveSync() {
    if (eventStream) eventStream.close();
    eventStream = null;
    for (const watcher of localWatchers) {
      try { watcher.close(); } catch (_) {}
    }
    localWatchers = [];
  }

  function scheduleTriggeredSync(side = "remote") {
    const settings = readSettings();
    if (!settings.enabled || settings.autoMode === "off") return;
    if (side === "local" && settings.autoMode !== "two-way") return;
    clearTimeout(triggerTimer);
    triggerTimer = setTimeout(() => {
      if (syncRunning) return scheduleTriggeredSync(side);
      runAutoCycle();
    }, 1800);
  }

  function connectRemoteEvents() {
    if (eventStream) eventStream.close();
    eventStream = null;
    const settings = readSettings();
    if (!settings.enabled || settings.autoMode === "off" || typeof EventSource !== "function") return;
    try {
      eventStream = new EventSource(new URL("api/v1/events", normalizedServer()).href);
      eventStream.addEventListener("change", () => scheduleTriggeredSync("remote"));
    } catch (_) {}
  }

  async function watchLocalLibrary() {
    for (const watcher of localWatchers) {
      try { watcher.close(); } catch (_) {}
    }
    localWatchers = [];
    const settings = readSettings();
    if (!window.eagle || !settings.enabled || settings.autoMode === "off") return;
    try {
      const info = await eagle.library.info();
      const libraryPath = info?.path || eagle.library.path;
      if (!libraryPath) return;
      const fs = require("fs");
      const path = require("path");
      for (const target of [path.join(libraryPath, "metadata.json"), path.join(libraryPath, "mtime.json"), path.join(libraryPath, "images")]) {
        if (!fs.existsSync(target)) continue;
        const watcher = fs.watch(target, { persistent: false }, () => scheduleTriggeredSync("local"));
        watcher.on("error", () => {});
        localWatchers.push(watcher);
      }
    } catch (_) {}
  }

  function refreshReactiveSync() {
    connectRemoteEvents();
    watchLocalLibrary();
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
      watchLocalLibrary();
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
      connectRemoteEvents();
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

  function folderParentId(folder) {
    return typeof folder?.parent === "string" ? folder.parent : folder?.parent?.id || null;
  }

  function sourceFolderMarker(folder) {
    return String(folder?.description || "").match(/pichome-sync:folder:([A-Za-z0-9_-]+)/)?.[1] || null;
  }

  async function reconcileFolderOrder(root, sourceFolders, localByMarker, legacyUnclassified) {
    if (!root) return 0;
    let reordered = 0;
    for (const source of sourceFolders || []) {
      const localFolder = localByMarker.get(source.id);
      const expectedParent = source.parent ? localByMarker.get(source.parent) : root;
      if (!localFolder || !expectedParent || folderParentId(localFolder) === expectedParent.id) continue;
      localFolder.parent = expectedParent.id;
      await localFolder.save();
      reordered += 1;
    }

    const siblingGroups = new Map();
    for (const source of sourceFolders || []) {
      const key = source.parent || "";
      if (!siblingGroups.has(key)) siblingGroups.set(key, []);
      siblingGroups.get(key).push(source.id);
    }
    for (const [parentSourceId, desiredIds] of siblingGroups) {
      const parentFolder = parentSourceId ? localByMarker.get(parentSourceId) : root;
      if (!parentFolder || desiredIds.length < 2) continue;
      const freshParent = await eagle.folder.getById(parentFolder.id);
      const desiredSet = new Set(desiredIds);
      const currentIds = (freshParent?.children || []).map(sourceFolderMarker).filter((id) => desiredSet.has(id));
      if (currentIds.length === desiredIds.length && currentIds.every((id, index) => id === desiredIds[index])) continue;

      const stagingParent = parentFolder.id === root.id ? null : root.id;
      for (const sourceId of desiredIds) {
        const mapped = localByMarker.get(sourceId);
        if (!mapped) continue;
        const folder = await eagle.folder.getById(mapped.id);
        if (!folder) continue;
        folder.parent = stagingParent;
        await folder.save();
      }
      for (const sourceId of desiredIds) {
        const mapped = localByMarker.get(sourceId);
        if (!mapped) continue;
        const folder = await eagle.folder.getById(mapped.id);
        if (!folder) continue;
        folder.parent = parentFolder.id;
        await folder.save();
      }
      if (parentFolder.id === root.id && legacyUnclassified && folderParentId(legacyUnclassified) === root.id) {
        legacyUnclassified.parent = null;
        await legacyUnclassified.save();
        legacyUnclassified.parent = root.id;
        await legacyUnclassified.save();
      }
      reordered += desiredIds.length;
    }
    return reordered;
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
      const managedIds = new Set(root ? [root.id] : []);
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
      const mappedLocalIds = new Set(Object.values(readSettings().imported?.[libraryId] || {}));
      const count = (items || []).filter((item) => !item.isDeleted && (mappedLocalIds.has(item.id) || itemFolderIds(item).some((id) => managedIds.has(id)))).length;
      elements.eagleItemCount.textContent = String(count);
    } catch (_) {
      elements.eagleItemCount.textContent = "--";
    }
  }

  async function ensureFolderTargets(library, sourceFolders, requiredIds) {
    const localFolders = flattenLocalFolders(await eagle.folder.getAll());
    const localByMarker = new Map();
    let unclassified = null;
    for (const folder of localFolders) {
      const marker = String(folder.description || "").match(/pichome-sync:(?:library|folder):([A-Za-z0-9_-]+)/);
      if (marker) localByMarker.set(marker[1], folder);
      if (String(folder.description || "").includes(`pichome-sync:unclassified:${library.id}`)) unclassified = folder;
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
    await reconcileFolderOrder(root, sourceFolders, localByMarker, unclassified);
    return { root, unclassified, sourceToLocal: result };
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
      if (source.star || Number(source.importedAt) > 0) {
        const localItem = await eagle.item.getById(localId);
        if (localItem) {
          if (source.star) localItem.star = source.star;
          if (Number(source.importedAt) > 0) localItem.importedAt = Number(source.importedAt);
          await localItem.save();
        }
      }
      return localId;
    } finally {
      try { if (fs.existsSync(tempPath)) fs.unlinkSync(tempPath); } catch (_) {}
    }
  }

  async function replaceItemFromPichome(localItem, source, onProgress) {
    if (!localItem || typeof localItem.replaceFile !== "function") return false;
    const fs = require("fs");
    const os = require("os");
    const path = require("path");
    const crypto = require("crypto");
    const safeExt = /^[A-Za-z0-9]{1,12}$/.test(source.ext) ? source.ext : "bin";
    const tempPath = path.join(os.tmpdir(), `pichome-sync-replace-${crypto.randomBytes(10).toString("hex")}.${safeExt}`);
    try {
      await downloadFile(source.downloadUrl, tempPath, onProgress, source.size);
      await localItem.replaceFile(tempPath);
      return true;
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
        const neededFolders = new Set((folderData.folders || []).map((folder) => folder.id));
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

  async function forceMutateRemoteItem(libraryId, sourceId, payload) {
    try {
      return await mutateRemoteItem(libraryId, sourceId, payload);
    } catch (error) {
      if (!error.conflict || !error.remoteState?.version) throw error;
      return mutateRemoteItem(libraryId, sourceId, { ...payload, baseVersion: error.remoteState.version });
    }
  }

  async function buildFolderContext(library, remoteFolders) {
    const requiredIds = new Set((remoteFolders || []).map((folder) => folder.id));
    await ensureFolderTargets(library, remoteFolders || [], requiredIds);
    const folders = flattenLocalFolders(await eagle.folder.getAll());
    const root = folders.find((folder) => String(folder.description || "").includes(`pichome-sync:library:${library.id}`));
    const unclassified = folders.find((folder) => String(folder.description || "").includes(`pichome-sync:unclassified:${library.id}`));
    const remoteByLocal = new Map();
    const localByRemote = new Map();
    const managedLocalIds = new Set([root?.id, unclassified?.id].filter(Boolean));
    for (const folder of folders) {
      const marker = String(folder.description || "").match(/pichome-sync:folder:([A-Za-z0-9_-]+)/);
      if (marker) {
        remoteByLocal.set(folder.id, marker[1]);
        localByRemote.set(marker[1], folder.id);
        managedLocalIds.add(folder.id);
      }
    }
    let expanded = true;
    while (expanded) {
      expanded = false;
      for (const folder of folders) {
        if (managedLocalIds.has(folder.id)) continue;
        if (managedLocalIds.has(folderParentId(folder))) {
          managedLocalIds.add(folder.id);
          expanded = true;
        }
      }
    }
    return { root, unclassified, remoteByLocal, localByRemote, managedLocalIds };
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

  function annotationWithSyncId(annotation, sourceId) {
    const clean = String(annotation || "").replace(/\s*\[Pichome Sync ID: [A-Za-z0-9_-]+\]\s*/g, "\n").trim();
    return `${clean}${clean ? "\n\n" : ""}[Pichome Sync ID: ${sourceId}]`;
  }

  async function loadAllRemoteItems(libraryId) {
    const items = [];
    let cursor = 0;
    while (true) {
      const page = await api(`api/v1/libraries/${libraryId}/items?cursor=${cursor}&limit=${PAGE_SIZE}`);
      items.push(...(page.items || []));
      if (page.nextCursor === null || !page.items?.length) break;
      cursor = Number(page.nextCursor);
    }
    return items;
  }

  function mappingsFromAnnotations(items, existing = {}) {
    const mappings = { ...existing };
    for (const item of items || []) {
      const marker = String(item.annotation || "").match(/\[Pichome Sync ID: ([A-Za-z0-9_-]+)\]/);
      if (marker) mappings[marker[1]] = item.id;
    }
    return mappings;
  }

  function resetTrackingForLibrary(libraryId, nextImported) {
    const settings = readSettings();
    const imported = { ...(settings.imported || {}), [libraryId]: nextImported };
    const syncBaselines = { ...(settings.syncBaselines || {}) };
    const conflicts = { ...(settings.conflicts || {}) };
    const sortAligned = { ...(settings.sortAligned || {}) };
    const autoCursors = { ...(settings.autoCursors || {}), [libraryId]: 0 };
    delete syncBaselines[libraryId];
    conflicts[libraryId] = {};
    delete sortAligned[libraryId];
    saveSettings({ imported, syncBaselines, conflicts, sortAligned, autoCursors });
  }

  async function forcePichomeToEagle() {
    if (syncRunning) return;
    const confirmed = window.confirm(
      "确认用 Pichome 强制覆盖 Eagle？\n\n" +
      "• Pichome 的全部有效素材会导入或覆盖到 Eagle\n" +
      "• 名称、标签、备注、评分、分类、未分类和导入时间以 Pichome 为准\n" +
      "• Eagle 当前素材库中所有多出来的素材都会移入回收站\n\n" +
      "该操作不会物理删除原文件，但可能产生大量移动和下载。"
    );
    if (!confirmed) return;
    if (!remoteLibraries.length && !(await testConnection(true))) return;
    const libraryId = elements.librarySelect.value || readSettings().selectedLibrary;
    const library = remoteLibraries.find((entry) => entry.id === libraryId);
    if (!library) return setResult(elements.syncResult, "请先连接并选择素材库。", "error");

    syncRunning = true;
    updateSyncButton();
    let importedCount = 0;
    let updatedCount = 0;
    let trashedCount = 0;
    try {
      setResult(elements.syncResult, "正在校准 Pichome 数据库与源素材库…");
      const reconciled = await postApi(`api/v1/libraries/${libraryId}/reconcile-source`);
      setResult(elements.syncResult, `Pichome 索引已校准 ${Number(reconciled.changed || 0)} 项，正在读取全量清单…`);
      const [remoteItems, remoteStateData, remoteFolderData] = await Promise.all([
        loadAllRemoteItems(libraryId),
        api(`api/v1/libraries/${libraryId}/state`),
        api(`api/v1/libraries/${libraryId}/folders`)
      ]);
      const targets = await ensureFolderTargets(library, remoteFolderData.folders || [], new Set((remoteFolderData.folders || []).map((folder) => folder.id)));
      const remoteStateById = new Map((remoteStateData.items || []).map((item) => [item.id, item]));
      let localItems = await eagle.item.getAll();
      const localById = new Map((localItems || []).map((item) => [item.id, item]));
      const settings = readSettings();
      const libraryImported = mappingsFromAnnotations(localItems, settings.imported?.[libraryId] || {});
      const claimedLocalIds = new Set();
      const total = remoteItems.length + localItems.length;
      let current = 0;
      setOverallProgress(0, total);

      for (const source of remoteItems) {
        const pichomeState = remoteStateById.get(source.id);
        const authoritativeSource = pichomeState ? { ...source, folders: pichomeState.folders || [] } : source;
        current += 1;
        setOverallProgress(current, total);
        setItemProgress(source.name);
        setResult(elements.syncResult, `Pichome → Eagle：正在强制同步 ${source.name}`);
        let localItem = localById.get(libraryImported[source.id]) || null;
        if (!localItem && libraryImported[source.id]) {
          try { localItem = await eagle.item.getById(libraryImported[source.id]); } catch (_) {}
        }
        const folderIds = (authoritativeSource.folders || []).map((id) => targets.sourceToLocal.get(id)?.id).filter(Boolean);
        if (!localItem) {
          const localId = await importItem(authoritativeSource, folderIds, (loaded, size) => setItemProgress(source.name, loaded, size));
          libraryImported[source.id] = localId;
          localItem = await eagle.item.getById(localId);
          localById.set(localId, localItem);
          importedCount += 1;
        } else {
          if (localItem.isDeleted && typeof localItem.restoreFromTrash === "function") {
            await localItem.restoreFromTrash();
            try { localItem = await eagle.item.getById(localItem.id); } catch (_) {}
          }
          if (Number(authoritativeSource.size) > 0 && Number(localItem.size) > 0 && Number(authoritativeSource.size) !== Number(localItem.size)) {
            await replaceItemFromPichome(localItem, authoritativeSource, (loaded, size) => setItemProgress(source.name, loaded, size));
            try { localItem = await eagle.item.getById(localItem.id); } catch (_) {}
          }
          localItem.name = authoritativeSource.name;
          localItem.tags = [...(authoritativeSource.tags || [])];
          localItem.annotation = annotationWithSyncId(authoritativeSource.annotation, source.id);
          localItem.url = authoritativeSource.website || "";
          localItem.star = Number(authoritativeSource.star || 0);
          localItem.folders = folderIds;
          if (Number(authoritativeSource.importedAt) > 0) localItem.importedAt = Number(authoritativeSource.importedAt);
          await localItem.save();
          updatedCount += 1;
        }
        claimedLocalIds.add(localItem.id);
        setItemProgress(source.name, 1, 1, true);
      }

      localItems = await eagle.item.getAll();
      for (const item of localItems || []) {
        current += 1;
        setOverallProgress(current, total);
        if (!claimedLocalIds.has(item.id)) {
          setItemProgress(item.name);
          await item.moveToTrash();
          trashedCount += 1;
          setItemProgress(item.name, 1, 1, true);
        }
      }
      resetTrackingForLibrary(libraryId, libraryImported);
      setOverallProgress(total, total);
      setResult(elements.syncResult, `强制同步完成：导入 ${importedCount} 项，覆盖 ${updatedCount} 项，移入 Eagle 回收站 ${trashedCount} 项。`, "success");
    } catch (error) {
      setResult(elements.syncResult, `Pichome → Eagle 强制同步失败：${error.message}`, "error");
    } finally {
      syncRunning = false;
      updateSyncButton();
      await refreshItemCounts(true);
    }
  }

  async function forceEagleToPichome() {
    if (syncRunning) return;
    const confirmed = window.confirm(
      "确认用 Eagle 强制覆盖 Pichome？\n\n" +
      "• Eagle 当前素材库中的全部新素材都会上传\n" +
      "• 已映射素材的分类、未分类和删除状态以 Eagle 为准\n" +
      "• Pichome 中在 Eagle 侧不存在的素材会移入回收站\n" +
      "• 不在 Pichome 映射文件夹中的 Eagle 素材会作为未分类上传\n\n" +
      "请确认 Eagle 当前同步范围完整，避免将 Pichome 素材批量移入回收站。"
    );
    if (!confirmed) return;
    if (!remoteLibraries.length && !(await testConnection(true))) return;
    const libraryId = elements.librarySelect.value || readSettings().selectedLibrary;
    const library = remoteLibraries.find((entry) => entry.id === libraryId);
    if (!library) return setResult(elements.syncResult, "请先连接并选择素材库。", "error");

    let uploadedCount = 0;
    let updatedCount = 0;
    let trashedCount = 0;
    syncRunning = true;
    updateSyncButton();
    try {
      const remoteFolderData = await api(`api/v1/libraries/${libraryId}/folders`);
      const folderContext = await buildFolderContext(library, remoteFolderData.folders || []);
      const localItems = await eagle.item.getAll();
      const localById = new Map((localItems || []).map((item) => [item.id, item]));
      const settings = readSettings();
      const libraryImported = mappingsFromAnnotations(localItems, settings.imported?.[libraryId] || {});
      const knownLocalIds = new Set(Object.values(libraryImported));
      const uploadCandidates = (localItems || []).filter((item) => !knownLocalIds.has(item.id));
      setOverallProgress(0, uploadCandidates.length);
      let uploadedCurrent = 0;
      for (const localItem of uploadCandidates) {
        const filePath = localItem.filePath;
        if (!filePath || !require("fs").existsSync(filePath)) throw new Error(`${localItem.name} 的原文件不可用，无法上传`);
        const localState = localStateForItem(localItem, folderContext);
        const firstFolder = localState.folders[0] || "";
        const filename = `${localItem.name}.${localItem.ext}`;
        setResult(elements.syncResult, `Eagle → Pichome：正在上传 ${filename}`);
        setItemProgress(filename);
        const response = await uploadFile(filePath, { filename, folderId: firstFolder }, (loaded, size) => setItemProgress(filename, loaded, size));
        if (!response.item?.id) throw new Error(`${filename} 回传后未收到素材 ID`);
        libraryImported[response.item.id] = localItem.id;
        knownLocalIds.add(localItem.id);
        uploadedCount += 1;
        uploadedCurrent += 1;
        setOverallProgress(uploadedCurrent, uploadCandidates.length);
        setItemProgress(filename, 1, 1, true);
      }

      const remoteStateData = await api(`api/v1/libraries/${libraryId}/state`);
      const remoteById = new Map((remoteStateData.items || []).map((item) => [item.id, item]));
      const total = remoteById.size;
      let current = 0;
      setOverallProgress(0, total);

      for (const [sourceId, remoteState] of remoteById) {
        current += 1;
        setOverallProgress(current, total);
        const localItem = localById.get(libraryImported[sourceId]) || null;
        setItemProgress(localItem?.name || sourceId);
        if (!localItem) {
          if (!remoteState.isDeleted && !remoteState.tombstone) {
            setResult(elements.syncResult, `Eagle → Pichome：正在将 ${sourceId} 移入 Pichome 回收站`);
            await forceMutateRemoteItem(libraryId, sourceId, {
              baseVersion: remoteState.version,
              folders: remoteState.folders || [],
              isDeleted: true
            });
            trashedCount += 1;
          }
          setItemProgress(localItem?.name || sourceId, 1, 1, true);
          continue;
        }
        const localState = localStateForItem(localItem, folderContext);
        if (remoteState.tombstone) {
          const filePath = localItem.filePath;
          if (!filePath || !require("fs").existsSync(filePath)) throw new Error(`${localItem.name} 的原文件不可用，无法重新上传`);
          const firstFolder = localState.folders[0] || "";
          const filename = `${localItem.name}.${localItem.ext}`;
          const response = await uploadFile(filePath, { filename, folderId: firstFolder }, (loaded, size) => setItemProgress(localItem.name, loaded, size));
          if (!response.item?.id) throw new Error(`${localItem.name} 回传后未收到素材 ID`);
          delete libraryImported[sourceId];
          libraryImported[response.item.id] = localItem.id;
          uploadedCount += 1;
        } else if (remoteState.isDeleted || !arraysEqual(remoteState.folders, localState.folders)) {
          setResult(elements.syncResult, `Eagle → Pichome：正在覆盖 ${localItem.name}`);
          await forceMutateRemoteItem(libraryId, sourceId, {
            baseVersion: remoteState.version,
            folders: localState.folders,
            isDeleted: false
          });
          updatedCount += 1;
        }
        setItemProgress(localItem.name, 1, 1, true);
      }
      resetTrackingForLibrary(libraryId, libraryImported);
      setOverallProgress(total, total);
      setResult(elements.syncResult, `强制同步完成：上传 ${uploadedCount} 项，覆盖 ${updatedCount} 项，移入 Pichome 回收站 ${trashedCount} 项。`, "success");
    } catch (error) {
      setResult(elements.syncResult, `Eagle → Pichome 强制同步失败：${error.message}`, "error");
    } finally {
      syncRunning = false;
      updateSyncButton();
      await refreshItemCounts(true);
    }
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
      const allSortAligned = { ...(settings.sortAligned || {}) };
      const sortAligned = { ...(allSortAligned[libraryId] || {}) };
      let moved = 0;
      let deleted = 0;
      let metadataAligned = 0;
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
        let remoteState = remoteById.get(sourceId);
        if (!remoteState) {
          conflicts[sourceId] = { sourceId, localId, detectedAt: Date.now(), reason: "Pichome 未返回该素材，也没有明确删除记录，因此没有传播删除" };
          conflictCount += 1;
          setItemProgress(itemName, 1, 1, true);
          continue;
        }
        let localState = localStateForItem(localItem, folderContext);
        let confirmedDeletedMissing = false;
        if (localState.missing) {
          if (remoteState.isDeleted && (remoteState.tombstone || remoteState.syncEvent?.kind === "delete")) {
            localState = { missing: false, isDeleted: true, modifiedAt: Number(remoteState.syncEvent?.eventTime || remoteState.modifiedAt || 0), folders: [] };
            confirmedDeletedMissing = true;
          } else {
            conflicts[sourceId] = { sourceId, localId, detectedAt: Date.now(), reason: "Eagle 未返回该素材，无法确认是删除还是暂时不可用，因此没有传播删除" };
            conflictCount += 1;
            setItemProgress(itemName, 1, 1, true);
            continue;
          }
        }

        if (localItem && !remoteState.isDeleted && !(remoteState.folders || []).length && localState.folders.length === 0) {
          const managedFolders = itemFolderIds(localItem).filter((id) => folderContext.managedLocalIds.has(id));
          if (managedFolders.length) {
            const unmanagedFolders = itemFolderIds(localItem).filter((id) => !folderContext.managedLocalIds.has(id));
            localItem.folders = sortedUnique(unmanagedFolders);
            await localItem.save();
            moved += 1;
            localState = localStateForItem(localItem, folderContext);
          }
        }

        const sourceImportedAt = Number(remoteState.importedAt || 0);
        const alignedImportedAt = Number(sortAligned[sourceId] || 0);
        if (localItem && sourceImportedAt > 0 && metadataAligned < 30 && alignedImportedAt !== sourceImportedAt && alignedImportedAt !== -sourceImportedAt) {
          try {
            localItem.importedAt = sourceImportedAt;
            await localItem.save();
            sortAligned[sourceId] = sourceImportedAt;
            metadataAligned += 1;
            localState = localStateForItem(localItem, folderContext);
          } catch (_) {
            sortAligned[sourceId] = -sourceImportedAt;
          }
        }
        if (confirmedDeletedMissing) {
          baselines[sourceId] = makeBaseline(remoteState, localState);
          delete conflicts[sourceId];
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
      allSortAligned[libraryId] = sortAligned;
      saveSettings({ syncBaselines, sortAligned: allSortAligned, conflicts: allConflicts });
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

  function setAutoMode(mode) {
    const settings = readSettings();
    saveSettings({ autoMode: mode, enabled: mode === "off" ? settings.enabled : true });
    renderState();
    refreshReactiveSync();
    if (mode !== "off") runAutoCycle();
  }

  function initialize() {
    if (initialized) return;
    initialized = true;
    [
      "runtimeBadge", "syncToggle", "syncStatus", "statusHint", "serverUrl", "saveButton", "testButton",
      "connectionResult", "pluginState", "libraryName", "libraryPath", "librarySelect",
      "forceFromPichomeButton", "forceToPichomeButton", "syncResult", "remoteCount", "progressBar", "overallProgressText", "currentItemName",
      "itemProgressBar", "itemProgressText", "pichomeItemCount", "eagleItemCount",
      "oneWayToggle", "twoWayToggle", "modeHint",
      "oneWayButton", "twoWayButton"
    ].forEach((id) => { elements[id] = document.getElementById(id); });

    const settings = readSettings();
    elements.serverUrl.value = settings.serverUrl || DEFAULT_SERVER;
    renderState();
    elements.syncToggle.addEventListener("change", () => { saveSettings({ enabled: elements.syncToggle.checked }); renderState(); refreshReactiveSync(); if (elements.syncToggle.checked) runAutoCycle(); });
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
        connectRemoteEvents();
        setResult(elements.connectionResult, "设置已保存。", "success");
      } catch (error) { setResult(elements.connectionResult, error.message, "error"); }
    });
    elements.testButton.addEventListener("click", () => testConnection(false));
    elements.librarySelect.addEventListener("change", () => {
      saveSettings({ selectedLibrary: elements.librarySelect.value });
      updateSyncButton();
      refreshItemCounts(false);
    });
    elements.forceFromPichomeButton.addEventListener("click", forcePichomeToEagle);
    elements.forceToPichomeButton.addEventListener("click", forceEagleToPichome);
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
    if (typeof eagle.onPluginBeforeExit === "function") eagle.onPluginBeforeExit(() => {
      if (autoTimer) clearInterval(autoTimer);
      if (triggerTimer) clearTimeout(triggerTimer);
      closeReactiveSync();
    });
  } else {
    window.addEventListener("DOMContentLoaded", initialize, { once: true });
  }
})();
