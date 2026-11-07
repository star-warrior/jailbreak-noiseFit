package com.jay.wfup;

import android.graphics.Bitmap;

/**
 * Turns a photo into an uploadable watch-face container, entirely on the phone.
 *
 * The photo is centre-cropped to a square, scaled to the 466x466 background
 * region, converted to RGB565 and spliced into the reference blob. Everything
 * outside the two image regions is carried over from the reference blob
 * untouched, so the firmware config, fonts and layout stay valid.
 *
 * A posterisation ladder is used to try to get under the transfer gate: full
 * RGB565 first, then 6, 5, 4, 3 and 2 bits per channel. Each rung is compressed
 * and measured, and the first one that fits is kept.
 */
public final class FacePipeline {

    private FacePipeline() {}

    public static final int BG_W = 466, BG_H = 466;
    public static final int PV_W = 310, PV_H = 310;

    /**
     * Sanity cap only. The BLE length field is a u32 and the vendor's own face with a
     * 1001544 byte blob transfers fine, so there is no 65535 limit; this just stops a
     * runaway build from streaming forever. The per-widget glyph sheets push the raw
     * blob to 1078368 bytes, so the cap has to sit above that.
     */
    public static final int LIMIT = 2000000;

    /**
     * Kept as a ladder so a pathological photo can still be shrunk, but with the
     * 2000000 byte cap the first (lossless quality) rung always fits, so the loop
     * stops there and no colour quantisation is ever applied.
     */
    public static final int[] LADDER = {8, 6, 5, 4};

    public static final class Built {
        public byte[] container;
        public byte[] blob;
        public String report = "";
        public int bits = 8;
        public boolean fits;
        public String error;
        public boolean ok() { return error == null; }
    }

    /**
     * Brightness window used to key the clock glyphs out of the stock clock strip.
     * Measured on the reference blob: the strip's background texture sits around
     * luma 45..105, the glyph strokes are pure white (255). Anything at or below
     * GLYPH_LO is treated as background, anything at or above GLYPH_HI as solid
     * glyph, and the antialiased edge in between is blended, so the digits stay
     * smooth instead of turning into stair steps.
     */
    public static final int GLYPH_LO = 160, GLYPH_HI = 235;

    /**
     * Stamps the stock clock glyphs onto the top rows of {@code dst} (the composed
     * 466x466 screen). The strip carries the glyph sprites the firmware blits for the
     * time, the date and the AM/PM marker; keeping only its bright pixels and letting
     * everything else come from the photo gives a full bleed face with a live clock.
     */
    public static int[] stampGlyphs(int[] dst, byte[] stockBand, int lo, int hi) {
        int pixels = Math.min(stockBand.length / 2, dst.length);
        int span = Math.max(1, hi - lo);
        for (int i = 0; i < pixels; i++) {
            int l = FaceBuilder.luma565(stockBand[i * 2], stockBand[i * 2 + 1]);
            if (l <= lo) continue;
            int a = Math.min(255, (l - lo) * 255 / span);
            int c = dst[i];
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            r += (255 - r) * a / 255;
            g += (255 - g) * a / 255;
            b += (255 - b) * a / 255;
            dst[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return dst;
    }

    /** Stock clock strip (rows 330..465 of the reference background block). */
    public static byte[] stockBand(byte[] base) {
        int off = FaceBuilder.OFF_BG + FaceBuilder.BG_ART_ROWS * FaceBuilder.BG_W * 2;
        int len = (FaceBuilder.BG_H - FaceBuilder.BG_ART_ROWS) * FaceBuilder.BG_W * 2;
        byte[] out = new byte[len];
        System.arraycopy(base, off, out, 0, len);
        return out;
    }

    /**
     * Photo face that keeps the clock working. The photo fills the artwork rows only
     * (the lower 330 of 466 screen rows); the clock strip is left exactly as it is in
     * the reference blob, so the watch renders the time as usual.
     *
     * The preview is generated from the composed screen (strip + photo) so the face
     * list thumbnail matches what the watch shows.
     */
    public static Built buildPhotoBelowClock(Bitmap src, byte[] base) {
        Built out = new Built();
        if (src == null) { out.error = "no image"; return out; }
        if (base == null || base.length != FaceBuilder.BLOB_SIZE) {
            out.error = "base blob missing or wrong size (" + (base == null ? "null" : base.length + "B") + ")";
            return out;
        }
        int artRows = FaceBuilder.BG_ART_ROWS;
        int bandRows = FaceBuilder.BG_H - artRows;

        // The crop is already the 466x466 screen, so take its lower 330 rows as they are
        // instead of scaling the square down into the artwork area (which squashed it).
        int[] whole = screenPixels(src);
        int[] art = new int[BG_W * artRows];
        System.arraycopy(whole, bandRows * BG_W, art, 0, art.length);
        byte[] art565 = FaceBuilder.toRgb565(art, BG_W, artRows);

        // preview: stock clock strip on top, photo underneath, exactly like the watch
        int[] bandPx = FaceBuilder.fromRgb565(stockBand(base), BG_W, bandRows);
        int[] screen = new int[BG_W * BG_H];
        System.arraycopy(bandPx, 0, screen, 0, bandPx.length);
        System.arraycopy(art, 0, screen, bandPx.length, art.length);
        Bitmap composed = Bitmap.createBitmap(screen, BG_W, BG_H, Bitmap.Config.ARGB_8888);
        int[] pvPx = pixels(Bitmap.createScaledBitmap(composed, PV_W, PV_H, true));
        byte[] pv565 = FaceBuilder.toRgb565(pvPx, PV_W, PV_H);

        byte[] blob = base.clone();
        System.arraycopy(art565, 0, blob, FaceBuilder.OFF_BG, artRows * FaceBuilder.BG_W * 2);
        System.arraycopy(pv565, 0, blob, FaceBuilder.OFF_PREVIEW, FaceBuilder.LEN_PREVIEW);

        FaceBuilder.Result r = FaceBuilder.pack(base, blob);
        if (!r.ok()) { out.error = r.error; return out; }

        String check = FaceBuilder.selfCheck(r.container, blob);
        out.container = r.container;
        out.blob = blob;
        out.bits = 8;
        out.fits = r.container.length <= LIMIT;
        out.report = "photo face: rows " + bandRows + ".." + (FaceBuilder.BG_H - 1)
                + " of the 466x466 crop in the artwork rows, clock strip untouched"
                + "\ncontainer " + r.container.length + "B, payload " + r.compressedSize + "B"
                + "\nselfcheck: " + check + "\n";
        if (!check.startsWith("OK")) out.error = "self check failed: " + check;
        return out;
    }

    /**
     * Full bleed: the photo covers the whole round screen, clock glyphs still come from
     * the untouched glyph atlas, so the time keeps working and the digits sit straight
     * on the photo.
     *
     * Screen is stored as three pieces: the clock bar's background in the mid block at
     * {@link FaceBuilder#OFF_MID}, the artwork in the first rows of the background block
     * (screen rows 136..465), and the glyph atlas at the end of it (left stock).
     */
    public static Built buildFullBleed(Bitmap src, byte[] base) {
        return buildFullBleed(src, base, false);
    }

    /**
     * Full bleed with the clock/date sprite sheets rebuilt: every cell background is
     * painted from the photo under that widget, so the firmware's opaque blit no longer
     * shows black boxes. The stock glyph pixels stay, blended by their own luminance.
     */
    public static Built buildFullBleedWithClock(Bitmap src, byte[] base) {
        return buildFullBleed(src, base, true);
    }

    private static Built buildFullBleed(Bitmap src, byte[] base, boolean clockBackgrounds) {
        Built out = new Built();
        if (src == null) { out.error = "no image"; return out; }
        if (base == null || base.length != FaceBuilder.BLOB_SIZE) {
            out.error = "base blob missing or wrong size (" + (base == null ? "null" : base.length + "B") + ")";
            return out;
        }
        int bandBytes = FaceBuilder.LEN_MID;
        int artBytes = FaceBuilder.BG_ART_ROWS * FaceBuilder.BG_W * 2;

        int[] screen = screenPixels(src);
        byte[] screen565 = FaceBuilder.toRgb565(screen, BG_W, BG_H);

        Bitmap shown = Bitmap.createBitmap(screen, BG_W, BG_H, Bitmap.Config.ARGB_8888);
        int[] pvPx = pixels(Bitmap.createScaledBitmap(shown, PV_W, PV_H, true));
        byte[] pv565 = FaceBuilder.toRgb565(pvPx, PV_W, PV_H);

        byte[] blob = base.clone();
        // screen rows 0..135 -> the clock bar background block
        System.arraycopy(screen565, 0, blob, FaceBuilder.OFF_MID, bandBytes);
        // screen rows 136..465 -> the artwork rows
        System.arraycopy(screen565, bandBytes, blob, FaceBuilder.OFF_BG, artBytes);
        System.arraycopy(pv565, 0, blob, FaceBuilder.OFF_PREVIEW, FaceBuilder.LEN_PREVIEW);

        if (clockBackgrounds) {
            try {
                blob = FaceBuilder.repaintWidgets(blob, screen);
            } catch (RuntimeException e) {
                out.error = "clock sheets: " + e.getMessage();
                return out;
            }
        }

        FaceBuilder.Result r = FaceBuilder.pack(base, blob);
        if (!r.ok()) { out.error = r.error; return out; }

        String check = FaceBuilder.selfCheck(r.container, blob);
        out.container = r.container;
        out.blob = blob;
        out.bits = 8;
        out.fits = r.container.length <= LIMIT;
        out.report = (clockBackgrounds
                    ? "full bleed: photo 466x466, clock/date sheet cells repainted from the photo"
                    : "full bleed: photo 466x466 across the whole screen, glyph atlas untouched")
                + "\nclock bar background " + bandBytes + "B at " + FaceBuilder.OFF_MID
                + ", artwork " + artBytes + "B at " + FaceBuilder.OFF_BG
                + "\nblob " + blob.length + "B"
                + "\ncontainer " + r.container.length + "B, payload " + r.compressedSize + "B"
                + "\nselfcheck: " + check + "\n";
        if (!check.startsWith("OK")) out.error = "self check failed: " + check;
        return out;
    }
    public static Built build(Bitmap src, byte[] base) {
        Built out = new Built();
        if (src == null) { out.error = "no image"; return out; }
        if (base == null || base.length != FaceBuilder.BLOB_SIZE) {
            out.error = "base blob missing or wrong size (" + (base == null ? "null" : base.length + "B") + ")";
            return out;
        }

        Bitmap square = centreCrop(src);
        int[] bgPx = pixels(Bitmap.createScaledBitmap(square, BG_W, BG_H, true));
        int[] pvPx = pixels(Bitmap.createScaledBitmap(square, PV_W, PV_H, true));
        byte[] pv565 = FaceBuilder.toRgb565(pvPx, PV_W, PV_H);

        StringBuilder sb = new StringBuilder();
        int bestBits = LADDER[LADDER.length - 1];
        int bestLen = Integer.MAX_VALUE;
        int firstBits = -1;

        for (int bits : LADDER) {
            int[] q = (bits >= 8) ? bgPx : quantize(bgPx, bits);
            byte[] bg565 = FaceBuilder.toRgb565(q, BG_W, BG_H);
            FaceBuilder.Result r = FaceBuilder.build(base, bg565, pv565);
            if (!r.ok()) {
                sb.append("bits ").append(bits).append(": FAIL ").append(r.error).append('\n');
                continue;
            }
            boolean fits = r.container.length <= LIMIT;
            sb.append("bits ").append(bits)
              .append(": container ").append(r.container.length).append("B")
              .append(fits ? "  fits" : "  OVER")
              .append('\n');
            if (fits && firstBits < 0) firstBits = bits;
            if (r.container.length < bestLen) { bestLen = r.container.length; bestBits = bits; }
            // the top rung always fits now; do not burn time on lossier ones
            if (bits == LADDER[0] && fits) break;
        }

        // Prefer the best looking rung that fits; if none fits, fall back to the
        // smallest one and let the caller decide whether to override the gate.
        int chosenBits = (firstBits >= 0) ? firstBits : bestBits;
        out.fits = (firstBits >= 0);
        if (!out.fits) sb.append("nothing fits ").append(LIMIT).append("B; smallest is ")
                .append(bestLen).append("B at ").append(bestBits).append(" bits\n");

        // rebuild the chosen rung once more to keep the blob for the self check
        int[] q = (chosenBits >= 8) ? bgPx : quantize(bgPx, chosenBits);
        byte[] bg565 = FaceBuilder.toRgb565(q, BG_W, BG_H);
        byte[] blob = base.clone();
        System.arraycopy(bg565, 0, blob, FaceBuilder.OFF_BG, FaceBuilder.LEN_BG);
        System.arraycopy(pv565, 0, blob, FaceBuilder.OFF_PREVIEW, FaceBuilder.LEN_PREVIEW);

        FaceBuilder.Result r = FaceBuilder.pack(base, blob);
        if (!r.ok()) { out.error = r.error; return out; }

        String check = FaceBuilder.selfCheck(r.container, blob);
        sb.append("selfcheck: ").append(check).append('\n');

        out.container = r.container;
        out.blob = blob;
        out.bits = chosenBits;
        out.report = sb.toString();
        if (!check.startsWith("OK")) out.error = "self check failed: " + check;
        return out;
    }

    /**
     * The photo as the full 466x466 screen. The crop dialog already hands over a square,
     * so this only scales; a non square bitmap (a caller that skipped the crop) is centre
     * cropped first rather than stretched.
     */
    static int[] screenPixels(Bitmap src) {
        Bitmap square = centreCrop(src);
        Bitmap sized = (square.getWidth() == BG_W)
                ? square : Bitmap.createScaledBitmap(square, BG_W, BG_H, true);
        int[] px = pixels(sized);
        if (sized != src) sized.recycle();
        if (square != src && square != sized) square.recycle();
        return px;
    }

    static Bitmap centreCrop(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int s = Math.min(w, h);
        int x = (w - s) / 2, y = (h - s) / 2;
        return Bitmap.createBitmap(src, x, y, s, s);
    }

    static int[] pixels(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    /** Reduce each channel to {@code bits} bits, no dithering (dither costs bytes). */
    static int[] quantize(int[] px, int bits) {
        int levels = (1 << bits) - 1;
        int[] o = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
            r = (r * levels / 255) * 255 / levels;
            g = (g * levels / 255) * 255 / levels;
            b = (b * levels / 255) * 255 / levels;
            o[i] = 0xff000000 | (r << 16) | (g << 8) | b;
        }
        return o;
    }
}
