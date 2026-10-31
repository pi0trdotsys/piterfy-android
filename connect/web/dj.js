/* DJ controls and narration reuse the native music queue; speech is never a music item. */
(() => {
  'use strict';
  async function beforeMusic(voice,music,valid){try{await voice();}catch(_){}if(valid())await music();}
  function readyBlock(state){return state?.active&&state.progress?.complete&&!state.progress.failed?(state.blocks||[]).filter(b=>b.ready!==false).at(-1)||null:null;}
  if(typeof module==='object'&&module.exports){module.exports={beforeMusic,readyBlock};return;}
  if(window.PiterfyDJ)return;
  let dialog,mode='dj',states={},account=localStorage.getItem('username')||'',busy=false,clip=null,clipUrl='',narrating=false,lastRefill=0,wantStart='',speechGeneration=0;
  let pendingPlayback=null,skipIntro=null,settleIntro=null;
  let armedSession=sessionStorage.getItem('pf-dj-armed:'+account)||'',hookedPending=null,manualDiscover=[];
  const hooked=new WeakSet();
  function arm(state){manualDiscover=[];armedSession=state.id;sessionStorage.setItem('pf-dj-armed:'+account,armedSession);}
  function disarm(){manualDiscover=[];armedSession='';sessionStorage.removeItem('pf-dj-armed:'+account);}
  function introduction(state,block,index){return index===0?block:block.announcements?.find(a=>a.track===block.tracks[index]?.id);}
  function selected(song=current()){
    for(const state of Object.values(states))if(state.active&&(state.id===armedSession||song?.remote))for(const block of state.blocks||[]){const index=block.tracks.findIndex(t=>t.id===song?.id);if(index>=0)return{state,block,index};}
    return null;
  }
  function sourceLabel(){const s=selected();return s?(s.state.mode==='dj'?'DJ':'Odkrywaj'):manualDiscover.includes(current()?.id)?'Odkrywaj':'';}
  const element=()=>document.querySelector('audio.music-player-audio');
  const current=()=>window.PiterfyPlayback?.current();
  const messages={queued:'Przygotowuję następny blok…',downloading:'Szukam i pobieram nowe utwory…',
    ready:'Kolejny blok gotowy',no_new_tracks:'Nowe utwory jeszcze niedostępne. Muzyka z biblioteki gra dalej.',
    temporarily_unavailable:'Pobieranie chwilowo niedostępne. Możesz słuchać gotowych bloków.',hourly_limit:'Wykorzystano limit pobierania na tę godzinę.'};
  async function request(body){
    const user=localStorage.getItem('username');
    const response=await fetch('/piterfy-api/connect/dj',{method:'POST',headers:{'Content-Type':'application/json',
      'X-ND-Authorization':'Bearer '+localStorage.getItem('token')},body:JSON.stringify({...body,device:window.PiterfyPlayback?.deviceId?.()}),signal:AbortSignal.timeout(12000)});
    if(user!==localStorage.getItem('username'))throw Error('Zmieniono konto');
    if(!response.ok)throw Error(response.status===409?'Przenieś odtwarzanie na to urządzenie.':'DJ jest chwilowo niedostępny. Spróbuj ponownie.');
    return response.json();
  }
  function button(parent,label,handler){const b=document.createElement('button');b.className='pf-library-button';b.textContent=label;b.type='button';b.onclick=async()=>{if(b.disabled)return;b.disabled=true;try{await handler();}catch(e){status(e.message);}finally{b.disabled=b.dataset.djSkip?!skipIntro:false;}};parent.append(b);return b;}
  function status(text){const s=dialog?.querySelector('[role=status]');if(s)s.textContent=text;}
  function stopVoice(){speechGeneration++;settleIntro?.();settleIntro=null;if(clip){clip.pause();clip.removeAttribute('src');clip.load();clip=null;}if(clipUrl)URL.revokeObjectURL(clipUrl);clipUrl='';narrating=false;pendingPlayback=null;skipIntro=null;}
  async function prepare(kind,seeds){
    const valid=seeds.filter(s=>s.artist&&!/podcast/i.test(s.genre||'')).slice(0,6);
    if(!valid.length)throw Error('Wybierz najpierw muzykę, od której zaczniemy.');
    const state=await request({action:'start',mode:kind,seeds:valid.map(s=>({id:s.id,artist:s.artist,title:s.title,genre:s.genre||''})),
      exploration:dialog?.open&&mode===kind?Number(dialog.querySelector('input[type=range]').value):window.PiterfyLibrary.exploration()});
    states[kind]=state;return state;
  }
  async function playBlock(state,block,index=0,resume=null){
    if(current()?.remote)throw Error('Najpierw wybierz „Przenieś tutaj” w Connect.');
    if(block.ready===false)throw Error('Ten blok jeszcze się przygotowuje.');
    arm(state);const announcement=introduction(state,block,index);
    stopVoice();window.PiterfyCrossfade?.cancel();element()?.pause();
    if(!state.active||!state.voice||!announcement?.voiceKey||announcement.announced)return resume?resume():window.PiterfyLibrary.play(block.tracks,index);
    const volume=element()?.volume??1,guard=speechGeneration;
    // Connect sees the intended queue during the introduction, although no music has started.
    pendingPlayback={items:block.tracks.map(s=>({id:s.id,title:s.title||'',artist:s.artist||'',album:s.album||'',albumId:s.albumId||'',artistId:s.artistId||'',cover:s.coverArt||s.id,suffix:s.suffix||'',duration:Number(s.duration)||0,bitrate:Number(s.bitRate)||0})),index,position:0,playing:true,volume,repeat:0,shuffle:false,speed:1};
    const startMusic=async()=>{
      if(guard!==speechGeneration)return;
      if(current()?.remote){stopVoice();return;}
      stopVoice();await (resume?resume():window.PiterfyLibrary.play(block.tracks,index));render();
    };
    skipIntro=startMusic;render();
    const voice=async()=>{
      const claimed=await request({action:'claim',mode:state.mode,block:block.id,track:block.tracks[index].id});
      if(guard!==speechGeneration)return;
      announcement.announced=true;
      if(!claimed.speak)return;
      const data=await request({action:'audio',mode:state.mode,key:claimed.voiceKey});
      if(guard!==speechGeneration||current()?.remote)return;
      const bytes=Uint8Array.from(atob(data.audio),c=>c.charCodeAt(0));
      clipUrl=URL.createObjectURL(new Blob([bytes],{type:'audio/wav'}));clip=new Audio(clipUrl);clip.volume=volume;
      await new Promise((resolve,reject)=>{
        settleIntro=resolve;clip.onended=resolve;clip.onerror=reject;
        narrating=true;render();clip.play().catch(reject);
      });
    };
    await beforeMusic(voice,startMusic,()=>guard===speechGeneration&&!current()?.remote);
  }
  function render(){
    if(!dialog?.open)return;const state=states[mode];
    status(pendingPlayback?(narrating?'DJ zapowiada następny blok…':'Przygotowuję zapowiedź…'):state?.active?(messages[state.status]||'Sesja gotowa'):'Uruchom sesję. Nowe utwory pobiorę niezależnie od codziennego Odkrywaj.');
    const voiceButton=dialog.querySelector('[data-dj-voice]');if(voiceButton){voiceButton.textContent=state?.voice?'Głos DJ-a: włączony':'Głos DJ-a: wyłączony';voiceButton.setAttribute('aria-pressed',String(!!state?.voice));}
    dialog.querySelector('.pf-dj-avatar')?.classList.toggle('is-active',!!pendingPlayback||!!current()?.playing&&!!state?.active);
    const skip=dialog.querySelector('[data-dj-skip]');if(skip)skip.disabled=!skipIntro;
    const progress=dialog.querySelector('.pf-dj-progress');
    if(progress){const p=state?.progress||{},value=Math.max(0,Math.min(100,p.percent||0)),unknown=['queued','matching','searching'].includes(p.stage)||!p.stage;
      progress.classList.toggle('is-indeterminate',unknown);progress.setAttribute('aria-valuenow',String(value));progress.querySelector('i').style.width=value+'%';
      const stages={queued:'Oczekiwanie',matching:'Dobieranie muzyki',searching:'Wyszukiwanie plików',downloading:'Pobieranie',importing:'Import do biblioteki',voice:'Przygotowanie zapowiedzi',ready:'Cały blok gotowy',unavailable:'Przygotowanie niedostępne'};
      progress.querySelector('span').textContent=(stages[p.stage]||'Kliknij Uruchom, aby przygotować blok')+(p.total?' · '+p.completed+'/'+p.total:'');
      if(p.complete&&p.failed)status('Nie wszystkie pliki są dostępne. Wybierz gotowy blok lub spróbuj ponownie.');}
    for(const key of ['summaryEmail','summaryTelegram']){const b=dialog.querySelector(`[data-dj-setting="${key}"]`);if(b){b.textContent=(key==='summaryEmail'?'Podsumowania e-mail: ':'Podsumowania Telegram: ')+(state?.[key]===false?'wyłączone':'włączone');b.setAttribute('aria-pressed',String(state?.[key]!==false));}}
    const rows=dialog.querySelector('.pf-dj-blocks');rows.replaceChildren();
    for(const block of state?.blocks||[]){const section=document.createElement('section');section.className='pf-library-song';
      const title=document.createElement('strong');title.textContent=block.title;section.append(title);
      const text=document.createElement('p');text.textContent=block.text;section.append(text);
      const list=document.createElement('p');list.className='pf-library-muted';list.textContent=block.tracks.map(t=>t.artist+' — '+t.title).join(' · ');section.append(list);
      button(section,'Odtwórz gotowy blok',()=>playBlock(state,block)).disabled=block.ready===false;
      const votes=document.createElement('div');votes.className='pf-library-actions';section.append(votes);
      for(const [label,vote] of [['Blok OK',1],['Nie mój klimat',-1]])button(votes,label,async()=>{await request({action:'block-feedback',mode:state.mode,block:block.id,vote});status('Zapisano ocenę bloku. Uwzględnię ją w kolejnych odkryciach.');});
      rows.append(section);}
  }
  async function open(kind='dj'){
    mode=kind;dialog?.close();dialog?.remove();dialog=document.createElement('dialog');dialog.className='pf-library-dialog pf-dj-dialog';document.body.append(dialog);
    const head=document.createElement('header');head.className='pf-library-heading';const h=document.createElement('h2');h.textContent=kind==='dj'?'Piterfy DJ':'Odkrywaj · nowe utwory';head.append(h);button(head,'Zamknij',()=>dialog.close());dialog.append(head);
    if(kind==='dj'){const avatar=document.createElement('div');avatar.className='pf-dj-avatar';avatar.setAttribute('aria-hidden','true');const core=document.createElement('div');core.className='pf-dj-orb';for(let i=0;i<5;i++)core.append(document.createElement('i'));avatar.append(core);dialog.append(avatar);}
    const intro=document.createElement('p');intro.textContent=kind==='dj'?'Twoja audycja: znajome brzmienia, nowe gatunki i polskie zapowiedzi.':'Osobna sesja wyszukiwania i pobierania muzyki spoza biblioteki.';dialog.append(intro);
    const progress=document.createElement('div');progress.className='pf-dj-progress';progress.setAttribute('role','progressbar');progress.setAttribute('aria-label','Przygotowanie całego bloku');progress.setAttribute('aria-valuemin','0');progress.setAttribute('aria-valuemax','100');progress.append(document.createElement('i'),document.createElement('span'));dialog.append(progress);
    const label=document.createElement('label');label.textContent='Eksperymentowanie';const slider=document.createElement('input');slider.type='range';slider.min=0;slider.max=100;slider.step=5;slider.value=states[kind]?.exploration??window.PiterfyLibrary.exploration();slider.setAttribute('aria-label','Eksperymentowanie DJ-a');label.append(slider);dialog.append(label);
    slider.onchange=()=>request({action:'settings',mode:kind,exploration:Number(slider.value)}).then(s=>{states[kind]=s;render();}).catch(e=>status(e.message));
    const actions=document.createElement('div');actions.className='pf-library-actions';dialog.append(actions);
    button(actions,'Uruchom',async()=>{stopVoice();element()?.pause();disarm();status('Przygotowuję cały blok…');const seeds=await window.PiterfyLibrary.rankSongs(await window.PiterfyLibrary.candidates());const state=await prepare(kind,seeds.slice(0,6));wantStart=state.id;render();});
    button(actions,'Zmień klimat',async()=>{
      const state=states[kind],song=current(),blocks=state?.blocks||[];
      if(!state?.active)throw Error('Najpierw uruchom sesję DJ.');
      const index=blocks.findIndex(b=>b.tracks.some(t=>t.id===song?.id));
      if(current()?.remote)throw Error('Najpierw wybierz „Przenieś tutaj” w Connect.');
      if(blocks[index+1])await playBlock(state,blocks[index+1]);
      else{nextWanted={mode:kind,session:state.id,seen:blocks.map(b=>b.id)};states[kind]=await request({action:'next',mode:kind});render();status('Przygotowuję inny klimat. Przejdę do niego, gdy będzie gotowy.');}
    });
    if(kind==='dj')button(actions,'Głos DJ-a',async()=>{const state=states[kind];states[kind]=await request({action:'settings',mode:kind,voice:!state?.voice});if(!states[kind].voice)await skipIntro?.();render();}).dataset.djVoice='true';
    button(actions,'Pomiń zapowiedź',()=>skipIntro?.()).dataset.djSkip='true';
    {
      const feedback=document.createElement('div');feedback.className='pf-library-actions';dialog.append(feedback);
      for(const [label,vote] of [['Więcej takich',1],['Mniej takich',-1]])button(feedback,label,async()=>{
        const song=current();if(!song||(states[kind]?.blocks||[]).every(b=>b.tracks.every(t=>t.id!==song.id)))throw Error('Odtwórz najpierw utwór z tej sesji.');
        const r=await fetch('/piterfy-api/connect/library',{method:'POST',headers:{'Content-Type':'application/json','X-ND-Authorization':'Bearer '+localStorage.getItem('token')},body:JSON.stringify({action:'feedback',id:song.id,artist:song.artist||'',vote,event:crypto.randomUUID()})});
        if(!r.ok)throw Error('Nie udało się zapisać reakcji.');status('Zapisano reakcję na: '+song.title);
      });
    }
    for(const key of ['summaryEmail','summaryTelegram'])button(actions,key,async()=>{states[kind]=await request({action:'settings',mode:kind,[key]:states[kind]?.[key]===false});render();}).dataset.djSetting=key;
    button(actions,'Zakończ sesję',async()=>{wantStart='';nextWanted=null;disarm();if(skipIntro)await skipIntro();else stopVoice();states[kind]=await request({action:'stop',mode:kind});render();});
    const info=document.createElement('p');info.setAttribute('role','status');dialog.append(info);const rows=document.createElement('div');rows.className='pf-dj-blocks';dialog.append(rows);dialog.showModal();
    try{states[kind]=await request({action:'get',mode:kind});slider.value=states[kind].exploration??window.PiterfyLibrary.exploration();render();}catch(e){status(e.message);}
  }
  let lastTrack='',observed=null,nextWanted=null;
  function completeObserved(){const previous=observed;observed=null;if(previous?.heard)request({action:'complete',mode:previous.mode,session:previous.session,block:previous.block}).catch(()=>{});}
  function observe(song){
    let match=null;
    for(const state of Object.values(states))if(state.active&&state.id===armedSession)for(const b of state.blocks||[])if(b.tracks.some(t=>t.id===song?.id))match={mode:state.mode,session:state.id,block:b.id,last:b.tracks.at(-1)?.id===song.id};
    if(observed&&(!match||match.block!==observed.block||match.session!==observed.session))completeObserved();
    if(match){observed=observed||{...match,heard:false};if(song?.playing&&Number(song.position)>10000)observed.heard=true;if(match.last&&element()?.ended)completeObserved();}
  }
  async function poll(){
    const identity=localStorage.getItem('username')||'';
    if(identity!==account){stopVoice();states={};observed=null;wantStart='';nextWanted=null;manualDiscover=[];account=identity;armedSession=sessionStorage.getItem('pf-dj-armed:'+account)||'';}
    if(!identity||busy)return;busy=true;
    try{states=await request({action:'list'});if(pendingPlayback&&!states.dj?.voice)await skipIntro?.();render();}
    catch(_){}finally{busy=false;}
  }
  setInterval(poll,8000);
  setInterval(()=>{
    const song=current();if(song?.remote){stopVoice();return;}
    dialog?.querySelector('.pf-dj-avatar')?.classList.toggle('is-active',!!pendingPlayback||!!song?.playing&&!!states[mode]?.active);
    observe(song);
    if(nextWanted){const s=states[nextWanted.mode],b=s?.blocks?.find(b=>!nextWanted.seen.includes(b.id)&&b.ready!==false);if(!s?.active||s.id!==nextWanted.session||s.progress?.failed&&s.progress.complete)nextWanted=null;else if(b&&s.progress?.complete){nextWanted=null;playBlock(s,b).catch(e=>status(e.message));return;}}
    if(pendingPlayback){if(element()&&!element().paused)stopVoice();return;}
    for(const state of Object.values(states))if(state.active&&state.id===wantStart&&state.progress?.complete){wantStart='';const block=readyBlock(state);if(block)playBlock(state,block).catch(e=>status(e.message));return;}
    if(!song)return;
    if(narrating&&element()&&!element().paused)stopVoice();
    for(const state of Object.values(states))if(state.active&&state.id===armedSession){
      const blocks=state.blocks||[], index=blocks.findIndex(b=>b.tracks.some(t=>t.id===song.id));
      if(index<0)continue;
      const block=blocks[index];
      const last=block.tracks.at(-1)?.id===song.id;
      if(last&&element()?.ended&&blocks[index+1]?.ready!==false&&blocks[index+1])playBlock(state,blocks[index+1]).catch(()=>{});
      if(song.playing&&!narrating&&index>=blocks.length-2&&Date.now()-lastRefill>90000&&!['queued','downloading'].includes(state.status)){
        lastRefill=Date.now();request({action:'refill',mode:state.mode}).then(s=>{states[state.mode]=s;}).catch(()=>{});}
    }lastTrack=song.id;
  },1000);
  window.addEventListener('pagehide',stopVoice);
  window.PiterfyDJ={open,prepare,sourceLabel,markDiscovery:songs=>{disarm();manualDiscover=songs.map(s=>s.id);},isNarrating:()=>narrating||!!pendingPlayback,pendingPlayback:()=>pendingPlayback,cancelNarration:stopVoice,canCrossfade:()=>!selected()?.state.voice};
  setInterval(()=>{
    const audio=element();if(audio&&!hooked.has(audio)){
      const original=audio.play;hooked.add(audio);
      audio.play=function(...args){
        if(hookedPending)return hookedPending;
        const chosen=selected(),a=chosen&&introduction(chosen.state,chosen.block,chosen.index);
        if(!pendingPlayback&&chosen?.state.voice&&a?.voiceKey&&!a.announced&&audio.currentTime<1.5){
          hookedPending=playBlock(chosen.state,chosen.block,chosen.index,()=>original.apply(audio,args)).finally(()=>{hookedPending=null;});return hookedPending;
        }
        return original.apply(audio,args);
      };
    }
    const label=sourceLabel();
    for(const target of document.querySelectorAll('.music-player-panel .panel-content,.react-jinke-music-player-mobile')){
      let badge=target.querySelector('.pf-dj-source');if(!badge){badge=document.createElement('span');badge.className='pf-dj-source';target.append(badge);}badge.textContent=label;badge.hidden=!label;badge.setAttribute('aria-label',label?'Tryb odtwarzania: '+label:'');
    }
  },500);
  setInterval(()=>{const header=document.querySelector('.pf-discovery-launch')?.parentElement;if(header&&!header.querySelector('[data-dj-launch]'))button(header,'DJ',()=>open('dj')).dataset.djLaunch='true';},1500);
})();
