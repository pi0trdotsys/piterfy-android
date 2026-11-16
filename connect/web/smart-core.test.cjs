const {test}=require('node:test');
const assert=require('node:assert/strict');
const {smart}=require('./library.js');

const song=(id,artist,extra={})=>({id,artist,title:'t'+id,genre:'Rock',...extra});
// powtarzalny generator liczb, żeby testy były deterministyczne
const lcg=seed=>()=>((seed=(seed*1664525+1013904223)>>>0)/4294967296);

test('seeds are distinct artists, newest first, featured names collapse',()=>{
  const history=[song('1','Daft Punk'),song('2','Daft Punk feat. Pharrell'),song('3','Justice'),song('4','Air'),song('5','Moby'),song('6','Röyksopp')];
  assert.deepEqual(smart.seeds(history,4).map(s=>s.id),['1','3','4','5']);
  assert.deepEqual(smart.seeds([],4),[]);
});

test('merge adds up hits across seeds and keeps the best rank',()=>{
  const a=song('a','A'),b=song('b','B'),c=song('c','C');
  const rows=smart.merge([[a,b],[b,c],[b]]);
  const byId=Object.fromEntries(rows.map(r=>[r.song.id,r]));
  assert.equal(byId.b.hits,3);
  assert.equal(byId.a.hits,1);
  assert.equal(byId.b.best,0);
  assert.equal(rows.length,3);
});

test('never proposes queued, just played, remembered or duplicate songs',()=>{
  const candidates=smart.merge([[song('q','A'),song('p','B'),song('m','C'),song('d1','D',{title:'Same Song'}),song('d2','D',{title:'Same Song (Live)'}),song('ok','E')]]);
  const out=smart.pick({candidates,history:[song('p','B')],queue:['q'],recent:new Set(['m']),count:10,rng:lcg(1)});
  assert.deepEqual(out.map(s=>s.id).sort(),['d1','ok']);
});

test('podcast episodes are never recommended',()=>{
  const candidates=smart.merge([[song('x','Show',{genre:'Podcast'}),song('y','Band')]]);
  assert.deepEqual(smart.pick({candidates,count:5,rng:lcg(2)}).map(s=>s.id),['y']);
});

test('one song per artist in a batch, relaxed only when the pool is tiny',()=>{
  const many=[];for(let i=0;i<30;i++)many.push(song('s'+i,'Same'),song('o'+i,'Other'+i));
  const out=smart.pick({candidates:smart.merge([many]),count:6,rng:lcg(3)});
  assert.equal(out.filter(s=>s.artist==='Same').length,1);
  const tiny=smart.pick({candidates:smart.merge([[song('a','X'),song('b','X'),song('c','X')]]),count:5,rng:lcg(4)});
  assert.equal(tiny.length,2);
});

test('artists that just played are strongly avoided',()=>{
  const history=[song('h1','Recent'),song('h2','Other')];
  const pool=[];for(let i=0;i<20;i++)pool.push(song('r'+i,'Recent'+(i%2?'':'')),song('n'+i,'Fresh'+i));
  let recent=0,total=0;
  for(let seed=1;seed<=40;seed++){
    for(const s of smart.pick({candidates:smart.merge([pool]),history,count:3,maxPerArtist:3,rng:lcg(seed)})){total++;if(s.artist==='Recent')recent++;}
  }
  assert.ok(recent/total<0.15,'recent artist share '+recent/total);
});

test('results vary between runs but follow the weights',()=>{
  const pool=[];for(let i=0;i<60;i++)pool.push(song('s'+i,'A'+i));
  const hot=song('hot','Hot');
  const candidates=[...smart.merge([pool,[hot],[hot],[hot]])];
  const seen=new Set(),counts=new Map();
  for(let seed=1;seed<=200;seed++){
    for(const s of smart.pick({candidates,count:5,rng:lcg(seed)})){seen.add(s.id);counts.set(s.id,(counts.get(s.id)||0)+1);}
  }
  const others=[...counts.entries()].filter(([id])=>id!=='hot').map(([,n])=>n),average=others.reduce((a,b)=>a+b,0)/60;
  assert.ok(seen.size>50,'variety '+seen.size);
  assert.ok(counts.get('hot')>average*1.6,'recommended by three seeds: '+counts.get('hot')+' vs '+average);
});

test('exploration slider shifts between unheard and familiar songs',()=>{
  const heard=[],fresh=[];
  for(let i=0;i<40;i++){heard.push(song('h'+i,'H'+i,{playCount:20}));fresh.push(song('f'+i,'F'+i,{playCount:0}));}
  const share=exploration=>{let f=0,n=0;for(let seed=1;seed<=40;seed++)for(const s of smart.pick({candidates:smart.merge([[...heard,...fresh]]),count:5,exploration,rng:lcg(seed)})){n++;if(s.id[0]==='f')f++;}return f/n;};
  assert.ok(share(100)>share(0)+0.15,share(0)+' vs '+share(100));
});

test('genre does not lock in after a run of the same genre',()=>{
  const history=[1,2,3,4].map(i=>song('g'+i,'G'+i,{genre:'Metal'}));
  const pool=[];for(let i=0;i<30;i++)pool.push(song('m'+i,'M'+i,{genre:'Metal'}),song('p'+i,'P'+i,{genre:'Pop'}));
  let pop=0,n=0;
  for(let seed=1;seed<=40;seed++)for(const s of smart.pick({candidates:smart.merge([pool]),history,count:4,rng:lcg(seed)})){n++;if(s.genre==='Pop')pop++;}
  assert.ok(pop/n>0.55,'pop share '+pop/n);
});

test('memory keeps recent ids only, newest first, bounded',()=>{
  const now=Date.UTC(2026,10,16);
  let memory=smart.remember({old:now-20*864e5,week:now-3*864e5},['a','b'],now);
  assert.deepEqual(Object.keys(memory).sort(),['a','b','week']);
  assert.deepEqual([...smart.recentIds(memory,now,2)].sort(),['a','b']);
  const ids=Array.from({length:700},(_,i)=>'id'+i);
  assert.equal(Object.keys(smart.remember({},ids,now)).length,600);
});

test('starred songs get a real bonus',()=>{
  const starred=song('star','S',{starred:'2026-01-01'});
  const candidates=smart.merge([[...Array.from({length:60},(_,i)=>song('s'+i,'A'+i)),starred]]);
  let hits=0;
  for(let seed=1;seed<=200;seed++)if(smart.pick({candidates,count:5,rng:lcg(seed)}).some(s=>s.id==='star'))hits++;
  assert.ok(hits>200*5/61*1.3,'starred picked '+hits+'/200');
});
