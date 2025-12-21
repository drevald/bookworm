# OCR + Metadata Extraction Service

Python service that does OCR and metadata extraction using the **proven working logic from ocr.py**.

## What it does:
1. **OCR** - Uses pytesseract (same as ocr.py)
2. **Regex hints** - Extracts author, ISBN, UDK, BBK using simple patterns
3. **LLM** - Calls Ollama to extract structured metadata
4. **Normalization** - Cleans and formats the results

## API:

### POST /extract
Extract metadata from book images.

**Request:**
```json
{
  "cover_image": "base64...",  // optional
  "info_image": "base64...",   // optional
  "back_image": "base64...",   // optional
  "language": "rus+eng"
}
```

**Response:**
```json
{
  "title": "Подмосковье. Прогулки по городам",
  "author": "Жебрак Михаил Юрьевич",
  "isbn": "978-5-17-165841-0",
  "publisher": "Издательство АСТ",
  "publication_year": 2025,
  "udk": "913(470.311)",
  "bbk": "26.89(2Рос-4)",
  ...
}
```

## Run locally:
```bash
pip install -r requirements.txt
python app.py
```

## Run with Docker:
```bash
docker-compose up ocr-service
```

## Test:
```bash
curl http://localhost:5000/health
```
