/* Piterfy — „czucie” interfejsu (strona): haptyka, ustawienia ruchu, wybuch serca, toast „Cofnij”, strażnik kontrastu akcentu
 * z okładki i potwierdzenia/cofanie zmian w kolejce. Logika: feel-core.js, undo-core.js (testy: *.test.cjs).
 *
 *   window.PiterfyFeel = { tap, tick, success, warn, heavy, enabled, motion, apply, burst, toast }
 *
 * Ustawienia (localStorage; zmienia je strona „Wygląd”, a po zmianie woła PiterfyFeel.apply() albo wysyła zdarzenie
 * `pf-prefs-changed`):
 *   pf-haptics  "0" | "1"                     — wibracje (domyślnie włączone; działają tam, gdzie jest navigator.vibrate)
 *   pf-motion   "system" | "full" | "reduced"  — ruch; „reduced” ustawia <html data-pf-motion="reduced"> (motion.css)
 * Bez wyboru ruch jest pełny (Piterfy celowo nie słucha systemowego „ogranicz ruch”); dawne pf-calm=1 działa jak „reduced”. */
(() => {
  'use strict';
  if (window.PiterfyFeel) return;
  const core = window.PiterfyFeelCore, undo = window.PiterfyUndoCore;
  if (!core) return;
  const root = document.documentElement;
  const read = (key) => { try { return localStorage.getItem(key); } catch (_) { return null; } };
  const reducedQuery = window.matchMedia ? matchMedia('(prefers-reduced-motion: reduce)') : { matches: false };
  let motion = 'full', haptics = true, lastBuzz = 0;

  // ── ustawienia ──────────────────────────────────────────────────────────────────────────────────────────
  function apply() {
    motion = core.resolveMotion(read('pf-motion'), reducedQuery.matches, read('pf-calm') === '1');
    haptics = core.hapticsOn(read('pf-haptics'));
    if (motion === 'reduced') root.setAttribute('data-pf-motion', 'reduced'); else root.removeAttribute('data-pf-motion');
    try { window.dispatchEvent(new CustomEvent('pf-feel-applied', { detail: { motion, haptics } })); } catch (_) { /* stare przeglądarki */ }
    return { motion, haptics };
  }

  // ── haptyka ─────────────────────────────────────────────────────────────────────────────────────────────
  function buzz(kind) {
    if (!haptics || typeof navigator.vibrate !== 'function' || document.hidden) return false;
    if (navigator.userActivation && !navigator.userActivation.hasBeenActive) return false;   // przeglądarka i tak zablokuje (hałas w konsoli)
    const now = Date.now();
    if (now - lastBuzz < 30) return false;          // nie zlewaj wibracji w ciągły warkot
    lastBuzz = now;
    try { return navigator.vibrate(core.pattern(kind)) !== false; } catch (_) { return false; }
  }
  const feel = {
    tap: () => buzz('tap'), tick: () => buzz('tick'), success: () => buzz('success'), warn: () => buzz('warn'), heavy: () => buzz('heavy'),
    enabled: () => haptics && typeof navigator.vibrate === 'function', motion: () => motion, apply,
  };

  // Kliknięcia sterowania odtwarzaczem, paska mobilnego i okien kolejki dają krótkie „pyknięcie”; polubienie — wybuch serca.
  const REGION = '.react-jinke-music-player, .react-jinke-music-player-main, .react-jinke-music-player-mobile, .pf-mobile-bar, .pf-library-dialog, .pf-undo';
  const LIKE = '[data-testid^="Favorite"], [data-testid^="Star"], [aria-label*="polub" i], [aria-label*="ulubion" i], [aria-label*="favorite" i], [aria-label="love" i], [aria-label="unlove" i]';
  document.addEventListener('click', (event) => {
    const target = event.target && event.target.closest ? event.target.closest('button, [role="button"], .prev-audio, .next-audio, .play-btn, .loop-btn, .audio-lists-btn') : null;
    if (!target || !target.closest(REGION) || target.disabled || target.getAttribute('aria-disabled') === 'true') return;
    const label = target.getAttribute('aria-label') || target.title || target.textContent || '';
    const likeish = target.matches(LIKE) || !!target.querySelector(LIKE);
    const kind = likeish ? 'success' : core.kindForLabel(label);
    if (kind) buzz(kind);
    if (likeish) setTimeout(() => { if (target.isConnected && target.querySelector('[data-testid="FavoriteIcon"], [data-testid="StarIcon"]')) burst(target); }, 90);
  }, true);

  // Sprężyste puszczenie: po podniesieniu palca element wraca z odbiciem (CSS: motion.css, klasa .pf-release).
  const PRESS = ':is(.react-jinke-music-player, .react-jinke-music-player-main, .react-jinke-music-player-mobile) :is(button, [role="button"], .prev-audio, .next-audio, .play-btn, .loop-btn, .audio-lists-btn, .reload-btn, .destroy-btn, .group), .pf-mobile-bar button, .pf-library-button, .pf-undo button, .pf-chips a';
  document.addEventListener('pointerup', (event) => {
    const node = motion === 'reduced' || !event.target || !event.target.closest ? null : event.target.closest(PRESS);
    if (!node) return;
    node.classList.remove('pf-release');
    void node.offsetWidth;                                  // restart animacji, gdy ktoś klika szybko
    node.classList.add('pf-release');
    node.addEventListener('animationend', (done) => { if (done.animationName === 'pf-release') node.classList.remove('pf-release'); }, { once: true });
    setTimeout(() => node.classList.remove('pf-release'), 600);   // pas bezpieczeństwa, gdy animationend nie nadejdzie (karta w tle)
  }, true);

  // ── wybuch serca ────────────────────────────────────────────────────────────────────────────────────────
  function burst(anchor) {
    if (motion === 'reduced' || !anchor || !anchor.getBoundingClientRect) return;
    const box = anchor.getBoundingClientRect(), node = document.createElement('div');
    node.className = 'pf-burst';
    node.style.left = box.left + box.width / 2 + 'px';
    node.style.top = box.top + box.height / 2 + 'px';
    node.append(document.createElement('b'));
    for (let i = 0; i < 7; i += 1) { const dot = document.createElement('i'); dot.style.setProperty('--a', i * (360 / 7) + 'deg'); node.append(dot); }
    document.body.append(node);
    setTimeout(() => node.remove(), 700);
  }
  feel.burst = burst;

  // ── toast „Cofnij” ──────────────────────────────────────────────────────────────────────────────────────
  let toastNode = null, toastTimer = 0;
  function dismissToast() {
    clearTimeout(toastTimer);
    const node = toastNode;
    toastNode = null;
    if (!node) return;
    node.classList.add('pf-undo-out');
    setTimeout(() => node.remove(), motion === 'reduced' ? 0 : 220);
  }
  /** toast({ text, action: "Cofnij", onAction, ms }) — jeden naraz; wstrzymuje się pod palcem/myszą. Zwraca { dismiss }. */
  function toast({ text, action, onAction, ms = 7000 } = {}) {
    dismissToast();
    const node = document.createElement('div');
    node.className = 'pf-undo';
    node.setAttribute('role', 'status');
    node.setAttribute('aria-live', 'polite');
    const label = document.createElement('span');
    label.textContent = text || '';
    node.append(label);
    if (action) {
      const button = document.createElement('button');
      button.type = 'button';
      button.textContent = action;
      button.addEventListener('click', () => { dismissToast(); try { onAction && onAction(); } catch (_) { /* akcja nie psuje strony */ } });
      node.append(button);
    }
    const close = document.createElement('button');
    close.type = 'button'; close.className = 'pf-undo-x'; close.textContent = '×'; close.setAttribute('aria-label', 'Zamknij');
    close.addEventListener('click', dismissToast);
    node.append(close);
    const arm = () => { clearTimeout(toastTimer); toastTimer = setTimeout(dismissToast, ms); };
    node.addEventListener('pointerenter', () => clearTimeout(toastTimer));
    node.addEventListener('pointerleave', arm);
    node.addEventListener('focusin', () => clearTimeout(toastTimer));
    node.addEventListener('focusout', arm);
    toastNode = node;
    document.body.append(node);
    // popover = warstwa najwyższa, więc toast jest widoczny także nad otwartym oknem modalnym (np. „Twoja kolejka”)
    try { if (typeof node.showPopover === 'function') { node.setAttribute('popover', 'manual'); node.showPopover(); } } catch (_) { /* bez popovera toast zostaje zwykłym elementem */ }
    arm();
    return { dismiss: dismissToast };
  }
  feel.toast = toast;

  // ── strażnik kontrastu akcentu z okładki ────────────────────────────────────────────────────────────────
  // library.js zapisuje --pf-now-accent w stylu <html>; tu liczymy z niego --pf-now-accent-safe (kolor z gwarantowanym kontrastem
  // względem panelu, do pasków i fali) oraz --pf-on-accent (czarny/biały tekst czytelny na samym akcencie). Obie zmienne płynnie
  // przechodzą razem z akcentem (patrz piterfy.css, `html { transition }`).
  let lastAccent = '';
  function guardAccent() {
    const accent = root.style.getPropertyValue('--pf-now-accent').trim();
    if (!accent || accent === lastAccent) return;
    lastAccent = accent;
    const light = root.hasAttribute('data-pf-light');
    const vars = core.accentVars(accent, light ? [255, 255, 255] : [18, 18, 18]);
    if (!vars) return;
    for (const name of Object.keys(vars)) root.style.setProperty(name, vars[name]);
  }
  try { new MutationObserver(guardAccent).observe(root, { attributes: true, attributeFilter: ['style'] }); } catch (_) { /* bez obserwatora strażnik działa przy odświeżeniu */ }

  // ── potwierdzenia i „Cofnij” dla kolejki ────────────────────────────────────────────────────────────────
  // Porównujemy kolejkę odtwarzacza przed i po zmianie. Komunikat pokazujemy tylko, gdy zmianę wywołał użytkownik (dotyk,
  // klawisz lub klik w ciągu ostatnich ~2,5 s) — dzięki temu uzupełnianie kolejki przez radio czy DJ-a nie sypie toastami.
  // Okno „Twoja kolejka” (library.js) ma własne „Cofnij usunięcie”, więc tam nic nie dublujemy.
  let lastInput = 0, previous = null, ignoreUntil = 0;
  for (const type of ['pointerdown', 'keydown', 'touchstart', 'click']) addEventListener(type, () => { lastInput = Date.now(); }, { capture: true, passive: true });
  const playerState = () => { try { return window.PiterfyLibrary && window.PiterfyLibrary.store && window.PiterfyLibrary.store().getState().player; } catch (_) { return null; } };
  const queueSnapshot = (player) => ({
    uuids: player.queue.map((track) => track.uuid), current: player.current ? player.current.uuid : null,
    items: player.queue.slice(), currentItem: player.current,
  });
  const titleOf = (track) => (track && ((track.song && track.song.title) || track.name || track.title)) || '';
  function restoreQueue(before) {
    const store = window.PiterfyLibrary.store(), player = store.getState().player;
    ignoreUntil = Date.now() + 2000;
    store.dispatch({ type: 'PLAYER_SYNC_QUEUE', data: { audioInfo: player.current && player.current.uuid ? player.current : before.currentItem, audioLists: before.items } });
    setTimeout(() => {
      const after = playerState();
      if (!after || after.queue.length !== before.items.length) toast({ text: 'Nie udało się cofnąć zmiany w kolejce.', ms: 4000 });
      else buzz('success');
    }, 400);
  }
  function watchQueue() {
    if (!undo) return;
    const player = playerState();
    if (!player || !Array.isArray(player.queue)) return;
    const now = queueSnapshot(player);
    if (!previous) { previous = now; return; }
    const same = previous.uuids.length === now.uuids.length && previous.uuids.every((id, i) => id === now.uuids[i]);
    const before = previous;
    previous = now;
    if (same || Date.now() - lastInput > 2500 || Date.now() < ignoreUntil) return;
    if (document.querySelector('.pf-queue-dialog[open]')) return;
    const diff = undo.queueDiff({ uuids: before.uuids, current: before.current }, { uuids: now.uuids, current: now.current });
    if (diff.kind === 'added-end' && window.PiterfyRadio && window.PiterfyRadio.active && window.PiterfyRadio.active()) return;
    const removedCurrent = diff.kind === 'removed' && diff.removed.includes(before.current);
    const track = diff.count === 1
      ? (diff.kind === 'removed' ? before.items.find((t) => t.uuid === diff.removed[0]) : now.items.find((t) => !before.uuids.includes(t.uuid)))
      : null;
    const message = undo.message(diff, titleOf(track));
    if (!message) return;
    buzz(diff.kind === 'removed' || diff.kind === 'cleared' ? 'warn' : 'success');
    toast({ text: message.text, action: message.undo && !removedCurrent ? 'Cofnij' : '', onAction: () => restoreQueue(before) });
  }
  setInterval(watchQueue, 350);

  // ── start ───────────────────────────────────────────────────────────────────────────────────────────────
  window.addEventListener('pf-prefs-changed', apply);
  window.addEventListener('storage', (event) => { if (!event.key || event.key === 'pf-haptics' || event.key === 'pf-motion' || event.key === 'pf-calm') apply(); });
  try { (reducedQuery.addEventListener || reducedQuery.addListener).call(reducedQuery, 'change', apply); } catch (_) { /* brak zdarzenia = odczyt przy następnym apply */ }
  window.PiterfyFeel = feel;
  apply();
  guardAccent();
})();
