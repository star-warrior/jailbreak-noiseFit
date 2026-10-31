#!/usr/bin/env python3
"""Generate replay3.txt from a built face container."""
from build_face import build_container, image_to_rgb565, UNCOMPRESSED_SIZE, NUM_BLOCKS, BLOCK_SIZE
from PIL import Image
import struct
import sys

def fmt(val, n):
    """Format integer as hex string with leading zeros."""
    return hex(val)[2:].zfill(n)

# Create test image
img = Image.new('RGB', (466, 466))
px = img.load()
for y in range(466):
    for x in range(466):
        if x < 20 or y < 20 or x >= 446 or y >= 456:
            px[x,y] = (0, 255, 0)  # green border
        else:
            v = ((x + y) % 60) * 4
            px[x,y] = (v, v, 255 - v)

img.save('test_face.png')
rgb565 = image_to_rgb565('test_face.png')
container = build_container(rgb565)

# Display header info
m = container[:4]
us = struct.unpack('<I', container[4:8])[0]
cs = struct.unpack('<I', container[12:16])[0]
bl = struct.unpack('<H', container[24:26])[0]
trailer = struct.unpack('<I', container[-4:])[0]

print(f'Magic: {m.hex()}')
print(f'Uncompressed size: {us}')
print(f'Compressed size: {cs}')
print(f'Blocks: {bl}')
print(f'File size: {len(container)}')
print(f'Trailer CRC: {trailer:#010x}')

# Split into 48-byte chunks for replay3.txt
chunk_size = 48
num_chunks = (len(container) + chunk_size - 1) // chunk_size
print(f'\nNumber of {chunk_size}-byte chunks: {num_chunks}')

# Generate replay lines
replay_lines = []
# Header lines: enable CCCDs
replay_lines.append('W 22 0100')
replay_lines.append('W 25 0100')
replay_lines.append('W 28 0100')
replay_lines.append('W 2B 0100')
replay_lines.append('W 2E 0100')

# Face data chunks
for i in range(num_chunks):
    start = i * chunk_size
    end = min(start + chunk_size, len(container))
    chunk = container[start:end]
    # Pad last chunk if needed
    if len(chunk) < chunk_size:
        chunk = chunk + b'\x00' * (chunk_size - len(chunk))
    hex_str = chunk.hex()
    replay_lines.append(f'W 2A {hex_str}')

# Trailer CRC chunk (already included in last container bytes, but ensure it's sent)
# Actually the trailer CRC is the last 4 bytes of the container, already included

# Add replay end marker
replay_lines.append('R 24 000001010000')  # wait for notification

# Write replay3.txt
with open('replay3.txt', 'w') as f:
    f.write('# replay3 - event sequence: W = write to watch, R = wait for notify\n')
    for line in replay_lines:
        f.write(line + '\n')

print(f'\nGenerated replay3.txt with {len(replay_lines)} lines')
print(f'First 5 replay lines:')
for line in replay_lines[:5]:
    print(f'  {line}')

# Also write a summary
with open('face_summary.txt', 'w') as f:
    f.write(f'Face file: test_face.png -> face.lz\n')
    f.write(f'Container size: {len(container)} bytes\n')
    f.write(f'Magic: {m.hex()}\n')
    f.write(f'Uncompressed size: {us}\n')
    f.write(f'Compressed size: {cs}\n')
    f.write(f'Blocks: {bl}\n')
    f.write(f'Chunks: {num_chunks}\n')