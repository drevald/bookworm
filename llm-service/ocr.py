"""
OCR and Metadata Extraction Functions
Based on llava/ocr.py - Deterministic regex + LLM extraction
"""

import pytesseract
from PIL import Image
import requests
import json
import re
import io
import base64
import os

# ========================================
# CONFIGURATION
# ========================================

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://192.168.0.134:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:7b")
OLLAMA_COMPLETIONS_URL = f"{OLLAMA_URL}/v1/completions"

TESSERACT_CMD = os.getenv("TESSERACT_CMD")
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD

# ========================================
# OCR FUNCTIONS
# ========================================

def image_from_base64(b64: str) -> Image.Image:
    return Image.open(io.BytesIO(base64.b64decode(b64)))

def ocr_image(image: Image.Image, lang: str) -> str:
    return pytesseract.image_to_string(image, lang=lang)

# ========================================
# NEW: OCR BLOCK EXTRACTION (layout-aware)
# ========================================

def ocr_image_blocks(image: Image.Image, lang: str) -> list[str]:
    """
    Extract OCR text grouped by Tesseract block_num.
    Each block is returned as a single joined string.
    """
    data = pytesseract.image_to_data(
        image, lang=lang, output_type=pytesseract.Output.DICT
    )

    blocks = {}
    for i in range(len(data["text"])):
        txt = data["text"][i].strip()
        if not txt:
            continue
        blk = data["block_num"][i]
        blocks.setdefault(blk, []).append(txt)

    return [" ".join(words) for words in blocks.values()]

# ========================================
# REGEX EXTRACTION (DETERMINISTIC HINTS)
# ========================================

def extract_author(ocr):
    patterns = [
        r'^[А-ЯЁ][а-яё]+ [А-ЯЁ]\. ?[А-ЯЁ]\.$',
        r'^[А-ЯЁ][а-яё]+, [А-ЯЁ][а-яё]+(?: [А-ЯЁ][а-яё]+)?$'
    ]
    for p in patterns:
        m = re.search(p, ocr, re.MULTILINE)
        if m:
            return normalize_author(m.group(0))
    return "unknown"

def normalize_author(author):
    if "," in author:
        parts = [p.strip() for p in author.split(",")]
        return f"{parts[0]} {parts[1]}"
    return author

def extract_isbn(ocr):
    m = re.search(r'ISBN(?:-1[03])?\s*[:]?\s*([0-9Xx\-\–\—\−\s]+)', ocr, re.IGNORECASE)
    if not m:
        return "unknown"

    raw = re.sub(r'[^0-9Xx]', '', m.group(1)).upper()

    if len(raw) == 10 and (raw[:-1].isdigit() or raw.isdigit()):
        return raw
    if len(raw) == 13 and raw.isdigit():
        return raw

    return "unknown"

def extract_udk(ocr):
    m = re.search(r'УДК\s*[:]? ?([\d.:()+=-]+)', ocr)
    return m.group(1) if m else "unknown"

def extract_bbk(ocr):
    m = re.search(r'ББК\s*[:.]?\s*([А-ЯЁ\d][\d\(\)=:А-ЯЁ.\-–]+)', ocr)
    return m.group(1) if m else "unknown"

# ========================================
# NEW: BIBLIOGRAPHIC BLOCK HEURISTICS
# ========================================

def filter_bibliographic_blocks(blocks: list[str]) -> list[str]:
    """
    Select blocks that look like bibliographic catalog entries.
    """
    result = []

    for text in blocks:
        score = 0

        if re.search(r'[А-ЯЁ][а-яё]+,\s*[А-ЯЁ]', text):
            score += 2
        if re.search(r'[—\-:]', text):
            score += 1
        if re.search(r'\b(19|20)\d{2}\b', text):
            score += 1
        if "/" in text:
            score += 1
        if len(text) > 80:
            score += 1

        if score >= 3:
            result.append(text)

    return result

def select_primary_ocr_text(biblio_blocks: list[str], full_ocr: str) -> str:
    if not biblio_blocks:
        return full_ocr

    best = max(biblio_blocks, key=len)
    return best if len(best) >= 60 else full_ocr

# ========================================
# LLM EXTRACTION
# ========================================

def build_extraction_prompt(ocr_text, author_hint, isbn_hint, udk_hint, bbk_hint):
    return f"""Extract bibliographic metadata from Russian book OCR text.
The OCR TEXT is a bibliographic catalog entry (library card, GOST-style).
Return ONLY valid JSON.

CRITICAL RULES:
1. Title: Remove subtitle after colon.
2. Author normalization required.
3. Publisher: Extract full publisher name.
4. Preserve Cyrillic letters in BBK/UDK.
5. Return ONLY JSON.

JSON SCHEMA:
{{
  "title": "",
  "author": "",
  "publisher": "",
  "year": 0,
  "isbn": "",
  "udk": "",
  "bbk": "",
  "annotation": ""
}}

HINTS:
author = "{author_hint}"
isbn = "{isbn_hint}"
udk = "{udk_hint}"
bbk = "{bbk_hint}"

OCR TEXT:
{ocr_text}

Return ONLY the JSON object:"""

def extract_json(text):
    text = re.sub(r"^```json\s*|\s*```$", "", text.strip())
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("No JSON found")
    return m.group(0)

def normalize_author_title(data):
    m = re.match(r'^([А-ЯЁ][а-яё]+),\s*([А-ЯЁ][а-яё]+)\.\s*[—-]\s*(.+)', data.get("title", ""))
    if m:
        surname, name, clean_title = m.groups()
        data["author"] = f"{surname} {name}"
        data["title"] = clean_title.strip()

def normalize_strings(data):
    for k, v in data.items():
        if isinstance(v, str):
            data[k] = " ".join(v.split())

def clean_annotation(text):
    if not text:
        return "unknown"
    text = " ".join(text.split())
    fragments = re.split(r'([.!?])', text)
    out, seen = [], set()
    for i in range(0, len(fragments)-1, 2):
        s = fragments[i].strip() + fragments[i+1]
        if s not in seen:
            seen.add(s)
            out.append(s)
    return " ".join(out)

def normalize_classification(code):
    if not code or code == "unknown":
        return "unknown"
    return "".join(code.split())

def finalize(data, isbn_hint, udk_hint, bbk_hint):
    for k in ["title", "author", "publisher", "annotation"]:
        data[k] = data.get(k) or "unknown"
    if not isinstance(data.get("year"), int):
        data["year"] = 0

    data["isbn"] = data.get("isbn") or isbn_hint
    data["udk"] = normalize_classification(data.get("udk") or udk_hint)
    data["bbk"] = normalize_classification(data.get("bbk") or bbk_hint)
    data["annotation"] = clean_annotation(data.get("annotation"))

def extract_metadata_with_llm(ocr_text, ocr_text_eng=None):
    author_hint = extract_author(ocr_text)
    isbn_hint = extract_isbn(ocr_text_eng or ocr_text)
    udk_hint = extract_udk(ocr_text)
    bbk_hint = extract_bbk(ocr_text)

    prompt = build_extraction_prompt(
        ocr_text, author_hint, isbn_hint, udk_hint, bbk_hint
    )

    response = requests.post(
        OLLAMA_COMPLETIONS_URL,
        json={
            "model": OLLAMA_MODEL,
            "prompt": prompt,
            "max_tokens": 800,
            "temperature": 0
        },
        timeout=60
    )
    response.raise_for_status()

    text = response.json()["choices"][0]["text"]
    data = json.loads(extract_json(text))

    normalize_author_title(data)
    normalize_strings(data)
    finalize(data, isbn_hint, udk_hint, bbk_hint)

    return data

# ========================================
# NEW: HIGH-LEVEL PIPELINE (USE THIS)
# ========================================

def extract_metadata_from_image(image: Image.Image) -> dict:
    """
    Full pipeline:
    - Full-page OCR
    - Block OCR
    - Bibliographic block selection
    - Regex + LLM extraction
    """

    ocr_ru_full = ocr_image(image, "rus")
    ocr_en = ocr_image(image, "eng")

    blocks = ocr_image_blocks(image, "rus")
    biblio_blocks = filter_bibliographic_blocks(blocks)

    primary_ocr = select_primary_ocr_text(biblio_blocks, ocr_ru_full)

    return extract_metadata_with_llm(primary_ocr, ocr_en)
