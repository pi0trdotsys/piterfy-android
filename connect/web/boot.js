/* Piterfy — ekran ładowania. Caddy dopisuje ten plik synchronicznie na początku <head>, więc działa jeszcze przed
 * pierwszym rysunkiem strony: zamiast białej pustki, „gołego” Navidrome i dopiero potem przebierania go w Piterfy
 * (logo, panel, krój pisma, kolory — przy Ctrl+R widać było wszystko po kolei) pokazuje animowane intro:
 * dryfujące zielone poświaty, logo z equalizerem, sonar rozchodzący się z logo i napis „piterfy”.
 * Zasłonę zdejmuje piterfy.js (window.__pfBootDone), gdy interfejs jest już gotowy — wtedy przesłona zamyka się
 * na logo i odsłania aplikację. Intro trwa co najmniej MIN_MS, żeby animacja zdążyła się pokazać; gdyby coś poszło
 * nie tak, zasłona znika sama po kilku sekundach, więc aplikacja nigdy nie zostaje zasłonięta na stałe.
 * Wszystko to czysty CSS (bez bibliotek); przy „ogranicz ruch” zostaje spokojne, nieruchome logo. */
(function () {
  "use strict";
  var root = document.documentElement;
  if (window.__pfBootDone || root.hasAttribute("data-pf-boot")) return;

  var MIN_MS = 750, MAX_MS = 5000, OUT_MS = 800;
  var t0 = performance.now();

  var css =
    'html[data-pf-boot]{background:#000!important}' +
    'html[data-pf-boot] #root{opacity:0}' +
    'html[data-pf-boot=out] #root{opacity:1}' +
    '.pf-bt{position:fixed;inset:0;z-index:2147483647;overflow:hidden;background:#000;display:flex;flex-direction:column;' +
    'align-items:center;justify-content:center;clip-path:circle(75% at 50% 50%);font-family:Figtree,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}' +
    '.pf-bt.out{pointer-events:none;animation:pf-bt-iris ' + OUT_MS + 'ms cubic-bezier(.65,0,.25,1) forwards}' +
    '@keyframes pf-bt-iris{to{clip-path:circle(0% at 50% 50%)}}' +
    /* poświaty tła */
    '.pf-bt-glow{position:absolute;width:90vmax;height:90vmax;border-radius:50%;pointer-events:none;filter:blur(8px);opacity:0;animation:pf-bt-fade 1.2s ease-out .1s forwards}' +
    '.pf-bt-glow.a{left:-30vmax;top:-35vmax;background:radial-gradient(closest-side,rgba(29,185,84,.34),rgba(29,185,84,0) 70%)}' +
    '.pf-bt-glow.b{right:-35vmax;bottom:-40vmax;background:radial-gradient(closest-side,rgba(164,237,194,.2),rgba(164,237,194,0) 70%)}' +
    '.pf-bt-glow.a i,.pf-bt-glow.b i{position:absolute;inset:0;border-radius:50%;background:inherit;animation:pf-bt-drift 7s ease-in-out infinite alternate}' +
    '.pf-bt-glow.b i{animation-duration:9s;animation-direction:alternate-reverse}' +
    '@keyframes pf-bt-drift{from{transform:translate(0,0) scale(1)}to{transform:translate(9vmax,6vmax) scale(1.18)}}' +
    '@keyframes pf-bt-fade{to{opacity:1}}' +
    /* sonar */
    '.pf-bt-core{position:relative;width:104px;height:104px;display:flex;align-items:center;justify-content:center;' +
    'animation:pf-bt-pop .7s cubic-bezier(.2,1.3,.4,1) both}' +
    '.pf-bt.out .pf-bt-core{animation:pf-bt-core-out ' + (OUT_MS - 150) + 'ms cubic-bezier(.65,0,.25,1) forwards}' +
    '@keyframes pf-bt-pop{from{transform:scale(.4);opacity:0}to{transform:scale(1);opacity:1}}' +
    '@keyframes pf-bt-core-out{to{transform:scale(.25);opacity:0}}' +
    '.pf-bt-ring{position:absolute;inset:0;border-radius:50%;border:2px solid rgba(29,185,84,.6);opacity:0;animation:pf-bt-ring 2.4s ease-out infinite}' +
    '.pf-bt-ring:nth-child(2){animation-delay:.8s}.pf-bt-ring:nth-child(3){animation-delay:1.6s}' +
    '@keyframes pf-bt-ring{0%{transform:scale(.9);opacity:.75}100%{transform:scale(3.6);opacity:0}}' +
    /* logo z equalizerem */
    '.pf-bt-logo{position:relative;width:104px;height:104px;overflow:visible;filter:drop-shadow(0 0 22px rgba(29,185,84,.55));' +
    'animation:pf-bt-breathe 2.2s ease-in-out infinite}' +
    '@keyframes pf-bt-breathe{50%{filter:drop-shadow(0 0 34px rgba(29,185,84,.85))}}' +
    '.pf-bt-bar{transform-box:fill-box;transform-origin:50% 100%;animation:pf-bt-eq .85s ease-in-out infinite alternate}' +
    '.pf-bt-bar:nth-of-type(1){animation-delay:-.1s}.pf-bt-bar:nth-of-type(2){animation-delay:-.55s;animation-duration:.7s}' +
    '.pf-bt-bar:nth-of-type(3){animation-delay:-.3s;animation-duration:.95s}.pf-bt-bar:nth-of-type(4){animation-delay:-.7s;animation-duration:.78s}' +
    '@keyframes pf-bt-eq{from{transform:scaleY(.3)}to{transform:scaleY(1)}}' +
    /* napis */
    '.pf-bt-word{position:relative;margin-top:52px;display:flex;gap:.34em;font-size:15px;font-weight:700;letter-spacing:.34em;color:rgba(255,255,255,.82)}' +
    '.pf-bt.out .pf-bt-word{animation:pf-bt-core-out ' + (OUT_MS - 250) + 'ms ease-in forwards}' +
    '.pf-bt-word b{font-weight:inherit;opacity:0;transform:translateY(10px);animation:pf-bt-letter .55s cubic-bezier(.2,.9,.3,1) forwards}' +
    '@keyframes pf-bt-letter{to{opacity:1;transform:none}}' +
    '@media (prefers-reduced-motion:reduce){.pf-bt *,.pf-bt{animation:none!important}.pf-bt-glow,.pf-bt-word b{opacity:1;transform:none}' +
    '.pf-bt.out{transition:opacity .25s ease;opacity:0;clip-path:none}.pf-bt-ring{display:none}}';

  var style = document.createElement("style");
  style.id = "pf-boot-css";
  style.textContent = css;
  (document.head || root).appendChild(style);

  var word = "piterfy".split("").map(function (ch, i) {
    return '<b style="animation-delay:' + (0.45 + i * 0.06).toFixed(2) + 's">' + ch + "</b>";
  }).join("");
  var veil = document.createElement("div");
  veil.className = "pf-bt";
  veil.setAttribute("aria-hidden", "true");
  veil.innerHTML =
    '<div class="pf-bt-glow a"><i></i></div><div class="pf-bt-glow b"><i></i></div>' +
    '<div class="pf-bt-core"><span class="pf-bt-ring"></span><span class="pf-bt-ring"></span><span class="pf-bt-ring"></span>' +
    '<svg class="pf-bt-logo" viewBox="0 0 30 30"><circle cx="15" cy="15" r="15" fill="#1db954"/>' +
    '<rect class="pf-bt-bar" x="7.6" y="8.5" width="2.6" height="13" rx="1.3" fill="#000"/>' +
    '<rect class="pf-bt-bar" x="11.9" y="8.5" width="2.6" height="13" rx="1.3" fill="#000"/>' +
    '<rect class="pf-bt-bar" x="16.2" y="8.5" width="2.6" height="13" rx="1.3" fill="#000"/>' +
    '<rect class="pf-bt-bar" x="20.5" y="8.5" width="2.6" height="13" rx="1.3" fill="#000"/></svg></div>' +
    '<div class="pf-bt-word">' + word + "</div>";

  root.setAttribute("data-pf-boot", "1");
  root.appendChild(veil);

  var finished = false;
  function cleanup() {
    root.removeAttribute("data-pf-boot");
    if (veil.parentNode) veil.parentNode.removeChild(veil);
    if (style.parentNode) style.parentNode.removeChild(style);
  }
  function finish() {
    if (finished) return;
    finished = true;
    // intro musi mieć czas się pokazać; po nim przesłona zamyka się na logo i odsłania aplikację
    setTimeout(function () {
      root.setAttribute("data-pf-boot", "out");
      veil.classList.add("out");
      setTimeout(cleanup, OUT_MS + 80);
    }, Math.max(0, MIN_MS - (performance.now() - t0)));
  }
  window.__pfBootDone = finish;
  setTimeout(finish, MAX_MS);
})();
