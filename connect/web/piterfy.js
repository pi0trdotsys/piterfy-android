/* Piterfy — dodatki do interfejsu Navidrome. Caddy wstrzykuje do strony krótki skrypt, który ładuje ten plik
 * z numerem wersji zmieniającym się co 10 minut (Cloudflare każe przeglądarce trzymać .js i .css 4 godziny,
 * a tak zmiany docierają najpóźniej po 10 minutach). Ten plik dokłada:
 * nazwę i logo Piterfy wszędzie, gdzie widać „Navidrome” (tytuł karty, ikona, logowanie, napisy),
 * logo i pozycje „Start”, „Ulubione”, „Podcasty” w panelu bocznym, stronę startową w stylu Spotify (#/start),
 * stronę „Ulubione” (#/ulubione: tylko polubiona muzyka, odtwarzanie i losowo) i „Podcasty” (#/podcasty);
 * podcasty są wycinane z widoków muzycznych,
 * przycisk „Mój gust” w górnym pasku, zdjęcie profilowe (menu użytkownika), plakietkę jakości dźwięku
 * na pasku odtwarzania, znacznik bieżącego widoku (do stylów) i tło nagłówka albumu/wykonawcy z jego okładki.
 * Klasy komponentów Navidrome są w produkcji losowe (jss123), więc opieramy się na stałych klasach
 * Material UI, identyfikatorach react-admin i klasach odtwarzacza. */
(function () {
  const NAME = "Piterfy";
  const GUST_URL = "/gust/";
  const UI = "/piterfy-ui/";
  const HOME = "#/start";
  const root = document.documentElement;
  const installed = matchMedia("(display-mode: standalone)").matches;
  const script = document.currentScript;
  const VERSION = ((script && script.src.match(/[?&]v=([^&]+)/)) || [])[1];

  const LOGO = '<svg viewBox="0 0 30 30" aria-hidden="true"><circle cx="15" cy="15" r="15" fill="#1db954"></circle>' +
    '<path d="M9 19v-6M13 21V9M17 18v-7M21 16v-3" stroke="#000" stroke-width="2.4" stroke-linecap="round"></path></svg>';
  const SPARKLE = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">' +
    '<path d="M12 3l1.8 4.7 4.7 1.8-4.7 1.8L12 16l-1.8-4.7-4.7-1.8 4.7-1.8z"></path></svg>';
  const HOME_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">' +
    '<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6h-6v6H4a1 1 0 0 1-1-1z"></path></svg>';
  const PLAY = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z" fill="currentColor"></path></svg>';
  const HEART = '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 21s-7.5-4.6-9.6-9.1C.9 8.6 2.9 4.5 6.7 4.5c2.2 0 3.6 1.2 4.4 2.4h1.8c.8-1.2 2.2-2.4 4.4-2.4 3.8 0 5.8 4.1 4.3 7.4C19.5 16.4 12 21 12 21z"></path></svg>';

  const HEART_LINE = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">' +
    '<path d="M12 20.5s-7-4.3-9-8.5C1.6 8.9 3.5 5 7 5c2 0 3.4 1.1 5 3 1.6-1.9 3-3 5-3 3.5 0 5.4 3.9 4 7-2 4.2-9 8.5-9 8.5z"></path></svg>';
  const MIC = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<rect x="9" y="3" width="6" height="11" rx="3"></rect><path d="M5 11a7 7 0 0 0 14 0M12 18v3M9 21h6"></path></svg>';
  const SHUFFLE = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5"></path></svg>';

  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const plural = (n, one, few, many) => n + " " + (n === 1 ? one
    : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 12 || n % 100 > 14) ? few : many);

  function addStyles() {
    // Na końcu <body>, żeby wygrywał ze stylami motywu, które Material UI dopisuje do <head>.
    if (document.getElementById("pf-css")) return;
    const link = document.createElement("link");
    link.id = "pf-css";
    link.rel = "stylesheet";
    link.href = UI + "piterfy.css" + (VERSION ? "?v=" + VERSION : "");
    document.body.appendChild(link);
  }

  function markTheme() {
    // Nasz wygląd jest dla ciemnych motywów; przy jasnym motywie Navidrome zostawiamy go w spokoju.
    // Kolor tekstu motywu ma główny kontener react-admin (.layout), nie <body> — i tego koloru nie zmieniamy,
    // więc odczyt nie zależy od naszego CSS. Na ekranie logowania (bez .layout) zostaje poprzednia decyzja.
    const layout = document.querySelector(".layout");
    if (!layout) return;
    const [r, g, b] = (getComputedStyle(layout).color.match(/\d+/g) || [255, 255, 255]).map(Number);
    root.toggleAttribute("data-pf-light", (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.5);
  }

  function markRoute() {
    // „#/album/<id>/show” → album-show; „#/album/recentlyPlayed?…” → album; „#/start” → start
    const parts = location.hash.replace(/^#\/?/, "").split("?")[0].split("/").filter(Boolean);
    root.dataset.pfRoute = !parts.length ? "home" : parts[parts.length - 1] === "show" ? parts[0] + "-show" : parts[0];
  }

  // ── marka: tytuł, ikony, meta, logowanie, napisy ──
  function setAttr(el, name, value) {
    if (el && el.getAttribute(name) !== value) el.setAttribute(name, value);
  }

  function rebrandHead() {
    // Zainstalowana aplikacja sama dopisuje „Piterfy” do tytułu okna — tam nazwę z tytułu usuwamy,
    // a sam „Navidrome” (ustawiany przez odtwarzacz na koniec utworu) zamieniamy na „Piterfy”.
    const title = installed
      ? document.title.replace(/\s*-\s*Navidrome|Navidrome\s*-\s*/g, "").replace(/^\s*Navidrome\s*$/, "").trim() || NAME
      : document.title.replace(/Navidrome/g, NAME);
    // Tylko przy faktycznej zmianie: każde przypisanie tytułu (nawet tego samego) to nowa zmiana w <head>,
    // na którą ta funkcja znów się uruchamia — bez tego warunku strona wpadała w nieskończoną pętlę.
    if (title !== document.title) document.title = title;
    document.querySelectorAll('link[rel~="icon"]').forEach((l) => {
      setAttr(l, "href", UI + "logo.svg");
      setAttr(l, "type", "image/svg+xml");
    });
    document.querySelectorAll('link[rel="apple-touch-icon"]').forEach((l) => setAttr(l, "href", UI + "apple-touch-icon.png"));
    document.querySelectorAll('meta[name="application-name"], meta[name="apple-mobile-web-app-title"]')
      .forEach((m) => setAttr(m, "content", NAME));
    document.querySelectorAll('meta[name="theme-color"]').forEach((m) => setAttr(m, "content", "#000000"));
  }

  function cleanTitleBar() {
    // Górny pasek pokazuje „Navidrome - Albumy - Wszystkie”; jak w Spotify zostawiamy samą nazwę widoku.
    const title = document.getElementById("react-admin-title");
    const walker = title && document.createTreeWalker(title, NodeFilter.SHOW_TEXT);
    while (walker && walker.nextNode()) {
      const text = walker.currentNode.nodeValue.replace(/^\s*(Navidrome|Piterfy)\s*-\s*/, "");
      if (text !== walker.currentNode.nodeValue) walker.currentNode.nodeValue = text;
    }
  }

  function rebrandText(scope) {
    if (scope.nodeType === Node.TEXT_NODE) {
      if (scope.nodeValue.includes("Navidrome") && !/^(SCRIPT|STYLE)$/.test(scope.parentNode && scope.parentNode.nodeName)) {
        scope.nodeValue = scope.nodeValue.replace(/Navidrome/g, NAME);
      }
      return;
    }
    if (scope.nodeType !== Node.ELEMENT_NODE) return;
    const walker = document.createTreeWalker(scope, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => (n.nodeValue.includes("Navidrome") && !/^(SCRIPT|STYLE)$/.test(n.parentNode.nodeName)
        ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach((n) => { n.nodeValue = n.nodeValue.replace(/Navidrome/g, NAME); });
  }

  let loginScene;

  function mountLogin(card) {
    const scene = document.createElement("div");
    scene.className = "pf-login-scene";
    scene.setAttribute("aria-hidden", "true");
    const hour = new Date().getHours();
    const greeting = hour >= 5 && hour < 18 ? "Dzień dobry!" : "Dobry wieczór!";
    scene.innerHTML = `<div class="pf-login-copy"><span>PITERFY</span>
      <strong>${greeting}<br> Czas na muzykę.</strong></div>
      <svg viewBox="0 0 380 235" focusable="false">
        <ellipse cx="192" cy="217" rx="156" ry="13" fill="#02170c" opacity=".45"/>
        <path class="pf-login-spark" d="M46 81v18m-9-9h18m278 4v22m-11-11h22m-35-67v14m-7-7h14"
          fill="none" stroke="#afffd2" stroke-width="3" stroke-linecap="round"/>
        ${[
          ["#27c965", "M105 204l-8-116q-2-36 22-59t57-16l42 10-27 181z", 158, 70],
          ["#0e8b50", "M168 204l13-114q3-23 25-24l55-2q20 0 17 23l-19 117z", 228, 108],
          ["#b2f8a4", "M252 206l15-65q8-37 38-37 37 0 37 38 0 24-16 64z", 302, 146],
          ["#66ed8e", "M36 208q1-43 32-66 28-22 67-17 49 5 65 41 10 23 2 42z", 122, 170],
        ].map(([color, body, x, y], i) => `<g class="pf-login-lean" data-x="${x}" data-y="${y}">
          <g class="pf-login-creature" style="--i:${i}">
          <path d="${body}" fill="${color}"/>
          <g class="pf-login-face" transform="translate(${x} ${y})" fill="#062516" stroke="#062516">
            <g class="pf-login-eyes">
              <g class="pf-login-blink"><ellipse cx="-10" rx="4" ry="4"/><ellipse cx="10" rx="4" ry="4"/></g>
              <path class="pf-login-brows" d="M-15-8q5-5 10 0m10 0q5-5 10 0" fill="none" stroke-width="2" stroke-linecap="round"/>
            </g>
            <path class="pf-login-smile" d="M-4 10q4 5 8 0" fill="none" stroke-width="2.5" stroke-linecap="round"/>
            <path class="pf-login-grin" d="M-7 9q7 2 14 0q-2 9-7 9t-7-9z"/>
            <path class="pf-login-hush" d="M-2 8q-4 5 1 7q5-1 2-6z"/>
            <path class="pf-login-frown" d="M-4 13q4-5 8 0" fill="none" stroke-width="2.5" stroke-linecap="round"/>
            <path class="pf-login-cheer" d="M-6 10h12q-6 12-12 0z" stroke-linejoin="round"/>
          </g>
        </g></g>`).join("")}
      </svg><span class="pf-login-caption"></span>`;
    const host = card.parentElement;
    const form = card.closest("form");
    host.classList.add("pf-login-layout");
    card.classList.add("pf-login-card");
    card.before(scene);

    const labels = {
      idle: "Wszyscy gotowi do słuchania.",
      waiting: "Chwila, łączymy się…",
      denied: "Nie tym razem. Spróbuj jeszcze raz.",
      unavailable: "Coś przerwało połączenie.",
      success: "Jesteś! Czas na muzykę.",
    };
    function state(value) {
      scene.dataset.state = value;
      scene.querySelector(".pf-login-caption").textContent = labels[value];
    }
    state("idle");
    const svg = scene.querySelector("svg");
    const creatures = [...svg.querySelectorAll(".pf-login-lean")].map(body => ({
      body, x: Number(body.dataset.x), y: Number(body.dataset.y),
    }));
    const listeners = new AbortController();
    let frame = 0;
    let pointer;
    function aim() {
      frame = 0;
      if (!pointer) return;
      const matrix = svg.getScreenCTM();
      if (!matrix) return;
      const point = pointer.matrixTransform(matrix.inverse());
      creatures.forEach(({ body, x, y }) => {
        const dx = point.x - x;
        const dy = point.y - y;
        const distance = Math.max(38, Math.hypot(dx, dy));
        body.style.setProperty("--look-x", 12 * dx / distance + "px");
        body.style.setProperty("--look-y", 12 * dy / distance + "px");
        body.style.setProperty("--lean", 11 * dx / distance + "deg");
      });
    }
    function scheduleAim() {
      if (!frame) frame = requestAnimationFrame(aim);
    }
    window.addEventListener("pointermove", ({ clientX, clientY, pointerType }) => {
      if (pointerType !== "mouse" && pointerType !== "pen") return;
      pointer = new DOMPoint(clientX, clientY);
      scheduleAim();
    }, { capture: true, passive: true, signal: listeners.signal });
    window.addEventListener("resize", scheduleAim, { signal: listeners.signal });
    window.addEventListener("scroll", scheduleAim, { passive: true, signal: listeners.signal });
    function reactToField(field) {
      scene.dataset.field = form.contains(field) && field.matches('input[name="username"], input[type="password"]')
        ? (field.type === "password" ? "password" : "username") : "";
    }
    reactToField(document.activeElement);
    form.addEventListener("focusin", ({ target }) => reactToField(target), { signal: listeners.signal });
    form.addEventListener("focusout", ({ relatedTarget }) => reactToField(relatedTarget), { signal: listeners.signal });
    form.addEventListener("input", () => {
      if (["denied", "unavailable"].includes(scene.dataset.state)) state("idle");
    }, { signal: listeners.signal });

    const nativeFetch = window.fetch;
    let attempt = 0;
    // Observe only the authentication result. The original request and response go to Navidrome unchanged.
    function observeLogin(input, options) {
      const response = nativeFetch.apply(this, arguments);
      const request = input instanceof Request ? input : null;
      const method = options?.method ?? request?.method;
      if (String(method).toUpperCase() !== "POST" || !card.isConnected) return response;
      let url;
      try { url = new URL(request?.url ?? input, location.href); } catch (_) { return response; }
      if (url.origin !== location.origin || !/\/auth\/(login|createAdmin)$/.test(url.pathname)) return response;
      const current = ++attempt;
      state("waiting");
      response.then((result) => {
        if (current !== attempt) return;
        state(result.ok ? "success" : [401, 403].includes(result.status) ? "denied" : "unavailable");
      }, () => { if (current === attempt) state("unavailable"); });
      return response;
    }
    window.fetch = observeLogin;
    return {
      card,
      dispose(celebrate) {
        attempt++;
        listeners.abort();
        cancelAnimationFrame(frame);
        if (window.fetch === observeLogin) window.fetch = nativeFetch;
        host.classList.remove("pf-login-layout");
        card.classList.remove("pf-login-card");
        if (celebrate && scene.dataset.state === "success") {
          scene.classList.add("pf-login-finale");
          document.body.appendChild(scene);
          setTimeout(() => scene.remove(), 1900);
        } else scene.remove();
      },
    };
  }

  function rebrandLogin() {
    const onLogin = root.dataset.pfRoute === "login";
    const card = onLogin && document.querySelector('#root form input[name="password"]')?.closest(".MuiCard-root");
    if (loginScene && loginScene.card !== card) {
      loginScene.dispose(!onLogin);
      loginScene = null;
    }
    if (!card) return;
    setAttr(card.querySelector('img[alt="logo"]'), "src", UI + "logo.svg");
    if (!loginScene) loginScene = mountLogin(card);
  }

  // ── panel boczny: logo i „Start” nad menu ──
  // Muzyka i podcasty to osobne światy: „Ulubione” zawiera tylko polubioną muzykę, „Podcasty” tylko audycje.
  const NAV = [["start", HOME, "Start", HOME_ICON], ["ulubione", "#/ulubione", "Ulubione", HEART_LINE], ["podcasty", "#/podcasty", "Podcasty", MIC]];

  function sidebar() {
    document.querySelectorAll(".MuiDrawer-paper").forEach((paper) => {
      if (!paper.querySelector(".pf-brand")) {
        // react-admin trzyma menu w bloku z position: fixed (pierwsze dziecko panelu) — logo musi
        // trafić do tego bloku, nad menu; wstawione obok niego zostaje pod menu i na nie najeżdża.
        const first = paper.firstElementChild;
        const host = first && getComputedStyle(first).position === "fixed" ? first : paper;
        const brand = document.createElement("a");
        brand.className = "pf-brand";
        brand.href = HOME;
        brand.setAttribute("aria-label", NAME + " — strona startowa");
        brand.innerHTML = LOGO + "<span>" + NAME + "</span>";
        const links = NAV.map(([name, href, label, icon]) => {
          const a = document.createElement("a");
          a.className = "pf-start";
          a.dataset.pfNav = name;
          a.href = href;
          a.innerHTML = icon + "<span>" + label + "</span>";
          return a;
        });
        host.prepend(brand, ...links);
        // zwinięty panel boczny (same ikony): bez napisów
        new ResizeObserver(() => {
          const compact = paper.getBoundingClientRect().width < 120;
          host.querySelectorAll(".pf-brand, .pf-start").forEach((el) => el.classList.toggle("pf-compact", compact));
        }).observe(paper);
      }
      paper.querySelectorAll(".pf-start").forEach((link) => {
        if (link.dataset.pfNav === root.dataset.pfRoute) setAttr(link, "aria-current", "page");
        else if (link.hasAttribute("aria-current")) link.removeAttribute("aria-current");
      });
    });
  }

  // ── przycisk „Mój gust” i nagłówek z okładki ──
  function gustButton() {
    const toolbar = document.querySelector("header.MuiAppBar-root .MuiToolbar-root");
    if (!toolbar || toolbar.querySelector(".pf-gust")) return;
    const userMenu = Array.from(toolbar.children).find((el) => el.querySelector('button[aria-haspopup="true"]'));
    const link = document.createElement("a");
    link.className = "pf-gust";
    link.href = GUST_URL;
    link.target = "_blank";  // nowa karta — muzyka gra dalej
    link.rel = "noopener";
    link.title = "Mój gust — podsumowanie tego, czego słuchasz (otwiera się w nowej karcie)";
    link.innerHTML = SPARKLE + "<span>Mój gust</span>";
    toolbar.insertBefore(link, userMenu || null);
  }

  function heroCover() {
    if (!/-show$/.test(root.dataset.pfRoute || "")) return;
    const card = document.querySelector("#main-content .MuiCard-root");
    const img = card && card.querySelector("img");
    const value = img && img.currentSrc ? `url("${img.currentSrc}")` : "";
    if (card && value && card.style.getPropertyValue("--pf-hero") !== value) {
      card.classList.add("pf-hero");
      card.style.setProperty("--pf-hero", value);
    }
  }

  // ── rzeczywista jakość dźwięku na pasku odtwarzania (jak „Bezstratna” w Spotify, ale z liczbami) ──
  // Trzy źródła: dane pliku (getSong), analiza widma pliku (spectrum.py w kontenerze beets, przez
  // /piterfy-api/quality — wykrywa FLAC-i zrobione z MP3 i podbite MP3) oraz decyzja serwera o transkodowaniu
  // z ukrytej plakietki Navidrome („FLAC → OPUS 128”), którą Navidrome rysuje tylko na szerokim ekranie.
  const API = "/piterfy-api/";
  const LOSSLESS = new Set(["flac", "alac", "wav", "aif", "aiff", "ape", "wv", "tta", "dsf", "dff"]);
  const WAVES = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">' +
    '<path d="M4 10v4M8 7v10M12 4v16M16 7v10M20 10v4"></path></svg>';
  const WARN = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M12 4l9 16H3z"></path><path d="M12 10v4M12 17v.5"></path></svg>';
  const quality = { src: "", song: null, spectrum: undefined, details: null };
  const khz = (hz) => (hz / 1000).toLocaleString("pl-PL", { maximumFractionDigits: 1 });

  function playingTrack() {
    const audio = document.querySelector("audio.music-player-audio, .react-jinke-music-player-main audio");
    const src = audio && audio.getAttribute("src");
    if (!src) return null;
    const url = new URL(src, location.href);
    const id = url.searchParams.get("id") || url.searchParams.get("mediaId");
    return id ? { id: id, src: src } : null;
  }

  function serverTranscoding() {
    const chip = document.querySelector(".react-jinke-music-player-main .audio-title .MuiChip-label");
    if (!chip) return undefined;  // nie wiadomo (wąski ekran)
    const m = chip.textContent.match(/→\s*(\S+)(?:\s+(\d+))?/);
    return m ? { codec: m[1], kbps: Number(m[2]) || 0 } : null;
  }

  function tier(kbps, codec) {
    const effective = kbps * (/opus|aac|m4a|mp4|ogg|vorbis/i.test(codec) ? 1.5 : 1);  // nowsze kodeki brzmią lepiej przy tym samym bitrate
    return effective >= 256 ? "Bardzo wysoka" : effective >= 160 ? "Wysoka" : effective >= 96 ? "Normalna" : "Niska";
  }

  // s — dane pliku z getSong, spec — analiza widma (null: brak, {analyzed:false}: jeszcze trwa),
  // target — transkodowanie przez serwer (null: brak, undefined: nie wiadomo)
  function describeQuality(s, spec, target) {
    const fmt = String(s.suffix || "").toLowerCase();
    const codec = fmt.toUpperCase();
    const lossless = LOSSLESS.has(fmt) || (/^(m4a|mp4)$/.test(fmt) && s.bitRate >= 500);  // ALAC w kontenerze m4a
    const rate = s.samplingRate ? khz(s.samplingRate) : "";
    const verdict = spec && spec.verdict;
    const cut = spec && spec.cutoff ? khz(spec.cutoff) + " kHz" : "";
    let text = lossless ? codec + " " + (s.bitDepth ? s.bitDepth + "/" : "") + rate : codec + (s.bitRate ? " " + s.bitRate : "");
    let label, kind = lossless ? "lossless" : "lossy", warn = "", note = "";

    if (verdict === "fake" || verdict === "transcoded") {
      kind = "fake";
      warn = "≈ MP3 " + spec.source_kbps;
      label = tier(spec.source_kbps, "mp3") + " — fałszywa kopia";
      note = verdict === "fake"
        ? "Dźwięk urywa się na " + cut + " — to MP3 ok. " + spec.source_kbps + " kb/s zapisane jako " + codec + ", nie prawdziwy plik bezstratny."
        : "Dźwięk urywa się na " + cut + " — to MP3 podbite z ok. " + spec.source_kbps + " kb/s, brzmi jak ten niższy bitrate.";
    } else if (verdict === "suspect") {
      kind = "doubt";
      warn = "≈ MP3 320?";
      label = "Bezstratna? — niepewne";
      note = "Dźwięk urywa się na " + cut + " — możliwe źródło MP3 320 albo po prostu taki mastering. Różnicy raczej nie usłyszysz.";
    } else if (verdict === "upsampled") {
      kind = "doubt";
      warn = "≈ CD";
      label = "Bezstratna (jakość CD, nie hi-res)";
      note = "Dźwięk urywa się na " + cut + " — ten „hi-res” zrobiono z płyty CD (16 bit / 44,1 kHz).";
    } else {
      const hires = (s.bitDepth || 0) >= 24 || (s.samplingRate || 0) > 48000;
      label = lossless ? (hires ? "Hi-Res bezstratna" : "Bezstratna") : tier(s.bitRate || 0, codec);
      note = !spec || spec.podcast ? "" : !spec.analyzed ? "Analiza widma jeszcze trwa — nowe pliki są sprawdzane w ciągu kilku minut."
        : lossless ? "Analiza widma: pełne pasmo" + (cut ? " (do " + cut + ")" : "") + " — prawdziwa jakość bezstratna."
        : "Analiza widma: pasmo" + (cut ? " do " + cut : " pełne") + " — zgodne z bitrate, bez podbijania.";
    }

    let playback = target === undefined ? "" : "Grasz oryginalny plik, bez transkodowania.";
    if (target) {  // to, co faktycznie słyszysz, wyznacza transkodowanie
      text = target.codec.toUpperCase() + (target.kbps ? " " + target.kbps : "");
      playback = "Serwer transkoduje z " + codec + " do " + target.codec.toUpperCase() +
        (target.kbps ? " " + target.kbps + " kb/s" : "") + ".";
      if (!LOSSLESS.has(target.codec.toLowerCase())) {
        kind = kind === "fake" ? "fake" : "lossy";
        if (kind !== "fake") label = tier(target.kbps, target.codec);
      }
    }
    const file = [codec, s.bitDepth ? s.bitDepth + " bit" : "", rate ? rate + " kHz" : "",
      { 1: "mono", 2: "stereo" }[s.channelCount] || (s.channelCount ? s.channelCount + " kanały" : ""),
      s.bitRate ? s.bitRate.toLocaleString("pl-PL") + " kb/s" : ""].filter(Boolean).join(" · ");
    return { text: text, warn: warn, kind: kind, label: label, file: file, note: note, playback: playback };
  }

  function qualityBadge() {
    const track = playingTrack();
    const artist = document.querySelector(".react-jinke-music-player-main .songArtist");
    let badge = document.querySelector(".pf-quality");
    if (track && track.src !== quality.src) {
      quality.src = track.src;
      quality.song = null;
      quality.spectrum = undefined;
      const src = track.src;
      subsonic("getSong", { id: track.id }).then((r) => {
        if (quality.src === src) { quality.song = r.song || null; update(); }
      });
      fetch(API + "quality/" + encodeURIComponent(track.id), {
        headers: { "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") },
      }).then((r) => (r.ok ? r.json() : null)).catch(() => null).then((spec) => {
        if (quality.src === src) { quality.spectrum = spec; update(); }
      });
    }
    if (!track || !artist || !quality.song) {
      if (badge) badge.remove();
      return;
    }
    const q = describeQuality(quality.song, quality.spectrum, serverTranscoding());
    quality.details = q;
    if (!badge || badge.nextSibling !== artist) {  // nowy tytuł od Navidrome — wstawiamy plakietkę od nowa
      if (badge) badge.remove();
      badge = document.createElement("span");
      badge.className = "pf-quality";
      badge.setAttribute("role", "button");
      badge.tabIndex = 0;
      artist.parentNode.insertBefore(badge, artist);
    }
    const html = (q.kind === "lossless" ? WAVES : q.kind === "lossy" ? "" : WARN) + "<span>" + esc(q.text) + "</span>" +
      (q.warn ? '<span class="pf-q-warn">' + esc(q.warn) + "</span>" : "");
    if (badge.dataset.html !== html) {
      badge.dataset.html = html;
      badge.innerHTML = html;
      badge.className = "pf-quality pf-q-" + q.kind;
    }
    setAttr(badge, "aria-label", ["Jakość: " + q.text + (q.warn ? " (" + q.warn + ")" : "") + ", " + q.label + ".",
      q.note, q.playback].filter(Boolean).join(" "));
  }

  // Szczegóły jakości w dymku nad plakietką (dymek w <body>, bo pasek odtwarzania przycina to, co z niego wystaje).
  function showQuality(badge) {
    let pop = document.querySelector(".pf-quality-pop");
    if (!pop) {
      pop = document.createElement("div");
      pop.className = "pf-quality-pop";
      pop.setAttribute("role", "tooltip");
      document.body.appendChild(pop);
    }
    const q = quality.details;
    if (!q) return;
    pop.innerHTML = "<b>" + esc(q.label) + "</b><span>" + esc(q.file) + "</span>" +
      [q.note, q.playback].filter(Boolean).map((line) => "<span>" + esc(line) + "</span>").join("");
    pop.className = "pf-quality-pop pf-q-" + q.kind;
    pop.classList.add("pf-show");  // najpierw widoczny — dopiero wtedy ma wymiary
    const r = badge.getBoundingClientRect();
    pop.style.left = Math.max(8, Math.min(r.left, innerWidth - pop.offsetWidth - 8)) + "px";
    pop.style.top = r.top - pop.offsetHeight - 10 + "px";
  }

  function hideQuality() {
    const pop = document.querySelector(".pf-quality-pop");
    if (pop) pop.classList.remove("pf-show");
  }

  document.addEventListener("mouseover", (e) => {
    const badge = e.target.closest && e.target.closest(".pf-quality");
    if (badge) showQuality(badge); else hideQuality();
  });
  document.addEventListener("focusin", (e) => { if (e.target.classList && e.target.classList.contains("pf-quality")) showQuality(e.target); });
  document.addEventListener("focusout", hideQuality);
  document.addEventListener("click", (e) => {
    // plakietka siedzi w linku do albumu — kliknięcie ma pokazać szczegóły, a nie przejść do albumu
    const badge = e.target.closest && e.target.closest(".pf-quality");
    if (!badge) return;
    e.preventDefault();
    e.stopPropagation();
    showQuality(badge);
  }, true);

  // ── inteligentne odtwarzanie (jak w Spotify) ──
  // Co SMART_EVERY utworów wstawia jako następny jeden polecony utwór, a gdy kolejka się kończy, dokłada
  // kolejne — muzyka gra dalej. Polecenia: getSimilarSongs2 (podobni wykonawcy z Last.fm, utwory z Twojej
  // biblioteki). Do kolejki dokładamy tymi samymi akcjami, których Navidrome używa w menu „Odtwórz następne”
  // i „Dodaj do kolejki”, przez jego magazyn stanu (Redux) wyciągnięty z drzewa Reacta.
  const SMART_EVERY = 3;
  const SMART_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M3 17h3.5c2 0 3-1 4.2-2.7l2.6-3.6C14.5 9 15.5 8 17.5 8H21M18 5l3 3-3 3M3 7h3.5c1.4 0 2.3.5 3.1 1.4M21 16h-3.5c-1.3 0-2.2-.4-3-1.2M18 13l3 3-3 3"></path>' +
    '<path d="M6.5 1.5l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8L4 4l1.8-.7z" fill="currentColor" stroke-width="1"></path></svg>';
  const smart = { on: false, store: null, uuid: null, played: 0, busy: false, added: new Set() };
  try {
    smart.on = localStorage.getItem("pf-smart") === "1";
    JSON.parse(localStorage.getItem("pf-smart-added") || "[]").forEach((id) => smart.added.add(id));
  } catch (e) { /* bez localStorage działa do odświeżenia strony */ }

  function reduxStore() {
    // Provider z react-redux trzyma magazyn w props — szukamy go w drzewie od korzenia aplikacji.
    if (smart.store) return smart.store;
    const el = document.getElementById("root");
    if (!el) return null;
    const container = el._reactRootContainer;
    const key = Object.keys(el).find((k) => k.startsWith("__reactContainer$"));
    const stack = [container ? (container._internalRoot || container).current : key && el[key]];
    for (let n = 0; stack.length && n < 20000; n++) {
      const fiber = stack.pop();
      if (!fiber) continue;
      const store = fiber.memoizedProps && fiber.memoizedProps.store;
      if (store && typeof store.dispatch === "function" && typeof store.getState === "function" && store.getState().player) {
        return (smart.store = store);
      }
      if (fiber.sibling) stack.push(fiber.sibling);
      if (fiber.child) stack.push(fiber.child);
    }
    return null;
  }

  async function songRecords(songs) {
    // pełne rekordy z natywnego API Navidrome — takie same, jakie Navidrome sam wrzuca do kolejki
    try {
      const r = await fetch("/api/song?" + songs.map((s) => "id=" + encodeURIComponent(s.id)).join("&"), {
        headers: { "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") },
      });
      const records = r.ok ? await r.json() : [];
      if (Array.isArray(records) && records.length) return records;
    } catch (e) { /* niżej zapas */ }
    return songs;  // zapas: dane z API Subsonic też wystarczą odtwarzaczowi
  }

  async function recommend(player, count) {
    const current = (player.current && player.current.song) || {};
    const inQueue = new Set(player.queue.map((item) => item.trackId));
    const fresh = (songs) => songs.filter((s) => s && s.id && !inQueue.has(s.id) && !smart.added.has(s.id) && !s.missing &&
      s.genre !== "Podcast" && !/(^|\/)Podcasty\//.test(s.path || ""));  // polecamy muzykę, nie odcinki audycji
    let songs = [];
    if (current.artistId) {
      songs = fresh(((await subsonic("getSimilarSongs2", { id: current.artistId, count: 50 })).similarSongs2 || {}).song || []);
    }
    if (songs.length < count && current.id) {
      songs = songs.concat(fresh(((await subsonic("getSimilarSongs", { id: current.id, count: 50 })).similarSongs || {}).song || []));
    }
    if (songs.length < count) {  // ostatnia deska ratunku: coś losowego z biblioteki
      songs = songs.concat(fresh(((await subsonic("getRandomSongs", { size: 30 })).randomSongs || {}).song || []));
    }
    // jak w Spotify: raczej inni wykonawcy niż ten, który właśnie gra; bez powtórek
    const other = songs.filter((s) => s.artist !== current.artist);
    const pool = (other.length >= count ? other : songs).filter((s, i, all) => all.findIndex((x) => x.id === s.id) === i);
    for (let i = pool.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [pool[i], pool[j]] = [pool[j], pool[i]];
    }
    const ranked = window.PiterfyLibrary ? await window.PiterfyLibrary.rankSongs(pool).catch(() => pool) : pool;
    return songRecords(ranked.slice(0, count));
  }

  async function smartAdd(store, type, count) {
    if (smart.busy) return;
    smart.busy = true;
    try {
      const songs = await recommend(store.getState().player, count);
      if (!songs.length) return;
      const data = {};
      songs.forEach((s) => {
        data[s.id] = s;
        smart.added.add(s.id);
      });
      try { localStorage.setItem("pf-smart-added", JSON.stringify(Array.from(smart.added).slice(-300))); } catch (e) { /* trudno */ }
      store.dispatch({ type: type, data: data });
    } finally {
      smart.busy = false;
    }
  }

  function smartPlay() {
    smartButton();
    repeatButton();
    recommendedMark();
    if (!smart.on) return;
    const store = reduxStore();
    const player = store && store.getState().player;
    const current = player && player.current;
    if (!current || !current.uuid || current.uuid === smart.uuid || !player.queue.length) return;
    smart.uuid = current.uuid;
    const index = player.queue.findIndex((item) => item.uuid === current.uuid);
    if (!smart.added.has(current.trackId)) smart.played += 1;
    if (index >= player.queue.length - 2) {
      smartAdd(store, "PLAYER_ADD_TRACKS", 5);  // kolejka się kończy — gramy dalej
    } else if (smart.played >= SMART_EVERY) {
      smart.played = 0;
      smartAdd(store, "PLAYER_PLAY_NEXT", 1);   // polecony utwór jako następny
    }
  }

  function smartButton() {
    const content = document.querySelector(".react-jinke-music-player-main .music-player-panel .player-content");
    if (!content) return;
    let button = content.querySelector(".pf-smart");
    if (!button) {
      button = document.createElement("span");
      button.className = "group pf-smart";
      button.setAttribute("role", "button");
      button.tabIndex = 0;
      button.innerHTML = SMART_ICON;
      const toggle = (e) => {
        e.preventDefault();
        e.stopPropagation();
        smart.on = !smart.on;
        try { localStorage.setItem("pf-smart", smart.on ? "1" : "0"); } catch (err) { /* trudno */ }
        smart.uuid = null;
        smart.played = 0;
        toast(smart.on ? "Inteligentne odtwarzanie włączone — co kilka utworów dorzucę coś podobnego i zagram dalej, gdy kolejka się skończy"
          : "Inteligentne odtwarzanie wyłączone");
        if (smart.on) {
          const store = reduxStore();
          const player = store && store.getState().player;
          const index = player ? player.queue.findIndex((item) => item.uuid === (player.current || {}).uuid) : -1;
          if (store && index >= player.queue.length - 2) smartAdd(store, "PLAYER_ADD_TRACKS", 5);
        }
        smartButton();
      };
      button.addEventListener("click", toggle);
      button.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") toggle(e); });
      const loop = content.querySelector(".loop-btn");
      content.insertBefore(button, loop && loop.parentNode === content ? loop : null);
    }
    button.classList.toggle("pf-on", smart.on);
    setAttr(button, "aria-pressed", String(smart.on));
    setAttr(button, "title", smart.on ? "Inteligentne odtwarzanie: włączone" : "Inteligentne odtwarzanie: dorzuca podobne utwory i gra dalej, gdy kolejka się skończy");
  }

  // ── przycisk powtarzania (jak w Spotify: wyłączone → wszystko → jeden utwór) ──
  // Własny przełącznik trybu odtwarzania Navidrome jest mało czytelny (w trybie „po kolei” to ikona listy).
  // Zmieniamy ten sam tryb (player.mode w magazynie Navidrome), więc oba przyciski zawsze się zgadzają.
  const REPEAT_ICON = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M17 2l4 4-4 4"></path><path d="M3 11V9a3 3 0 0 1 3-3h15"></path><path d="M7 22l-4-4 4-4"></path><path d="M21 13v2a3 3 0 0 1-3 3H3"></path></svg>';
  const REPEAT_STATES = {
    order: { next: "orderLoop", on: false, title: "Powtarzanie: wyłączone" },
    shufflePlay: { next: "orderLoop", on: false, title: "Powtarzanie: wyłączone (losowe odtwarzanie)" },
    orderLoop: { next: "singleLoop", on: true, title: "Powtarzanie: cała kolejka" },
    singleLoop: { next: "order", on: true, one: true, title: "Powtarzanie: jeden utwór" },
  };

  function repeatButton() {
    const content = document.querySelector(".react-jinke-music-player-main .music-player-panel .player-content");
    if (!content) return;
    const store = reduxStore();
    const mode = (store && store.getState().player.mode) || "order";
    const state = REPEAT_STATES[mode] || REPEAT_STATES.order;
    let button = content.querySelector(".pf-repeat");
    if (!button) {
      button = document.createElement("span");
      button.className = "group pf-repeat";
      button.setAttribute("role", "button");
      button.tabIndex = 0;
      button.innerHTML = REPEAT_ICON + '<b aria-hidden="true">1</b>';
      const press = (e) => {
        e.preventDefault();
        e.stopPropagation();
        const s = reduxStore();
        if (!s) return;
        const current = REPEAT_STATES[s.getState().player.mode || "order"] || REPEAT_STATES.order;
        s.dispatch({ type: "PLAYER_SET_MODE", data: { mode: current.next } });
        repeatButton();
      };
      button.addEventListener("click", press);
      button.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") press(e); });
      const anchor = content.querySelector(".pf-smart") || content.querySelector(".loop-btn");
      content.insertBefore(button, anchor && anchor.parentNode === content ? anchor : null);
    }
    button.classList.toggle("pf-on", state.on);
    button.classList.toggle("pf-one", !!state.one);
    setAttr(button, "aria-pressed", String(state.on));
    setAttr(button, "title", state.title + " — kliknij, aby zmienić");
  }

  function recommendedMark() {
    // „✦ Polecane” przy utworze, który dorzuciło inteligentne odtwarzanie
    const track = playingTrack();
    const artist = document.querySelector(".react-jinke-music-player-main .songArtist");
    let mark = document.querySelector(".pf-rec");
    if (!track || !artist || !smart.added.has(track.id)) {
      if (mark) mark.remove();
      return;
    }
    const anchor = artist.previousElementSibling && artist.previousElementSibling.classList.contains("pf-quality")
      ? artist.previousElementSibling : artist;
    if (!mark || mark.nextSibling !== anchor) {
      if (mark) mark.remove();
      mark = document.createElement("span");
      mark.className = "pf-rec";
      mark.title = "Polecane przez inteligentne odtwarzanie";
      mark.textContent = "✦ Polecane";
      anchor.parentNode.insertBefore(mark, anchor);
    }
  }

  // ── zdjęcie profilowe (zapisuje je profile_api.py w kontenerze discover) ──
  const PHOTO = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">' +
    '<path d="M4 8h3l2-3h6l2 3h3v11H4z"></path><circle cx="12" cy="13" r="3.5"></circle></svg>';
  const TRASH = '<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round">' +
    '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"></path></svg>';
  const avatar = { missing: false };

  function avatarSrc() {
    const user = localStorage.getItem("username");
    return user ? API + "avatar/" + encodeURIComponent(user) + "?v=" + (localStorage.getItem("pf-avatar-v") || "1") : "";
  }

  function avatarButton() {
    // menu użytkownika to ostatni przycisk z menu w górnym pasku
    const buttons = document.querySelectorAll('header.MuiAppBar-root button[aria-haspopup="true"]');
    const button = buttons[buttons.length - 1];
    if (!button) return;
    const src = avatarSrc();
    let img = button.querySelector(".pf-avatar");
    if (avatar.missing || !src) {
      if (img) img.remove();
      button.classList.remove("pf-has-avatar");
      return;
    }
    if (!img) {
      img = document.createElement("img");
      img.className = "pf-avatar";
      img.alt = "";
      img.addEventListener("error", () => { avatar.missing = true; update(); });
      (button.querySelector(".MuiIconButton-label") || button).appendChild(img);
    }
    if (img.getAttribute("src") !== src) img.setAttribute("src", src);
    button.classList.add("pf-has-avatar");
  }

  function toast(text) {
    const t = document.createElement("div");
    t.className = "pf-toast";
    t.setAttribute("role", "status");
    t.textContent = text;
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 4000);
  }

  function closeMenu() {
    const backdrop = document.querySelector(".MuiPopover-root .MuiBackdrop-root");
    if (backdrop) backdrop.click();
  }

  function squareJpeg(file, size) {
    return createImageBitmap(file).then((bmp) => {
      const side = Math.min(bmp.width, bmp.height);
      const canvas = document.createElement("canvas");
      canvas.width = canvas.height = size;
      canvas.getContext("2d").drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, size, size);
      return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.9));
    });
  }

  function saveAvatar(method, body) {
    return fetch(API + "avatar", {
      method: method, body: body,
      headers: { "Content-Type": "image/jpeg", "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") },
    }).then((r) => {
      if (!r.ok) return r.text().then((t) => { throw new Error(t || "błąd " + r.status); });
      localStorage.setItem("pf-avatar-v", String(Date.now()));
      avatar.missing = method === "DELETE";
      update();
    });
  }

  function pickAvatar() {
    const input = document.createElement("input");
    input.type = "file";
    input.accept = "image/jpeg,image/png,image/webp,image/gif";
    input.addEventListener("change", () => {
      const file = input.files[0];
      if (!file) return;
      squareJpeg(file, 512)
        .catch(() => { throw new Error("Nie umiem otworzyć tego pliku — wybierz JPG, PNG albo WebP."); })
        .then((jpeg) => saveAvatar("PUT", jpeg))
        .then(() => toast("Zdjęcie profilowe zmienione"), (e) => toast("Nie udało się zmienić zdjęcia: " + e.message));
    });
    input.click();
  }

  function avatarMenu() {
    const personal = document.querySelector('.MuiPopover-paper a[href="#/personal"]');
    const list = personal && personal.closest("ul");
    if (!list || list.querySelector(".pf-avatar-item")) return;
    const item = (icon, text, action) => {
      const li = document.createElement("li");
      li.className = "MuiButtonBase-root MuiListItem-root MuiMenuItem-root MuiMenuItem-gutters MuiListItem-gutters MuiListItem-button pf-avatar-item";
      li.setAttribute("role", "menuitem");
      li.tabIndex = -1;
      li.innerHTML = icon + "<span>" + text + "</span>";
      li.addEventListener("click", () => { closeMenu(); action(); });
      return li;
    };
    const items = [item(PHOTO, avatar.missing ? "Dodaj zdjęcie profilowe" : "Zmień zdjęcie profilowe", pickAvatar)];
    if (!avatar.missing) {
      items.push(item(TRASH, "Usuń zdjęcie profilowe", () => saveAvatar("DELETE")
        .then(() => toast("Zdjęcie profilowe usunięte"), (e) => toast("Nie udało się usunąć zdjęcia: " + e.message))));
    }
    list.prepend(...items);
  }

  // ── strona startowa (#/start) ──
  // Navidrome nie zna tej trasy i pokazuje w treści „Nie znaleziono” — CSS to chowa, a my dokładamy własną treść.
  // Dane z API Subsonic, uwierzytelnione tak samo jak sam interfejs (dane zapisane przez Navidrome
  // w localStorage tej przeglądarki przy logowaniu).
  function subsonicParams(extra) {
    return new URLSearchParams(Object.assign({
      u: localStorage.getItem("username") || "", t: localStorage.getItem("subsonic-token") || "",
      s: localStorage.getItem("subsonic-salt") || "", v: "1.16.1", c: NAME, f: "json",
    }, extra));
  }

  function subsonic(command, extra) {
    return fetch("/rest/" + command + "?" + subsonicParams(extra))
      .then((r) => r.json())
      .then((j) => j["subsonic-response"] || {})
      .catch(() => ({}));
  }

  function cover(id, size) {
    return id ? "/rest/getCoverArt?" + subsonicParams({ id: id, size: size || 300 }) : "";
  }

  // ── podcasty: rozpoznawanie, żeby nigdy nie mieszały się z muzyką ──
  // Podcasty to w bibliotece albumy z gatunkiem „Podcast” (folder Podcasty/<audycja>/; zapisuje je podcasts.py).
  // Zbiór ich albumów i wykonawców (audycji) służy do wycinania ich z widoków muzycznych.
  // piterfy-media-kinds-start
(function(root,factory){const api=factory();if(typeof module==='object'&&module.exports)module.exports=api;else root.PiterfyMediaKinds=api;})(typeof window!=='undefined'?window:globalThis,()=>{
  function isPodcast(song){
    if(!song)return false;
    if(String(song.type||'').toLowerCase()==='podcast')return true;
    if(/(^|[;,/])\s*(podcasts?|podcasty)\s*($|[;,/])/i.test(song.genre||''))return true;
    return /(^|\/)(podcasts?|podcasty)(\/|$)/i.test(String(song.path||'').replace(/\\/g,'/'));
  }
  return {isPodcast};
});

  // piterfy-media-kinds-end
  const podcasts = { albums: new Set(), artists: new Set(), list: [], ready: false, at: 0, loading: null };

  const isPodcastSong = (s) => window.PiterfyMediaKinds.isPodcast(s) || !!s && podcasts.albums.has(s.albumId);

  // piterfy-music-catalog-start
  const musicTracks={data:null,at:0,loading:false,query:'',visible:100};
  const libraryCatalog={owner:'',at:0,songs:[],loading:null};
  function activeSongState(){return root.dataset.pfRoute==='utwory'?musicTracks:liked;}
  async function allLibrarySongs(){
    const owner=localStorage.getItem('username')||'';
    if(!owner)throw Error('Zaloguj się w Piterfy.');
    if(libraryCatalog.owner!==owner){libraryCatalog.owner=owner;libraryCatalog.at=0;libraryCatalog.songs=[];libraryCatalog.loading=null;}
    if(Date.now()-libraryCatalog.at<120000)return libraryCatalog.songs;
    if(libraryCatalog.loading)return libraryCatalog.loading;
    const pending=(async()=>{
      const songs=[],seen=new Set();
      for(let offset=0;;offset+=500){
        const response=await fetch('/api/song?_start='+offset+'&_end='+(offset+500)+'&_sort=title&_order=ASC',{
          headers:{'X-ND-Authorization':'Bearer '+localStorage.getItem('token')},signal:AbortSignal.timeout(15000)});
        if(!response.ok)throw Error('Nie udało się wczytać utworów.');
        const batch=await response.json();if(!Array.isArray(batch))throw Error('Nieprawidłowa lista utworów.');
        let added=0;
        for(const song of batch)if(song.id&&!seen.has(song.id)){seen.add(song.id);songs.push(song);added++;}
        if(owner!==(localStorage.getItem('username')||''))throw Error('Zmieniono konto.');
        if(batch.length<500)break;
        if(!added)throw Error('Nie udało się wczytać kolejnej strony utworów.');
      }
      if(libraryCatalog.owner===owner){libraryCatalog.songs=songs;libraryCatalog.at=Date.now();}
      return songs;
    })();
    libraryCatalog.loading=pending;
    try{return await pending;}finally{if(libraryCatalog.loading===pending)libraryCatalog.loading=null;}
  }
  function ensurePodcasts(){
    if(podcasts.loading)return podcasts.loading;
    if(!localStorage.getItem('username')||Date.now()-podcasts.at<120000)return Promise.resolve();
    const owner=localStorage.getItem('username');
    podcasts.loading=allLibrarySongs().then(songs=>{
      if(owner!==localStorage.getItem('username'))return;
      const shows=new Map();
      for(const song of songs){
        if(song.missing||!window.PiterfyMediaKinds.isPodcast(song)||!song.albumId)continue;
        if(!shows.has(song.albumId))shows.set(song.albumId,{id:song.albumId,name:song.album||'Podcast',artist:song.artist||'',artistId:song.artistId,coverArt:song.coverArt||'al-'+song.albumId,songCount:0,genre:'Podcast'});
        shows.get(song.albumId).songCount++;
      }
      podcasts.list=[...shows.values()];podcasts.albums=new Set(shows.keys());
      podcasts.artists=new Set(podcasts.list.map(s=>s.artistId).filter(Boolean));podcasts.ready=true;podcasts.at=Date.now();
    }).catch(()=>{podcasts.at=Date.now()-110000;}).finally(()=>{podcasts.loading=null;update();});
    return podcasts.loading;
  }
  async function loadMusicTracks(){
    const songs=(await allLibrarySongs()).filter(song=>!song.missing&&!isPodcastSong(song));
    return {songs,albums:[],artists:[]};
  }
  // piterfy-music-catalog-end

  // Widoki muzyczne Navidrome (albumy, wykonawcy, utwory): chowa kafelki i wiersze podcastów.
  // Strony „-show” (np. album audycji, otwarty świadomie) zostają nietknięte.
  function hidePodcasts() {
    ensurePodcasts();
    const route = root.dataset.pfRoute || "";
    const content = document.getElementById("main-content");
    if (!podcasts.ready || !content || /-show$/.test(route) || PAGES[route]) return;
    content.querySelectorAll('a[href*="/album/"], a[href*="/artist/"]').forEach((a) => {
      if (a.dataset.pfPod === undefined) {
        const m = a.getAttribute("href").match(/\/(album|artist)\/([^/?]+)\/show/);
        a.dataset.pfPod = m && podcasts[m[1] + "s"].has(m[2]) ? "1" : "0";
      }
      if (a.dataset.pfPod !== "1") return;
      const host = a.closest(".MuiGridListTile-root, tr, .MuiListItem-root");
      if (host && !host.classList.contains("pf-pod")) host.classList.add("pf-pod");
    });
  }

  const DAILY = /^Odkrywaj \d{2}\.\d{2}$/;
  let home = { data: null, at: 0, loading: false, playAt: 0 };

  async function loadHome() {
    await ensurePodcasts();
    const albums = (r) => ((r.albumList2 && r.albumList2.album) || []).filter((a) => !podcasts.albums.has(a.id));
    const [recent, frequent, newest, lists, taste] = await Promise.all([
      subsonic("getAlbumList2", { type: "recent", size: 16 }),
      subsonic("getAlbumList2", { type: "frequent", size: 16 }),
      subsonic("getAlbumList2", { type: "newest", size: 16 }),
      subsonic("getPlaylists"),
      fetch(GUST_URL + "data/taste.json", { cache: "no-cache" }).then((r) => (r.ok ? r.json() : null)).catch(() => null),
    ]);
    const playlists = (lists.playlists && lists.playlists.playlist) || [];
    const daily = playlists.filter((p) => DAILY.test(p.name))
      .sort((a, b) => String(b.created).localeCompare(String(a.created)));
    const fresh = daily[0] ? ((await subsonic("getPlaylist", { id: daily[0].id })).playlist || {}).entry || [] : [];
    const artists = taste && taste.top_artists ? (await Promise.all(taste.top_artists.slice(0, 10).map(async (a) => {
      const found = ((await subsonic("search3", { query: a.name, artistCount: 5, albumCount: 0, songCount: 0 }))
        .searchResult3 || {}).artist || [];
      const artist = found.find((x) => x.name.toLowerCase() === a.name.toLowerCase());
      return artist && !podcasts.artists.has(artist.id) ? { id: artist.id, name: artist.name, img: a.img || cover(artist.coverArt) } : null;
    }))).filter(Boolean) : [];
    return {
      recent: albums(recent), frequent: albums(frequent), newest: albums(newest),
      daily: daily, mix: playlists.find((p) => p.name === "Odkrywaj"), fresh: fresh,
      own: playlists.filter((p) => p.name !== "Odkrywaj" && !DAILY.test(p.name)),
      artists: artists, taste: taste,
    };
  }

  function sidebarLink(fragment, fallback) {
    const a = document.querySelector('.MuiDrawer-paper a[href*="' + fragment + '"]');
    return a ? a.getAttribute("href") : fallback;
  }

  function playButton(name) {
    return '<span class="pf-play" data-pf-play role="button" aria-label="Odtwórz: ' + esc(name) + '">' + PLAY + "</span>";
  }

  function tile(href, name, art, playable) {
    return '<a class="pf-tile" href="' + esc(href) + '">' + art + '<span class="pf-tile-name">' + esc(name) + "</span>" +
      (playable === false ? "" : playButton(name)) + "</a>";
  }

  function card(href, title, sub, img, opts) {
    opts = opts || {};
    return '<a class="pf-card' + (opts.round ? " pf-round" : "") + '" href="' + esc(href) + '"><span class="pf-card-art"' +
      (opts.art ? ' data-pf-art="' + esc(opts.art) + '"' : "") + ">" +
      (img ? '<img src="' + esc(img) + '" alt="" loading="lazy" onerror="this.remove()">' : "") +
      (opts.label ? '<span class="pf-card-label">' + esc(opts.label) + "</span>" : "") +
      (opts.round || opts.noPlay ? "" : playButton(title)) +
      '</span><span class="pf-card-title">' + esc(title) + '</span><span class="pf-card-sub">' + esc(sub) + "</span></a>";
  }

  function section(title, more, cards, kicker) {
    if (!cards.length) return "";
    return '<section class="pf-sec"><div class="pf-sec-head"><div>' +
      (kicker ? '<span class="pf-sec-kicker">' + esc(kicker) + "</span>" : "") + "<h2>" + esc(title) + "</h2></div>" +
      (more ? '<a href="' + esc(more) + '">Pokaż wszystko</a>' : "") + '</div><div class="pf-row">' + cards.join("") + "</div></section>";
  }

  function renderHome(box, d) {
    const hour = new Date().getHours();
    const albumCard = (a) => card("#/album/" + a.id + "/show", a.name, a.artist || "", cover(a.coverArt));
    const playlistCard = (p, sub, art) => card("#/playlist/" + p.id + "/show", p.name, sub, cover(p.coverArt),
      { art: art, label: art === "daily" ? p.name.replace("Odkrywaj ", "") : "" });
    const songs = (p) => plural(p.songCount || 0, "utwór", "utwory", "utworów");

    const tiles = [];
    if (d.daily[0]) tiles.push(tile("#/playlist/" + d.daily[0].id + "/show", d.daily[0].name,
      '<span class="pf-tile-art pf-tile-daily">' + esc(d.daily[0].name.replace("Odkrywaj ", "")) + "</span>"));
    if (d.mix) tiles.push(tile("#/playlist/" + d.mix.id + "/show", "Odkrywaj", '<span class="pf-tile-art pf-tile-mix">' + SPARKLE + "</span>"));
    tiles.push(tile("#/ulubione", "Ulubione", '<span class="pf-tile-art pf-tile-liked">' + HEART + "</span>", false));
    d.recent.slice(0, 8 - tiles.length).forEach((a) => tiles.push(tile("#/album/" + a.id + "/show", a.name,
      '<span class="pf-tile-art"><img src="' + esc(cover(a.coverArt, 120)) + '" alt="" onerror="this.remove()"></span>')));

    const chips = [["Muzyka", HOME, true], ["Utwory", "#/utwory"], ["Podcasty", "#/podcasty"], ["Radia", "#/radio"], ["Albumy", sidebarLink("album/all", "#/album")],
      ["Wykonawcy", "#/artist"], ["Playlisty", "#/playlist"], ["Ulubione", "#/ulubione"]];

    const t = d.taste && d.taste.totals;
    const top = d.taste && d.taste.top_artists && d.taste.top_artists[0];
    const banner = t ? '<a class="pf-banner" href="' + GUST_URL + '" target="_blank" rel="noopener">' +
      (top && top.img ? '<img class="pf-banner-img" src="' + esc(top.img) + '" alt="">' : "") +
      '<span class="pf-banner-body"><span class="pf-banner-kicker">' + SPARKLE + "Mój gust · ostatnie " + (d.taste.period_days || 30) + " dni</span>" +
      '<span class="pf-banner-title">' + (top ? "Najczęściej: " + esc(top.name) : "Twój gust") + "</span>" +
      '<span class="pf-banner-stats"><b>' + t.plays + "</b> odsłuchów · <b>" + Math.round(t.minutes / 60) + "</b> godz. · <b>" +
      t.artists + '</b> wykonawców</span><span class="pf-banner-cta">Zobacz podsumowanie ↗</span></span></a>' : "";

    const madeForYou = (d.mix ? [playlistCard(d.mix, "Wszystko, co dla Ciebie znalazłem", "mix")] : [])
      .concat(d.daily.slice(0, 7).map((p) => playlistCard(p, songs(p) + " · nowe dla Ciebie", "daily")));

    box.innerHTML =
      '<header class="pf-home-head"><h1 class="pf-hello">' + (hour >= 5 && hour < 18 ? "Dzień dobry" : "Dobry wieczór") + "</h1>" +
      '<nav class="pf-chips">' + chips.map((c) => '<a href="' + esc(c[1]) + '"' + (c[2] ? ' aria-current="page"' : "") + ">" + c[0] + "</a>").join("") +
      "</nav></header>" +
      '<div class="pf-tiles">' + tiles.join("") + "</div>" +
      section("Odkryte dla Ciebie", "#/playlist", madeForYou, "Stworzone dla Ciebie") +
      section("Świeże odkrycia", d.daily[0] ? "#/playlist/" + d.daily[0].id + "/show" : "",
        d.fresh.map((s) => card("#/album/" + s.albumId + "/show", s.title, s.artist || "", cover(s.coverArt))), d.daily[0] && d.daily[0].name) +
      section("Ostatnio słuchane", sidebarLink("recentlyPlayed", "#/album"), d.recent.map(albumCard)) +
      section("Twoi ulubieni wykonawcy", "#/artist", d.artists.map((a) => card("#/artist/" + a.id + "/show", a.name, "Wykonawca", a.img, { round: true }))) +
      banner +
      section("Twoje playlisty", "#/playlist", d.own.map((p) => playlistCard(p, songs(p)))) +
      section("Najczęściej słuchane", sidebarLink("mostPlayed", "#/album"), d.frequent.map(albumCard)) +
      section("Nowe w bibliotece", sidebarLink("recentlyAdded", "#/album"), d.newest.map(albumCard));
  }

  // ── „Ulubione” (#/ulubione): tylko muzyka z serduszkiem, bez podcastów ──
  const liked = { data: null, at: 0, loading: false, query: "", sig: "" };

  async function loadLiked() {
    await ensurePodcasts();
    let songs = null;
    try {
      // pełne rekordy z natywnego API (te same, których Navidrome używa w kolejce); bez sortowania po stronie serwera
      const r = await fetch("/api/song?_start=0&_end=5000&starred=true", {
        headers: { "X-ND-Authorization": "Bearer " + (localStorage.getItem("token") || "") },
      });
      if (r.ok) songs = await r.json();
    } catch (e) { /* niżej zapas */ }
    const starred = (await subsonic("getStarred2")).starred2 || {};
    if (!Array.isArray(songs)) songs = starred.song || [];  // zapas: dane Subsonic też wystarczą odtwarzaczowi
    songs = songs.filter((s) => s && s.id && !s.missing && !isPodcastSong(s));
    const when = (s) => String(s.starredAt || s.starred || "");
    songs.sort((a, b) => when(b).localeCompare(when(a)));  // od ostatnio polubionych
    return {
      songs: songs,
      albums: (starred.album || []).filter((a) => !podcasts.albums.has(a.id) && !podcasts.artists.has(a.artistId)),
      artists: (starred.artist || []).filter((a) => !podcasts.artists.has(a.id)),
    };
  }

  const clock = (sec) => Math.floor(sec / 60) + ":" + String(Math.floor(sec % 60)).padStart(2, "0");

  function likedSongs() {
    const state=activeSongState();
    const q = state.query.trim().toLowerCase();
    const all = (state.data && state.data.songs) || [];
    return !q ? all : all.filter((s) => [s.title, s.artist, s.album].join(" ").toLowerCase().includes(q));
  }

  function likedRows() {
    const songs = likedSongs();
    if (!songs.length) {
      return '<p class="pf-empty">' + (activeSongState().query ? "Nic nie pasuje do „" + esc(activeSongState().query) + "”." :
        (root.dataset.pfRoute==="utwory"?"Brak utworów muzycznych. Podcasty znajdziesz w osobnej kategorii.":"Nie masz jeszcze polubionych utworów. Kliknij serduszko przy utworze — trafi tutaj.")) + "</p>";
    }
    const visible=root.dataset.pfRoute==="utwory"?songs.slice(0,musicTracks.visible):songs;
    return visible.map((s, i) => '<div class="pf-song" data-pf-song="' + esc(s.id) + '" tabindex="0" role="button" aria-label="Odtwórz: ' + esc(s.title) + '">' +
      '<span class="pf-song-n"><b>' + (i + 1) + "</b>" + PLAY + "</span>" +
      '<span class="pf-song-main"><img src="' + esc(cover(s.coverArt || (s.albumId ? "al-" + s.albumId : s.id), 80)) + '" alt="" loading="lazy" onerror="this.remove()">' +
      '<span class="pf-song-text"><span class="pf-song-title">' + esc(s.title) + '</span><span class="pf-song-artist">' + esc(s.artist) + "</span></span></span>" +
      '<span class="pf-song-album">' + esc(s.album) + '</span><span class="pf-song-time">' + (s.duration ? clock(s.duration) : "") + "</span></div>").join("")+(root.dataset.pfRoute==="utwory"&&songs.length>visible.length?'<button type="button" class="pf-btn-ghost" data-pf-act="music-more">Pokaż kolejne 100</button>':"");
  }

  function renderLiked(box, d) {
    const count = d.songs.length;
    const minutes = Math.round(d.songs.reduce((sum, s) => sum + (s.duration || 0), 0) / 60);
    const length = minutes >= 60 ? Math.floor(minutes / 60) + " godz. " + (minutes % 60) + " min" : minutes + " min";
    box.innerHTML =
      '<header class="pf-page-head"><span class="pf-page-art pf-liked-art">' + HEART + "</span>" +
      '<div class="pf-page-title"><span class="pf-sec-kicker">Tylko muzyka · bez podcastów</span><h1>' + (root.dataset.pfRoute==="utwory"?"Utwory":"Ulubione") + '</h1>' +
      '<span class="pf-page-meta">' + (count ? "<b>" + plural(count, "utwór", "utwory", "utworów") + "</b> · " + length : "Pusto") + "</span></div></header>" +
      '<div class="pf-actions">' +
      '<button type="button" class="pf-btn-play" data-pf-act="liked-play"' + (count ? "" : " disabled") + ">" + PLAY + "<span>Odtwórz</span></button>" +
      '<button type="button" class="pf-btn-ghost" data-pf-act="liked-shuffle"' + (count ? "" : " disabled") + ">" + SHUFFLE + "<span>Losowo</span></button>" +
      (count > 8 ? '<input class="pf-filter" type="search" placeholder="Szukaj utworu" aria-label="Szukaj utworu" value="' + esc(activeSongState().query) + '">' : "") +
      '</div><div class="pf-songs">' + likedRows() + "</div>" +
      section("Ulubione albumy", "", d.albums.map((a) => card("#/album/" + a.id + "/show", a.name, a.artist || "", cover(a.coverArt)))) +
      section("Ulubieni wykonawcy", "", d.artists.map((a) => card("#/artist/" + a.id + "/show", a.name, "Wykonawca", cover(a.coverArt), { round: true })));
  }

  // Odtwarza polubione utwory w kolejności (od wybranego) albo losowo — tą samą akcją, której używa sam Navidrome.
  function playSongs(songs, startId, random) {
    const store = reduxStore();
    if (!store) { toast("Odtwarzacz nie jest jeszcze gotowy — spróbuj za chwilę"); return; }
    if (!songs.length) return;
    const list = songs.filter(s=>!isPodcastSong(s));
    if(!list.length)return;
    if (random) {
      for (let i = list.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [list[i], list[j]] = [list[j], list[i]];
      }
    }
    const data = {};
    list.forEach((s) => { data["_" + s.id] = s; });  // „_”: klucze zachowują kolejność (jak w shuffle Navidrome)
    store.dispatch({ type: "PLAYER_PLAY_TRACKS", id: "_" + (startId || list[0].id), data: data });
  }

  // ── „Podcasty” (#/podcasty): audycje z biblioteki, osobno od muzyki ──
  async function loadPodcasts() {
    podcasts.at = 0;  // zawsze świeża lista na tej stronie
    await ensurePodcasts();
    return { shows: podcasts.list.slice().sort((a, b) => String(a.name).localeCompare(String(b.name), "pl")) };
  }

  function renderPodcasts(box, d) {
    box.innerHTML = '<header class="pf-home-head"><h1 class="pf-hello">Podcasty</h1>' +
      '<span class="pf-page-meta">Audycje osobno od muzyki — nie trafiają do Ulubionych, Odkrywaj ani poleceń</span></header>' +
      (d.shows.length ? '<div class="pf-grid">' + d.shows.map((a) => card("#/album/" + a.id + "/show", a.name,
        plural(a.songCount || 0, "odcinek", "odcinki", "odcinków"), cover(a.coverArt), { noPlay: true })).join("") + "</div>" :
        '<p class="pf-empty">Brak podcastów. Dodaj audycję w pliku podcasts.txt na serwerze — pojawi się tutaj po pobraniu pierwszych odcinków.</p>');
  }

  const PAGES = {
    utwory: { state: musicTracks, ttl:120000, load:loadMusicTracks, render:renderLiked, sig:d=>d.songs.map(s=>s.id).join() },
    start: { state: home, ttl: 60000, load: loadHome, render: renderHome },
    ulubione: { state: liked, ttl: 15000, load: loadLiked, render: renderLiked, sig: (d) => d.songs.map((s) => s.id).join() + "|" + d.albums.length + "|" + d.artists.length },
    podcasty: { state: { data: null, at: 0, loading: false }, ttl: 120000, load: loadPodcasts, render: renderPodcasts },
  };

  function customPage() {
    const route = root.dataset.pfRoute;
    const page = PAGES[route];
    let box = document.querySelector(".pf-home");
    const content = document.getElementById("main-content");
    if (!page || !content) {
      if (box) box.remove();
      return;
    }
    const st = page.state;
    if (box && (box.parentNode !== content || box.dataset.pfPage !== route)) {
      box.remove();
      box = null;
    }
    if (!box) {
      box = document.createElement("div");
      box.className = "pf-home";
      box.dataset.pfPage = route;
      content.appendChild(box);
      if (st.data) {
        page.render(box, st.data);
        st.sig = page.sig ? page.sig(st.data) : "";
      }
    }
    if (!st.loading && Date.now() - st.at > page.ttl) {
      st.loading = true;
      page.load().then((data) => {
        st.data = data;
        st.at = Date.now();
        st.loading = false;
        const current = document.querySelector('.pf-home[data-pf-page="' + route + '"]');
        const sig = page.sig ? page.sig(data) : null;
        // strony z listą nie przerysowują się, gdy nic się nie zmieniło (żeby nie gubić wpisywanego filtra)
        if (current && (sig === null || sig !== st.sig || !current.firstChild)) {
          page.render(current, data);
          st.sig = sig;
        }
      }, () => { st.loading = false; st.at = Date.now() - page.ttl + 10000; });
    }
  }

  document.addEventListener("click", (e) => {
    const target = e.target.closest && e.target.closest("[data-pf-act], [data-pf-song]");
    if (!target || !["ulubione","utwory"].includes(root.dataset.pfRoute)) return;
    e.preventDefault();
    const act = target.dataset.pfAct;
    if(act==="music-more"){musicTracks.visible+=100;document.querySelector(".pf-songs").innerHTML=likedRows();return;}
    if (act) playSongs(likedSongs(), null, act === "liked-shuffle");
    else playSongs(likedSongs(), target.dataset.pfSong, false);
  });
  document.addEventListener("keydown", (e) => {
    const row = e.target.closest && e.target.closest("[data-pf-song]");
    if (row && (e.key === "Enter" || e.key === " ")) { e.preventDefault(); row.click(); }
  });
  document.addEventListener("input", (e) => {
    if (!e.target.classList || !e.target.classList.contains("pf-filter")) return;
    activeSongState().query = e.target.value;musicTracks.visible=100;
    const list = document.querySelector(".pf-songs");
    if (list) list.innerHTML = likedRows();
  });

  // Zielony przycisk na karcie: przejdź do albumu/playlisty i od razu odtwórz.
  document.addEventListener("click", (e) => {
    const play = e.target.closest && e.target.closest("[data-pf-play]");
    if (!play) return;
    e.preventDefault();
    e.stopPropagation();
    home.playAt = Date.now();
    location.hash = play.closest("a").getAttribute("href");
  }, true);

  function autoplay() {
    if (!home.playAt) return;
    if (Date.now() - home.playAt > 10000) { home.playAt = 0; return; }
    // „Odtwórz” to pierwszy przycisk paska nad listą utworów; czekamy, aż wczyta się sama lista.
    if (!/-show$/.test(root.dataset.pfRoute || "") ||
        !document.querySelector("#main-content tbody tr, #main-content .MuiList-root .MuiListItem-root")) return;
    const button = document.querySelector("#main-content .MuiToolbar-root .MuiButton-root");
    if (button) {
      home.playAt = 0;
      button.click();
    }
  }

  // Zbiera serię zmian strony w jedno odświeżenie (setTimeout, bo requestAnimationFrame stoi w kartach w tle).
  // Napisy „Navidrome” poprawiamy tylko w tym, co doszło od ostatniego razu — odtwarzacz zmienia stronę
  // kilka razy na sekundę i przeglądanie za każdym razem całej strony obciążało przeglądarkę.
  let queued = false;
  const added = new Set();
  function update() {
    if (queued) return;
    queued = true;
    setTimeout(() => {
      queued = false;
      // Jak w Spotify: aplikacja otwiera się na stronie startowej (start bez adresu załatwia już skrypt w <head>).
      if (/^#?\/?$/.test(location.hash)) location.replace(HOME);
      if(/^#\/song(?:\?.*)?$/.test(location.hash)){location.replace("#/utwory");return;}
      addStyles();
      markTheme();
      markRoute();
      rebrandHead();
      cleanTitleBar();
      added.forEach((node) => { if (node.isConnected) rebrandText(node); });
      added.clear();
      rebrandLogin();
      sidebar();
      gustButton();
      avatarButton();
      avatarMenu();
      heroCover();
      qualityBadge();
      smartPlay();
      customPage();
      hidePodcasts();
      autoplay();
    }, 50);
  }

  function init() {
    markRoute();
    // Strona przerysowuje się po zalogowaniu i zmianie widoku — wtedy dokładamy wszystko ponownie.
    new MutationObserver((records) => {
      records.forEach((r) => r.addedNodes.forEach((node) => added.add(node)));
      update();
    }).observe(document.body, { childList: true, subtree: true });
    // Navidrome zmienia tytuł karty przy każdym widoku.
    new MutationObserver(rebrandHead).observe(document.head, { childList: true, subtree: true, characterData: true });
    window.addEventListener("hashchange", (e) => {
      // Po zalogowaniu — od razu na stronę startową.
      if (/#\/login(\?|$)/.test(e.oldURL) && !/^#\/login/.test(location.hash)) location.replace(HOME);
      markRoute();
      update();
    });
    // Zmiana utworu często podmienia tylko teksty (bez nowych elementów) — łapiemy więc zdarzenia odtwarzacza.
    // Nie „bąbelkują”, ale przechodzą przez document w fazie przechwytywania.
    ["loadstart", "loadedmetadata"].forEach((type) => document.addEventListener(type, update, true));
    // Okładka w nagłówku albumu wczytuje się później niż sama strona; plakietka jakości czeka na decyzję serwera.
    setInterval(() => { heroCover(); qualityBadge(); smartPlay(); }, 1500);
    added.add(document.body);
    update();
  }

  // Skrypt ładowany z <head> może ruszyć, zanim przeglądarka dojdzie do <body>.
  if (document.body) init();
  else document.addEventListener("DOMContentLoaded", init);
})();

// piterfy-connect-loader
;(() => { const s = document.createElement("script"); s.src = "/piterfy-ui/connect.js?v=20261104-204612"; document.head.appendChild(s); })();

// piterfy-lyrics-loader
;(() => {
  const version = Math.floor(Date.now()/6e5);
  const core = document.createElement('script');
  core.src = '/piterfy-ui/lyrics-core.js?v=' + version;
  core.onload = () => { const ui = document.createElement('script'); ui.src = '/piterfy-ui/lyrics.js?v=' + version; document.head.appendChild(ui); };
  document.head.appendChild(core);
})();

// piterfy-miniplayer-loader
;(() => { const s = document.createElement('script'); s.src = '/piterfy-ui/miniplayer.js?v=20261022-205640'; document.head.appendChild(s); })();





















































// piterfy-crossfade-loader
;(() => {
  const core = document.createElement('script');
  core.src = '/piterfy-ui/crossfade-core.js?v=20261104-204610';
  core.onload = () => { const ui = document.createElement('script'); ui.src = '/piterfy-ui/crossfade.js?v=20261104-204610'; document.head.appendChild(ui); };
  document.head.appendChild(core);
})();
// piterfy-crossfade-loader-end

// piterfy-library-loader
;(() => { const s = document.createElement('script'); s.src = '/piterfy-ui/library.js?v=20261104-204612'; document.head.appendChild(s); })();
// piterfy-library-loader-end



// piterfy-offline-loader
;(() => {const core=document.createElement("script");core.src="/piterfy-ui/offline-core.js?v=20261105-085945";core.onload=()=>{const script=document.createElement("script");script.src="/piterfy-ui/offline-sync.js?v=20261105-085945";document.head.append(script);};document.head.append(core);})();
// piterfy-offline-loader-end
