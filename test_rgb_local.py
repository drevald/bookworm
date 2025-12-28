#!/usr/bin/env python3
"""
Test RGB and CMYK channel OCR with inversions locally (no Docker needed)
"""

from PIL import Image, ImageOps
import pytesseract

def ocr_channel(img, name):
    """Run OCR on a channel and its inverse"""
    normal = pytesseract.image_to_string(img, lang='eng').strip()
    inverted = pytesseract.image_to_string(ImageOps.invert(img), lang='eng').strip()

    print(f"=== {name} ===")
    print(f"Normal:   {len(normal):3d} chars | {repr(normal[:60])}")
    print(f"Inverted: {len(inverted):3d} chars | {repr(inverted[:60])}")
    print()

    return [(normal, name), (inverted, f"{name}-inv")]

# Read the cover image
image = Image.open(r'C:\Projects\bookworm\llm-service\test_data\potter\cover.jpg')

# Convert to RGB if needed
if image.mode != 'RGB':
    image = image.convert('RGB')

print("Running OCR on RGB and CMYK channels (normal and inverted)...\n")

results = []

# Original
results.extend(ocr_channel(image.convert('L'), "ORIGINAL"))

# RGB channels
r, g, b = image.split()
results.extend(ocr_channel(r, "R"))
results.extend(ocr_channel(g, "G"))
results.extend(ocr_channel(b, "B"))

# CMYK channels
cmyk = image.convert('CMYK')
c, m, y, k = cmyk.split()
results.extend(ocr_channel(c, "C (Cyan)"))
results.extend(ocr_channel(m, "M (Magenta)"))
results.extend(ocr_channel(y, "Y (Yellow)"))
results.extend(ocr_channel(k, "K (Black)"))

# Find best
best_result, best_channel = max(results, key=lambda x: len(x[0]))
print(f"\n{'='*60}")
print(f"BEST RESULT: {best_channel}")
print(f"Length: {len(best_result)} chars")
print(f"Text: {repr(best_result)}")
print(f"{'='*60}")
