"""
OCR and Metadata Extraction
Rule-based parsers for Russian book info pages, one per ГОСТ standard.

Parser hierarchy (tried in order, best result wins):
  1. ГОСТ 7.1-2003       — books 2003–2017
  2. ГОСТ Р 7.0.100-2018 — books 2018+
  3. ГОСТ 7.1-84         — books pre-2003
  4. ГОСТ Р 7.0.5-2008   — reference-list style (fallback)
  5. Field-level regex    — last resort, field by field
"""

import re
import io
import base64
import logging
import os
from typing import Optional

import pytesseract
from PIL import Image

logger = logging.getLogger(__name__)

TESSERACT_CMD = os.getenv("TESSERACT_CMD")
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD

# ========================================
# OCR
# ========================================

def image_from_base64(b64: str) -> Image.Image:
    from PIL import ImageOps
    img = Image.open(io.BytesIO(base64.b64decode(b64)))
    return ImageOps.exif_transpose(img)

def ocr_image(image: Image.Image, lang: str) -> str:
    return pytesseract.image_to_string(image, lang=lang)


def ocr_info_page(image: Image.Image) -> str:
    """
    Two-pass Russian OCR optimised for book copyright/info pages.

    Pass 1 — full-page OCR (no preprocessing — dewarping distorts pages).
              Captures the main body: citation, annotation, editorial block.

    Pass 2 — raw top-left crop (~50% × 20%), PSM 6, NO preprocessing.
              Preprocessing degrades the small catalog-block text.
              Prepended to the main text so the structured parser finds
              УДК / ББК at the very start.

    English OCR (for ISBN detection) is handled by the caller and passed
    separately to extract_metadata_from_info_page as ocr_eng.
    """
    main_text = ocr_image(image, 'rus')

    # Pass 2: raw catalog block crop (top-left corner)
    w, h = image.size
    catalog_crop = image.crop((0, 0, int(w * 0.50), int(h * 0.20)))
    catalog_text = pytesseract.image_to_string(
        catalog_crop, lang='rus', config='--psm 6'
    )

    if re.search(r'УДК|ББК', catalog_text):
        return catalog_text.strip() + '\n\n' + main_text

    return main_text


def _whitepoint_normalize(image: Image.Image) -> Image.Image:
    """
    Scale image brightness so the 99th-percentile pixel = 255 (white point).

    Aged / yellowed paper reflects ~75-85% of white instead of 100%.  Scaling
    so the brightest region becomes pure white simultaneously brightens the
    background and darkens the text — greatly improving binarization quality
    for books printed on yellowed stock.

    Skipped when:
      • the image is already near-white (scale ≤ 1.05) — nothing to correct.
      • the 99th percentile is near-black (< 10) — fully black image, skip.
    """
    import numpy as np
    arr = np.array(image.convert('RGB')).astype(float)
    brightness = arr.max(axis=2)
    wp = np.percentile(brightness, 99)
    if wp < 10:
        return image          # fully black — skip
    scale = 255.0 / wp
    if scale <= 1.15:
        return image          # already near-white (paper < 13% off-white) — skip
    return Image.fromarray(np.clip(arr * scale, 0, 255).astype('uint8'))


def _binarize(image: Image.Image) -> Image.Image:
    """
    Convert to high-contrast black-on-white using Otsu's thresholding.

    Finds the greyscale level that best separates text (dark) from background
    (light) by maximising inter-class variance.  Pixels above the threshold
    become white (255), below become black (0).

    Pure numpy — no OpenCV required.
    """
    import numpy as np
    gray = np.array(image.convert('L'))
    hist = np.bincount(gray.flatten(), minlength=256).astype(float)
    total = gray.size
    cum_n   = np.cumsum(hist)           # cumulative pixel count
    cum_val = np.cumsum(hist * np.arange(256))  # cumulative intensity sum

    max_var, threshold = 0.0, 128
    for t in range(1, 255):
        w0 = cum_n[t]
        w1 = total - w0
        if w0 == 0 or w1 == 0:
            continue
        m0 = cum_val[t] / w0
        m1 = (cum_val[-1] - cum_val[t]) / w1
        var = (w0 * w1) * (m0 - m1) ** 2
        if var > max_var:
            max_var = var
            threshold = t

    binary = np.where(gray > threshold, 255, 0).astype('uint8')
    return Image.fromarray(binary)


def ocr_colored_text_page(image: Image.Image) -> str:
    """
    OCR for title pages where text is printed in colour (e.g. blue) on a
    light background — a common style in old Soviet books.

    Passes tried in order; the first that returns >= 20 characters is used:
      1. R − B channel difference → binarized.  Best for blue/red text.
      2. Greyscale → binarized (Otsu).  Best for black text on aged paper.
      3. Raw greyscale.  Fallback for low-contrast images.
    """
    import numpy as np

    # Correct yellowed / aged paper before OCR: scale so 99th-percentile = white.
    # This makes the background brighter and text darker, improving all passes.
    image = _whitepoint_normalize(image)

    arr = np.array(image.convert('RGB'))

    # Pass 1: colour channel subtraction (R − B) then binarize
    diff = np.clip(arr[:, :, 0].astype(int) - arr[:, :, 2].astype(int), 0, 255).astype('uint8')
    text1 = pytesseract.image_to_string(_binarize(Image.fromarray(diff)), lang='rus')
    if len(text1.strip()) >= 20:
        return text1

    # Pass 2: greyscale + binarize
    text2 = pytesseract.image_to_string(_binarize(image), lang='rus')
    if len(text2.strip()) >= 20:
        return text2

    # Pass 3: raw greyscale fallback
    return pytesseract.image_to_string(image.convert('L'), lang='rus')


def _parse_title_page_hocr(image: Image.Image) -> dict:
    """
    Font-size-aware title-page parser using Tesseract HOCR output.

    Old Soviet books (no GOST info page) follow this layout:
      top line(s)  → author  (small caps / regular size)
      largest text → book title
      footer       → publisher + year

    Tesseract HOCR provides word bounding boxes so we can measure each
    line's average character height and identify the largest-font group
    (= title) — something impossible with plain OCR text.

    For books where text is printed in colour (blue, red) HOCR may miss
    top/middle lines entirely.  In that case this function returns only
    the footer data (year + publisher); the caller is expected to fill the
    remaining fields from `ocr_colored_text_page` + `_parse_title_page`.
    """
    import xml.etree.ElementTree as ET

    # Binarize before HOCR: improves bounding-box detection on aged paper
    # and coloured-text pages.  Positions and sizes are preserved; only
    # contrast changes.
    hocr_img = _binarize(image)

    hocr_bytes = pytesseract.image_to_pdf_or_hocr(hocr_img, lang='rus', extension='hocr')
    # Strip default namespace so ElementTree iteration works without prefixes
    hocr_str = hocr_bytes.decode('utf-8').replace(
        'xmlns="http://www.w3.org/1999/xhtml"', ''
    )
    root = ET.fromstring(hocr_str)

    # Collect lines: (y_top, avg_word_height, text)
    lines = []
    for span in root.iter('span'):
        if 'ocr_line' not in (span.get('class') or ''):
            continue
        bbox_m = re.search(r'bbox (\d+) (\d+) (\d+) (\d+)', span.get('title', ''))
        if not bbox_m:
            continue
        ly1 = int(bbox_m.group(2))

        words, heights = [], []
        for w in span.iter('span'):
            if 'ocrx_word' not in (w.get('class') or ''):
                continue
            wt = (w.text or '').strip()
            if not wt:
                continue
            conf_m = re.search(r'x_wconf (\d+)', w.get('title', ''))
            if conf_m and int(conf_m.group(1)) < 15:
                continue
            wb = re.search(r'bbox (\d+) (\d+) (\d+) (\d+)', w.get('title', ''))
            if wb:
                heights.append(int(wb.group(4)) - int(wb.group(2)))
            words.append(wt)

        if not words:
            continue
        avg_h = sum(heights) / len(heights) if heights else 0
        if avg_h < 10:
            continue   # likely OCR noise
        lines.append((ly1, avg_h, ' '.join(words)))

    if not lines:
        return _empty()

    lines.sort(key=lambda x: x[0])  # top → bottom

    # Drop editorial credit lines — they confuse title/author detection.
    # These lines are never part of the title or author block.
    _EDITORIAL_RE = re.compile(
        r'^(Под\s+редакцией|Редактор|Ответственный\s+редактор|Научный\s+редактор'
        r'|Составитель|Перевод|Переводчик|Художник|Оформление|Корректор'
        r'|Рецензент|Предисловие'
        r'|проф\.|доц\.|д-р\s|канд\.\s'
        r'|ИЗДАНИЕ\s+(ВТОРОЕ|ТРЕТЬЕ|ЧЕТВЁРТОЕ|ПЯТОЕ|\d+[-–]е)'
        r'|Издание\s+(второе|третье|четвёртое|пятое|\d+[-–]е)'
        r'|ИСПРАВЛЕННОЕ|ДОПОЛНЕННОЕ|ПЕРЕРАБОТАННОЕ)',
        re.IGNORECASE,
    )
    lines = [(y1, h, t) for y1, h, t in lines if not _EDITORIAL_RE.match(t)]

    if not lines:
        return _empty()

    img_h = image.size[1]
    year_re = re.compile(r'\b(1[5-9]\d{2}|20\d{2})\b')
    data = _empty()

    # ── Footer: find year line; extend upward to capture publisher lines ─────
    # Old books have a compact colophon block (2-3 lines) at the very bottom.
    # Strategy: find the first line with a year below 55% of image height,
    # then extend the footer block upward as long as:
    #   • the preceding line is in the bottom 30% of the image, AND
    #   • the vertical gap is tight (< 8% of image height)
    # Cap at 2 upward extensions to avoid swallowing subtitle/credit lines.
    footer_start = None
    for i, (y1, h, t) in enumerate(lines):
        if y1 > img_h * 0.55 and year_re.search(t):
            footer_start = i
            break

    if footer_start is not None:
        for _ in range(2):
            if footer_start == 0:
                break
            prev_y = lines[footer_start - 1][0]
            curr_y = lines[footer_start][0]
            gap    = curr_y - prev_y
            if gap < img_h * 0.08 and prev_y > img_h * 0.70:
                footer_start -= 1
            else:
                break

    footer_lines = lines[footer_start:] if footer_start is not None else []
    body_lines   = lines[:footer_start] if footer_start is not None else lines

    # Extract year and publisher from footer block
    if footer_lines:
        footer_text = ' '.join(t for _, _, t in footer_lines)
        ym = year_re.search(footer_text)
        if ym:
            data['year'] = int(ym.group(1))
            pub = re.sub(r'\b(1[5-9]\d{2}|20\d{2})\b.*', '', footer_text).strip(' .,—–»«{|}')
            pub = re.sub(r'(?<!\w)\S(?!\w)', '', pub).strip()   # remove isolated noise chars
            # Strip leading city name — old books often print "Москва Издательство «X»"
            pub = re.sub(
                r'^(?:МОСКВА|ЛЕНИНГРАД|САНКТ.ПЕТЕРБУРГ|СПБ|МИНСК|КИЕВ|НОВОСИБИРСК|ХАРЬКОВ)'
                r'[\s.,;:—–]*',
                '', pub, flags=re.IGNORECASE
            ).strip(' .,—–»«')
            if pub and len(pub) > 3:
                data['publisher'] = pub

    # ── Title: largest-font lines in body (skip small translator/credit lines) ─
    if body_lines:
        max_h = max(h for _, h, _ in body_lines)
        title_threshold = max_h * 0.65

        first_large = next(
            (i for i, (_, h, _) in enumerate(body_lines) if h >= title_threshold), None
        )

        if first_large is not None:
            author_lines = [t for _, h, t in body_lines[:first_large]]
            # Title = only the large-font lines; skip small credit lines after them
            title_lines  = [t for _, h, t in body_lines[first_large:] if h >= title_threshold]
        else:
            # All similar size: first line = author, rest = title
            author_lines = [body_lines[0][2]]
            title_lines  = [t for _, _, t in body_lines[1:]]

        if author_lines:
            author_text = ' '.join(author_lines).strip()
            # Strip leading noise tokens before the first proper name word.
            # A proper name word starts with an uppercase Cyrillic letter and
            # contains ≥ 3 uppercase Cyrillic letters (handles ALL-CAPS names like
            # "ЭРНСТ ТЕОДОР АМАДЕЙ ГОФМАН").  Lowercase-initial tokens like
            # "м(мем)" and short mixed tokens like "Ач" are noise and skipped.
            parts = author_text.split()
            for start_idx, tok in enumerate(parts):
                if re.match(r'^[А-ЯЁ]', tok) and len(re.findall(r'[А-ЯЁ]', tok)) >= 3:
                    author_text = ' '.join(parts[start_idx:])
                    break
            # Strip trailing 1–2 char noise fragments
            author_text = re.sub(r'\s+\S{1,2}$', '', author_text).strip()
            data['author'] = _normalize_author(author_text)

        if title_lines:
            raw = re.sub(r'\s+[_|]\s+', ' ', ' '.join(title_lines)).strip()
            data['title'] = _clean_title(raw)

    # ── Top-crop retry when body lines are all in the bottom half ───────────
    # Tesseract can miss very-large title text at the top of a tall image while
    # still detecting smaller body/footer text below.  If every detected body
    # line is below the midpoint of the image, the title area was skipped —
    # retry on just the top 40% crop.  Guard: skip if we ARE a crop (h ≤ 800).
    # body_in_upper=True means we already have (or don't need) top-crop analysis:
    # • body_lines is empty → nothing to retry (colored OCR handles it) → True
    # • body_lines has at least one line above the midpoint → top area was seen → True
    # • body_lines exist but all are below midpoint → HOCR missed the top → False → retry
    body_in_upper = (not body_lines) or any(y1 < img_h * 0.5 for y1, _, _ in body_lines)
    if not body_in_upper and image.size[1] > 800:
        top_crop = image.crop((0, 0, image.size[0], int(img_h * 0.40)))
        try:
            crop_result = _parse_title_page_hocr(top_crop)
            if crop_result['title'] != 'unknown':
                data['title'] = crop_result['title']
            if crop_result['author'] != 'unknown':
                data['author'] = crop_result['author']
        except Exception:
            pass

    return data


def _parse_title_page(text: str) -> dict:
    """
    Layout-based parser for old-style title pages (pre-GOST info pages).
    Fallback when HOCR is unavailable.

    Old Soviet books often have no copyright page — the title page serves as
    the sole metadata source.  Layout:
        top lines   → author  (small caps, above the title)
        middle      → title   (largest text)
        bottom lines → publisher + year

    Algorithm: split into line-groups (separated by blank lines).
      • First group  → author
      • Last group   → publisher + year if it contains a 4-digit year
      • Middle groups → title (joined)
    """
    _EDITORIAL_RE = re.compile(
        r'^(Под\s+редакцией|Редактор|Ответственный\s+редактор|Научный\s+редактор'
        r'|Составитель|Перевод|Переводчик|Художник|Оформление|Корректор'
        r'|Рецензент|Предисловие'
        r'|проф\.|доц\.|д-р\s|канд\.\s'
        r'|ИЗДАНИЕ\s+(ВТОРОЕ|ТРЕТЬЕ|ЧЕТВЁРТОЕ|ПЯТОЕ|\d+[-–]е)'
        r'|Издание\s+(второе|третье|четвёртое|пятое|\d+[-–]е)'
        r'|ИСПРАВЛЕННОЕ|ДОПОЛНЕННОЕ|ПЕРЕРАБОТАННОЕ)',
        re.IGNORECASE,
    )
    data = _empty()
    groups = []
    current: list = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            if current:
                groups.append(current)
                current = []
        elif not _EDITORIAL_RE.match(s):
            current.append(s)
    if current:
        groups.append(current)

    if not groups:
        return data

    year_re = re.compile(r'\b(1[5-9]\d{2}|20\d{2})\b')

    # First group → author
    data['author'] = _normalize_author(' '.join(groups[0]))

    if len(groups) == 1:
        # Only one group — treat everything after the first line as title
        if len(groups[0]) > 1:
            data['title'] = _clean_title(' '.join(groups[0][1:]))
        return data

    # Last group → publisher + year if it looks like a colophon
    last = groups[-1]
    last_text = ' '.join(last)
    ym = year_re.search(last_text)
    if ym:
        data['year'] = int(ym.group(1))
        # Publisher: everything before the year on the same line, stripped of noise
        pub = re.sub(r'\b(1[5-9]\d{2}|20\d{2})\b.*', '', last_text).strip(' .,—–')
        if pub:
            data['publisher'] = pub
        title_groups = groups[1:-1]
    else:
        title_groups = groups[1:]

    if title_groups:
        raw_title = ' '.join(line for g in title_groups for line in g)
        # Remove standalone OCR noise tokens (_, |, single punctuation)
        raw_title = re.sub(r'\s+[_|]\s+', ' ', raw_title).strip()
        data['title'] = _clean_title(raw_title)

    return data


def ocr_isbn_from_image(image: Image.Image) -> str:
    """
    Scan the full image in horizontal strips using English OCR and return
    the first line that contains an ISBN pattern.

    Rationale: Tesseract silently stops reading a full tall image partway
    through when large blank areas separate text blocks.  Scanning in
    overlapping strips guarantees every part of the page is read.
    ISBN must be OCR-ed in English — Russian mode garbles 'ISBN' into
    '15ВМ', 'Г5ВМ', etc., making digit extraction unreliable.
    """
    w, h = image.size
    strip_h = max(h // 8, 300)   # 8 strips, each at least 300px tall
    step    = strip_h // 2        # 50% overlap

    isbn_re = re.compile(r'\bISBN\b', re.IGNORECASE)

    y = 0
    while y < h:
        strip = image.crop((0, y, w, min(y + strip_h, h)))
        text  = pytesseract.image_to_string(strip, lang='eng')
        for line in text.splitlines():
            if isbn_re.search(line) and line.strip():
                return line.strip()
        y += step

    return ""


def ocr_image_rgb_channels(image: Image.Image, lang: str) -> str:
    """
    Try OCR on multiple colour channels (normal + inverted).
    Useful for decorative covers where text may be on coloured backgrounds.
    Stops early once a result with >= 40 chars is found.

    NOTE: No preprocessing — cover images are artwork, not flat text pages.
    Dewarping/illumination correction destroys cover images.
    """
    from PIL import ImageOps

    if image.mode != "RGB":
        image = image.convert("RGB")

    def best_of(channel) -> str:
        norm = pytesseract.image_to_string(channel, lang=lang).strip()
        inv  = pytesseract.image_to_string(ImageOps.invert(channel), lang=lang).strip()
        return norm if len(norm) >= len(inv) else inv

    gray = image.convert("L")
    r, g, b = image.split()

    best = ""
    for ch in (g, gray, r, b):
        result = best_of(ch)
        if len(result) > len(best):
            best = result
        if len(best) >= 40:
            break
    return best

# ========================================
# SHARED PATTERNS
# ========================================

_UDK = re.compile(r"УДК\s*[:.]?\s*([\d.\s:()+=/\-]+)", re.IGNORECASE)
_BBK = re.compile(r"ББК\s*[:.]?\s*(.+)")

# ISBN with common Tesseract misreads in Russian mode:
#   ISBN → 15ВМ  (I→1, S→5, B→В cyrillic, N→М cyrillic)
#   ISBN → 15В№  (N→№ numero sign)
#   ISBN → ISBМ  (N→М)
#   ISBN → Г5ВМ  (I→Г cyrillic; sans-serif uppercase I resembles Г)
#   separator: up to 5 non-digit non-newline chars (handles "$", ":", "№", etc.)
#   digit group: $ included because Russian OCR commonly misreads 5 → $
_ISBN = re.compile(
    r"(?:ISBN|1[35$][ВBвb][МNмн№]|ISB[МNмн]|Г[35][ВBвb][МNмн])[^\d$\n]{0,5}"
    r"([$0-9XxХх\-\–\—\−\.\s]{10,25})",  # Х/х: Cyrillic X misread; $: 5 or S misread; _clean_isbn validates
    re.IGNORECASE,
)

# Авторский знак variants:
#   "С 45"  — 1-2 Cyrillic/Latin (upper or lower) + space + digits
#   "п 61"  — lowercase letter + space + digits
#   "М74"   — letter + digits, no space
#   "Ж44"   — letter + digits, no space
#   "Ч-49"  — letter + hyphen + digits
#   "579"   — digits-only; must be followed by uppercase Cyrillic (a title start),
#             to avoid false matches on page counts like "224 с."
_AUTHOR_SIGN = re.compile(r"^([А-ЯЁа-яёA-Za-z]{1,2}[\-\s]?\d+|\d{2,4}(?=\s+[А-ЯЁ]))\s*(.*)")

# ========================================
# HELPERS
# ========================================

def _empty() -> dict:
    return {
        "udk": "unknown", "bbk": "unknown",
        "author": "unknown", "title": "unknown",
        "publisher": "unknown", "year": 0,
        "isbn": "unknown", "annotation": "unknown",
    }

def _score(data: dict) -> int:
    """Count how many fields were successfully extracted."""
    score = 0
    for k, v in data.items():
        if k == "year":
            score += 1 if v > 0 else 0
        elif v and v != "unknown":
            score += 1
    return score

def _clean_isbn(raw: str) -> str:
    # Normalize common OCR misreads before stripping non-digit chars:
    #   $ → 5  (Russian OCR commonly misreads the digit 5 as dollar sign)
    #   Cyrillic Х → Latin X
    result = re.sub(
        r"[^0-9X]", "",
        raw.replace("$", "5").replace("Х", "X").replace("х", "x").upper()
    )
    # Strip 978 prefix — ISBN-13 and ISBN-10 refer to the same book;
    # store the shorter ISBN-10 body for uniform comparison
    if len(result) == 13 and result.startswith("978"):
        result = result[3:]
    return result if result else "unknown"

def _extract_isbn_from_text(text: str) -> str:
    for m in _ISBN.finditer(text):
        result = _clean_isbn(m.group(1))
        if result != "unknown":
            return result
    return "unknown"

# Public alias used by tests
extract_isbn = _extract_isbn_from_text

def _extract_udk(text: str) -> str:
    # Collect all UDK matches and pick the longest (most specific).
    # The catalog block at the top is often garbled by OCR; the clean value
    # typically appears later in the text (e.g. on a second info page).
    candidates = [re.sub(r'[|\s]+', '', m.group(1).strip()) for m in _UDK.finditer(text) if m.group(1).strip()]
    candidates = [c for c in candidates if re.search(r'\d', c)]
    if candidates:
        return candidates[-1]  # last match is the clean typeset value (bottom of final page)
    # УДК alone on its line — value on next non-empty line
    m2 = re.search(r"^УДК\s*$", text, re.MULTILINE | re.IGNORECASE)
    if m2:
        after = text[m2.end():]
        m3 = re.search(r"^\s*([\d.\s/:()+=-]+)", after, re.MULTILINE)
        if m3 and m3.group(1).strip():
            return m3.group(1).strip()
    return "unknown"

def _extract_bbk(text: str) -> str:
    m = _BBK.search(text)
    if m:
        val = m.group(1).strip().split("\n")[0].strip()
        if val:
            return val
    # ББК alone on its line — value on next non-empty line
    m2 = re.search(r"^ББК\s*$", text, re.MULTILINE)
    if m2:
        after = text[m2.end():]
        m3 = re.search(r"^\s*([А-ЯЁа-яёA-Za-z\d][\dА-ЯЁа-яёA-Za-z().=:,\-–\s]+)", after, re.MULTILINE)
        if m3 and m3.group(1).strip():
            return m3.group(1).strip().split("\n")[0]
    return "unknown"

def _normalize_author(author: str) -> str:
    author = author.strip()
    if "," in author:
        parts = [p.strip() for p in author.split(",", 1)]
        return f"{parts[0]} {parts[1]}"
    return author

def _trim_author_noise(name: str) -> str:
    """
    Remove trailing tokens that don't look like part of a name.
    Two-column OCR often appends right-column words at the end of a name line.
    Keep only leading tokens that start with an uppercase letter.
    """
    parts = name.split()
    while parts and not parts[-1][0].isupper():
        parts.pop()
    return " ".join(parts).rstrip(",").strip() if parts else name

def _strip_sign_prefix(title: str) -> str:
    """Remove leading авторский знак from title, e.g. 'Ч-49 Звезды' → 'Звезды'."""
    return re.sub(r"^[А-ЯЁа-яёA-Za-z]{0,2}[\-\s]?\d+\s+", "", title).strip()

def _clean_title(raw: str) -> str:
    """Strip авторский знак prefix and subtitle after colon; remove trailing period."""
    title = _strip_sign_prefix(raw)
    title = re.split(r"\s*:\s*", title, maxsplit=1)[0].strip()
    return title.rstrip(".").strip()

def _lines(text: str) -> list:
    return [l.strip() for l in text.splitlines() if l.strip()]


def _annotation_after_isbn(text: str) -> str:
    """
    Find the first ISBN line in *text* and return the annotation paragraph
    that follows it (lines after ISBN until first blank line or _ANN_STOP).

    Returns "unknown" when no ISBN line is found or no annotation text follows.
    """
    raw = [l.strip() for l in text.splitlines()]
    isbn_i = None
    for i, line in enumerate(raw):
        if _ISBN.search(line):
            isbn_i = i
            break
    if isbn_i is None:
        return "unknown"
    # Skip blank lines between ISBN and annotation block
    k = isbn_i + 1
    while k < len(raw) and not raw[k]:
        k += 1
    ann = []
    for j in range(k, len(raw)):
        if not raw[j] or _ANN_STOP.search(raw[j]):
            break
        ann.append(raw[j])
    return " ".join(ann) if ann else "unknown"

# How many lines ahead to join when trying to parse a multi-line citation.
# Keep small (4) so the catalog-block авторский знак (near УДК/ББК) doesn't
# accidentally reach the real citation lines further down the page.
_CITATION_WINDOW = 4

# Pattern to find city:publisher,year on a SINGLE line (two-step fallback).
# Handles compound cities joined by ";" (e.g. "М.; Соловецкие острова: Publisher, Year").
# Publisher may contain commas (e.g. "Аксиома, Мифрил") so use non-greedy .+? up to year.
_CITY_PUB_YEAR = re.compile(
    r"(?P<place>[А-ЯЁ][а-яёА-ЯЁ.]{0,20}(?:;\s*[А-ЯЁ][а-яёА-ЯЁ\s.]{0,25})?)"
    r"\s*:\s*(?P<publisher>.+?),\s*(?P<year>(?:19|20)\d{2})"
)

# Авторский знак pattern for catalog-block detection (user heuristic):
# "Letter + space or dash + two digits" — e.g. "С 45", "Ч-49", "М 74"
# Uppercase-only: prevents false matches against keyword suffixes like "К 85" in "ББК 85".
_SIGN_CATALOG = re.compile(r'([А-ЯЁA-Z][ \-]\d{2,3})', re.UNICODE)

# Lines that mark the END of an annotation block: copyright notice, colophon data.
# Used to stop collecting annotation lines at the first such line.
_ANN_STOP = re.compile(
    r'^(?:©|Подписано|Тираж|Формат|Бумага|Гарнитура|Печ\.|Усл\.|Зак\.|Отпечатано|Заказ)',
    re.IGNORECASE,
)

# Price / catalog reference lines: start with a digit, contain only digits, spaces,
# dashes, dots, commas, and $.  These appear on Soviet-era info pages as cost
# references (e.g. "4705040000—159" or "4705040000 —159 -. 527,") and must not
# be picked up as author names.
_PRICE_CATALOG = re.compile(r'^\d[\d\s\-\—\.\$,]+$')

# Author line pattern: Lastname A. B.  (initials may use "," instead of "." from OCR)
# Examples: "Иванов И. И.", "Бобров Ю. Г.", "Бобров Ю, Г,"
_AUTHOR_INITIALS = re.compile(
    r"^([А-ЯЁ][а-яё]{1,20})\s+([А-ЯЁ][,.]\s*[А-ЯЁ][,.]?\s*)(?:[—\-].{0,20})?$"
)

# Physical description end-of-block marker: "— NNN с." or "NNN с.: ил."
_PAGES_MARKER = re.compile(r"\d+\s*с[.:]")


def _find_biblio_block(lines: list, isbn_idx: Optional[int] = None) -> tuple:
    """
    Locate the bibliographic description block and return
    (author, block_lines, start_idx).

    Strategy:
      1. Look for авторский знак  → block starts there, author on preceding line.
      2. Look for Lastname A. B.  → author line, block starts on the next line.

    block_lines: joined text of the block (up to _PAGES_MARKER or isbn_idx).
    Returns (None, None, None) when no block is found.
    """
    limit = isbn_idx if isbn_idx is not None else len(lines)

    # Path 1: авторский знак anchor (existing logic)
    for i, line in enumerate(lines):
        if i >= limit:
            break
        if not _AUTHOR_SIGN.match(line):
            continue
        author, citation_text, c_idx = _parse_author_sign_block(lines, i)
        if citation_text:
            return author, citation_text, c_idx

    # Path 2: Lastname A. B. anchor (no авторский знак)
    for i, line in enumerate(lines):
        if i >= limit:
            break
        m = _AUTHOR_INITIALS.match(line)
        if not m:
            continue
        # Confirm this looks like an author, not a stray fragment:
        # the block must contain a dash separator or page marker within 6 lines
        window_end = min(i + 7, limit)
        window_lines = lines[i + 1 : window_end]
        window_text = " ".join(window_lines)
        if not re.search(r"[—\-]|" + _PAGES_MARKER.pattern, window_text):
            continue
        # Normalise initials: replace commas with dots
        initials = re.sub(r",", ".", m.group(2)).strip()
        author = f"{m.group(1)} {initials}"
        return author, window_text, i + 1

    return None, None, None


def _parse_author_sign_block(lines: list, i: int) -> tuple:
    """
    Given авторский знак at lines[i], return (author, citation_text, citation_line_idx).

    Author: the closest preceding line that looks like a name.
            Scans backwards up to 8 lines to skip copyright noise.
    Citation: lines starting from rest-of-sign-line (or next line),
              joined into a single string for regex matching.
    """
    m = _AUTHOR_SIGN.match(lines[i])
    if not m:
        return None, None, None

    # Find author: scan at most 2 lines back for a name-like line.
    # A tight window prevents picking up translator/editor credits that appear
    # several lines above the авторский знак.
    author = "unknown"
    for back in range(1, min(i + 1, 3)):
        candidate = lines[i - back]
        # Name: starts with uppercase Cyrillic word, followed by another uppercase
        # word or a comma (e.g. "Чернин А. Д." or "Жебрак, Михаил")
        if re.match(r"^[А-ЯЁ][а-яё]+(?:\s+[А-ЯЁ]|,)", candidate):
            author = _trim_author_noise(_normalize_author(candidate))
            break

    rest = m.group(2).strip()
    end = min(i + _CITATION_WINDOW, len(lines))

    if rest:
        citation_text = rest + " " + " ".join(lines[i + 1 : end])
        return author, citation_text, i
    elif i + 1 < len(lines):
        citation_text = " ".join(lines[i + 1 : end])
        return author, citation_text, i + 1

    return author, None, None

# ========================================
# ГОСТ 7.1-2003  (books 2003–2017)
# ========================================
# Copyright page layout:
#   УДК XXX
#   ББК XX.XX
#
#   Фамилия И.О.
#   С 45  [Title] / Фамилия И.О. — М. : Publisher, Year. — NNN с.
#       or
#   С 45  [Title] — М. : Publisher, Year. — NNN с.  (no / author)
#
# Author:  Фамилия И.О.  (no comma, initials without spaces)
# City:    abbreviated  М., СПб., Л.
# Sep:     — (em-dash, mandatory)

_CITATION_2003 = re.compile(
    r"(?P<title>[^/—\n]{3,80}?)"
    r"(?:\s*/\s*[^—\n]+?)?"            # optional: / author (allow hyphens — translator credits)
    r"\s*[—\-]\s*"
    r"(?P<place>[А-ЯЁ][а-яёА-ЯЁ.]{0,15}(?:;\s*[А-ЯЁ][а-яёА-ЯЁ\s.]{0,25})?)"
    r"\s*:\s*(?P<publisher>.+?),\s*(?P<year>\d{4})"
)

def _parse_gost_7_1_2003(text: str) -> dict:
    lines = _lines(text)
    data = _empty()
    data["udk"] = _extract_udk(text)
    data["bbk"] = _extract_bbk(text)
    data["isbn"] = _extract_isbn_from_text(text)

    citation_idx = None
    isbn_idx     = None

    for i, line in enumerate(lines):
        if _ISBN.search(line):
            isbn_idx = i
            break

    for i, line in enumerate(lines):
        if isbn_idx and i >= isbn_idx:
            break
        if not _AUTHOR_SIGN.match(line):
            continue

        author, citation_text, c_idx = _parse_author_sign_block(lines, i)
        if not citation_text:
            continue

        cm = _CITATION_2003.search(citation_text)
        if not cm:
            continue

        # Reject if first city part looks like a full word (≥4 chars) — that's ГОСТ 7.0.100-2018
        # Split on ";" to handle compound cities like "М.; Соловецкие острова"
        first_city = cm.group("place").split(";")[0].strip().rstrip(".")
        if len(first_city) > 3:
            continue

        data["author"]    = author
        data["title"]     = _clean_title(cm.group("title"))
        data["publisher"] = cm.group("publisher").strip()
        data["year"]      = int(cm.group("year"))
        citation_idx      = c_idx
        break

    # Two-step fallback: when single-regex fails (e.g. two-column OCR noise in the
    # citation body), extract title from the авторский знак line and search for
    # city:publisher,year on individual subsequent lines.
    if data["title"] == "unknown":
        for i, line in enumerate(lines):
            if isbn_idx and i >= isbn_idx:
                break
            m = _AUTHOR_SIGN.match(line)
            if not m:
                continue
            rest = m.group(2).strip()
            if not rest:
                continue  # need title to be on the sign line
            raw_title = _clean_title(rest)
            if not raw_title or raw_title == "unknown":
                continue
            # Strip trailing 1-2 char fragments (right-column OCR bleedover)
            raw_title = re.sub(r"(\s+\S{1,2})+$", "", raw_title).strip()
            if not raw_title:
                continue

            # Scan subsequent lines for city:publisher,year
            author, _, _ = _parse_author_sign_block(lines, i)
            limit = min(isbn_idx if isbn_idx else len(lines), i + 8)
            for j in range(i + 1, limit):
                pm = _CITY_PUB_YEAR.search(lines[j])
                if not pm:
                    continue
                if len(pm.group("place").rstrip(".")) > 3:
                    continue  # full city → let 7.0.100-2018 handle
                data["author"]    = author
                data["title"]     = raw_title
                data["publisher"] = pm.group("publisher").strip()
                data["year"]      = int(pm.group("year"))
                break
            if data["title"] != "unknown":
                break

    # Fallback: Lastname A. B. anchor (no авторский знак)
    if data["title"] == "unknown":
        author, block_text, start_idx = _find_biblio_block(lines, isbn_idx)
        if block_text:
            cm = _CITATION_2003.search(block_text)
            if cm:
                first_city = cm.group("place").split(";")[0].strip().rstrip(".")
                if len(first_city) <= 3:
                    data["author"]    = author
                    data["title"]     = _clean_title(cm.group("title"))
                    data["publisher"] = cm.group("publisher").strip()
                    data["year"]      = int(cm.group("year"))
                    citation_idx      = start_idx

    if data["annotation"] == "unknown":
        data["annotation"] = _annotation_after_isbn(text)

    return data

# ========================================
# ГОСТ Р 7.0.100-2018  (books 2018+)
# ========================================
# Structurally similar to ГОСТ 7.1-2003 but:
#   Author:  Фамилия, И. О.  (comma after surname, spaces between initials)
#            OR full name: Фамилия, Имя Отчество
#   City:    full name  Москва, Санкт-Петербург  (≥4 chars)
#   Sep:     .— or —
#
# Examples:
#   Иванов, И. О.
#   И 23  Название / И. О. Иванов. — Москва : Издательство, 2020. — 350 с.
#
#   Жебрак, Михаил Юрьевич
#   Подмосковье. Прогулки по городам / М.Ю. Жебрак — Москва : АСТ, 2025.

_CITATION_2018 = re.compile(
    r"(?P<title>[^/—\n]{3,80}?)"
    r"(?:\s*/\s*[^—\n]+?)?"            # optional: / author (allow hyphens)
    r"\s*[.—\-]+\s*"
    r"(?P<place>[А-ЯЁ][а-яё]{3,20})\s*:\s*(?P<publisher>[^,]+?),\s*(?P<year>\d{4})"
)
# 2018 comma-format author: Фамилия, И. О.  or  Фамилия, Имя Отчество
_AUTHOR_2018 = re.compile(r"^[А-ЯЁ][а-яё]+,\s+[А-ЯЁ]")

def _parse_gost_7_0_100_2018(text: str) -> dict:
    lines = _lines(text)
    data = _empty()
    data["udk"] = _extract_udk(text)
    data["bbk"] = _extract_bbk(text)
    data["isbn"] = _extract_isbn_from_text(text)

    citation_idx = None
    isbn_idx     = None

    for i, line in enumerate(lines):
        if _ISBN.search(line):
            isbn_idx = i
            break

    # Path 1: авторский знак anchor
    for i, line in enumerate(lines):
        if isbn_idx and i >= isbn_idx:
            break
        if not _AUTHOR_SIGN.match(line):
            continue

        author, citation_text, c_idx = _parse_author_sign_block(lines, i)
        if not citation_text:
            continue

        cm = _CITATION_2018.search(citation_text)
        if not cm:
            continue

        # Must have full city name (≥4 real chars) OR comma-format author
        city = cm.group("place")
        raw_author = author
        has_full_city    = len(city.rstrip(".")) >= 4
        has_comma_author = bool(_AUTHOR_2018.match(raw_author))

        if not has_full_city and not has_comma_author:
            continue

        data["author"]    = raw_author
        data["title"]     = _clean_title(cm.group("title"))
        data["publisher"] = cm.group("publisher").strip()
        data["year"]      = int(cm.group("year"))
        citation_idx      = c_idx
        break

    # Path 2: no авторский знак — find comma-format author line, then citation on next line
    if data["title"] == "unknown":
        for i, line in enumerate(lines):
            if isbn_idx and i >= isbn_idx:
                break
            if not _AUTHOR_2018.match(line):
                continue
            # Scan forward up to 10 lines for a citation with full city
            window = " ".join(lines[i + 1 : i + 10])
            cm = _CITATION_2018.search(window)
            if not cm:
                continue
            if len(cm.group("place").rstrip(".")) < 4:
                continue
            data["author"]    = _trim_author_noise(_normalize_author(line))
            data["title"]     = _clean_title(cm.group("title"))
            data["publisher"] = cm.group("publisher").strip()
            data["year"]      = int(cm.group("year"))
            citation_idx      = i + 1
            break

    if data["annotation"] == "unknown":
        data["annotation"] = _annotation_after_isbn(text)

    return data

# ========================================
# ГОСТ 7.1-84  (books pre-2003)
# ========================================
# Авторский знак is optional.
# Author can appear in multiple positions:
#   И.О. Фамилия. Заглавие / И.О. Фамилия. — Город : Изд-во, Год. — NNN с.
#   Фамилия И.О. Заглавие. — Город : Изд-во, Год.
# City: abbreviated, old forms (Л. for Ленинград, etc.)
# Sep: `. —` or just `—`

_CITATION_84 = re.compile(
    r"(?:(?P<author_pre>[А-ЯЁ][а-яё]+\s+[А-ЯЁA-Z]\.\s?(?:[А-ЯЁA-Z]\.)?)\s+)?"
    r"(?P<title>[А-ЯЁ][а-яё][^/—\-\n]{4,79}?)"
    r"(?:\s*/\s*[^—\n]+?)?"            # optional: / author (allow hyphens)
    r"[,]?\s*[.—\-]+\s*"              # sep: allow OCR artefact "," before em-dash
    r"(?P<place>[А-ЯЁ][а-яёА-ЯЁ.]{0,15}(?:;\s*[А-ЯЁ][а-яёА-ЯЁ\s.]{0,25})?)"
    r"\s*:\s*(?P<publisher>.+?),\s*(?P<year>(?:19|20)\d{2})"
)
_AUTHOR_DIRECT = re.compile(r"^([А-ЯЁA-Z]\.\s?[А-ЯЁA-Z]\.\s+[А-ЯЁ][а-яё]+)")

def _parse_gost_7_1_84(text: str) -> dict:
    lines = _lines(text)
    data = _empty()
    data["udk"] = _extract_udk(text)
    data["bbk"] = _extract_bbk(text)
    data["isbn"] = _extract_isbn_from_text(text)

    isbn_idx     = None
    citation_idx = None

    for i, line in enumerate(lines):
        if _ISBN.search(line):
            isbn_idx = i
            break

    # Path 1: авторский знак anchor
    for i, line in enumerate(lines):
        if isbn_idx and i >= isbn_idx:
            break
        if not _AUTHOR_SIGN.match(line):
            continue
        author, citation_text, c_idx = _parse_author_sign_block(lines, i)
        if not citation_text:
            continue
        cm = _CITATION_84.search(citation_text)
        if not cm:
            continue
        data["author"]    = author
        data["title"]     = _clean_title(cm.group("title"))
        data["publisher"] = cm.group("publisher").strip()
        data["year"]      = int(cm.group("year"))
        citation_idx      = c_idx
        break

    # Path 2: scan every line for citation pattern (no авторский знак needed)
    if data["title"] == "unknown":
        for i, line in enumerate(lines):
            if isbn_idx and i >= isbn_idx:
                break
            cm = _CITATION_84.search(line)
            if not cm:
                continue
            if cm.group("author_pre"):
                data["author"] = _normalize_author(cm.group("author_pre"))
            elif i > 0:
                am = _AUTHOR_DIRECT.match(lines[i - 1])
                if am:
                    data["author"] = am.group(1).strip()
            data["title"]     = _clean_title(cm.group("title"))
            data["publisher"] = cm.group("publisher").strip()
            data["year"]      = int(cm.group("year"))
            citation_idx      = i
            break

    # Path 3: Lastname A. B. anchor (no авторский знак)
    if data["title"] == "unknown":
        author, block_text, start_idx = _find_biblio_block(lines, isbn_idx)
        if block_text:
            cm = _CITATION_84.search(block_text)
            if cm:
                data["author"]    = author
                data["title"]     = _clean_title(cm.group("title"))
                data["publisher"] = cm.group("publisher").strip()
                data["year"]      = int(cm.group("year"))
                citation_idx      = start_idx

    if data["annotation"] == "unknown":
        data["annotation"] = _annotation_after_isbn(text)

    return data

# ========================================
# ГОСТ Р 7.0.5-2008  (reference-list style, fallback)
# ========================================
# Not a copyright-page standard, but its format appears on some
# academic book info pages.
#
# Format A (author first):  Фамилия И.О. Заглавие. Город: Изд-во, Год. NNN с.
# Format B (title first):   Заглавие / И.О. Фамилия. — Город: Изд-во, Год.
# No авторский знак. Period separators.

_CITATION_REF_AUTHOR_FIRST = re.compile(
    r"(?P<author>[А-ЯЁ][а-яё]+\s+[А-ЯЁA-Z]\.\s?(?:[А-ЯЁA-Z]\.)?)\s+"
    r"(?P<title>[А-ЯЁ][^\n.]{5,80}?)\."
    r"\s*[-—]?\s*"
    r"(?P<place>[А-ЯЁ][а-яёА-ЯЁ.]{0,20})\s*:\s*(?P<publisher>[^,]+?),\s*(?P<year>\d{4})"
)
_CITATION_REF_TITLE_FIRST = re.compile(
    r"(?P<title>[А-ЯЁ][^\n/]{5,80}?)\s*/\s*(?P<author>[^—\-\n]{3,50}?)"
    r"[.\s]*[—\-][.\s—\-]*"              # separator: requires at least one dash; handles ". — ", "—"
    r"(?P<place>[А-ЯЁ][а-яёА-ЯЁ.]{0,20})\s*:\s*(?P<publisher>.+?),\s*(?P<year>\d{4})"
)

def _parse_gost_r_7_0_5_2008(text: str) -> dict:
    data = _empty()
    data["udk"]  = _extract_udk(text)
    data["bbk"]  = _extract_bbk(text)
    data["isbn"] = _extract_isbn_from_text(text)

    # Search on original text first (single-line citations)
    for pattern, author_key, title_key in [
        (_CITATION_REF_AUTHOR_FIRST, "author", "title"),
        (_CITATION_REF_TITLE_FIRST,  "author", "title"),
    ]:
        m = pattern.search(text)
        if not m:
            continue
        data["title"]     = _clean_title(m.group(title_key))
        data["author"]    = _normalize_author(m.group(author_key))
        data["publisher"] = m.group("publisher").strip()
        data["year"]      = int(m.group("year"))
        break

    # Fallback: join adjacent line pairs to handle multi-line citations.
    # Title-first format often wraps across lines, e.g.:
    #   "Территория : роман / Олег Куваев. — СПб. : Аз
    #    бука, Азбука-Аттикус, 2021. — 352 с."
    if data["title"] == "unknown":
        lines = _lines(text)
        for i in range(len(lines) - 1):
            # Strip leading open-quote characters that precede the title
            line_a = re.sub(r'^["""«„]+', '', lines[i])
            line_b = lines[i + 1]
            # Detect word split at line boundary: if line_a ends with a Cyrillic
            # letter and line_b starts with a lowercase Cyrillic letter, the word
            # was split without a hyphen (OCR dropped the soft-hyphen).
            # Join without space to restore: "Аз" + "бука" → "Азбука".
            if line_a and re.search(r'[а-яёА-ЯЁ]$', line_a) and re.match(r'^[а-яё]', line_b):
                joined = line_a + line_b
            else:
                joined = line_a + " " + line_b
            m = _CITATION_REF_TITLE_FIRST.search(joined)
            if not m:
                continue
            title = _clean_title(m.group("title"))
            if not title or title == "unknown":
                continue
            data["title"]     = title
            data["author"]    = _normalize_author(m.group("author"))
            data["publisher"] = m.group("publisher").strip()
            data["year"]      = int(m.group("year"))
            break

    return data

# ========================================
# STRUCTURED PARSER  (primary strategy)
# ========================================
#
# Algorithm anchored on the fixed block layout of Russian info pages:
#
#   УДК <value>          ← find this line
#   ББК <value>          ← next non-empty line
#   <авторский знак>     ← next non-empty line; memorise the sign token
#   …
#   <Author name>        ← line immediately before the citation line
#   <sign>  <citation>   ← line starting with the same sign token
#   ISBN <value>         ← first ISBN line after citation
#   <annotation text>    ← lines after ISBN until first blank line
#

def _parse_structured(text: str) -> dict:
    """
    Structure-aware info-page parser.

    Steps:
    1. Scan full text for УДК → extract value
    2. Scan full text for ББК → extract value
    3. Try to read авторский знак from the catalog block (line after ББК)
    4. Search full text for a line that starts with the sign token AND has
       citation content on it.  Fallback: find any авторский знак line with
       content (using a loose OCR-tolerant pattern).
    5. Author = previous non-empty line before the citation line
    6. Parse citation text (title, publisher, year)
    7. ISBN = first ISBN line at or after the citation line
    8. Annotation = lines after ISBN until first blank line
    """
    raw_lines = text.splitlines()
    stripped  = [l.strip() for l in raw_lines]
    data = _empty()

    # Loose авторский знак: optional leading № (OCR artifact), then 1-2 letters +
    # optional noise char + 1-3 digits, then whitespace + at least 5 chars of content.
    # Handles OCR substitutions like "М!7" (space→!) or "Ч-49" (hyphen), and
    # the common "№М17 Заглавие..." variant where № is prepended by the OCR engine.
    _SIGN_LOOSE = re.compile(
        r'^[№]?([А-ЯЁа-яёA-Za-z]{1,2}[\-\s!.,]*\d{1,3})\s+(.{5,})',
        re.UNICODE
    )

    # ── 1. УДК — scan full text ─────────────────────────────────────────────
    udk_idx = None
    for i, line in enumerate(stripped):
        if re.match(r'^(?:УДК|UDK)\b', line, re.IGNORECASE):
            udk_idx = i
            val = re.sub(r'^(?:УДК|UDK)\s*', '', line, flags=re.IGNORECASE).strip()
            data['udk'] = val or 'unknown'
            break

    # ── 2. ББК — scan full text ─────────────────────────────────────────────
    bbk_idx = None
    for i, line in enumerate(stripped):
        if re.match(r'^ББК\b', line, re.IGNORECASE):
            bbk_idx = i
            val = re.sub(r'^ББК\s*', '', line, flags=re.IGNORECASE).strip()
            data['bbk'] = val or 'unknown'
            break

    # ── 3. Авторский знак from catalog block ────────────────────────────────
    # User heuristic: УДК / ББК / авторский знак lines can appear in mixed order.
    # Search all 3 non-empty lines around the first catalog keyword for a sign
    # matching "Letter + space-or-dash + two digits"  (e.g. "С 45", "Ч-49").
    sign = None
    catalog_anchor = min(x for x in [udk_idx, bbk_idx] if x is not None) \
                     if (udk_idx is not None or bbk_idx is not None) else 0
    # Scan a window of ±2 lines around the anchor, collecting up to 3 non-empty lines
    scan_start = max(0, catalog_anchor - 2)
    scan_end   = min(len(stripped), catalog_anchor + 6)
    non_empty  = 0
    for i in range(scan_start, scan_end):
        if not stripped[i]:
            continue
        non_empty += 1
        m = _SIGN_CATALOG.search(stripped[i])
        if m:
            sign = m.group(1)
            break
        if non_empty >= 3:
            break

    # ── 4. Find citation line starting with the sign (or any sign with content) ──
    record_idx = None

    if sign is not None:
        # Case-insensitive search: OCR may uppercase a lowercase sign (or vice versa)
        sign_lower = sign.lower()
        for i, line in enumerate(stripped):
            if line.lower().startswith(sign_lower) and len(line) > len(sign) + 3:
                if len(line[len(sign):].strip()) >= 5:
                    record_idx = i
                    break

    loose_citation_text = None  # content captured from _SIGN_LOOSE group 2
    if record_idx is None:
        # Fallback: find the first авторский знак line that has citation content.
        # Require the content (after the sign) to lead with an uppercase Cyrillic
        # letter, optionally preceded by a dash separator ("— Заглавие").
        # This filters out catalog/barcode lines like "Ч 053 02)-84"
        # whose content ("02)-84") doesn't look like a title.
        for i, line in enumerate(stripped):
            m = _SIGN_LOOSE.match(line)
            if m and re.match(r'^[—\-]?\s*[А-ЯЁ]', m.group(2)):
                sign = m.group(1)
                record_idx = i
                loose_citation_text = m.group(2)  # already stripped of sign prefix
                break

    if record_idx is None:
        return data

    # ── 5. Author = nearest preceding line that looks like an author name ────
    # Prefer lines matching "Фамилия И. О." (AUTHOR_INITIALS) so that multi-line
    # editorial boards or catalog numbers between the author and the citation are
    # correctly skipped.  Fall back to the first non-catalog non-empty line.
    # Scan up to 60 lines back; stop at УДК / ББК block boundaries.
    first_candidate = None
    for i in range(record_idx - 1, max(-1, record_idx - 60), -1):
        s = stripped[i]
        if not s:
            continue
        if re.match(r'^(?:УДК|ББК|ISBN|©|\d+\s*к\.)', s, re.IGNORECASE):
            continue
        if _PRICE_CATALOG.match(s):
            continue
        # Strip leading single-char OCR noise: "ПШпаликов Г. Ф." → "Шпаликов Г. Ф."
        # When one uppercase letter is immediately followed by another uppercase + lowercase,
        # the first letter is an OCR artifact glued onto the actual name.
        s_clean = re.sub(r'^([А-ЯЁ])([А-ЯЁ][а-яё])', r'\2', s)
        if _AUTHOR_INITIALS.match(s_clean):
            # Clear name-with-initials match — use it immediately
            data['author'] = _trim_author_noise(_normalize_author(s_clean))
            break
        if first_candidate is None:
            first_candidate = s_clean  # remember first non-catalog line for fallback
    # Fallback if no initials-format match found
    if data['author'] == 'unknown' and first_candidate is not None:
        data['author'] = _trim_author_noise(_normalize_author(first_candidate))

    # ── 6. Parse citation text ───────────────────────────────────────────────
    # Collect up to 10 subsequent lines, skipping blank lines (OCR sometimes
    # inserts blank lines mid-citation).  Stop only at an ISBN line.
    # When the record line was found via _SIGN_LOOSE, use the pre-captured content
    # directly (it already excludes any leading № or sign prefix).
    if loose_citation_text is not None:
        first_citation = loose_citation_text
    else:
        first_citation = re.sub(r'^' + re.escape(sign) + r'\s*', '', stripped[record_idx]).strip()
    citation_parts = [first_citation]
    for i in range(record_idx + 1, min(record_idx + 10, len(stripped))):
        if stripped[i] and _ISBN.search(stripped[i]):
            break
        if stripped[i]:
            citation_parts.append(stripped[i])
    citation_text = ' '.join(citation_parts)

    for pattern in (_CITATION_2018, _CITATION_2003, _CITATION_84):
        cm = pattern.search(citation_text)
        if cm:
            data['title']     = _clean_title(cm.group('title'))
            data['publisher'] = cm.group('publisher').strip()
            data['year']      = int(cm.group('year'))
            break

    # ── 7. ISBN ──────────────────────────────────────────────────────────────
    isbn_idx = None
    for i in range(record_idx, min(record_idx + 10, len(stripped))):
        if _ISBN.search(stripped[i]):
            isbn_idx = i
            m = _ISBN.search(stripped[i])
            data['isbn'] = _clean_isbn(m.group(1))
            break

    # ── 8. Annotation: lines after ISBN (or after pages marker) ─────────────
    # Skip leading blank lines between anchor and annotation.
    # Stop at copyright/colophon lines (©, Подписано, Тираж, etc.).
    ann_anchor = isbn_idx  # primary anchor: ISBN line
    if ann_anchor is None:
        # Fallback for pre-ISBN books (no ISBN number): find the pages marker
        # ("NNN с." or "NNN с.:") in the citation block and anchor from there.
        for i in range(record_idx, min(record_idx + 15, len(stripped))):
            if _PAGES_MARKER.search(stripped[i]):
                ann_anchor = i  # keep scanning — last match is end of citation

    if ann_anchor is not None:
        ann_start = ann_anchor + 1
        while ann_start < len(stripped) and not stripped[ann_start]:
            ann_start += 1
        ann_lines = []
        for i in range(ann_start, len(stripped)):
            if not stripped[i] or _ANN_STOP.search(stripped[i]):
                break
            ann_lines.append(stripped[i])
        if ann_lines:
            data['annotation'] = ' '.join(ann_lines)

    # ── 9. Embedded ББК / УДК fallback — scan full text ────────────────────
    # ББК and УДК (Cyrillic abbreviations) may appear buried mid-line anywhere
    # on the page: top-left catalog block, bottom catalog block, or mixed with
    # 2-column TOC noise.  Scan the entire document when step 1/2 found nothing.
    # Both abbreviations use Cyrillic letters: ББК, УДК.

    if data['bbk'] == 'unknown':
        for line in stripped:
            m = re.search(
                r'ББК\s*[:.]?\s*([0-9А-ЯЁ][0-9А-ЯЁа-яёA-Za-z().=:\-]{1,30})',
                line
            )
            if m:
                val = m.group(1).strip().rstrip(',')
                # Strip trailing TOC noise: stop at first standalone lowercase word ≥4 chars
                val = re.split(r'\s+[а-яё]{4,}', val)[0].strip()
                if val:
                    data['bbk'] = val
                    break

    if data['udk'] == 'unknown':
        for line in stripped:
            m = re.search(
                r'УДК\s*[:.]?\s*([\d.\s:()+=/\-]{3,30})',
                line
            )
            if m:
                val = m.group(1).strip()
                if val:
                    data['udk'] = val
                    break

    return data


# ========================================
# MAIN PIPELINE
# ========================================

_GOST_PARSERS = {
    "2018": _parse_gost_7_0_100_2018,
    "2003": _parse_gost_7_1_2003,
    "84":   _parse_gost_7_1_84,
    "2008": _parse_gost_r_7_0_5_2008,
}

def extract_metadata_from_info_page(ocr_text: str, ocr_eng: str = "",
                                     gost_parser: Optional[str] = None) -> dict:
    """
    Run ГОСТ parser(s) and return the best result.

    gost_parser — if given, run only that specific parser:
                  "2018" → ГОСТ Р 7.0.100-2018
                  "2003" → ГОСТ 7.1-2003
                  "84"   → ГОСТ 7.1-84
                  "2008" → ГОСТ Р 7.0.5-2008
                  None / unknown → auto: run all, pick highest score
    """
    if gost_parser and gost_parser in _GOST_PARSERS:
        data = _GOST_PARSERS[gost_parser](ocr_text)
    else:
        # Primary: structure-aware parser anchored on УДК/ББК/sign block
        data = _parse_structured(ocr_text)
        if _score(data) < 3:
            # Fallback: try all GOST pattern parsers and pick the best
            candidates = [
                _parse_gost_7_0_100_2018(ocr_text),
                _parse_gost_7_1_2003(ocr_text),
                _parse_gost_7_1_84(ocr_text),
                _parse_gost_r_7_0_5_2008(ocr_text),
            ]
            fallback = max(candidates, key=_score)
            if _score(fallback) > _score(data):
                data = fallback

    combined = ocr_text + "\n" + ocr_eng
    if data["isbn"] == "unknown":
        data["isbn"] = _extract_isbn_from_text(combined)

    return data


def extract_metadata_from_title_page(image: Image.Image) -> dict:
    """
    Extract metadata from an old-book title page (no GOST info page).

    Two complementary passes are merged:

    Pass 1 — HOCR (bounding-box heights):
      Best at detecting font size → identifies title as largest text, and
      reliably captures the footer (publisher + year) because footer text
      is usually black on white.  May miss coloured author/title text.

    Pass 2 — colour-contrast OCR + blank-line-group heuristic:
      Recovers coloured text (blue, red) that HOCR misses.  Splits the
      page into groups by blank lines: top = author, middle = title,
      bottom = publisher + year.

    The two results are merged: HOCR data takes priority (it has better
    structural analysis), gaps are filled from the colour-contrast pass.

    Landscape images (width > height × 1.2) are auto-rotated 90° CCW to
    portrait before parsing — this handles pages scanned sideways.
    """
    # Auto-correct landscape orientation (scanned portrait pages photographed sideways)
    w, h = image.size
    if w > h * 1.2:
        image = image.rotate(90, expand=True)

    hocr_data = _empty()
    try:
        hocr_data = _parse_title_page_hocr(image)
    except Exception:
        pass

    text_data = _empty()
    try:
        text = ocr_colored_text_page(image)
        text_data = _parse_title_page(text)
    except Exception:
        pass

    # Merge strategy:
    #  • year / publisher — HOCR is most reliable (footer text is usually black)
    #  • author / title   — HOCR wins when it identified a clear author line
    #                       (small font before large font).  If HOCR found no
    #                       author (all body lines were large-font, so author/title
    #                       boundary was ambiguous), coloured-OCR title takes
    #                       priority to avoid mixing an author word into the title.
    data = hocr_data.copy()

    hocr_has_author = hocr_data['author'] not in ('unknown', '')

    for field in ('author', 'title', 'publisher', 'year'):
        hocr_val  = hocr_data[field]
        text_val  = text_data[field]
        hocr_miss = hocr_val in ('unknown', 0)
        text_hit  = text_val not in ('unknown', 0)

        if hocr_miss and text_hit:
            data[field] = text_val          # HOCR missed it → use coloured OCR
        elif field == 'title' and not hocr_has_author and text_hit:
            data[field] = text_val          # HOCR title untrustworthy → use coloured OCR

    return data



def extract_title_author_from_cover(ocr_text: str) -> dict:
    """
    Best-effort title/author from cover text.
    Without font-size info only basic heuristics are possible.
    """
    lines = _lines(ocr_text)
    if not lines:
        return {"title": "unknown", "author": "unknown"}

    _COVER_AUTHOR = [
        re.compile(r"^[А-ЯЁ][а-яё]+,?\s+[А-ЯЁA-Z]\.\s?(?:[А-ЯЁA-Z]\.)?$"),
        re.compile(r"^[А-ЯЁ][а-яё]+\s+[А-ЯЁ][а-яё]+(?:\s+[А-ЯЁ][а-яё]+)?$"),
    ]

    author = "unknown"
    author_idx = None
    for i, line in enumerate(lines):
        for p in _COVER_AUTHOR:
            if p.match(line):
                author = _normalize_author(line)
                author_idx = i
                break
        if author != "unknown":
            break

    title_lines = [l for i, l in enumerate(lines) if i != author_idx and len(l) > 2]
    title = title_lines[0] if title_lines else "unknown"

    return {"title": title, "author": author}


# ========================================
# BARCODE DETECTION
# ========================================

def detect_barcode_isbn(image: Image.Image) -> Optional[str]:
    """
    Detect a barcode (EAN-13 / ISBN) in the image using pyzbar.
    Returns the ISBN string if found, or None.
    """
    try:
        from pyzbar.pyzbar import decode as pyzbar_decode
        import numpy as np

        img_array = np.array(image.convert("RGB"))
        barcodes = pyzbar_decode(img_array)

        for barcode in barcodes:
            raw = barcode.data.decode("utf-8", errors="ignore").strip()
            # Accept EAN-13 (ISBN-13) and EAN-8 / Code128 that look like ISBNs
            digits = re.sub(r"[^0-9X]", "", raw.upper())
            if len(digits) in (10, 13):
                logger.info("Barcode detected: type=%s data=%s", barcode.type, raw)
                return digits
            # Some scanners return ISBN: prefix
            if raw.upper().startswith("ISBN"):
                clean = re.sub(r"[^0-9X]", "", raw.upper())
                if len(clean) in (10, 13):
                    logger.info("Barcode detected (ISBN prefix): %s", clean)
                    return clean

        return None

    except ImportError:
        logger.warning("pyzbar not installed — barcode detection skipped")
        return None
    except Exception as e:
        logger.warning("Barcode detection failed: %s", e)
        return None
