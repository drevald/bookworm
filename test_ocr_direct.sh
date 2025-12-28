#!/bin/bash
# Test OCR service directly from server container

# Create a simple test with a real image
cd /app
apt-get update -qq && apt-get install -y -qq python3-pip curl > /dev/null 2>&1
pip3 install -q requests pillow 2>/dev/null

python3 << 'PYEOF'
import requests
import base64
import json

# Create a minimal 1-pixel test image base64
test_img = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

print("Testing OCR service from server container...")
print(f"Target: http://ocr-service:5000/extract")

try:
    response = requests.post(
        "http://ocr-service:5000/extract",
        json={"images": [test_img]},
        timeout=30
    )

    print(f"\nStatus: {response.status_code}")

    if response.status_code == 200:
        print("\n=== SUCCESS! OCR Service is Working ===")
        result = response.json()
        print(json.dumps(result, indent=2, ensure_ascii=False)[:500])
    else:
        print(f"\n=== ERROR ===")
        print(response.text)

except Exception as e:
    print(f"\n=== FAILED ===")
    print(str(e))
PYEOF
