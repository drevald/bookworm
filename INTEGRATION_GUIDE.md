# Bookworm LLM Integration Guide

This guide explains how the LLM metadata extraction is integrated with the main Bookworm application.

## Architecture

```
┌─────────────────┐         ┌──────────────────────┐
│  Android App    │         │      Ollama          │
│  (android)      │         │   (LLM Server)       │
└────────┬────────┘         └──────────┬───────────┘
         │ gRPC                         │
         │                              │ HTTP
         ▼                              │
┌─────────────────────────────────────┐ │
│      Server (Java)                  │ │
│  ┌──────────────────────────────┐   │ │
│  │  1. OCR Service              │   │ │
│  │     (Tesseract)              │   │ │
│  └────────────┬─────────────────┘   │ │
│               │                     │ │
│               ▼                     │ │
│  ┌──────────────────────────────┐   │ │
│  │  2. LLM Metadata Extractor ──┼───┼─┘
│  │     (calls Ollama directly)  │   │
│  └────────────┬─────────────────┘   │
│               │ (on failure)        │
│               ▼                     │
│  ┌──────────────────────────────┐   │
│  │  3. BookParser (regex)       │   │
│  │     (fallback)               │   │
│  └──────────────────────────────┘   │
└─────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│   PostgreSQL    │
└─────────────────┘
```

## Setup

### 1. Install Ollama

```bash
# Visit https://ollama.ai/ and install Ollama

# Pull a model
ollama pull llama3.2
# Or use mistral:
# ollama pull mistral
```

### 2. Start Ollama Service

```bash
# Start Ollama (if not already running)
ollama serve

# Verify it's running
curl http://localhost:11434/api/tags
```

### 3. Start Server

```bash
cd server

# The server will automatically detect and use Ollama
docker-compose up -d

# Check logs to verify LLM integration
docker logs bookworm-server -f
```

## How It Works

### Processing Flow

1. **Book Upload**: User uploads book images via Android app
2. **Image Storage**: Images stored in PostgreSQL as LOBs
3. **OCR Processing**: Tesseract extracts text from COVER, INFO_PAGE, and BACK images
4. **LLM Extraction**:
   - Java server sends OCR text to Ollama directly via HTTP
   - LLM analyzes text and extracts metadata
   - Handles OCR errors (e.g., "ПОБОВНИК CMEPTH" → "Любовник смерти")
   - Returns structured JSON with title, author, ISBN, etc.
5. **Fallback**: If Ollama fails, falls back to regex-based `BookParser`
6. **Database Update**: Book metadata saved to PostgreSQL

### Configuration

**Java Application** (`server/.env` or `application.properties`):
```properties
bookworm.ollama.url=http://192.168.0.189:11434
bookworm.ollama.model=qwen2.5:7b
bookworm.llm.service.enabled=true
```

### Disabling LLM Extraction

To disable and use only regex parsing:

```properties
bookworm.llm.service.enabled=false
```

## Testing

### Test with Existing Book

```bash
# Reprocess an existing book to use LLM extraction
curl -X POST "http://localhost:4040/books/{BOOK_ID}/reprocess?language=rus+eng"

# Check logs to see LLM extraction in action
docker logs bookworm-server -f
```

## Advantages of LLM Extraction

1. **OCR Error Correction**: Fixes common mistakes like Cyrillic/Latin confusion
2. **Context Understanding**: Uses surrounding text to infer correct metadata
3. **Better Parsing**: Handles various book formats and layouts
4. **Multilingual**: Works with mixed Cyrillic/Latin text
5. **Adaptive**: Can handle edge cases that regex patterns miss

## Performance

- **LLM Extraction**: ~5-15 seconds (depends on model and hardware)
- **Regex Parsing**: <1 second
- **Recommendation**: Use LLM for better accuracy, especially with Russian books

## Future Enhancements

- [ ] GPU acceleration for faster LLM inference
- [ ] Caching of LLM responses
- [ ] Batch processing endpoint
- [ ] Support for multiple LLM backends (OpenAI, Claude, etc.)
- [ ] Confidence-based fallback (if LLM confidence < 0.7, try regex)
- [ ] A/B testing to compare LLM vs regex accuracy
