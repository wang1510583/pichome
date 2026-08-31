package top.lempet.pichome;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private, login-scoped media cache for Pichome media and revisioned frame-atlas endpoints.
 * Images and one-second frame atlases are cached while displayed. Partial video requests trigger
 * one background download so a later preview can be served entirely from app-private storage.
 */
final class MediaDiskCache {
    private static final long DEFAULT_MAX_CACHE_BYTES = 4L * 1024L * 1024L * 1024L;
    private static final long MIN_CACHE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_CONFIGURABLE_CACHE_BYTES = 32L * 1024L * 1024L * 1024L;
    private static final long MAX_SINGLE_FILE_BYTES = 512L * 1024L * 1024L;
    private static final long MIN_FREE_SPACE_BYTES = 512L * 1024L * 1024L;
    private static final long DEFAULT_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final Pattern MAX_AGE_PATTERN =
            Pattern.compile("(?:^|,)\\s*max-age\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE_PATTERN =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_MEDIA_URL_PATTERN = Pattern.compile(
            "https?://pichome\\.lempet\\.top:5575/index\\.php\\?mod=io(?:&|&amp;)op=getStream[^\\\"'\\s<]*",
            Pattern.CASE_INSENSITIVE
    );

    private final File directory;
    private final String allowedHost;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService localClients = Executors.newCachedThreadPool();
    private final Set<String> inFlight = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> localPlaybackKeys = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong discoveredCount = new AtomicLong();
    private final AtomicLong startedCount = new AtomicLong();
    private final AtomicLong completedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong localPlaybackCount = new AtomicLong();
    private final String localToken = UUID.randomUUID().toString().replace("-", "");
    private volatile ServerSocket localServer;
    private volatile Thread localServerThread;
    private volatile String lastDiagnostic = "等待发现详情素材";
    private volatile long maxCacheBytes;

    MediaDiskCache(Context context, String allowedHost) {
        this.directory = preparePersistentDirectory(context);
        this.allowedHost = allowedHost;
        this.preferences = context.getSharedPreferences("pichome_media_cache", Context.MODE_PRIVATE);
        this.maxCacheBytes = clampCacheSize(preferences.getLong(
                "max_cache_bytes",
                DEFAULT_MAX_CACHE_BYTES
        ));
        if (!directory.exists()) {
            directory.mkdirs();
        }
        startLocalServer();
        worker.execute(() -> {
            try {
                cleanup(0);
            } catch (Throwable ignored) {
                // Cache maintenance must never terminate the app process.
            }
        });
    }

    private static File preparePersistentDirectory(Context context) {
        File legacyDirectory = new File(context.getCacheDir(), "pichome-media-v1");
        File persistentDirectory = new File(
                context.getNoBackupFilesDir(),
                "pichome-media-v1"
        );

        // A directory rename keeps completed bodies, metadata and their LRU timestamps intact.
        if (!persistentDirectory.exists()
                && legacyDirectory.isDirectory()
                && legacyDirectory.renameTo(persistentDirectory)) {
            return persistentDirectory;
        }
        if (!persistentDirectory.exists()) {
            persistentDirectory.mkdirs();
        }
        migrateCompletedEntries(legacyDirectory, persistentDirectory);
        return persistentDirectory;
    }

    private static void migrateCompletedEntries(File legacyDirectory, File persistentDirectory) {
        if (!legacyDirectory.isDirectory()
                || !persistentDirectory.isDirectory()
                || legacyDirectory.equals(persistentDirectory)) {
            return;
        }
        File[] legacyFiles = legacyDirectory.listFiles();
        if (legacyFiles == null) return;
        for (File legacyBody : legacyFiles) {
            String name = legacyBody.getName();
            if (!legacyBody.isFile()
                    || !name.matches("[0-9a-f]{64}\\.body")) {
                continue;
            }
            String key = name.substring(0, name.length() - ".body".length());
            File legacyMetadata = new File(legacyDirectory, key + ".meta");
            if (!legacyMetadata.isFile()) continue;

            File persistentBody = new File(persistentDirectory, key + ".body");
            File persistentMetadata = new File(persistentDirectory, key + ".meta");
            if (persistentBody.exists() || persistentMetadata.exists()) continue;
            if (!legacyBody.renameTo(persistentBody)) continue;
            if (!legacyMetadata.renameTo(persistentMetadata)) {
                persistentBody.renameTo(legacyBody);
            }
        }
    }

    WebResourceResponse intercept(WebResourceRequest request, String fallbackUserAgent) {
        if (request == null || !"GET".equalsIgnoreCase(request.getMethod())) {
            return null;
        }

        Uri uri = request.getUrl();
        if (isPlayerPageUri(uri)) {
            WebResourceResponse cachedPlayer = serveCachedPlayerPage(
                    uri,
                    fallbackUserAgent,
                    request.getRequestHeaders()
            );
            if (cachedPlayer != null) return cachedPlayer;
        }
        if (!isCacheableMediaUri(uri)) {
            return null;
        }

        String url = uri.toString();
        String cookie = CookieManager.getInstance().getCookie(url);
        // Session cookies may be rotated even when the underlying media URL is unchanged.
        // Prefer the server-provided resource id because encrypted media paths can change after
        // a new login/app process even though the underlying Pichome resource is unchanged.
        String stableIdentity = stableMediaIdentity(uri);
        String key = stableIdentity.isEmpty()
                ? sha256(url)
                : cacheKey(url, stableIdentity);
        Entry entry = readEntry(key);
        if (entry != null) {
            if (entry.expiresAt >= System.currentTimeMillis() || validate(entry, url, cookie, fallbackUserAgent)) {
                return serveEntry(entry, request.getRequestHeaders());
            }
            deleteEntry(key);
        }

        return fetchFromNetwork(
                key,
                url,
                cookie,
                fallbackUserAgent,
                request.getRequestHeaders(),
                isStreamUri(uri)
        );
    }

    void shutdown() {
        try {
            if (localServer != null) localServer.close();
        } catch (IOException ignored) {
            // Closing the listener is only used to wake its accept loop.
        }
        localClients.shutdownNow();
        worker.shutdownNow();
    }

    void prefetch(String url, String cacheIdentity, String fallbackUserAgent) {
        discoveredCount.incrementAndGet();
        try {
            Uri uri = Uri.parse(url);
            if (!isCacheableMediaUri(uri)) {
                lastDiagnostic = "已发现素材，但地址不是可缓存媒体";
                return;
            }
            String key = cacheKey(uri.toString(), cacheIdentity);
            Entry existing = readEntry(key);
            if (existing == null && migrateLegacyUrlEntry(uri.toString(), key)) {
                existing = readEntry(key);
            }
            if (existing != null) {
                lastDiagnostic = "素材已在本地缓存";
                return;
            }
            String cookie = CookieManager.getInstance().getCookie(uri.toString());
            lastDiagnostic = "素材已加入后台缓存队列";
            schedulePrefetch(key, uri.toString(), cookie, fallbackUserAgent);
        } catch (RuntimeException error) {
            failedCount.incrementAndGet();
            lastDiagnostic = "缓存地址处理失败：" + error.getClass().getSimpleName();
        }
    }

    String getLocalUrl(String url, String cacheIdentity) {
        try {
            Uri uri = Uri.parse(url);
            if (!isCacheableMediaUri(uri) || localServer == null || localServer.isClosed()) return "";
            String key = cacheKey(uri.toString(), cacheIdentity);
            Entry entry = readEntry(key);
            if (entry == null && migrateLegacyUrlEntry(uri.toString(), key)) {
                entry = readEntry(key);
            }
            if (entry == null) return "";
            if (localPlaybackKeys.add(key)) {
                localPlaybackCount.incrementAndGet();
            }
            lastDiagnostic = "播放器已直接使用本地素材缓存";
            return "http://127.0.0.1:" + localServer.getLocalPort()
                    + "/" + localToken + "/" + key;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    CacheStats getStats() {
        long totalBytes = 0;
        long pendingBytes = 0;
        int itemCount = 0;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".body")) {
                    totalBytes += file.length();
                    itemCount++;
                } else if (file.getName().endsWith(".part")) {
                    pendingBytes += file.length();
                }
            }
        }
        return new CacheStats(
                totalBytes,
                itemCount,
                maxCacheBytes,
                inFlight.size(),
                pendingBytes,
                discoveredCount.get(),
                startedCount.get(),
                completedCount.get(),
                failedCount.get(),
                localPlaybackCount.get(),
                lastDiagnostic
        );
    }

    void clearAll(Runnable completion) {
        generation.incrementAndGet();
        Thread clearThread = new Thread(() -> {
            try {
                File[] files = directory.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
            } catch (Throwable ignored) {
                // The settings dialog reports completion without taking down the app.
            } finally {
                if (completion != null) completion.run();
            }
        }, "pichome-cache-clear");
        clearThread.start();
    }

    void setMaxCacheBytes(long bytes, Runnable completion) {
        maxCacheBytes = clampCacheSize(bytes);
        preferences.edit().putLong("max_cache_bytes", maxCacheBytes).apply();
        Thread cleanupThread = new Thread(() -> {
            try {
                cleanup(0);
            } catch (Throwable ignored) {
                // Keep the selected limit even if this cleanup pass cannot run.
            } finally {
                if (completion != null) completion.run();
            }
        }, "pichome-cache-resize");
        cleanupThread.start();
    }

    private long clampCacheSize(long bytes) {
        return Math.max(MIN_CACHE_BYTES, Math.min(MAX_CONFIGURABLE_CACHE_BYTES, bytes));
    }

    private boolean isCacheableMediaUri(Uri uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !allowedHost.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        String query = uri.getEncodedQuery();
        if (query == null) return false;
        String normalized = query.toLowerCase(Locale.US);
        boolean regularMedia = hasQueryValue(normalized, "mod", "io")
                && (hasQueryValue(normalized, "op", "getimg")
                || hasQueryValue(normalized, "op", "getstream"));
        boolean frameAtlas = hasQueryValue(normalized, "mod", "details")
                && hasQueryValue(normalized, "op", "fileinterface")
                && hasQueryValue(normalized, "operation", "frameatlas");
        return regularMedia || frameAtlas;
    }

    private boolean isPlayerPageUri(Uri uri) {
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !allowedHost.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        try {
            return "xgplayer".equalsIgnoreCase(uri.getQueryParameter("mod"))
                    && uri.getQueryParameter("path") != null
                    && !uri.getQueryParameter("path").isEmpty();
        } catch (UnsupportedOperationException ignored) {
            return false;
        }
    }

    private WebResourceResponse serveCachedPlayerPage(
            Uri playerUri,
            String fallbackUserAgent,
            Map<String, String> requestHeaders
    ) {
        String path = playerUri.getQueryParameter("path");
        String rid = playerUri.getQueryParameter("pichome_rid");
        String stableRid = rid == null ? "" : rid.trim();
        String identity = !stableRid.isEmpty() && stableRid.length() <= 256
                ? "rid:" + stableRid + ":video"
                : "xgplayer:" + path;
        String key = cacheKey(playerUri.toString(), identity);
        Entry entry = readEntry(key);
        if (entry == null || localServer == null || localServer.isClosed()) return null;
        String localUrl = "http://127.0.0.1:" + localServer.getLocalPort()
                + "/" + localToken + "/" + key;
        HttpURLConnection connection = null;
        try {
            String url = playerUri.toString();
            String cookie = CookieManager.getInstance().getCookie(url);
            connection = openConnection(url, cookie, fallbackUserAgent, requestHeaders, false);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] block = new byte[16 * 1024];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (buffer.size() + count > 4 * 1024 * 1024) return null;
                    buffer.write(block, 0, count);
                }
            }
            String html = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            Matcher matcher = PLAYER_MEDIA_URL_PATTERN.matcher(html);
            if (!matcher.find()) {
                lastDiagnostic = "已命中视频缓存，但播放器地址替换失败";
                return null;
            }
            html = matcher.replaceAll(Matcher.quoteReplacement(localUrl));
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            headers.put("Access-Control-Allow-Origin", "*");
            if (localPlaybackKeys.add(key)) {
                localPlaybackCount.incrementAndGet();
            }
            lastDiagnostic = "已从本地缓存初始化视频播放器";
            return new WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))
            );
        } catch (IOException | RuntimeException error) {
            lastDiagnostic = "本地播放器初始化失败：" + error.getClass().getSimpleName();
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean isStreamUri(Uri uri) {
        String query = uri == null ? null : uri.getEncodedQuery();
        return query != null
                && hasQueryValue(query.toLowerCase(Locale.US), "op", "getstream");
    }

    private String stableMediaIdentity(Uri uri) {
        if (uri == null) return "";
        try {
            if ("details".equalsIgnoreCase(uri.getQueryParameter("mod"))
                    && "fileinterface".equalsIgnoreCase(uri.getQueryParameter("op"))
                    && "frameAtlas".equalsIgnoreCase(uri.getQueryParameter("operation"))) {
                String atlasRid = valueOrEmpty(uri.getQueryParameter("pichome_atlas_rid")).trim();
                String revision = valueOrEmpty(uri.getQueryParameter("revision")).trim();
                String second = valueOrEmpty(uri.getQueryParameter("second")).trim();
                String version = valueOrEmpty(uri.getQueryParameter("pichome_atlas_version")).trim();
                if (!atlasRid.isEmpty() && atlasRid.length() <= 256
                        && revision.matches("[0-9a-f]{24}")
                        && second.matches("\\d{1,9}")
                        && version.matches("\\d{1,3}")) {
                    return "atlas:" + atlasRid + ":" + revision + ":" + second + ":v" + version;
                }
                return "";
            }
            String rid = uri.getQueryParameter("pichome_rid");
            if (rid == null) return "";
            rid = rid.trim();
            if (rid.isEmpty() || rid.length() > 256) return "";
            return "rid:" + rid + ":" + (isStreamUri(uri) ? "video" : "img");
        } catch (UnsupportedOperationException ignored) {
            return "";
        }
    }

    private boolean hasQueryValue(String query, String name, String value) {
        String pair = name + "=" + value;
        return query.equals(pair)
                || query.startsWith(pair + "&")
                || query.endsWith("&" + pair)
                || query.contains("&" + pair + "&");
    }

    private String cacheKey(String url, String cacheIdentity) {
        String identity = cacheIdentity == null ? "" : cacheIdentity.trim();
        if (identity.length() > 2048) identity = "";
        return sha256(identity.isEmpty() ? "url:" + url : "media:" + identity);
    }

    private synchronized boolean migrateLegacyUrlEntry(String url, String targetKey) {
        String legacyKey = sha256(url);
        if (legacyKey.equals(targetKey) || readEntry(legacyKey) == null) return false;
        File sourceBody = bodyFile(legacyKey);
        File sourceMetadata = metadataFile(legacyKey);
        File targetBody = bodyFile(targetKey);
        File targetMetadata = metadataFile(targetKey);
        if (targetBody.exists() || targetMetadata.exists()) return false;
        if (!sourceBody.renameTo(targetBody)) return false;
        if (!sourceMetadata.renameTo(targetMetadata)) {
            targetBody.renameTo(sourceBody);
            return false;
        }
        return true;
    }

    private WebResourceResponse fetchFromNetwork(
            String key,
            String url,
            String cookie,
            String fallbackUserAgent,
            Map<String, String> requestHeaders,
            boolean prefetchPartial
    ) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, cookie, fallbackUserAgent, requestHeaders, false);
            int statusCode = connection.getResponseCode();
            if (statusCode >= 300 && statusCode <= 399) {
                connection.disconnect();
                return null;
            }

            rememberResponseCookies(url, connection);
            Map<String, String> responseHeaders = flattenHeaders(connection);
            String contentType = connection.getContentType();
            String mimeType = parseMimeType(contentType);
            String encoding = parseCharset(contentType);
            long contentLength = getContentLength(connection);
            InputStream source = statusCode >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            if (source == null) {
                source = new ByteArrayInputStream(new byte[0]);
            }

            boolean cacheable = statusCode >= 200
                    && statusCode < 300
                    && isResponseCacheable(connection)
                    && isCompleteRepresentation(connection, statusCode, contentLength)
                    && canStore(contentLength);

            InputStream responseBody;
            if (cacheable) {
                File temporary = File.createTempFile(key + "-", ".part", directory);
                Entry pending = entryFromResponse(key, mimeType, encoding, connection, contentLength);
                responseBody = new CachingInputStream(
                        source,
                        connection,
                        temporary,
                        pending,
                        contentLength
                );
            } else {
                responseBody = new DisconnectingInputStream(source, connection);
                if (prefetchPartial && statusCode >= 200 && statusCode < 300) {
                    schedulePrefetch(key, url, cookie, fallbackUserAgent);
                }
            }

            return new WebResourceResponse(
                    mimeType,
                    encoding,
                    statusCode,
                    reasonPhrase(statusCode, connection.getResponseMessage()),
                    responseHeaders,
                    responseBody
            );
        } catch (IOException | RuntimeException error) {
            if (connection != null) {
                connection.disconnect();
            }
            return null;
        }
    }

    private HttpURLConnection openConnection(
            String url,
            String cookie,
            String fallbackUserAgent,
            Map<String, String> requestHeaders,
            boolean head
    ) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(head ? "HEAD" : "GET");
        connection.setUseCaches(false);

        if (requestHeaders != null) {
            for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
                String name = header.getKey();
                if (name == null || shouldSkipRequestHeader(name, head)) {
                    continue;
                }
                connection.setRequestProperty(name, header.getValue());
            }
        }
        connection.setRequestProperty("Accept-Encoding", "identity");
        String userAgent = connection.getRequestProperty("User-Agent");
        if (userAgent == null || userAgent.isEmpty()) {
            userAgent = fallbackUserAgent == null ? "PichomeAndroid" : fallbackUserAgent;
        }
        if (!userAgent.contains("PichomeMediaCache/")) {
            userAgent += " PichomeMediaCache/1.0";
        }
        connection.setRequestProperty("User-Agent", userAgent);
        if (cookie != null && !cookie.isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return connection;
    }

    private boolean shouldSkipRequestHeader(String name, boolean head) {
        String lower = name.toLowerCase(Locale.US);
        return "host".equals(lower)
                || "connection".equals(lower)
                || "content-length".equals(lower)
                || "accept-encoding".equals(lower)
                || "cookie".equals(lower)
                || "if-none-match".equals(lower)
                || "if-modified-since".equals(lower)
                || (head && ("range".equals(lower) || "if-range".equals(lower)));
    }

    private boolean validate(Entry entry, String url, String cookie, String fallbackUserAgent) {
        HttpURLConnection connection = null;
        try {
            connection = openConnection(url, cookie, fallbackUserAgent, Collections.emptyMap(), true);
            if (entry.etag != null && !entry.etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", entry.etag);
            }
            if (entry.lastModified != null && !entry.lastModified.isEmpty()) {
                connection.setRequestProperty("If-Modified-Since", entry.lastModified);
            }
            int statusCode = connection.getResponseCode();
            rememberResponseCookies(url, connection);
            boolean unchanged = statusCode == HttpURLConnection.HTTP_NOT_MODIFIED;
            if (!unchanged && statusCode >= 200 && statusCode < 300) {
                String remoteEtag = connection.getHeaderField("ETag");
                long remoteLength = getContentLength(connection);
                unchanged = remoteEtag != null
                        && remoteEtag.equals(entry.etag)
                        && (remoteLength < 0 || remoteLength == entry.length);
            }
            if (unchanged) {
                entry.expiresAt = calculateExpiresAt(connection);
                writeEntryMetadata(entry);
                touch(entry);
                return true;
            }
            return statusCode >= 500;
        } catch (IOException | RuntimeException ignored) {
            // Stale-if-error: a previously verified private cache is better than a blank preview.
            return true;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private WebResourceResponse serveEntry(Entry entry, Map<String, String> requestHeaders) {
        Range range = null;
        String rangeHeader = findHeader(requestHeaders, "Range");
        String ifRange = findHeader(requestHeaders, "If-Range");
        if (rangeHeader != null && ifRangeMatches(ifRange, entry)) {
            range = parseRange(rangeHeader, entry.length);
            if (range == null) {
                Map<String, String> headers = baseCacheHeaders(entry);
                headers.put("Content-Range", "bytes */" + entry.length);
                return new WebResourceResponse(
                        entry.mimeType,
                        entry.encoding,
                        416,
                        "Requested Range Not Satisfiable",
                        headers,
                        new ByteArrayInputStream(new byte[0])
                );
            }
        }

        try {
            long start = range == null ? 0 : range.start;
            long end = range == null ? entry.length - 1 : range.end;
            long responseLength = Math.max(0, end - start + 1);
            InputStream body = new RandomAccessFileInputStream(entry.body, start, responseLength);
            Map<String, String> headers = baseCacheHeaders(entry);
            headers.put("Content-Length", Long.toString(responseLength));
            int status = 200;
            String reason = "OK";
            if (range != null) {
                status = 206;
                reason = "Partial Content";
                headers.put(
                        "Content-Range",
                        "bytes " + start + "-" + end + "/" + entry.length
                );
            }
            touch(entry);
            return new WebResourceResponse(
                    entry.mimeType,
                    entry.encoding,
                    status,
                    reason,
                    headers,
                    body
            );
        } catch (IOException error) {
            deleteEntry(entry.key);
            return null;
        }
    }

    private Map<String, String> baseCacheHeaders(Entry entry) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "private, max-age=" + Math.max(
                0,
                (entry.expiresAt - System.currentTimeMillis()) / 1000L
        ));
        headers.put("Access-Control-Allow-Origin", "*");
        if (entry.etag != null && !entry.etag.isEmpty()) {
            headers.put("ETag", entry.etag);
        }
        if (entry.lastModified != null && !entry.lastModified.isEmpty()) {
            headers.put("Last-Modified", entry.lastModified);
        }
        return headers;
    }

    private void startLocalServer() {
        try {
            localServer = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            localServerThread = new Thread(() -> {
                while (localServer != null && !localServer.isClosed()) {
                    try {
                        Socket socket = localServer.accept();
                        localClients.execute(() -> serveLocalRequest(socket));
                    } catch (IOException error) {
                        if (localServer != null && !localServer.isClosed()) {
                            // A later app restart will retry listener creation.
                        }
                        break;
                    }
                }
            }, "pichome-cache-server");
            localServerThread.setDaemon(true);
            localServerThread.start();
        } catch (IOException error) {
            localServer = null;
        }
    }

    private void serveLocalRequest(Socket socket) {
        try (Socket connection = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     connection.getInputStream(),
                     StandardCharsets.ISO_8859_1
             ));
             OutputStream output = connection.getOutputStream()) {
            connection.setSoTimeout(10_000);
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2
                    || !("GET".equals(requestParts[0]) || "HEAD".equals(requestParts[0]))) {
                writeLocalError(output, 405, "Method Not Allowed");
                return;
            }

            String rangeHeader = null;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                int separator = headerLine.indexOf(':');
                if (separator > 0 && "range".equalsIgnoreCase(headerLine.substring(0, separator).trim())) {
                    rangeHeader = headerLine.substring(separator + 1).trim();
                }
            }

            String[] pathParts = requestParts[1].split("\\?")[0].split("/");
            if (pathParts.length != 3
                    || !localToken.equals(pathParts[1])
                    || !pathParts[2].matches("[0-9a-f]{64}")) {
                writeLocalError(output, 404, "Not Found");
                return;
            }
            Entry entry = readEntry(pathParts[2]);
            if (entry == null) {
                writeLocalError(output, 404, "Not Found");
                return;
            }

            Range range = rangeHeader == null ? null : parseRange(rangeHeader, entry.length);
            if (rangeHeader != null && range == null) {
                writeAscii(output, "HTTP/1.1 416 Requested Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + entry.length + "\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                return;
            }
            long start = range == null ? 0 : range.start;
            long end = range == null ? entry.length - 1 : range.end;
            long responseLength = Math.max(0, end - start + 1);
            StringBuilder headers = new StringBuilder();
            headers.append(range == null ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 206 Partial Content\r\n");
            headers.append("Content-Type: ").append(valueOrEmpty(entry.mimeType)).append("\r\n");
            headers.append("Content-Length: ").append(responseLength).append("\r\n");
            headers.append("Accept-Ranges: bytes\r\n");
            headers.append("Cache-Control: no-store\r\n");
            headers.append("Access-Control-Allow-Origin: *\r\n");
            if (range != null) {
                headers.append("Content-Range: bytes ").append(start).append('-').append(end)
                        .append('/').append(entry.length).append("\r\n");
            }
            headers.append("Connection: close\r\n\r\n");
            writeAscii(output, headers.toString());
            if ("HEAD".equals(requestParts[0])) return;

            try (RandomAccessFileInputStream input = new RandomAccessFileInputStream(
                    entry.body,
                    start,
                    responseLength
            )) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
                touch(entry);
            }
        } catch (IOException | RuntimeException ignored) {
            // Media clients routinely close Range streams early while seeking.
        }
    }

    private void writeLocalError(OutputStream output, int status, String reason) throws IOException {
        writeAscii(output, "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n");
    }

    private void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void schedulePrefetch(String key, String url, String cookie, String userAgent) {
        if (!inFlight.add(key)) {
            return;
        }
        worker.execute(() -> {
            HttpURLConnection connection = null;
            File temporary = null;
            boolean completed = false;
            try {
                if (readEntry(key) != null) {
                    lastDiagnostic = "素材已在本地缓存";
                    completed = true;
                    return;
                }
                startedCount.incrementAndGet();
                lastDiagnostic = "正在连接素材服务器";
                connection = openConnection(url, cookie, userAgent, Collections.emptyMap(), false);
                int statusCode = connection.getResponseCode();
                if (statusCode != HttpURLConnection.HTTP_OK || !isResponseCacheable(connection)) {
                    lastDiagnostic = "缓存下载被服务器拒绝：HTTP " + statusCode;
                    return;
                }
                long length = getContentLength(connection);
                if (!canStore(length)) {
                    lastDiagnostic = length <= 0
                            ? "服务器没有返回素材大小"
                            : "素材过大或手机可用空间不足";
                    return;
                }
                lastDiagnostic = "正在下载素材（" + length + " 字节）";
                String contentType = connection.getContentType();
                Entry pending = entryFromResponse(
                        key,
                        parseMimeType(contentType),
                        parseCharset(contentType),
                        connection,
                        length
                );
                temporary = File.createTempFile(key + "-", ".part", directory);
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[64 * 1024];
                    long copied = 0;
                    int count;
                    while (!Thread.currentThread().isInterrupted()
                            && (count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        copied += count;
                        if (copied > length || copied > MAX_SINGLE_FILE_BYTES) {
                            return;
                        }
                    }
                    output.flush();
                    if (copied == length) {
                        commit(temporary, pending, length);
                        if (readEntry(key) != null) {
                            temporary = null;
                            completed = true;
                            completedCount.incrementAndGet();
                            lastDiagnostic = "素材缓存完成";
                        } else {
                            lastDiagnostic = "素材下载完成，但写入缓存失败";
                        }
                    }
                }
            } catch (IOException | RuntimeException error) {
                lastDiagnostic = "缓存下载失败：" + error.getClass().getSimpleName();
            } finally {
                if (!completed) failedCount.incrementAndGet();
                if (temporary != null) {
                    temporary.delete();
                }
                if (connection != null) {
                    connection.disconnect();
                }
                inFlight.remove(key);
            }
        });
    }

    private Entry entryFromResponse(
            String key,
            String mimeType,
            String encoding,
            HttpURLConnection connection,
            long length
    ) {
        Entry entry = new Entry();
        entry.key = key;
        entry.body = bodyFile(key);
        entry.mimeType = mimeType;
        entry.encoding = encoding;
        entry.etag = valueOrEmpty(connection.getHeaderField("ETag"));
        entry.lastModified = valueOrEmpty(connection.getHeaderField("Last-Modified"));
        entry.expiresAt = calculateExpiresAt(connection);
        entry.length = length;
        entry.generation = generation.get();
        return entry;
    }

    private synchronized void commit(File temporary, Entry entry, long expectedLength) {
        if (temporary == null
                || temporary.length() != expectedLength
                || entry.generation != generation.get()
                || expectedLength > maxCacheBytes
                || directory.getUsableSpace() < MIN_FREE_SPACE_BYTES) {
            if (temporary != null) temporary.delete();
            return;
        }
        cleanup(expectedLength);
        File target = bodyFile(entry.key);
        if (target.exists() && !target.delete()) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            return;
        }
        entry.body = target;
        entry.length = target.length();
        writeEntryMetadata(entry);
        touch(entry);
        cleanup(0);
    }

    private Entry readEntry(String key) {
        File body = bodyFile(key);
        File metadata = metadataFile(key);
        if (!body.isFile() || !metadata.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(metadata)) {
            properties.load(input);
            Entry entry = new Entry();
            entry.key = key;
            entry.body = body;
            entry.length = Long.parseLong(properties.getProperty("length", "-1"));
            entry.expiresAt = Long.parseLong(properties.getProperty("expiresAt", "0"));
            entry.mimeType = properties.getProperty("mimeType", "application/octet-stream");
            entry.encoding = emptyToNull(properties.getProperty("encoding", ""));
            entry.etag = properties.getProperty("etag", "");
            entry.lastModified = properties.getProperty("lastModified", "");
            if (entry.length < 0 || body.length() != entry.length) {
                deleteEntry(key);
                return null;
            }
            return entry;
        } catch (IOException | NumberFormatException error) {
            deleteEntry(key);
            return null;
        }
    }

    private void writeEntryMetadata(Entry entry) {
        Properties properties = new Properties();
        properties.setProperty("length", Long.toString(entry.length));
        properties.setProperty("expiresAt", Long.toString(entry.expiresAt));
        properties.setProperty("mimeType", valueOrEmpty(entry.mimeType));
        properties.setProperty("encoding", valueOrEmpty(entry.encoding));
        properties.setProperty("etag", valueOrEmpty(entry.etag));
        properties.setProperty("lastModified", valueOrEmpty(entry.lastModified));
        File temporary = new File(directory, entry.key + ".meta.tmp");
        try (OutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, null);
            output.flush();
            File target = metadataFile(entry.key);
            if (target.exists()) target.delete();
            if (!temporary.renameTo(target)) temporary.delete();
        } catch (IOException ignored) {
            temporary.delete();
        }
    }

    private synchronized void cleanup(long incomingBytes) {
        if (!directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;

        List<File> bodies = new ArrayList<>();
        long total = 0;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.getName().endsWith(".body")) {
                bodies.add(file);
                total += file.length();
            } else if ((file.getName().endsWith(".part") || file.getName().endsWith(".tmp"))
                    && now - file.lastModified() > 60L * 60L * 1000L) {
                file.delete();
            }
        }
        bodies.sort(Comparator.comparingLong(File::lastModified));
        for (File body : bodies) {
            boolean overBudget = total + incomingBytes > maxCacheBytes;
            boolean lowSpace = directory.getUsableSpace() < incomingBytes + MIN_FREE_SPACE_BYTES;
            if (!overBudget && !lowSpace) break;
            long length = body.length();
            String name = body.getName();
            String key = name.substring(0, name.length() - ".body".length());
            if (body.delete()) {
                metadataFile(key).delete();
                total -= length;
            }
        }
    }

    private boolean canStore(long length) {
        return length > 0
                && length <= MAX_SINGLE_FILE_BYTES
                && length <= maxCacheBytes
                && directory.isDirectory()
                && directory.getUsableSpace() >= length + MIN_FREE_SPACE_BYTES;
    }

    private boolean isResponseCacheable(HttpURLConnection connection) {
        String cacheControl = connection.getHeaderField("Cache-Control");
        return cacheControl == null || !cacheControl.toLowerCase(Locale.US).contains("no-store");
    }

    private boolean isCompleteRepresentation(
            HttpURLConnection connection,
            int statusCode,
            long contentLength
    ) {
        if (statusCode == HttpURLConnection.HTTP_OK) {
            return contentLength > 0;
        }
        if (statusCode != HttpURLConnection.HTTP_PARTIAL) {
            return false;
        }
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(valueOrEmpty(
                connection.getHeaderField("Content-Range")
        ));
        if (!matcher.matches() || "*".equals(matcher.group(3))) {
            return false;
        }
        long start = Long.parseLong(matcher.group(1));
        long end = Long.parseLong(matcher.group(2));
        long total = Long.parseLong(matcher.group(3));
        return start == 0 && end == total - 1 && contentLength == total;
    }

    private long calculateExpiresAt(HttpURLConnection connection) {
        String cacheControl = connection.getHeaderField("Cache-Control");
        if (cacheControl != null) {
            Matcher matcher = MAX_AGE_PATTERN.matcher(cacheControl);
            if (matcher.find()) {
                try {
                    return System.currentTimeMillis() + Long.parseLong(matcher.group(1)) * 1000L;
                } catch (NumberFormatException ignored) {
                    // Fall through to the safe media default.
                }
            }
        }
        return System.currentTimeMillis() + DEFAULT_MAX_AGE_MS;
    }

    private void rememberResponseCookies(String url, HttpURLConnection connection) {
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return;
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            if (field.getKey() == null || !"Set-Cookie".equalsIgnoreCase(field.getKey())) continue;
            for (String cookie : field.getValue()) {
                CookieManager.getInstance().setCookie(url, cookie);
            }
        }
    }

    private Map<String, String> flattenHeaders(HttpURLConnection connection) {
        Map<String, String> result = new HashMap<>();
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) return result;
        for (Map.Entry<String, List<String>> field : fields.entrySet()) {
            if (field.getKey() == null || field.getValue() == null || field.getValue().isEmpty()) {
                continue;
            }
            result.put(field.getKey(), joinHeaderValues(field.getValue()));
        }
        return result;
    }

    private String joinHeaderValues(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (joined.length() > 0) joined.append(", ");
            joined.append(value);
        }
        return joined.toString();
    }

    private Range parseRange(String header, long total) {
        if (header == null || total <= 0 || !header.startsWith("bytes=")) return null;
        String value = header.substring("bytes=".length()).trim();
        if (value.contains(",")) return null;
        int separator = value.indexOf('-');
        if (separator < 0) return null;
        String startText = value.substring(0, separator).trim();
        String endText = value.substring(separator + 1).trim();
        try {
            long start;
            long end;
            if (startText.isEmpty()) {
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) return null;
                start = Math.max(0, total - suffix);
                end = total - 1;
            } else {
                start = Long.parseLong(startText);
                end = endText.isEmpty() ? total - 1 : Long.parseLong(endText);
                if (start < 0 || start >= total || end < start) return null;
                end = Math.min(end, total - 1);
            }
            return new Range(start, end);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private boolean ifRangeMatches(String ifRange, Entry entry) {
        if (ifRange == null || ifRange.trim().isEmpty()) return true;
        String value = ifRange.trim();
        if (value.startsWith("W/")) return false;
        if (value.startsWith("\"")) return value.equals(entry.etag);
        return value.equals(entry.lastModified);
    }

    private String findHeader(Map<String, String> headers, String expectedName) {
        if (headers == null) return null;
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (expectedName.equalsIgnoreCase(header.getKey())) return header.getValue();
        }
        return null;
    }

    private long getContentLength(HttpURLConnection connection) {
        return connection.getHeaderFieldLong("Content-Length", -1L);
    }

    private String parseMimeType(String contentType) {
        if (contentType == null || contentType.trim().isEmpty()) {
            return "application/octet-stream";
        }
        int separator = contentType.indexOf(';');
        return (separator < 0 ? contentType : contentType.substring(0, separator)).trim();
    }

    private String parseCharset(String contentType) {
        if (contentType == null) return null;
        String[] parts = contentType.split(";");
        for (int index = 1; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.toLowerCase(Locale.US).startsWith("charset=")) {
                return part.substring("charset=".length()).replace("\"", "").trim();
            }
        }
        return null;
    }

    private String reasonPhrase(int statusCode, String provided) {
        if (provided != null && !provided.trim().isEmpty()) return provided;
        switch (statusCode) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Requested Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "HTTP " + statusCode;
        }
    }

    private File bodyFile(String key) {
        return new File(directory, key + ".body");
    }

    private File metadataFile(String key) {
        return new File(directory, key + ".meta");
    }

    private synchronized void deleteEntry(String key) {
        bodyFile(key).delete();
        metadataFile(key).delete();
    }

    private void touch(Entry entry) {
        long now = System.currentTimeMillis();
        entry.body.setLastModified(now);
        metadataFile(entry.key).setLastModified(now);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte item : result) hex.append(String.format(Locale.US, "%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static final class Entry {
        String key;
        File body;
        long length;
        long expiresAt;
        String mimeType;
        String encoding;
        String etag;
        String lastModified;
        long generation;
    }

    static final class CacheStats {
        final long totalBytes;
        final int itemCount;
        final long maxBytes;
        final int pendingCount;
        final long pendingBytes;
        final long discoveredCount;
        final long startedCount;
        final long completedCount;
        final long failedCount;
        final long localPlaybackCount;
        final String lastDiagnostic;

        CacheStats(
                long totalBytes,
                int itemCount,
                long maxBytes,
                int pendingCount,
                long pendingBytes,
                long discoveredCount,
                long startedCount,
                long completedCount,
                long failedCount,
                long localPlaybackCount,
                String lastDiagnostic
        ) {
            this.totalBytes = totalBytes;
            this.itemCount = itemCount;
            this.maxBytes = maxBytes;
            this.pendingCount = pendingCount;
            this.pendingBytes = pendingBytes;
            this.discoveredCount = discoveredCount;
            this.startedCount = startedCount;
            this.completedCount = completedCount;
            this.failedCount = failedCount;
            this.localPlaybackCount = localPlaybackCount;
            this.lastDiagnostic = lastDiagnostic;
        }
    }

    private static final class Range {
        final long start;
        final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class RandomAccessFileInputStream extends InputStream {
        private final RandomAccessFile file;
        private long remaining;

        RandomAccessFileInputStream(File source, long start, long length) throws IOException {
            file = new RandomAccessFile(source, "r");
            file.seek(start);
            remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = file.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int count = file.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }

    private static class DisconnectingInputStream extends FilterInputStream {
        final HttpURLConnection connection;

        DisconnectingInputStream(InputStream input, HttpURLConnection connection) {
            super(input);
            this.connection = connection;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                connection.disconnect();
            }
        }
    }

    private final class CachingInputStream extends DisconnectingInputStream {
        private final File temporary;
        private final Entry entry;
        private final long expectedLength;
        private OutputStream cacheOutput;
        private long written;
        private boolean completed;

        CachingInputStream(
                InputStream input,
                HttpURLConnection connection,
                File temporary,
                Entry entry,
                long expectedLength
        ) throws IOException {
            super(input, connection);
            this.temporary = temporary;
            this.entry = entry;
            this.expectedLength = expectedLength;
            this.cacheOutput = new FileOutputStream(temporary);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                writeToCache(new byte[]{(byte) value}, 0, 1);
            } else {
                completeIfPossible();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                writeToCache(buffer, offset, count);
            } else if (count == -1) {
                completeIfPossible();
            }
            return count;
        }

        private void writeToCache(byte[] buffer, int offset, int count) {
            if (cacheOutput == null) return;
            try {
                cacheOutput.write(buffer, offset, count);
                written += count;
            } catch (IOException error) {
                abandonCache();
            }
        }

        private void completeIfPossible() {
            if (completed) return;
            completed = true;
            closeCacheOutput();
            if (written == expectedLength) {
                try {
                    commit(temporary, entry, expectedLength);
                } catch (RuntimeException error) {
                    temporary.delete();
                }
            } else {
                temporary.delete();
            }
        }

        private void abandonCache() {
            closeCacheOutput();
            temporary.delete();
        }

        private void closeCacheOutput() {
            if (cacheOutput == null) return;
            try {
                cacheOutput.close();
            } catch (IOException ignored) {
                // The network response must remain readable even when disk caching fails.
            }
            cacheOutput = null;
        }

        @Override
        public void close() throws IOException {
            if (!completed) {
                completeIfPossible();
            }
            super.close();
        }
    }
}
