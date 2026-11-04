/* Shared discovery and podcast experience for the existing Navidrome player. */
(() => {
  'use strict';
  function queueIndex(queue,current,saved=0){const at=queue.findIndex(t=>t.uuid===current);return at>=0?at:queue.length?Math.max(0,Math.min(queue.length-1,saved||0)):-1;}
  function protectedQueue(songs,queue,current,pinned){
    if(!songs.length)return songs;
    const at=queue.findIndex(t=>t.uuid===current),ids=new Set(songs.map(s=>s.id));
    const pins=queue.slice(at+1).filter(t=>t.song).map(t=>({...t.song,id:t.trackId||t.song.mediaFileId||t.song.id}))
      .filter(s=>{if(podcast(s)||!pinned.includes(s.id)||ids.has(s.id))return false;ids.add(s.id);return true;});
    return [songs[0],...pins,...songs.slice(1)].slice(0,500);
  }
  function podcast(song){const core=typeof module==='object'&&module.exports?require('./media-kind-core.js'):window.PiterfyMediaKinds;return core?core.isPodcast(song):String(song?.type).toLowerCase()==='podcast'||/(^|[;,/])\s*(podcasts?|podcasty)\s*($|[;,/])/i.test(song?.genre||'')||/(^|[/\\])(podcasts?|podcasty)([/\\]|$)/i.test(song?.path||'');}
  if(typeof module==='object'&&module.exports){module.exports={protectedQueue,queueIndex};return;}
  if (window.PiterfyLibrary) return;
  let store, dialog, account = '', preferences = {}, previous, lastSave = 0, generation = 0;
  let applyingQueue = false, seekGuard = 0, saving = false, checkedAlbum = '', coverSource = '';
  const preparedRecords=new Map();let preparedIdentity='';
  let prefixJob=null,prefixId='',prefixOwner='';
  async function prefetchNextAudio(){
    const owner=identity(),player=findStore()?.getState().player;
    if(prefixOwner!==owner||window.PiterfyPlayback?.current()?.remote||navigator.connection?.saveData){prefixJob?.abort();prefixJob=null;prefixId='';prefixOwner=owner;return;}
    const queue=player?.queue||[],index=queue.findIndex(t=>t.uuid===player.current?.uuid),track=queue[index+1];
    if(!track||track.isRadio||podcast(track.song)||track.uuid===prefixId||prefixJob||!audio()||audio().paused)return;
    prefixId=track.uuid;const controller=new AbortController();prefixJob=controller;
    const timer=setTimeout(()=>controller.abort(),7000);
    try{
      const raw=typeof track.musicSrc==='function'?await track.musicSrc():track.musicSrc;
      const url=new URL(raw,location.href);
      if(owner!==identity()||url.origin!==location.origin||!/^\/rest\/(stream|getTranscodeStream)(\.view)?$/.test(url.pathname))return;
      const response=await fetch(url,{headers:{Range:'bytes=0-524287'},signal:controller.signal,cache:'default'});
      if(!response.ok)return;
      const reader=response.body.getReader();let bytes=0;
      while(bytes<524288){const chunk=await reader.read();if(chunk.done)break;bytes+=chunk.value.byteLength;}
      await reader.cancel();
    }catch(_){}finally{clearTimeout(timer);if(prefixJob===controller)prefixJob=null;}
  }
  setInterval(prefetchNextAudio,4000);
  const audio = () => document.querySelector('audio.music-player-audio');
  const identity = () => localStorage.getItem('username') || '';
  const queueKey = kind => 'piterfy.queue.v1:' + account + ':' + kind;
  function findStore() {
    if (store) return store;
    const root = document.getElementById('root');
    if (!root) return;
    const key = Object.keys(root).find(k => k.startsWith('__reactContainer$'));
    const legacy = root._reactRootContainer;
    const stack = [legacy ? (legacy._internalRoot || legacy).current : key && root[key]];
    for (let i=0; stack.length && i<20000; i++) {
      const fiber=stack.pop(); if(!fiber) continue;
      if(fiber.memoizedProps?.store?.getState?.().player) return (store=fiber.memoizedProps.store);
      if(fiber.child) stack.push(fiber.child); if(fiber.sibling) stack.push(fiber.sibling);
    }
  }
  async function api(body) {
    const user=identity();
    if(!user || !localStorage.getItem('token')) throw Error('Zaloguj się w Piterfy.');
    const response=await fetch('/piterfy-api/connect/library',{method:'POST',cache:'no-store',
      headers:{'Content-Type':'application/json','X-ND-Authorization':'Bearer '+localStorage.getItem('token')},
      body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
    if(!response.ok) throw Error('Nie udało się połączyć. Spróbuj ponownie.');
    const result=await response.json(); if(user!==identity()) throw Error('Zmieniono konto.');
    if(result.exploration!==undefined) preferences=result;
    return result;
  }
  async function subsonic(command,extra={}) {
    const params=new URLSearchParams({u:identity(),t:localStorage.getItem('subsonic-token')||'',
      s:localStorage.getItem('subsonic-salt')||'',v:'1.16.1',c:'Piterfy',f:'json',...extra});
    const response=await fetch('/rest/'+command+'?'+params,{signal:AbortSignal.timeout(12000),cache:'no-store'});
    if(!response.ok)throw Error('Nie można pobrać biblioteki.');
    const result=(await response.json())['subsonic-response'];
    if(result?.status!=='ok')throw Error('Nie można pobrać biblioteki.');
    return result;
  }
  async function rankSongs(songs) {
    const unique=[...new Map(songs.filter(s=>s?.id&&!podcast(s)).map(s=>[s.id,s])).values()].slice(0,1000);
    const ranked=await api({action:'rank',items:unique.map(s=>({id:s.id,artist:s.artist||'',genre:s.genre||'',familiar:Number(s.playCount)>0}))});
    const byId=new Map(unique.map(s=>[s.id,s]));
    return ranked.items.map(row=>({...byId.get(row.id),piterfyReason:row.reason}));
  }
  window.PiterfyLibrary={rankSongs, play, prefetch:records, queue:openQueue, downloads:openDownloads, feedback:vote, exploration:()=>preferences.exploration??50, candidates:()=>subsonic('getRandomSongs',{size:100}).then(r=>r.randomSongs?.song||[])};
  const djScript=document.createElement('script');djScript.src='/piterfy-ui/dj.js?v='+encodeURIComponent(new URL(document.currentScript?.src||location.href).searchParams.get('v')||Date.now());document.head.append(djScript);
  async function records(songs) {
    const owner=identity();if(preparedIdentity!==owner){preparedRecords.clear();preparedIdentity=owner;}
    const missing=songs.filter(s=>!preparedRecords.has(s.id)||Date.now()-preparedRecords.get(s.id).at>600000);
    const byId=new Map();
    for(let offset=0;offset<missing.length;offset+=40){
      const response=await fetch('/api/song?'+missing.slice(offset,offset+40).map(s=>'id='+encodeURIComponent(s.id)).join('&'),{
        headers:{'X-ND-Authorization':'Bearer '+localStorage.getItem('token')},signal:AbortSignal.timeout(12000)});
      if(!response.ok)throw Error('Nie można przygotować kolejki.');
      for(const song of await response.json())byId.set(song.id,song);
    }
    if(owner!==identity())return [];
    for(const [id,song] of byId)preparedRecords.set(id,{song,at:Date.now()});
    while(preparedRecords.size>120)preparedRecords.delete(preparedRecords.keys().next().value);
    return songs.map(s=>preparedRecords.get(s.id)?.song).filter(Boolean);
  }
  function saveQueue(player=findStore()?.getState().player) {
    const element=audio();if(!player?.current?.song || !element || player.current.isRadio || applyingQueue)return;
    const kind=podcast(player.current.song)?'podcasts':'music';
    const queue=player.queue.filter(s=>!s.isRadio&&podcast(s.song)===(kind==='podcasts'));
    const index=queue.findIndex(s=>s.uuid===player.current.uuid);
    if(index<0)return;
    const start=Math.max(0,index-100);
    try{localStorage.setItem(queueKey(kind),JSON.stringify({ids:queue.slice(start,start+500).map(s=>s.trackId),index:index-start,position:element.currentTime,at:Date.now()}));}catch(_){}
  }
  async function play(songs,index=0,position=0,protect=false) {
    if(window.PiterfyPlayback?.current()?.remote)throw Error('Najpierw wybierz „Przenieś tutaj” w Connect.');
    if(protect&&songs.length){
      const wanted=songs[index]?.id,player=findStore()?.getState().player;
      songs=protectedQueue(songs,player?.queue||[],player?.queue[queueIndex(player.queue,player.current?.uuid,player.savedPlayIndex)]?.uuid,preferences.pins||[]);index=Math.max(0,songs.findIndex(s=>s.id===wanted));
    }
    saveQueue();window.PiterfyCrossfade?.cancel();
    const token=generation, s=findStore();if(!s)throw Error('Odtwarzacz nie jest jeszcze gotowy.');
    const fresh=await records(songs);if(token!==generation)return;
    if(!fresh.length)throw Error('Brak dostępnych utworów.');
    const data=Object.fromEntries(fresh.map((song,i)=>['pf_'+i,song]));
    const wanted=songs[index]?.id;
    const found=fresh.findIndex(song=>song.id===wanted);
    const target=found>=0?found:0;applyingQueue=true;
    s.dispatch({type:'PLAYER_PLAY_TRACKS',data,id:'pf_'+target});
    const expected=s.getState().player.queue[target]?.uuid;
    const deadline=performance.now()+10000;
    const wait=setInterval(()=>{
      if(token!==generation||performance.now()>deadline){clearInterval(wait);applyingQueue=false;return;}
      if(s.getState().player.current?.uuid===expected&&audio()?.readyState>=1){
        clearInterval(wait);if(position>0)audio().currentTime=position;applyingQueue=false;
      }
    },80);
  }
  async function restore(kind) {
    const saved=JSON.parse(localStorage.getItem(queueKey(kind))||'{}');
    if(!saved.ids?.length)throw Error('Ta kolejka jest jeszcze pusta.');
    await play(saved.ids.map(id=>({id})),saved.index,saved.position);
  }
  function el(tag,text,parent,cls) {
    const node=document.createElement(tag);if(text)node.textContent=text;if(cls)node.className=cls;parent?.append(node);return node;
  }
  function button(parent,text,handler,cls='pf-library-button') {
    const node=el('button',text,parent,cls);node.type='button';node.addEventListener('click',handler);
    for(const kind of ['keydown','keyup'])node.addEventListener(kind,event=>{if(event.key===' '||event.key==='Enter')event.stopPropagation();});
    return node;
  }
  function mobileBar() {
    let bar=document.querySelector('.pf-mobile-bar');
    const player=findStore()?.getState().player;
    if(!matchMedia('(max-width:809px)').matches||!player?.queue?.length){bar?.remove();return;}
    if(!bar){
      bar=el('div','',document.body,'pf-mobile-bar');
      const open=button(bar,'',()=>{
        if(window.PiterfyPlayback?.current()?.remote)document.getElementById('pf-connect-button')?.click();
        else {
          const controller=document.querySelector('.music-player-controller');
          if(!controller)return;
          // The native mini controller opens on drag-stop, rather than click.
          const point=controller.getBoundingClientRect();
          const event={bubbles:true,button:0,clientX:point.x+1,clientY:point.y+1};
          controller.dispatchEvent(new MouseEvent('mousedown',event));
          document.dispatchEvent(new MouseEvent('mouseup',event));
          controller.click();
        }
      },'pf-mobile-track');
      const art=el('img','',open);art.alt='';
      const labels=el('span','',open);el('strong','',labels);el('small','',labels);
      button(bar,'',()=>{
        const state=window.PiterfyPlayback?.current();
        const playing=state?.remote?state.playing:!!audio()&&!audio().paused;
        const action=playing?'pause':'play';
        const result=window.PiterfyPlayback?window.PiterfyPlayback.control(action):(action==='play'?audio()?.play():audio()?.pause());
        Promise.resolve(result).then(mobileBar).catch(()=>{});
      },'pf-mobile-play');
      const next=button(bar,'',()=>window.PiterfyPlayback?.control('next'),'pf-mobile-next');next.setAttribute('aria-label','Następny utwór');next.title='Następny utwór';
      const queue=button(bar,'☷',openQueue,'pf-mobile-next');queue.setAttribute('aria-label','Edytuj kolejkę');
      const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');const path=document.createElementNS(icon.namespaceURI,'path');path.setAttribute('d','M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z');path.setAttribute('fill','currentColor');icon.append(path);next.append(icon);
    }
    const remote=window.PiterfyPlayback?.current();
    const item=player.current?.song?player.current:player.queue[player.savedPlayIndex||0];
    const song=remote?.remote?remote:item?.song;
    if(!song)return;
    bar.hidden=!!document.querySelector('.react-jinke-music-player-mobile');
    const open=bar.querySelector('.pf-mobile-track');
    open.setAttribute('aria-label','Otwórz odtwarzacz: '+(song.title||''));
    open.querySelector('strong').textContent=song.title||'Wybierz utwór';
    open.querySelector('small').textContent=(song.artist||'')+(remote?.remote?' · '+remote.output:'');
    const cover=!remote?.remote&&item?.cover;
    const art=open.querySelector('img');art.hidden=!cover;
    if(cover&&art.getAttribute('src')!==cover)art.src=cover;
    const playing=remote?.remote?remote.playing:!!audio()&&!audio().paused;
    const control=bar.querySelector('.pf-mobile-play');control.textContent=playing?'❚❚':'▶';
    control.setAttribute('aria-label',playing?'Wstrzymaj':'Odtwarzaj');
  }
  function message(text) {const node=dialog?.querySelector('[role=status]');if(node)node.textContent=text;}
  let queueDialog,queueUndo=null;
  function openDownloads(){
    const panel=el('dialog','',document.body,'pf-library-dialog');panel.setAttribute('aria-label','Pobieranie i gotowe bloki');
    const head=el('header','',panel,'pf-library-heading');el('h2','Pobieranie i gotowe bloki',head);button(head,'Zamknij',()=>panel.close());
    el('p','Tutaj sprawdzisz przygotowanie muzyki na serwerze. Pobrania offline telefonu znajdziesz w aplikacji: Pobrane → Centrum pobierania.',panel,'pf-library-muted');
    const rows=el('div','',panel);const notice=el('p','',panel);notice.setAttribute('role','status');let sig='';
    function render(){
      if(!panel.open)return;const states=window.PiterfyDJ?.statuses?.()||[];const next=JSON.stringify(states);if(next===sig)return;sig=next;rows.replaceChildren();
      for(const state of states){
        const card=el('section','',rows,'pf-dj-card');el('h3',state.mode==='dj'?'Piterfy DJ':'Odkrywaj · nowe utwory',card);
        const p=state.progress||{},labels={queued:'W kolejce',matching:'Dobieranie muzyki',searching:'Wyszukiwanie',downloading:'Pobieranie na serwer',importing:'Dodawanie do biblioteki',voice:'Przygotowanie zapowiedzi',ready:'Gotowe',unavailable:'Nie udało się przygotować',hourly_limit:'Osiągnięto godzinny limit pobierania'};
        el('p',`Gotowe bloki: ${state.buffer?.ready||0}/2 · inne klimaty: ${state.buffer?.alternatives?.length||0}`,card);
        const progress=el('progress','',card);progress.max=100;progress.value=Math.max(0,Math.min(100,Number(p.percent)||0));progress.setAttribute('aria-label','Postęp przygotowania '+(state.mode==='dj'?'DJ':'Odkrywaj'));
        const stage=p.stage==='unavailable'&&state.buffer?.ready?'Zapas gotowy do odsłuchu. Nowe odkrycia chwilowo niedostępne.':labels[p.stage]||'Zapas przygotowuje się w tle';
        el('p',stage+(p.total?` · ${p.completed||0}/${p.total} plików`:''),card,'pf-library-muted');
        if(p.failed)el('p',`Nieudane pliki: ${p.failed}. Gotowy zapas nadal możesz odtworzyć.`,card,'pf-library-muted');
        button(card,'Otwórz '+(state.mode==='dj'?'DJ':'Odkrywaj'),()=>{panel.close();window.PiterfyDJ.open(state.mode);});
        button(card,'Sprawdź ponownie',async()=>{try{await window.PiterfyDJ.refreshBuffer(state.mode);notice.textContent='Sprawdzono przygotowanie. Pobieranie działa w ramach limitów serwera.';render();}catch(e){notice.textContent=e.message;}});
      }
      if(!states.length)notice.textContent='Uruchom DJ lub Odkrywaj, aby przygotować zapas muzyki.';
    }
    panel.addEventListener('close',()=>{clearInterval(poll);panel.remove();},{once:true});panel.showModal();render();const poll=setInterval(render,2000);
  }
  function queueSnapshot(){
    const remote=window.PiterfyPlayback?.remoteQueue?.();
    if(remote)return {...remote,remote:true,keys:remote.items.map(s=>s.id)};
    const p=findStore()?.getState().player,items=p?.queue||[];
    return {items,index:queueIndex(items,p?.current?.uuid,p?.savedPlayIndex),keys:items.map(s=>s.uuid),remote:false};
  }
  async function editQueue(action,from,to,expected){
    const q=queueSnapshot();
    if(JSON.stringify(q.keys)!==JSON.stringify(expected.keys)||q.remote!==expected.remote||q.remote&&q.version!==expected.version)throw Error('Kolejka się zmieniła. Spróbuj ponownie.');
    if(q.remote){const result=await window.PiterfyPlayback.editQueue(action,{from,to,expected:q.version});if(action==='remove')queueUndo={song:q.items[from],at:from,remote:true,version:result.version,remaining:result.items.map(s=>s.id),account:identity()};return;}
    const p=findStore().getState().player,items=[...q.items];
    if(action==='remove'){
      if(from===q.index)throw Error('Bieżący utwór pozostaje w kolejce. Najpierw wybierz następny.');
      const removed=items.splice(from,1)[0];queueUndo={song:removed,at:from,remaining:items.map(s=>s.uuid),account:identity()};
    }else if(action==='move')items.splice(to,0,items.splice(from,1)[0]);
    else throw Error('Nieobsługiwana zmiana kolejki.');
    findStore().dispatch({type:'PLAYER_SYNC_QUEUE',data:{audioInfo:p.current,audioLists:items}});saveQueue();
  }
  function openQueue(){
    if(queueDialog?.open)return;queueDialog?.remove();queueUndo=null;
    queueDialog=el('dialog','',document.body,'pf-library-dialog pf-queue-dialog');queueDialog.setAttribute('aria-labelledby','pf-queue-heading');
    const head=el('header','',queueDialog,'pf-library-heading');el('h2','Twoja kolejka',head).id='pf-queue-heading';button(head,'Zamknij',()=>queueDialog.close());
    el('p','Przeciągnij utwór lub użyj strzałek. Przypięte utwory pozostaną po zmianie klimatu DJ-a.',queueDialog,'pf-library-muted');
    const notice=el('p','',queueDialog);notice.setAttribute('role','status');
    const undo=button(queueDialog,'Cofnij usunięcie',async()=>{
      const q=queueSnapshot(),u=queueUndo;
      if(!u||u.account!==identity()||q.remote!==!!u.remote||JSON.stringify(q.keys)!==JSON.stringify(u.remaining)||u.remote&&u.version!==q.version){notice.textContent='Kolejka się zmieniła. Nie przywrócono starego układu.';return;}
      if(u.remote){try{await window.PiterfyPlayback.editQueue('insert',{items:[u.song],index:u.at,expected:u.version});queueUndo=null;renderQueue(true);notice.textContent='Przywrócono utwór.';}catch(e){notice.textContent=e.message;}return;}
      const items=[...q.items];items.splice(u.at,0,u.song);const p=findStore().getState().player;
      findStore().dispatch({type:'PLAYER_SYNC_QUEUE',data:{audioInfo:p.current,audioLists:items}});queueUndo=null;saveQueue();renderQueue();notice.textContent='Przywrócono utwór.';
    });undo.hidden=true;
    const rows=el('div','',queueDialog);let signature='',drag=null;
    const fail=e=>{notice.textContent=e.message;renderQueue(true);};
    function renderQueue(force=false){
      if(!queueDialog.open)return;
      const q=queueSnapshot(),sig=JSON.stringify([q.keys,q.index,q.remote,q.version,preferences.pins]);
      if(!force&&sig===signature)return;
      if(!force&&rows.contains(document.activeElement))return;
      signature=sig;rows.replaceChildren();undo.hidden=!queueUndo;
      if(!q.items.length)el('p','Kolejka jest pusta.',rows,'pf-library-muted');
      q.items.forEach((item,i)=>{
        const song=q.remote?item:{...item.song,id:item.trackId||item.song?.mediaFileId||item.song?.id},row=el('article','',rows,'pf-queue-row');row.draggable=true;
        const pin=preferences.pins?.includes(song.id);
        el('small',i===q.index?'TERAZ GRA':String(i+1).padStart(2,'0'),row,'pf-dj-eyebrow');
        el('strong',(pin?'📌 ':'')+(song.title||item.name||'Utwór'),row);el('span',song.artist||item.singer||'',row,'pf-library-muted');
        row.addEventListener('dragstart',e=>{drag={i,q};e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',String(i));});
        row.addEventListener('dragover',e=>e.preventDefault());row.addEventListener('drop',e=>{e.preventDefault();if(drag)editQueue('move',drag.i,i,drag.q).then(()=>renderQueue(true)).catch(fail);drag=null;});
        const controls=el('div','',row,'pf-library-actions');
        const move=(to)=>editQueue('move',i,to,q).then(()=>renderQueue(true)).catch(fail);
        const up=button(controls,'↑',()=>move(i-1));up.disabled=i===0;up.setAttribute('aria-label','Przesuń wyżej: '+song.title);
        const down=button(controls,'↓',()=>move(i+1));down.disabled=i===q.items.length-1;down.setAttribute('aria-label','Przesuń niżej: '+song.title);
        const next=button(controls,'Zagraj następne',()=>move(i<q.index?q.index:q.index+1));next.disabled=i===q.index||q.index<0;
        button(controls,pin?'Odepnij':'Przypnij',()=>api({action:'pin',id:song.id,enabled:!pin}).then(()=>{renderQueue(true);notice.textContent=pin?'Odpięto.':'Ten utwór zostanie zachowany przy zmianie klimatu.';}).catch(fail));
        const remove=button(controls,'Usuń',()=>editQueue('remove',i,i+1,q).then(()=>{renderQueue(true);notice.textContent='Usunięto utwór. Możesz cofnąć tę zmianę.';}).catch(fail));remove.disabled=i===q.index;
      });
    }
    queueDialog.showModal();renderQueue(true);api({action:'get'}).then(()=>renderQueue(true)).catch(fail);
    const poll=setInterval(renderQueue,1500);queueDialog.addEventListener('close',()=>clearInterval(poll),{once:true});
  }
  async function vote(song,value,reason='') {
    if(value===-1&&!reason)return new Promise((resolve,reject)=>{
      const choice=el('dialog','',document.body,'pf-library-dialog');choice.setAttribute('aria-label','Co nie pasuje?');el('h2','Co nie pasuje?',choice);el('p','Powód pomoże dokładniej dobierać muzykę. Możesz go pominąć.',choice,'pf-library-muted');
      for(const [key,title] of [['track','Ten utwór'],['artist','Wykonawca'],['genre','Gatunek'],['energy','Energia utworu'],['repetition','Zbyt częste powtórki']]){const b=button(choice,title,()=>{b.disabled=true;vote(song,value,key).then(()=>{choice.close();resolve();}).catch(e=>{b.disabled=false;reject(e);choice.close();});});}
      button(choice,'Bez podawania powodu',()=>vote(song,value,'track').then(()=>{choice.close();resolve();}).catch(reject));
      button(choice,'Anuluj',()=>{resolve(false);choice.close();});choice.addEventListener('close',()=>{choice.remove();resolve(false);},{once:true});choice.showModal();
    });
    await api({action:'feedback',id:song.id,artist:song.artist||'',genre:song.genre||'',reason,vote:value,event:crypto.randomUUID()});
    message('Zapisano — kolejne propozycje uwzględnią Twoją reakcję.');return true;
  }
  function open() {
    if(dialog?.open)return;
    dialog?.remove();dialog=el('dialog','',document.body,'pf-library-dialog');dialog.setAttribute('aria-labelledby','pf-library-heading');
    const head=el('header','',dialog,'pf-library-heading');el('h2','Odkrywaj',head).id='pf-library-heading';
    button(head,'Zamknij',()=>dialog.close());
    el('p','Więcej muzyki dopasowanej do Ciebie — z miejscem na nowe brzmienia.',dialog,'pf-library-muted');
    const label=el('label','Poziom eksperymentowania',dialog);label.htmlFor='pf-exploration';const value=el('output','50%',label);
    const slider=el('input','',dialog);slider.type='range';slider.id='pf-exploration';slider.min='0';slider.max='100';slider.step='5';slider.value=preferences.exploration??50;
    const update=()=>{value.textContent=slider.value+'%';slider.setAttribute('aria-valuetext',slider.value+' procent');};update();
    slider.oninput=update;slider.onchange=()=>api({action:'settings',exploration:Number(slider.value)}).then(()=>message('Zapisane na Twoim koncie.')).catch(e=>message(e.message));
    el('p','Znajome brzmienia ← → Nowi wykonawcy',dialog,'pf-library-muted');
    const actions=el('div','',dialog,'pf-library-actions');
    button(actions,'Edytuj kolejkę',()=>{dialog.close();openQueue();});
    button(actions,'Pobieranie i gotowe bloki',()=>{dialog.close();openDownloads();});
    button(actions,'Piterfy DJ',()=>{dialog.close();window.PiterfyDJ?.open('dj');});
    button(actions,'Nowe utwory · pobieranie',()=>{dialog.close();window.PiterfyDJ?.open('discover');});
    const status=el('p','',dialog,'pf-library-muted');status.setAttribute('role','status');
    const current=window.PiterfyPlayback?.current()?.remote?null:findStore()?.getState().player.current?.song;
    if(current&&!podcast(current)){
      const row=el('section','',dialog,'pf-library-current');el('strong',current.title+' · '+current.artist,row);
      button(row,'Więcej takich',()=>vote(current,1).catch(e=>message(e.message)));
      button(row,'Mniej takich',()=>vote(current,-1).catch(e=>message(e.message)));
    }
    const results=el('div','',dialog,'pf-library-results');
    const refresh=button(actions,'Odkryj muzykę',async()=>{
      refresh.disabled=true;results.replaceChildren();message('Dobieram propozycje…');const token=generation;
      try{
        const random=(await subsonic('getRandomSongs',{size:150})).randomSongs?.song||[];
        const similar=current?.artistId?(await subsonic('getSimilarSongs2',{id:current.artistId,count:40}).catch(()=>({}))).similarSongs2?.song||[]:[];
        const songs=(await rankSongs([...similar,...random])).slice(0,20);if(token!==generation||!dialog.open)return;
        window.PiterfyDJ?.prepare('discover',songs.slice(0,5)).catch(()=>{});
        if(songs.length)button(results,'Odtwórz propozycje',()=>{window.PiterfyDJ?.markDiscovery(songs);return play(songs).then(()=>dialog.close()).catch(e=>message(e.message));});
        for(const song of songs){
          const row=el('article','',results,'pf-library-song');el('strong',song.title,row);el('span',song.artist,row,'pf-library-muted');el('small',song.piterfyReason,row,'pf-library-muted');
          const controls=el('div','',row,'pf-library-actions');
          button(controls,'Więcej takich',()=>vote(song,1).catch(e=>message(e.message)));
          button(controls,'Mniej takich',()=>vote(song,-1).then(saved=>{if(saved)row.remove();}).catch(e=>message(e.message)));
        }
        message(songs.length?'Propozycje uwzględniają reakcje i wczesne pominięcia.':'Brak propozycji. Zmień ustawienie i spróbuj ponownie.');
      }catch(e){message(e.message);}finally{refresh.disabled=false;}
    });
    button(actions,'Kolejka muzyki',()=>restore('music').then(()=>dialog.close()).catch(e=>message(e.message)));
    button(actions,'Kolejka podcastów',()=>restore('podcasts').then(()=>dialog.close()).catch(e=>message(e.message)));
    api({action:'get'}).then(state=>{if(dialog.open){slider.value=state.exploration;update();}}).catch(e=>message(e.message));
    dialog.showModal();
  }
  async function saveProgress(item,position,duration) {
    if(saving||window.PiterfyPlayback?.current()?.remote||!podcast(item)||position<3||!Number.isFinite(duration)||duration<=0)return;
    saving=true;
    try{
      const result=await api({action:'progress',id:item.id,position:Math.min(duration,position)*1000,duration:duration*1000,
        revision:preferences.episodes?.[item.id]?.revision||0});
      if(result.conflict)await api({action:'get'});
    }catch(_){}finally{saving=false;}
  }
  async function loadProgress(item,element) {
    if(!podcast(item))return;
    const token=generation, guard=++seekGuard;
    try{
      const state=await api({action:'get'});const episode=state.episodes?.[item.id];
      if(token===generation&&guard===seekGuard&&findStore()?.getState().player.current?.trackId===item.id&&element.currentTime<5&&episode&&!episode.completed&&episode.position>5000)
        element.currentTime=episode.position/1000;
    }catch(_){}
  }
  function attachAudio(element) {
    if(!element||element.dataset.piterfyLibrary)return;
    element.dataset.piterfyLibrary='1';
    element.addEventListener('seeking',()=>{seekGuard++;});
    element.addEventListener('pause',()=>{const song=findStore()?.getState().player.current?.song;saveProgress(song,element.currentTime,element.duration);saveQueue();});
  }
  async function subscribe(id,button) {
    try{
      await api({action:'get'});const enabled=!(preferences.subscriptions||[]).includes(id);
      await api({action:'subscribe',id,enabled});button.textContent=enabled?'Subskrybujesz · auto offline na telefonie':'Subskrybuj · auto offline na telefonie';
    }catch(e){button.textContent=e.message;}
  }
  async function podcastTools(main) {
    if(main.querySelector('.pf-podcast-tools'))return;
    const section=el('section','',null,'pf-podcast-tools');
    const heading=main.querySelector('.pf-home-head');
    if(heading)heading.after(section);else main.prepend(section);
    const nav=el('nav','',section,'pf-library-actions');nav.setAttribute('aria-label','Muzyka, podcasty i radio');
    for(const [label,href] of [['Muzyka','#/start'],['Podcasty','#/podcasty'],['Radia','#/radio']]){
      const link=el('a',label,nav,'pf-library-button');link.href=href;
      if(href==='#/podcasty')link.setAttribute('aria-current','page');
    }
    const actions=el('div','',section,'pf-library-actions');
    const status=el('p','',section,'pf-library-muted');status.setAttribute('role','status');
    button(actions,'Wróć do kolejki podcastów',()=>restore('podcasts').catch(e=>status.textContent=e.message));
    button(actions,'Wróć do muzyki',()=>restore('music').catch(e=>status.textContent=e.message));
    el('p','Subskrypcje są wspólne z Androidem. Nowe odcinki pobiera automatycznie telefon przez Wi-Fi.',section,'pf-library-muted');
    const token=generation;
    try{
      const state=await api({action:'get'});if(token!==generation||!section.isConnected)return;
      const episodes=Object.values(state.episodes||{}).filter(e=>!e.completed&&e.position>5000).sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,8);
      if(episodes.length){
        const songs=await records(episodes.map(e=>({id:e.id})));
        if(token!==generation||!section.isConnected)return;
        el('h3','Kontynuuj słuchanie',section);
        const cards=el('div','',section,'pf-library-actions');
        for(const song of songs){const episode=state.episodes[song.id];
          button(cards,song.title+' · '+Math.floor(episode.position/60000)+':'+String(Math.floor(episode.position/1000)%60).padStart(2,'0'),
            ()=>play([song],0,episode.position/1000).catch(e=>status.textContent=e.message));
        }
      }
    }catch(e){status.textContent=e.message;}
  }
  function mount() {
    if(account!==identity()) {generation++;account=identity();preferences={};previous=null;store=null;checkedAlbum='';applyingQueue=false;dialog?.close();}
    if(!account||location.hash.startsWith('#/login')){document.querySelector('.pf-mobile-bar')?.remove();return;}
    const categories=document.querySelector('.pf-home .pf-chips');
    if(categories){categories.setAttribute('role','navigation');categories.setAttribute('aria-label','Biblioteka Piterfy');for(const link of categories.querySelectorAll('a')){if(link.hash===(location.hash||'#/start'))link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');}}
    for(const [selector,label] of [['.react-jinke-music-player-mobile-header-right','Zamknij odtwarzacz'],['.react-jinke-music-player-mobile .prev-audio','Poprzedni utwór'],['.react-jinke-music-player-mobile .next-audio','Następny utwór'],['.react-jinke-music-player-mobile .play-btn',audio()?.paused?'Odtwórz':'Wstrzymaj'],['.music-player-panel .play-btn',audio()?.paused?'Odtwórz':'Wstrzymaj']]){
      for(const node of document.querySelectorAll(selector)){
        if(node.matches('button,input,a'))continue;
        node.setAttribute('role','button');node.setAttribute('aria-label',label);node.tabIndex=0;
        if(!node.dataset.pfKeyboard){node.dataset.pfKeyboard='true';node.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.stopPropagation();if(!event.repeat)node.click();}});node.addEventListener('keyup',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.stopPropagation();}});}
      }
    }
    attachAudio(audio());
    const toolbar=document.querySelector('#react-admin-title')?.parentElement;
    if(toolbar&&!document.querySelector('.pf-discovery-launch'))button(toolbar,'Odkrywaj',open,'pf-library-button pf-discovery-launch');
    const panel=document.querySelector('.music-player-panel .player-content, .react-jinke-music-player-mobile-operation');
    if(panel&&!panel.querySelector('.pf-discovery-feedback'))button(panel,'Polecenia',open,'group pf-library-button pf-discovery-feedback');
    const match=location.hash.match(/^#\/album\/([^/]+)\/show/);
    const title=document.querySelector('#react-admin-title');
    if(match&&title&&checkedAlbum!==match[1]){
      checkedAlbum=match[1];document.querySelector('.pf-subscribe-launch')?.remove();
      const id=decodeURIComponent(match[1]);
      subsonic('getAlbum',{id}).then(result=>{
        if(location.hash.includes(id)&&result.album?.song?.some(podcast)&&!document.querySelector('.pf-subscribe-launch'))
          button(title.parentElement,'Subskrybuj · auto offline na telefonie',event=>subscribe(id,event.currentTarget),'pf-library-button pf-subscribe-launch');
      }).catch(()=>{});
    }
    if(!match){checkedAlbum='';document.querySelector('.pf-subscribe-launch')?.remove();}
    const main=document.querySelector('#main-content .pf-home');
    if(location.hash.startsWith('#/podcasty')&&main)podcastTools(main);
    else document.querySelector('.pf-podcast-tools')?.remove();
    const art=document.querySelector('.music-player-panel .img-content');
    const background=art&&getComputedStyle(art).backgroundImage;
    const raw=background?.match(/^url\(["']?(.*?)["']?\)$/)?.[1];
    if(raw&&raw!==coverSource){
      coverSource=raw;const token=generation;const url=new URL(raw,location.href);
      if(url.origin===location.origin){
        const image=new Image();image.crossOrigin='anonymous';
        image.onload=()=>{try{
          if(token!==generation||coverSource!==raw)return;
          const canvas=document.createElement('canvas');canvas.width=canvas.height=24;
          const context=canvas.getContext('2d',{willReadFrequently:true});context.drawImage(image,0,0,24,24);
          const pixels=context.getImageData(0,0,24,24).data;const sum=[0,0,0];let total=0;
          for(let i=0;i<pixels.length;i+=4){const rgb=[pixels[i],pixels[i+1],pixels[i+2]],weight=(Math.max(...rgb)-Math.min(...rgb))/255;if(weight<.12)continue;total+=weight;rgb.forEach((v,j)=>sum[j]+=v*weight);}
          const color=total?sum.map(v=>Math.round(v/total*.28)):[18,18,18];
          document.documentElement.style.setProperty('--pf-cover-accent','rgb('+color.join(',')+')');
        }catch(_){}};
        image.src=url.href;
      }
    }
  }
  function observe() {
    const player=findStore()?.getState().player,element=audio();if(!player||!element||!account)return;
    mobileBar();
    const song=player.current?.song;
    if(song&&!element.paused&&!applyingQueue&&!window.PiterfyPlayback?.current()?.remote&&
      !player.queue.some(item=>item.isRadio)&&player.queue.some(item=>podcast(item.song)!==podcast(song))){
      const selected=player.queue.filter(item=>podcast(item.song)===podcast(song));
      const other=player.queue.filter(item=>podcast(item.song)!==podcast(song));
      const kind=podcast(song)?'music':'podcasts';
      try{
        const old=JSON.parse(localStorage.getItem(queueKey(kind))||'{}');const wanted=old.ids?.[old.index];
        const ids=other.slice(0,500).map(item=>item.trackId);const index=Math.max(0,ids.indexOf(wanted));
        localStorage.setItem(queueKey(kind),JSON.stringify({ids,index,position:old.position||0,at:Date.now()}));
      }catch(_){}
      applyingQueue=true;
      const index=selected.findIndex(item=>item.uuid===player.current.uuid);
      play(selected.map(item=>({id:item.trackId})),Math.max(0,index),element.currentTime)
        .catch(()=>{applyingQueue=false;});
      return;
    }
    if(previous?.uuid!==player.current?.uuid){
      if(previous)saveProgress(previous.song,previous.position,previous.duration);
      if(song)loadProgress(song,element);
    }
    previous={uuid:player.current?.uuid,song,position:element.currentTime,duration:element.duration};
    if(Date.now()-lastSave>15000){lastSave=Date.now();saveQueue();if(!element.paused)saveProgress(song,element.currentTime,element.duration);}
  }
  document.addEventListener('click',event=>{
    if(!event.target.closest?.('.next-audio, .pf-mobile-next, .audio-lists-panel li')||window.PiterfyPlayback?.current()?.remote)return;
    const player=findStore()?.getState().player,song=player?.current?.song,element=audio();
    if(!song||podcast(song)||!element)return;
    const before={id:song.id,artist:song.artist||'',position:element.currentTime*1000,duration:element.duration*1000,event:crypto.randomUUID()};
    setTimeout(()=>{if(findStore()?.getState().player.current?.song?.id!==before.id&&before.position>=3000&&before.position<Math.min(30000,before.duration*.3))api({action:'skip',...before}).catch(()=>{});},1000);
  },true);
  window.addEventListener('pagehide',()=>saveQueue());
  const css=el('link','',document.head);css.rel='stylesheet';css.href='/piterfy-ui/library.css?v='+encodeURIComponent(new URL(document.currentScript?.src||location.href).searchParams.get('v')||Date.now());
  const experience=el('link','',document.head);experience.rel='stylesheet';experience.href=css.href.replace('/library.css?','/experience.css?');
  mount();setInterval(mount,2000);setInterval(observe,500);
})();
