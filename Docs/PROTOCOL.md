# NoiseFit Halo watch-face container and BLE upload protocol

Everything below is measured from real, accepted transfers and cross-checked
against the vendor code in `noisefit_base.apk`. The framing is **byte-exact**: the
generator in `uploader/app/src/main/java/com/jay/wfup/ReplayBuilder.java`
reproduces all 302 data writes of the original capture identically, and the
container section was verified by reconstructing all 50 287 stream bytes from that
capture.

Status markers used throughout: **verified** (reproduced from hardware or vendor
code), **observed** (seen once, consistent), **assumed** (hypothesis, untested).

## 1. GATT layout

Custom service: handles `0x001F`-`0x002E`. UUID bytes on the wire are
`FB 34 9B 7F 80 00 00 80 00 10 00 00 00 6F 18 xx`, i.e. host order
`16186Fxx-0000-1000-8000-00807F9B34FB`.

| value handle | characteristic UUID                       | properties | CCCD |
| ------------ | ----------------------------------------- | ---------- | ---- |
| 0x0021       | `16186F01-...` (channel A)                | 0x14       | 0x0022 |
| 0x0024       | `16186F02-...` (channel B)                | 0x14       | 0x0025 |
| 0x0027       | `16186F03-...` (unused)                   | 0x14       | 0x0028 |
| 0x002A       | `16186F04-...` (data stream)              | 0x14       | 0x002B |
| 0x002D       | `16186F05-...` (side channel)             | 0x14       | 0x002E |

`0x14` = notify + write-without-response. Other services present: `0x1800`,
`0x1801`, `0x180A`, `0x1812` (HID), `0xFFD0`, and
`BFE4EF05-73D2-4FFD-3AB3-A5AE16C935B1`.

Connect: request MTU 247 (writes are 177 bytes), discover services, then write
`01 00` to every CCCD in the custom service (`0x0022`, `0x0025`, `0x0028`,
`0x002B`, `0x002E`).

## 2. Command session (handles 0x21 / 0x24)

Used before the file transfer, per command:

1. P->W `00 00 00 00 01 00` (session start marker)
2. W->P notify `00 00 01 01 00 00`
3. P->W `00 00 01 00 <len u16 LE> <cmd>`
4. W->P notify `00 00 01 00 00 00` (ack)
5. Repeat on the other handle to read the reply; the reply arrives as a notify on
   the opposite handle to the one used to send.

This session is **required** before a watch-face upload. Without it the watch never
enters transfer mode and rejects every `0x2A` write with `00 00 01 02 00 00`.

The face transfer command for face id `916` is (handle `0x24` unless noted):

```
W 24 00 00 00 00 01 00                      session start
R 24 00 00 01 01 00 00                      go ahead
W 24 01 00 08 53 3A 0D 22 0B 0A 03 39 31 36 10 32 18 32 20 01
R 24 00 00 01 00 00 00                      ack
R 21 00 00 00 00 01 00                      watch asks for credit (0x21)
W 21 00 00 01 01 00 00                      give credit
R 21 01 00 08 53 3A ...                     reply, body 53 3A 04 2A 02 08 00 = status 0
W 21 00 00 01 00 00 00                      ack the reply
```

Body `53 3A` means "transfer this face"; its protobuf field 1 is the face id as a
string (`"916"` here, the id of the reference face), fields 2 and 3 are `50`, field
4 is `1`. The reply body is `53 3A 04 2A 02 08 00` and the last byte `00` is the
status. Recovered from `new_face_snoop.log`; see `ReplayBuilder.PREAMBLE`.

The handshake is reproducible: the same 18-byte command, replayed with the same
face id, is accepted on every upload. The id does not have to match the reference
face for a new face to install.

## 3. Data transfer (handle 0x2A)

The byte stream is

```
prefix(22) || container || crc32(prefix || container)(4, LE)
```

The 22-byte prefix is `00 10` + 16 zero bytes + the container length as u32 LE. The
4-byte tail is a standard zlib CRC32 (polynomial `0xEDB88320`, init and final xor
`0xFFFFFFFF`) over the prefix concatenated with the container, stored little endian.
Source: `com.zhapp.ble.manager.ThemeManager.initUpload` (`CrcUtils.CRC32`), which
builds exactly this buffer before handing it to the piece splitter. Verified against
both real faces: 50 283 B -> `78 66 84 28`, 274 013 B -> `34 81 C5 C7`.

The piece count advertised in the chunk header is
`ceil((containerLength + 26) / 3846)`, i.e. 14 for the reference face and 72 for the
274 013 B face.

The stream is split into **windows**. Each window is announced by a 6-byte marker
and then carries up to 22 chunks of at most 177 bytes:

```
marker  : 00 00 00 00 <chunkCount u16 LE>
chunk 1 : <seq u16 LE> 0E 00 <winIdx u16 LE> <12-byte field> [10-byte meta] <data>
chunk N : <seq u16 LE> <data>
```

`seq` starts at 1 in every window and counts the chunks of that window.
`winIdx` starts at 1 and increases per window.

### Window 1 (special)

* 12-byte field is the constant `00 10 00 00 00 00 00 00 00 00 00 00`
* after the field comes a 10-byte meta block: six zero bytes then the
  **container length as u32 LE** (the 4-byte tail is not counted)
* chunk 1 therefore carries 149 payload bytes, chunks 2..22 carry 175 each
* window payload = 149 + 21*175 = **3824 bytes**

### Windows 2..N

* the 12-byte field is the **first 12 bytes of that window's own payload**; those
  bytes are consumed from the stream and then re-emitted in the header
* chunk 1 carries 159 payload bytes, chunks 2..22 carry 175 each
* window payload = 12 + 159 + 21*175 = **3846 bytes**

### Last window

Simply the remainder: fewer than 22 chunks, last chunk shorter. The capture's last
window had 2 chunks (`177 + 142 = 319` bytes of which 12 are the field and 311 are
stream bytes).

### Pacing / flow control

The watch is not a dumb pipe. Every window is flow-controlled, measured from the
accepted transfer in `new_face_snoop.log`:

```
marker            -> wait for 00 00 01 01 00 00   on 0x2A
chunk 1 .. chunk N (N = chunkCount)              ~14 ms apart
last chunk        -> wait for 00 00 01 00 00 00   on 0x2A
next marker
```

The go-ahead arrives ~80 ms after the marker, the window-done ack 180-480 ms after
the last chunk, so one 22-chunk window takes ~0.56 s. Sending the chunks without
waiting for those two notifications, or skipping the command session, makes the
watch answer `00 00 01 02 00 00` to every write and abort the transfer. A capture
of exactly that failure is in the same snoop file.

### Size limit

The container length in window 1 is a u32, so the framing itself has no 64 KB
limit. A 274 013 B vendor face transfers fine, and a 1 001 544 B vendor face also
transfers. The per-widget-sheet Halo blob is 1 078 368 B; the app keeps a sanity
gate at 2 000 000 B with an explicit override.

## 4. Container format (`afafaf02`)

```
0     4     magic "AF AF AF 02"
4     4     u32le  uncompressed size (754368 for the reference blob)
8     4     u32le  vendor checksum of the uncompressed blob
12    4     u32le  compressed payload size
16    4     u32le  vendor checksum of the compressed payload
20    4     u32le  reserved, 0
24    2     u16le  block count
26    2*n   u16le  per-block entries: 0x8000 | compressed block size
26+2n ...   payload: n LZO1X blocks of 4096 bytes, last block the remainder
```

The header is **dynamic**: `26 + 2 * blockCount`. For the reference blob that is
396 bytes with 185 blocks, and `396 + 49 887 = 50 283` = the real file size.

### Compression

Each 4096-byte block is compressed independently with **LZO1X** (LZO1X-1 style
opcodes). The vendor uses `lzo1x_1_compress` from LZO 2.x; this repo's `Lzo1x.java`
and `lzo1x.py` round-trip the vendor's own streams and produce smaller output than
the vendor encoder on the same blob.

### Vendor checksum (fields 8 and 16)

Recovered from `com.zhapp.ble.utils.lzo.LzoCrcUtils`:

```java
long crc = 0xFFFFFFFFL;
for (byte b : data) crc = table[((int) crc ^ b) & 0xFF] ^ ((crc >> 8) & 0xFFFFFF);
return littleEndianBytes(~crc);
```

`table` is a **vendor S-box of 256 32-bit values**, not a polynomial CRC table
(`table[0] == 0x60454269`, not 0), which is why no standard CRC parameter set
matches it. Reproduced verbatim in `LzoCrc32.java` / `lzo_crc32.py`.

Field 8 covers the uncompressed blob, field 16 covers the compressed payload.
`LzoUtils.mergeDialFile([B)[B` returns exactly the container; the 4-byte tail is
added later by the BLE layer.

Caveat: the cloud copy of the reference face carries `76329039` / `4B97A128`, while
the algorithm above gives `9EA8CB0B` / `57317C02`. The vendor server therefore does
not use this function. The app's own DIY uploads do, and those are accepted, so the
app form is the one that matters.

## 5. Uncompressed blob layout (face A, 754 368 B)

All pixel data is **RGB565 big endian** (high byte first). Each region is stored as
a plain row-major image with stride `width * 2`.

| range               | size    | content                                              |
| ------------------- | ------- | ---------------------------------------------------- |
| `0 .. 514`          | 514     | config header: widget table, layout, firmware fields  |
| `514 .. 192714`     | 192 200 | preview 310x310 (face-list thumbnail only)            |
| `192714 .. 320056`  | 127 342 | clock-bar background, 466x136 image + 590-byte tail   |
| `320056 .. 627616`  | 307 560 | artwork 466x330 (the lower part of the round screen)  |
| `627616 .. 754368`  | 126 752 | glyph sprite sheets (date, digits, colon, AM/PM)      |

This was proven on hardware, one region at a time:

* Replacing only the preview changes nothing on the watch - it is the face-list
  thumbnail.
* Painting the mid block with a grid lights the clock-bar background up and leaves
  the digits crisp. So the bar is a stored image, not a firmware rectangle.
* Replacing the artwork rows only affects the lower 330 screen rows; the clock keeps
  working.
* Replacing the atlas turns digits into solid cells; keeping it stock keeps the time
  live.

Screen composition, 1:1 with no scaling:

```
screen rows   0 .. 135   <- mid block, 466x136 image
screen rows 136 .. 465   <- artwork block, 466x330 image
screen (all rows)        <- sprite sheets blitted opaque over the top by firmware
```

330 + 136 = 466, so a full-bleed face splits a 466x466 image at row 136: rows
0..135 go to the mid block, rows 136..465 to the artwork.

### 5.1 Builder blob with per-widget sheets (1 078 368 B)

The stock blob keeps one shared digit sheet, so a photo behind the clock cannot
show through the cells. The app rebuilds the atlas with one sheet per widget and
paints the photo patch from that widget's screen rect into every cell (glyph
composited over it by max RGB channel as alpha). This makes the blob larger but
keeps it a normal blob: only the atlas offset and the six sheet offsets change.

| range                 | size      | content                                   |
| --------------------- | --------- | ----------------------------------------- |
| `0 .. 627616`         | 627 616   | header, preview, mid block, artwork       |
| `627616 .. 627648`    | 32        | atlas pad, copied unchanged               |
| `627648 .. 642048`    | 14 400    | date sheet, 4 cells of 60x30              |
| `642048 .. 750048`    | 108 000   | digit sheet #1, 10 cells of 60x90         |
| `750048 .. 858048`    | 108 000   | digit sheet #2                            |
| `858048 .. 966048`    | 108 000   | digit sheet #3                            |
| `966048 .. 1074048`   | 108 000   | digit sheet #4                            |
| `1074048 .. 1078368`  | 4 320     | colon sheet, 1 cell of 24x90              |

Total: `627616 + 32 + 14400 + 4*108000 + 4320 = 1 078 368` bytes. The six widget
records point at `627648`, `642048`, `750048`, `858048`, `966048`
(digits, in table order) and `1074048`. The container packs and round-trips at
this size; on-watch transfer of a blob this large is still being confirmed.


## 6. Config header and widget table

The first 514 bytes are firmware/face config. Two parts are decoded:

* `0x0062` (u16 BE) = `0x0180` = 384, the offset of the widget table.
* The widget table starts at `0x0180` and holds **13-byte records** until a zero
  record:

```
u16 x      screen x
u16 y      screen y
u16 w      screen width
u16 h      screen height
u8  kind   4 = date row, 10 = digit, 1 = colon
u32 sheet  byte offset of this widget's glyph sprite sheet in the blob, big endian
```

Face A (reference face, `10:37` style) decodes to:

| rect (x,y,w,h)  | kind | sheet offset | sheet bytes        |
| --------------- | ---- | ------------ | ------------------ |
| 204,23,60,30    | 4    | 627648       | 14 400 = 4 cells   |
| 86,69,60,90     | 10   | 642048       | 108 000 = 10 cells |
| 154,69,60,90    | 10   | 642048       | shared             |
| 222,69,24,90    | 1    | 750048       | 4 320 = 1 cell     |
| 254,69,60,90    | 10   | 642048       | shared             |
| 322,69,60,90    | 10   | 642048       | shared             |

The arithmetic is exact: `10 * 60 * 90 * 2 = 108 000`, `4 * 60 * 30 * 2 = 14 400`,
`1 * 24 * 90 * 2 = 4 320`, and the three ranges tile the atlas block with no gaps.

Face B (a vendor face with cyan hours, white minutes) has the same six records with
different geometry (y=64, h=100, w=70) and **two** sheet offsets for the digits:
hours and minutes use separate sheets, which is why they can be different colours
and sizes. Colour is baked into the sheet pixels, not stored in the record.

The vendor APK itself documents the intended schema:
`assets/dial/style/OSW-802N/template1..4.json` uses fields `widget: "textbox"`,
`data: "time_hour24" | "minute" | "dot_second" | "date_weekday_day"`, `view: [x,y,w,h]`,
`font_color`, `font_bkcolor`, `font_type`, `font_size`, `align`, `alpha`, `zorder`.
So positions, colours, sizes and backgrounds are config, not firmware.

Open issue resolved: the sprite cells are stored with their own background and are
blitted **opaque**; the firmware does not key out a colour or use a config
background. A probe that painted the stock cells red/green/blue showed those
colours on the watch, and replacing the atlas with solid cells turned the digits
into solid boxes. The fix is the per-widget atlas described in section 5.1: paint
the photo patch for each widget's screen rect into every cell of that widget's own
sheet, then composite the stock glyph over it.

The `font_bkcolor` field in the vendor template is therefore used by the vendor
compiler to bake the background into the sheet, not read by the firmware at
runtime.

## 7. Building a face offline

1. pick a photo and open the square crop dialog (drag to move, pinch to zoom);
   the square is the whole round screen, so nothing is squashed
2. crop to 466x466
3. optionally scale to 310x310 for the preview (generate it from the composed
   screen so the thumbnail matches the watch)
4. convert the screen to RGB565 **big endian**
5. splice: rows 0..135 into the mid block, rows 136..465 into the artwork
6. rebuild the glyph atlas: one sheet per widget, each cell = photo patch at that
   widget's rect with the stock glyph composited over it (section 5.1)
7. split the blob into 4096-byte blocks, LZO1X each, build the dynamic header
8. compute both vendor checksums
9. self-check by decompressing the container and comparing every byte
10. frame with `ReplayBuilder` (prefix, CRC32 tail, windows) and write over BLE

All of this runs in the Android app (`CropView`, `FacePipeline`, `FaceBuilder`,
`Lzo1x`, `LzoCrc32`, `ReplayBuilder`). No server, no Python, no root.

## 8. Open questions

* SOLVED: the 4-byte tail is `CRC32(prefix22 || container)`, see section 3.
* SOLVED: the container header is dynamic (`26 + 2n`), not fixed 396 bytes.
* SOLVED: pixels are RGB565 **big endian**.
* SOLVED: the clock bar is a stored image in the mid block; the atlas holds sprites.
* SOLVED: the black boxes around time/date/AM-PM cells. Cells are blitted opaque;
  the builder now gives every widget its own sheet with the photo painted in.
* OPEN: on-watch confirmation of the 1 078 368-byte per-widget-sheet blob.
* OPEN: the widget record's remaining fields; only x/y/w/h/kind/sheet are mapped.
* OPEN: the container's type field (`0214` vs `0203`) and whether it gates layout.
* OPEN: whether fields 8/16 are validated by the watch. The cloud face's values do
  not match the vendor function, which suggests not, but DIY uploads use the
  function and are accepted, so it is implemented properly.
* OPEN: even larger blobs with additional sheets - the length field is u32 and the
  1 001 544-byte vendor blob transfers fine, but untested beyond the new size.
