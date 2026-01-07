"""
OCR and Metadata Extraction Service - Python version
Using regex hints + LLM extraction
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List
import logging
import json
import os
import pytesseract
from PIL import Image
import requests
import re
import io
import base64
import pathlib

# ========================================
# ENV + LOGGING
# ========================================

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S'
)
logger = logging.getLogger(__name__)

# ========================================
# OLLAMA CONFIGURATION
# ========================================

OLLAMA_URL = os.getenv("OLLAMA_URL", "http://192.168.0.189:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:7b")
OLLAMA_COMPLETIONS_URL = f"{OLLAMA_URL}/v1/completions"

# Tesseract configuration
# In Docker, tesseract is installed via apt-get and available in PATH
# No need to set tesseract_cmd - pytesseract will find it automatically
# If you need to override, set TESSERACT_CMD environment variable
TESSERACT_CMD = os.getenv("TESSERACT_CMD")
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD
# else: don't set anything, pytesseract will use 'tesseract' from PATH

# ========================================
# OCR FUNCTIONS
# ========================================

def image_from_base64(b64: str) -> Image.Image:
    """Convert base64 string to PIL Image"""
    return Image.open(io.BytesIO(base64.b64decode(b64)))

def ocr_image(image: Image.Image, lang: str, preprocess: bool = False) -> str:
    """Extract text from image using Tesseract OCR"""
    if preprocess:
        # Enhance image for better OCR on decorative covers
        from PIL import ImageEnhance, ImageOps
        # Convert to grayscale
        image = ImageOps.grayscale(image)
        # Increase contrast
        enhancer = ImageEnhance.Contrast(image)
        image = enhancer.enhance(2.0)
        # Increase sharpness
        enhancer = ImageEnhance.Sharpness(image)
        image = enhancer.enhance(2.0)

    return pytesseract.image_to_string(image, lang=lang)


def ocr_image_rgb_channels(image: Image.Image, lang: str) -> str:
    """
    Try OCR on RGB and CMYK channels (both normal and inverted) for decorative covers.
    Uses progressive strategy: try most promising channels first, stop early if good result found.
    """
    from PIL import ImageOps

    # Convert to RGB if needed
    if image.mode != 'RGB':
        image = image.convert('RGB')

    def try_ocr(channel, name):
        """Try OCR on channel (normal and inverted), return best"""
        ocr_norm = pytesseract.image_to_string(channel, lang=lang).strip()
        ocr_inv = pytesseract.image_to_string(ImageOps.invert(channel), lang=lang).strip()
        if len(ocr_norm) >= len(ocr_inv):
            return (ocr_norm, name, len(ocr_norm))
        else:
            return (ocr_inv, f"{name}-inv", len(ocr_inv))

    best_result = ("", "none", 0)

    # Phase 1: Try most promising channels (G, M, original)
    # These typically work best for decorative covers
    gray = image.convert('L')
    r, g, b = image.split()
    cmyk = image.convert('CMYK')
    c, m, y, k = cmyk.split()

    for channel, name in [(g, "G"), (m, "M"), (gray, "original")]:
        result = try_ocr(channel, name)
        if result[2] > best_result[2]:
            best_result = result
            logger.info(f"OCR {name}: {result[2]} chars")
            # Early termination if we got a good result
            if result[2] >= 40:
                logger.info(f"Early termination: {result[2]} chars from {result[1]} is sufficient")
                return result[0]

    # Phase 2: If still not good, try remaining RGB
    if best_result[2] < 20:
        for channel, name in [(r, "R"), (b, "B")]:
            result = try_ocr(channel, name)
            if result[2] > best_result[2]:
                best_result = result
                logger.info(f"OCR {name}: {result[2]} chars")
                if result[2] >= 40:
                    return result[0]

    # Phase 3: Last resort - try remaining CMYK
    if best_result[2] < 20:
        for channel, name in [(c, "C"), (y, "Y"), (k, "K")]:
            result = try_ocr(channel, name)
            if result[2] > best_result[2]:
                best_result = result
                logger.info(f"OCR {name}: {result[2]} chars")
                if result[2] >= 40:
                    return result[0]

    logger.info(f"Best OCR from {best_result[1]}: {best_result[2]} chars - '{best_result[0][:100]}'")
    return best_result[0]

# ========================================
# REGEX EXTRACTION (DETERMINISTIC HINTS)
# ========================================

def ocr_with_vision_fallback(image: Image.Image, tesseract_result: str) -> str:
    """
    Use Ollama vision model as fallback when Tesseract fails or gets poor results.
    Returns vision model OCR if available, otherwise returns original tesseract_result.
    """
    # If Tesseract got decent results (>20 chars), use them
# Check if OCR result is poor quality (short, or has noise characters)    result_len = len(tesseract_result.strip())    has_noise = any(c in tesseract_result[:20] for c in ["|", ";", "@", "#", "ufffd"])        if result_len > 50 and not has_noise:        return tesseract_result        logger.info(f"Tesseract result poor (len={result_len}, noise={has_noise}), trying vision model...")
    
    try:
        # Convert image to base64
        buffered = io.BytesIO()
        image.save(buffered, format="PNG")
        img_b64 = base64.b64encode(buffered.getvalue()).decode()
        
        # Call Ollama vision API
        vision_url = f"{OLLAMA_URL}/api/generate"
        payload = {
            "model": "qwen2.5vl:7b",
            "prompt": "Extract ALL visible text from this book page/cover image EXACTLY as it appears, preserving the original language (including Cyrillic/Russian characters). Do not translate. Include: book title, author name, publisher, year, ISBN, UDK, BBK, and any other text. Output the text exactly as shown in the image.",
            "images": [img_b64],
            "stream": False
        }
        
        response = requests.post(vision_url, json=payload, timeout=90)
        if response.status_code == 200:
            result = response.json()
            vision_text = result.get("response", "")
            if len(vision_text.strip()) > len(tesseract_result.strip()):
                logger.info(f"Vision model got better result: {len(vision_text.strip())} chars vs {len(tesseract_result.strip())} chars")
                return vision_text
        else:
            logger.warning(f"Vision model failed: {response.status_code}")
    except Exception as e:
        logger.error(f"Vision OCR fallback error: {e}")
    
    # Return original if vision model failed
    return tesseract_result
def extract_author(ocr):
    """Extract author name from OCR text using patterns"""
    # Try English format first
    eng_biblio = extract_english_bibliographic_entry(ocr)
    if eng_biblio and eng_biblio['author']:
        return eng_biblio['author']

    # Try GOST format
    biblio = extract_bibliographic_entry(ocr)
    if biblio and biblio['author']:
        return biblio['author']

    # Fallback patterns (ordered by priority)
    patterns = [
        # HIGHEST PRIORITY: Author directly before catalog code (e.g., "Чернин А. Д.\nА-49 Звезды")
        # This avoids matching reviewers/editors listed elsewhere
        # Allow multiple newlines/whitespace between author and catalog code
        r'([А-ЯЁ][а-яё]+\s+[А-ЯЁA-Z]\.\s?[А-ЯЁA-Z]\.)\s*[\n\s]+[А-ЯЁA-Z][\d-]+\s',
        # Matches: Николаева A.H. or Николаева А.Н. (Cyrillic or Latin initials)
        r'[А-ЯЁ][а-яё]+\s+[А-ЯЁA-Z]\.\s?[А-ЯЁA-Z]\.',
        # Matches: Куваев, Олег or Фамилия, Имя Отчество
        r'[А-ЯЁ][а-яё]+,\s+[А-ЯЁ][а-яё]+(?:\s+[А-ЯЁ][а-яё]+)?',
        # Matches copyright line: © Николаева A.H.
        r'©\s+([А-ЯЁ][а-яё]+\s+[А-ЯЁA-Z]\.\s?[А-ЯЁA-Z]\.)',
    ]
    for p in patterns:
        m = re.search(p, ocr, re.MULTILINE)
        if m:
            author = m.group(1) if m.lastindex else m.group(0)
            return normalize_author(author.replace('©', '').strip())
    return "unknown"

def normalize_author(author):
    """Normalize author name from catalog form"""
    if "," in author:
        parts = [p.strip() for p in author.split(",")]
        return f"{parts[0]} {parts[1]}"
    return author

def extract_isbn(ocr):
    """Extract ISBN from OCR text"""
    # Stop at opening parenthesis or other non-ISBN chars
    m = re.search(r'ISBN\s*[:]? ?([0-9Xx\-\–\—\−\s]+?)(?:\s*[\(;,]|$)', ocr, re.IGNORECASE)
    if not m:
        return "unknown"

    # Keep only digits and X
    raw = re.sub(r'[^0-9Xx]', '', m.group(1)).upper()

    # ISBN-10: 9 digits + digit or X (X only last)
    if len(raw) == 10:
        if raw[-1] == 'X' and raw[:-1].isdigit():
            return raw
        if raw.isdigit():
            return raw
        return "unknown"

    # ISBN-13: 13 digits only
    if len(raw) == 13 and raw.isdigit():
        return raw

    return "unknown"

def extract_udk(ocr):
    """Extract UDK code from OCR text"""
    m = re.search(r'УДК\s*[:.]?\s*([\d.\s:()+=/\-]+)', ocr)
    if m:
        # Clean up spaces but preserve structure
        return re.sub(r'\s+', ' ', m.group(1).strip())
    return "unknown"

def extract_bbk(ocr):
    """Extract BBK code from OCR text"""
    # BBK can start with Cyrillic letter (e.g., Ч84) or digit (e.g., 22.3)
    # Allow Cyrillic letters, digits, and special chars including comma
    m = re.search(r'ББК\s*[:.]?\s*([А-ЯЁа-яёA-Za-z\d][\d\(\)=:,А-ЯЁа-яёA-Za-z.\-–\s]+)', ocr)
    if m:
        # Clean up but preserve Cyrillic
        result = m.group(1).strip()
        # Remove trailing newline/whitespace content
        result = re.split(r'\n|(?:\s{2,})', result)[0]
        return result
    return "unknown"

def extract_english_bibliographic_entry(ocr):
    """
    Extract metadata from English/international bibliographic citation formats:
    - Author. Title. Place: Publisher, Year.
    - Title / Author. - Place : Publisher, Year.

    Examples:
    Rowling, J.K. Harry Potter and the Philosopher's Stone. London: Bloomsbury, 1997.
    The Lord of the Rings / J.R.R. Tolkien. - New York : Houghton Mifflin, 1954.
    """
    patterns = [
        # Author, Title. Place: Publisher, Year.
        r'([A-Z][a-z]+(?:,?\s+[A-Z][a-z.]+)*)[.,]\s+([A-Z][^.:]+?)\.\s*(?:[A-Z][a-z]+)\s*:\s*([A-Z][a-zA-Z\s&]+?),\s*(\d{4})',
        # Title / Author. - Place : Publisher, Year.
        r'([A-Z][^/]+?)\s*/\s*([A-Z][a-z]+(?:,?\s+[A-Z][a-z.]+)*)\.\s*[-—]\s*(?:[A-Z][a-z]+)\s*:\s*([A-Z][a-zA-Z\s&]+?),\s*(\d{4})',
    ]

    for pattern in patterns:
        m = re.search(pattern, ocr)
        if m:
            groups = m.groups()
            # First pattern: author first
            if len(groups) == 4 and not '/' in m.group(0):
                author = groups[0].strip()
                title = groups[1].strip()
                publisher = groups[2].strip()
                year = int(groups[3])
            else:
                # Second pattern: title first
                title = groups[0].strip()
                author = groups[1].strip()
                publisher = groups[2].strip()
                year = int(groups[3])

            return {
                'author': author,
                'title': title,
                'publisher': publisher,
                'year': year
            }

# Try extracting from copyright/trademark line    # e.g., "Harry Potter, names, characters and related indicia are"    copyright_match = re.search(r"([A-Z][A-Za-z]+(?:s+[A-Z][A-Za-z]+)+),s+names?,s+characters?", ocr)    if copyright_match:        return {"title": copyright_match.group(1), "author": "unknown", "publisher": "unknown", "year": 0}
    return None

def extract_bibliographic_entry(ocr):
    """
    Extract metadata from GOST bibliographic citation format:
    [XXX] [Author]. — [Title]. — [Place] : [Publisher], [Year]. — [Pages].

    Examples:
    М68 Мифы Русского Севера, Сибири и Дальнего Востока. — Москва : Эксмо, 2024. — 240 с.
    К89 Куваев, О. М. — Территория : роман. — Москва : Азбука, 2020. — 416 с.

    Handles multiline entries where title and publisher are on different lines.
    """
    # Pattern with optional author, handles newlines
    # Allow both Latin and Cyrillic catalog letters (M68 or М68)
    # Use \s* to handle any whitespace including newlines
    pattern = r'[А-ЯЁA-Z]\d+\s+(?:([А-ЯЁ][а-яё]+(?:,?\s+[А-ЯЁ][а-яё.]+)*)\s*[.—]+\s*)?([А-ЯЁ][^.—]+?)\.\s*—\s*([А-ЯЁ][а-яё]+)\s*:\s*([А-ЯЁ][а-яёА-ЯЁ\s]+?),\s*(\d{4})'

    m = re.search(pattern, ocr, re.DOTALL)
    if m:
        author = m.group(1).strip() if m.group(1) else None
        title = m.group(2).strip()
        place = m.group(3).strip()  # Place name (e.g., Москва)
        publisher = m.group(4).strip()
        year = int(m.group(5))

        # Clean title - remove subtitle after colon or slash
        title = re.split(r'\s*[:/]\s*', title)[0].strip()

        # Normalize author if present
        if author:
            author = normalize_author(author)

        return {
            'author': author,
            'title': title,
            'publisher': publisher,
            'year': year
        }

    return None

def extract_title(ocr):
    """Extract title from OCR text"""
    # Try English format first
    eng_biblio = extract_english_bibliographic_entry(ocr)
    if eng_biblio:
        return eng_biblio['title']

    # Try GOST format
    biblio = extract_bibliographic_entry(ocr)
    if biblio:
        return biblio['title']

    # Fallback: find title in catalog entry format
    m = re.search(r'[А-ЯЁ]\d+\s+([А-ЯЁ][^.—]+?)\.?\s*[—\-]', ocr)
    if m:
        title = m.group(1).strip()
        title = re.split(r'\s*:\s*', title)[0]
        return title
    return "unknown"

def extract_publisher(ocr):
    """Extract publisher from OCR text"""
    # Try English format first
    eng_biblio = extract_english_bibliographic_entry(ocr)
    if eng_biblio:
        return eng_biblio['publisher']

    # Try GOST format
    biblio = extract_bibliographic_entry(ocr)
    if biblio:
        return biblio['publisher']

    # Fallback patterns
    patterns = [
        r'Москва\s*:\s*([А-ЯЁ][а-яёА-ЯЁ\s]+?),\s*\d{4}',
        r'ИЗДАТЕЛЬСТВО\s*\n?\s*([А-ЯЁ][А-ЯЁа-яё\s]+)',
    ]
    for p in patterns:
        m = re.search(p, ocr)
        if m:
            return m.group(1).strip()
    return "unknown"

def extract_year(ocr):
    """Extract publication year from OCR text"""
    # Try English format first
    eng_biblio = extract_english_bibliographic_entry(ocr)
    if eng_biblio:
        return eng_biblio['year']

    # Try GOST format
    biblio = extract_bibliographic_entry(ocr)
    if biblio:
        return biblio['year']

    # Fallback: look for 4-digit year in common contexts
    m = re.search(r'(?:Москва|СПб|издательство)[^,]*,\s*(\d{4})', ocr, re.IGNORECASE)
    if m:
        return int(m.group(1))

    return 0

# ========================================
# LLM EXTRACTION
# ========================================

def build_extraction_prompt(ocr_text: str, author_hint: str, isbn_hint: str, udk_hint: str, bbk_hint: str) -> str:
    """Build prompt for LLM metadata extraction"""
    return f"""Extract bibliographic metadata from Russian book OCR text.

===== ABSOLUTE RULES - FOLLOW EXACTLY OR RESPONSE IS INVALID =====

1. TITLE EXTRACTION (MOST IMPORTANT):
   - Find the bibliographic entry line (format: "Author. Title / Author. — Publisher, Year")
   - Extract the title (between author name and " / " or " — "), STOP at colon (:) - exclude subtitle/translation info
   - If NO bibliographic entry exists (e.g., copyright page, blank page), return "unknown"
   - CRITICAL: If text contains "copyright", "trademark", "reserved", "indicia" → NOT A TITLE, return "unknown"
   - CRITICAL: Bibliographic entries have "—" or "/" separator and publication year, copyright text does NOT
   - DO NOT extract titles from copyright notices, legal text, or random sentences
   - DO NOT invent, translate, or paraphrase - copy character-by-character
   - FORBIDDEN: Writing words that don't appear in OCR text
   - FORBIDDEN: Extracting from copyright or legal text
   - FORBIDDEN: Titles containing "copyright", "trademark", "reserved", "rights", "indicia"

2. AUTHOR EXTRACTION:
   - If format "Фамилия, Имя" → convert to "Фамилия Имя"
   - Use ONLY the author name from bibliographic entry
   - DO NOT mix multiple authors from different parts of text

3. EXTRACT EXACTLY AS WRITTEN - NO INTERPRETATION

===== CORRECT EXAMPLES =====
Input: "Куваев О. М.\nК88 Территория : роман / Олег Куваев. — М., 2021"
Output: {{"title": "Территория : роман", "author": "Куваев Олег", ...}}
REASON: Full title with subtitle extracted from bibliographic entry

Input: "M17 Путём-дорога! : Избранные фрагменты из книги «Год на Севере» / С.В. Максимов. — М., 2022"
Output: {{"title": "Путём-дорога! : Избранные фрагменты из книги «Год на Севере»", "author": "Максимов С.В.", ...}}
REASON: Full title with subtitle extracted from bibliographic entry

Input: "Copyright © J.K.Rowling 1997\nISBN 978-0-7475-3274-3\nAll rights reserved"
Output: {{"title": "unknown", "author": "J.K.Rowling", ...}}
REASON: Copyright page has NO bibliographic entry, so title is unknown

===== WRONG EXAMPLES (NEVER DO THIS) =====
Input: "Harry Potter, names, characters and related indicia are / copyright Warner Bros"
WRONG: {{"title": "Harry Potter, names, characters and related indicia are", ...}}  ❌ EXTRACTED FROM COPYRIGHT TEXT!
CORRECT: {{"title": "unknown", ...}}  ✓ NO BIBLIOGRAPHIC ENTRY = UNKNOWN

JSON SCHEMA:
{{
  "title": "full book title with subtitle if present, or unknown",
  "author": "author in form: Фамилия Имя",
  "publisher": "publisher name",
  "year": publication_year_as_integer,
  "isbn": "ISBN code or unknown",
  "udk": "UDK code or unknown",
  "bbk": "BBK code with Cyrillic or unknown",
  "annotation": "book description or unknown"
}}

HINTS (use if extraction unclear):
author = "{author_hint}"
isbn = "{isbn_hint}"
udk = "{udk_hint}"
bbk = "{bbk_hint}"

OCR TEXT:
{ocr_text}

===== FINAL REMINDER BEFORE EXTRACTION =====
- Title: Copy EXACT text from OCR, stop at colon (:)
- NO invented words! NO paraphrasing! Character-by-character copy ONLY!
- Find bibliographic line (format: Title / Author. — Publisher, Year)

Return ONLY the JSON object:"""

def extract_json(text):
    """Extract JSON from LLM response"""
    text = text.strip()
    text = re.sub(r"^```json\s*", "", text)
    text = re.sub(r"\s*```$", "", text)
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("No JSON found")
    return m.group(0)

def normalize_author_title(data):
    """Normalize author from title if present, reject garbage titles"""
    # Reject titles that are clearly copyright/legal text
    if data.get("title"):
        title_lower = data["title"].lower()
        garbage_keywords = ["copyright", "trademark", "reserved", "indicia", "rights reserved"]
        if any(keyword in title_lower for keyword in garbage_keywords):
            data["title"] = "unknown"

    # Clean up title from GOST bibliographic format
    if data.get("title") and data["title"] != "unknown":
        title = data["title"]

        # Remove GOST catalog codes at the beginning (e.g., "B 68 ", "М68 ", "А-49 ")
        title = re.sub(r'^[А-ЯЁA-Z][\s\-]*\d+\s+', '', title)

        # Remove everything after " / " (author/translator info)
        # e.g., "Змеи /К. Маттисон; Пер. сангл. Т. Ю. Чугунова. — М.:..."
        title = re.split(r'\s*/\s*', title)[0]

        # Remove everything after ". —" (location, publisher, year, pages)
        # e.g., "Видения страшного суда. — М.: Изд-во ЭКСМО-Пресс, 2002"
        title = re.split(r'\.\s*—\s*', title)[0]

        # Strip subtitle/translation info after colon (e.g., "Title : Translation" -> "Title")
        title = re.split(r'\s*:\s*', title, maxsplit=1)[0]

        data["title"] = title.strip()

    # Extract author from title if embedded
    m = re.match(r'^([А-ЯЁ][а-яё]+),\s*([А-ЯЁ][а-яё]+)\.\s*[—-]\s*(.+)', data.get("title", ""))
    if m:
        surname, name, clean_title = m.groups()
        data["author"] = f"{surname} {name}"
        data["title"] = clean_title.strip()

def normalize_strings(data):
    """Normalize all string values to single line"""
    for k, v in data.items():
        if isinstance(v, str):
            data[k] = " ".join(v.split())


def normalize_old_cyrillic(text: str) -> str:
    """Convert pre-1918 Russian orthography to modern Cyrillic
    
    Common old letters:
    - Ѣ (yat) → Е
    - І (i decimal) → И  
    - Ѵ (izhitsa) → И
    - Ѳ (fita) → Ф
    - Ъ at end of words → remove
    """
    if not text:
        return text
    
    # Replace old Cyrillic letters
    text = text.replace('Ѣ', 'Е').replace('ѣ', 'е')
    text = text.replace('І', 'И').replace('і', 'и')
    text = text.replace('Ѵ', 'И').replace('ѵ', 'и')
    text = text.replace('Ѳ', 'Ф').replace('ѳ', 'ф')
    
    # Remove hard sign at end of words
    import re
    text = re.sub(r'Ъ\b', '', text)
    text = re.sub(r'ъ\b', '', text)
    
    return text

def normalize_old_cyrillic_data(data):
    """Apply old Cyrillic normalization to all text fields"""
    for key in ['title', 'author', 'publisher']:
        if data.get(key) and data[key] != "unknown":
            data[key] = normalize_old_cyrillic(data[key])
def clean_annotation(text: str) -> str:
    """Clean OCR annotation: remove line breaks, duplicates"""
    if not text:
        return "unknown"

    text = " ".join(text.split())
    fragments = re.split(r'([.!?])', text)
    cleaned = []
    seen_fragments = set()
    for i in range(0, len(fragments)-1, 2):
        fragment = fragments[i].strip()
        punct = fragments[i+1]
        full_sentence = f"{fragment}{punct}"
        if full_sentence not in seen_fragments:
            cleaned.append(full_sentence)
            seen_fragments.add(full_sentence)
    return " ".join(cleaned)

def normalize_classification(code: str) -> str:
    """Normalize BBK or UDK code"""
    if not code or code.lower() == "unknown":
        return "unknown"
    code = "".join(code.split())
    return code

def finalize(data, isbn_hint, udk_hint, bbk_hint):
    """Finalize metadata with fallbacks to hints"""
    for k in ["title", "author", "publisher", "annotation"]:
        if not data.get(k):
            data[k] = "unknown"
    if not isinstance(data.get("year"), int):
        data["year"] = 0

    # Use hints if LLM didn't provide value
    if not data.get("isbn") or data["isbn"] == "unknown":
        data["isbn"] = isbn_hint
    if not data.get("udk") or data["udk"] == "unknown":
        data["udk"] = udk_hint
    if not data.get("bbk") or data["bbk"] == "unknown":
        data["bbk"] = bbk_hint

    # Normalize classification codes
    data["bbk"] = normalize_classification(data.get("bbk"))
    data["udk"] = normalize_classification(data.get("udk"))

    # Normalize ISBN (remove publisher names in parentheses, validate format)
    isbn = data.get("isbn", "unknown")
    if isbn != "unknown":
        # Remove everything after opening parenthesis, semicolon, or comma
        isbn = re.split(r'\s*[\(;,]', isbn)[0].strip()
        # Keep only digits, dashes, and X
        clean_isbn = re.sub(r'[^0-9Xx\-]', '', isbn).upper()
        # Remove dashes for validation
        digits_only = re.sub(r'[^0-9Xx]', '', clean_isbn).upper()
        # Validate: must be 10 or 13 digits (X only allowed as last char in ISBN-10)
        if len(digits_only) == 10:
            if digits_only.isdigit() or (digits_only[-1] == 'X' and digits_only[:-1].isdigit()):
                data["isbn"] = clean_isbn
            else:
                data["isbn"] = "unknown"
        elif len(digits_only) == 13 and digits_only.isdigit():
            data["isbn"] = clean_isbn
        else:
            data["isbn"] = "unknown"

    # Clean annotation
    data["annotation"] = clean_annotation(data.get("annotation", "unknown"))

def build_cover_prompt(ocr_text: str) -> str:
    """Build prompt for extracting title and author from book cover"""
    return f"""You are extracting metadata from a BOOK COVER.

IMPORTANT:
- This is NOT a catalog entry
- Text may be decorative, short, or incomplete
- Ignore copyright, ISBN, publishers, and legal text
- Use visual hierarchy heuristics

RULES:
1. TITLE:
   - Usually the largest, most prominent text
   - Often at the top or center
   - May span multiple lines
   - May be a single word
   - MUST NOT include the author name
   - Take ONLY the part BEFORE any colon (:) or subtitle separator

2. AUTHOR:
   - Person name near the title
   - Often in uppercase or a badge
   - May be initials (e.g. J. K. Rowling, С.В. Максимов)
   - May appear above or below the title

3. Do NOT invent data
4. If unsure, return "unknown"

OUTPUT FORMAT:
Return ONLY valid JSON.
No markdown. No comments.

JSON:
{{
  "title": "string",
  "author": "string"
}}

OCR TEXT:
\"\"\"{ocr_text}\"\"\"
"""

def extract_cover_metadata(ocr_text: str) -> dict:
    """Extract title and author from book cover using LLM"""
    prompt = build_cover_prompt(ocr_text)

    try:
        response = requests.post(
            OLLAMA_COMPLETIONS_URL,
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "max_tokens": 200,
                "temperature": 0
            },
            timeout=90
        )
        response.raise_for_status()
        result_text = response.json()["choices"][0]["text"]

        # Parse JSON
        clean_json = extract_json(result_text)
        data = json.loads(clean_json)

        # Normalize
        normalize_author_title(data)
        normalize_strings(data)
        normalize_old_cyrillic_data(data)

        return {
            "title": data.get("title", "unknown"),
            "author": data.get("author", "unknown")
        }

    except Exception as e:
        logger.warning(f"Cover extraction failed: {e}")
        return {"title": "unknown", "author": "unknown"}

def extract_metadata_with_llm(ocr_main: str, ocr_eng: str = "") -> dict:
    """Extract all metadata using regex hints + LLM"""
    # Combine OCR texts for processing
    ocr_text = ocr_main + "\n" + ocr_eng if ocr_eng else ocr_main

    # Get regex hints
    author_hint = extract_author(ocr_text)
    isbn_hint = extract_isbn(ocr_text)
    udk_hint = extract_udk(ocr_text)
    bbk_hint = extract_bbk(ocr_text)
    title_hint = extract_title(ocr_text)
    publisher_hint = extract_publisher(ocr_text)
    year_hint = extract_year(ocr_text)

    # Build prompt with hints
    prompt = build_extraction_prompt(ocr_text, author_hint, isbn_hint, udk_hint, bbk_hint)

    # Try to call LLM, fall back to regex-only if unavailable
    try:
        response = requests.post(
            OLLAMA_COMPLETIONS_URL,
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "max_tokens": 800,  # Increased from 400 to allow full response
                "temperature": 0
            },
            timeout=90
        )
        response.raise_for_status()
        result_text = response.json()["choices"][0]["text"]

        # Parse and normalize
        clean_json = extract_json(result_text)
        data = json.loads(clean_json)
        normalize_author_title(data)
        normalize_strings(data)
        normalize_old_cyrillic_data(data)
        finalize(data, isbn_hint, udk_hint, bbk_hint)

        return data

    except Exception as e:
        logger.warning(f"LLM service unavailable, using regex-only extraction")

        # Fall back to regex-only extraction using all hints
        data = {
            "title": title_hint if title_hint != "unknown" else "unknown",
            "author": author_hint if author_hint != "unknown" else "unknown",
            "publisher": publisher_hint if publisher_hint != "unknown" else "unknown",
            "year": year_hint if year_hint > 0 else 0,
            "isbn": isbn_hint if isbn_hint != "unknown" else "unknown",
            "udk": udk_hint if udk_hint != "unknown" else "unknown",
            "bbk": bbk_hint if bbk_hint != "unknown" else "unknown",
            "annotation": "unknown"
        }
        normalize_author_title(data)
        normalize_strings(data)
        normalize_old_cyrillic_data(data)
        finalize(data, isbn_hint, udk_hint, bbk_hint)
        return data

# ========================================
# FASTAPI APP
# ========================================

app = FastAPI(title="Bookworm OCR + Metadata Service")

SERVICE_PORT = int(os.getenv("SERVICE_PORT", "5000"))

# ========================================
# MODELS
# ========================================

class OCRRequest(BaseModel):
    cover_image: Optional[str] = None
    info_images: Optional[List[str]] = None
    back_image: Optional[str] = None
    language: str = "rus"  # Pure Cyrillic for metadata; ISBN extracted from separate English OCR

class BookMetadata(BaseModel):
    title: str
    author: str
    authors: Optional[List[str]] = None
    publisher: str
    year: int
    isbn: str
    udk: str
    bbk: str
    annotation: str
    raw_ocr: Optional[str] = None  # Raw OCR text before extraction

# ========================================
# API ENDPOINT
# ========================================

@app.post("/extract", response_model=BookMetadata)
async def extract_metadata(req: OCRRequest):
    try:
        # Dual OCR approach (separate passes, NO mixed languages):
        # 1. Pure English OCR for ISBN extraction (ISBN is always Latin digits/letters)
        # 2. Pure Cyrillic OCR for all other metadata (title, author, publisher)

        ocr_eng = ""  # English OCR for ISBN
        ocr_cover = ""  # Cover OCR (for title/author extraction with cover prompt)
        ocr_info = ""  # Info pages OCR (for full metadata with catalog prompt)

        # Process cover separately (use RGB channel OCR for decorative covers)
        if req.cover_image:
            cover_img = image_from_base64(req.cover_image)
            ocr_eng += "=== COVER ===\n" + ocr_image(cover_img, "eng") + "\n"
            ocr_cover = ocr_with_vision_fallback(cover_img, ocr_image_rgb_channels(cover_img, req.language))

        # Process info pages
        for i, b64 in enumerate(req.info_images or [], 1):
            img = image_from_base64(b64)
            ocr_eng += f"=== INFO PAGE {i} ===\n" + ocr_image(img, "eng") + "\n"
            info_ocr_text = ocr_with_vision_fallback(img, ocr_image(img, req.language))
            ocr_info += f"=== INFO PAGE {i} ===\n" + info_ocr_text + "\n"

        # Process back cover
        if req.back_image:
            back_img = image_from_base64(req.back_image)
            ocr_eng += "=== BACK COVER ===\n" + ocr_image(back_img, "eng") + "\n"
            ocr_info += "=== BACK COVER ===\n" + ocr_image(back_img, req.language) + "\n"

        if not ocr_cover.strip() and not ocr_info.strip():
            raise HTTPException(400, "No OCR text")

        # Extract from cover (title/author only)
        cover_data = extract_cover_metadata(ocr_cover) if ocr_cover.strip() else {}

        # Extract from info pages (full metadata)
        info_data = extract_metadata_with_llm(ocr_info, ocr_eng) if ocr_info.strip() else {}

        # Merge: prefer info page data, use cover as fallback
        data = {
            "title": info_data.get("title", cover_data.get("title", "unknown")),
            "author": info_data.get("author", cover_data.get("author", "unknown")),
            "publisher": info_data.get("publisher", "unknown"),
            "year": info_data.get("year", 0),
            "isbn": info_data.get("isbn", "unknown"),
            "udk": info_data.get("udk", "unknown"),
            "bbk": info_data.get("bbk", "unknown"),
            "annotation": info_data.get("annotation", "unknown")
        }

        # If info pages had no title/author, use cover
        if data["title"] == "unknown" and cover_data.get("title", "unknown") != "unknown":
            data["title"] = cover_data.get("title", "unknown")
        if data["author"] == "unknown" and cover_data.get("author", "unknown") != "unknown":
            data["author"] = cover_data.get("author", "unknown")

        data["raw_ocr"] = f"=== COVER ===\n{ocr_cover}\n\n{ocr_info}"
        data["authors"] = [data["author"]] if data["author"] != "unknown" else []

        # Log
        logger.info(f"Cover OCR:\n{ocr_cover}")
        logger.info(f"Info OCR:\n{ocr_info}")
        log_data = {k: v for k, v in data.items() if k != "raw_ocr"}
        logger.info(f"Extracted metadata: {json.dumps(log_data, ensure_ascii=False, indent=2)}")

        return BookMetadata(**data)

    except Exception as e:
        logger.exception(e)
        raise HTTPException(500, str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
