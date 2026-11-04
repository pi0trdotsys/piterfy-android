  // piterfy-music-catalog-start
  const musicTracks={data:null,at:0,loading:false,query:'',visible:100};
  const libraryCatalog={owner:'',at:0,songs:[],loading:null};
  function activeSongState(){return root.dataset.pfRoute==='utwory'?musicTracks:liked;}
  async function allLibrarySongs(){
    const owner=localStorage.getItem('username')||'';
    if(!owner)throw Error('Zaloguj się w Piterfy.');
    if(libraryCatalog.owner!==owner){libraryCatalog.owner=owner;libraryCatalog.at=0;libraryCatalog.songs=[];libraryCatalog.loading=null;}
    if(Date.now()-libraryCatalog.at<120000)return libraryCatalog.songs;
    if(libraryCatalog.loading)return libraryCatalog.loading;
    const pending=(async()=>{
      const songs=[],seen=new Set();
      for(let offset=0;;offset+=500){
        const response=await fetch('/api/song?_start='+offset+'&_end='+(offset+500)+'&_sort=title&_order=ASC',{
          headers:{'X-ND-Authorization':'Bearer '+localStorage.getItem('token')},signal:AbortSignal.timeout(15000)});
        if(!response.ok)throw Error('Nie udało się wczytać utworów.');
        const batch=await response.json();if(!Array.isArray(batch))throw Error('Nieprawidłowa lista utworów.');
        let added=0;
        for(const song of batch)if(song.id&&!seen.has(song.id)){seen.add(song.id);songs.push(song);added++;}
        if(owner!==(localStorage.getItem('username')||''))throw Error('Zmieniono konto.');
        if(batch.length<500)break;
        if(!added)throw Error('Nie udało się wczytać kolejnej strony utworów.');
      }
      if(libraryCatalog.owner===owner){libraryCatalog.songs=songs;libraryCatalog.at=Date.now();}
      return songs;
    })();
    libraryCatalog.loading=pending;
    try{return await pending;}finally{if(libraryCatalog.loading===pending)libraryCatalog.loading=null;}
  }
  function ensurePodcasts(){
    if(podcasts.loading)return podcasts.loading;
    if(!localStorage.getItem('username')||Date.now()-podcasts.at<120000)return Promise.resolve();
    const owner=localStorage.getItem('username');
    podcasts.loading=allLibrarySongs().then(songs=>{
      if(owner!==localStorage.getItem('username'))return;
      const shows=new Map();
      for(const song of songs){
        if(song.missing||!window.PiterfyMediaKinds.isPodcast(song)||!song.albumId)continue;
        if(!shows.has(song.albumId))shows.set(song.albumId,{id:song.albumId,name:song.album||'Podcast',artist:song.artist||'',artistId:song.artistId,coverArt:song.coverArt||'al-'+song.albumId,songCount:0,genre:'Podcast'});
        shows.get(song.albumId).songCount++;
      }
      podcasts.list=[...shows.values()];podcasts.albums=new Set(shows.keys());
      podcasts.artists=new Set(podcasts.list.map(s=>s.artistId).filter(Boolean));podcasts.ready=true;podcasts.at=Date.now();
    }).catch(()=>{podcasts.at=Date.now()-110000;}).finally(()=>{podcasts.loading=null;update();});
    return podcasts.loading;
  }
  async function loadMusicTracks(){
    const songs=(await allLibrarySongs()).filter(song=>!song.missing&&!isPodcastSong(song));
    return {songs,albums:[],artists:[]};
  }
  // piterfy-music-catalog-end
