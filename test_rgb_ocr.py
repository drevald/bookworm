#!/usr/bin/env python3
"""
Test RGB channel OCR on Harry Potter cover
"""

import base64
import requests
import json

# Read the cover image
with open(r'C:\Projects\bookworm\llm-service\test_data\potter\cover.jpg', 'rb') as f:
    cover_data = base64.b64encode(f.read()).decode('utf-8')

# Prepare request
payload = {
    "cover_image": cover_data,
    "language": "eng"
}

# Send to OCR service
print("Sending cover to OCR service...")
response = requests.post('http://127.0.0.1:5000/extract', json=payload)

print(f"Status: {response.status_code}")
print(f"\nResponse:")
result = response.json()
print(json.dumps(result, indent=2))
