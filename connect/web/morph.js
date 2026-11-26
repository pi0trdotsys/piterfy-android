/* Piterfy — przejścia z morfem okładki (View Transitions API): kliknięcie karty/pozycji prowadzącej do albumu, wykonawcy
 * albo playlisty „unosi” okładkę z listy na jej miejsce na nowej stronie, a reszta strony płynnie się podmienia.
 * Navidrome to aplikacja jednostronicowa (adresy #/…), więc nawigację wykonujemy sami wewnątrz document.startViewTransition:
 * dzięki temu przeglądarka zrobi zdjęcie starego widoku, zanim React narysuje nowy. Gdy API go nie ma (albo gdy wyłączono
 * ruch: localStorage pf-calm=1; samo przejście wyłączysz przez pf-vt=0), klik działa zwyczajnie. */
(() => {
  "use strict";
  if (window.PiterfyMorph) return;
  const flag = (key, value) => { try { return localStorage.getItem(key) === value; } catch (e) { return false; } };
  const enabled = () => typeof document.startViewTransition === "function" && !flag("pf-calm", "1") && !flag("pf-vt", "0");
  const NAME = "pf-cover";
  const SETTLE_MS = 140, MAX_MS = 900;

  /** Czeka, aż treść strony przestanie się zmieniać (React dorysował nowy widok), nie dłużej niż MAX_MS. */
  function settled() {
    return new Promise((resolve) => {
      const target = document.getElementById("main-content") || document.body;
      let quiet = 0;
      const done = () => { observer.disconnect(); clearTimeout(quiet); clearTimeout(cap); resolve(); };
      const observer = new MutationObserver(() => { clearTimeout(quiet); quiet = setTimeout(done, SETTLE_MS); });
      observer.observe(target, { childList: true, subtree: true, attributes: false });
      quiet = setTimeout(done, SETTLE_MS * 2);
      const cap = setTimeout(done, MAX_MS);
    });
  }

  /** Największa widoczna okładka na nowej stronie (nagłówek albumu, wykonawcy, playlisty). */
  function destinationCover() {
    const candidates = [...document.querySelectorAll('#main-content img[src*="getCoverArt"], #main-content .pf-hero')];
    let best = null, area = 0;
    for (const el of candidates) {
      const r = el.getBoundingClientRect();
      const visible = r.bottom > 0 && r.top < innerHeight && r.width >= 100 && r.height >= 100;
      if (visible && r.width * r.height > area) { best = el; area = r.width * r.height; }
    }
    return best;
  }

  document.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (!enabled()) return;
    const target = event.target;
    if (!target || !target.closest || target.closest("button, [data-pf-play], .pf-play, input, textarea, select")) return;
    const link = target.closest('a[href^="#/"]');
    if (!link || link.target || link.hasAttribute("download")) return;
    const href = link.getAttribute("href");
    if (!href || href === location.hash) return;
    const source = link.querySelector(".pf-card-art, img");
    event.preventDefault();
    if (source) source.style.viewTransitionName = NAME;
    let claimed = null;
    const cleanup = () => {
      if (source) source.style.viewTransitionName = "";
      if (claimed) claimed.style.viewTransitionName = "";
      window.__pfMorphing = false;
    };
    window.__pfMorphing = true;  // routeFade() w piterfy.js nie dubluje animacji
    try {
      const transition = document.startViewTransition(async () => {
        location.hash = href;
        await settled();
        if (source) source.style.viewTransitionName = "";
        const cover = source && destinationCover();
        if (cover) { cover.style.viewTransitionName = NAME; claimed = cover; }
      });
      transition.finished.then(cleanup, cleanup);
    } catch (error) {
      cleanup();
      location.hash = href;
    }
  }, true);

  window.PiterfyMorph = { enabled };
})();
