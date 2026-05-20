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

import cv2
import numpy as np
import pytesseract
from PIL import Image

logger = logging.getLogger(__name__)

TESSERACT_CMD = os.getenv("TESSERACT_CMD")
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD

# ========================================
# IMAGE PREPROCESSING — DEWARP + ILLUMINATE
# ========================================
#
# Pipeline
# --------
# 1. _perspective_correct  — optional planar homography (removes camera tilt)
# 2. _detect_text_baselines — binarise → dilate → CC → poly-fit each text line
# 3. _build_dewarp_map      — per-column interpolation → (map_x, map_y) for remap
# 4. cv2.remap              — non-linear correction; straightens curved baselines
# 5. _correct_illumination  — divide by blurred bg to kill gutter shadow
#
# All stages degrade gracefully: if evidence is insufficient the image passes
# through unchanged.


# ── 0. Grid-calibration dewarp (loaded once from dewarp_calibration.npz) ───

_GRID_CAL: Optional[dict] = None
_GRID_CAL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                               "dewarp_calibration.npz")


def _load_grid_cal() -> Optional[dict]:
    """Load (and cache) the grid dewarp calibration if the npz exists."""
    global _GRID_CAL
    if _GRID_CAL is not None:
        return _GRID_CAL
    if not os.path.exists(_GRID_CAL_PATH):
        return None
    try:
        d = np.load(_GRID_CAL_PATH)
        _GRID_CAL = {"map_x_rel": d["map_x_rel"], "map_y_rel": d["map_y_rel"]}
        logger.info("Grid dewarp calibration loaded (%d H × %d V lines)",
                    int(d.get("n_h_lines", 0)), int(d.get("n_v_lines", 0)))
        return _GRID_CAL
    except Exception as exc:
        logger.warning("Failed to load grid calibration: %s", exc)
        return None


def _apply_grid_dewarp(img_rgb: np.ndarray) -> np.ndarray:
    """
    Apply the grid-calibrated dewarp map.
    If no calibration file exists the image is returned unchanged.
    The stored map is in relative (0–1) coordinates and is bilinearly
    rescaled to match any input resolution.
    """
    cal = _load_grid_cal()
    if cal is None:
        return img_rgb
    h, w = img_rgb.shape[:2]
    mx = cal["map_x_rel"]
    my = cal["map_y_rel"]
    if mx.shape != (h, w):
        mx = cv2.resize(mx, (w, h), interpolation=cv2.INTER_LINEAR)
        my = cv2.resize(my, (w, h), interpolation=cv2.INTER_LINEAR)
    map_x = (mx * (w - 1)).astype(np.float32)
    map_y = (my * (h - 1)).astype(np.float32)
    return cv2.remap(img_rgb, map_x, map_y,
                     interpolation=cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_REPLICATE)


# ── 1. Perspective pre-correction ──────────────────────────────────────────

def _order_corners(pts: np.ndarray) -> np.ndarray:
    """Return (4,2) corners in [TL, TR, BR, BL] order."""
    rect = np.zeros((4, 2), dtype=np.float32)
    s       = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]        # TL: smallest x+y
    rect[2] = pts[np.argmax(s)]        # BR: largest  x+y
    diff    = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]     # TR: smallest y-x
    rect[3] = pts[np.argmax(diff)]     # BL: largest  y-x
    return rect


def _find_page_quad(gray: np.ndarray) -> Optional[np.ndarray]:
    """Return the largest 4-corner polygon covering ≥15 % of the image, or None."""
    h, w = gray.shape

    def _search(binary: np.ndarray) -> Optional[np.ndarray]:
        cnts, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for cnt in sorted(cnts, key=cv2.contourArea, reverse=True)[:10]:
            peri   = cv2.arcLength(cnt, True)
            approx = cv2.approxPolyDP(cnt, 0.02 * peri, True)
            if len(approx) == 4 and cv2.contourArea(approx) > 0.15 * h * w:
                return approx.reshape(4, 2).astype(np.float32)
        return None

    blur   = cv2.GaussianBlur(gray, (5, 5), 0)
    thresh = cv2.adaptiveThreshold(blur, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
                                   cv2.THRESH_BINARY, 11, 2)
    result = _search(cv2.bitwise_not(thresh))
    if result is not None:
        return result

    edges = cv2.Canny(blur, 50, 150)
    edges = cv2.dilate(edges, np.ones((3, 3), np.uint8), iterations=1)
    return _search(edges)


def _perspective_correct(img_rgb: np.ndarray) -> np.ndarray:
    """
    Remove camera-tilt distortion via a planar homography when a clear page
    boundary quad is visible.  Passes through unchanged otherwise.
    Note: this corrects only the viewing angle, not page-surface curvature.
    """
    gray = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2GRAY)
    h, w = gray.shape
    quad  = _find_page_quad(gray)
    if quad is None:
        return img_rgb
    rect = _order_corners(quad)
    # Skip if quad already matches image boundaries
    if np.max(np.abs(rect - np.array([[0,0],[w,0],[w,h],[0,h]], np.float32))) < 5:
        return img_rgb
    tl, tr, br, bl = rect
    out_w = int(max(np.linalg.norm(br - bl), np.linalg.norm(tr - tl)))
    out_h = int(max(np.linalg.norm(tr - br), np.linalg.norm(tl - bl)))
    dst   = np.array([[0,0],[out_w-1,0],[out_w-1,out_h-1],[0,out_h-1]], np.float32)
    M     = cv2.getPerspectiveTransform(rect, dst)
    out   = cv2.warpPerspective(img_rgb, M, (out_w, out_h),
                                flags=cv2.INTER_LINEAR,
                                borderMode=cv2.BORDER_REPLICATE)
    logger.debug("perspective_correct: %dx%d → %dx%d", w, h, out_w, out_h)
    return out


# ── 2. Text-baseline detection ─────────────────────────────────────────────

def _sample_strip_centerline(label_mask: np.ndarray,
                              x0: int, x1: int,
                              n_samples: int = 40) -> tuple:
    """
    Sample the per-column y-centroid of a connected-component strip.
    Returns (xs, ys) 1-D float arrays suitable for np.polyfit.
    """
    xs, ys = [], []
    step = max(1, (x1 - x0) // n_samples)
    for x in range(x0, x1, step):
        rows = np.where(label_mask[:, x])[0]
        if rows.size == 0:
            continue
        xs.append(float(x))
        ys.append(float(rows.mean()))
    return np.asarray(xs), np.asarray(ys)


def _detect_text_baselines(gray: np.ndarray,
                            debug_img: Optional[np.ndarray] = None) -> list:
    """
    Detect text lines and return a list of degree-2 polynomial coefficient arrays
    (np.polyfit output), sorted top-to-bottom.

    Algorithm
    ---------
    1. Locally-normalised Otsu binarisation  →  removes illumination gradient.
    2. Horizontal morphological dilation     →  merges characters within a line.
    3. Vertical erosion                      →  separates vertically adjacent lines.
    4. Connected-component analysis          →  one component ≈ one text-line strip.
    5. Width / height filter                 →  keeps only plausible text lines.
    6. Centerline sampling + poly-fit        →  one curve per line.
    """
    h, w = gray.shape

    # Step 1 — normalise illumination, then binarise
    kw_bg = max(3, (w // 20) | 1)
    kh_bg = max(3, (h // 20) | 1)
    bg    = cv2.GaussianBlur(gray, (kw_bg, kh_bg), 0)
    norm  = cv2.divide(gray.astype(np.float32), bg.astype(np.float32) + 1e-3,
                       scale=255).clip(0, 255).astype(np.uint8)
    _, binary = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)

    # Step 2 — horizontal dilation: bridge word gaps, keep lines separate
    kw_dil = max(5, w // 25)
    kernel_h = cv2.getStructuringElement(cv2.MORPH_RECT, (kw_dil, 1))
    dilated  = cv2.dilate(binary, kernel_h, iterations=3)

    # Step 3 — light vertical erosion to split accidentally merged line pairs
    kernel_v = cv2.getStructuringElement(cv2.MORPH_RECT, (1, 3))
    dilated  = cv2.erode(dilated, kernel_v, iterations=1)

    # Step 4 — connected components
    num_labels, labels, stats, _ = cv2.connectedComponentsWithStats(dilated)

    polys = []
    for i in range(1, num_labels):
        x0_s, y0_s, bw_s, bh_s, _ = stats[i]

        # Step 5 — filter: wide enough to be a text line; not so tall it's a block
        if bw_s < w * 0.25:    # too short — not a reliable line
            continue
        if bh_s > h * 0.12:   # too tall  — image block or merged paragraph
            continue
        if bh_s < 3:
            continue

        # Step 6 — sample centerline + fit degree-2 polynomial
        mask_strip = labels == i
        xs, ys = _sample_strip_centerline(mask_strip, x0_s, x0_s + bw_s)
        if len(xs) < 8:
            continue

        coeffs = np.polyfit(xs, ys, 2)
        polys.append(coeffs)

        if debug_img is not None:
            pts = np.array([[int(x), int(np.polyval(coeffs, x))]
                            for x in range(x0_s, x0_s + bw_s, 4)], dtype=np.int32)
            pts = pts[(pts[:, 1] >= 0) & (pts[:, 1] < h)]
            cv2.polylines(debug_img, [pts.reshape(-1, 1, 2)], False, (0, 220, 0), 2)

    if not polys:
        return polys

    # Sort top-to-bottom by the curve's value at the image centre
    polys.sort(key=lambda c: np.polyval(c, w / 2))

    # Sanity check: large median linear slope (b coefficient) means the page is
    # viewed at a steep angle — perspective distortion, not page curl.
    # Text-line dewarping cannot fix that and will make things worse.
    # Threshold: if b*w > 15 % of image height the slope is too extreme (~8°+).
    median_b = float(np.median([p[1] for p in polys]))
    if abs(median_b) * w > 0.10 * h:
        logger.debug(
            "_detect_text_baselines: median slope b*w=%.1f > 0.10*h=%.1f "
            "— likely perspective distortion, skipping dewarp",
            abs(median_b) * w, 0.10 * h,
        )
        return []

    logger.debug("_detect_text_baselines: %d lines found", len(polys))
    return polys


# ── 3. Displacement map ────────────────────────────────────────────────────

def _build_dewarp_map(polys: list, h: int, w: int,
                      debug_img: Optional[np.ndarray] = None) -> tuple:
    """
    Build (map_x, map_y) for cv2.remap() from detected polynomial baselines.

    Geometry
    --------
    Each polynomial p_i(x) gives the y-position of text line i in the *source*
    image.  We want line i to appear at a constant target y_i = median_x p_i(x)
    in the output (perfectly horizontal).

    For each output column x we have a set of (target_y, source_y) control-point
    pairs.  We add hard boundary pins at y=0 and y=h-1 so the warp doesn't drift
    at the image edges.  Between control points we interpolate linearly; outside
    the outermost baselines the boundary pins provide stable extrapolation.

    The resulting map_y is lightly Gaussian-smoothed to suppress column-by-column
    jitter and avoid visible tearing artifacts.
    """
    x_coords = np.arange(w, dtype=np.float64)

    # Source curve values at every x   (L, w)
    src_curves = np.array([np.polyval(p, x_coords) for p in polys])
    # Target: median y of each curve → straight horizontal lines   (L, w)
    tgt_curves = np.median(src_curves, axis=1)[:, None] * np.ones((1, w))

    # Boundary control points: pin top and bottom edges
    top    = np.zeros((1, w))
    bottom = np.full((1, w), float(h - 1))
    src_ctrl = np.vstack([top, src_curves, bottom])   # (L+2, w)
    tgt_ctrl = np.vstack([top, tgt_curves, bottom])

    out_ys = np.arange(h, dtype=np.float64)
    map_y  = np.empty((h, w), dtype=np.float32)

    for x in range(w):
        tgt_col = tgt_ctrl[:, x]
        src_col = src_ctrl[:, x]
        # Ensure strictly increasing x for np.interp
        order = np.argsort(tgt_col, kind="stable")
        tgt_s, src_s = tgt_col[order], src_col[order]
        _, keep = np.unique(tgt_s, return_index=True)
        map_y[:, x] = np.interp(out_ys, tgt_s[keep], src_s[keep])

    # Smooth to kill column-by-column jitter → no vertical tearing
    map_y = cv2.GaussianBlur(map_y, (5, 5), 0)

    # Clamp displacement: no pixel should move more than 15 % of image height.
    # Prevents catastrophic warping in regions with sparse control points
    # (e.g. a large gap between text blocks, or a spine fold with no text).
    identity_y = np.arange(h, dtype=np.float32)[:, np.newaxis] * np.ones((1, w), dtype=np.float32)
    max_disp   = h * 0.15
    map_y      = np.clip(map_y, identity_y - max_disp, identity_y + max_disp)

    map_x = np.tile(x_coords.astype(np.float32), (h, 1))

    if debug_img is not None:
        # Visualise the warp mesh (source sampling positions)
        for x in range(0, w, 40):
            for y_out in range(0, h, 20):
                y_src = int(np.clip(map_y[y_out, x], 0, h - 1))
                cv2.circle(debug_img, (x, y_src), 1, (255, 128, 0), -1)

    return map_x, map_y


# ── 4. Illumination correction ─────────────────────────────────────────────

def _correct_illumination(img_rgb: np.ndarray) -> np.ndarray:
    """
    Normalise uneven illumination (gutter shadow, brightness gradient) by
    dividing each channel by a heavily blurred version of itself.

    The blur kernel is ~20 % of the smaller image dimension, large enough to
    capture page-scale gradients without touching character-level contrast.
    """
    h, w  = img_rgb.shape[:2]
    ksize = max(51, (min(h, w) // 5) | 1)   # must be odd

    out = np.empty_like(img_rgb, dtype=np.float32)
    for c in range(3):
        ch  = img_rgb[:, :, c].astype(np.float32)
        bg  = cv2.GaussianBlur(ch, (ksize, ksize), 0)
        out[:, :, c] = cv2.divide(ch, bg + 1e-3, scale=255.0)

    return out.clip(0, 255).astype(np.uint8)


# ── 5. Debug output ────────────────────────────────────────────────────────

def _save_debug(debug_dir: str, original: np.ndarray, corrected: np.ndarray,
                baselines_vis: Optional[np.ndarray],
                mesh_vis: Optional[np.ndarray]) -> None:
    import os as _os
    _os.makedirs(debug_dir, exist_ok=True)
    p = _os.path.join(debug_dir, "dewarp_")
    cv2.imwrite(p + "0_original.jpg",  cv2.cvtColor(original,  cv2.COLOR_RGB2BGR))
    cv2.imwrite(p + "3_corrected.jpg", cv2.cvtColor(corrected, cv2.COLOR_RGB2BGR))
    if baselines_vis is not None:
        cv2.imwrite(p + "1_baselines.jpg", cv2.cvtColor(baselines_vis, cv2.COLOR_RGB2BGR))
    if mesh_vis is not None:
        cv2.imwrite(p + "2_mesh.jpg",      cv2.cvtColor(mesh_vis,      cv2.COLOR_RGB2BGR))
    logger.debug("dewarp debug images written to %s", debug_dir)


# ── Public entry point ─────────────────────────────────────────────────────

def preprocess_for_ocr(image: Image.Image,
                       debug_dir: Optional[str] = None) -> Image.Image:
    """
    Full preprocessing pipeline for a photographed book page.

    Stages
    ------
    1. Perspective correction  — planar homography removes camera-tilt
    2. Baseline detection      — text lines fitted with degree-2 polynomials
    3. Non-linear dewarp       — per-column interpolation + cv2.remap()
    4. Illumination correction — divide-by-blur kills gutter shadow

    Each stage falls back gracefully when evidence is insufficient.

    Parameters
    ----------
    image     : PIL Image in any mode
    debug_dir : optional path; if given, saves visualisations:
                  dewarp_0_original.jpg
                  dewarp_1_baselines.jpg  (detected curves in green)
                  dewarp_2_mesh.jpg       (warp-field sampling dots)
                  dewarp_3_corrected.jpg
    """
    img_rgb = np.array(image.convert("RGB"))

    # Stage 1 — perspective
    img_rgb = _perspective_correct(img_rgb)

    # Stage 2 — grid-calibration dewarp (book-curl correction)
    img_rgb = _apply_grid_dewarp(img_rgb)

    h, w    = img_rgb.shape[:2]
    gray    = cv2.cvtColor(img_rgb, cv2.COLOR_RGB2GRAY)

    # Stage 3 — detect baselines
    debug_bl = img_rgb.copy() if debug_dir else None
    polys    = _detect_text_baselines(gray, debug_img=debug_bl)

    if len(polys) < 3:
        logger.debug("preprocess_for_ocr: %d baselines — skipping dewarp", len(polys))
        corrected = _correct_illumination(img_rgb)
        if debug_dir:
            _save_debug(debug_dir, img_rgb, corrected, debug_bl, None)
        return Image.fromarray(corrected)

    # Stage 4 — build map and remap
    debug_mesh     = img_rgb.copy() if debug_dir else None
    map_x, map_y   = _build_dewarp_map(polys, h, w, debug_img=debug_mesh)
    dewarped       = cv2.remap(img_rgb, map_x, map_y,
                               interpolation=cv2.INTER_LINEAR,
                               borderMode=cv2.BORDER_REPLICATE)

    # Stage 4 — illumination
    corrected = _correct_illumination(dewarped)

    if debug_dir:
        _save_debug(debug_dir, img_rgb, corrected, debug_bl, debug_mesh)

    return Image.fromarray(corrected)


# ========================================
# OCR
# ========================================

def image_from_base64(b64: str) -> Image.Image:
    return Image.open(io.BytesIO(base64.b64decode(b64)))

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
_ISBN = re.compile(
    r"(?:ISBN|1[35][ВBвb][МNмн№]|ISB[МNмн]|Г[35][ВBвb][МNмн])\s*[№:\-]?\s*"
    r"([0-9XxХх\-\–\—\−\.\s]{10,25})",  # Х/х: Cyrillic X misread; dot: OCR separator; _clean_isbn validates
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
    # Normalize Cyrillic Х → Latin X, then keep only digits and X
    result = re.sub(r"[^0-9X]", "", raw.replace("Х", "X").replace("х", "x").upper())
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
    m = _UDK.search(text)
    if m and m.group(1).strip():
        return m.group(1).strip()
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
_SIGN_CATALOG = re.compile(r'([А-ЯЁA-Z][ \-]\d{2,3})', re.UNICODE)

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

    if citation_idx is not None and isbn_idx is not None and isbn_idx > citation_idx + 1:
        data["annotation"] = " ".join(lines[citation_idx + 1 : isbn_idx])

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

    if citation_idx is not None and isbn_idx is not None and isbn_idx > citation_idx + 1:
        data["annotation"] = " ".join(lines[citation_idx + 1 : isbn_idx])

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

    if citation_idx is not None and isbn_idx is not None and isbn_idx > citation_idx + 1:
        data["annotation"] = " ".join(lines[citation_idx + 1 : isbn_idx])

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
            joined = re.sub(r'^["""«„]+', '', lines[i]) + " " + lines[i + 1]
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

    # Loose авторский знак: 1-2 letters + optional noise char + 1-3 digits,
    # then whitespace + at least 5 chars of content.
    # Handles OCR substitutions like "М!7" (space→!) or "Ч-49" (hyphen).
    _SIGN_LOOSE = re.compile(
        r'^([А-ЯЁа-яёA-Za-z]{1,2}[\-\s!.,]*\d{1,3})\s+(.{5,})',
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
        # Prefer exact match from catalog-block sign
        for i, line in enumerate(stripped):
            if line.startswith(sign) and len(line) > len(sign) + 3:
                if len(line[len(sign):].strip()) >= 5:
                    record_idx = i
                    break

    if record_idx is None:
        # Fallback: find the first авторский знак line that has citation content
        # using the OCR-tolerant loose pattern
        for i, line in enumerate(stripped):
            m = _SIGN_LOOSE.match(line)
            if m:
                sign = m.group(1)
                record_idx = i
                break

    if record_idx is None:
        return data

    # ── 5. Author = previous non-empty line ─────────────────────────────────
    for i in range(record_idx - 1, -1, -1):
        s = stripped[i]
        if s and not re.match(r'^(?:УДК|ББК|ISBN|©|\d+\s*к\.)', s, re.IGNORECASE):
            data['author'] = _trim_author_noise(_normalize_author(s))
            break

    # ── 6. Parse citation text ───────────────────────────────────────────────
    citation_parts = [re.sub(r'^' + re.escape(sign) + r'\s*', '', stripped[record_idx]).strip()]
    for i in range(record_idx + 1, min(record_idx + 6, len(stripped))):
        if not stripped[i] or _ISBN.search(stripped[i]):
            break
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

    # ── 8. Annotation: lines after ISBN until first blank line ───────────────
    # Skip leading blank lines between ISBN and annotation (OCR sometimes
    # inserts an empty line between the ISBN line and the annotation block).
    if isbn_idx is not None:
        ann_start = isbn_idx + 1
        while ann_start < len(stripped) and not stripped[ann_start]:
            ann_start += 1
        ann_lines = []
        for i in range(ann_start, len(stripped)):
            if not stripped[i]:
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


def preprocess_stages(image: Image.Image) -> dict:
    """
    Run the preprocessing pipeline and return each intermediate stage.

    Returns a dict:
        "perspective" → PIL Image after planar homography correction
        "dewarped"    → PIL Image after full pipeline (perspective + dewarp + illumination)

    Both values may equal the original if that stage produced no change.
    """
    img_rgb = np.array(image.convert("RGB"))

    # Stage 1: perspective correction
    after_persp = _perspective_correct(img_rgb)
    stages = {"perspective": Image.fromarray(after_persp)}

    # Stage 2: grid-calibration dewarp
    after_grid = _apply_grid_dewarp(after_persp)
    if _load_grid_cal() is not None:
        stages["grid_dewarp"] = Image.fromarray(after_grid)

    # Stage 3: text-line dewarp + illumination
    h, w  = after_grid.shape[:2]
    gray  = cv2.cvtColor(after_grid, cv2.COLOR_RGB2GRAY)
    polys = _detect_text_baselines(gray)
    if len(polys) >= 3:
        map_x, map_y = _build_dewarp_map(polys, h, w)
        dewarped = cv2.remap(after_grid, map_x, map_y,
                             interpolation=cv2.INTER_LINEAR,
                             borderMode=cv2.BORDER_REPLICATE)
    else:
        dewarped = after_grid

    stages["dewarped"] = Image.fromarray(_correct_illumination(dewarped))
    return stages


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
