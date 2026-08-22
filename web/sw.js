const CACHE_NAME = 'pichome-pwa-v1';
const PWA_ASSETS = [
  'manifest.json',
  'static/image/pwa-icon-192.png',
  'static/image/pwa-icon-512.png',
  'static/image/apple-touch-icon.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(PWA_ASSETS).catch((err) => {
        console.warn('PWA assets cache error:', err);
      });
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.map((key) => {
          if (key !== CACHE_NAME && key.startsWith('pichome-pwa-')) {
            return caches.delete(key);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') {
    return;
  }
  const url = new URL(request.url);
  // 对于静态 PWA 核心图标资源优先缓存，其余所有动态请求与媒体流直通网络
  if (url.pathname.endsWith('manifest.json') || url.pathname.indexOf('pwa-icon-') !== -1) {
    event.respondWith(
      caches.match(request).then((response) => {
        return response || fetch(request);
      })
    );
    return;
  }
  // 其余请求默认走网络与现有 HTTP 强缓存
});
