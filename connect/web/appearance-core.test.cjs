const {test}=require('node:test');
const assert=require('node:assert/strict');
const {appearance:a}=require('./library.js');
const id=(y,m,d)=>a.active(y,m,d)?.occasion.id;

test('easter is computed for many years',()=>{
  const known={2024:[3,31],2025:[4,20],2026:[4,5],2027:[3,28],2028:[4,16],2029:[4,1],2030:[4,21],2038:[4,25]};
  for(const [year,date] of Object.entries(known))assert.deepEqual(a.easter(Number(year)),date,'Wielkanoc '+year);
});

test('epoch days match known dates',()=>{
  assert.equal(a.epochDay(1970,1,1),0);
  assert.equal(a.epochDay(2000,3,1),11017);
  assert.equal(a.epochDay(2024,3,1)-a.epochDay(2024,2,29),1);
  assert.equal(a.epochDay(2027,1,1)-a.epochDay(2026,1,1),365);
});

test('the window is two days before and after',()=>{
  assert.equal(id(2026,12,22),'christmas');assert.equal(id(2026,12,24),'christmas');assert.equal(id(2026,12,26),'christmas');
  assert.equal(id(2026,12,27),undefined);assert.equal(id(2026,12,21),undefined);
});

test('new year crosses the year boundary',()=>{
  for(const [y,m,d] of [[2026,12,29],[2026,12,31],[2027,1,1],[2027,1,2]])assert.equal(id(y,m,d),'newyear');
  assert.equal(id(2027,1,3),undefined);assert.equal(id(2026,12,28),undefined);
});

test('easter follows the moving holiday',()=>{
  assert.equal(id(2026,4,3),'easter');assert.equal(id(2026,4,7),'easter');
  assert.equal(id(2026,4,2),undefined);assert.equal(id(2026,4,8),undefined);
  assert.equal(id(2027,3,26),'easter');assert.equal(id(2027,3,30),'easter');
});

test('other holidays and ordinary days',()=>{
  assert.equal(id(2027,2,12),'valentine');assert.equal(id(2027,2,16),'valentine');assert.equal(id(2027,2,17),undefined);
  assert.equal(id(2026,10,29),'halloween');assert.equal(id(2026,11,2),'halloween');assert.equal(id(2026,11,3),undefined);
  assert.equal(id(2026,11,9),'independence');assert.equal(id(2026,11,13),'independence');assert.equal(id(2026,11,16),undefined);
  assert.equal(id(2026,6,19),'music');assert.equal(id(2026,6,23),'music');
  assert.equal(id(2026,12,4),'mikolajki');assert.equal(id(2026,12,8),'mikolajki');
  assert.equal(id(2026,10,10),'birthday');assert.equal(id(2026,10,14),'birthday');
  assert.equal(id(2026,8,20),undefined);assert.equal(id(2027,5,5),undefined);
});

test('daysFromEvent says how far from the holiday',()=>{
  assert.equal(a.active(2026,12,22).daysFromEvent,-2);assert.equal(a.active(2026,12,24).daysFromEvent,0);assert.equal(a.active(2026,12,26).daysFromEvent,2);
});

test('overlapping windows pick the nearer holiday, ties the earlier one',()=>{
  const list=[{id:'a',rule:['fixed',5,10]},{id:'b',rule:['fixed',5,14]}];
  assert.equal(a.active(2026,5,11,list,2).occasion.id,'a');
  assert.equal(a.active(2026,5,13,list,2).occasion.id,'b');
  assert.equal(a.active(2026,5,12,list,2).occasion.id,'a');
});

test('the next occasion is the nearest window start',()=>{
  const n=a.next(2026,11,16);assert.equal(n.occasion.id,'mikolajki');assert.equal(n.inDays,18);
  assert.ok(a.next(2026,12,30));
});

test('an occasion overrides the choice only while automatic mode is on',()=>{
  const prefs={auto:true,theme:'ocean',icon:'vinyl'};
  const eff=a.effective(prefs,2026,10,31);
  assert.equal(eff.theme,'halloween');assert.equal(eff.icon,'halloween');assert.equal(eff.occasion.id,'halloween');
  const off=a.effective({...prefs,auto:false},2026,10,31);
  assert.equal(off.theme,'ocean');assert.equal(off.icon,'vinyl');assert.equal(off.occasion,null);
  const plain=a.effective(prefs,2026,8,20);assert.equal(plain.theme,'ocean');assert.equal(plain.icon,'vinyl');
});

test('unknown ids fall back to classic and light themes are not offered on the web',()=>{
  assert.equal(a.effective({auto:false,theme:'nope',icon:'nope'},2026,8,20).theme,'classic');
  assert.equal(a.effective({auto:false,theme:'nope',icon:'nope'},2026,8,20).icon,'classic');
  assert.equal(a.theme('papier'),null);
  assert.ok(a.data.themes.length>=10);
  assert.equal(a.effective({auto:true},2026,4,5).theme,'las');   // Wielkanoc: jasny motyw ma na webie ciemny odpowiednik
  assert.ok(a.icon('halloween').svg.startsWith('<svg'));
});
