package top.lempet.pichome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CacheInventory {
    private final Map<String, Long> covers = new HashMap<>();
    private final LinkedHashMap<String, Long> media = new LinkedHashMap<>(16, 0.75f, true);
    private long mediaBytes;
    private long coverBytes;

    synchronized void record(String key, long length, boolean cover) {
        remove(key);
        if (cover) {
            covers.put(key, length);
            coverBytes += length;
        } else {
            media.put(key, length);
            mediaBytes += length;
        }
    }

    synchronized void remove(String key) {
        Long oldMedia = media.remove(key);
        Long oldCover = covers.remove(key);
        if (oldMedia != null) mediaBytes -= oldMedia;
        if (oldCover != null) coverBytes -= oldCover;
    }

    synchronized void touch(String key) {
        media.get(key);
    }

    synchronized List<String> oldestMedia() {
        return new ArrayList<>(media.keySet());
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(mediaBytes, media.size(), coverBytes, covers.size());
    }

    static final class Snapshot {
        final long mediaBytes;
        final int mediaCount;
        final long coverBytes;
        final int coverCount;

        Snapshot(long mediaBytes, int mediaCount, long coverBytes, int coverCount) {
            this.mediaBytes = mediaBytes;
            this.mediaCount = mediaCount;
            this.coverBytes = coverBytes;
            this.coverCount = coverCount;
        }
    }
}
