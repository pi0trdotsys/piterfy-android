(() => {
  'use strict';
  const api=window.PiterfyOffline,$=id=>document.getElementById(id),audio=document.querySelector('audio');
  let songs=[],queue=[],current=-1,objectURL='',loading=false,refresh=0,selected='',playingToken=0;
  const fmt=seconds=>Math.floor((seconds||0)/60)+':'+String(Math.floor((seconds||0)%60)).padStart(2,'0');
  function render(){
    $('count').textContent=songs.length+' utworów · '+(songs.reduce((sum,s)=>sum+(s.bytes||0),0)/1024/1024).toFixed(0)+' MB';
    $('play-all').disabled=$('shuffle').disabled=!songs.length;
    const q=$('search').value.trim().toLocaleLowerCase('pl');const shown=songs.filter(s=>(s.artist+' '+s.title+' '+s.album).toLocaleLowerCase('pl').includes(q));
    const box=$('songs');box.replaceChildren();
    if(!shown.length){const p=document.createElement('p');p.className='muted empty';p.textContent=q?'Brak zapisanych utworów pasujących do wyszukiwania.':'Brak zapisanych utworów. Połącz się z siecią i zaczekaj na zakończenie pobierania ulubionych.';box.append(p);return;}
    for(const [index,song] of shown.entries()){
      const button=document.createElement('button');button.className='song';button.setAttribute('aria-current',String(song.id===selected));button.setAttribute('aria-label','Odtwórz offline: '+song.title);
      const number=document.createElement('span');number.className='number';number.textContent=song.id===selected?'♫':String(index+1);
      const text=document.createElement('span');text.textContent=song.title;const artist=document.createElement('span');artist.className='artist';artist.textContent=song.artist;text.append(artist);
      const time=document.createElement('span');time.className='muted';time.textContent=fmt(song.duration);button.append(number,text,time);button.onclick=()=>{queue=shown.slice();play(index);};box.append(button);
    }
  }
  async function update(){if(loading)return;loading=true;try{songs=(await api.songs()).sort((a,b)=>b.savedAt-a.savedAt);render();}catch(e){$('download-status').textContent=e.message;}finally{loading=false;}}
  async function play(index){
    if(index<0||index>=queue.length)return;const token=++playingToken;const song=queue[index];
    try{const blob=await api.blob(song.id);if(token!==playingToken)return;audio.pause();if(objectURL)URL.revokeObjectURL(objectURL);objectURL=URL.createObjectURL(blob);audio.src=objectURL;
      current=index;selected=song.id;$('player').hidden=false;$('now').textContent=song.artist+' — '+song.title+' · Offline';render();
      if('mediaSession'in navigator){navigator.mediaSession.metadata=new MediaMetadata({title:song.title,artist:song.artist,album:song.album});}
      await audio.play();
    }catch(e){$('now').textContent=e.name==='NotAllowedError'?'Naciśnij odtwarzanie, aby posłuchać zapisanego utworu.':'Nie udało się odtworzyć pliku. '+e.message;}
  }
  api.subscribe(state=>{
    $('download-status').textContent=state.message+(state.shellReady?' Ekran startowy jest zapisany offline.':'');$('auto').checked=state.enabled!==false;$('sync').disabled=state.busy||!navigator.onLine;
    $('progress').value=state.total?Math.min(100,(state.done+(state.phase==='downloading'?state.percent/100:0))/state.total*100):0;
    $('progress').setAttribute('aria-valuetext',state.done+'/'+state.total+' utworów');
    $('connection').textContent=navigator.onLine?'Zapis na tym komputerze':'Bez sieci · grasz lokalnie';
    if(state.phase==='signed-out'){++playingToken;audio.pause();audio.removeAttribute('src');if(objectURL)URL.revokeObjectURL(objectURL);objectURL='';songs=[];queue=[];selected='';$('player').hidden=true;render();}
    else if(Date.now()-refresh>2000){refresh=Date.now();update();}
  });
  $('auto').onchange=()=>api.setEnabled($('auto').checked);$('sync').onclick=()=>api.sync();$('search').oninput=render;
  $('play-all').onclick=()=>{queue=songs.slice();play(0);};$('shuffle').onclick=()=>{queue=songs.slice();for(let i=queue.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[queue[i],queue[j]]=[queue[j],queue[i]];}play(0);};
  $('next').onclick=()=>play(current+1);$('previous').onclick=()=>{if(audio.currentTime>3)audio.currentTime=0;else play(current-1);};audio.onended=()=>play(current+1);
  $('persist').onclick=async()=>{$('persist-status').textContent=await api.persist()?'Przeglądarka przyznała trwałą pamięć.':'Przeglądarka nie przyznała trwałej pamięci. Utwory są zapisane, ale mogą zostać usunięte przy braku miejsca.';};
  if('mediaSession'in navigator)for(const [action,handler] of Object.entries({play:()=>audio.play(),pause:()=>audio.pause(),previoustrack:()=>$('previous').click(),nexttrack:()=>$('next').click()}))try{navigator.mediaSession.setActionHandler(action,handler);}catch(_){}
  window.addEventListener('offline',()=>{$('connection').textContent='Bez sieci · grasz lokalnie';$('sync').disabled=true;});
  window.addEventListener('pagehide',()=>{if(objectURL)URL.revokeObjectURL(objectURL);});
  setInterval(update,10000);update();
})();
