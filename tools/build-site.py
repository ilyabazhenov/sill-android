#!/usr/bin/env python3
"""Builds the published site: one page per language, so each has its own URL.

site/index.html holds both languages, every phrase as <span class="ru"> and
<span class="en">, and works on its own with an in-page switch. Search engines
want one language per URL, so this writes the English page to / and the Russian
one to /ru/, each with only its own text, its own head (the data-en values of
site/index.html), hreflang links between them, a link as the language switch and
the app's structured data. Then robots.txt and sitemap.xml.

Usage: build-site.py site _site   (after stamp-release.py, so the version is current)
"""
import html
import json
import re
import shutil
import sys
from pathlib import Path

ORIGIN = "https://usesash.app"
PAGES = {"en": "/", "ru": "/ru/"}
LANGS = {"ru": "Русский", "en": "English"}

src, out = Path(sys.argv[1]), Path(sys.argv[2])
if out.exists():
    shutil.rmtree(out)
shutil.copytree(src, out)
source = (src / "index.html").read_text(encoding="utf-8")

SPAN = re.compile(r"<span\b[^>]*>|</span>")


def strip_lang(page, lang):
    """Removes every <span class="lang">…</span>, nested spans included."""
    opener = f'<span class="{lang}">'
    parts, pos = [], 0
    while (start := page.find(opener, pos)) != -1:
        depth, end = 0, None
        for m in SPAN.finditer(page, start):
            depth += -1 if m.group().startswith("</") else 1
            if depth == 0:
                end = m.end()
                break
        if end is None:
            sys.exit(f"unclosed {opener} at {start}")
        parts.append(page[pos:start])
        pos = end
    parts.append(page[pos:])
    page = "".join(parts)
    if f'class="{lang}"' in page:
        sys.exit(f'class="{lang}" left in the page outside a plain span')
    return page


def head_for(page, lang):
    """Takes each data-en value on the English page and drops the attribute everywhere."""
    def title(m):
        return f"<title>{m.group(1) if lang == 'en' else m.group(2)}</title>"
    page = re.sub(r'<title data-en="([^"]*)">([^<]*)</title>', title, page)

    def tag(m):
        t, value = m.group(0), m.group(1)
        t = t.replace(f' data-en="{value}"', "")
        if lang == "en":
            attr = "href" if t.startswith("<link") else "content"
            t = re.sub(rf'\b{attr}="[^"]*"', lambda _: f'{attr}="{value}"', t, count=1)
        return t
    page = re.sub(r'<(?:meta|link)\b[^>]*\sdata-en="([^"]*)"[^>]*>', tag, page)

    # tags only one language needs, such as the Cyrillic font preload
    def only(m):
        return m.group(0).replace(f' data-only="{m.group(1)}"', "") if m.group(1) == lang else ""
    page = re.sub(r'<[^>]*\sdata-only="(\w+)"[^>]*>\n?', only, page)
    return re.sub(r"<!-- The English page is built[^>]*-->\n", "", page)


def switch_for(page, lang):
    """The RU / EN buttons become links to the two pages."""
    def link(m):
        l = m.group(1)
        here = ' aria-current="page"' if l == lang else ""
        return (f'<a id="lang-{l}" data-lang="{l}" href="{PAGES[l]}" hreflang="{l}" lang="{l}"'
                f' title="{LANGS[l]}"{here}>{l.upper()}</a>')
    page, n = re.subn(r'<button type="button" id="lang-(\w+)" data-lang="\1" aria-pressed="\w+">\w+</button>', link, page)
    if n != 2:
        sys.exit(f"found {n} language buttons, expected 2")
    return page


def structured_data(page, lang):
    def meta(name):
        return html.unescape(re.search(rf'<meta (?:name|property)="{name}" content="([^"]*)"', page).group(1))
    version = re.search(r'data-release="version">([^<]*)<', page).group(1)
    apk = re.search(r'<a\b[^>]*\bdata-release="href"[^>]*\bhref="([^"]*)"', page).group(1)
    data = {
        "@context": "https://schema.org",
        "@type": "SoftwareApplication",
        "name": "Sash",
        "url": ORIGIN + PAGES[lang],
        "description": meta("description"),
        "inLanguage": lang,
        "image": meta("og:image"),
        "applicationCategory": "UtilitiesApplication",
        "operatingSystem": "Android",
        "softwareVersion": version,
        "downloadUrl": apk,
        "license": "https://www.gnu.org/licenses/gpl-3.0.html",
        "isAccessibleForFree": True,
        "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        "sameAs": ["https://github.com/ilyabazhenov/sill-android"],
    }
    script = json.dumps(data, ensure_ascii=False, indent=1).replace("</", "<\\/")
    return page.replace("</head>", f'<script type="application/ld+json">\n{script}\n</script>\n</head>', 1)


def build(lang):
    other = "en" if lang == "ru" else "ru"
    page = strip_lang(source, other)
    page = head_for(page, lang)
    page = switch_for(page, lang)
    # aria-label="Theme / Тема": English first, Russian after the slash
    page = re.sub(r'aria-label="([^"/]+) / ([^"/]+)"',
                  lambda m: f'aria-label="{m.group(1) if lang == "en" else m.group(2)}"', page)
    page = page.replace('<html lang="ru">', f'<html lang="{lang}" data-lang="{lang}" data-page-lang="{lang}">', 1)
    page = structured_data(page, lang)
    depth = PAGES[lang].count("/") - 1
    if depth:
        # /ru/ sits a folder down: its relative links to fonts and icons go up one
        up = "../" * depth
        page = re.sub(r'\b(href|src)="(?!#|[a-z]+:|/)', rf'\1="{up}', page)
        page = page.replace("url(fonts/", f"url({up}fonts/")
    path = out / PAGES[lang].strip("/") / "index.html"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(page, encoding="utf-8")
    print(f"{lang}: {path} ({len(page) // 1024} KB)")


for lang in PAGES:
    build(lang)

alternates = "".join(
    f'\n    <xhtml:link rel="alternate" hreflang="{l}" href="{ORIGIN}{p}"/>' for l, p in PAGES.items()
) + f'\n    <xhtml:link rel="alternate" hreflang="x-default" href="{ORIGIN}{PAGES["en"]}"/>'
urls = "".join(f"\n  <url>\n    <loc>{ORIGIN}{p}</loc>{alternates}\n  </url>" for p in PAGES.values())
(out / "sitemap.xml").write_text(
    '<?xml version="1.0" encoding="UTF-8"?>\n'
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">'
    f"{urls}\n</urlset>\n", encoding="utf-8")
(out / "robots.txt").write_text(f"User-agent: *\nAllow: /\n\nSitemap: {ORIGIN}/sitemap.xml\n", encoding="utf-8")
