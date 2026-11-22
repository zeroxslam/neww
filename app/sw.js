/* CheckLink service worker: network-first for pages, cache fallback (offline shell). */
const CACHE = 'checklink-app-v2';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png'];
self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const r = e.request;
  if (r.method !== 'GET') return;
  const u = new URL(r.url);
  if (u.origin !== location.origin) return; /* never touch VirusTotal worker / CDNs */
  e.respondWith(
    fetch(r).then(res => {
      if (res && res.ok && u.pathname.indexOf('/__') !== 0) { const cp = res.clone(); caches.open(CACHE).then(c => c.put(r.mode === 'navigate' ? './index.html' : r, cp)); }
      return res;
    }).catch(() => caches.match(r, { ignoreSearch: true }).then(m => m || caches.match('./index.html')))
  );
});
