/* Scope: /app/. This worker caches only the offline shell; it never caches authenticated API responses. */
const SHELL='piterfy-offline-shell-v3';
const ASSETS=['/piterfy-ui/offline.html','/piterfy-ui/offline-player.js','/piterfy-ui/offline-sync.js','/piterfy-ui/offline-core.js','/piterfy-ui/media-kind-core.js'];
self.addEventListener('install',event=>event.waitUntil((async()=>{const cache=await caches.open(SHELL);await Promise.all(ASSETS.map(async path=>{const response=await fetch(path+'?offline=v3',{cache:'reload'});const type=response.headers.get('content-type')||'';if(!response.ok||!(path.endsWith('.html')?type.includes('text/html'):/javascript/.test(type)))throw Error('Offline asset unavailable: '+path);await cache.put(path,response);}));await self.skipWaiting();})()));
self.addEventListener('activate',event=>event.waitUntil((async()=>{for(const key of await caches.keys())if(key.startsWith('piterfy-offline-shell-')&&key!==SHELL)await caches.delete(key);await self.clients.claim();})()));
self.addEventListener('fetch',event=>{
  const url=new URL(event.request.url);if(url.origin!==self.location.origin||event.request.method!=='GET')return;
  if(event.request.mode==='navigate'&&url.pathname.startsWith('/app/')){
    event.respondWith((async()=>{try{const response=await fetch(event.request);if(response.status<500)return response;}catch(_){}return await (await caches.open(SHELL)).match('/piterfy-ui/offline.html')||new Response('Piterfy offline nie jest jeszcze przygotowane.',{status:503});})());
  }else if(ASSETS.includes(url.pathname))event.respondWith((async()=>await (await caches.open(SHELL)).match(url.pathname)||fetch(event.request))());
});
