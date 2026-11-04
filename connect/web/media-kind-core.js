(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.PiterfyMediaKinds=api;})(typeof window!=='undefined'?window:globalThis,()=>{
  function isPodcast(song){
    if(!song)return false;
    if(String(song.type||'').toLowerCase()==='podcast')return true;
    if(/(^|[;,/])\s*(podcasts?|podcasty)\s*($|[;,/])/i.test(song.genre||''))return true;
    return /(^|\/)(podcasts?|podcasty)(\/|$)/i.test(String(song.path||'').replace(/\\/g,'/'));
  }
  return {isPodcast};
});
