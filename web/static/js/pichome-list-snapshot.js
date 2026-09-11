(function (root) {
    'use strict';
    function create(storage, scope, now) {
        var storageKey = 'pichome-list-snapshot-v1';
        var lifetime = 7 * 24 * 60 * 60 * 1000;
        function read() {
            try {
                var value = JSON.parse(storage.getItem(storageKey));
                if (value && value.scope === scope && Array.isArray(value.entries)) return value.entries.filter(function (entry) { return now() - entry.time < lifetime && entry.time <= now(); });
                storage.removeItem(storageKey);
            } catch (ignore) {}
            return [];
        }
        function canonical(value) {
            if (Array.isArray(value)) return value.map(canonical);
            if (!value || typeof value !== 'object') return value;
            var result = {};
            Object.keys(value).sort().forEach(function (key) { result[key] = canonical(value[key]); });
            return result;
        }
        function key(url, params) { return JSON.stringify([url, canonical(params)]); }
        function valid(data) { return data && Array.isArray(data.data) && data.param && Number(data.param.page) === 1 && Number.isFinite(Number(data.param.total)); }
        function save(entries) {
            try {
                while (entries.length) {
                    var value = JSON.stringify({scope: scope, entries: entries});
                    if (value.length <= 900000) { storage.setItem(storageKey, value); return; }
                    entries.pop();
                }
                storage.removeItem(storageKey);
            } catch (ignore) {}
        }
        read();
        return {
            get: function (url, params) {
                if (!scope || Number(params.page) !== 1) return null;
                var match = read().find(function (entry) { return entry.key === key(url, params); });
                return match && valid(match.data) ? match.data : null;
            },
            put: function (url, params, data) {
                if (!scope || Number(params.page) !== 1 || !valid(data) || data.data.length > 120) return;
                var identity = key(url, params);
                var entries = read().filter(function (entry) { return entry.key !== identity; });
                entries.unshift({key: identity, time: now(), data: data});
                save(entries.slice(0, 4));
            },
            remove: function (url, params) { var identity = key(url, params); save(read().filter(function (entry) { return entry.key !== identity; })); }
        };
    }
    if (typeof module === 'object' && module.exports) module.exports = create;
    if (root) root.PichomeListSnapshot = {configure: function (scope, mobile) {
        try { this.cache = create(root.localStorage, mobile && scope ? scope : '', Date.now); } catch (ignore) { this.cache = null; }
    }};
})(typeof window === 'undefined' ? null : window);
