package com.jay.wfup;

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

    public static final int HEADER_SIZE = 396;
    /** Largest container proven on the wire. Not a format limit: the length field is u32. */
    public static final int MAX_FILE = 65535;

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

    /** Compresses blob and wraps it in the container, copying vendor fields from base. */
    public static Result pack(byte[] base, byte[] blob) {
        Result r = new Result();
        r.blockSizes = new int[BLOCK_COUNT];

        byte[] out = new byte[BLOCK + BLOCK / 8 + 128];
        byte[] payload = new byte[BLOB_SIZE + BLOB_SIZE / 8 + BLOCK_COUNT * 8];
        int payloadLen = 0;

        for (int b = 0; b < BLOCK_COUNT; b++) {
            int off = b * BLOCK;
            int len = Math.min(BLOCK, BLOB_SIZE - off);
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

        int total = HEADER_SIZE + payloadLen;
        byte[] c = new byte[total];
        c[0] = (byte) 0xAF; c[1] = (byte) 0xAF; c[2] = (byte) 0xAF; c[3] = 0x02;
        putU32(c, 4, BLOB_SIZE);
        // Field 8 and field 16 are the vendor checksum (LzoCrc32) of the uncompressed
        // blob and of the compressed payload respectively, per LzoUtils.mergeDialFile.
        // Note: the cloud copy of the reference face carries different values, i.e. the
        // vendor server does not use this function. The app's own DIY uploads do, and
        // those are accepted by the watch, so this is the form the watch sees.
        putU32(c, 8, LzoCrc32.compute(blob));
        putU32(c, 12, payloadLen);
        putU32(c, 16, LzoCrc32.compute(payload, 0, payloadLen));
        putU32(c, 20, 0);
        c[24] = (byte) (BLOCK_COUNT & 0xff);
        c[25] = (byte) ((BLOCK_COUNT >> 8) & 0xff);
        for (int b = 0; b < BLOCK_COUNT; b++) {
            int v = 0x8000 | r.blockSizes[b];
            c[26 + b * 2] = (byte) (v & 0xff);
            c[26 + b * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        System.arraycopy(payload, 0, c, HEADER_SIZE, payloadLen);

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

    /**
     * Decompresses the container with the in-app decoder and compares every byte
     * against the blob we meant to ship. Catches compressor bugs, splice bugs and
     * table bugs before anything reaches the watch.
     */
    public static String selfCheck(byte[] container, byte[] expectedBlob) {
        if (container == null || container.length < HEADER_SIZE) return "FAIL: container too small";
        if ((container[0] & 0xff) != 0xAF || (container[1] & 0xff) != 0xAF
                || (container[2] & 0xff) != 0xAF || (container[3] & 0xff) != 0x02) {
            return "FAIL: bad magic";
        }
        int unc = getU32(container, 4);
        int comp = getU32(container, 12);
        int count = (container[24] & 0xff) | ((container[25] & 0xff) << 8);
        if (unc != BLOB_SIZE) return "FAIL: uncompressed field " + unc;
        if (count != BLOCK_COUNT) return "FAIL: block count " + count;
        if (HEADER_SIZE + comp != container.length) {
            return "FAIL: size field " + comp + " != file " + (container.length - HEADER_SIZE);
        }

        byte[] out = new byte[BLOCK];
        byte[] got = new byte[BLOB_SIZE];
        int gp = 0;
        int ip = HEADER_SIZE;
        for (int b = 0; b < count; b++) {
            int v = (container[26 + b * 2] & 0xff) | ((container[26 + b * 2 + 1] & 0xff) << 8);
            int size = v & 0x7fff;
            int expect = Math.min(BLOCK, BLOB_SIZE - b * BLOCK);
            int n;
            try {
                n = Lzo1x.decompress(container, ip, size, out, 0, out.length);
            } catch (RuntimeException e) {
                return "FAIL: block " + b + " decode: " + e.getMessage();
            }
            if (n != expect) return "FAIL: block " + b + " decoded " + n + "B, expected " + expect;
            System.arraycopy(out, 0, got, gp, n);
            gp += n;
            ip += size;
        }
        if (gp != BLOB_SIZE) return "FAIL: total " + gp;

        if (expectedBlob != null) {
            for (int k = 0; k < BLOB_SIZE; k++) {
                if (got[k] != expectedBlob[k]) {
                    return "FAIL: mismatch at byte " + k
                            + " (got 0x" + Integer.toHexString(got[k] & 0xff)
                            + " want 0x" + Integer.toHexString(expectedBlob[k] & 0xff) + ")";
                }
            }
        }
        return "OK: " + BLOB_SIZE + " bytes round-trip identical, " + count + " blocks, payload " + comp + "B";
    }
}
