const { test } = require('node:test');
const assert = require('node:assert/strict');
const core = require('./crossfade-core.js');
const track = (id, extra = {}) => ({uuid:id, trackId:id, duration:60, musicSrc:'https://music.test/rest/stream?id=' + id, song:{title:id,duration:60}, ...extra});
class AudioMock extends EventTarget {
  constructor() { super(); this.currentTime=40; this.duration=60; this.readyState=4; this.paused=false; this.ended=false; this.seeking=false; this.muted=false; this.playbackRate=1; this.src='a'; this._volume=.64; this.buffered={length:1,start:()=>0,end:()=>60}; }
  get volume() { return this._volume; }
  set volume(value) { this._volume=value; }
  play() { this.paused=false; return Promise.resolve(); }
  pause() { this.paused=true; }
  load() {}
}
global.HTMLMediaElement = AudioMock;
function fixture() {
  const main=new AudioMock(), incoming=new AudioMock(); incoming.currentTime=0; incoming.paused=true;
  let clock=0, disposed=false, envelopeGain=1, deckGain=0, chosen='', status='';
  const a=track('a'), b=track('b'), c=track('c');
  const state={allowed:true,account:'user',mode:'order',queue:[a,b,c],current:a};
  const config={enabled:true,seconds:5};
  const engine=new core.Engine({audio:()=>main,state:()=>state,settings:()=>config,now:()=>clock,
    deck:()=>({audio:incoming,gain:(g,v)=>{deckGain=g*v;},dispose:()=>{disposed=true;incoming.pause();}}),
    resolve:async target=>target.musicSrc,
    envelope:()=>({set:g=>{envelopeGain=g;},restore:()=>{envelopeGain=1;}}),
    select:id=>{chosen=id;main.src='b';main.currentTime=0;main.paused=true;},
    status:s=>{status=s;}
  });
  return {main,incoming,state,config,engine, advance:ms=>{clock+=ms;},
    get disposed(){return disposed;},get envelopeGain(){return envelopeGain;},get deckGain(){return deckGain;},get chosen(){return chosen;},get status(){return status;}};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
async function overlap(f) {f.engine.tick();await flush();f.main.currentTime=55;f.engine.tick();await flush();assert.equal(f.engine.job.phase,'overlap');}
test('settings clamp to 1–10 seconds',()=>{assert.equal(core.seconds(0),1);assert.equal(core.seconds(99),10);assert.equal(core.seconds('oops'),5);});
test('supports current Navidrome transcode and legacy same-origin streams only',()=>{
  for(const path of ['/rest/getTranscodeStream','/rest/stream','/rest/stream.view']) assert.equal(core.streamURL(path+'?id=a','https://music.test/app/'),'https://music.test'+path+'?id=a');
  for(const raw of ['https://other.test/rest/stream','javascript:alert(1)','/app/','',undefined]) assert.throws(()=>core.streamURL(raw,'https://music.test/app/'));
});
test('equal-power fade endpoints and midpoint',()=>{assert.deepEqual(core.gains(0),[1,0]);const g=core.gains(.5);assert.ok(Math.abs(g[0]**2+g[1]**2-1)<1e-12);assert.ok(core.gains(1)[0]<1e-12);assert.equal(core.gains(1)[1],1);});
test('queue boundaries, repeat and shuffle do not mutate queue',()=>{
  const a=track('a'),b=track('b'),s={queue:[a,b],current:b,mode:'order'};
  assert.equal(core.next(s),null);s.mode='orderLoop';assert.equal(core.next(s),a);
  s.mode='singleLoop';assert.equal(core.next(s),null);s.mode='shufflePlay';assert.equal(core.next(s,()=>.99),a);assert.deepEqual(s.queue,[a,b]);
});
test('radio and podcasts are excluded at either side',()=>{
  for(const special of [track('b',{isRadio:true}),track('b',{song:{duration:60,genre:'Podcast'}}),track('b',{song:{duration:60,path:'Podcasty/Show/episode.mp3'}})]) {
    assert.equal(core.next({queue:[track('a'),special],current:track('a')}),null);
    assert.equal(core.next({queue:[special,track('a')],current:special}),null);
  }
});
test('crossfade overlaps then hands off at the incoming position without restarting',async()=>{
  const f=fixture();await overlap(f);f.incoming.currentTime=2.5;f.main.currentTime=57.5;f.engine.tick();
  assert.ok(Math.abs(f.envelopeGain-Math.SQRT1_2)<1e-6);assert.ok(Math.abs(f.deckGain-.64*Math.SQRT1_2)<1e-6);assert.equal(f.main.volume,.64);
  f.incoming.currentTime=5;f.main.currentTime=60;f.engine.tick();assert.equal(f.chosen,'b');assert.equal(f.engine.job.phase,'handoff');
  f.state.current=f.state.queue[1];f.engine.tick();assert.equal(f.main.currentTime,5);await flush();f.engine.tick();
  assert.equal(f.engine.job,null);assert.equal(f.disposed,true);assert.equal(f.envelopeGain,1);assert.equal(f.main.paused,false);
});
test('pause during the overlap immediately removes the auxiliary audio',async()=>{const f=fixture();await overlap(f);f.main.paused=true;f.engine.tick();assert.equal(f.engine.job,null);assert.equal(f.incoming.paused,true);assert.equal(f.chosen,'');assert.equal(f.envelopeGain,1);});
test('user cancellation does not restart a transition on the same track',async()=>{const f=fixture();await overlap(f);f.engine.cancel();f.engine.tick();assert.equal(f.engine.job,null);});
test('slow preload leaves native audio and its volume untouched',async()=>{const f=fixture();f.incoming.buffered={length:0};f.engine.tick();await flush();f.main.currentTime=59;f.engine.tick();assert.equal(f.engine.job.phase,'ready');assert.equal(f.chosen,'');assert.equal(f.envelopeGain,1);assert.equal(f.incoming.paused,true);});
test('buffer underrun restores outgoing music',async()=>{const f=fixture();await overlap(f);f.incoming.readyState=2;f.engine.tick();assert.equal(f.disposed,true);assert.equal(f.envelopeGain,1);assert.equal(f.main.paused,false);});
test('queue edits, account change and Connect transfer cancel pending transitions',async()=>{
  for(const mutate of [f=>{f.state.queue=[f.state.queue[0]];},f=>{f.state.account='other';},f=>{f.state.allowed=false;},f=>{f.state.mode='shufflePlay';},f=>{f.config.enabled=false;}]) {
    const f=fixture();await overlap(f);mutate(f);f.engine.tick();assert.equal(f.engine.job,null);assert.equal(f.incoming.paused,true);assert.equal(f.envelopeGain,1);
  }
});
test('stalled native handoff has a deadline and leaves no ghost audio',async()=>{const f=fixture();await overlap(f);f.engine.commit();f.main.readyState=0;f.advance(8001);f.engine.tick();assert.equal(f.engine.job,null);assert.equal(f.disposed,true);assert.equal(f.envelopeGain,1);});
test('rejected auxiliary play keeps ordinary playback',async()=>{const f=fixture();f.incoming.play=()=>Promise.reject(Error('NotAllowedError'));f.engine.tick();await flush();f.main.currentTime=55;f.engine.tick();await flush();assert.equal(f.engine.job,null);assert.equal(f.main.paused,false);assert.equal(f.envelopeGain,1);});
test('cancelled URL lookup cannot load an orphan deck',async()=>{const f=fixture();let finish;f.engine.o.resolve=()=>new Promise(resolve=>{finish=resolve;});f.engine.tick();f.engine.cancel();finish('late');await flush();assert.notEqual(f.incoming.src,'late');assert.equal(f.disposed,true);});
test('volume envelope keeps user volume stable and restores later volume changes',()=>{
  const audio=new AudioMock(),volume=core.envelope(audio);volume.set(.5);assert.equal(audio.volume,.64);assert.equal(audio._volume,.32);
  audio.volume=.4;assert.equal(audio.volume,.4);assert.equal(audio._volume,.2);volume.restore();assert.equal(audio.volume,.4);assert.equal(Object.hasOwn(audio,'volume'),false);
});
