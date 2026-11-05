const test = require('node:test');
const assert = require('node:assert/strict');
const { snapshot, formatTime } = require('./cast-receiver.js');

test('metadata stays visible through pause and temporary buffering, then clears on idle', () => {
  const playing = snapshot(null, { state: 'PLAYING', media: { metadata: { title: 'Song', artist: 'Artist' } }, time: 25, duration: 90, seekable: true });
  const paused = snapshot(playing, { state: 'PAUSED', time: 25, duration: 90, seekable: true });
  assert.equal(paused.title, 'Song');
  const buffering = snapshot(paused, { state: 'BUFFERING', time: 0, duration: NaN, seekable: false });
  assert.equal(buffering.artist, 'Artist');
  assert.equal(buffering.position, 25);
  assert.equal(buffering.duration, 90);
  assert.equal(buffering.seekable, true);
  assert.equal(snapshot(paused, { state: 'IDLE' }), null);
});

test('a new track replaces its predecessor and uses only receiver-accessible artwork', () => {
  const changed = snapshot({ title: 'Old', cover: 'old' }, { state: 'PLAYING', media: { metadata: {
    title: 'New', images: [{ url: 'content://local/art' }, { url: 'https://example.org/art.jpg' }],
  } }, time: 100, duration: 80, seekable: true });
  assert.equal(changed.title, 'New');
  assert.equal(changed.cover, 'https://example.org/art.jpg');
  assert.equal(changed.position, 80);
  assert.equal(changed.seekable, true);
});

test('live radio has no fake duration or seek controls', () => {
  const live = snapshot(null, { media: { streamType: 'LIVE', metadata: { title: 'Radio' } },
    state: 'PLAYING', duration: Infinity, time: 5, seekable: true });
  assert.equal(live.live, true);
  assert.equal(live.duration, 0);
  assert.equal(live.seekable, false);
  assert.equal(formatTime(3665), '1:01:05');
});
