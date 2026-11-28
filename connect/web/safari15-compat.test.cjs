/* Straż zgodności ze starym Safari (15.x, iPad mini 4): statyczny przegląd CSS i JS załadowanych w przeglądarce.
 * Nie zastępuje próby na prawdziwym iPadzie, ale wyłapuje to, co psuło się po cichu: zagnieżdżony CSS (Safari 16.5+), color-mix (16.2+) bez zapasowego
 * koloru, nowe API bez polifilla (AbortSignal.timeout 16, Object.hasOwn/at/findLast/randomUUID 15.4) i składnię z Safari 16+. */
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const DIR = __dirname;
const files = fs.readdirSync(DIR);
const css = files.filter((f) => f.endsWith(".css") && f !== "cast-receiver.css");   // odbiornik Cast działa na Chromium Chromecasta
const js = files.filter((f) => f.endsWith(".js") && !f.endsWith(".test.js") && f !== "cast-receiver.js" && f !== "cast-library.js" && f !== "playback-diagnostic.js");
const read = (f) => fs.readFileSync(path.join(DIR, f), "utf8");

/** Usuwa komentarze CSS, zostawiając napisy. */
const stripCssComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, "");

/** Znajduje reguły stylu zagnieżdżone w regule stylu (CSS Nesting). Zwraca opisy miejsc. */
function nestedRules(source) {
  const text = stripCssComments(source);
  const stack = [];   // "at" | "style" | "keyframes"
  const found = [];
  let start = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (c === '"' || c === "'") { const q = c; i++; while (i < text.length && text[i] !== q) { if (text[i] === "\\") i++; i++; } continue; }
    if (c === "(") { let d = 1; i++; while (i < text.length && d) { if (text[i] === "(") d++; else if (text[i] === ")") d--; i++; } i--; continue; }
    if (c === "{") {
      const prelude = text.slice(start, i).trim();
      const parent = stack[stack.length - 1];
      let kind;
      if (prelude.startsWith("@")) kind = /^@(-webkit-)?keyframes/i.test(prelude) ? "keyframes" : "at";
      else kind = parent === "keyframes" ? "frame" : "style";
      if (kind === "style" && parent === "style") found.push(prelude.slice(0, 70));
      stack.push(kind);
      start = i + 1;
    } else if (c === "}") { stack.pop(); start = i + 1; }
    else if (c === ";") { start = i + 1; }
  }
  return found;
}

test("żaden arkusz nie używa zagnieżdżonego CSS (Safari 16.5+) — piterfy.css jest spłaszczony", () => {
  css.forEach((f) => assert.deepEqual(nestedRules(read(f)), [], f + ": reguła zagnieżdżona w regule"));
});

test("wykrywacz zagnieżdżeń naprawdę łapie zagnieżdżenia (kontrola samego testu)", () => {
  assert.equal(nestedRules("a { & b { color: red; } }").length, 1);
  assert.equal(nestedRules("a { color: red; } @media (min-width: 1px) { b { color: red } } @keyframes x { from { top: 0 } 50% { top: 1px } }").length, 0);
  assert.equal(nestedRules("@media screen { a { b & c { x: y } } }").length, 1);
});

/** Bloki deklaracji (tekst między { }) z poziomu reguł stylu, bez zagnieżdżeń. */
function declarationBlocks(source) {
  const text = stripCssComments(source);
  const out = [];
  const re = /\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(text))) out.push(m[1]);
  return out;
}

const COLOR_MIX_EXEMPT = {
  "glass.css": "Liquid Glass jest opcjonalny (boot.js ładuje go tylko przy włączonym szkle), a bez color-mix szkło po prostu jest przezroczyste",
};
// kolory z okładki w piterfy.css (--pf-now-accent) to efekt dodatkowy: bez color-mix tło zostaje domyślne
const COLOR_MIX_ACCENT_OK = /--pf-now-accent/;

test("color-mix (Safari 16.2+) ma zapasową deklarację tej samej właściwości tuż przed sobą", () => {
  css.forEach((f) => {
    if (COLOR_MIX_EXEMPT[f]) return;
    declarationBlocks(read(f)).forEach((block) => {
      const decls = block.split(";").map((d) => d.trim()).filter(Boolean);
      decls.forEach((d, i) => {
        if (!/color-mix\(/.test(d)) return;
        if (f === "piterfy.css" && COLOR_MIX_ACCENT_OK.test(d)) return;
        const prop = d.slice(0, d.indexOf(":")).trim();
        if (prop.startsWith("--")) return;   // zmienna: wartość i tak jest używana w innym miejscu, które samo ma zapas
        const earlier = decls.slice(0, i).some((x) => x.slice(0, x.indexOf(":")).trim() === prop && !/color-mix\(/.test(x));
        assert.ok(earlier, f + ": color-mix bez zapasu w „" + d.slice(0, 90) + "”");
      });
    });
  });
});

test("lite.css nie używa niczego nowszego niż Safari 15", () => {
  const text = stripCssComments(read("lite.css"));
  [/color-mix\(/, /:has\(/, /\d(d|s|l)vh\b/, /@container/, /@layer/, /@scope/, /\binset\s*:/, /text-wrap/, /:popover-open/, /@property/, /view-transition/, /oklch|oklab|lab\(|lch\(/, /\bsubgrid\b/, /anchor-name/]
    .forEach((re) => assert.doesNotMatch(text, re, "lite.css: " + re));
  assert.deepEqual(nestedRules(read("lite.css")), []);
});

test("lite.css: każdy selektor jest pod html[data-pf-lite] albo należy do własnego odtwarzacza .pf-lite-*", () => {
  const text = stripCssComments(read("lite.css"));
  const re = /(^|\})\s*([^{}@]+)\{/g;
  let m;
  const bad = [];
  while ((m = re.exec(text))) {
    m[2].split(",").map((s) => s.trim()).filter(Boolean).forEach((sel) => {
      if (/^html\[data-pf-lite/.test(sel)) return;
      if (/^\.pf-lite-/.test(sel)) return;
      if (/^(from|to|\d+%)$/.test(sel)) return;
      bad.push(sel);
    });
  }
  assert.deepEqual(bad, [], "selektory poza trybem Lite");
});

// ── JS ──

/** Linie kodu bez pełnych komentarzy (przybliżenie wystarczające do wyszukiwania API). */
function codeLines(source) {
  return source.split("\n").map((line, i) => [i + 1, line]).filter(([, line]) => !/^\s*(\/\/|\*|\/\*)/.test(line));
}

const FORBIDDEN = [
  [/\bstructuredClone\s*\(/, "structuredClone (Safari 15.4)"],
  [/\(\?<[=!]/, "regex lookbehind (Safari 16.4)"],
  [/\bArray\.fromAsync\b|\.toSorted\(|\.toReversed\(|\.toSpliced\(|Promise\.withResolvers|Object\.groupBy|Map\.groupBy/, "metody z Safari 16+/17+"],
  [/\bAbortSignal\.any\b/, "AbortSignal.any (Safari 17.4)"],
  [/\bIntl\.Segmenter\b|\bIntl\.DurationFormat\b/, "Intl z Safari 15.4+/18"],
  [/^\s*static\s*\{|\bstatic\s+#|^\s+#[a-zA-Z_]+\s*[=;(]/, "pola prywatne/bloki static klas (Safari 16.4)"],
  [/\bimport\.meta\b|^await\s/, "import.meta / top-level await"],
  [/\bnew\s+CSSStyleSheet\b|\badoptedStyleSheets\b/, "constructable stylesheets (Safari 16.4)"],
  [/\b(?:window\.)?requestIdleCallback\b(?!\s*\))/, "requestIdleCallback (Safari go nie ma)"],
];

test("żaden moduł nie używa API ani składni z Safari 16+ bez polifilla", () => {
  js.forEach((f) => {
    codeLines(read(f)).forEach(([n, line]) => {
      FORBIDDEN.forEach(([re, what]) => assert.doesNotMatch(line, re, f + ":" + n + " używa " + what + ": " + line.trim().slice(0, 100)));
    });
  });
});

// API, których brak w Safari 15.x łata compat-core.js; każde użycie wymaga, żeby compat-core je znał i żeby boot.js go ładował
const POLYFILLED = [
  [/\bAbortSignal\.timeout\b/, "AbortSignal.timeout"],
  [/\bObject\.hasOwn\b/, "Object.hasOwn"],
  [/\.at\(\s*-?\d/, "Array.at"],
  [/\.findLast(Index)?\(/, "Array.findLast"],
  [/\brandomUUID\b/, "crypto.randomUUID"],
];

test("API łatane przez compat-core.js: każde używane w kodzie jest na jego liście, a boot.js ładuje go przed resztą", () => {
  const compat = read("compat-core.js");
  const boot = read("boot.js");
  const used = new Set();
  js.filter((f) => f !== "compat-core.js" && f !== "boot.js").forEach((f) => {
    codeLines(read(f)).forEach(([, line]) => POLYFILLED.forEach(([re, name]) => { if (re.test(line)) used.add(name); }));
  });
  assert.ok(used.has("AbortSignal.timeout"), "kontrola testu: AbortSignal.timeout jest w kodzie");
  used.forEach((name) => assert.ok(compat.includes('"' + name + '"'), "compat-core.js nie łata " + name));
  // boot.js sprawdza każdą z tych funkcji i dopisuje compat-core.js synchronicznie
  ["AbortSignal.timeout", "Object.hasOwn", "Array.prototype.at", "Array.prototype.findLast", "randomUUID"].forEach((probe) => assert.ok(boot.includes(probe), "boot.js nie sprawdza " + probe));
  assert.match(boot, /document\.write\('<script src="\/piterfy-ui\/compat-core\.js/);
});

// funkcje, których nie ma w Safari 15, ale kod ma je za strażnikiem — sprawdzamy, że strażnik jest w tej samej linii albo tuż obok
const GUARDED = [
  [/document\.startViewTransition\s*\(/, /typeof document\.startViewTransition|startViewTransition\b.*===\s*"function"/, "startViewTransition"],
  [/\.showPopover\s*\(/, /typeof node\.showPopover|typeof [a-z.]*showPopover/, "showPopover"],
];

test("funkcje nowszych przeglądarek są użyte tylko za strażnikiem typeof", () => {
  js.forEach((f) => {
    const source = read(f);
    GUARDED.forEach(([use, guard, name]) => {
      if (use.test(source)) assert.match(source, guard, f + ": " + name + " bez strażnika typeof");
    });
  });
});

test("piterfy.js pomija ciężkie moduły w trybie Lite i ładuje lite.js zawsze", () => {
  const source = read("piterfy.js");
  ["piterfy-canvas-loader", "piterfy-morph-loader", "piterfy-glass-loader", "piterfy-feel-loader", "piterfy-crossfade-loader"].forEach((name) => {
    const start = source.indexOf("// " + name + "\n");
    assert.ok(start >= 0, name);
    const block = source.slice(start, start + 400);
    assert.match(block, /data-pf-lite/, name + " nie sprawdza trybu Lite");
  });
  assert.match(source, /\/\/ piterfy-lite-loader\n[\s\S]*lite-core\.js[\s\S]*lite\.js/);
  assert.match(source, /hasAttribute\('data-pf-lite'\) \? \["radio\.js"\]/);   // radar (obrazki) pomijany w Lite
});

test("moduły dokładane przez loadery są wczytywane niezależnie: błąd jednego nie blokuje pozostałych", () => {
  const source = read("piterfy.js");
  // loader „feel” używa Promise.all z onerror=resolve, a „browse” osobnych wywołań load(): żaden nie ma wspólnego łańcucha po błędzie
  assert.match(source, /el\.onload = el\.onerror = resolve/);
  const browse = source.slice(source.indexOf("// piterfy-browse-loader\n"), source.indexOf("// piterfy-browse-loader-end"));
  assert.ok((browse.match(/load\(/g) || []).length >= 4);
  // żaden z loaderów nie jest w try/catch ani nie rzuca: każdy to osobny IIFE zaczynający się od „;(() =>”
  assert.ok((source.match(/^;\(\(\) => \{/gm) || []).length >= 8);
});
