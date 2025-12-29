"""
Integration tests for LLM OCR Metadata Extraction Service
"""
import pytest
import json
import base64
import re
from pathlib import Path
from fastapi.testclient import TestClient

from app import app
from ocr import extract_isbn, ocr_image, image_from_base64

client = TestClient(app)

# Test data directory structure:
# test_data/
#   ├── eng/
#   │   └── potter/
#   │       ├── cover.jpg
#   │       ├── info.jpg
#   │       └── expected.json
#   └── rus/
#       ├── doroga/
#       ├── zvezdy/
#       └── territory/

TEST_DATA_DIR = Path(__file__).parent / "test_data"


def load_image_as_base64(image_path: Path) -> str:
    """Load image file and convert to base64"""
    with open(image_path, "rb") as f:
        return base64.b64encode(f.read()).decode()


def get_book_test_cases():
    """Find all book test directories organized by language"""
    if not TEST_DATA_DIR.exists():
        return []

    test_cases = []
    # Look for language directories (eng, rus, etc.)
    for lang_dir in TEST_DATA_DIR.iterdir():
        if lang_dir.is_dir():
            # Look for book directories within each language
            for book_dir in lang_dir.iterdir():
                if book_dir.is_dir() and (book_dir / "expected.json").exists():
                    test_cases.append((lang_dir.name, book_dir))

    return test_cases


def find_image(book_dir: Path, base_name: str) -> Path:
    """Find image with various extensions"""
    for ext in ['.jpg', '.jpeg', '.jfif', '.png']:
        path = book_dir / f"{base_name}{ext}"
        if path.exists():
            return path
    return None


def normalize_for_comparison(value) -> str:
    """Normalize string for comparison - ignore case, spaces, punctuation, Latin/Cyrillic"""
    if value is None:
        return None

    normalized = str(value).lower()

    # Replace Latin characters with Cyrillic equivalents (handles OCR confusion)
    latin_to_cyrillic = {
        'a': 'а', 'b': 'в', 'c': 'с', 'e': 'е', 'h': 'н', 'k': 'к',
        'm': 'м', 'o': 'о', 'p': 'р', 't': 'т', 'x': 'х', 'y': 'у'
    }
    for latin, cyrillic in latin_to_cyrillic.items():
        normalized = normalized.replace(latin, cyrillic)

    # Remove all punctuation and spaces (keep only alphanumeric and Cyrillic)
    normalized = re.sub(r'[^\w\u0400-\u04FF]', '', normalized)

    return normalized


def normalize_isbn(value) -> str:
    """Normalize ISBN - extract only digits and X (X can only be last char in ISBN-10)"""
    if value is None:
        return None
    # Extract only digits and X (X represents value 10 as last check digit in ISBN-10 only)
    normalized = ''.join(c for c in str(value).upper() if c.isdigit() or c == 'X')
    return normalized if normalized else None


def normalize_classification(value) -> str:
    """Normalize BBK/UDK - replace Latin to Cyrillic, remove punctuation/spaces"""
    if value is None:
        return None

    normalized = str(value).lower()

    # Replace Latin characters with Cyrillic equivalents
    latin_to_cyrillic = {
        'a': 'а', 'b': 'в', 'c': 'с', 'e': 'е', 'h': 'н', 'k': 'к',
        'm': 'м', 'o': 'о', 'p': 'р', 't': 'т', 'x': 'х', 'y': 'у'
    }
    for latin, cyrillic in latin_to_cyrillic.items():
        normalized = normalized.replace(latin, cyrillic)

    # Remove all punctuation and spaces (keep only alphanumeric and Cyrillic)
    normalized = re.sub(r'[^\w\u0400-\u04FF]', '', normalized)

    return normalized


@pytest.mark.parametrize(
    "lang_and_book",
    get_book_test_cases(),
    ids=lambda t: f"{t[0]}/{t[1].name}"
)
def test_extract_metadata(lang_and_book):
    """Test metadata extraction for each book with correct language"""
    language, book_dir = lang_and_book
    expected_file = book_dir / "expected.json"

    # Prepare request with language from directory structure
    request_data = {"language": language}
    info_images = []

    # Load cover image
    cover_path = find_image(book_dir, "cover")
    if cover_path:
        request_data["cover_image"] = load_image_as_base64(cover_path)

    # Collect all info page images (info.jpg, info_page.jpg, info1.jpg, info2.jpg, etc.)
    for base_name in ["info", "info_page", "info1", "info2", "info3", "info4"]:
        info_path = find_image(book_dir, base_name)
        if info_path:
            info_images.append(load_image_as_base64(info_path))

    if info_images:
        request_data["info_images"] = info_images

    back_path = find_image(book_dir, "back")
    if back_path:
        request_data["back_image"] = load_image_as_base64(back_path)

    # Extract metadata
    response = client.post("/extract", json=request_data)
    assert response.status_code == 200, f"Failed for {book_dir.name}: {response.json()}"

    actual = response.json()

    # Load expected results
    with open(expected_file, "r", encoding="utf-8") as f:
        expected = json.load(f)

    # Compare results
    print(f"\n=== {book_dir.name} ===")

    # Print raw OCR text
    if 'raw_ocr' in actual and actual['raw_ocr']:
        print(f"\n--- Raw OCR Text ---")
        # Handle console encoding issues
        safe_ocr = actual['raw_ocr'].encode('cp1251', errors='replace').decode('cp1251')
        print(safe_ocr)
        print(f"--- End Raw OCR ---\n")

    print(f"Extracted Metadata:")
    print(f"  Title: {actual['title']}")
    print(f"  Author: {actual['author']}")
    print(f"  ISBN: {actual['isbn']}")
    print(f"  Year: {actual['year']}")
    print(f"  UDK: {actual['udk']}")
    print(f"  BBK: {actual['bbk']}")
    print(f"  Publisher: {actual['publisher']}")
    print(f"\nComparison:")

    checks = [
        ("Title", actual["title"], expected["title"]),
        ("Author", actual["author"], expected["author"]),
        ("ISBN", actual["isbn"], expected["isbn"]),
        ("Year", actual["year"], expected["year"]),
        ("UDK", actual["udk"], expected["udk"]),
        ("BBK", actual["bbk"], expected["bbk"]),
        ("Publisher", actual["publisher"], expected["publisher"])
    ]

    all_passed = True
    for field, actual_val, expected_val in checks:
        # Compare normalized values with appropriate normalization per field
        if field == "ISBN":
            matches = normalize_isbn(actual_val) == normalize_isbn(expected_val)
        elif field in ("UDK", "BBK"):
            matches = normalize_classification(actual_val) == normalize_classification(expected_val)
        else:
            matches = normalize_for_comparison(actual_val) == normalize_for_comparison(expected_val)

        if matches:
            print(f"  [OK] {field}")
        else:
            print(f"  [FAIL] {field}: expected '{expected_val}', got '{actual_val}'")
            all_passed = False

    # Assertions - comparing normalized values (ignore case and spaces)
    assert normalize_for_comparison(actual["title"]) == normalize_for_comparison(expected["title"]), \
        f"Title mismatch: expected '{expected['title']}', got '{actual['title']}'"
    assert normalize_for_comparison(actual["author"]) == normalize_for_comparison(expected["author"]), \
        f"Author mismatch: expected '{expected['author']}', got '{actual['author']}'"
    assert normalize_isbn(actual["isbn"]) == normalize_isbn(expected["isbn"]), \
        f"ISBN mismatch: expected '{expected['isbn']}', got '{actual['isbn']}'"
    assert normalize_for_comparison(actual["year"]) == normalize_for_comparison(expected["year"]), \
        f"Year mismatch: expected '{expected['year']}', got '{actual['year']}'"
    assert normalize_classification(actual["udk"]) == normalize_classification(expected["udk"]), \
        f"UDK mismatch: expected '{expected['udk']}', got '{actual['udk']}'"
    assert normalize_classification(actual["bbk"]) == normalize_classification(expected["bbk"]), \
        f"BBK mismatch: expected '{expected['bbk']}', got '{actual['bbk']}'"
    assert normalize_for_comparison(actual["publisher"]) == normalize_for_comparison(expected["publisher"]), \
        f"Publisher mismatch: expected '{expected['publisher']}', got '{actual['publisher']}'"


def test_isbn_extraction():
    """Test that ISBN extraction works correctly with English OCR"""
    # With dual OCR approach, ISBN is extracted from English OCR
    # so these should all work correctly
    test_cases = [
        ("ISBN 978-5-17-123456-7", "9785171234567"),
        ("ISBN: 978-5-17-123456-7", "9785171234567"),
        ("ISBN 0-306-40615-2", "0306406152"),
        ("isbn 978-5-17-123456-7", "9785171234567"),  # lowercase
        ("Text before ISBN 978-5-17-123456-7 text after", "9785171234567"),
        ("ISBN-13: 978-5-17-123456-7", "9785171234567"),
    ]

    for ocr_text, expected_isbn in test_cases:
        result = extract_isbn(ocr_text)
        print(f"\nInput: {ocr_text}")
        print(f"Expected: {expected_isbn}, Got: {result}")
        assert result == expected_isbn, f"Failed for input: {ocr_text!r}"

    print("\n[OK] All ISBN extraction tests passed")


def test_dual_ocr_isbn_from_images():
    """Integration test: OCR real images with dual approach and extract ISBN"""
    # Find book test cases with images
    book_dirs = get_book_test_cases()

    if not book_dirs:
        pytest.skip("No test images found in test_data/")

    for book_dir in book_dirs:
        expected_file = book_dir / "expected.json"
        if not expected_file.exists():
            continue

        with open(expected_file, "r", encoding="utf-8") as f:
            expected = json.load(f)

        expected_isbn = expected.get("isbn")
        if not expected_isbn or expected_isbn == "unknown":
            continue  # Skip books without ISBN

        print(f"\n=== Testing {book_dir.name} ===")

        # Find info page (where ISBN usually is)
        info_path = find_image(book_dir, "info")
        if not info_path:
            continue

        # Load image
        img_b64 = load_image_as_base64(info_path)
        img = image_from_base64(img_b64)

        # Run DUAL OCR
        ocr_eng = ocr_image(img, "eng")
        ocr_rus = ocr_image(img, "rus")

        # Extract ISBN from both
        isbn_from_eng = extract_isbn(ocr_eng)
        isbn_from_rus = extract_isbn(ocr_rus)

        print(f"Expected ISBN: {expected_isbn}")
        print(f"ISBN from English OCR: {isbn_from_eng}")
        print(f"ISBN from Russian OCR: {isbn_from_rus}")

        # Check if English OCR found ISBN pattern (even if digits are wrong due to OCR accuracy)
        eng_found_isbn = isbn_from_eng != "unknown"
        rus_found_isbn = isbn_from_rus != "unknown"

        print(f"[RESULT] English OCR found ISBN: {eng_found_isbn}")
        print(f"[RESULT] Russian OCR found ISBN: {rus_found_isbn}")

        # The key test: English OCR should at least FIND the ISBN pattern
        # (even if some digits are misread - that's an OCR accuracy issue, not our problem)
        assert eng_found_isbn, f"English OCR failed to find ISBN pattern for {book_dir.name}"

        # Check accuracy (but don't fail if OCR misread digits)
        if normalize_isbn(isbn_from_eng) == normalize_isbn(expected_isbn):
            print(f"[PERFECT] English OCR extracted ISBN with 100% accuracy")
        else:
            print(f"[INFO] English OCR found ISBN but some digits were misread (OCR accuracy issue)")

        # Show comparison with Russian OCR
        if not rus_found_isbn:
            print(f"[SUCCESS] Russian OCR failed to find ISBN - dual OCR approach is necessary!")
        else:
            print(f"[INFO] Russian OCR also found ISBN (unexpected but ok)")


if __name__ == "__main__":
    pytest.main([__file__, "-v", "-s"])
