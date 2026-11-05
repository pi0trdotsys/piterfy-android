const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function harness(network){const handlers={};const cached=new Response('<html>offline</html>',{headers:{'content-type':'text/html'}});let stored=[];
 const context={URL,Response,fetch:(request,options)=>typeof request==='string'?Promise.resolve(new Response('asset',{headers:{'content-type':request.includes('.html')?'text/html':'text/javascript'}})):network(request,options),caches:{open:async()=>({put:async path=>{stored.push(path);},match:async path=>path==='/piterfy-ui/offline.html'?cached.clone():undefined})},self:{location:{origin:'https://piterfy.test'},clients:{claim:async()=>{}},skipWaiting:async()=>{},addEventListener:(name,fn)=>handlers[name]=fn}};
 vm.runInNewContext(fs.readFileSync(__dirname+'/offline-sw.js','utf8'),context);
 return {handlers,get stored(){return stored;},async navigation(path='/app/',mode='navigate',method='GET'){let response;handlers.fetch({request:{url:'https://piterfy.test'+path,mode,method},respondWith:p=>response=p});return response&&await response;}};
}
test('a fresh offline navigation under /app loads the saved start screen, including deep links',async()=>{
 const h=harness(async()=>{throw TypeError('offline');});for(const path of ['/app/','/app/offline/','/app/album/saved'])assert.match(await (await h.navigation(path)).text(),/offline/);
 assert.equal(await h.navigation('/api/song','cors'),undefined);assert.equal(await h.navigation('/rest/stream','cors'),undefined);assert.equal(await h.navigation('/app/','navigate','POST'),undefined);
});
test('online app and login errors keep their real responses; server failures use offline shell',async()=>{
 assert.equal((await harness(async()=>new Response('login',{status:401})).navigation()).status,401);
 assert.equal(await (await harness(async()=>new Response('online')).navigation()).text(),'online');
 assert.match(await (await harness(async()=>new Response('unavailable',{status:503})).navigation()).text(),/offline/);
});
test('worker precaches the complete offline shell and no credentials or media/API responses',async()=>{
 const h=harness();let done;h.handlers.install({waitUntil:p=>done=p});await done;
 assert.equal(h.stored.length,5);assert.ok(h.stored.includes('/piterfy-ui/offline-player.js'));assert.ok(h.stored.every(p=>p.startsWith('/piterfy-ui/')&&!p.includes('?')));
});
