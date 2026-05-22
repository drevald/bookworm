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
import re

import pytesseract
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List

from ocr import (
    image_from_base64,
    ocr_image,
    ocr_image_rgb_channels,
    ocr_info_page,
    ocr_isbn_from_image,
    extract_metadata_from_info_page,
    extract_metadata_from_title_page,
    extract_title_author_from_cover,
    detect_barcode_isbn,
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
    title_images:  Optional[List[str]] = None
    back_image:    Optional[str]       = None
    barcode_image: Optional[str]       = None
    language:      str                 = "rus"
    gost_parser:   Optional[str]       = None  # "2018"|"2003"|"84"|"2008"|None


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
            # Two-pass Russian OCR: preprocessed full page + raw catalog crop
            page_text = ocr_info_page(img) if req.language == "rus" else ocr_image(img, req.language)
            ocr_info += f"=== INFO PAGE {i} ===\n{page_text}\n"
            # English OCR: strip-scan for ISBN (full-image OCR stops mid-page on tall images)
            isbn_line = ocr_isbn_from_image(img)
            if isbn_line:
                ocr_eng += f"=== INFO PAGE {i} ISBN ===\n{isbn_line}\n"

        # Title pages (old books with no GOST info page)
        title_page_data = {}
        for i, b64 in enumerate(req.title_images or [], 1):
            img = image_from_base64(b64)
            result = extract_metadata_from_title_page(img)
            logger.info("Title page %d extracted: %s", i,
                        {k: v for k, v in result.items() if k != "annotation"})
            # Merge: first title page with a non-unknown value wins per field
            for field, val in result.items():
                if field not in title_page_data and val not in ("unknown", 0, None):
                    title_page_data[field] = val

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

        if not ocr_cover.strip() and not ocr_info.strip() and not title_page_data and not barcode_isbn:
            raise HTTPException(400, "No OCR text extracted from provided images")

        # Extract
        cover_data = extract_title_author_from_cover(ocr_cover) if ocr_cover.strip() else {}
        info_data  = extract_metadata_from_info_page(ocr_info, ocr_eng, req.gost_parser) if ocr_info.strip() else {}

        def _val(field, default):
            """Pick best value: info page → title page → cover → default."""
            v = info_data.get(field, default)
            if v in ("unknown", 0, None):
                v = title_page_data.get(field, default)
            return v

        # Merge — info page wins; title page fills gaps; cover is last fallback for title/author
        data = {
            "title":      _val("title",     cover_data.get("title",  "unknown")),
            "author":     _val("author",    cover_data.get("author", "unknown")),
            "publisher":  _val("publisher", "unknown"),
            "year":       _val("year",      0),
            "isbn":       _val("isbn",      "unknown"),
            "udk":        _val("udk",       "unknown"),
            "bbk":        _val("bbk",       "unknown"),
            "annotation": _val("annotation","unknown"),
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



if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
