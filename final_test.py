#!/usr/bin/env python3
"""Final OCR test with real image"""
import requests
import base64
import json
from pathlib import Path

# Read a test image
img_path = Path("server/src/test/resources/bolotov_cover.jpg")
if not img_path.exists():
    print(f"Error: {img_path} not found")
    exit(1)

with open(img_path, "rb") as f:
    img_b64 = base64.b64encode(f.read()).decode()

print(f"Testing OCR service with {img_path.name}")
print(f"Image size: {len(img_b64)} chars base64\n")

# Test via the OCR service container name (Docker network)
url = "http://ocr-service:5000/extract"

try:
    response = requests.post(
        url,
        json={"images": [img_b64]},
        timeout=90
    )

    print(f"Status: {response.status_code}")

    if response.status_code == 200:
        result = response.json()
        print("\n" + "="*50)
        print("OCR EXTRACTION SUCCESSFUL")
        print("="*50)
        print(f"Title:     {result.get('title', 'N/A')}")
        print(f"Author:    {result.get('author', 'N/A')}")
        print(f"Authors:   {result.get('authors', 'N/A')}")
        print(f"Publisher: {result.get('publisher', 'N/A')}")
        print(f"Year:      {result.get('year', 'N/A')}")
        print(f"ISBN:      {result.get('isbn', 'N/A')}")
        print(f"UDK:       {result.get('udk', 'N/A')}")
        print(f"BBK:       {result.get('bbk', 'N/A')}")
        print("="*50)
        exit(0)
    else:
        print(f"\nERROR Response:")
        print(response.text)
        exit(1)

except Exception as e:
    print(f"\nFAILED: {e}")
    import traceback
    traceback.print_exc()
    exit(1)
