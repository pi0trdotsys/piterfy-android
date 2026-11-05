const {test}=require('node:test');const assert=require('node:assert/strict');const core=require('./offline-core.js');
test('cached metadata contains no server credentials, stream URLs or library paths',()=>{
 const s=core.safeSong({id:'song',title:'Music',artist:'Artist',duration:120,size:4000,path:'Music/file',token:'secret',musicSrc:'https://example/stream?secret=1'});
 assert.deepEqual(Object.keys(s),['id','title','artist','album','duration','size']);assert.equal(core.safeSong({id:'gone',missing:true}),null);
});
test('downloads stop before storage runs out and respect detected metered/save-data connections',()=>{
 assert.equal(core.enoughSpace({quota:100e6,usage:99e6},1000),false);assert.equal(core.enoughSpace({quota:1e9,usage:1e6},20e6),true);
 assert.equal(core.networkAllowed({saveData:true}),false);assert.equal(core.networkAllowed({type:'cellular'}),false);assert.equal(core.networkAllowed(undefined),true);
});
test('only complete successful audio responses may become downloaded songs',()=>{
 assert.equal(core.isAudio(new Response('audio',{headers:{'content-type':'audio/flac'}})),true);
 assert.equal(core.isAudio(new Response('{}',{headers:{'content-type':'application/json'}})),false);
 assert.equal(core.isAudio(new Response('part',{status:206,headers:{'content-type':'audio/mpeg'}})),false);
 assert.equal(core.isAudio(new Response('denied',{status:403,headers:{'content-type':'audio/mpeg'}})),false);
});
