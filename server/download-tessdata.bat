@echo off
REM Download Tesseract trained data files for multiple languages

echo Downloading Tesseract language data files...
echo.

set TESSDATA_URL=https://github.com/tesseract-ocr/tessdata/raw/main
set TESSDATA_DIR=tessdata

if not exist %TESSDATA_DIR% mkdir %TESSDATA_DIR%

echo Downloading Russian (rus.traineddata)...
curl -L -o %TESSDATA_DIR%/rus.traineddata %TESSDATA_URL%/rus.traineddata

echo Downloading English (eng.traineddata)...
curl -L -o %TESSDATA_DIR%/eng.traineddata %TESSDATA_URL%/eng.traineddata

echo Downloading French (fra.traineddata)...
curl -L -o %TESSDATA_DIR%/fra.traineddata %TESSDATA_URL%/fra.traineddata

echo Downloading German (deu.traineddata)...
curl -L -o %TESSDATA_DIR%/deu.traineddata %TESSDATA_URL%/deu.traineddata

echo Downloading Ukrainian (ukr.traineddata)...
curl -L -o %TESSDATA_DIR%/ukr.traineddata %TESSDATA_URL%/ukr.traineddata

echo Downloading Swedish (swe.traineddata)...
curl -L -o %TESSDATA_DIR%/swe.traineddata %TESSDATA_URL%/swe.traineddata

echo.
echo Download complete!
echo Language files are in: %TESSDATA_DIR%
echo.
pause
