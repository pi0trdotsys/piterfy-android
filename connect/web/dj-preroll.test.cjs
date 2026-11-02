const {test}=require('node:test');
const assert=require('node:assert/strict');
const {beforeMusic,readyBlock,nextBlock,incomingId}=require('./dj.js');

test('ready reserve starts despite unfinished or failed background downloads',()=>{
  const state={buffered:true,active:true,startBlock:'next',currentBlock:'playing',progress:{complete:false,failed:2},buffer:{alternatives:['alt']},blocks:[{id:'playing',ready:true},{id:'next',ready:true},{id:'alt',ready:true}]};
  assert.equal(readyBlock(state).id,'next');assert.equal(nextBlock(state,true).id,'alt');
  state.active=false;assert.equal(readyBlock(state),null);
});

test('incoming song is identified before Navidrome publishes its play event',()=>{
  assert.equal(incomingId('/rest/getTranscodeStream?mediaId=next-song&u=private&t=private'),'next-song');
  assert.equal(incomingId('/rest/stream?id=other-song'),'other-song');
  assert.equal(incomingId('blob:example'),'');
});

test('autoplay requires the whole prepared block and no failed files',()=>{
  const state={active:true,progress:{complete:false,failed:0},blocks:[{id:'preview',ready:false}]};
  assert.equal(readyBlock(state),null);
  state.blocks.push({id:'ready',ready:true});assert.equal(readyBlock(state),null);
  state.progress.complete=true;assert.equal(readyBlock(state).id,'ready');
  state.progress.failed=1;assert.equal(readyBlock(state),null);
  state.progress.failed=0;state.active=false;assert.equal(readyBlock(state),null);
});
test('music waits for the entire spoken introduction',async()=>{
  const events=[];let finish;
  const pending=beforeMusic(()=>{events.push('voice');return new Promise(resolve=>{finish=resolve;});},()=>events.push('music'),()=>true);
  await Promise.resolve();assert.deepEqual(events,['voice']);
  finish();await pending;assert.deepEqual(events,['voice','music']);
});
test('a transfer or cancellation prevents a late music start',async()=>{
  let finish,valid=true,played=false;
  const pending=beforeMusic(()=>new Promise(resolve=>{finish=resolve;}),()=>{played=true;},()=>valid);
  valid=false;finish();await pending;assert.equal(played,false);
});
test('unavailable speech falls back to music exactly once',async()=>{
  let played=0;
  await beforeMusic(()=>Promise.reject(Error('unavailable')),()=>{played++;},()=>true);
  assert.equal(played,1);
});
