/* Piterfy — gesty w „Teraz gra” i na mini-pasku: czysta logika decyzji (bez DOM, testy: gesture-core.test.cjs).
 * gestures.js zbiera dotyk, a tu zapada decyzja: w którą stronę gest się „zablokował”, czy puszczony palec zatwierdza
 * zmianę utworu, jak mocno okładka stawia opór na końcu kolejki i czy dwa dotknięcia to podwójne tapnięcie z przewinięciem. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyGestureCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  const SLOP = 8;              // px, zanim gest w ogóle uznamy za przeciąganie
  const AXIS_BIAS = 1.2;       // o ile jedna oś musi przeważać nad drugą, żeby się „zablokować”
  const COMMIT_RATIO = 0.28;   // przeciągnięcie na tyle szerokości okładki zatwierdza zmianę utworu
  const FLICK_SPEED = 0.45;    // px/ms: szybkie machnięcie zatwierdza nawet krótkie przeciągnięcie
  const FLICK_MIN = 24;        // px: ale nie krótsze niż to
  const SEEK_STEP = 10;        // s przy podwójnym tapnięciu
  const DOUBLE_TAP_MS = 300;
  const DOUBLE_TAP_DIST = 48;

  /** Oś gestu po przekroczeniu progu: "x", "y" albo null (jeszcze za wcześnie / niejednoznacznie). */
  function axisLock(dx, dy, slop = SLOP) {
    const ax = Math.abs(dx), ay = Math.abs(dy);
    if (Math.max(ax, ay) < slop) return null;
    if (ax > ay * AXIS_BIAS) return "x";
    if (ay > ax * AXIS_BIAS) return "y";
    return null;
  }

  /**
   * Co robi puszczenie palca po przeciąganiu w poziomie: "next" (w lewo), "prev" (w prawo) albo "cancel" (wróć na miejsce).
   * `canNext`/`canPrev` mówią, czy jest dokąd przejść; bez tego okładka tylko się sprężynuje z powrotem.
   */
  function releaseHorizontal({ dx, dt, width, canNext = true, canPrev = true }) {
    const w = Math.max(1, width || 1), dist = Math.abs(dx), speed = dist / Math.max(1, dt || 1);
    const far = dist >= w * COMMIT_RATIO, flick = speed >= FLICK_SPEED && dist >= FLICK_MIN;
    if (!(far || flick)) return "cancel";
    if (dx < 0) return canNext ? "next" : "cancel";
    return canPrev ? "prev" : "cancel";
  }

  /**
   * Co robi puszczenie palca po przeciąganiu w pionie: w dół w pełnym odtwarzaczu = "minimise", w górę na mini-pasku = "open".
   * Pionowo wystarczy krótsze przeciągnięcie (ekran jest wysoki), ale gest musi być wyraźnie pionowy.
   */
  function releaseVertical({ dx, dy, dt, height, direction = "down" }) {
    const sign = direction === "down" ? 1 : -1, along = dy * sign;
    if (along <= 0 || Math.abs(dx) > along * 0.8) return "cancel";
    const h = Math.max(1, height || 1), speed = along / Math.max(1, dt || 1);
    const far = along >= h * 0.18, flick = speed >= 0.55 && along >= 40;
    if (!(far || flick)) return "cancel";
    return direction === "down" ? "minimise" : "open";
  }

  /**
   * Przesunięcie okładki pod palcem: 1:1, gdy jest dokąd przejść, a na końcu kolejki gumowy opór (im dalej, tym ciężej,
   * z górną granicą). dx > 0 = ciągniemy w prawo (poprzedni), dx < 0 = w lewo (następny).
   */
  function rubber(dx, { canPrev = true, canNext = true, limit = 90 } = {}) {
    const blocked = dx > 0 ? !canPrev : !canNext;
    if (!blocked) return dx;
    const sign = dx < 0 ? -1 : 1, x = Math.abs(dx);
    return sign * limit * (1 - Math.exp(-x / limit)) * 0.55;
  }

  /** Strefa dotknięcia okładki: lewe 40% = "left", prawe 40% = "right", środek = "center". */
  function tapZone(x, width) {
    const w = Math.max(1, width || 1), r = x / w;
    if (r < 0.4) return "left";
    if (r > 0.6) return "right";
    return "center";
  }

  /**
   * Czy dotknięcie `tap` ({x, at, zone}) po `prev` jest podwójnym tapnięciem w tej samej bocznej strefie. Zwraca przesunięcie
   * w sekundach (−10 / +10) albo 0.
   */
  function doubleTapSeek(prev, tap) {
    if (!prev || !tap || tap.zone === "center" || prev.zone !== tap.zone) return 0;
    if (tap.at - prev.at > DOUBLE_TAP_MS || tap.at < prev.at) return 0;
    if (Math.hypot(tap.x - prev.x, (tap.y || 0) - (prev.y || 0)) > DOUBLE_TAP_DIST) return 0;
    return tap.zone === "left" ? -SEEK_STEP : SEEK_STEP;
  }

  /** Nowa pozycja po przewinięciu, w granicach utworu (duration może być nieznane → tylko dolna granica). */
  function seekTarget(current, delta, duration) {
    const next = Math.max(0, (Number(current) || 0) + delta);
    return Number.isFinite(duration) && duration > 0 ? Math.min(next, Math.max(0, duration - 0.25)) : next;
  }

  /** Czas animacji dokończenia gestu (ms): im szybsze machnięcie, tym krócej, w granicach 140–320. */
  function settleMs(remainingPx, speedPxPerMs) {
    const v = Math.max(0.2, Math.abs(speedPxPerMs) || 0.2);
    return Math.round(Math.min(320, Math.max(140, remainingPx / v)));
  }

  return { SLOP, COMMIT_RATIO, FLICK_SPEED, SEEK_STEP, DOUBLE_TAP_MS, axisLock, releaseHorizontal, releaseVertical, rubber, tapZone,
    doubleTapSeek, seekTarget, settleMs };
});
