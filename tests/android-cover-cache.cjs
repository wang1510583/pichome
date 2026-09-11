const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {execFileSync} = require('node:child_process');

const source = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/top/lempet/pichome/MediaDiskCache.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert.notEqual(start, -1);
    return source.slice(start, source.indexOf('\n    }', start) + 6);
}
assert.match(method('private boolean isCoverUrl('), /!"large"\.equalsIgnoreCase/);
assert.match(method('private boolean isCoverUrl('), /"getImg"\.equalsIgnoreCase/);
assert.match(method('WebResourceResponse intercept('), /if \(!isCoverEntry\(entry\)\) deleteEntry\(key\)/);
assert.match(method('private synchronized void commit('), /cleanup\(isCoverEntry\(entry\) \? 0 : expectedLength\)/);
assert.match(method('private boolean validate('), /statusCode == 401 \|\| statusCode == 403 \|\| statusCode == 404 \|\| statusCode == 410/);
assert.doesNotMatch(method('CacheStats getStats('), /listFiles|readEntry|\.length\(/);
assert.doesNotMatch(method('private synchronized void cleanup('), /listFiles|readEntry/);

const methods = [
    'CacheStats getStats(', 'void clearAll(', 'private synchronized void cleanup(',
    'private boolean canStore(', 'private boolean isCoverEntry(', 'static final class CacheStats {',
    'private synchronized void deleteEntry(', 'private File bodyFile(', 'private void initializeInventory('
].map(method).join('\n');
const harness = `
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class MediaDiskCache {
    static class Disk extends File {
        long freeBytes = Long.MAX_VALUE;
        Disk(String path) { super(path); }
        public long getUsableSpace() { return freeBytes; }
    }
    final File directory;
    final Map<String, Entry> entries = new HashMap<>();
    final Set<String> inFlight = new HashSet<>();
    final CacheInventory inventory = new CacheInventory();
    boolean inventoryReady = true;
    int metadataReads;
    final AtomicLong generation = new AtomicLong(), discoveredCount = new AtomicLong(), startedCount = new AtomicLong(), completedCount = new AtomicLong(), failedCount = new AtomicLong(), localPlaybackCount = new AtomicLong();
    final String lastDiagnostic = "test";
    static final long MIN_FREE_SPACE_BYTES = 0, MAX_SINGLE_FILE_BYTES = 512L * 1024 * 1024;
    long maxCacheBytes = 10;
    MediaDiskCache(File directory) { this.directory = directory; }
    static class Entry { String mimeType = "image/webp"; String url; long length; }
    boolean isCoverUrl(String url) { return url.equals("cover"); }
    Entry readEntry(String key) { metadataReads++; return new File(directory, key + ".body").isFile() ? entries.get(key) : null; }
    File metadataFile(String key) { return new File(directory, key + ".meta"); }
    void add(String key, String kind, int size) throws Exception {
        Entry entry = new Entry(); entry.url = kind; entry.length = size; entries.put(key, entry);
        Files.write(new File(directory, key + ".body").toPath(), new byte[size]);
        Files.write(metadataFile(key).toPath(), new byte[0]);
        inventory.record(key, size, kind.equals("cover"));
    }
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
    ${methods}
    public static void main(String[] args) throws Exception {
        Disk disk = new Disk(args[0]);
        MediaDiskCache cache = new MediaDiskCache(disk);
        cache.add("oldCover", "cover", 100);
        cache.add("video", "video", 6);
        cache.inventory.remove("oldCover"); cache.inventory.remove("video");
        cache.inventoryReady = false;
        check(cache.getStats().lastDiagnostic.contains("部分统计"), "startup partial stats explicit");
        cache.initializeInventory();
        check(cache.inventoryReady && cache.getStats().coverCount == 1 && cache.getStats().totalBytes == 6, "startup indexes existing entries");
        cache.cleanup(0);
        check(cache.readEntry("oldCover") != null, "cover survives quota");
        check(cache.readEntry("video") != null, "cover excluded from media budget");
        CacheStats stats = cache.getStats();
        check(stats.totalBytes == 6 && stats.itemCount == 1, "media stats exclude covers");
        check(stats.coverBytes == 100 && stats.coverCount == 1, "cover stats separate");
        check(cache.canStore(20, true) && !cache.canStore(20, false), "separate admission limits");
        cache.cleanup(8);
        check(cache.readEntry("video") == null, "media evicted for incoming media");
        check(cache.readEntry("oldCover") != null, "cover retained during eviction");
        cache.maxCacheBytes = 1;
        cache.cleanup(0);
        check(cache.readEntry("oldCover") != null, "resize preserves covers");
        disk.freeBytes = 0;
        cache.cleanup(1);
        check(cache.readEntry("oldCover") != null, "low space never evicts covers");
        check(!cache.canStore(20, true), "low space stops new covers");
        disk.freeBytes = Long.MAX_VALUE;
        cache.add("atlas", "atlas", 5);
        CountDownLatch done = new CountDownLatch(1);
        cache.clearAll(done::countDown);
        check(done.await(5, TimeUnit.SECONDS), "clear completed");
        check(cache.readEntry("oldCover") != null && cache.metadataFile("oldCover").exists(), "manual media clear preserves cover pair");
        check(cache.readEntry("atlas") == null, "manual clear removes atlas");
        check(cache.getStats().totalBytes == 0 && cache.getStats().coverCount == 1, "clear updates accounting");
        cache.inventory.record("replace", 10, true);
        cache.inventory.record("replace", 20, true);
        check(cache.getStats().coverBytes == 120, "replacement does not double count");
        cache.inventory.remove("replace");
        cache.inventory.record("older", 1, false);
        cache.inventory.record("newer", 1, false);
        cache.inventory.touch("older");
        check(cache.inventory.oldestMedia().get(0).equals("newer"), "LRU touch order");
        cache.inventory.remove("older"); cache.inventory.remove("newer");
        for (int index = 0; index < 20000; index++) cache.inventory.record("cover-" + index, 1024, true);
        int readsBefore = cache.metadataReads;
        long started = System.nanoTime();
        for (int index = 0; index < 10000; index++) {
            check(cache.getStats().coverCount == 20001, "large inventory stats");
            cache.cleanup(0);
        }
        check(cache.metadataReads == readsBefore, "stats and normal cleanup never read metadata");
        System.out.println("PASS: cover isolation, quota, LRU, replacement, clear accounting; 20,000 covers / 10,000 stats+cleanup calls: " + (System.nanoTime() - started) / 1000000 + "ms, zero metadata reads");
    }
}
`;
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pichome-cover-test-'));
try {
    fs.writeFileSync(path.join(temporary, 'MediaDiskCache.java'), harness);
    const inventorySource = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/top/lempet/pichome/CacheInventory.java'), 'utf8').replace('package top.lempet.pichome;', '');
    fs.writeFileSync(path.join(temporary, 'CacheInventory.java'), inventorySource);
    fs.mkdirSync(path.join(temporary, 'cache'));
    execFileSync('javac', [path.join(temporary, 'MediaDiskCache.java'), path.join(temporary, 'CacheInventory.java')], {stdio:'inherit'});
    execFileSync('java', ['-cp', temporary, 'MediaDiskCache', path.join(temporary, 'cache')], {stdio:'inherit'});
} finally {
    fs.rmSync(temporary, {recursive:true, force:true});
}
