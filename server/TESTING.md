# OCR Service Testing Guide

This document describes the test suite for the OCR service and how to run the tests.

## Test Files

### 1. `OCRServiceTest.java` (Unit Tests)
**Purpose:** Fast unit tests that don't require Tesseract binaries or language data files.

**Tests:**
- `extractText_ValidImage_ReturnsText()` - Verifies basic OCR functionality with mocked Tesseract
- `extractText_InvalidImage_ThrowsIOException()` - Verifies error handling for invalid image data
- `extractText_ConfiguresMultipleLanguages()` - Verifies all 6 languages are configured

**How to run:**
```bash
gradle test --tests OCRServiceTest
```

These tests use Mockito to mock the Tesseract instance, so they run quickly and don't require any external dependencies.

### 2. `OCRServiceIntegrationTest.java` (Integration Tests)
**Purpose:** Real-world integration tests using actual Tesseract OCR with real images.

**Tests:**
- `extractText_RussianBookPage_ExtractsCorrectInformation()` - Tests OCR on real Russian book page
- `extractText_RussianBookPage_PerformanceTest()` - Measures OCR performance

**Requirements:**
1. Tesseract language data files must be downloaded (see below)
2. `TESSDATA_PREFIX` environment variable must be set

**How to run:**
```bash
# Set environment variable
$env:TESSDATA_PREFIX="C:/Projects/bookworm/server/tessdata"

# Run tests
gradle test --tests OCRServiceIntegrationTest
```

**Note:** These tests are automatically skipped if `TESSDATA_PREFIX` is not set.

## Test Data

### Test Image: `src/test/resources/page.jpg`
This is a real book information page containing:
- **Title:** Послания из вымышленного царства (Russian)
- **Publisher:** СПб.: Азбука-классика
- **Year:** 2004
- **ISBN:** 5-352-01102-Х

The integration test verifies that OCR correctly extracts this information.

## Setup for Integration Tests

### Step 1: Download Language Data Files

Run the download script:
```batch
.\download-tessdata.bat
```

Or manually download from: https://github.com/tesseract-ocr/tessdata

Required files:
- `rus.traineddata` (Russian)
- `eng.traineddata` (English)
- `fra.traineddata` (French)
- `deu.traineddata` (German)
- `ukr.traineddata` (Ukrainian)
- `swe.traineddata` (Swedish)

Place them in: `tessdata/` directory

### Step 2: Set Environment Variable

**For current session:**
```powershell
$env:TESSDATA_PREFIX="C:/Projects/bookworm/server/tessdata"
```

**Permanently (add to `.env` file):**
```
TESSDATA_PREFIX=C:/Projects/bookworm/server/tessdata
```

### Step 3: Run Tests

**Run all tests:**
```bash
gradle test
```

**Run only unit tests (fast, no dependencies):**
```bash
gradle test --tests OCRServiceTest
```

**Run only integration tests (requires tessdata):**
```bash
gradle test --tests OCRServiceIntegrationTest
```

## Expected Results

### Unit Tests
All unit tests should pass immediately without any setup:
```
OCRServiceTest > extractText_ValidImage_ReturnsText() PASSED
OCRServiceTest > extractText_InvalidImage_ThrowsIOException() PASSED
OCRServiceTest > extractText_ConfiguresMultipleLanguages() PASSED
```

### Integration Tests
Integration tests will:
1. Load the real `page.jpg` image
2. Run Tesseract OCR on it
3. Verify extracted text contains:
   - Book title keywords (послания, вымышленного, царства)
   - Publisher (азбука, спб)
   - Year (2004)
   - ISBN (5-352-01102)
4. Print the full extracted text for manual verification
5. Measure and report OCR processing time

**Sample output:**
```
=== Extracted Text ===
Послания из вымышленного царства
Пер с др.-греч
СПб.: Азбука-классика, 2004
ISBN 5-352-01102-Х
======================
OCR processing time: 2341ms

OCRServiceIntegrationTest > extractText_RussianBookPage_ExtractsCorrectInformation() PASSED
OCRServiceIntegrationTest > extractText_RussianBookPage_PerformanceTest() PASSED
```

## Troubleshooting

### Integration tests are skipped
**Cause:** `TESSDATA_PREFIX` environment variable is not set.
**Solution:** Set the environment variable as described in Step 2 above.

### "Error opening data file" error
**Cause:** Language data files are missing or `TESSDATA_PREFIX` points to wrong directory.
**Solution:** 
1. Verify files exist: `ls tessdata/*.traineddata`
2. Check environment variable: `echo $env:TESSDATA_PREFIX`
3. Re-run download script: `.\download-tessdata.bat`

### OCR accuracy is poor
**Cause:** Image quality or wrong language data version.
**Solution:**
1. Try `tessdata_best` version for higher accuracy (slower)
2. Ensure image is clear and high resolution
3. Check that Russian language data (`rus.traineddata`) is present

### Tests timeout
**Cause:** OCR processing is taking too long.
**Solution:**
1. Use `tessdata_fast` version for faster processing
2. Increase timeout in test: modify `assertTrue(duration < 10000, ...)` 
3. Check system resources (CPU, memory)

## Continuous Integration

For CI/CD pipelines, you can:

1. **Skip integration tests** (default behavior if `TESSDATA_PREFIX` not set)
2. **Download tessdata in CI** by adding a step:
   ```yaml
   - name: Download Tesseract data
     run: |
       mkdir -p tessdata
       curl -L -o tessdata/rus.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/rus.traineddata
       curl -L -o tessdata/eng.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
       # ... download other languages
   ```
3. **Use Docker** with pre-installed Tesseract and language data
