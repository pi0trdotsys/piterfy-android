const {test}=require('node:test');
const assert=require('node:assert/strict');
const {protectedQueue}=require('./library.js');
test('playlist occurrence ID is never used to fetch a pinned library file',()=>{
  const rows=[{uuid:'current',trackId:'old',song:{id:'1'}},{uuid:'pin',trackId:'actual-file',song:{id:'2',title:'Keep me'}}];
  const result=protectedQueue([{id:'new',title:'New'}],rows,'current',['actual-file']);
  assert.deepEqual(result.map(s=>s.id),['new','actual-file']);
  assert.equal(result[1].title,'Keep me');
});
test('only upcoming music is protected and already included tracks are not duplicated',()=>{
  const q=[{uuid:'past',trackId:'past',song:{}},{uuid:'now',song:{}},{uuid:'one',trackId:'keep',song:{}},{uuid:'dup',trackId:'keep',song:{}},{uuid:'pod',trackId:'pod',song:{genre:'Podcast'}}];
  assert.deepEqual(protectedQueue([{id:'new'}],q,'now',['past','keep','pod']).map(s=>s.id),['new','keep']);
  assert.deepEqual(protectedQueue([{id:'new'},{id:'keep'}],q,'now',['keep']).map(s=>s.id),['new','keep']);
});
