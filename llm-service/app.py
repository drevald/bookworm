"""
OCR and Metadata Extraction Service - Python version (proven to work)
Based on working ocr.py logic
"""
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List
import pytesseract
from PIL import Image
import requests
import json
import re
import io
import base64
import logging
import os

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Bookworm OCR + Metadata Service")

# Configuration from environment variables
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://192.168.0.134:11434")
OLLAMA_MODEL = os.getenv("OLLAMA_MODEL", "qwen2.5:7b")
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "5000"))

# Build full Ollama completions URL
OLLAMA_COMPLETIONS_URL = f"{OLLAMA_URL}/v1/completions"

logger.info(f"Configuration:")
logger.info(f"  OLLAMA_URL: {OLLAMA_URL}")
logger.info(f"  OLLAMA_MODEL: {OLLAMA_MODEL}")
logger.info(f"  SERVICE_PORT: {SERVICE_PORT}")


class OCRRequest(BaseModel):
    """Request to perform OCR on images"""
    cover_image: Optional[str] = None  # Base64 encoded
    info_image: Optional[str] = None   # Base64 encoded
    back_image: Optional[str] = None   # Base64 encoded
    language: str = "rus+eng"


class BookMetadata(BaseModel):
    """Extracted book metadata"""
    title: Optional[str] = None
    author: Optional[str] = None
    authors: Optional[List[str]] = None
    isbn: Optional[str] = None
    publisher: Optional[str] = None
    publication_year: Optional[int] = None
    udk: Optional[str] = None
    bbk: Optional[str] = None
    annotation: Optional[str] = None
    confidence: Optional[float] = None


# ========================================
# OCR Functions (from ocr.py - proven to work)
# ========================================

def image_from_base64(base64_str: str) -> Image.Image:
    """Convert base64 string to PIL Image"""
    image_bytes = base64.b64decode(base64_str)
    return Image.open(io.BytesIO(image_bytes))


def ocr_image(image: Image.Image, lang: str = "rus+eng") -> str:
    """Perform OCR on image using pytesseract (exactly like ocr.py)"""
    return pytesseract.image_to_string(image, lang=lang)


# ========================================
# Regex Extraction (from ocr.py)
# ========================================

def extract_author(ocr: str) -> str:
    """Extract author using patterns from ocr.py"""
    patterns = [
        r'^[А-ЯЁ][а-яё]+ [А-ЯЁ]\. ?[А-ЯЁ]\.$',
        r'^[А-ЯЁ][а-яё]+, [А-ЯЁ][а-яё]+(?: [А-ЯЁ][а-яё]+)?$'
    ]
    for p in patterns:
        m = re.search(p, ocr, re.MULTILINE)
        if m:
            return m.group(0)
    return "unknown"


def normalize_author(author: str) -> str:
    """Normalize author name from catalog format"""
    if "," in author:
        parts = [p.strip() for p in author.split(",")]
        return f"{parts[0]} {parts[1]}"
    return author


def extract_isbn(ocr: str) -> str:
    """Extract ISBN (from ocr.py)"""
    m = re.search(r'ISBN\s*[:]? ?([0-9\-–]+)', ocr, re.IGNORECASE)
    return m.group(1) if m else "unknown"


def extract_udk(ocr: str) -> str:
    """Extract UDK (from ocr.py)"""
    m = re.search(r'УДК\s*[:]? ?([\d.:()+=-]+)', ocr)
    return m.group(1) if m else "unknown"


def extract_bbk(ocr: str) -> str:
    """Extract BBK (from ocr.py)"""
    m = re.search(r'(?:ББК\s*[:.]?\s*)?([А-ЯЁ][\d\(\)=:А-ЯЁ]+)', ocr)
    return m.group(1) if m else "unknown"


# ========================================
# LLM Prompt (from ocr.py)
# ========================================

def build_prompt(ocr_text: str, author_hint: str, isbn_hint: str, udk_hint: str, bbk_hint: str) -> str:
    """Build LLM prompt (from ocr.py)"""
    return f"""You are a bibliographic metadata extractor for Russian books.

MANDATORY RULES:
- Return ONLY valid JSON
- Do NOT wrap JSON in markdown
- All fields must be present
- null values are FORBIDDEN
- All string values MUST be single-line (no line breaks)

AUTHOR RULES:
- Line "Фамилия И. О." before title IS the author
- Catalog form "Фамилия, Имя." MUST be normalized
- Title must NEVER start with an author name

FIELDS:
{{
  "title": "string",
  "author": "string",
  "publisher": "string",
  "year": integer,
  "isbn": "string",
  "udk": "string",
  "bbk": "string",
  "annotation": "string"
}}

CLASSIFICATION:
- BBK may start with Cyrillic letter (e.g. Ч84)
- BBK and UDK are NOT noise

HINTS (use if correct):
author = "{author_hint}"
isbn = "{isbn_hint}"
udk = "{udk_hint}"
bbk = "{bbk_hint}"

OCR TEXT:
\"\"\"{ocr_text}\"\"\"
"""


# ========================================
# Normalization (from ocr.py)
# ========================================

def extract_json(text: str) -> str:
    """Extract JSON from LLM response"""
    text = text.strip()
    text = re.sub(r"^```json\s*", "", text)
    text = re.sub(r"\s*```$", "", text)
    m = re.search(r"\{.*\}", text, re.S)
    if not m:
        raise ValueError("No JSON found")
    return m.group(0)


def normalize_author_title(data: dict):
    """Normalize if author appears in title"""
    m = re.match(r'^([А-ЯЁ][а-яё]+),\s*([А-ЯЁ][а-яё]+)\.\s*[—-]\s*(.+)', data.get("title", ""))
    if m:
        surname, name, clean_title = m.groups()
        data["author"] = f"{surname} {name}"
        data["title"] = clean_title.strip()


def normalize_strings(data: dict):
    """Remove extra whitespace"""
    for k, v in data.items():
        if isinstance(v, str):
            data[k] = " ".join(v.split())


def finalize(data: dict, isbn_hint: str, udk_hint: str, bbk_hint: str):
    """Fill in missing fields with hints and defaults"""
    for k in ["title", "author", "publisher", "annotation"]:
        if not data.get(k):
            data[k] = "unknown"

    if not isinstance(data.get("year"), int):
        data["year"] = 0

    # Use hints if LLM didn't provide values
    if not data.get("isbn") or data["isbn"] == "unknown":
        data["isbn"] = isbn_hint
    if not data.get("udk") or data["udk"] == "unknown":
        data["udk"] = udk_hint
    if not data.get("bbk") or data["bbk"] == "unknown":
        data["bbk"] = bbk_hint


# ========================================
# API Endpoints
# ========================================

@app.get("/health")
async def health_check():
    """Health check"""
    return {"status": "healthy", "service": "ocr-metadata"}


@app.post("/extract", response_model=BookMetadata)
async def extract_metadata(request: OCRRequest):
    """
    Extract metadata from book images using OCR + regex + LLM
    (Exact logic from working ocr.py)
    """
    try:
        logger.info("=== OCR + METADATA EXTRACTION START ===")

        # Step 1: OCR on all provided images
        texts = []
        combined_text = ""

        if request.info_image:
            logger.info("Performing OCR on info page...")
            image = image_from_base64(request.info_image)
            text = ocr_image(image, request.language)
            texts.append(f"=== INFO PAGE ===\n{text}")
            combined_text += text + "\n"
            logger.info(f"Info page OCR: {len(text)} chars")

        if request.cover_image:
            logger.info("Performing OCR on cover...")
            image = image_from_base64(request.cover_image)
            text = ocr_image(image, request.language)
            texts.append(f"=== COVER ===\n{text}")
            combined_text += text + "\n"
            logger.info(f"Cover OCR: {len(text)} chars")

        if request.back_image:
            logger.info("Performing OCR on back cover...")
            image = image_from_base64(request.back_image)
            text = ocr_image(image, request.language)
            texts.append(f"=== BACK COVER ===\n{text}")
            combined_text += text + "\n"
            logger.info(f"Back cover OCR: {len(text)} chars")

        if not combined_text.strip():
            raise HTTPException(status_code=400, detail="No text extracted from images")

        logger.info(f"\n===== COMBINED OCR TEXT =====\n{combined_text[:500]}...\n")

        # Step 2: Extract regex hints (from ocr.py)
        author_hint = normalize_author(extract_author(combined_text))
        isbn_hint = extract_isbn(combined_text)
        udk_hint = extract_udk(combined_text)
        bbk_hint = extract_bbk(combined_text)

        logger.info("===== REGEX HINTS =====")
        logger.info(f"AUTHOR: {author_hint}")
        logger.info(f"ISBN  : {isbn_hint}")
        logger.info(f"UDK   : {udk_hint}")
        logger.info(f"BBK   : {bbk_hint}")

        # Step 3: Build LLM prompt
        prompt = build_prompt(combined_text, author_hint, isbn_hint, udk_hint, bbk_hint)

        # Step 4: Call Ollama
        logger.info(f"Calling Ollama at {OLLAMA_COMPLETIONS_URL}")
        response = requests.post(
            OLLAMA_COMPLETIONS_URL,
            json={
                "model": OLLAMA_MODEL,
                "prompt": prompt,
                "max_tokens": 400,
                "temperature": 0
            },
            timeout=60
        )
        response.raise_for_status()
        result_text = response.json()["choices"][0]["text"]

        logger.info("===== MODEL RAW OUTPUT =====")
        logger.info(result_text)

        # Step 5: Parse and normalize
        clean_json = extract_json(result_text)
        data = json.loads(clean_json)
        normalize_author_title(data)
        normalize_strings(data)
        finalize(data, isbn_hint, udk_hint, bbk_hint)

        logger.info("===== FINAL METADATA =====")
        logger.info(json.dumps(data, indent=2, ensure_ascii=False))

        # Convert to response model
        metadata = BookMetadata(**data)
        if metadata.author and metadata.author != "unknown":
            metadata.authors = [metadata.author]

        return metadata

    except Exception as e:
        logger.error(f"Error: {str(e)}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
