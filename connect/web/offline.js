/* Local favorite-song storage. Credentials are used only for same-origin requests and never cached. */
(() => {
  'use strict';
  if(window.PiterfyOffline)return;
  const core=window.PiterfyOfflineCore;
  let owner='',bucket='',cache,manifest={songs:[]},enabled=true,busy=false,controller,generation=0,lastSync=0,shellReady=false;
  let status={phase:'waiting',done:0,total:0,bytes:0,percent:0,message:'Przygotowuję ulubione offline…'};
  const listeners=new Set(),workerURL='/app/piterfy-offline-sw.js';
  const identity=()=>localStorage.getItem('username')||'';
  const pref=()=> 'piterfy.offline.enabled:'+bucket;
  const mediaKey=id=>'/app/offline/data/'+bucket+'/'+encodeURIComponent(id);
  const metaKey=()=>'/app/offline/data/'+bucket+'/metadata';
  function emit(update={}){status={...status,...update,enabled,busy,shellReady};for(const fn of listeners)fn({...status});renderLauncher();}
  function authenticated(){return owner&&identity()===owner&&!!localStorage.getItem('token')&&!location.hash.startsWith('#/login');}
  function url(command,id,options={}){
    if(!authenticated())throw Error('Zaloguj się do Piterfy, aby pobrać ulubione.');
    const token=localStorage.getItem('subsonic-token'),salt=localStorage.getItem('subsonic-salt');
    if(!token||!salt)throw Error('Odśwież logowanie do Piterfy.');
    const p=new URLSearchParams({u:owner,t:token,s:salt,v:'1.16.1',c:'PiterfyOffline',f:'json',...options});if(id)p.set('id',id);
    return '/rest/'+command+'?'+p;
  }
  async function setupWorker(){
    const existing=await navigator.serviceWorker.getRegistration('/app/');
    const reg=!navigator.onLine&&existing?.active?existing:await navigator.serviceWorker.register(workerURL,{scope:'/app/'});
    if(!reg.active){const worker=reg.installing||reg.waiting;if(!worker)throw Error('Ekran offline nie jest gotowy.');
      await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Nie udało się przygotować ekranu offline.')),20000);const check=()=>{if(worker.state==='activated'){clearTimeout(timer);resolve();}else if(worker.state==='redundant'){clearTimeout(timer);reject(Error('Nie udało się zapisać ekranu offline.'));}};worker.addEventListener('statechange',check);check();});}
    shellReady=true;
  }
  async function changeAccount(){
    const name=identity();if(name===owner&&cache)return;
    // Let the old job unwind before replacing its cache/manifest references.
    if(name!==owner&&busy){generation++;controller?.abort();emit({phase:'signed-out',message:'Zmieniono konto. Otwieram właściwe ulubione…'});return;}
    generation++;controller?.abort();owner=name;cache=null;manifest={songs:[]};lastSync=0;shellReady=false;
    if(!name){emit({phase:'signed-out',done:0,total:0,message:'Zaloguj się do Piterfy, aby otworzyć ulubione offline.'});return;}
    const token=generation;
    const hash=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(name));const key=Array.from(new Uint8Array(hash),n=>n.toString(16).padStart(2,'0')).join('');
    const opened=await caches.open('piterfy-favorites-v1-'+key);if(token!==generation)return;
    bucket=key;cache=opened;enabled=localStorage.getItem(pref())!=='false';
    try{manifest=await (await cache.match(metaKey()))?.json()||{songs:[]};}catch(_){manifest={songs:[]};}
    emit({done:manifest.songs.length,total:manifest.songs.length,phase:'waiting',message:manifest.songs.length+' utworów zapisanych na tym komputerze.'});
    try{await setupWorker();if(token===generation)emit();}catch(_){if(token===generation)emit({phase:'error',message:'Nie udało się przygotować ekranu offline. Odśwież stronę z dostępem do sieci.'});}
  }
  async function save(){await cache.put(metaKey(),new Response(JSON.stringify(manifest),{headers:{'Content-Type':'application/json'}}));}
  async function download(song,run){
    const estimate=await navigator.storage?.estimate?.();if(!core.enoughSpace(estimate,song.size))throw Error('Brakuje miejsca w pamięci przeglądarki. Zapisane utwory pozostają dostępne.');
    const response=await fetch(url('stream',song.id,{format:'raw',maxBitRate:'0'}),{signal:controller.signal,cache:'no-store'});
    if(!core.isAudio(response))throw Error('Serwer nie zwrócił pełnego pliku audio. Spróbuj ponownie.');
    const reader=response.body.getReader(),chunks=[];let bytes=0;const length=Number(response.headers.get('Content-Length'))||song.size;
    while(true){const {done,value}=await reader.read();if(done)break;bytes+=value.byteLength;
      if(bytes>512*1024*1024){await reader.cancel();throw Error('Plik jest zbyt duży do zapisania w przeglądarce.');}
      chunks.push(value);emit({bytes,percent:length?Math.min(99,Math.round(bytes/length*100)):0});
      if(run!==generation||!enabled||!authenticated())throw new DOMException('Przerwano','AbortError');
    }
    if(!bytes)throw Error('Serwer zwrócił pusty plik audio.');
    if(length&&bytes<length)throw Error('Pobieranie urwało się przed końcem pliku. Ponów synchronizację.');
    const blob=new Blob(chunks,{type:response.headers.get('Content-Type')});
    if(run!==generation||!enabled||!authenticated())throw new DOMException('Przerwano','AbortError');
    // Only complete files become visible offline. The cache key contains no authentication data.
    await cache.put(mediaKey(song.id),new Response(blob,{headers:{'Content-Type':blob.type,'Content-Length':String(bytes)}}));
    manifest.songs=manifest.songs.filter(s=>s.id!==song.id);manifest.songs.push({...song,bytes,savedAt:Date.now()});await save();
  }
  async function performSync(){
    if(busy||!cache||!enabled||!authenticated())return;
    if(!navigator.onLine){emit({phase:'offline',message:'Bez sieci · zapisane utwory są dostępne offline.'});return;}
    if(!core.networkAllowed(navigator.connection)){emit({phase:'waiting',message:'Pobieranie czeka: oszczędzanie danych lub sieć komórkowa.'});return;}
    busy=true;const run=generation;controller=new AbortController();const timeout=setInterval(()=>{if(run!==generation||!enabled||!authenticated()||!core.networkAllowed(navigator.connection))controller?.abort();},1000);
    try{
      emit({phase:'checking',message:'Sprawdzam ulubione utwory…',percent:0});
      const response=await fetch(url('getStarred2'),{signal:controller.signal,cache:'no-store'});if(!response.ok)throw Error('Nie udało się sprawdzić ulubionych.');
      const data=(await response.json())['subsonic-response'];if(data?.status!=='ok')throw Error('Serwer odrzucił synchronizację ulubionych.');
      const songs=(data.starred2?.song||[]).filter(s=>!window.PiterfyMediaKinds.isPodcast(s)).map(core.safeSong).filter(Boolean);
      const unique=[...new Map(songs.map(s=>[s.id,s])).values()];
      const available=new Set();for(const song of manifest.songs)if(await cache.match(mediaKey(song.id)))available.add(song.id);
      manifest.songs=manifest.songs.filter(s=>available.has(s.id));
      let done=unique.filter(s=>available.has(s.id)).length;emit({total:unique.length,done});
      const failures=[];
      for(const song of unique){
        if(available.has(song.id))continue;
        if(run!==generation||!enabled||!authenticated())throw new DOMException('Przerwano','AbortError');
        emit({phase:'downloading',message:'Zapisuję: '+song.artist+' — '+song.title,percent:0,bytes:0,done});
        const timer=setTimeout(()=>controller.abort(),180000);
        try{await download(song,run);done++;emit({done,percent:100});}
        catch(e){if(e.name==='AbortError'||e.name==='QuotaExceededError'||!navigator.onLine)throw e;failures.push(song.id);}
        finally{clearTimeout(timer);}
      }
      await save();lastSync=Date.now();emit({phase:failures.length?'error':'ready',done,message:failures.length?'Zapisano '+done+'/'+unique.length+' utworów. '+failures.length+' plików czeka na ponowienie.':'Ulubione gotowe offline · '+done+'/'+unique.length+'.'});
    }catch(e){if(run===generation)emit({phase:e.name==='AbortError'?'waiting':'error',message:e.name==='AbortError'?'Pobieranie wstrzymane. Kompletne utwory są zachowane.':e.name==='QuotaExceededError'?'Pamięć przeglądarki jest pełna. Zapisane utwory pozostają dostępne.':e.message});}
    finally{clearInterval(timeout);busy=false;controller=null;emit();}
  }
  async function sync(){
    if(busy)return;
    if(navigator.locks)await navigator.locks.request('piterfy-favorites-sync',{ifAvailable:true},async lock=>{if(lock)await performSync();else emit({phase:'waiting',message:'Ulubione pobiera już inna karta Piterfy.'});});
    else await performSync();
  }
  function renderLauncher(){
    if(document.body.dataset.offlinePage)return;
    let link=document.getElementById('pf-offline-link');const liked=document.querySelector('a[href="#/ulubione"]');
    if(!liked)return;
    if(!link){link=document.createElement('a');link.id='pf-offline-link';link.href='/app/offline/';link.className=liked.className;link.style.cssText='display:flex;gap:12px;align-items:center;min-height:44px;padding:0 16px;color:inherit;text-decoration:none';liked.after(link);}
    link.textContent='↓ Offline'+(status.total?' · '+status.done+'/'+status.total:'');link.title=status.message;
  }
  window.PiterfyOffline={
    subscribe(fn){listeners.add(fn);fn({...status});return()=>listeners.delete(fn);},sync,
    async songs(){if(!cache)await changeAccount();return manifest.songs.slice();},
    async blob(id){if(!cache||!identity()||identity()!==owner)throw Error('Zaloguj się do właściwego konta.');const response=await cache.match(mediaKey(id));if(!response)throw Error('Ten utwór nie został zapisany.');return response.blob();},
    setEnabled(value){enabled=!!value;if(cache)localStorage.setItem(pref(),String(enabled));if(!enabled)controller?.abort();emit({message:enabled?'Synchronizacja ulubionych włączona.':'Automatyczne pobieranie wyłączone. Pliki są zachowane.'});if(enabled)sync();},
    async persist(){return navigator.storage?.persist?.();}
  };
  async function tick(){
    try{await changeAccount();renderLauncher();if(cache&&enabled&&!busy&&Date.now()-lastSync>60000)await sync();}
    catch(_){emit({phase:'error',message:'Przeglądarka blokuje zapis offline. Sprawdź ustawienia pamięci witryny.'});}
  }
  if(!core||!('caches' in window)||!navigator.serviceWorker){emit({phase:'error',message:'Ta przeglądarka nie udostępnia trybu offline.'});return;}
  setInterval(tick,15000);window.addEventListener('online',()=>{lastSync=0;tick();});window.addEventListener('storage',()=>tick());tick();
})();
