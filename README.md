# Bookworm - Home Library Management System

A book cataloging system with OCR and LLM-powered metadata extraction.

## Project Structure

```
bookworm/
├── server/                # Java Spring Boot backend (OCR + LLM + gRPC + Web UI)
├── android/               # Android client app
├── docker-compose.yml     # Docker orchestration
└── .env                   # Environment configuration
```

## Services

| Service | Port | Description |
|---------|------|-------------|
| **server** | 4040 (HTTP), 9090 (gRPC) | Java server (gRPC + Web UI) |
| **ocr-service** | 5000 | Python OCR + metadata extraction (uses ocr.py logic) |
| **db** | 5437 | PostgreSQL database |

## Configuration

Copy `.env.example` to `.env` and customize:

```bash
cp .env.example .env
```

**Available environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_NAME` | bookworm | Database name |
| `DB_USERNAME` | bookworm | Database user |
| `DB_PASSWORD` | bookworm | Database password |
| `DB_PORT` | 5437 | Database port (host) |
| `GRPC_PORT` | 9090 | gRPC server port |
| `WEB_UI_PORT` | 4040 | Web UI port |
| `OCR_SERVICE_PORT` | 5000 | Python OCR service port |
| `OLLAMA_URL` | http://192.168.0.189:11434 | Ollama LLM server URL |
| `OLLAMA_MODEL` | qwen2.5:7b | LLM model to use |

## Quick Start

```bash
# Configure (optional - uses defaults if skipped)
cp .env.example .env
# Edit .env with your settings

# Start all services
docker compose up -d

# View logs
docker compose logs -f

# Stop all services
docker compose down
```

## Access Points

- **Web UI**: http://dobby:4040
- **Database**: dobby:5437

## Development

See individual README files:
- `server/` - Java server documentation
- `android/` - Android app documentation

## Features

- 📚 Book cataloging with OCR
- 🤖 AI-powered metadata extraction (Ollama)
- 📱 Android app for scanning books
- 🔍 Russian and English language support
- 🖼️ Image management (cover, info page, back)

## License

Private project
