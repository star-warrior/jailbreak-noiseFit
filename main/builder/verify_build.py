#!/usr/bin/env python3
"""Verify the build_face.py output."""
from build_face import build_container, image_to_rgb565, UNCOMPRESSED_SIZE, NUM_BLOCKS, BLOCK_SIZE
from PIL import Image
import struct

# Create test image
img = Image.new('RGB', (466, 466))
px = img.load()
for y in range(466):
    for x in range(466):
        if x < 20 or y < 20 or x >= 446 or y >= 456:
            px[x,y] = (0, 255, 0)
        else:
            v = ((x + y) % 60) * 4
            px[x,y] = (v, v, 255 - v)

img.save('verify_test.png')
rgb565 = image_to_rgb565('verify_test.png')
container = build_container(rgb565)

# Verify all key fields
m = container[:4]
us = struct.unpack('<I', container[4:8])[0]
cs = struct.unpack('<I', container[12:16])[0]
bl = struct.unpack('<H', container[24:26])[0]
trailer = struct.unpack('<I', container[-4:])[0]

print('=== BUILD FACE PYTHON VERIFICATION ===')
print(f'Magic: {m.hex()} (expected afafaf02)')
print(f'Uncompressed size: {us} (expected 434312)')
print(f'Compressed size: {cs}')
print(f'Blocks: {bl} (expected 107)')
print(f'File size: {len(container)}')
print(f'Trailer CRC: {trailer:#010x}')
print()

# Check first chunk
first_bytes = container[:48]
print(f'First chunk magic: {first_bytes[:4].hex()}')
print(f'  Magic correct: {first_bytes[:4] == b"\\xaf\\xaf\\xaf\\x02"}')
print(f'First chunk unc size: {struct.unpack("<I", first_bytes[4:8])[0]}')
print(f'First chunk comp size: {struct.unpack("<I", first_bytes[12:16])[0]}')
print(f'First chunk blocks: {struct.unpack("<H", first_bytes[24:26])[0]}')
print()
print('=== USAGE ===')
print('python build_face.py <input_image> <output.lz>')
print('Then upload with: .\\upload.ps1')