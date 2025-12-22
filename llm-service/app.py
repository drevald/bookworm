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

# Import OCR functions
from ocr import image_from_base64, ocr_image, extract_metadata_with_llm

# ========================================
# ENV + LOGGING
# ========================================

try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Bookworm OCR + Metadata Service")

SERVICE_PORT = int(os.getenv("SERVICE_PORT", "5000"))

# ========================================
# MODELS
# ========================================

class OCRRequest(BaseModel):
    cover_image: Optional[str] = None
    info_images: Optional[List[str]] = None
    back_image: Optional[str] = None
    language: str = "rus+eng"

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
        combined = ""

        # OCR cover image
        if req.cover_image:
            combined += "=== COVER ===\n"
            combined += ocr_image(image_from_base64(req.cover_image), req.language) + "\n"

        # OCR info pages
        for i, b64 in enumerate(req.info_images or [], 1):
            combined += f"=== INFO PAGE {i} ===\n"
            combined += ocr_image(image_from_base64(b64), req.language) + "\n"

        # OCR back cover
        if req.back_image:
            combined += "=== BACK COVER ===\n"
            combined += ocr_image(image_from_base64(req.back_image), req.language) + "\n"

        if not combined.strip():
            raise HTTPException(400, "No OCR text")

        # Extract metadata using LLM with regex hints
        data = extract_metadata_with_llm(combined)
        data["raw_ocr"] = combined  # Include raw OCR text for debugging
        data["authors"] = [data["author"]] if data["author"] != "unknown" else []

        logger.info(json.dumps(data, ensure_ascii=False, indent=2))
        return BookMetadata(**data)

    except Exception as e:
        logger.exception(e)
        raise HTTPException(500, str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=SERVICE_PORT)
