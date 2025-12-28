#!/usr/bin/env python3
"""Quick test script to verify OCR service is working in Docker"""
import requests
import base64
import json
from pathlib import Path

def test_ocr_extraction():
    # Use one of the test images
    test_image = Path("server/src/test/resources/bolotov_cover.jpg")

    if not test_image.exists():
        print(f"Error: Test image not found at {test_image}")
        return False

    # Read and encode image
    with open(test_image, "rb") as f:
        image_data = base64.b64encode(f.read()).decode()

    # Prepare request
    url = "http://localhost:5000/extract"
    payload = {
        "images": [image_data]
    }

    print(f"Testing OCR extraction with {test_image.name}...")
    print(f"Sending request to {url}...")

    try:
        response = requests.post(url, json=payload, timeout=60)

        print(f"Status code: {response.status_code}")

        if response.status_code == 200:
            result = response.json()
            print("\n=== SUCCESS ===")
            print(f"Title: {result.get('title', 'N/A')}")
            print(f"Author: {result.get('author', 'N/A')}")
            print(f"Publisher: {result.get('publisher', 'N/A')}")
            print(f"Year: {result.get('year', 'N/A')}")
            print(f"ISBN: {result.get('isbn', 'N/A')}")
            print(f"\nOCR Text Preview (first 200 chars):")
            ocr_text = result.get('ocr_rus', '') + result.get('ocr_eng', '')
            print(ocr_text[:200] + "..." if len(ocr_text) > 200 else ocr_text)
            return True
        else:
            print(f"\n=== FAILED ===")
            print(f"Response: {response.text}")
            return False

    except requests.exceptions.RequestException as e:
        print(f"\n=== ERROR ===")
        print(f"Failed to connect to OCR service: {e}")
        return False

if __name__ == "__main__":
    success = test_ocr_extraction()
    exit(0 if success else 1)
