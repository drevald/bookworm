# Tesseract Language Data Setup

This directory contains the trained data files (`.traineddata`) required by Tesseract OCR to recognize text in different languages.

## Required Languages

The application is configured to support the following languages:
- **Russian** (rus)
- **English** (eng)
- **French** (fra)
- **German** (deu)
- **Ukrainian** (ukr)
- **Swedish** (swe)

## Quick Setup

### Option 1: Automatic Download (Recommended)

Run the provided batch script from the project root:

```batch
.\download-tessdata.bat
```

This will automatically download all required language files to this directory.

### Option 2: Manual Download

Download the `.traineddata` files from the official Tesseract repository:

**Repository:** https://github.com/tesseract-ocr/tessdata

**Direct download links:**
- [rus.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/rus.traineddata)
- [eng.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata)
- [fra.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/fra.traineddata)
- [deu.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/deu.traineddata)
- [ukr.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/ukr.traineddata)
- [swe.traineddata](https://github.com/tesseract-ocr/tessdata/raw/main/swe.traineddata)

Place all downloaded files in this `tessdata` directory.

## Verification

After downloading, this directory should contain:
```
tessdata/
├── rus.traineddata
├── eng.traineddata
├── fra.traineddata
├── deu.traineddata
├── ukr.traineddata
└── swe.traineddata
```

## Configuration

The application is configured to look for tessdata in this directory via the `TESSDATA_PREFIX` environment variable in your `.env` file:

```
TESSDATA_PREFIX=C:/Projects/bookworm/bookworm/tessdata
```

## Alternative Versions

There are three versions of trained data available:

1. **tessdata** (Standard) - Good balance of speed and accuracy
2. **tessdata_best** - Highest accuracy, slower processing
3. **tessdata_fast** - Fastest processing, lower accuracy

The download script uses the standard version. If you need different versions, replace `tessdata` with `tessdata_best` or `tessdata_fast` in the URLs.

## Troubleshooting

If you see errors like "Error opening data file", verify:
1. All required `.traineddata` files are in this directory
2. The `TESSDATA_PREFIX` environment variable is correctly set in `.env`
3. The application has been restarted after adding the files
