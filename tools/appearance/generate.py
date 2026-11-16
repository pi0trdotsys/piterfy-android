#!/usr/bin/env python3
"""Generuje pliki wyglądu Piterfy z catalog.py (ikony, motywy, okazje) dla Androida i sieci.

  python3 tools/appearance/generate.py            # zapisz pliki w repozytorium
  python3 tools/appearance/generate.py --preview  # dodatkowo arkusz podglądu SVG w katalogu podanym przez PREVIEW_DIR

Wynik jest zatwierdzany w repozytorium; ten skrypt jest powtarzalny (bloki między znacznikami są podmieniane).
"""
import colorsys
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import catalog as C  # noqa: E402

RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/eddyizm/tempus/appearance"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
WEB = ROOT / "connect/web"

STRINGS_PL = {
    "appearance_overridden": "Dziś działa motyw lub ikona okolicznościowa. Twój wybór zadziała po święcie albo po wyłączeniu automatu.",
    "appearance_title": "Wygląd Piterfy: motywy i ikony",
    "appearance_summary": "Motywy kolorystyczne, ikony aplikacji i automatyczne zmiany w dni okolicznościowe",
    "appearance_auto": "Automatycznie w dni okolicznościowe",
    "appearance_auto_summary": "Od 2 dni przed do 2 dni po święcie aplikacja sama przełącza motyw i ikonę",
    "appearance_themes": "Motywy",
    "appearance_icons": "Ikony aplikacji",
    "appearance_theme_classic": "Piterfy",
    "appearance_theme_classic_note": "Twoje ustawienia koloru",
    "appearance_today": "Dziś: %1$s",
    "appearance_none_today": "Dziś nie ma okazji. Następna: %1$s (%2$s)",
    "appearance_icon_note": "Ikona zmieni się, gdy zamkniesz aplikację",
    "appearance_close": "Gotowe",
}
STRINGS_EN = {
    "appearance_overridden": "A holiday theme or icon is active today. Your choice applies after the holiday or when you turn the automatic mode off.",
    "appearance_title": "Piterfy look: themes and icons",
    "appearance_summary": "Colour themes, app icons and automatic changes on special days",
    "appearance_auto": "Switch automatically on special days",
    "appearance_auto_summary": "From 2 days before to 2 days after a holiday the app switches its theme and icon by itself",
    "appearance_themes": "Themes",
    "appearance_icons": "App icons",
    "appearance_theme_classic": "Piterfy",
    "appearance_theme_classic_note": "Your own colour settings",
    "appearance_today": "Today: %1$s",
    "appearance_none_today": "No occasion today. Next: %1$s (%2$s)",
    "appearance_icon_note": "The icon changes when you close the app",
    "appearance_close": "Done",
}


def argb(hex_color, alpha=1.0):
    h = hex_color.lstrip("#")
    return "#{:02X}{}".format(round(alpha * 255), h.upper())


def mix(a, b, t):
    """Miesza dwa kolory #RRGGBB: t=0 to a, t=1 to b."""
    pa = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    pb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(x + (y - x) * t):02X}" for x, y in zip(pa, pb))


def luminance(hex_color):
    r, g, b = [int(hex_color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def on_color(hex_color):
    return "#000000" if luminance(hex_color) > 0.5 else "#FFFFFF"


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'")


# ── ikony: Android (wektory) ─────────────────────────────────────────────────────────────────────────────────────

def vec_path(shape, indent="    "):
    attrs = [f'android:pathData="{shape["d"]}"']
    fill = shape.get("fill")
    alpha = shape.get("alpha", 1.0)
    lines = []
    if shape.get("evenodd"):
        attrs.append('android:fillType="evenOdd"')
    if fill is not None and not isinstance(fill, tuple):
        attrs.append(f'android:fillColor="{argb(fill)}"')
        if alpha != 1.0:
            attrs.append(f'android:fillAlpha="{alpha:g}"')
    if shape.get("stroke"):
        attrs.append(f'android:strokeColor="{argb(shape["stroke"])}"')
        attrs.append(f'android:strokeWidth="{shape.get("sw", 1):g}"')
        if alpha != 1.0 and fill is None:
            attrs.append(f'android:strokeAlpha="{alpha:g}"')
        if shape.get("round"):
            attrs.append('android:strokeLineCap="round"')
    head = f"{indent}<path\n" + "".join(f"{indent}    {a}\n" for a in attrs).rstrip("\n")
    if isinstance(fill, tuple):
        _, x1, y1, x2, y2, stops = fill
        grad = [f'{indent}    <aapt:attr name="android:fillColor">',
                f'{indent}        <gradient android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}" android:type="linear">']
        grad += [f'{indent}            <item android:offset="{o:g}" android:color="{argb(c)}" />' for o, c in stops]
        grad += [f"{indent}        </gradient>", f"{indent}    </aapt:attr>"]
        return head + ">\n" + "\n".join(grad) + f"\n{indent}</path>\n"
    return head + " />\n"


def vector(shapes, comment):
    body = "".join(vec_path(s) for s in shapes)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {comment}. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n' + body + "</vector>\n")


def icon_files():
    out = {}
    for icon_id, _pl, _en, bg, fg in C.ICONS:
        if fg is None:
            continue
        bg_shape = dict(d="M0,0h108v108h-108z", fill=bg)
        out[RES / f"drawable/ic_launcher_bg_{icon_id}.xml"] = vector([bg_shape], f"Piterfy: tło ikony „{icon_id}”")
        out[RES / f"drawable/ic_launcher_fg_{icon_id}.xml"] = vector(fg, f"Piterfy: pierwszy plan ikony „{icon_id}”")
        out[RES / f"mipmap-anydpi-v26/ic_launcher_{icon_id}.xml"] = (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/ic_launcher_bg_{icon_id}"/>\n'
            f'    <foreground android:drawable="@drawable/ic_launcher_fg_{icon_id}"/>\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n'
            "</adaptive-icon>\n")
    return out


# ── ikony: SVG (web i podgląd) ───────────────────────────────────────────────────────────────────────────────────

def svg_paint(shape_id, fill, defs):
    if isinstance(fill, tuple):
        _, x1, y1, x2, y2, stops = fill
        gid = f"g{shape_id}"
        s = "".join(f'<stop offset="{o:g}" stop-color="{c}"/>' for o, c in stops)
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{s}</linearGradient>')
        return f"url(#{gid})"
    return fill


def svg_icon(icon_id, rounded=True, prefix="i"):
    _, _, _, bg, fg = next(i for i in C.ICONS if i[0] == icon_id)
    if fg is None:  # klasyk: istniejąca ikona
        fg = [dict(d=C.EMBLEM, fill="#1DB954", evenodd=True)]
        bg = "#000000"
    defs, body = [], []
    rect = f'<rect width="108" height="108" rx="{24 if rounded else 0}" fill="{svg_paint(prefix + "bg", bg, defs)}"/>'
    for n, s in enumerate(fg):
        attrs = [f'd="{s["d"]}"']
        fill = s.get("fill")
        attrs.append(f'fill="{svg_paint(prefix + str(n), fill, defs) if fill is not None else "none"}"')
        if s.get("evenodd"):
            attrs.append('fill-rule="evenodd"')
        if s.get("stroke"):
            attrs.append(f'stroke="{s["stroke"]}" stroke-width="{s.get("sw", 1):g}"')
            if s.get("round"):
                attrs.append('stroke-linecap="round"')
        if s.get("alpha", 1.0) != 1.0:
            attrs.append(f'opacity="{s["alpha"]:g}"')
        body.append("<path " + " ".join(attrs) + "/>")
    d = f"<defs>{''.join(defs)}</defs>" if defs else ""
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">{d}{rect}{"".join(body)}</svg>'


# ── aliasy launchera ─────────────────────────────────────────────────────────────────────────────────────────────

def alias_class(icon_id):
    return "Icon" + "".join(p.capitalize() for p in icon_id.split("_"))


def manifest_block():
    out = ["        <!-- piterfy-icons:begin (wygenerowane przez tools/appearance/generate.py) -->\n"]
    for icon_id, *_ in C.ICONS:
        mip = "@mipmap/ic_launcher" if icon_id == "classic" else f"@mipmap/ic_launcher_{icon_id}"
        out.append(
            f'        <activity-alias\n            android:name=".{alias_class(icon_id)}"\n'
            f'            android:targetActivity=".ui.activity.MainActivity"\n'
            f'            android:enabled="{"true" if icon_id == "classic" else "false"}"\n'
            f'            android:exported="true"\n            android:icon="{mip}"\n            android:roundIcon="{mip}"\n'
            f'            android:label="@string/app_name">\n'
            '            <intent-filter>\n'
            '                <action android:name="android.intent.action.MAIN" />\n'
            '                <category android:name="android.intent.category.LAUNCHER" />\n'
            '            </intent-filter>\n        </activity-alias>\n')
    out.append("        <!-- piterfy-icons:end -->\n")
    return "".join(out)


def patch_between(path, begin, end, block, insert_before=None, append=False):
    text = path.read_text(encoding="utf-8")
    pattern = re.compile(re.escape(begin) + r".*?" + re.escape(end) + r"\n?", re.S)
    if pattern.search(text):
        text = pattern.sub(lambda m: block, text, count=1)
    elif append:
        text = text.rstrip("\n") + "\n\n" + block
    else:
        assert insert_before and insert_before in text, f"{path}: brak znacznika i kotwicy"
        text = text.replace(insert_before, block + insert_before, 1)
    path.write_text(text, encoding="utf-8")


def drop_main_launcher_filter():
    """Ikona startowa należy teraz do aliasów, więc MainActivity traci własny filtr LAUNCHER (tylko raz)."""
    text = MANIFEST.read_text(encoding="utf-8")
    head, sep, tail = text.partition("        <!-- piterfy-icons:begin")
    flt = ('            <intent-filter>\n                <action android:name="android.intent.action.MAIN" />\n'
           '                <category android:name="android.intent.category.LAUNCHER" />\n            </intent-filter>\n')
    if flt in head:
        head = head.replace(flt, "", 1)
        MANIFEST.write_text(head + sep + tail, encoding="utf-8")


# ── motywy: Android ─────────────────────────────────────────────────────────────────────────────────────────────

def theme_colors(t):
    _id, _pl, _en, dark, seed, accent, accent2, bg, surface, high, text, muted = t
    mid = mix(surface, high, 0.5)
    return dict(
        background=bg, surface=surface, low=mix(bg, surface, 0.5), container=surface, containerHigh=mid, highest=high,
        variant=high, outline=mix(muted, bg, 0.35), outlineVariant=mix(high, muted, 0.2),
        onSurface=text, onVariant=muted,
        primary=accent, onPrimary=on_color(accent),
        primaryContainer=mix(accent, bg, 0.7) if dark else mix(accent, "#FFFFFF", 0.78),
        onPrimaryContainer=text if dark else mix(accent, "#000000", 0.5),
        secondary=accent2, onSecondary=on_color(accent2),
        secondaryContainer=mix(accent2, bg, 0.75) if dark else mix(accent2, "#FFFFFF", 0.8),
        onSecondaryContainer=text if dark else mix(accent2, "#000000", 0.5),
    )


def style_name(theme_id):
    return "ThemeOverlay.PiterfyTheme." + "".join(p.capitalize() for p in theme_id.split("_"))


def themes_xml():
    lines = ['<?xml version="1.0" encoding="utf-8"?>\n',
             "<!-- Piterfy: nakładki motywów. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. -->\n<resources>\n"]
    for t in C.THEMES:
        c = theme_colors(t)
        items = {
            "android:colorBackground": c["background"], "colorSurface": c["surface"], "colorOnSurface": c["onSurface"],
            "colorOnBackground": c["onSurface"], "colorSurfaceVariant": c["variant"], "colorOnSurfaceVariant": c["onVariant"],
            "colorSurfaceContainerLowest": c["background"], "colorSurfaceContainerLow": c["low"], "colorSurfaceContainer": c["container"],
            "colorSurfaceContainerHigh": c["containerHigh"], "colorSurfaceContainerHighest": c["highest"],
            "colorOutline": c["outline"], "colorOutlineVariant": c["outlineVariant"],
            "colorPrimary": c["primary"], "colorOnPrimary": c["onPrimary"], "colorPrimaryContainer": c["primaryContainer"],
            "colorOnPrimaryContainer": c["onPrimaryContainer"], "colorSecondary": c["secondary"], "colorOnSecondary": c["onSecondary"],
            "colorSecondaryContainer": c["secondaryContainer"], "colorOnSecondaryContainer": c["onSecondaryContainer"],
        }
        lines.append(f'    <style name="{style_name(t[0])}" parent="">\n')
        lines += [f'        <item name="{k}">{v}</item>\n' for k, v in items.items()]
        lines.append("    </style>\n")
    lines.append("</resources>\n")
    return "".join(lines)


def strings_xml(extra):
    lines = ['<?xml version="1.0" encoding="utf-8"?>\n',
             "<!-- Piterfy: nazwy motywów, ikon i okazji. Wygenerowane przez tools/appearance/generate.py. -->\n<resources>\n"]
    for k, v in extra.items():
        lines.append(f'    <string name="{k}">{esc(v)}</string>\n')
    lines.append("</resources>\n")
    return "".join(lines)


def all_strings(lang):
    idx = 1 if lang == "pl" else 2
    s = dict(STRINGS_PL if lang == "pl" else STRINGS_EN)
    for t in C.THEMES:
        s[f"appearance_theme_{t[0]}"] = t[idx]
    for i in C.ICONS:
        s[f"appearance_icon_{i[0]}"] = i[idx]
    for o in C.OCCASIONS:
        s[f"appearance_occasion_{o[0]}"] = o[idx]
    return s


# ── Kotlin ──────────────────────────────────────────────────────────────────────────────────────────────────────

def kotlin_hex(h):
    return "0xFF" + h.lstrip("#").upper()


def kotlin_catalog():
    L = ["package com.eddyizm.tempus.appearance\n\n",
         "import com.eddyizm.tempus.R\n\n",
         "/** Piterfy: katalog motywów i ikon. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. */\n",
         "object PiterfyCatalog {\n",
         "    data class Theme(val id: String, val nameRes: Int, val dark: Boolean, val seed: String, val accent: Long, val accent2: Long,\n"
         "                     val background: Long, val surface: Long, val text: Long, val overlay: Int)\n",
         "    data class Icon(val id: String, val nameRes: Int, val alias: String, val iconRes: Int)\n\n",
         "    val themes: List<Theme> = listOf(\n"]
    for t in C.THEMES:
        _id, _pl, _en, dark, seed, accent, accent2, bg, surface, high, text, muted = t
        L.append(f'        Theme("{_id}", R.string.appearance_theme_{_id}, {str(dark).lower()}, "{seed}", {kotlin_hex(accent)}, {kotlin_hex(accent2)}, '
                 f"{kotlin_hex(bg)}, {kotlin_hex(surface)}, {kotlin_hex(text)}, R.style.{style_name(_id).replace('.', '_')}),\n")
    L.append("    )\n\n    val icons: List<Icon> = listOf(\n")
    for icon_id, *_ in C.ICONS:
        mip = "ic_launcher" if icon_id == "classic" else f"ic_launcher_{icon_id}"
        L.append(f'        Icon("{icon_id}", R.string.appearance_icon_{icon_id}, "{alias_class(icon_id)}", R.mipmap.{mip}),\n')
    L.append("    )\n}\n")
    return "".join(L)


def kotlin_occasion_data():
    L = ["package com.eddyizm.tempus.appearance\n\n",
         "/** Piterfy: dni okolicznościowe. Wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie. */\n",
         "internal object PiterfyOccasionData {\n    const val WINDOW_DAYS = %d\n    val all: List<PiterfyOccasions.Occasion> = listOf(\n" % C.OCCASION_WINDOW]
    for oid, _pl, _en, rule, icon, theme in C.OCCASIONS:
        if rule[0] == "fixed":
            L.append(f'        PiterfyOccasions.Occasion("{oid}", PiterfyOccasions.Rule.Fixed({rule[1]}, {rule[2]}), "{icon}", "{theme}"),\n')
        else:
            L.append(f'        PiterfyOccasions.Occasion("{oid}", PiterfyOccasions.Rule.Easter({rule[1]}), "{icon}", "{theme}"),\n')
    L.append("    )\n}\n")
    return "".join(L)


# ── web ─────────────────────────────────────────────────────────────────────────────────────────────────────────

# Motywy jasne nie są dostępne w przeglądarce (arkusz Noir jest ciemny); okazja z jasnym motywem dostaje ciemny odpowiednik.
WEB_ALT = {"easter": "las", "papier": "zloto", "sakura": "valentine"}


def web_themes():
    return [t for t in C.THEMES if t[3]]


def web_css():
    L = ["/* piterfy-themes:begin (wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie) */\n"]
    for t in web_themes():
        tid, _pl, _en, dark, seed, accent, accent2, bg, surface, high, text, muted = t
        L.append(
            f'html[data-pf-theme="{tid}"] {{ --pf-bg: {bg}; --pf-panel: {surface}; --pf-card: {surface}; --pf-card-hover: {high}; '
            f"--pf-text: {text}; --pf-muted: {muted}; --pf-accent: {accent}; --pf-accent-2: {accent2}; "
            f"--pf-tint: {mix(surface, accent, 0.22)}; --pf-tint-2: {mix(surface, accent, 0.34)}; --pf-deep: {mix(bg, accent, 0.45)}; "
            f"--pf-accent-hover: {mix(accent, '#FFFFFF', 0.18)}; }}\n"
            f'html[data-pf-theme="{tid}"] .pf-home {{ --pf-h-text: {text}; --pf-h-muted: {muted}; --pf-h-surface: {mix(bg, text, 0.07)}; '
            f"--pf-h-hover: {mix(bg, text, 0.13)}; --pf-h-green: {accent}; }}\n"
            f'html[data-pf-theme="{tid}"]:not([data-pf-light]) .pf-hello {{ background: linear-gradient(92deg, {text} 25%, {accent} 85%); '
            f"-webkit-background-clip: text; background-clip: text; }}\n"
            f'html[data-pf-theme="{tid}"] .pf-home-head::before {{ background: radial-gradient(closest-side, {accent}A6, transparent); }}\n'
            f'html[data-pf-theme="{tid}"] .pf-home-head::after {{ background: radial-gradient(closest-side, {accent2}80, transparent); }}\n'
            f'html[data-pf-theme="{tid}"] .pf-play {{ background: {accent}; color: {on_color(accent)}; }}\n'
            f'html[data-pf-theme="{tid}"] body .react-jinke-music-player-main .music-player-panel {{ background: {surface}CC !important; }}\n')
    L.append("/* piterfy-themes:end */\n")
    return "".join(L)


def web_js_data():
    import json
    themes = [dict(id=t[0], name=t[1], dark=True, accent=t[5], accent2=t[6], bg=t[7], surface=t[8], text=t[10]) for t in web_themes()]
    icons = [dict(id=i[0], name=i[1], svg=svg_icon(i[0])) for i in C.ICONS]
    occasions = []
    for oid, pl, _en, rule, icon, theme in C.OCCASIONS:
        occasions.append(dict(id=oid, name=pl, rule=list(rule), icon=icon, theme=WEB_ALT.get(theme, theme) if not next(t for t in C.THEMES if t[0] == theme)[3] else theme))
    data = dict(window=C.OCCASION_WINDOW, themes=themes, icons=icons, occasions=occasions)
    return ("  // piterfy-appearance-data:begin (wygenerowane przez tools/appearance/generate.py; nie edytuj ręcznie)\n"
            "  const APPEARANCE_DATA = " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + ";\n"
            "  // piterfy-appearance-data:end\n")


# ── podgląd ─────────────────────────────────────────────────────────────────────────────────────────────────────

def preview_sheet():
    cells = []
    for n, (icon_id, pl, *_rest) in enumerate(C.ICONS):
        x, y = (n % 4) * 150 + 20, (n // 4) * 170 + 20
        cells.append(f'<g transform="translate({x},{y})"><g transform="scale(1.1)">{svg_icon(icon_id, prefix=f"p{n}").replace("<svg ", "<svg width=\"108\" height=\"108\" ")}</g>'
                     f'<text x="59" y="140" fill="#ddd" font-size="14" text-anchor="middle" font-family="sans-serif">{pl}</text></g>')
    h = ((len(C.ICONS) + 3) // 4) * 170 + 40
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="640" height="{h}" viewBox="0 0 640 {h}"><rect width="640" height="{h}" fill="#1b1b1b"/>{"".join(cells)}</svg>'


def theme_sheet():
    cells = []
    for n, t in enumerate(C.THEMES):
        x, y = (n % 3) * 210 + 15, (n // 3) * 110 + 15
        cells.append(f'<g transform="translate({x},{y})"><rect width="195" height="95" rx="10" fill="{t[7]}" stroke="#444"/>'
                     f'<rect x="10" y="10" width="175" height="30" rx="6" fill="{t[8]}"/><rect x="10" y="48" width="80" height="12" rx="6" fill="{t[5]}"/>'
                     f'<rect x="96" y="48" width="50" height="12" rx="6" fill="{t[6]}"/><text x="12" y="82" fill="{t[10]}" font-size="13" font-family="sans-serif">{t[1]}</text></g>')
    h = ((len(C.THEMES) + 2) // 3) * 110 + 30
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="660" height="{h}"><rect width="660" height="{h}" fill="#222"/>{"".join(cells)}</svg>'


def main():
    written = []

    def write(path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        written.append(path.relative_to(ROOT))

    for path, content in icon_files().items():
        write(path, content)
    write(RES / "values/piterfy_themes.xml", themes_xml())
    write(RES / "values/piterfy_appearance.xml", strings_xml(all_strings("en")))
    write(RES / "values-pl/piterfy_appearance.xml", strings_xml(all_strings("pl")))
    write(JAVA / "PiterfyCatalog.kt", kotlin_catalog())
    write(JAVA / "PiterfyOccasionData.kt", kotlin_occasion_data())
    patch_between(MANIFEST, "        <!-- piterfy-icons:begin", "        <!-- piterfy-icons:end -->", manifest_block(),
                  insert_before="        <activity\n            android:name=\".ui.login.LoginActivity\"")
    drop_main_launcher_filter()
    patch_between(WEB / "piterfy.css", "/* piterfy-themes:begin", "/* piterfy-themes:end */", web_css(), append=True)
    patch_between(WEB / "library.js", "  // piterfy-appearance-data:begin", "  // piterfy-appearance-data:end", web_js_data(),
                  insert_before="  // ── inteligentne odtwarzanie: czysty rdzeń wyboru (bez sieci, testowany w smart-core.test.cjs) ──\n")
    if "--preview" in sys.argv:
        d = Path(os.environ.get("PREVIEW_DIR", "/tmp"))
        (d / "icons.svg").write_text(preview_sheet(), encoding="utf-8")
        (d / "themes.svg").write_text(theme_sheet(), encoding="utf-8")
        written.append(d / "icons.svg")
    print("\n".join(str(p) for p in written))


if __name__ == "__main__":
    main()
