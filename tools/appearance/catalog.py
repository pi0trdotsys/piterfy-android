"""Piterfy: katalog wyglądu: motywy, ikony i dni okolicznościowe (jedno źródło prawdy dla Androida i sieci).

`generate.py` zamienia ten plik w: wektorowe ikony i aliasy launchera (Android), nakładki motywów i teksty (Android),
blok CSS motywów (web), dane ikon i motywów dla przeglądarki (library.js) oraz podglądy SVG.
Kalendarz okazji (±2 dni) jest zaimplementowany osobno i przetestowany w Kotlinie (PiterfyOccasions) i JS (library.js).
"""

# ── motywy ──────────────────────────────────────────────────────────────────────────────────────────────────────
# id, nazwa PL, nazwa EN, ciemny?, ziarno koloru (Material), akcent, akcent 2, tło, powierzchnia, powierzchnia wyższa, tekst, tekst przygaszony
THEMES = [
    ("noir_neon",    "Noir Neon",           "Noir Neon",        True,  "#FF2E93", "#FF4FA3", "#00E5FF", "#07070D", "#12121C", "#1D1D2C", "#F5F5FA", "#A6A6BD"),
    ("synthwave",    "Synthwave",           "Synthwave",        True,  "#B026FF", "#FF6EC7", "#FFB000", "#150A28", "#211040", "#2E1A57", "#FBEFFF", "#BFA8DB"),
    ("papier",       "Papier",              "Paper",            False, "#B5483A", "#B5483A", "#3F6B5C", "#F4EEE2", "#FBF7EE", "#EFE7D6", "#2A2018", "#75675A"),
    ("las",          "Las",                 "Forest",           True,  "#3DDC84", "#6FE3A1", "#E3C26F", "#08150F", "#0F2418", "#173324", "#E8F5EC", "#93B5A0"),
    ("ocean",        "Ocean",               "Ocean",            True,  "#1E88E5", "#4FC3F7", "#26E0C1", "#050E1A", "#0A1D33", "#112C4A", "#E6F3FF", "#8FB2D1"),
    ("sakura",       "Sakura",              "Sakura",           False, "#E91E63", "#D81B60", "#8E6AC8", "#FFF4F7", "#FFFFFF", "#FDE6EE", "#3A1B27", "#8D6575"),
    ("terminal",     "Retro Terminal",      "Retro Terminal",   True,  "#00FF41", "#39FF14", "#00C8FF", "#000000", "#06100A", "#0C1D12", "#B8FFC9", "#5FA874"),
    ("zloto",        "Złoto",               "Gold",             True,  "#D4AF37", "#E7C25B", "#B87333", "#0A0803", "#17120A", "#231B0E", "#F6ECD2", "#B7A77C"),
    ("summer",       "Lato",                "Summer",           True,  "#FF7A3D", "#FF7A3D", "#22C3C3", "#190B1C", "#2A1330", "#3B1B43", "#FFF0E5", "#D1A9A0"),
    ("winter",       "Zima",                "Winter",           True,  "#4FC3F7", "#A8E0FF", "#FFFFFF", "#08121C", "#11202F", "#1A3045", "#EAF6FF", "#9AB7CC"),
    ("halloween",    "Halloween",           "Halloween",        True,  "#FF7A00", "#FF8A1F", "#9B5DE5", "#0D0614", "#1A0F24", "#281738", "#FFEFD9", "#C2A58B"),
    ("christmas",    "Boże Narodzenie",     "Christmas",        True,  "#D62828", "#FF4D4D", "#3DDC84", "#0A1410", "#13241B", "#1C3326", "#FFF3F0", "#B7C7BC"),
    ("valentine",    "Walentynki",          "Valentine's",      True,  "#E5195E", "#FF5C8A", "#FFB3C6", "#14050B", "#240B14", "#34121F", "#FFEEF3", "#D09AAB"),
    ("newyear",      "Sylwester",           "New Year",         True,  "#F5C542", "#F5C542", "#C0C8FF", "#05070F", "#0E1424", "#17203A", "#F8F5E8", "#A9B0C8"),
    ("easter",       "Wiosna / Wielkanoc",  "Spring / Easter",  False, "#7BC96F", "#4FA84A", "#E8789F", "#F6FBF0", "#FFFFFF", "#E8F4DD", "#22331F", "#6A8062"),
    ("independence", "Biało-czerwony",      "Red and white",    True,  "#DC143C", "#E8314F", "#FFFFFF", "#0E0508", "#1E0C11", "#2C1219", "#FFF4F5", "#C79BA2"),
    ("birthday",     "Urodziny Piterfy",    "Piterfy birthday", True,  "#1DB954", "#1ED760", "#FFC107", "#000000", "#121212", "#1E1E1E", "#FFFFFF", "#B3B3B3"),
]

# ── ikony: tło + pierwszy plan w układzie 108×108 (bezpieczna strefa: środek 66×66) ──────────────────────────────
EMBLEM = ("M26,54a28,28 0,1 1,56,0a28,28 0,1 1,-56,0z"
          "M40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27z"
          "M48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8z"
          "M55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53z"
          "M62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z")
BARS = [  # same bars as filled shapes (for icons where the bars are drawn, not cut out)
    "M40.56,50.27L40.56,61.47A2.24,2.24 0,0 0,45.04,61.47L45.04,50.27A2.24,2.24 0,0 0,40.56,50.27z",
    "M48.03,42.8L48.03,65.2A2.24,2.24 0,0 0,52.51,65.2L52.51,42.8A2.24,2.24 0,0 0,48.03,42.8z",
    "M55.49,46.53L55.49,59.6A2.24,2.24 0,0 0,59.97,59.6L59.97,46.53A2.24,2.24 0,0 0,55.49,46.53z",
    "M62.96,50.27L62.96,55.87A2.24,2.24 0,0 0,67.44,55.87L67.44,50.27A2.24,2.24 0,0 0,62.96,50.27z",
]


def lin(x1, y1, x2, y2, *stops):
    """Gradient liniowy: stops = (offset, '#RRGGBB')."""
    return ("lin", x1, y1, x2, y2, list(stops))


def star(x, y, r=6):
    k = r * 0.27
    return f"M{x},{y-r}L{x+k:.1f},{y-k:.1f}L{x+r},{y}L{x+k:.1f},{y+k:.1f}L{x},{y+r}L{x-k:.1f},{y+k:.1f}L{x-r},{y}L{x-k:.1f},{y-k:.1f}z"


def circle(cx, cy, r):
    return f"M{cx-r},{cy}a{r},{r} 0,1 1,{2*r},0a{r},{r} 0,1 1,{-2*r},0z"


def shift(path, dx, dy):
    """Przesuwa ścieżkę o (dx, dy). Obsługuje wiele podścieżek (M/L/A bezwzględne, a względne)."""
    import re
    parts = []
    for sub in path.split("z"):
        if not sub.strip():
            continue
        out = []
        for cmd, args in re.findall(r"([MLAa])([^MLAa]*)", sub):
            nums = [float(n) for n in re.findall(r"-?\d+\.?\d*", args)]
            if cmd in "ML":
                nums = [n + (dx if i % 2 == 0 else dy) for i, n in enumerate(nums)]
            elif cmd == "A":
                for i in range(0, len(nums), 7):
                    nums[i + 5] += dx
                    nums[i + 6] += dy
            out.append(cmd + ",".join(f"{n:g}" for n in nums))
        parts.append("".join(out) + "z")
    return "".join(parts)


# Każda ikona: id, nazwa PL, nazwa EN, tło (kolor albo gradient), lista kształtów pierwszego planu.
# Kształt: dict(d=ścieżka, fill=kolor|gradient|None, stroke=kolor, sw=grubość, alpha=0..1, evenodd=bool, round=bool)
ICONS = [
    ("classic", "Klasyk", "Classic", "#000000", None),  # istniejąca ikona Piterfy (nie jest generowana)
    ("neon", "Neon", "Neon", lin(0, 0, 108, 108, (0, "#0B0B18"), (1, "#2A0B4A")), [
        dict(d=circle(54, 54, 28), fill="#FF2E93", alpha=0.14),
        dict(d=circle(54, 54, 31), stroke="#FF2E93", sw=3, alpha=0.95),
        dict(d=circle(54, 54, 31), stroke="#FF2E93", sw=7, alpha=0.18),
        *[dict(d=b, fill="#00E5FF") for b in BARS]]),
    ("vinyl", "Winyl", "Vinyl", lin(0, 0, 108, 108, (0, "#101010"), (1, "#242424")), [
        dict(d=circle(54, 54, 35), fill="#0B0B0B"),
        dict(d=circle(54, 54, 31), stroke="#2C2C2C", sw=1.2), dict(d=circle(54, 54, 26), stroke="#2C2C2C", sw=1.2),
        dict(d=circle(54, 54, 21), stroke="#2C2C2C", sw=1.2),
        dict(d="M54,22A32,32 0,0 1,84,43", stroke="#FFFFFF", sw=2.6, alpha=0.16, round=True),
        dict(d=circle(54, 54, 13), fill="#1DB954"), dict(d=circle(54, 54, 3), fill="#0B0B0B")]),
    ("valentine", "Walentynki", "Valentine's", lin(0, 0, 108, 108, (0, "#FF5C8A"), (1, "#B5123F")), [
        dict(d="M54,82C28,63 20,48 28,37C35,28 48,31 54,41C60,31 73,28 80,37C88,48 80,63 54,82z" + BARS[0] + BARS[1] + BARS[2] + BARS[3],
             fill="#FFFFFF", evenodd=True),
        dict(d=star(86, 24, 5), fill="#FFE0EA"), dict(d=star(22, 80, 4), fill="#FFE0EA")]),
    ("easter", "Wielkanoc", "Easter", lin(0, 0, 108, 108, (0, "#FFF1F7"), (1, "#C9E9FF")), [
        dict(d="M54,22C69,22 81,45 81,62C81,77 69,86 54,86C39,86 27,77 27,62C27,45 39,22 54,22z", fill="#9BE3B4"),
        dict(d="M28,56L37,49L46,56L54,49L62,56L71,49L80,56L80,64L71,57L62,64L54,57L46,64L37,57L28,64z", fill="#FF8FB1"),
        dict(d="M32,72L40,68L48,72L56,68L64,72L72,68L78,71L77,76L72,73L64,77L56,73L48,77L40,73L33,76z", fill="#FFFFFF", alpha=0.9),
        dict(d=circle(46, 38, 2.6), fill="#FFE066"), dict(d=circle(60, 42, 2.2), fill="#FFE066")]),
    ("summer", "Lato", "Summer", lin(0, 0, 0, 108, (0, "#FFB347"), (0.55, "#FF3D77"), (1, "#7B2FF7")), [
        dict(d=circle(54, 48, 24) + shift("".join(BARS), 0, -8), fill="#FFE066", evenodd=True),
        dict(d="M20,76Q30,69 40,76T60,76T80,76T100,76", stroke="#FFFFFF", sw=3.2, alpha=0.9, round=True),
        dict(d="M20,86Q30,79 40,86T60,86T80,86T100,86", stroke="#FFFFFF", sw=3.2, alpha=0.5, round=True)]),
    ("halloween", "Halloween", "Halloween", lin(0, 0, 108, 108, (0, "#1B0B2E"), (1, "#45106B")), [
        dict(d="M54,38C44,38 38,46 38,57C38,69 45,77 54,77C63,77 70,69 70,57C70,46 64,38 54,38z", fill="#FF8A1F"),
        dict(d="M44,40C32,41 25,50 26,60C27,70 34,77 44,76C38,72 36,64 36,57C36,49 39,43 44,40z", fill="#F26A00"),
        dict(d="M64,40C76,41 83,50 82,60C81,70 74,77 64,76C70,72 72,64 72,57C72,49 69,43 64,40z", fill="#F26A00"),
        dict(d="M51,38L52,30L57,30L58,38z", fill="#53B94F"),
        dict(d="M54,46L47,53L61,53z", fill="#2A0A3F"),
        dict(d="M40,55L46,51L47,58z", fill="#2A0A3F"), dict(d="M68,55L62,51L61,58z", fill="#2A0A3F"),
        dict(d="M39,64L44,68L48,64L54,69L60,64L64,68L69,64L66,72L54,75L42,72z", fill="#2A0A3F"),
        dict(d=star(86, 26, 5), fill="#FFD166"), dict(d=star(24, 30, 4), fill="#FFD166")]),
    ("christmas", "Boże Narodzenie", "Christmas", lin(0, 0, 108, 108, (0, "#0E5A32"), (1, "#052A19")), [
        dict(d=EMBLEM, fill="#E63946", evenodd=True),
        dict(d="M36,46C37,31 53,23 70,28C63,32 60,39 61,46z", fill="#E63946"),
        dict(d="M32,43L76,43A4.5,4.5 0,0 1,76,52L32,52A4.5,4.5 0,0 1,32,43z", fill="#FFFFFF"),
        dict(d=circle(72, 27, 5.5), fill="#FFFFFF"),
        dict(d=star(88, 74, 4), fill="#FFE066"), dict(d=star(20, 30, 4), fill="#FFE066"), dict(d=star(22, 84, 3), fill="#FFFFFF")]),
    ("newyear", "Sylwester", "New Year", lin(0, 0, 108, 108, (0, "#0A0F2C"), (1, "#3A1B6E")), [
        dict(d=EMBLEM, fill="#F5C542", evenodd=True),
        dict(d=star(24, 26, 7), fill="#FFFFFF"), dict(d=star(86, 30, 5), fill="#F5C542"), dict(d=star(82, 86, 6), fill="#FFFFFF"),
        dict(d=circle(20, 78, 2.4), fill="#FF6EC7"), dict(d=circle(92, 58, 2.2), fill="#00E5FF"), dict(d=circle(30, 92, 2), fill="#F5C542"),
        dict(d=circle(70, 20, 2), fill="#FF6EC7")]),
    ("birthday", "Urodziny", "Birthday", lin(0, 0, 108, 108, (0, "#1DB954"), (1, "#0B5A37")), [
        dict(d=shift(EMBLEM, 0, 8), fill="#FFFFFF", evenodd=True),
        dict(d="M54,14L69,42L39,42z", fill="#FFC107"),
        dict(d="M46.5,28L61.5,28L65,35L43,35z", fill="#FF4081"),
        dict(d=circle(54, 13.5, 3.6), fill="#FF4081"),
        dict(d=circle(22, 24, 2.6), fill="#FF4081"), dict(d=circle(88, 30, 2.6), fill="#FFC107"), dict(d=circle(90, 82, 2.6), fill="#FF4081"),
        dict(d=circle(18, 80, 2.4), fill="#FFC107"), dict(d=star(84, 18, 4), fill="#FFFFFF")]),
    ("independence", "Biało-czerwona", "Red and white", lin(0, 0, 108, 108, (0, "#E0173A"), (1, "#8E0C23")), [
        dict(d=EMBLEM, fill="#FFFFFF", evenodd=True),
        dict(d="M20,26L88,26", stroke="#FFFFFF", sw=3, alpha=0.0), dict(d=star(88, 28, 5), fill="#FFFFFF"), dict(d=star(20, 84, 4), fill="#FFFFFF")]),
]

# ── dni okolicznościowe (±2 dni): id, nazwa PL, nazwa EN, reguła, ikona, motyw ─────────────────────────────────
# reguła: ("fixed", miesiąc, dzień) albo ("easter", przesunięcie_w_dniach)
OCCASION_WINDOW = 2
OCCASIONS = [
    ("newyear",      "Sylwester i Nowy Rok",   "New Year's Eve",   ("fixed", 12, 31), "newyear",      "newyear"),
    ("valentine",    "Walentynki",             "Valentine's Day",  ("fixed", 2, 14),  "valentine",    "valentine"),
    ("easter",       "Wielkanoc",              "Easter",           ("easter", 0),     "easter",       "easter"),
    ("music",        "Dzień Muzyki",           "Music Day",        ("fixed", 6, 21),  "neon",         "noir_neon"),
    ("independence", "Dzień Niepodległości",   "Independence Day", ("fixed", 11, 11), "independence", "independence"),
    ("halloween",    "Halloween",              "Halloween",        ("fixed", 10, 31), "halloween",    "halloween"),
    ("mikolajki",    "Mikołajki",              "St. Nicholas Day", ("fixed", 12, 6),  "christmas",    "christmas"),
    ("christmas",    "Boże Narodzenie",        "Christmas",        ("fixed", 12, 24), "christmas",    "christmas"),
    ("birthday",     "Urodziny Piterfy",       "Piterfy birthday", ("fixed", 10, 12), "birthday",     "birthday"),
]
