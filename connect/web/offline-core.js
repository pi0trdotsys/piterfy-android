(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.PiterfyOfflineCore=api;})(typeof window!=='undefined'?window:globalThis,()=>{
  function safeSong(s){
    if(!s||!s.id||s.missing)return null;
    return {id:String(s.id),title:String(s.title||'Bez tytułu'),artist:String(s.artist||''),album:String(s.album||''),duration:Number(s.duration)||0,size:Number(s.size)||0};
  }
  function enoughSpace(estimate,size){return !estimate?.quota||estimate.quota-(estimate.usage||0)>Math.max(50*1024*1024,(size||0)*1.2);}
  function networkAllowed(connection){return !connection?.saveData&&connection?.type!=='cellular';}
  function isAudio(response){const type=response.headers.get('content-type')||'';return response.ok&&response.status!==206&&(type.startsWith('audio/')||type.startsWith('application/octet-stream'));}
  return {safeSong,enoughSpace,networkAllowed,isAudio};
});
