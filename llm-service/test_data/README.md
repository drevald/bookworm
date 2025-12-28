# Test Data Structure

Each subdirectory represents a test case for a book.

## Directory Structure

```
test_data/
  ├── sample_book/
  │   ├── cover.jpg        # Book cover image
  │   ├── info.jpg         # Info page 1 (with ISBN, UDK, BBK, etc.)
  │   ├── info2.jpg        # Info page 2 (optional, if book has multiple info pages)
  │   ├── back.jpg         # Back cover image (with annotation)
  │   └── expected.json    # Expected metadata extraction result
  ├── another_book/
  │   ├── cover.jpg
  │   ├── info.jpg
  │   ├── info2.jpg        # Additional info pages supported
  │   ├── back.jpg
  │   └── expected.json
```

**Note:** You can have multiple info pages (info.jpg, info2.jpg, info3.jpg, etc.). All info pages will be processed together during OCR extraction.

## Creating Test Images

To create stub images for a new test case:

```python
from PIL import Image
from pathlib import Path

# Create directory
book_dir = Path("test_data/my_book")
book_dir.mkdir(parents=True, exist_ok=True)

# Create blank images
img = Image.new('RGB', (800, 600), color='white')
img.save(book_dir / 'cover.jpg')
img.save(book_dir / 'info.jpg')
img.save(book_dir / 'back.jpg')
```

Or use real scanned book page images.

## Expected JSON Format

```json
{
  "title": "Book Title",
  "author": "Author Name",
  "isbn": "978-5-1234-5678-9",
  "year": 2023,
  "udk": "004.43",
  "bbk": "Ч84",
  "publisher": "Publisher Name",
  "annotation": "Book annotation text"
}
```

## Running Tests

```bash
cd llm-service
pytest test_app.py -v -s
```
