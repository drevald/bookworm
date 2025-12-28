#!/usr/bin/env python3
"""Test OCR service from inside Docker network"""
import requests
import json

# Test health endpoint
try:
    response = requests.get("http://localhost:5000/health", timeout=5)
    print(f"Health check: {response.status_code}")
    print(f"Response: {response.text}\n")
except Exception as e:
    print(f"Health check failed: {e}\n")

# Test with minimal data
try:
    # Simple test with dummy data
    import base64
    # Create a small test image (1x1 pixel white PNG)
    test_data = base64.b64encode(b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde').decode()

    response = requests.post(
        "http://localhost:5000/extract",
        json={"images": [test_data]},
        timeout=30
    )

    print(f"Extract test: {response.status_code}")
    if response.status_code == 200:
        print("SUCCESS - OCR service is working!")
        print(json.dumps(response.json(), indent=2))
    else:
        print(f"Error: {response.text}")

except Exception as e:
    print(f"Extract test failed: {e}")
