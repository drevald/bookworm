"""
Bookworm OCR + Metadata Service
Extracts bibliographic metadata from book images using Tesseract OCR
and rule-based parsing (ГОСТ 7.1-2003).
"""

import base64
import io
import json
import logging
import os

import pytesseract
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List

from ocr import (
    image_from_base64,
    ocr_image,
    ocr_image_rgb_channels,
    extract_metadata_from_info_page,
    extract_title_author_from_cover,
    detect_barcode_isbn,
    preprocess_stages,
    preprocess_for_ocr,
)

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger(__name__)

TESSERACT_CMD = os.getenv("TESSERACT_CMD")
if TESSERACT_CMD:
    pytesseract.pytesseract.tesseract_cmd = TESSERACT_CMD

SERVICE_PORT = int(os.getenv("SERVICE_PORT", "5000"))

# ========================================
# FASTAPI
# ========================================

app = FastAPI(title="Bookworm OCR + Metadata Service")

class OCRRequest(BaseModel):
    cover_image:   Optional[str]       = None
    info_images:   Optional[List[str]] = None
    back_image:    Optional[str]       = None
    barcode_image: Optional[str]       = None
    language:      str                 = "rus"
    gost_parser:   Optional[str]       = None  # "2018"|"2003"|"84"|"2008"|None


class PreprocessRequest(BaseModel):
    image: str  # base64-encoded image

class BookMetadata(BaseModel):
    title:          str
    author:         str
    authors:        Optional[List[str]] = None
    publisher:      str
    year:           int
    isbn:           str
    udk:            str
    bbk:            str
    annotation:     str
    raw_ocr:        Optional[str] = None
    barcode_value:  Optional[str] = None

# ========================================
# ENDPOINT
# ========================================

@app.post("/extract", response_model=BookMetadata)
async def extract_metadata(req: OCRRequest):
    try:
        ocr_cover = ""
        ocr_info  = ""
        ocr_eng   = ""   # English OCR pass for ISBN (always Latin digits)

        # Cover
        if req.cover_image:
            cover_img = image_from_base64(req.cover_image)
            ocr_cover = ocr_image_rgb_channels(cover_img, req.language)
            ocr_eng  += "=== COVER ===\n" + ocr_image(cover_img, "eng") + "\n"

        # Info pages
        for i, b64 in enumerate(req.info_images or [], 1):
            img = image_from_base64(b64)
            page_text = ocr_image(img, req.language)
            ocr_info += f"=== INFO PAGE {i} ===\n{page_text}\n"
            ocr_eng  += f"=== INFO PAGE {i} ===\n" + ocr_image(img, "eng") + "\n"

        # Back cover
        if req.back_image:
            back_img = image_from_base64(req.back_image)
            ocr_info += "=== BACK COVER ===\n" + ocr_image(back_img, req.language) + "\n"
            ocr_eng  += "=== BACK COVER ===\n" + ocr_image(back_img, "eng") + "\n"

        # Barcode
        barcode_isbn = None
        if req.barcode_image:
            barcode_img = image_from_base64(req.barcode_image)
            barcode_isbn = detect_barcode_isbn(barcode_img)
            if barcode_isbn:
                logger.info("Barcode detected ISBN: %s", barcode_isbn)

        if not ocr_cover.strip() and not ocr_info.strip() and not barcode_isbn:
            raise HTTPException(400, "No OCR text extracted from provided images")

        # Extract
        cover_data = extract_title_author_from_cover(ocr_cover) if ocr_cover.strip() else {}
        info_data  = extract_metadata_from_info_page(ocr_info, ocr_eng, req.gost_parser) if ocr_info.strip() else {}

        # Merge — info page wins; cover is fallback for title/author; barcode ISBN wins for isbn
        data = {
            "title":      info_data.get("title",     cover_data.get("title",     "unknown")),
            "author":     info_data.get("author",    cover_data.get("author",    "unknown")),
            "publisher":  info_data.get("publisher", "unknown"),
            "year":       info_data.get("year",      0),
            "isbn":       info_data.get("isbn",      "unknown"),
            "udk":        info_data.get("udk",       "unknown"),
            "bbk":        info_data.get("bbk",       "unknown"),
            "annotation": info_data.get("annotation","unknown"),
        }

        # Barcode ISBN overrides OCR-parsed ISBN (more reliable)
        if barcode_isbn:
            data["isbn"] = barcode_isbn

        if data["title"]  == "unknown" and cover_data.get("title")  not in (None, "unknown"):
            data["title"]  = cover_data["title"]
        if data["author"] == "unknown" and cover_data.get("author") not in (None, "unknown"):
            data["author"] = cover_data["author"]

        data["raw_ocr"] = f"=== COVER ===\n{ocr_cover}\n\n{ocr_info}"
        data["authors"] = [data["author"]] if data["author"] != "unknown" else []
        data["barcode_value"] = barcode_isbn

        logger.info("Extracted metadata: %s", json.dumps(
            {k: v for k, v in data.items() if k != "raw_ocr"},
            ensure_ascii=False, indent=2
        ))

        return BookMetadata(**data)

    except HTTPException:
        raise
    except Exception as e:
        logger.exception(e)
        raise HTTPException(500, str(e))


@app.post("/preprocess-image")
async def preprocess_image_endpoint(req: PreprocessRequest):
    """Return the dewarped + illumination-corrected version of a single image."""
    try:
        img       = image_from_base64(req.image)
        processed = preprocess_for_ocr(img)
        buf = io.BytesIO()
        processed.save(buf, format="JPEG", quality=90)
        return {"image": base64.b64encode(buf.getvalue()).decode()}
    except Exception as e:
        logger.exception(e)
        raise HTTPException(500, str(e))


@app.post("/preprocess-stages")
async def preprocess_stages_endpoint(req: PreprocessRequest):
    """
    Return all intermediate preprocessing stages for a single image.

    Response:
        perspective  — after planar homography (base64 JPEG)
        dewarped     — after full pipeline: perspective + dewarp + illumination (base64 JPEG)
    """
    try:
        img    = image_from_base64(req.image)
        stages = preprocess_stages(img)
        result = {}
        for name, pil_img in stages.items():
            buf = io.BytesIO()
            pil_img.save(buf, format="JPEG", quality=90)
            result[name] = base64.b64encode(buf.getvalue()).decode()
        return result
    except Exception as e:
        logger.exception(e)
        raise HTTPException(500, str(e))


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
