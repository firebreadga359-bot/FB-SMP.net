// Minimal service worker so the control room installs as an app.
// Only the shell icons/manifest are cached; the panel HTML and /api responses
// stay network-only so an installable app never shows stale data or auth state.
const CACHE = "fbsmp-shell-v1";
const ASSETS = [
  "/icon-192.png",
  "/icon-512.png",
  "/icon-180.png",
  "/apple-touch-icon.png",
  "/manifest.webmanifest",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(ASSETS)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  let url;
  try { url = new URL(request.url); } catch (_) { return; }
  if (url.origin !== self.location.origin) return;
  if (ASSETS.indexOf(url.pathname) >= 0) {
    event.respondWith(caches.match(request).then((hit) => hit || fetch(request)));
  }
});
