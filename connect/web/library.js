/* Shared discovery and podcast experience for the existing Navidrome player. */
(() => {
  'use strict';
  function queueIndex(queue,current,saved=0){const at=queue.findIndex(t=>t.uuid===current);return at>=0?at:queue.length?Math.max(0,Math.min(queue.length-1,saved||0)):-1;}
  function protectedQueue(songs,queue,current,pinned){
    if(!songs.length)return songs;
    const at=queue.findIndex(t=>t.uuid===current),ids=new Set(songs.map(s=>s.id));
    const pins=queue.slice(at+1).filter(t=>t.song).map(t=>({...t.song,id:t.trackId||t.song.mediaFileId||t.song.id}))
      .filter(s=>{if(podcast(s)||!pinned.includes(s.id)||ids.has(s.id))return false;ids.add(s.id);return true;});
    return [songs[0],...pins,...songs.slice(1)].slice(0,500);
  }
  function podcast(song){const core=typeof module==='object'&&module.exports?require('./media-kind-core.js'):window.PiterfyMediaKinds;return core?core.isPodcast(song):String(song?.type).toLowerCase()==='podcast'||/(^|[;,/])\s*(podcasts?|podcasty)\s*($|[;,/])/i.test(song?.genre||'')||/(^|[/\\])(podcasts?|podcasty)([/\\]|$)/i.test(song?.path||'');}

  // piterfy-appearance-data:begin (wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie)
  const APPEARANCE_DATA = {"window":2,"themes":[{"id":"noir_neon","name":"Noir Neon","dark":true,"accent":"#FF4FA3","accent2":"#00E5FF","bg":"#07070D","surface":"#12121C","text":"#F5F5FA"},{"id":"synthwave","name":"Synthwave","dark":true,"accent":"#FF6EC7","accent2":"#FFB000","bg":"#150A28","surface":"#211040","text":"#FBEFFF"},{"id":"las","name":"Las","dark":true,"accent":"#6FE3A1","accent2":"#E3C26F","bg":"#08150F","surface":"#0F2418","text":"#E8F5EC"},{"id":"ocean","name":"Ocean","dark":true,"accent":"#4FC3F7","accent2":"#26E0C1","bg":"#050E1A","surface":"#0A1D33","text":"#E6F3FF"},{"id":"terminal","name":"Retro Terminal","dark":true,"accent":"#39FF14","accent2":"#00C8FF","bg":"#000000","surface":"#06100A","text":"#B8FFC9"},{"id":"zloto","name":"Złoto","dark":true,"accent":"#E7C25B","accent2":"#B87333","bg":"#0A0803","surface":"#17120A","text":"#F6ECD2"},{"id":"summer","name":"Lato","dark":true,"accent":"#FF7A3D","accent2":"#22C3C3","bg":"#190B1C","surface":"#2A1330","text":"#FFF0E5"},{"id":"winter","name":"Zima","dark":true,"accent":"#A8E0FF","accent2":"#FFFFFF","bg":"#08121C","surface":"#11202F","text":"#EAF6FF"},{"id":"halloween","name":"Halloween","dark":true,"accent":"#FF8A1F","accent2":"#9B5DE5","bg":"#0D0614","surface":"#1A0F24","text":"#FFEFD9"},{"id":"christmas","name":"Boże Narodzenie","dark":true,"accent":"#FF4D4D","accent2":"#3DDC84","bg":"#0A1410","surface":"#13241B","text":"#FFF3F0"},{"id":"valentine","name":"Walentynki","dark":true,"accent":"#FF5C8A","accent2":"#FFB3C6","bg":"#14050B","surface":"#240B14","text":"#FFEEF3"},{"id":"newyear","name":"Sylwester","dark":true,"accent":"#F5C542","accent2":"#C0C8FF","bg":"#05070F","surface":"#0E1424","text":"#F8F5E8"},{"id":"independence","name":"Biało-czerwony","dark":true,"accent":"#E8314F","accent2":"#FFFFFF","bg":"#0E0508","surface":"#1E0C11","text":"#FFF4F5"},{"id":"birthday","name":"Urodziny Piterfy","dark":true,"accent":"#1ED760","accent2":"#FFC107","bg":"#000000","surface":"#121212","text":"#FFFFFF"}],"icons":[{"id":"classic","name":"Klasyk","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"#000000\"/><path d=\"M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0zM40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27zM48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8zM55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53zM62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#1DB954\" fill-rule=\"evenodd\"/></svg>"},{"id":"neon","name":"Neon","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#0B0B18\"/><stop offset=\"1\" stop-color=\"#2A0B4A\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0z\" fill=\"#FF2E93\" opacity=\"0.14\"/><path d=\"M23,54a31,31 0,1 1,62,0a31,31 0,1 1,-62,0z\" fill=\"none\" stroke=\"#FF2E93\" stroke-width=\"3\" opacity=\"0.95\"/><path d=\"M23,54a31,31 0,1 1,62,0a31,31 0,1 1,-62,0z\" fill=\"none\" stroke=\"#FF2E93\" stroke-width=\"7\" opacity=\"0.18\"/><path d=\"M40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27z\" fill=\"#00E5FF\"/><path d=\"M48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8z\" fill=\"#00E5FF\"/><path d=\"M55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53z\" fill=\"#00E5FF\"/><path d=\"M62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#00E5FF\"/></svg>"},{"id":"vinyl","name":"Winyl","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#101010\"/><stop offset=\"1\" stop-color=\"#242424\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M19,54a35,35 0,1 1,70,0a35,35 0,1 1,-70,0z\" fill=\"#0B0B0B\"/><path d=\"M23,54a31,31 0,1 1,62,0a31,31 0,1 1,-62,0z\" fill=\"none\" stroke=\"#2C2C2C\" stroke-width=\"1.2\"/><path d=\"M28,54a26,26 0,1 1,52,0a26,26 0,1 1,-52,0z\" fill=\"none\" stroke=\"#2C2C2C\" stroke-width=\"1.2\"/><path d=\"M33,54a21,21 0,1 1,42,0a21,21 0,1 1,-42,0z\" fill=\"none\" stroke=\"#2C2C2C\" stroke-width=\"1.2\"/><path d=\"M54,22A32,32 0,0 1,84,43\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"2.6\" stroke-linecap=\"round\" opacity=\"0.16\"/><path d=\"M41,54a13,13 0,1 1,26,0a13,13 0,1 1,-26,0z\" fill=\"#1DB954\"/><path d=\"M51,54a3,3 0,1 1,6,0a3,3 0,1 1,-6,0z\" fill=\"#0B0B0B\"/></svg>"},{"id":"valentine","name":"Walentynki","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#FF5C8A\"/><stop offset=\"1\" stop-color=\"#B5123F\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M54,82C28,63 20,48 28,37C35,28 48,31 54,41C60,31 73,28 80,37C88,48 80,63 54,82zM40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27zM48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8zM55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53zM62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#FFFFFF\" fill-rule=\"evenodd\"/><path d=\"M86,19L87.3,22.6L91,24L87.3,25.4L86,29L84.7,25.4L81,24L84.7,22.6z\" fill=\"#FFE0EA\"/><path d=\"M22,76L23.1,78.9L26,80L23.1,81.1L22,84L20.9,81.1L18,80L20.9,78.9z\" fill=\"#FFE0EA\"/></svg>"},{"id":"easter","name":"Wielkanoc","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#FFF1F7\"/><stop offset=\"1\" stop-color=\"#C9E9FF\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M54,22C69,22 81,45 81,62C81,77 69,86 54,86C39,86 27,77 27,62C27,45 39,22 54,22z\" fill=\"#9BE3B4\"/><path d=\"M28,56L37,49L46,56L54,49L62,56L71,49L80,56L80,64L71,57L62,64L54,57L46,64L37,57L28,64z\" fill=\"#FF8FB1\"/><path d=\"M32,72L40,68L48,72L56,68L64,72L72,68L78,71L77,76L72,73L64,77L56,73L48,77L40,73L33,76z\" fill=\"#FFFFFF\" opacity=\"0.9\"/><path d=\"M43.4,38a2.6,2.6 0,1 1,5.2,0a2.6,2.6 0,1 1,-5.2,0z\" fill=\"#FFE066\"/><path d=\"M57.8,42a2.2,2.2 0,1 1,4.4,0a2.2,2.2 0,1 1,-4.4,0z\" fill=\"#FFE066\"/></svg>"},{"id":"summer","name":"Lato","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"108\"><stop offset=\"0\" stop-color=\"#FFB347\"/><stop offset=\"0.55\" stop-color=\"#FF3D77\"/><stop offset=\"1\" stop-color=\"#7B2FF7\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M30,48a24,24 0,1 1,48,0a24,24 0,1 1,-48,0zM40.56,42.27L40.56,53.47A2.24,2.24,0,0,0,45.04,53.47L45.04,42.27A2.24,2.24,0,0,0,40.56,42.27zM48.03,34.8L48.03,57.2A2.24,2.24,0,0,0,52.51,57.2L52.51,34.8A2.24,2.24,0,0,0,48.03,34.8zM55.49,38.53L55.49,51.6A2.24,2.24,0,0,0,59.97,51.6L59.97,38.53A2.24,2.24,0,0,0,55.49,38.53zM62.96,42.27L62.96,47.87A2.24,2.24,0,0,0,67.44,47.87L67.44,42.27A2.24,2.24,0,0,0,62.96,42.27z\" fill=\"#FFE066\" fill-rule=\"evenodd\"/><path d=\"M20,76Q30,69 40,76T60,76T80,76T100,76\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"3.2\" stroke-linecap=\"round\" opacity=\"0.9\"/><path d=\"M20,86Q30,79 40,86T60,86T80,86T100,86\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"3.2\" stroke-linecap=\"round\" opacity=\"0.5\"/></svg>"},{"id":"halloween","name":"Halloween","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#1B0B2E\"/><stop offset=\"1\" stop-color=\"#45106B\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M54,38C44,38 38,46 38,57C38,69 45,77 54,77C63,77 70,69 70,57C70,46 64,38 54,38z\" fill=\"#FF8A1F\"/><path d=\"M44,40C32,41 25,50 26,60C27,70 34,77 44,76C38,72 36,64 36,57C36,49 39,43 44,40z\" fill=\"#F26A00\"/><path d=\"M64,40C76,41 83,50 82,60C81,70 74,77 64,76C70,72 72,64 72,57C72,49 69,43 64,40z\" fill=\"#F26A00\"/><path d=\"M51,38L52,30L57,30L58,38z\" fill=\"#53B94F\"/><path d=\"M54,46L47,53L61,53z\" fill=\"#2A0A3F\"/><path d=\"M40,55L46,51L47,58z\" fill=\"#2A0A3F\"/><path d=\"M68,55L62,51L61,58z\" fill=\"#2A0A3F\"/><path d=\"M39,64L44,68L48,64L54,69L60,64L64,68L69,64L66,72L54,75L42,72z\" fill=\"#2A0A3F\"/><path d=\"M86,21L87.3,24.6L91,26L87.3,27.4L86,31L84.7,27.4L81,26L84.7,24.6z\" fill=\"#FFD166\"/><path d=\"M24,26L25.1,28.9L28,30L25.1,31.1L24,34L22.9,31.1L20,30L22.9,28.9z\" fill=\"#FFD166\"/></svg>"},{"id":"christmas","name":"Boże Narodzenie","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#0E5A32\"/><stop offset=\"1\" stop-color=\"#052A19\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0zM40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27zM48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8zM55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53zM62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#E63946\" fill-rule=\"evenodd\"/><path d=\"M36,46C37,31 53,23 70,28C63,32 60,39 61,46z\" fill=\"#E63946\"/><path d=\"M32,43L76,43A4.5,4.5 0,0 1,76,52L32,52A4.5,4.5 0,0 1,32,43z\" fill=\"#FFFFFF\"/><path d=\"M66.5,27a5.5,5.5 0,1 1,11.0,0a5.5,5.5 0,1 1,-11.0,0z\" fill=\"#FFFFFF\"/><path d=\"M88,70L89.1,72.9L92,74L89.1,75.1L88,78L86.9,75.1L84,74L86.9,72.9z\" fill=\"#FFE066\"/><path d=\"M20,26L21.1,28.9L24,30L21.1,31.1L20,34L18.9,31.1L16,30L18.9,28.9z\" fill=\"#FFE066\"/><path d=\"M22,81L22.8,83.2L25,84L22.8,84.8L22,87L21.2,84.8L19,84L21.2,83.2z\" fill=\"#FFFFFF\"/></svg>"},{"id":"newyear","name":"Sylwester","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#0A0F2C\"/><stop offset=\"1\" stop-color=\"#3A1B6E\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0zM40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27zM48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8zM55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53zM62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#F5C542\" fill-rule=\"evenodd\"/><path d=\"M24,19L25.9,24.1L31,26L25.9,27.9L24,33L22.1,27.9L17,26L22.1,24.1z\" fill=\"#FFFFFF\"/><path d=\"M86,25L87.3,28.6L91,30L87.3,31.4L86,35L84.7,31.4L81,30L84.7,28.6z\" fill=\"#F5C542\"/><path d=\"M82,80L83.6,84.4L88,86L83.6,87.6L82,92L80.4,87.6L76,86L80.4,84.4z\" fill=\"#FFFFFF\"/><path d=\"M17.6,78a2.4,2.4 0,1 1,4.8,0a2.4,2.4 0,1 1,-4.8,0z\" fill=\"#FF6EC7\"/><path d=\"M89.8,58a2.2,2.2 0,1 1,4.4,0a2.2,2.2 0,1 1,-4.4,0z\" fill=\"#00E5FF\"/><path d=\"M28,92a2,2 0,1 1,4,0a2,2 0,1 1,-4,0z\" fill=\"#F5C542\"/><path d=\"M68,20a2,2 0,1 1,4,0a2,2 0,1 1,-4,0z\" fill=\"#FF6EC7\"/></svg>"},{"id":"birthday","name":"Urodziny","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#1DB954\"/><stop offset=\"1\" stop-color=\"#0B5A37\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M26,62a28,28,0,1,1,56,0a28,28,0,1,1,-56,0zM40.56,58.27L40.56,69.47A2.24,2.24,0,0,0,45.04,69.47L45.04,58.27A2.24,2.24,0,0,0,40.56,58.27zM48.03,50.8L48.03,73.2A2.24,2.24,0,0,0,52.51,73.2L52.51,50.8A2.24,2.24,0,0,0,48.03,50.8zM55.49,54.53L55.49,67.6A2.24,2.24,0,0,0,59.97,67.6L59.97,54.53A2.24,2.24,0,0,0,55.49,54.53zM62.96,58.27L62.96,63.87A2.24,2.24,0,0,0,67.44,63.87L67.44,58.27A2.24,2.24,0,0,0,62.96,58.27z\" fill=\"#FFFFFF\" fill-rule=\"evenodd\"/><path d=\"M54,14L69,42L39,42z\" fill=\"#FFC107\"/><path d=\"M46.5,28L61.5,28L65,35L43,35z\" fill=\"#FF4081\"/><path d=\"M50.4,13.5a3.6,3.6 0,1 1,7.2,0a3.6,3.6 0,1 1,-7.2,0z\" fill=\"#FF4081\"/><path d=\"M19.4,24a2.6,2.6 0,1 1,5.2,0a2.6,2.6 0,1 1,-5.2,0z\" fill=\"#FF4081\"/><path d=\"M85.4,30a2.6,2.6 0,1 1,5.2,0a2.6,2.6 0,1 1,-5.2,0z\" fill=\"#FFC107\"/><path d=\"M87.4,82a2.6,2.6 0,1 1,5.2,0a2.6,2.6 0,1 1,-5.2,0z\" fill=\"#FF4081\"/><path d=\"M15.6,80a2.4,2.4 0,1 1,4.8,0a2.4,2.4 0,1 1,-4.8,0z\" fill=\"#FFC107\"/><path d=\"M84,14L85.1,16.9L88,18L85.1,19.1L84,22L82.9,19.1L80,18L82.9,16.9z\" fill=\"#FFFFFF\"/></svg>"},{"id":"independence","name":"Biało-czerwona","svg":"<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 108 108\"><defs><linearGradient id=\"gibg\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"108\" y2=\"108\"><stop offset=\"0\" stop-color=\"#E0173A\"/><stop offset=\"1\" stop-color=\"#8E0C23\"/></linearGradient></defs><rect width=\"108\" height=\"108\" rx=\"24\" fill=\"url(#gibg)\"/><path d=\"M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0zM40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27zM48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8zM55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53zM62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z\" fill=\"#FFFFFF\" fill-rule=\"evenodd\"/><path d=\"M20,26L88,26\" fill=\"none\" stroke=\"#FFFFFF\" stroke-width=\"3\" opacity=\"0\"/><path d=\"M88,23L89.3,26.6L93,28L89.3,29.4L88,33L86.7,29.4L83,28L86.7,26.6z\" fill=\"#FFFFFF\"/><path d=\"M20,80L21.1,82.9L24,84L21.1,85.1L20,88L18.9,85.1L16,84L18.9,82.9z\" fill=\"#FFFFFF\"/></svg>"}],"occasions":[{"id":"newyear","name":"Sylwester i Nowy Rok","rule":["fixed",12,31],"icon":"newyear","theme":"newyear"},{"id":"valentine","name":"Walentynki","rule":["fixed",2,14],"icon":"valentine","theme":"valentine"},{"id":"easter","name":"Wielkanoc","rule":["easter",0],"icon":"easter","theme":"las"},{"id":"music","name":"Dzień Muzyki","rule":["fixed",6,21],"icon":"neon","theme":"noir_neon"},{"id":"independence","name":"Dzień Niepodległości","rule":["fixed",11,11],"icon":"independence","theme":"independence"},{"id":"halloween","name":"Halloween","rule":["fixed",10,31],"icon":"halloween","theme":"halloween"},{"id":"mikolajki","name":"Mikołajki","rule":["fixed",12,6],"icon":"christmas","theme":"christmas"},{"id":"christmas","name":"Boże Narodzenie","rule":["fixed",12,24],"icon":"christmas","theme":"christmas"},{"id":"birthday","name":"Urodziny Piterfy","rule":["fixed",10,12],"icon":"birthday","theme":"birthday"}]};
  // piterfy-appearance-data:end

  // ── wygląd: dni okolicznościowe (±2 dni), motywy i ikony; ta sama logika co PiterfyOccasions.kt (testy: appearance-core.test.cjs) ──
  const appearanceCore=(()=>{
    const D=APPEARANCE_DATA;
    const epochDay=(y,m,d)=>{
      const yy=m<=2?y-1:y,era=Math.floor(yy/400),yoe=yy-era*400;
      const doy=Math.floor((153*(m+(m>2?-3:9))+2)/5)+d-1,doe=yoe*365+Math.floor(yoe/4)-Math.floor(yoe/100)+doy;
      return era*146097+doe-719468;
    };
    const easter=y=>{
      const a=y%19,b=Math.floor(y/100),c=y%100,d=Math.floor(b/4),e=b%4,f=Math.floor((b+8)/25),g=Math.floor((b-f+1)/3);
      const h=(19*a+b-d-g+15)%30,i=Math.floor(c/4),k=c%4,l=(32+2*e+2*i-h-k)%7,m=Math.floor((a+11*h+22*l)/451);
      return [Math.floor((h+l-7*m+114)/31),(h+l-7*m+114)%31+1];
    };
    const eventDay=(rule,y)=>rule[0]==='fixed'?epochDay(y,rule[1],rule[2]):(([m,d])=>epochDay(y,m,d))(easter(y))+rule[1];
    /** Okazja aktywna danego dnia (od `window` dni przed świętem do `window` dni po) albo null; przy zazębieniu wygrywa bliższa. */
    function active(y,m,d,list=D.occasions,window=D.window){
      const today=epochDay(y,m,d);let best=null;
      for(const o of list)for(let yy=y-1;yy<=y+1;yy++){
        const delta=today-eventDay(o.rule,yy);
        if(delta<-window||delta>window)continue;
        if(!best||Math.abs(delta)<Math.abs(best.daysFromEvent))best={occasion:o,daysFromEvent:delta};
      }
      return best;
    }
    /** Najbliższa przyszła okazja (od jutra): {occasion, inDays} do podpisu w oknie wyboru. */
    function next(y,m,d,list=D.occasions,window=D.window){
      const today=epochDay(y,m,d);let best=null;
      for(const o of list)for(let yy=y;yy<=y+1;yy++){
        const until=eventDay(o.rule,yy)-window-today;
        if(until>0&&(!best||until<best.inDays))best={occasion:o,inDays:until};
      }
      return best;
    }
    const theme=id=>D.themes.find(t=>t.id===id)||null;
    const icon=id=>D.icons.find(i=>i.id===id)||null;
    /** Motyw i ikona na dany dzień: okazja (gdy automat włączony) ma pierwszeństwo przed własnym wyborem. */
    function effective(prefs,y,m,d){
      const hit=prefs.auto===false?null:active(y,m,d);
      const themeId=hit?hit.occasion.theme:prefs.theme,iconId=hit?hit.occasion.icon:prefs.icon;
      return {theme:theme(themeId)?themeId:'classic',icon:icon(iconId)?iconId:'classic',occasion:hit?hit.occasion:null};
    }
    return {epochDay,easter,active,next,effective,theme,icon,data:D};
  })();
  // ── inteligentne odtwarzanie: czysty rdzeń wyboru (bez sieci, testowany w smart-core.test.cjs) ──
  // Wzorem są ostatnio grane utwory (nie tylko bieżący), podobieństwa z kilku źródeł się sumują, wykonawcy z ostatniej
  // chwili są wyciszani, a utwory losuje się ważonym losowaniem, nie biorąc zawsze początku listy.
  const smartCore=(()=>{
    const fold=s=>String(s||'').normalize('NFKD').replace(/[̀-ͯ]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();
    const artistKey=s=>fold(String(typeof s==='string'?s:s?.artist||'').split(/\s*(?:,|;|&|\bfeat\.?|\bft\.?|\bx\b)\s*/i)[0]);
    const songKey=s=>artistKey(s)+'|'+fold(String(s?.title||'').replace(/\s*[\(\[].*?[\)\]]\s*/g,' '));
    const DAY=864e5, MEMORY_DAYS=14, MEMORY_MAX=600;
    /** Do kilku ostatnich wykonawców (najnowszy pierwszy), z których pytamy o podobne utwory. */
    function seeds(history,max=4){
      const out=[],seen=new Set();
      for(const song of history||[]){
        const key=artistKey(song);
        if(!key||seen.has(key))continue;
        seen.add(key);out.push(song);
        if(out.length>=max)break;
      }
      return out;
    }
    /** Sumuje listy podobnych utworów: utwór polecany przez kilka wzorów dostaje większą wagę (`hits`). */
    function merge(lists){
      const byId=new Map();
      lists.forEach((songs,source)=>{
        (songs||[]).forEach((song,rank)=>{
          if(!song?.id)return;
          const row=byId.get(song.id)||{song,hits:0,sources:new Set(),best:rank};
          if(!row.sources.has(source)){row.sources.add(source);row.hits+=1;}
          row.best=Math.min(row.best,rank);
          byId.set(song.id,row);
        });
      });
      return [...byId.values()];
    }
    /** Pamięć „już to było”: {id: czas} z ostatnich dni, ograniczona liczbą wpisów. */
    function remember(memory,ids,now=Date.now()){
      const next={...(memory||{})};
      for(const id of ids)if(id)next[id]=now;
      const live=Object.entries(next).filter(([,at])=>now-at<MEMORY_DAYS*DAY).sort((a,b)=>b[1]-a[1]).slice(0,MEMORY_MAX);
      return Object.fromEntries(live);
    }
    function recentIds(memory,now=Date.now(),days=7){return new Set(Object.entries(memory||{}).filter(([,at])=>now-at<days*DAY).map(([id])=>id));}
    function weight(row,ctx){
      const song=row.song,artist=artistKey(song),n=Math.max(1,ctx.size);
      let w=1+0.6*(row.hits-1)+0.5*(1-Math.min(row.best,40)/40);
      if(row.rank!==undefined)w+=0.8*(1-row.rank/n);                      // wyższe miejsce w rankingu gustu
      const novelty=(ctx.exploration-50)/50,played=Number(song.playCount)>0;
      w+=novelty*(played?-0.3:0.6);                                      // suwak odkrywania: więcej nowych albo znajomych
      if(song.starred)w+=0.7;
      const age=ctx.artists.indexOf(artist);
      if(age>=0)w*=age<6?0.12:0.45;                                       // ten wykonawca grał przed chwilą
      const genre=fold(song.genre);
      if(genre&&ctx.genreRun>=3&&genre===ctx.lastGenre)w*=0.6;           // nie utknij w jednym gatunku
      return Math.max(w,0.01);
    }
    /**
     * candidates: [{song,hits,best,rank?}] z `merge`; ctx.history: ostatnio grane (najnowsze pierwsze);
     * ctx.queue: id utworów w kolejce; ctx.recent: Set id z pamięci; exploration 0–100.
     */
    function pick({candidates,history=[],queue=[],recent=new Set(),count=5,exploration=50,maxPerArtist=1,rng=Math.random}){
      const inQueue=new Set(queue),played=new Set(history.map(s=>s.id)),titles=new Set(history.map(songKey));
      const seen=new Set();
      const pool=candidates.filter(row=>{
        const s=row.song;
        if(!s?.id||inQueue.has(s.id)||played.has(s.id)||recent.has(s.id)||podcast(s))return false;
        const key=songKey(s);
        if(titles.has(key)||seen.has(key))return false;
        seen.add(key);return true;
      });
      const artists=[];for(const s of history){const a=artistKey(s);if(!artists.includes(a))artists.push(a);}
      const genres=history.map(s=>fold(s.genre)),lastGenre=genres[0]||'';
      let genreRun=0;for(const g of genres){if(g&&g===lastGenre)genreRun++;else break;}
      const ctx={size:pool.length,exploration:Math.max(0,Math.min(100,Number(exploration))),artists,lastGenre,genreRun};
      // ważone losowanie bez zwracania (Efraimidis–Spirakis): klucz = u^(1/w)
      const keyed=pool.map(row=>({row,key:Math.pow(Math.max(rng(),1e-9),1/weight(row,ctx))})).sort((a,b)=>b.key-a.key);
      const limit=Math.max(1,pool.length<count*2?maxPerArtist+1:maxPerArtist);
      const out=[],per=new Map();
      for(const {row} of keyed){
        const a=artistKey(row.song);
        if((per.get(a)||0)>=limit)continue;
        per.set(a,(per.get(a)||0)+1);out.push(row.song);
        if(out.length>=count)break;
      }
      return out;
    }
    return {artistKey,songKey,seeds,merge,remember,recentIds,pick};
  })();
  if(typeof module==='object'&&module.exports){module.exports={protectedQueue,queueIndex,smart:smartCore,appearance:appearanceCore};return;}
  if (window.PiterfyLibrary) return;
  let store, dialog, account = '', preferences = {}, previous, lastSave = 0, generation = 0;
  let applyingQueue = false, seekGuard = 0, saving = false, checkedAlbum = '', coverSource = '';
  const preparedRecords=new Map();let preparedIdentity='';
  let prefixJob=null,prefixId='',prefixOwner='';
  async function prefetchNextAudio(){
    const owner=identity(),player=findStore()?.getState().player;
    if(prefixOwner!==owner||window.PiterfyPlayback?.current()?.remote||navigator.connection?.saveData){prefixJob?.abort();prefixJob=null;prefixId='';prefixOwner=owner;return;}
    const queue=player?.queue||[],index=queue.findIndex(t=>t.uuid===player.current?.uuid),track=queue[index+1];
    if(!track||track.isRadio||podcast(track.song)||track.uuid===prefixId||prefixJob||!audio()||audio().paused)return;
    prefixId=track.uuid;const controller=new AbortController();prefixJob=controller;
    const timer=setTimeout(()=>controller.abort(),7000);
    try{
      const raw=typeof track.musicSrc==='function'?await track.musicSrc():track.musicSrc;
      const url=new URL(raw,location.href);
      if(owner!==identity()||url.origin!==location.origin||!/^\/rest\/(stream|getTranscodeStream)(\.view)?$/.test(url.pathname))return;
      const response=await fetch(url,{headers:{Range:'bytes=0-524287'},signal:controller.signal,cache:'default'});
      if(!response.ok)return;
      const reader=response.body.getReader();let bytes=0;
      while(bytes<524288){const chunk=await reader.read();if(chunk.done)break;bytes+=chunk.value.byteLength;}
      await reader.cancel();
    }catch(_){}finally{clearTimeout(timer);if(prefixJob===controller)prefixJob=null;}
  }
  setInterval(prefetchNextAudio,4000);
  const audio = () => document.querySelector('audio.music-player-audio');
  const identity = () => localStorage.getItem('username') || '';
  const queueKey = kind => 'piterfy.queue.v1:' + account + ':' + kind;
  function findStore() {
    if (store) return store;
    const root = document.getElementById('root');
    if (!root) return;
    const key = Object.keys(root).find(k => k.startsWith('__reactContainer$'));
    const legacy = root._reactRootContainer;
    const stack = [legacy ? (legacy._internalRoot || legacy).current : key && root[key]];
    for (let i=0; stack.length && i<20000; i++) {
      const fiber=stack.pop(); if(!fiber) continue;
      if(fiber.memoizedProps?.store?.getState?.().player) return (store=fiber.memoizedProps.store);
      if(fiber.child) stack.push(fiber.child); if(fiber.sibling) stack.push(fiber.sibling);
    }
  }
  async function api(body) {
    const user=identity();
    if(!user || !localStorage.getItem('token')) throw Error('Zaloguj się w Piterfy.');
    const response=await fetch('/piterfy-api/connect/library',{method:'POST',cache:'no-store',
      headers:{'Content-Type':'application/json','X-ND-Authorization':'Bearer '+localStorage.getItem('token')},
      body:JSON.stringify(body),signal:AbortSignal.timeout(12000)});
    if(!response.ok) throw Error('Nie udało się połączyć. Spróbuj ponownie.');
    const result=await response.json(); if(user!==identity()) throw Error('Zmieniono konto.');
    if(result.exploration!==undefined) preferences=result;
    return result;
  }
  async function subsonic(command,extra={}) {
    const params=new URLSearchParams({u:identity(),t:localStorage.getItem('subsonic-token')||'',
      s:localStorage.getItem('subsonic-salt')||'',v:'1.16.1',c:'Piterfy',f:'json',...extra});
    const response=await fetch('/rest/'+command+'?'+params,{signal:AbortSignal.timeout(12000),cache:'no-store'});
    if(!response.ok)throw Error('Nie można pobrać biblioteki.');
    const result=(await response.json())['subsonic-response'];
    if(result?.status!=='ok')throw Error('Nie można pobrać biblioteki.');
    return result;
  }
  async function rankSongs(songs) {
    const unique=[...new Map(songs.filter(s=>s?.id&&!podcast(s)).map(s=>[s.id,s])).values()].slice(0,1000);
    const ranked=await api({action:'rank',items:unique.map(s=>({id:s.id,artist:s.artist||'',genre:s.genre||'',familiar:Number(s.playCount)>0}))});
    const byId=new Map(unique.map(s=>[s.id,s]));
    return ranked.items.map(row=>({...byId.get(row.id),piterfyReason:row.reason}));
  }

  // ── wygląd (przeglądarka): zastosowanie motywu i ikony oraz okno wyboru ──
  const APPEARANCE_KEY='pf-appearance';
  function appearancePrefs(){
    const base={auto:true,theme:'classic',icon:'classic'};
    try{return {...base,...JSON.parse(localStorage.getItem(APPEARANCE_KEY)||'{}')};}catch(_){return base;}
  }
  function saveAppearance(prefs){try{localStorage.setItem(APPEARANCE_KEY,JSON.stringify(prefs));}catch(_){/* bez zapisu działa do odświeżenia */}}
  function currentAppearance(){const d=new Date();return appearanceCore.effective(appearancePrefs(),d.getFullYear(),d.getMonth()+1,d.getDate());}
  function faviconHref(){
    const e=currentAppearance();if(e.icon==='classic')return '';
    const i=appearanceCore.icon(e.icon);return i?'data:image/svg+xml,'+encodeURIComponent(i.svg):'';
  }
  let appliedAppearance='';
  function applyAppearance(force){
    const e=currentAppearance(),key=e.theme+'|'+e.icon;
    if(!force&&key===appliedAppearance)return;
    appliedAppearance=key;
    const root=document.documentElement;
    if(e.theme==='classic')root.removeAttribute('data-pf-theme');else root.setAttribute('data-pf-theme',e.theme);
    const href=faviconHref();
    document.querySelectorAll('link[rel~="icon"]').forEach(l=>{l.setAttribute('href',href||'/piterfy-ui/logo.svg');l.setAttribute('type','image/svg+xml');});
    const meta=appearanceCore.theme(e.theme);
    document.querySelectorAll('meta[name="theme-color"]').forEach(m=>m.setAttribute('content',meta?meta.bg:'#000000'));
  }
  function occasionNote(){
    const d=new Date(),y=d.getFullYear(),m=d.getMonth()+1,day=d.getDate(),hit=appearanceCore.active(y,m,day);
    if(hit)return 'Dziś: '+hit.occasion.name;
    const n=appearanceCore.next(y,m,day);if(!n)return '';
    const when=new Date(d.getFullYear(),d.getMonth(),day+n.inDays);
    return 'Najbliższa okazja: '+n.occasion.name+' (od '+when.getDate()+'.'+String(when.getMonth()+1).padStart(2,'0')+')';
  }
  function openAppearance(){
    document.querySelector('.pf-ap-dialog')?.remove();
    const dlg=el('dialog','',document.body,'pf-ap-dialog');
    const render=()=>{
      dlg.replaceChildren();
      const prefs=appearancePrefs(),eff=currentAppearance();
      el('h2','Wygląd Piterfy',dlg);
      const auto=el('label','',dlg,'pf-ap-auto'),box=el('input','',auto);box.type='checkbox';box.checked=prefs.auto!==false;
      const labels=el('span','',auto);el('strong','Automatycznie w dni okolicznościowe',labels);
      el('small','Od 2 dni przed do 2 dni po święcie motyw i ikona zmieniają się same',labels);
      box.addEventListener('change',()=>{saveAppearance({...appearancePrefs(),auto:box.checked});applyAppearance(true);render();});
      const note=occasionNote();if(note)el('p',note,dlg,'pf-ap-note');
      el('h3','Motywy',dlg);
      const themes=el('div','',dlg,'pf-ap-grid');
      const pick=(group,id)=>{saveAppearance({...appearancePrefs(),[group]:id});applyAppearance(true);render();};
      const classic=el('button','',themes,'pf-ap-card'+(prefs.theme==='classic'?' pf-sel':''));classic.type='button';
      classic.style.cssText='--c0:#000;--c1:#1db954;--c2:#a4edc2;--ct:#fff';
      el('span','',classic,'pf-ap-chips');el('b','Piterfy',classic);classic.addEventListener('click',()=>pick('theme','classic'));
      for(const t of appearanceCore.data.themes){
        const card=el('button','',themes,'pf-ap-card'+(prefs.theme===t.id?' pf-sel':''));card.type='button';
        card.style.cssText='--c0:'+t.bg+';--c1:'+t.accent+';--c2:'+t.accent2+';--ct:'+t.text;
        el('span','',card,'pf-ap-chips');el('b',t.name,card);card.addEventListener('click',()=>pick('theme',t.id));
      }
      el('h3','Ikony (karta przeglądarki)',dlg);
      const icons=el('div','',dlg,'pf-ap-grid pf-ap-icons');
      for(const i of appearanceCore.data.icons){
        const card=el('button','',icons,'pf-ap-icon'+(prefs.icon===i.id?' pf-sel':''));card.type='button';
        const img=el('img','',card);img.alt='';img.src='data:image/svg+xml,'+encodeURIComponent(i.svg);
        el('b',i.name,card);card.addEventListener('click',()=>pick('icon',i.id));
      }
      if(eff.occasion&&prefs.auto!==false)el('p','Dziś działa motyw i ikona okolicznościowa. Twój wybór zadziała po święcie albo po wyłączeniu automatu.',dlg,'pf-ap-note');
      const close=el('button','Gotowe',dlg,'pf-library-button');close.type='button';close.addEventListener('click',()=>dlg.close());
    };
    dlg.addEventListener('close',()=>dlg.remove(),{once:true});
    render();dlg.showModal();
  }
  window.PiterfyAppearance={faviconHref,apply:applyAppearance,open:openAppearance,current:currentAppearance};
  applyAppearance(true);
  setInterval(()=>applyAppearance(false),60000);  // zmiana dnia o północy

  window.PiterfyLibrary={smart:smartCore, rankSongs, play, prefetch:records, queue:openQueue, downloads:openDownloads, feedback:vote, exploration:()=>preferences.exploration??50, candidates:()=>subsonic('getRandomSongs',{size:100}).then(r=>r.randomSongs?.song||[])};
  const djScript=document.createElement('script');djScript.src='/piterfy-ui/dj.js?v='+encodeURIComponent(new URL(document.currentScript?.src||location.href).searchParams.get('v')||Date.now());document.head.append(djScript);
  async function records(songs) {
    const owner=identity();if(preparedIdentity!==owner){preparedRecords.clear();preparedIdentity=owner;}
    const missing=songs.filter(s=>!preparedRecords.has(s.id)||Date.now()-preparedRecords.get(s.id).at>600000);
    const byId=new Map();
    for(let offset=0;offset<missing.length;offset+=40){
      const response=await fetch('/api/song?'+missing.slice(offset,offset+40).map(s=>'id='+encodeURIComponent(s.id)).join('&'),{
        headers:{'X-ND-Authorization':'Bearer '+localStorage.getItem('token')},signal:AbortSignal.timeout(12000)});
      if(!response.ok)throw Error('Nie można przygotować kolejki.');
      for(const song of await response.json())byId.set(song.id,song);
    }
    if(owner!==identity())return [];
    for(const [id,song] of byId)preparedRecords.set(id,{song,at:Date.now()});
    while(preparedRecords.size>120)preparedRecords.delete(preparedRecords.keys().next().value);
    return songs.map(s=>preparedRecords.get(s.id)?.song).filter(Boolean);
  }
  function saveQueue(player=findStore()?.getState().player) {
    const element=audio();if(!player?.current?.song || !element || player.current.isRadio || applyingQueue)return;
    const kind=podcast(player.current.song)?'podcasts':'music';
    const queue=player.queue.filter(s=>!s.isRadio&&podcast(s.song)===(kind==='podcasts'));
    const index=queue.findIndex(s=>s.uuid===player.current.uuid);
    if(index<0)return;
    const start=Math.max(0,index-100);
    try{localStorage.setItem(queueKey(kind),JSON.stringify({ids:queue.slice(start,start+500).map(s=>s.trackId),index:index-start,position:element.currentTime,at:Date.now()}));}catch(_){}
  }
  async function play(songs,index=0,position=0,protect=false) {
    if(window.PiterfyPlayback?.current()?.remote)throw Error('Najpierw wybierz „Przenieś tutaj” w Connect.');
    if(protect&&songs.length){
      const wanted=songs[index]?.id,player=findStore()?.getState().player;
      songs=protectedQueue(songs,player?.queue||[],player?.queue[queueIndex(player.queue,player.current?.uuid,player.savedPlayIndex)]?.uuid,preferences.pins||[]);index=Math.max(0,songs.findIndex(s=>s.id===wanted));
    }
    saveQueue();window.PiterfyCrossfade?.cancel();
    const token=generation, s=findStore();if(!s)throw Error('Odtwarzacz nie jest jeszcze gotowy.');
    const fresh=await records(songs);if(token!==generation)return;
    if(!fresh.length)throw Error('Brak dostępnych utworów.');
    const data=Object.fromEntries(fresh.map((song,i)=>['pf_'+i,song]));
    const wanted=songs[index]?.id;
    const found=fresh.findIndex(song=>song.id===wanted);
    const target=found>=0?found:0;applyingQueue=true;
    s.dispatch({type:'PLAYER_PLAY_TRACKS',data,id:'pf_'+target});
    const expected=s.getState().player.queue[target]?.uuid;
    const deadline=performance.now()+10000;
    const wait=setInterval(()=>{
      if(token!==generation||performance.now()>deadline){clearInterval(wait);applyingQueue=false;return;}
      if(s.getState().player.current?.uuid===expected&&audio()?.readyState>=1){
        clearInterval(wait);if(position>0)audio().currentTime=position;applyingQueue=false;
      }
    },80);
  }
  async function restore(kind) {
    const saved=JSON.parse(localStorage.getItem(queueKey(kind))||'{}');
    if(!saved.ids?.length)throw Error('Ta kolejka jest jeszcze pusta.');
    await play(saved.ids.map(id=>({id})),saved.index,saved.position);
  }
  function el(tag,text,parent,cls) {
    const node=document.createElement(tag);if(text)node.textContent=text;if(cls)node.className=cls;parent?.append(node);return node;
  }
  function button(parent,text,handler,cls='pf-library-button') {
    const node=el('button',text,parent,cls);node.type='button';node.addEventListener('click',handler);
    for(const kind of ['keydown','keyup'])node.addEventListener(kind,event=>{if(event.key===' '||event.key==='Enter')event.stopPropagation();});
    return node;
  }
  function mobileBar() {
    let bar=document.querySelector('.pf-mobile-bar');
    const player=findStore()?.getState().player;
    if(!matchMedia('(max-width:809px)').matches||!player?.queue?.length){bar?.remove();return;}
    if(!bar){
      bar=el('div','',document.body,'pf-mobile-bar');
      const open=button(bar,'',()=>{
        if(window.PiterfyPlayback?.current()?.remote)document.getElementById('pf-connect-button')?.click();
        else {
          const controller=document.querySelector('.music-player-controller');
          if(!controller)return;
          // The native mini controller opens on drag-stop, rather than click.
          const point=controller.getBoundingClientRect();
          const event={bubbles:true,button:0,clientX:point.x+1,clientY:point.y+1};
          controller.dispatchEvent(new MouseEvent('mousedown',event));
          document.dispatchEvent(new MouseEvent('mouseup',event));
          controller.click();
        }
      },'pf-mobile-track');
      const art=el('img','',open);art.alt='';
      const labels=el('span','',open);el('strong','',labels);el('small','',labels);
      button(bar,'',()=>{
        const state=window.PiterfyPlayback?.current();
        const playing=state?.remote?state.playing:!!audio()&&!audio().paused;
        const action=playing?'pause':'play';
        const result=window.PiterfyPlayback?window.PiterfyPlayback.control(action):(action==='play'?audio()?.play():audio()?.pause());
        Promise.resolve(result).then(mobileBar).catch(()=>{});
      },'pf-mobile-play');
      const next=button(bar,'',()=>window.PiterfyPlayback?.control('next'),'pf-mobile-next');next.setAttribute('aria-label','Następny utwór');next.title='Następny utwór';
      const queue=button(bar,'☷',openQueue,'pf-mobile-next');queue.setAttribute('aria-label','Edytuj kolejkę');
      const icon=document.createElementNS('http://www.w3.org/2000/svg','svg');icon.setAttribute('viewBox','0 0 24 24');icon.setAttribute('aria-hidden','true');const path=document.createElementNS(icon.namespaceURI,'path');path.setAttribute('d','M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z');path.setAttribute('fill','currentColor');icon.append(path);next.append(icon);
    }
    const remote=window.PiterfyPlayback?.current();
    const item=player.current?.song?player.current:player.queue[player.savedPlayIndex||0];
    const song=remote?.remote?remote:item?.song;
    if(!song)return;
    bar.hidden=!!document.querySelector('.react-jinke-music-player-mobile');
    const open=bar.querySelector('.pf-mobile-track');
    open.setAttribute('aria-label','Otwórz odtwarzacz: '+(song.title||''));
    open.querySelector('strong').textContent=song.title||'Wybierz utwór';
    open.querySelector('small').textContent=(song.artist||'')+(remote?.remote?' · '+remote.output:'');
    const cover=!remote?.remote&&item?.cover;
    const art=open.querySelector('img');art.hidden=!cover;
    if(cover&&art.getAttribute('src')!==cover)art.src=cover;
    const playing=remote?.remote?remote.playing:!!audio()&&!audio().paused;
    const control=bar.querySelector('.pf-mobile-play');control.textContent=playing?'❚❚':'▶';
    control.setAttribute('aria-label',playing?'Wstrzymaj':'Odtwarzaj');
  }
  function message(text) {const node=dialog?.querySelector('[role=status]');if(node)node.textContent=text;}
  let queueDialog,queueUndo=null;
  function openDownloads(){
    const panel=el('dialog','',document.body,'pf-library-dialog');panel.setAttribute('aria-label','Pobieranie i gotowe bloki');
    const head=el('header','',panel,'pf-library-heading');el('h2','Pobieranie i gotowe bloki',head);button(head,'Zamknij',()=>panel.close());
    el('p','Tutaj sprawdzisz przygotowanie muzyki na serwerze. Pobrania offline telefonu znajdziesz w aplikacji: Pobrane → Centrum pobierania.',panel,'pf-library-muted');
    const rows=el('div','',panel);const notice=el('p','',panel);notice.setAttribute('role','status');let sig='';
    function render(){
      if(!panel.open)return;const states=window.PiterfyDJ?.statuses?.()||[];const next=JSON.stringify(states);if(next===sig)return;sig=next;rows.replaceChildren();
      for(const state of states){
        const card=el('section','',rows,'pf-dj-card');el('h3',state.mode==='dj'?'Piterfy DJ':'Odkrywaj · nowe utwory',card);
        const p=state.progress||{},labels={queued:'W kolejce',matching:'Dobieranie muzyki',searching:'Wyszukiwanie',downloading:'Pobieranie na serwer',importing:'Dodawanie do biblioteki',voice:'Przygotowanie zapowiedzi',ready:'Gotowe',unavailable:'Nie udało się przygotować',hourly_limit:'Osiągnięto godzinny limit pobierania'};
        el('p',`Gotowe bloki: ${state.buffer?.ready||0}/2 · inne klimaty: ${state.buffer?.alternatives?.length||0}`,card);
        const progress=el('progress','',card);progress.max=100;progress.value=Math.max(0,Math.min(100,Number(p.percent)||0));progress.setAttribute('aria-label','Postęp przygotowania '+(state.mode==='dj'?'DJ':'Odkrywaj'));
        const stage=p.stage==='unavailable'&&state.buffer?.ready?'Zapas gotowy do odsłuchu. Nowe odkrycia chwilowo niedostępne.':labels[p.stage]||'Zapas przygotowuje się w tle';
        el('p',stage+(p.total?` · ${p.completed||0}/${p.total} plików`:''),card,'pf-library-muted');
        if(p.failed)el('p',`Nieudane pliki: ${p.failed}. Gotowy zapas nadal możesz odtworzyć.`,card,'pf-library-muted');
        button(card,'Otwórz '+(state.mode==='dj'?'DJ':'Odkrywaj'),()=>{panel.close();window.PiterfyDJ.open(state.mode);});
        button(card,'Sprawdź ponownie',async()=>{try{await window.PiterfyDJ.refreshBuffer(state.mode);notice.textContent='Sprawdzono przygotowanie. Pobieranie działa w ramach limitów serwera.';render();}catch(e){notice.textContent=e.message;}});
      }
      if(!states.length)notice.textContent='Uruchom DJ lub Odkrywaj, aby przygotować zapas muzyki.';
    }
    panel.addEventListener('close',()=>{clearInterval(poll);panel.remove();},{once:true});panel.showModal();render();const poll=setInterval(render,2000);
  }
  function queueSnapshot(){
    const remote=window.PiterfyPlayback?.remoteQueue?.();
    if(remote)return {...remote,remote:true,keys:remote.items.map(s=>s.id)};
    const p=findStore()?.getState().player,items=p?.queue||[];
    return {items,index:queueIndex(items,p?.current?.uuid,p?.savedPlayIndex),keys:items.map(s=>s.uuid),remote:false};
  }
  async function editQueue(action,from,to,expected){
    const q=queueSnapshot();
    if(JSON.stringify(q.keys)!==JSON.stringify(expected.keys)||q.remote!==expected.remote||q.remote&&q.version!==expected.version)throw Error('Kolejka się zmieniła. Spróbuj ponownie.');
    if(q.remote){const result=await window.PiterfyPlayback.editQueue(action,{from,to,expected:q.version});if(action==='remove')queueUndo={song:q.items[from],at:from,remote:true,version:result.version,remaining:result.items.map(s=>s.id),account:identity()};return;}
    const p=findStore().getState().player,items=[...q.items];
    if(action==='remove'){
      if(from===q.index)throw Error('Bieżący utwór pozostaje w kolejce. Najpierw wybierz następny.');
      const removed=items.splice(from,1)[0];queueUndo={song:removed,at:from,remaining:items.map(s=>s.uuid),account:identity()};
    }else if(action==='move')items.splice(to,0,items.splice(from,1)[0]);
    else throw Error('Nieobsługiwana zmiana kolejki.');
    findStore().dispatch({type:'PLAYER_SYNC_QUEUE',data:{audioInfo:p.current,audioLists:items}});saveQueue();
  }
  function openQueue(){
    if(queueDialog?.open)return;queueDialog?.remove();queueUndo=null;
    queueDialog=el('dialog','',document.body,'pf-library-dialog pf-queue-dialog');queueDialog.setAttribute('aria-labelledby','pf-queue-heading');
    const head=el('header','',queueDialog,'pf-library-heading');el('h2','Twoja kolejka',head).id='pf-queue-heading';button(head,'Zamknij',()=>queueDialog.close());
    el('p','Przeciągnij utwór lub użyj strzałek. Przypięte utwory pozostaną po zmianie klimatu DJ-a.',queueDialog,'pf-library-muted');
    const notice=el('p','',queueDialog);notice.setAttribute('role','status');
    const undo=button(queueDialog,'Cofnij usunięcie',async()=>{
      const q=queueSnapshot(),u=queueUndo;
      if(!u||u.account!==identity()||q.remote!==!!u.remote||JSON.stringify(q.keys)!==JSON.stringify(u.remaining)||u.remote&&u.version!==q.version){notice.textContent='Kolejka się zmieniła. Nie przywrócono starego układu.';return;}
      if(u.remote){try{await window.PiterfyPlayback.editQueue('insert',{items:[u.song],index:u.at,expected:u.version});queueUndo=null;renderQueue(true);notice.textContent='Przywrócono utwór.';}catch(e){notice.textContent=e.message;}return;}
      const items=[...q.items];items.splice(u.at,0,u.song);const p=findStore().getState().player;
      findStore().dispatch({type:'PLAYER_SYNC_QUEUE',data:{audioInfo:p.current,audioLists:items}});queueUndo=null;saveQueue();renderQueue();notice.textContent='Przywrócono utwór.';
    });undo.hidden=true;
    const rows=el('div','',queueDialog);let signature='',drag=null;
    const fail=e=>{notice.textContent=e.message;renderQueue(true);};
    function renderQueue(force=false){
      if(!queueDialog.open)return;
      const q=queueSnapshot(),sig=JSON.stringify([q.keys,q.index,q.remote,q.version,preferences.pins]);
      if(!force&&sig===signature)return;
      if(!force&&rows.contains(document.activeElement))return;
      signature=sig;rows.replaceChildren();undo.hidden=!queueUndo;
      if(!q.items.length)el('p','Kolejka jest pusta.',rows,'pf-library-muted');
      q.items.forEach((item,i)=>{
        const song=q.remote?item:{...item.song,id:item.trackId||item.song?.mediaFileId||item.song?.id},row=el('article','',rows,'pf-queue-row');row.draggable=true;
        const pin=preferences.pins?.includes(song.id);
        el('small',i===q.index?'TERAZ GRA':String(i+1).padStart(2,'0'),row,'pf-dj-eyebrow');
        el('strong',(pin?'📌 ':'')+(song.title||item.name||'Utwór'),row);el('span',song.artist||item.singer||'',row,'pf-library-muted');
        row.addEventListener('dragstart',e=>{drag={i,q};e.dataTransfer.effectAllowed='move';e.dataTransfer.setData('text/plain',String(i));});
        row.addEventListener('dragover',e=>e.preventDefault());row.addEventListener('drop',e=>{e.preventDefault();if(drag)editQueue('move',drag.i,i,drag.q).then(()=>renderQueue(true)).catch(fail);drag=null;});
        const controls=el('div','',row,'pf-library-actions');
        const move=(to)=>editQueue('move',i,to,q).then(()=>renderQueue(true)).catch(fail);
        const up=button(controls,'↑',()=>move(i-1));up.disabled=i===0;up.setAttribute('aria-label','Przesuń wyżej: '+song.title);
        const down=button(controls,'↓',()=>move(i+1));down.disabled=i===q.items.length-1;down.setAttribute('aria-label','Przesuń niżej: '+song.title);
        const next=button(controls,'Zagraj następne',()=>move(i<q.index?q.index:q.index+1));next.disabled=i===q.index||q.index<0;
        button(controls,pin?'Odepnij':'Przypnij',()=>api({action:'pin',id:song.id,enabled:!pin}).then(()=>{renderQueue(true);notice.textContent=pin?'Odpięto.':'Ten utwór zostanie zachowany przy zmianie klimatu.';}).catch(fail));
        const remove=button(controls,'Usuń',()=>editQueue('remove',i,i+1,q).then(()=>{renderQueue(true);notice.textContent='Usunięto utwór. Możesz cofnąć tę zmianę.';}).catch(fail));remove.disabled=i===q.index;
      });
    }
    queueDialog.showModal();renderQueue(true);api({action:'get'}).then(()=>renderQueue(true)).catch(fail);
    const poll=setInterval(renderQueue,1500);queueDialog.addEventListener('close',()=>clearInterval(poll),{once:true});
  }
  async function vote(song,value,reason='') {
    if(value===-1&&!reason)return new Promise((resolve,reject)=>{
      const choice=el('dialog','',document.body,'pf-library-dialog');choice.setAttribute('aria-label','Co nie pasuje?');el('h2','Co nie pasuje?',choice);el('p','Powód pomoże dokładniej dobierać muzykę. Możesz go pominąć.',choice,'pf-library-muted');
      for(const [key,title] of [['track','Ten utwór'],['artist','Wykonawca'],['genre','Gatunek'],['energy','Energia utworu'],['repetition','Zbyt częste powtórki']]){const b=button(choice,title,()=>{b.disabled=true;vote(song,value,key).then(()=>{choice.close();resolve();}).catch(e=>{b.disabled=false;reject(e);choice.close();});});}
      button(choice,'Bez podawania powodu',()=>vote(song,value,'track').then(()=>{choice.close();resolve();}).catch(reject));
      button(choice,'Anuluj',()=>{resolve(false);choice.close();});choice.addEventListener('close',()=>{choice.remove();resolve(false);},{once:true});choice.showModal();
    });
    await api({action:'feedback',id:song.id,artist:song.artist||'',genre:song.genre||'',reason,vote:value,event:crypto.randomUUID()});
    message('Zapisano — kolejne propozycje uwzględnią Twoją reakcję.');return true;
  }
  function open() {
    if(dialog?.open)return;
    dialog?.remove();dialog=el('dialog','',document.body,'pf-library-dialog');dialog.setAttribute('aria-labelledby','pf-library-heading');
    const head=el('header','',dialog,'pf-library-heading');el('h2','Odkrywaj',head).id='pf-library-heading';
    button(head,'Zamknij',()=>dialog.close());
    el('p','Więcej muzyki dopasowanej do Ciebie — z miejscem na nowe brzmienia.',dialog,'pf-library-muted');
    const label=el('label','Poziom eksperymentowania',dialog);label.htmlFor='pf-exploration';const value=el('output','50%',label);
    const slider=el('input','',dialog);slider.type='range';slider.id='pf-exploration';slider.min='0';slider.max='100';slider.step='5';slider.value=preferences.exploration??50;
    const update=()=>{value.textContent=slider.value+'%';slider.setAttribute('aria-valuetext',slider.value+' procent');};update();
    slider.oninput=update;slider.onchange=()=>api({action:'settings',exploration:Number(slider.value)}).then(()=>message('Zapisane na Twoim koncie.')).catch(e=>message(e.message));
    el('p','Znajome brzmienia ← → Nowi wykonawcy',dialog,'pf-library-muted');
    const actions=el('div','',dialog,'pf-library-actions');
    button(actions,'Edytuj kolejkę',()=>{dialog.close();openQueue();});
    button(actions,'Pobieranie i gotowe bloki',()=>{dialog.close();openDownloads();});
    button(actions,'Piterfy DJ',()=>{dialog.close();window.PiterfyDJ?.open('dj');});
    button(actions,'Nowe utwory · pobieranie',()=>{dialog.close();window.PiterfyDJ?.open('discover');});
    const status=el('p','',dialog,'pf-library-muted');status.setAttribute('role','status');
    const current=window.PiterfyPlayback?.current()?.remote?null:findStore()?.getState().player.current?.song;
    if(current&&!podcast(current)){
      const row=el('section','',dialog,'pf-library-current');el('strong',current.title+' · '+current.artist,row);
      button(row,'Więcej takich',()=>vote(current,1).catch(e=>message(e.message)));
      button(row,'Mniej takich',()=>vote(current,-1).catch(e=>message(e.message)));
    }
    const results=el('div','',dialog,'pf-library-results');
    const refresh=button(actions,'Odkryj muzykę',async()=>{
      refresh.disabled=true;results.replaceChildren();message('Dobieram propozycje…');const token=generation;
      try{
        const random=(await subsonic('getRandomSongs',{size:150})).randomSongs?.song||[];
        const similar=current?.artistId?(await subsonic('getSimilarSongs2',{id:current.artistId,count:40}).catch(()=>({}))).similarSongs2?.song||[]:[];
        const songs=(await rankSongs([...similar,...random])).slice(0,20);if(token!==generation||!dialog.open)return;
        window.PiterfyDJ?.prepare('discover',songs.slice(0,5)).catch(()=>{});
        if(songs.length)button(results,'Odtwórz propozycje',()=>{window.PiterfyDJ?.markDiscovery(songs);return play(songs).then(()=>dialog.close()).catch(e=>message(e.message));});
        for(const song of songs){
          const row=el('article','',results,'pf-library-song');el('strong',song.title,row);el('span',song.artist,row,'pf-library-muted');el('small',song.piterfyReason,row,'pf-library-muted');
          const controls=el('div','',row,'pf-library-actions');
          button(controls,'Więcej takich',()=>vote(song,1).catch(e=>message(e.message)));
          button(controls,'Mniej takich',()=>vote(song,-1).then(saved=>{if(saved)row.remove();}).catch(e=>message(e.message)));
        }
        message(songs.length?'Propozycje uwzględniają reakcje i wczesne pominięcia.':'Brak propozycji. Zmień ustawienie i spróbuj ponownie.');
      }catch(e){message(e.message);}finally{refresh.disabled=false;}
    });
    button(actions,'Kolejka muzyki',()=>restore('music').then(()=>dialog.close()).catch(e=>message(e.message)));
    button(actions,'Kolejka podcastów',()=>restore('podcasts').then(()=>dialog.close()).catch(e=>message(e.message)));
    api({action:'get'}).then(state=>{if(dialog.open){slider.value=state.exploration;update();}}).catch(e=>message(e.message));
    dialog.showModal();
  }
  async function saveProgress(item,position,duration) {
    if(saving||window.PiterfyPlayback?.current()?.remote||!podcast(item)||position<3||!Number.isFinite(duration)||duration<=0)return;
    saving=true;
    try{
      const result=await api({action:'progress',id:item.id,position:Math.min(duration,position)*1000,duration:duration*1000,
        revision:preferences.episodes?.[item.id]?.revision||0});
      if(result.conflict)await api({action:'get'});
    }catch(_){}finally{saving=false;}
  }
  async function loadProgress(item,element) {
    if(!podcast(item))return;
    const token=generation, guard=++seekGuard;
    try{
      const state=await api({action:'get'});const episode=state.episodes?.[item.id];
      if(token===generation&&guard===seekGuard&&findStore()?.getState().player.current?.trackId===item.id&&element.currentTime<5&&episode&&!episode.completed&&episode.position>5000)
        element.currentTime=episode.position/1000;
    }catch(_){}
  }
  function attachAudio(element) {
    if(!element||element.dataset.piterfyLibrary)return;
    element.dataset.piterfyLibrary='1';
    element.addEventListener('seeking',()=>{seekGuard++;});
    element.addEventListener('pause',()=>{const song=findStore()?.getState().player.current?.song;saveProgress(song,element.currentTime,element.duration);saveQueue();});
  }
  async function subscribe(id,button) {
    try{
      await api({action:'get'});const enabled=!(preferences.subscriptions||[]).includes(id);
      await api({action:'subscribe',id,enabled});button.textContent=enabled?'Subskrybujesz · auto offline na telefonie':'Subskrybuj · auto offline na telefonie';
    }catch(e){button.textContent=e.message;}
  }
  async function podcastTools(main) {
    if(main.querySelector('.pf-podcast-tools'))return;
    const section=el('section','',null,'pf-podcast-tools');
    const heading=main.querySelector('.pf-home-head');
    if(heading)heading.after(section);else main.prepend(section);
    const nav=el('nav','',section,'pf-library-actions');nav.setAttribute('aria-label','Muzyka, podcasty i radio');
    for(const [label,href] of [['Muzyka','#/start'],['Podcasty','#/podcasty'],['Radia','#/radio']]){
      const link=el('a',label,nav,'pf-library-button');link.href=href;
      if(href==='#/podcasty')link.setAttribute('aria-current','page');
    }
    const actions=el('div','',section,'pf-library-actions');
    const status=el('p','',section,'pf-library-muted');status.setAttribute('role','status');
    button(actions,'Wróć do kolejki podcastów',()=>restore('podcasts').catch(e=>status.textContent=e.message));
    button(actions,'Wróć do muzyki',()=>restore('music').catch(e=>status.textContent=e.message));
    el('p','Subskrypcje są wspólne z Androidem. Nowe odcinki pobiera automatycznie telefon przez Wi-Fi.',section,'pf-library-muted');
    const token=generation;
    try{
      const state=await api({action:'get'});if(token!==generation||!section.isConnected)return;
      const episodes=Object.values(state.episodes||{}).filter(e=>!e.completed&&e.position>5000).sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,8);
      if(episodes.length){
        const songs=await records(episodes.map(e=>({id:e.id})));
        if(token!==generation||!section.isConnected)return;
        el('h3','Kontynuuj słuchanie',section);
        const cards=el('div','',section,'pf-library-actions');
        for(const song of songs){const episode=state.episodes[song.id];
          button(cards,song.title+' · '+Math.floor(episode.position/60000)+':'+String(Math.floor(episode.position/1000)%60).padStart(2,'0'),
            ()=>play([song],0,episode.position/1000).catch(e=>status.textContent=e.message));
        }
      }
    }catch(e){status.textContent=e.message;}
  }
  function mount() {
    if(account!==identity()) {generation++;account=identity();preferences={};previous=null;store=null;checkedAlbum='';applyingQueue=false;dialog?.close();}
    if(!account||location.hash.startsWith('#/login')){document.querySelector('.pf-mobile-bar')?.remove();return;}
    const categories=document.querySelector('.pf-home .pf-chips');
    if(categories){categories.setAttribute('role','navigation');categories.setAttribute('aria-label','Biblioteka Piterfy');for(const link of categories.querySelectorAll('a')){if(link.hash===(location.hash||'#/start'))link.setAttribute('aria-current','page');else link.removeAttribute('aria-current');}}
    for(const [selector,label] of [['.react-jinke-music-player-mobile-header-right','Zamknij odtwarzacz'],['.react-jinke-music-player-mobile .prev-audio','Poprzedni utwór'],['.react-jinke-music-player-mobile .next-audio','Następny utwór'],['.react-jinke-music-player-mobile .play-btn',audio()?.paused?'Odtwórz':'Wstrzymaj'],['.music-player-panel .play-btn',audio()?.paused?'Odtwórz':'Wstrzymaj']]){
      for(const node of document.querySelectorAll(selector)){
        if(node.matches('button,input,a'))continue;
        node.setAttribute('role','button');node.setAttribute('aria-label',label);node.tabIndex=0;
        if(!node.dataset.pfKeyboard){node.dataset.pfKeyboard='true';node.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.stopPropagation();if(!event.repeat)node.click();}});node.addEventListener('keyup',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.stopPropagation();}});}
      }
    }
    attachAudio(audio());
    const toolbar=document.querySelector('#react-admin-title')?.parentElement;
    if(toolbar&&!document.querySelector('.pf-discovery-launch'))button(toolbar,'Odkrywaj',open,'pf-library-button pf-discovery-launch');
    const panel=document.querySelector('.music-player-panel .player-content, .react-jinke-music-player-mobile-operation');
    if(panel&&!panel.querySelector('.pf-discovery-feedback'))button(panel,'Polecenia',open,'group pf-library-button pf-discovery-feedback');
    const match=location.hash.match(/^#\/album\/([^/]+)\/show/);
    const title=document.querySelector('#react-admin-title');
    if(match&&title&&checkedAlbum!==match[1]){
      checkedAlbum=match[1];document.querySelector('.pf-subscribe-launch')?.remove();
      const id=decodeURIComponent(match[1]);
      subsonic('getAlbum',{id}).then(result=>{
        if(location.hash.includes(id)&&result.album?.song?.some(podcast)&&!document.querySelector('.pf-subscribe-launch'))
          button(title.parentElement,'Subskrybuj · auto offline na telefonie',event=>subscribe(id,event.currentTarget),'pf-library-button pf-subscribe-launch');
      }).catch(()=>{});
    }
    if(!match){checkedAlbum='';document.querySelector('.pf-subscribe-launch')?.remove();}
    const main=document.querySelector('#main-content .pf-home');
    if(location.hash.startsWith('#/podcasty')&&main)podcastTools(main);
    else document.querySelector('.pf-podcast-tools')?.remove();
    const art=document.querySelector('.music-player-panel .img-content');
    const background=art&&getComputedStyle(art).backgroundImage;
    const raw=background?.match(/^url\(["']?(.*?)["']?\)$/)?.[1];
    if(raw&&raw!==coverSource){
      coverSource=raw;const token=generation;const url=new URL(raw,location.href);
      if(url.origin===location.origin){
        const image=new Image();image.crossOrigin='anonymous';
        image.onload=()=>{try{
          if(token!==generation||coverSource!==raw)return;
          const canvas=document.createElement('canvas');canvas.width=canvas.height=24;
          const context=canvas.getContext('2d',{willReadFrequently:true});context.drawImage(image,0,0,24,24);
          const pixels=context.getImageData(0,0,24,24).data;const sum=[0,0,0];let total=0;
          for(let i=0;i<pixels.length;i+=4){const rgb=[pixels[i],pixels[i+1],pixels[i+2]],weight=(Math.max(...rgb)-Math.min(...rgb))/255;if(weight<.12)continue;total+=weight;rgb.forEach((v,j)=>sum[j]+=v*weight);}
          const color=total?sum.map(v=>Math.round(v/total*.28)):[18,18,18];
          document.documentElement.style.setProperty('--pf-cover-accent','rgb('+color.join(',')+')');
        }catch(_){}};
        image.src=url.href;
      }
    }
  }
  function observe() {
    const player=findStore()?.getState().player,element=audio();if(!player||!element||!account)return;
    mobileBar();
    const song=player.current?.song;
    if(song&&!element.paused&&!applyingQueue&&!window.PiterfyPlayback?.current()?.remote&&
      !player.queue.some(item=>item.isRadio)&&player.queue.some(item=>podcast(item.song)!==podcast(song))){
      const selected=player.queue.filter(item=>podcast(item.song)===podcast(song));
      const other=player.queue.filter(item=>podcast(item.song)!==podcast(song));
      const kind=podcast(song)?'music':'podcasts';
      try{
        const old=JSON.parse(localStorage.getItem(queueKey(kind))||'{}');const wanted=old.ids?.[old.index];
        const ids=other.slice(0,500).map(item=>item.trackId);const index=Math.max(0,ids.indexOf(wanted));
        localStorage.setItem(queueKey(kind),JSON.stringify({ids,index,position:old.position||0,at:Date.now()}));
      }catch(_){}
      applyingQueue=true;
      const index=selected.findIndex(item=>item.uuid===player.current.uuid);
      play(selected.map(item=>({id:item.trackId})),Math.max(0,index),element.currentTime)
        .catch(()=>{applyingQueue=false;});
      return;
    }
    if(previous?.uuid!==player.current?.uuid){
      if(previous)saveProgress(previous.song,previous.position,previous.duration);
      if(song)loadProgress(song,element);
    }
    previous={uuid:player.current?.uuid,song,position:element.currentTime,duration:element.duration};
    if(Date.now()-lastSave>15000){lastSave=Date.now();saveQueue();if(!element.paused)saveProgress(song,element.currentTime,element.duration);}
  }
  document.addEventListener('click',event=>{
    if(!event.target.closest?.('.next-audio, .pf-mobile-next, .audio-lists-panel li')||window.PiterfyPlayback?.current()?.remote)return;
    const player=findStore()?.getState().player,song=player?.current?.song,element=audio();
    if(!song||podcast(song)||!element)return;
    const before={id:song.id,artist:song.artist||'',position:element.currentTime*1000,duration:element.duration*1000,event:crypto.randomUUID()};
    setTimeout(()=>{if(findStore()?.getState().player.current?.song?.id!==before.id&&before.position>=3000&&before.position<Math.min(30000,before.duration*.3))api({action:'skip',...before}).catch(()=>{});},1000);
  },true);
  window.addEventListener('pagehide',()=>saveQueue());
  const css=el('link','',document.head);css.rel='stylesheet';css.href='/piterfy-ui/library.css?v='+encodeURIComponent(new URL(document.currentScript?.src||location.href).searchParams.get('v')||Date.now());
  const experience=el('link','',document.head);experience.rel='stylesheet';experience.href=css.href.replace('/library.css?','/experience.css?');
  mount();setInterval(mount,2000);setInterval(observe,500);
})();
