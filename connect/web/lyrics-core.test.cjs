const assert = require('node:assert/strict');
const core = require('./lyrics-core.js');
const parsed = core.parse('[offset:500]\n[00:12.5][00:42.050]  Sample line  \n[00:05]Earlier\n[ar:Test]');
assert.deepEqual(parsed.lines.map(x => x.start), [5000, 12500, 42050]);
assert.equal(parsed.lines[1].value, 'Sample line');
assert.equal(core.active(parsed.lines, 0, parsed.offset), -1);
assert.equal(core.active(parsed.lines, 4500, parsed.offset), 0);
assert.equal(core.active(parsed.lines, 12000, parsed.offset), 1);
assert.equal(core.parse('No timestamps').synced, false);
const gaps = core.parse('[00:02]First\n[00:04]\n[00:08]Last');
assert.equal(gaps.lines[1].value, '');
assert.equal(core.active(gaps.lines, 6000), 1);
assert.equal(core.active(gaps.lines, 3000), 0);
assert.equal(core.structured([{synced:true,line:[{value:'Missing time'}]}]), null);
assert.equal(core.structured([{synced:false,line:[{value:'Plain'}]}]).synced, false);
assert.equal(core.structured([{synced:true,line:[{value:'Missing time'}]}, {synced:false,line:[{value:'Fallback'}]}]).lines[0].value, 'Fallback');
// karaoke: postęp linii i styl wg odległości
const k = core.parse('[00:02]First\n[00:06]Second\n[00:40]Third');
assert.equal(core.progress(k.lines, 0, 1000), 0);
assert.equal(core.progress(k.lines, 0, 2000), 0);
assert.equal(core.progress(k.lines, 0, 4000), 0.5);
assert.equal(core.progress(k.lines, 0, 6000), 1);
assert.equal(core.progress(k.lines, 0, 9000), 1);
assert.equal(core.progress(k.lines, 0, 3000, 1000), 0.5);                  // dodatni offset pokazuje linię wcześniej
assert.equal(core.progress(k.lines, 1, 10500), 0.5);                       // 6000→40000, ale długą przerwę skracamy do 9 s
assert.equal(core.progress(k.lines, 1, 16000), 1);
assert.equal(core.progress(k.lines, 2, 42000), 0.5);                       // ostatnia linia trwa 4 s
assert.equal(core.progress(k.lines, 2, 60000), 1);
assert.equal(core.progress(k.lines, 7, 1000), 0);                          // brak takiej linii
assert.equal(core.progress([{ start: 1000 }, { start: 1000 }], 0, 1000), 1); // zerowa długość = od razu pełna
assert.deepEqual(core.style(0), { scale: 1, alpha: 1, blur: 0 });
assert.deepEqual(core.style(-1), core.style(1));
assert.equal(core.style(1).scale, 0.86);
assert.deepEqual(core.style(99), core.style(5));
for (let d = 1; d < 6; d++) {
  assert.ok(core.style(d).alpha < core.style(d - 1).alpha);
  assert.ok(core.style(d).blur > core.style(d - 1).blur);
  assert.ok(core.style(d).blur <= 4 && core.style(d).alpha > 0.2);
}
console.log('Lyrics timing and karaoke: assertions passed');
