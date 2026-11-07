package com.jay.wfup;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds the NoiseFit Halo watchface container (afafaf02) from the known-good
 * uncompressed blob.
 *
 * Layout of the 754368 byte uncompressed blob (measured, not guessed):
 *   0      .. 514      header / config
 *   514    .. 192714   preview 310x310 RGB565 big endian          (192200 bytes)
 *   192714 .. 320056   zero padding (one 62 byte sprite)
 *   320056 .. 754368   background 466x466 RGB565 big endian       (434312 bytes)
 *
 * Inside the background block:
 *   rows   0 .. 329    artwork, displayed as the lower part of the round screen
 *   rows 330 .. 465    clock strip, displayed above the artwork (1:1, no scaling)
 * Replacing the strip rows is what turns the time into solid boxes, so they are
 * treated as a preserved band (see {@link #BG_ART_ROWS}).
 *
 * Only the two image regions are ever modified; every other byte is carried
 * over from the reference blob untouched.
 *
 * Container:
 *   0   magic  "AF AF AF 02"
 *   4   u32le  uncompressed size (754368)
 *   8   u32le  vendor checksum of the uncompressed blob      (LzoCrc32)
 *   12  u32le  compressed payload size
 *   16  u32le  vendor checksum of the compressed payload     (LzoCrc32)
 *   20  u32le  reserved = 0
 *   24  u16le  block count (185)
 *   26  u16le  block count entries: 0x8000 | compressed block size
 *   396 payload, blocks of 4096 bytes, last block is the remainder
 *
 * This layout and the two checksums were recovered from the vendor method
 * com.zhapp.ble.utils.lzo.LzoUtils.mergeDialFile([B)[B in classes10.dex. Note
 * that the cloud copy of the reference face carries different checksum values,
 * i.e. the vendor server does not use that method, while the app's own DIY
 * uploads do and those are accepted by the watch.
 *
 * The 4 byte trailer 78 66 84 28 for the reference face is appended by the phone
 * at transfer time and is NOT part of the container. It is per file, not constant:
 * it is the zlib CRC32 of the 22 byte upload prefix plus the container, stored
 * little endian. See {@link ReplayBuilder#trailer(byte[])}.
 */
public final class FaceBuilder {

    private FaceBuilder() {}

    public static final int BLOB_SIZE = 754368;
    public static final int BLOCK = 4096;
    public static final int BLOCK_COUNT = (BLOB_SIZE + BLOCK - 1) / BLOCK;

    public static final int OFF_PREVIEW = 514;
    public static final int PREVIEW_W = 310, PREVIEW_H = 310;
    public static final int LEN_PREVIEW = PREVIEW_W * PREVIEW_H * 2;

    public static final int BG_W = 466, BG_H = 466;
    public static final int LEN_BG = BG_W * BG_H * 2;
    /**
     * Rows of the background block that are actual artwork. Rows 330..465 are the clock
     * strip: they hold the glyph sprites the firmware blits for the time, so overwriting
     * them turns the digits into solid cells.
     */
    public static final int BG_ART_ROWS = 330;

    /** Strip height in screen rows, i.e. the height of the glyph-atlas area. */
    public static final int STRIP_ROWS = BG_H - BG_ART_ROWS;

    /** Block between the preview and the artwork: 466x136 pixels plus 590 bytes. Nearly
     *  all zeros in the reference blob, i.e. black - this is where the clock strip's own
     *  background is expected to live. */
    public static final int OFF_MID = 192714;
    public static final int MID_SIZE = 127342;
    /** The presumed image part of the mid block: 466 * 136 * 2 bytes. */
    public static final int LEN_MID = BG_W * STRIP_ROWS * 2;

    public static final int OFF_BG = 320056;

    /** Glyph sprite atlas: 32 byte pad then one sheet per widget. */
    public static final int OFF_ATLAS = 627616;
    public static final int ATLAS_PAD = 32;
    public static final int WIDGET_TABLE = 0x180, WIDGET_REC = 13, WIDGET_END = 514;

    /** Header for the 185-block reference blob; real containers use 26 + 2 * blockCount. */
    public static final int HEADER_SIZE = 396;
    /** Largest container proven on the wire. Not a format limit: the length field is u32. */
    public static final int MAX_FILE = 65535;

    /** One 13-byte widget record from the config header. */
    public static final class Widget {
        public int x, y, width, height, kind, sheet, recordOff, cells;
    }

    public static List<Widget> widgets(byte[] blob) {
        List<Widget> out = new ArrayList<>();
        for (int off = WIDGET_TABLE; off + WIDGET_REC <= WIDGET_END; off += WIDGET_REC) {
            Widget w = new Widget();
            w.x = u16(blob, off);
            w.y = u16(blob, off + 2);
            w.width = u16(blob, off + 4);
            w.height = u16(blob, off + 6);
            w.kind = blob[off + 8] & 0xff;
            // Widget records are big endian, unlike the container header.
            w.sheet = getU32be(blob, off + 9);
            w.recordOff = off;
            if (w.x == 0 && w.y == 0 && w.width == 0 && w.height == 0
                    && w.kind == 0 && w.sheet == 0) break;
            out.add(w);
        }
        return out;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    /** digit sheet = 10 cells, date sheet = 4 words, colon = 1 cell. */
    static int sheetCells(int kind) {
        if (kind == 10) return 10;
        if (kind == 4) return 4;
        if (kind == 1) return 1;
        return 0;
    }

    /**
     * Rebuilds the glyph atlas so every sheet cell carries the photo patch from its
     * widget's screen rect under the stock glyph. The firmware blits the selected cell
     * at that rect, so the date's four word cells all need the same patch.
     *
     * @param blob   blob with the photo already spliced into the image regions
     * @param screen composed 466x466 ARGB screen
     * @return a new blob with rebuilt sheets and updated widget offsets
     */
    public static byte[] repaintWidgets(byte[] blob, int[] screen) {
        if (screen == null || screen.length != BG_W * BG_H) {
            throw new IllegalArgumentException("screen is not " + BG_W + "x" + BG_H);
        }
        List<Widget> ws = widgets(blob);
        int total = OFF_ATLAS + ATLAS_PAD;
        for (Widget w : ws) {
            w.cells = sheetCells(w.kind);
            if (w.cells <= 0) {
                throw new IllegalArgumentException("widget kind " + w.kind
                        + " at record " + w.recordOff + " has no known sheet layout");
            }
            total += w.cells * w.width * w.height * 2;
        }

        byte[] out = new byte[total];
        System.arraycopy(blob, 0, out, 0, OFF_ATLAS + ATLAS_PAD);
        int cursor = OFF_ATLAS + ATLAS_PAD;
        for (Widget w : ws) {
            writeSheet(out, cursor, blob, w, screen);
            putU32be(out, w.recordOff + 9, cursor);
            cursor += w.cells * w.width * w.height * 2;
        }
        return out;
    }

    /** Photo patch at the widget rect, with the stock glyph blended over it. */
    private static void writeSheet(byte[] out, int dst, byte[] stock, Widget w, int[] screen) {
        int cellPx = w.width * w.height;
        for (int c = 0; c < w.cells; c++) {
            int src = w.sheet + c * cellPx * 2;
            for (int yy = 0; yy < w.height; yy++) {
                int sy = w.y + yy;
                for (int xx = 0; xx < w.width; xx++) {
                    int sx = w.x + xx;
                    int photo = (sy >= 0 && sy < BG_H && sx >= 0 && sx < BG_W)
                            ? screen[sy * BG_W + sx] : 0xff000000;

                    int v = ((stock[src] & 0xff) << 8) | (stock[src + 1] & 0xff);
                    int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
                    r = (r << 3) | (r >> 2);
                    g = (g << 2) | (g >> 4);
                    b = (b << 3) | (b >> 2);
                    int a = Math.max(r, Math.max(g, b));   // black background -> 0, cyan/white -> 255

                    int pr = (photo >> 16) & 0xff, pg = (photo >> 8) & 0xff, pb = photo & 0xff;
                    if (a > 0) {
                        pr = (r * a + pr * (255 - a)) / 255;
                        pg = (g * a + pg * (255 - a)) / 255;
                        pb = (b * a + pb * (255 - a)) / 255;
                    }
                    int o = ((pr >> 3) << 11) | ((pg >> 2) << 5) | (pb >> 3);
                    out[dst] = (byte) (o >> 8);
                    out[dst + 1] = (byte) o;
                    dst += 2;
                    src += 2;
                }
            }
        }
    }

    public static final class Result {
        public byte[] container;
        public int compressedSize;
        public int[] blockSizes;
        public String error;
        public boolean ok() { return error == null; }
    }

    /** RGB565 bytes (big endian) -> ARGB int[]. */
    public static int[] fromRgb565(byte[] b, int w, int h) {
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            int v = ((b[i * 2] & 0xff) << 8) | (b[i * 2 + 1] & 0xff);
            int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, bl = v & 0x1f;
            r = (r << 3) | (r >> 2);
            g = (g << 2) | (g >> 4);
            bl = (bl << 3) | (bl >> 2);
            out[i] = 0xff000000 | (r << 16) | (g << 8) | bl;
        }
        return out;
    }

    /** Perceived brightness 0..255 of an RGB565 pixel. */
    public static int luma565(byte hi, byte lo) {
        int v = ((hi & 0xff) << 8) | (lo & 0xff);
        int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
        // 5/6/5 -> approximate 0..255 before weighting
        return ((r * 255 / 31) * 77 + (g * 255 / 63) * 150 + (b * 255 / 31) * 29) >> 8;
    }

    /** ARGB int[] -> RGB565 bytes, big endian (high byte first), as the reference blob stores them. */
    public static byte[] toRgb565(int[] argb, int w, int h) {
        byte[] out = new byte[w * h * 2];
        for (int i = 0; i < w * h; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xff;
            int g = (c >> 8) & 0xff;
            int b = c & 0xff;
            int v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
            out[i * 2] = (byte) ((v >> 8) & 0xff);
            out[i * 2 + 1] = (byte) (v & 0xff);
        }
        return out;
    }

    /**
     * @param base    reference 754368 byte blob
     * @param bg565   466*466*2 background pixels, or null to keep the original
     * @param prev565 310*310*2 preview pixels, or null to keep the original
     */
    public static Result build(byte[] base, byte[] bg565, byte[] prev565) {
        return build(base, bg565, 0, BG_H, prev565);
    }

    /**
     * Same, but only the first {@code bgRows} rows of the background are written; the
     * rest of the block is left as it is in {@code base}. The 466x466 block is not a
     * plain bitmap: everything from {@link #BG_ART_ROWS} down carries the time glyph
     * data, and overwriting it turns the clock into solid boxes.
     */
    public static Result build(byte[] base, byte[] bg565, int bgRows, byte[] prev565) {
        return build(base, bg565, 0, bgRows, prev565);
    }

    /**
     * Writes only background rows {@code fromRow} (inclusive) to {@code toRow}
     * (exclusive), leaving the rest of the 466x466 block exactly as it is in
     * {@code base}. Used by the strip probe, which paints the clock band and must not
     * touch the artwork above it.
     */
    public static Result build(byte[] base, byte[] bg565, int fromRow, int toRow, byte[] prev565) {
        Result r = new Result();
        if (base == null || base.length != BLOB_SIZE) {
            r.error = "base blob is " + (base == null ? "null" : base.length + "B") + ", expected " + BLOB_SIZE;
            return r;
        }
        if (bg565 != null && bg565.length != LEN_BG) {
            r.error = "background is " + bg565.length + "B, expected " + LEN_BG;
            return r;
        }
        if (prev565 != null && prev565.length != LEN_PREVIEW) {
            r.error = "preview is " + prev565.length + "B, expected " + LEN_PREVIEW;
            return r;
        }
        if (bg565 != null && (fromRow < 0 || toRow > BG_H || fromRow >= toRow)) {
            r.error = "background rows " + fromRow + ".." + toRow + " outside 0.." + BG_H;
            return r;
        }

        byte[] blob = base.clone();
        if (bg565 != null) {
            int off = fromRow * BG_W * 2;
            int len = (toRow - fromRow) * BG_W * 2;
            System.arraycopy(bg565, off, blob, OFF_BG + off, len);
        }
        if (prev565 != null) System.arraycopy(prev565, 0, blob, OFF_PREVIEW, LEN_PREVIEW);

        return pack(base, blob);
    }

    /**
     * Replaces the mid block's image part (the clock strip background) and keeps every
     * other byte of the blob, including the glyph atlas at the end of the background
     * block. Used by the strip-background probe and, if that confirms the layout, by the
     * full-bleed photo build.
     */
    public static Result buildMid(byte[] base, byte[] mid565) {
        Result r = new Result();
        if (base == null || base.length != BLOB_SIZE) {
            r.error = "base blob is " + (base == null ? "null" : base.length + "B") + ", expected " + BLOB_SIZE;
            return r;
        }
        if (mid565 == null || mid565.length != LEN_MID) {
            r.error = "mid image is " + (mid565 == null ? "null" : mid565.length + "B") + ", expected " + LEN_MID;
            return r;
        }
        byte[] blob = base.clone();
        System.arraycopy(mid565, 0, blob, OFF_MID, LEN_MID);
        return pack(base, blob);
    }

    /** Compresses blob and wraps it in the container. Header size is 26 + 2 * blockCount. */
    public static Result pack(byte[] base, byte[] blob) {
        Result r = new Result();
        if (blob == null || blob.length == 0) {
            r.error = "empty blob";
            return r;
        }
        int blockCount = (blob.length + BLOCK - 1) / BLOCK;
        r.blockSizes = new int[blockCount];

        byte[] out = new byte[BLOCK + BLOCK / 8 + 128];
        int payloadCap = blob.length + blob.length / 8 + blockCount * 8 + 1024;
        byte[] payload = new byte[payloadCap];
        int payloadLen = 0;

        for (int b = 0; b < blockCount; b++) {
            int off = b * BLOCK;
            int len = Math.min(BLOCK, blob.length - off);
            int n;
            try {
                n = Lzo1x.compress(blob, off, len, out, 0);
            } catch (RuntimeException e) {
                r.error = "compress failed on block " + b + ": " + e.getMessage();
                return r;
            }
            if (n >= 0x8000) {
                r.error = "block " + b + " compressed to " + n + "B, exceeds 0x7fff table field";
                return r;
            }
            r.blockSizes[b] = n;
            System.arraycopy(out, 0, payload, payloadLen, n);
            payloadLen += n;
        }

        int header = 26 + 2 * blockCount;
        byte[] c = new byte[header + payloadLen];
        c[0] = (byte) 0xAF; c[1] = (byte) 0xAF; c[2] = (byte) 0xAF; c[3] = 0x02;
        putU32(c, 4, blob.length);
        // Field 8 and field 16 are the vendor checksum (LzoCrc32) of the uncompressed
        // blob and of the compressed payload respectively, per LzoUtils.mergeDialFile.
        // Note: the cloud copy of the reference face carries different values, i.e. the
        // vendor server does not use this function. The app's own DIY uploads do, and
        // those are accepted by the watch, so this is the form the watch sees.
        putU32(c, 8, LzoCrc32.compute(blob));
        putU32(c, 12, payloadLen);
        putU32(c, 16, LzoCrc32.compute(payload, 0, payloadLen));
        putU32(c, 20, 0);
        c[24] = (byte) (blockCount & 0xff);
        c[25] = (byte) ((blockCount >> 8) & 0xff);
        for (int b = 0; b < blockCount; b++) {
            int v = 0x8000 | r.blockSizes[b];
            c[26 + b * 2] = (byte) (v & 0xff);
            c[26 + b * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        System.arraycopy(payload, 0, c, header, payloadLen);

        r.container = c;
        r.compressedSize = payloadLen;
        return r;
    }

    private static void putU32(byte[] a, int p, int v) {
        a[p] = (byte) (v & 0xff);
        a[p + 1] = (byte) ((v >> 8) & 0xff);
        a[p + 2] = (byte) ((v >> 16) & 0xff);
        a[p + 3] = (byte) ((v >> 24) & 0xff);
    }

    public static int getU32(byte[] a, int p) {
        return (a[p] & 0xff) | ((a[p + 1] & 0xff) << 8) | ((a[p + 2] & 0xff) << 16) | ((a[p + 3] & 0xff) << 24);
    }

    /** Widget records store their sheet offsets big endian, like the image data. */
    private static void putU32be(byte[] a, int p, int v) {
        a[p] = (byte) ((v >> 24) & 0xff);
        a[p + 1] = (byte) ((v >> 16) & 0xff);
        a[p + 2] = (byte) ((v >> 8) & 0xff);
        a[p + 3] = (byte) (v & 0xff);
    }

    public static int getU32be(byte[] a, int p) {
        return ((a[p] & 0xff) << 24) | ((a[p + 1] & 0xff) << 16) | ((a[p + 2] & 0xff) << 8) | (a[p + 3] & 0xff);
    }

    /**
     * Decompresses the container with the in-app decoder and compares every byte
     * against the blob we meant to ship. Catches compressor bugs, splice bugs and
     * table bugs before anything reaches the watch.
     */
    public static String selfCheck(byte[] container, byte[] expectedBlob) {
        if (container == null || container.length < 26) return "FAIL: container too small";
        if ((container[0] & 0xff) != 0xAF || (container[1] & 0xff) != 0xAF
                || (container[2] & 0xff) != 0xAF || (container[3] & 0xff) != 0x02) {
            return "FAIL: bad magic";
        }
        int unc = getU32(container, 4);
        int comp = getU32(container, 12);
        int count = (container[24] & 0xff) | ((container[25] & 0xff) << 8);
        int expected = expectedBlob == null ? unc : expectedBlob.length;
        int expectCount = (unc + BLOCK - 1) / BLOCK;
        int header = 26 + 2 * count;
        if (unc != expected) return "FAIL: uncompressed field " + unc + ", expected " + expected;
        if (count != expectCount) return "FAIL: block count " + count + ", expected " + expectCount;
        if (header + comp != container.length) {
            return "FAIL: size field " + comp + " != file " + (container.length - header)
                    + " (header " + header + ")";
        }

        byte[] out = new byte[BLOCK];
        byte[] got = new byte[unc];
        int gp = 0;
        int ip = header;
        for (int b = 0; b < count; b++) {
            int v = (container[26 + b * 2] & 0xff) | ((container[26 + b * 2 + 1] & 0xff) << 8);
            int size = v & 0x7fff;
            int expectLen = Math.min(BLOCK, unc - b * BLOCK);
            int n;
            try {
                n = Lzo1x.decompress(container, ip, size, out, 0, out.length);
            } catch (RuntimeException e) {
                return "FAIL: block " + b + " decode: " + e.getMessage();
            }
            if (n != expectLen) return "FAIL: block " + b + " decoded " + n + "B, expected " + expectLen;
            System.arraycopy(out, 0, got, gp, n);
            gp += n;
            ip += size;
        }
        if (gp != unc) return "FAIL: total " + gp + ", expected " + unc;

        if (expectedBlob != null) {
            for (int k = 0; k < unc; k++) {
                if (got[k] != expectedBlob[k]) {
                    return "FAIL: mismatch at byte " + k
                            + " (got 0x" + Integer.toHexString(got[k] & 0xff)
                            + " want 0x" + Integer.toHexString(expectedBlob[k] & 0xff) + ")";
                }
            }
        }
        return "OK: " + unc + " bytes round-trip identical, " + count + " blocks, payload " + comp + "B";
    }
}
