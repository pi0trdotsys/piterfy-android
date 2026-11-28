/* Piterfy — „Cofnij” i potwierdzenia dla kolejki: czysta logika (bez DOM, testy: undo-core.test.cjs).
 * feel.js porównuje kolejkę odtwarzacza przed i po każdej zmianie. Z samej różnicy (kto zniknął, kto doszedł, gdzie) wynika,
 * czy użytkownik usunął utwory, wyczyścił kolejkę, dodał „jako następne” albo „na koniec” — i jaki komunikat pokazać.
 * Wymiana kolejki (nowy album, nowa playlista), przestawienie utworów i drobne zmiany stanu nie dają żadnego komunikatu. */
(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  else root.PiterfyUndoCore = api;
})(typeof window !== "undefined" ? window : globalThis, () => {
  /** Czy `small` jest podciągiem `big` (te same elementy w tej samej kolejności, być może z przerwami). */
  function isSubsequence(small, big) {
    let at = 0;
    for (const item of big) if (at < small.length && item === small[at]) at += 1;
    return at === small.length;
  }

  /**
   * prev / next: {uuids: [...], current: uuid|null} (kolejka w kolejności odtwarzania i uuid grającego utworu).
   * Zwraca {kind, count, ...}: "none" | "removed" | "cleared" | "added-next" | "added-end" | "added" | "moved" | "replaced".
   */
  function queueDiff(prev, next) {
    const a = (prev && prev.uuids) || [], b = (next && next.uuids) || [];
    if (a.length === b.length && a.every((id, i) => id === b[i])) return { kind: "none", count: 0 };
    if (!a.length) return { kind: "replaced", count: b.length };
    if (!b.length) return a.length >= 2 ? { kind: "cleared", count: a.length } : { kind: "replaced", count: 0 };
    if (b.length < a.length && isSubsequence(b, a)) {
      const keep = new Set(b);
      return { kind: "removed", count: a.length - b.length, removed: a.filter((id) => !keep.has(id)) };
    }
    if (b.length > a.length && isSubsequence(a, b)) {
      const had = new Set(a), added = b.map((id, i) => (had.has(id) ? -1 : i)).filter((i) => i >= 0);
      const current = a.indexOf(prev.current);
      const firstAfter = current >= 0 ? current + 1 : 0;
      const contiguous = added.every((pos, i) => pos === added[0] + i);
      if (contiguous && added[0] === b.length - added.length) return { kind: "added-end", count: added.length };
      if (contiguous && added[0] === firstAfter) return { kind: "added-next", count: added.length };
      return { kind: "added", count: added.length };
    }
    if (a.length === b.length && isSubsequence([...a].sort(), [...b].sort()) && [...a].sort().every((id, i) => id === [...b].sort()[i])) {
      return { kind: "moved", count: 0 };
    }
    return { kind: "replaced", count: b.length };
  }

  /** Polska odmiana: 1 utwór, 2–4 utwory (poza 12–14), reszta utworów. */
  function songs(n) {
    const last = n % 10, teen = n % 100 >= 12 && n % 100 <= 14;
    return n + " " + (n === 1 ? "utwór" : last >= 2 && last <= 4 && !teen ? "utwory" : "utworów");
  }

  /** Komunikat i czy ma przycisk „Cofnij” dla danej różnicy; null = bez komunikatu. `title` to tytuł pojedynczego utworu. */
  function message(diff, title) {
    const one = diff.count === 1 && title ? "„" + title + "”" : songs(diff.count);
    switch (diff.kind) {
      case "removed": return { text: "Usunięto z kolejki: " + one, undo: true };
      case "cleared": return { text: "Wyczyszczono kolejkę (" + songs(diff.count) + ")", undo: true };
      case "added-next": return { text: "Zagrane jako następne: " + one, undo: true };
      case "added-end": return { text: "Dodano na koniec kolejki: " + one, undo: true };
      case "added": return { text: "Dodano do kolejki: " + one, undo: true };
      default: return null;
    }
  }

  return { isSubsequence, queueDiff, songs, message };
});
