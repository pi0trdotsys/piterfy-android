/* DJ controls and narration reuse the native music queue; speech is never a music item. */
(() => {
  'use strict';
  async function beforeMusic(voice,music,valid){try{await voice();}catch(_){}if(valid())await music();}
  if(typeof module==='object'&&module.exports){module.exports={beforeMusic};return;}
  if(window.PiterfyDJ)return;
  let dialog,mode='dj',states={},account=localStorage.getItem('username')||'',busy=false,clip=null,clipUrl='',narrating=false,lastRefill=0,wantStart='',speechGeneration=0;
  let pendingPlayback=null,skipIntro=null,settleIntro=null;
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
  function button(parent,label,handler){const b=document.createElement('button');b.className='pf-library-button';b.textContent=label;b.type='button';b.onclick=()=>Promise.resolve(handler()).catch(e=>status(e.message));parent.append(b);return b;}
  function status(text){const s=dialog?.querySelector('[role=status]');if(s)s.textContent=text;}
  function stopVoice(){speechGeneration++;settleIntro?.();settleIntro=null;if(clip){clip.pause();clip.removeAttribute('src');clip.load();clip=null;}if(clipUrl)URL.revokeObjectURL(clipUrl);clipUrl='';narrating=false;pendingPlayback=null;skipIntro=null;}
  async function prepare(kind,seeds){
    const valid=seeds.filter(s=>s.artist&&!/podcast/i.test(s.genre||'')).slice(0,6);
    if(!valid.length)throw Error('Wybierz najpierw muzykę, od której zaczniemy.');
    const state=await request({action:'start',mode:kind,seeds:valid.map(s=>({id:s.id,artist:s.artist,title:s.title,genre:s.genre||''})),
      exploration:dialog?.open&&mode===kind?Number(dialog.querySelector('input[type=range]').value):window.PiterfyLibrary.exploration()});
    states[kind]=state;return state;
  }
  async function playBlock(state,block){
    if(current()?.remote)throw Error('Najpierw wybierz „Przenieś tutaj” w Connect.');
    stopVoice();window.PiterfyCrossfade?.cancel();element()?.pause();
    if(!state.active||!state.voice||!block.voiceKey||block.announced)return window.PiterfyLibrary.play(block.tracks);
    const volume=element()?.volume??1,guard=speechGeneration;
    // Connect sees the intended queue during the introduction, although no music has started.
    pendingPlayback={items:block.tracks.map(s=>({id:s.id,title:s.title||'',artist:s.artist||'',album:s.album||'',albumId:s.albumId||'',artistId:s.artistId||'',cover:s.coverArt||s.id,suffix:s.suffix||'',duration:Number(s.duration)||0,bitrate:Number(s.bitRate)||0})),index:0,position:0,playing:true,volume,repeat:0,shuffle:false,speed:1};
    const startMusic=async()=>{
      if(guard!==speechGeneration)return;
      if(current()?.remote){stopVoice();return;}
      stopVoice();await window.PiterfyLibrary.play(block.tracks);render();
    };
    skipIntro=startMusic;render();
    const voice=async()=>{
      const claimed=await request({action:'claim',mode:state.mode,block:block.id});
      if(guard!==speechGeneration)return;
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
    const rows=dialog.querySelector('.pf-dj-blocks');rows.replaceChildren();
    for(const block of state?.blocks||[]){const section=document.createElement('section');section.className='pf-library-song';
      const title=document.createElement('strong');title.textContent=block.title;section.append(title);
      const text=document.createElement('p');text.textContent=block.text;section.append(text);
      const list=document.createElement('p');list.className='pf-library-muted';list.textContent=block.tracks.map(t=>t.artist+' — '+t.title).join(' · ');section.append(list);
      button(section,'Odtwórz blok',()=>playBlock(state,block));rows.append(section);}
  }
  async function open(kind='dj'){
    mode=kind;dialog?.close();dialog?.remove();dialog=document.createElement('dialog');dialog.className='pf-library-dialog pf-dj-dialog';document.body.append(dialog);
    const head=document.createElement('header');head.className='pf-library-heading';const h=document.createElement('h2');h.textContent=kind==='dj'?'Piterfy DJ':'Odkrywaj · nowe utwory';head.append(h);button(head,'Zamknij',()=>dialog.close());dialog.append(head);
    const intro=document.createElement('p');intro.textContent=kind==='dj'?'Twoja audycja: znajome brzmienia, nowe gatunki i polskie zapowiedzi.':'Osobna sesja wyszukiwania i pobierania muzyki spoza biblioteki.';dialog.append(intro);
    const label=document.createElement('label');label.textContent='Eksperymentowanie';const slider=document.createElement('input');slider.type='range';slider.min=0;slider.max=100;slider.step=5;slider.value=states[kind]?.exploration??window.PiterfyLibrary.exploration();slider.setAttribute('aria-label','Eksperymentowanie DJ-a');label.append(slider);dialog.append(label);
    slider.onchange=()=>request({action:'settings',mode,exploration:Number(slider.value)}).then(s=>{states[mode]=s;render();}).catch(e=>status(e.message));
    const actions=document.createElement('div');actions.className='pf-library-actions';dialog.append(actions);
    button(actions,'Uruchom',async()=>{status('Dobieram muzykę…');const seeds=await window.PiterfyLibrary.rankSongs(await window.PiterfyLibrary.candidates());const state=await prepare(mode,seeds.slice(0,6));render();if(state.blocks.length)await playBlock(state,state.blocks.at(-1));else wantStart=state.id;});
    button(actions,'Zmień klimat',async()=>{
      const state=states[mode],song=current(),blocks=state?.blocks||[];
      const index=blocks.findIndex(b=>b.tracks.some(t=>t.id===song?.id));
      if(blocks[index+1])await playBlock(state,blocks[index+1]);
      else{states[mode]=await request({action:'next',mode});render();status('Przygotowuję inny klimat. Gotowy blok pojawi się poniżej.');}
    });
    if(mode==='dj')button(actions,'Głos DJ-a',async()=>{const state=states[mode];states[mode]=await request({action:'settings',mode,voice:!state?.voice});if(!states[mode].voice)await skipIntro?.();render();}).dataset.djVoice='true';
    button(actions,'Pomiń zapowiedź',()=>skipIntro?.());
    const track=current();
    if(track&&(states[mode]?.blocks||[]).some(b=>b.tracks.some(t=>t.id===track.id))){
      const feedback=document.createElement('div');feedback.className='pf-library-actions';dialog.append(feedback);
      for(const [label,vote] of [['Więcej takich',1],['Mniej takich',-1]])button(feedback,label,async()=>{
        const song=current();if(!song)throw Error('Wybierz utwór.');
        const r=await fetch('/piterfy-api/connect/library',{method:'POST',headers:{'Content-Type':'application/json','X-ND-Authorization':'Bearer '+localStorage.getItem('token')},body:JSON.stringify({action:'feedback',id:song.id,artist:song.artist||'',vote,event:crypto.randomUUID()})});
        if(!r.ok)throw Error('Nie udało się zapisać reakcji.');status('Zapisano reakcję na: '+song.title);
      });
    }
    button(actions,'Zakończ sesję',async()=>{wantStart='';if(skipIntro)await skipIntro();else stopVoice();states[mode]=await request({action:'stop',mode});render();});
    const info=document.createElement('p');info.setAttribute('role','status');dialog.append(info);const rows=document.createElement('div');rows.className='pf-dj-blocks';dialog.append(rows);dialog.showModal();
    try{states[mode]=await request({action:'get',mode});slider.value=states[mode].exploration??window.PiterfyLibrary.exploration();render();}catch(e){status(e.message);}
  }
  let lastTrack='';
  async function poll(){
    const identity=localStorage.getItem('username')||'';
    if(identity!==account){stopVoice();states={};wantStart='';account=identity;}
    if(!identity||busy)return;busy=true;
    try{states=await request({action:'list'});if(pendingPlayback&&!states.dj?.voice)await skipIntro?.();render();}
    catch(_){}finally{busy=false;}
  }
  setInterval(poll,8000);
  setInterval(()=>{
    const song=current();if(song?.remote){stopVoice();return;}
    if(pendingPlayback){if(element()&&!element().paused)stopVoice();return;}
    for(const state of Object.values(states))if(state.active&&state.id===wantStart&&state.blocks?.length){wantStart='';playBlock(state,state.blocks[0]).catch(()=>{});return;}
    if(!song)return;
    if(narrating&&element()&&!element().paused)stopVoice();
    for(const state of Object.values(states))if(state.active){
      const blocks=state.blocks||[], index=blocks.findIndex(b=>b.tracks.some(t=>t.id===song.id));
      if(index<0)continue;
      const block=blocks[index];
      const last=block.tracks.at(-1)?.id===song.id;
      if(last&&element()?.ended&&blocks[index+1])playBlock(state,blocks[index+1]).catch(()=>{});
      if(song.playing&&!narrating&&index>=blocks.length-2&&Date.now()-lastRefill>90000&&!['queued','downloading'].includes(state.status)){
        lastRefill=Date.now();request({action:'refill',mode:state.mode}).then(s=>{states[state.mode]=s;}).catch(()=>{});}
    }lastTrack=song.id;
  },1000);
  window.addEventListener('pagehide',stopVoice);
  window.PiterfyDJ={open,prepare,isNarrating:()=>narrating||!!pendingPlayback,pendingPlayback:()=>pendingPlayback,cancelNarration:stopVoice};
  setInterval(()=>{const header=document.querySelector('.pf-discovery-launch')?.parentElement;if(header&&!header.querySelector('[data-dj-launch]'))button(header,'DJ',()=>open('dj')).dataset.djLaunch='true';},1500);
})();
