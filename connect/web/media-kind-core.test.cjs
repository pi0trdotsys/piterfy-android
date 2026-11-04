const {test}=require('node:test'),assert=require('node:assert/strict');
const {isPodcast}=require('./media-kind-core.js');
test('episodes with no genre are classified using their actual folder',()=>{
  assert.equal(isPodcast({genre:'',type:'music',path:'Podcasty/Raport/episode.mp3'}),true);
  assert.equal(isPodcast({path:'C:\\Library\\Podcasts\\Show\\episode.m4a'}),true);
  assert.equal(isPodcast({type:'podcast'}),true);
  assert.equal(isPodcast({genre:'Spoken word; Podcast'}),true);
});
test('song titles and shared artist names never make music a podcast',()=>{
  assert.equal(isPodcast({title:'Podcast',artist:'Raport',path:'Rock/Podcast Rock.mp3',genre:'Rock'}),false);
  assert.equal(isPodcast({genre:'Podcast Rock'}),false);
  assert.equal(isPodcast(null),false);
});
