package com.jay.wfup;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    /** Shown on every start so the installed APK can be identified from the log alone. */
    static final String BUILD_TAG = "2.6.1-scan-clock";
    static final int REQ_SCAN = 2;
    static final String SRV_CUST_PREFIX = "16186F00-0000-1000-8000-00807F9B34FB";
    static final String CHR_A = "16186F01-0000-1000-8000-00807F9B34FB";
    static final String CHR_B = "16186F02-0000-1000-8000-00807F9B34FB";
    static final String CHR_DATA = "16186F04-0000-1000-8000-00807F9B34FB";
    static final String CHR_SIDE = "16186F05-0000-1000-8000-00807F9B34FB";

    BluetoothGatt gatt;
    BluetoothGattCharacteristic chA, chB, chData, chSide;
    TextView logView;
    ScrollView svRef;
    ListView listDevices;
    ArrayAdapter<String> devAdapter;
    final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();
    List<String> replayLines = new ArrayList<>();
    int replayIdx = 0;
    final Handler h = new Handler(Looper.getMainLooper());
    boolean running = false;
    boolean paused = false;
    String waitAck = null;
    String waitGateChar = null;
    boolean isGateChar(BluetoothGattCharacteristic c, String which) {
        if (c == null) return false;
        String w = which.toLowerCase();
        if (w.equals("21")) return c == chA;
        if (w.equals("24")) return c == chB;
        if (w.equals("2a")) return c == chData;
        if (w.equals("2d")) return c == chSide;
        return false;
    }
    int hnotify = 0;
    final java.util.ArrayDeque<String> autoReplies = new java.util.ArrayDeque<>();
    boolean writePending = false;
    long writePendingDesc = 0;
    long tStart = 0;
    int ackGen = 0, wrGen = 0;

    /* ---- offline face builder state ---- */
    static final int REQ_PHOTO = 1001;
    Bitmap photo;
    /** Full picked image, kept so the crop can be reopened without picking again. */
    Bitmap photoSrc;
    CropView cropView;
    android.app.Dialog cropDialog;
    byte[] baseBlob;
    byte[] builtContainer;
    boolean builtFits = false;
    boolean allowBig = false;
    String builtReport = "";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); svRef = sv;
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL); sv.addView(col); setContentView(sv);
        // Tiny focus sink so the MAC field does not grab initial focus and scroll
        // the column past the section header and SCAN button.
        android.view.View focusSink = new android.view.View(this);
        focusSink.setFocusable(true);
        focusSink.setFocusableInTouchMode(true);
        col.addView(focusSink, new android.widget.LinearLayout.LayoutParams(1, 1));        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);

        /* ---------- 1. connect ---------- */
        devHeader = section("1  CONNECT TO WATCH");
        col.addView(devHeader);
        Button btnScan = new Button(this); btnScan.setText("SCAN"); col.addView(btnScan);
        android.widget.EditText macIn = new android.widget.EditText(this);
        macIn.setHint("or paste watch MAC XX:XX:XX:XX:9F:33");
        macIn.setText("DB:81:C2:15:9F:33");
        macIn.setTextSize(14); col.addView(macIn);
        sv.postDelayed(() -> {
            macIn.clearFocus();
            focusSink.requestFocus();
            sv.scrollTo(0, 0);
        }, 400);
        Button btnMac = new Button(this); btnMac.setText("CONNECT BY MAC"); col.addView(btnMac);
        listDevices = new ListView(this);
        listDevices.setAdapter(devAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1));
        // A ListView inside a ScrollView measures to one row unless it gets a fixed
        // height, so set one explicitly and let it scroll on its own.
        android.widget.LinearLayout.LayoutParams listLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (300 * getResources().getDisplayMetrics().density));
        listDevices.setLayoutParams(listLp);
        col.addView(listDevices);
        Button btnRun = new Button(this); btnRun.setText("REPLAY"); col.addView(btnRun);

        /* ---------- 2. build from photo ---------- */
        col.addView(section("2  BUILD FACE FROM PHOTO"));
        Button btnPhoto = new Button(this); btnPhoto.setText("PICK PHOTO"); col.addView(btnPhoto);
        Button btnRecrop = new Button(this); btnRecrop.setText("RE-CROP PHOTO"); col.addView(btnRecrop);
        Button btnBleed = new Button(this); btnBleed.setText("BUILD FACE (photo over whole screen)"); col.addView(btnBleed);

        /* ---------- 3. upload ---------- */
        col.addView(section("3  UPLOAD"));
        Button btnUp = new Button(this); btnUp.setText("UPLOAD BUILT FACE"); col.addView(btnUp);
        android.widget.CheckBox cbBig = new android.widget.CheckBox(this);
        cbBig.setText("allow over-gate upload (untested above " + FacePipeline.LIMIT + "B)");
        cbBig.setChecked(false);
        cbBig.setOnCheckedChangeListener((v, c) -> allowBig = c);
        col.addView(cbBig);

        /* ---------- 4. log ---------- */
        col.addView(section("LOG"));
        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        col.addView(logView);
        log("WFUP build " + BUILD_TAG);

        btnScan.setOnClickListener(v -> {
            if (!hasScanPermissions()) {
                log("requesting scan permissions (" + permissionState() + ")");
                requestScanPermissions();
                return;
            }
            startScan();
        });
        btnMac.setOnClickListener(v -> {
            String mac = macIn.getText().toString().trim().toUpperCase();
            if (mac.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
                BluetoothAdapter ad = ((BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
                BluetoothDevice d = ad.getRemoteDevice(mac);
                log("mac connect " + mac);
                connect(d);
            } else log("bad mac format");
        });
        listDevices.setOnItemClickListener((p, v, pos, id) -> {
            String name = devAdapter.getItem(pos);
            BluetoothDevice dev = found.get(name);
            if (dev != null) connect(dev);
        });
        btnRun.setOnClickListener(v -> { if (!running) runReplay(); });
        btnPhoto.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(i, REQ_PHOTO); }
            catch (Exception e) { log("no picker: " + e); }
        });
        btnRecrop.setOnClickListener(v -> openCropDialog());
        btnUp.setOnClickListener(v -> uploadBuilt());
        btnBleed.setOnClickListener(v -> buildFullBleedFace());
    }

    /** Section heading used to group the buttons on screen. */
    TextView section(String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF1565C0);
        t.setPadding(0, 28, 0, 4);
        return t;
    }


    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PHOTO || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            // Size first, so a 100 megapixel camera shot is downsampled instead of OOM.
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream is = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, MAX_SRC);
            Bitmap bm;
            try (java.io.InputStream is = getContentResolver().openInputStream(u)) {
                bm = BitmapFactory.decodeStream(is, null, o);
            }
            if (bm == null) { log("could not decode image"); return; }
            bm = applyExif(bm, exifOrientation(u));
            if (photoSrc != null && photoSrc != bm) photoSrc.recycle();
            photoSrc = bm;
            log("photo loaded " + bm.getWidth() + "x" + bm.getHeight());
            log("drag to move, pinch to zoom, then USE CROP");
            openCropDialog();
        } catch (Exception | OutOfMemoryError e) { log("photo load failed: " + e); }
    }

    /** Largest edge kept from the picked file; the crop only ever needs 466 px. */
    static final int MAX_SRC = 2048;

    static int sampleFor(int w, int h, int max) {
        int s = 1;
        if (w <= 0 || h <= 0) return s;
        while (Math.max(w, h) / s > max) s *= 2;
        return s;
    }

    int exifOrientation(Uri u) {
        try (java.io.InputStream is = getContentResolver().openInputStream(u)) {
            if (is == null) return 0;
            return new android.media.ExifInterface(is)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Applies the JPEG orientation flag so portrait shots are not stored sideways. */
    static Bitmap applyExif(Bitmap b, int o) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        switch (o) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(270); m.postScale(-1, 1); break;
            case 8: m.setRotate(270); break;
            default: return b;
        }
        Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (r != b) b.recycle();
        return r;
    }

    /**
     * Opens the square crop dialog, the same idea as the vendor app's custom face step:
     * what is inside the square is what the round watch shows, so nothing gets squashed
     * to fit. The crop is square because the screen is 466x466.
     */
    void openCropDialog() {
        if (photoSrc == null) { log("pick a photo first"); return; }
        if (cropDialog != null && cropDialog.isShowing()) return;

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xff000000);

        cropView = new CropView(this);
        box.addView(cropView, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        android.widget.LinearLayout bar = new android.widget.LinearLayout(this);
        bar.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        Button cancel = new Button(this); cancel.setText("CANCEL");
        Button use = new Button(this); use.setText("USE CROP");
        bar.addView(cancel, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(use, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(bar);

        cropDialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        cropDialog.setContentView(box);
        cropDialog.setOnShowListener(d -> cropView.setBitmap(photoSrc));
        cancel.setOnClickListener(v -> cropDialog.dismiss());
        use.setOnClickListener(v -> useCrop());
        cropDialog.show();
    }

    /** Cuts the square out of the picked photo at 466x466 and keeps it as the face image. */
    void useCrop() {
        if (cropView == null) return;
        final CropView cv = cropView;
        final float zoom = cv.zoomFactor();
        log("cropping at " + String.format(java.util.Locale.US, "%.2f", zoom) + "x...");
        new Thread(() -> {
            Bitmap cut = null;
            String err = null;
            try {
                cut = cv.crop(FaceBuilder.BG_W);
            } catch (Throwable e) {
                err = String.valueOf(e);
            }
            final Bitmap done = cut;
            final String fail = err;
            runOnUiThread(() -> {
                if (done == null) { log("crop failed: " + (fail == null ? "no image" : fail)); return; }
                if (photo != null && photo != done) photo.recycle();
                photo = done;
                if (cropDialog != null) cropDialog.dismiss();
                log("crop ready " + done.getWidth() + "x" + done.getHeight() + " at "
                        + String.format(java.util.Locale.US, "%.2f", zoom) + "x zoom");
                log("now use BUILD FACE or BUILD FULL-BLEED FACE");
            });
        }, "face-crop").start();
    }

    byte[] loadBaseBlob() {
        if (baseBlob != null) return baseBlob;
        try {
            java.io.InputStream is = getAssets().open("blob.bin");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(FaceBuilder.BLOB_SIZE);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            baseBlob = bo.toByteArray();
            log("base blob " + baseBlob.length + "B");
        } catch (Exception e) {
            log("blob.bin missing: " + e);
        }
        return baseBlob;
    }

    void buildFace() {
        if (photo == null) { log("pick a photo first"); return; }
        final byte[] base = loadBaseBlob();
        if (base == null) return;
        final Bitmap src = photo;
        log("building photo face (clock strip preserved)...");
        new Thread(() -> {
            long t0 = System.nanoTime();
            final FacePipeline.Built b = FacePipeline.buildPhotoBelowClock(src, base);
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            runOnUiThread(() -> {
                builtReport = b.report;
                for (String line : b.report.split("\n")) if (!line.isEmpty()) log(line);
                if (!b.ok()) {
                    builtContainer = null;
                    builtFits = false;
                    log("BUILD FAILED: " + b.error);
                    return;
                }
                builtContainer = b.container;
                builtFits = b.fits;
                log("built in " + ms + "ms, container " + b.container.length + "B");
                log("gate " + FacePipeline.LIMIT + "B: " + (b.fits ? "OK" : "OVER - tick the box to try anyway"));
            });
        }, "face-build").start();
    }

    /**
     * Diagnostic: packs the untouched reference blob. Same content as the face the
     * watch already accepted, but our compressor and our checksums. If the watch
     * accepts this, the packer is fine and the rejection is about the photo content;
     * if it rejects, the packer (compression or checksums) is what the watch refuses.
     */
    void packReference() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        FaceBuilder.Result r = FaceBuilder.pack(base, base);
        if (!r.ok()) { log("pack failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = true;
        log("packed reference: " + r.container.length + "B, payload " + r.compressedSize + "B");
        log("field8 " + String.format("%08X", FaceBuilder.getU32(r.container, 8))
                + "  field16 " + String.format("%08X", FaceBuilder.getU32(r.container, 16)));
        log("selfcheck: " + FaceBuilder.selfCheck(r.container, base));
        log("tap UPLOAD BUILT FACE to test the packer");
    }

    /**
     * Colour calibration face: four colour bars over a greyscale ramp, written into both
     * image regions. Upload it and the watch tells us the channel order directly.
     */
    void buildTestPattern() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int[] bars = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};
        int[] greys = {0xFF000000, 0xFF404040, 0xFF808080, 0xFFFFFFFF};
        int[] bg = barGrid(FacePipeline.BG_W, FacePipeline.BG_H, bars, greys);
        int[] pv = barGrid(FacePipeline.PV_W, FacePipeline.PV_H, bars, greys);
        FaceBuilder.Result r = FaceBuilder.build(base,
                FaceBuilder.toRgb565(bg, FacePipeline.BG_W, FacePipeline.BG_H),
                FaceBuilder.toRgb565(pv, FacePipeline.PV_W, FacePipeline.PV_H));
        if (!r.ok()) { log("pattern build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("colour pattern built: " + r.container.length + "B, payload " + r.compressedSize + "B");
        log("tap UPLOAD BUILT FACE, then look at the watch");
    }

    /** Four vertical bars on the top half, four grey steps on the bottom half. */
    static int[] barGrid(int w, int h, int[] bars, int[] greys) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int[] row = (y < h / 2) ? bars : greys;
            for (int x = 0; x < w; x++) px[y * w + x] = row[Math.min(3, x * 4 / w)];
        }
        return px;
    }

    /**
     * Strip probe. Paints the clock band only (rows FaceBuilder.BG_ART_ROWS..465) with
     * two flat colours: magenta on the first half, cyan on the second. The artwork and
     * the preview are left stock, so whatever changes in the clock area can only have
     * come from the band.
     *
     *   strip turns magenta/cyan -> the strip background is drawn from the band's pixels
     *                              -> a photo can be extended under it (full bleed)
     *   strip stays black        -> the bar is drawn by the firmware
     *   which colour lands on top also gives the band's row direction on screen.
     */
    void buildStripProbe() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int from = FaceBuilder.BG_ART_ROWS, to = FaceBuilder.BG_H;
        int mid = from + (to - from) / 2;
        int[] px = new int[FaceBuilder.BG_W * FaceBuilder.BG_H];
        for (int y = from; y < to; y++) {
            int c = (y < mid) ? 0xFFFF00FF : 0xFF00FFFF;   // magenta, cyan
            for (int x = 0; x < FaceBuilder.BG_W; x++) px[y * FaceBuilder.BG_W + x] = c;
        }
        FaceBuilder.Result r = FaceBuilder.build(base,
                FaceBuilder.toRgb565(px, FaceBuilder.BG_W, FaceBuilder.BG_H), from, to, null);
        if (!r.ok()) { log("strip probe build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("strip probe built: " + r.container.length + "B, painted rows " + from + ".." + (mid - 1)
                + " magenta, " + mid + ".." + (to - 1) + " cyan");
        log("artwork and preview untouched - tap UPLOAD BUILT FACE, then look at the top strip");
    }

    /**
     * Geometry probe. Writes red/green/blue thirds into the artwork rows only and leaves
     * the clock strip (rows FaceBuilder.BG_ART_ROWS..465) untouched, so the time keeps
     * rendering. Confirms the 1:1 row mapping between the blob and the screen.
     */
    void buildBands() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int rows = FaceBuilder.BG_ART_ROWS;
        int[] px = new int[FaceBuilder.BG_W * FaceBuilder.BG_H];
        int step = rows / 3;
        for (int y = 0; y < rows; y++) {
            int c = (y < step) ? 0xFFFF0000 : (y < 2 * step) ? 0xFF00FF00 : 0xFF0000FF;
            for (int x = 0; x < FaceBuilder.BG_W; x++) px[y * FaceBuilder.BG_W + x] = c;
        }
        FaceBuilder.Result r = FaceBuilder.build(base,
                FaceBuilder.toRgb565(px, FaceBuilder.BG_W, FaceBuilder.BG_H), rows, null);
        if (!r.ok()) { log("bands build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("bands built: " + r.container.length + "B, wrote rows 0.." + (rows - 1)
                + ", kept " + (FaceBuilder.BG_H - rows) + " glyph rows");
        log("tap UPLOAD BUILT FACE, then look at the watch");
    }

    /**
     * Full bleed build: the photo is scaled to the whole 466x466 screen and the clock
     * glyphs are keyed back on top of it, so there is no black clock bar any more.
     */
    void buildFullBleedFace() {
        if (photo == null) { log("pick a photo first"); return; }
        final byte[] base = loadBaseBlob();
        if (base == null) return;
        final Bitmap src = photo;
        log("building full bleed (photo behind the clock, cells repainted)...");
        new Thread(() -> {
            long t0 = System.nanoTime();
            final FacePipeline.Built b = FacePipeline.buildFullBleedWithClock(src, base);
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            runOnUiThread(() -> {
                for (String line : b.report.split("\n")) if (!line.isEmpty()) log(line);
                if (!b.ok()) {
                    builtContainer = null;
                    builtFits = false;
                    log("FULL BLEED FAILED: " + b.error);
                    return;
                }
                builtContainer = b.container;
                builtFits = b.fits;
                log("full bleed built in " + ms + "ms");
                log("tap UPLOAD BUILT FACE, then look at the whole screen");
            });
        }, "face-fullbleed").start();
    }

    /**
     * Diagnostic: paints the clock strip dark blue and keys only the bright stock pixels
     * back on top. If the watch shows white digits over blue, the luma key in
     * {@link FacePipeline#GLYPH_LO} works and the glyphs can sit on a photo.
     */
    void buildStripMask() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int from = FaceBuilder.BG_ART_ROWS, to = FaceBuilder.BG_H;
        int bandPx = (to - from) * FaceBuilder.BG_W;
        int[] band = new int[bandPx];
        for (int i = 0; i < bandPx; i++) band[i] = 0xff102040;      // dark blue
        FacePipeline.stampGlyphs(band, FacePipeline.stockBand(base),
                FacePipeline.GLYPH_LO, FacePipeline.GLYPH_HI);
        int[] px = new int[FaceBuilder.BG_W * FaceBuilder.BG_H];
        System.arraycopy(band, 0, px, from * FaceBuilder.BG_W, bandPx);
        FaceBuilder.Result r = FaceBuilder.build(base,
                FaceBuilder.toRgb565(px, FaceBuilder.BG_W, FaceBuilder.BG_H), from, to, null);
        if (!r.ok()) { log("mask probe build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("mask probe built: " + r.container.length + "B, blue strip, glyphs keyed at luma "
                + FacePipeline.GLYPH_LO + ".." + FacePipeline.GLYPH_HI);
        log("tap UPLOAD BUILT FACE: expect white digits/date over blue, no boxes");
    }

    /**
     * Atlas map. Paints the clock strip with a 32 px grid where red encodes the column
     * and green the row of the source cell. Whatever colour the digits take on the
     * watch is the grid cell the firmware copies them from, which tells us where the
     * glyph sprites live so a full-bleed build can preserve exactly those cells.
     */
    void buildGridProbe() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int from = FaceBuilder.BG_ART_ROWS, to = FaceBuilder.BG_H;
        int cell = 32;
        int cols = (FaceBuilder.BG_W + cell - 1) / cell;
        int rows = (to - from + cell - 1) / cell;
        int[] px = new int[FaceBuilder.BG_W * FaceBuilder.BG_H];
        for (int y = from; y < to; y++) {
            int by = (y - from) / cell;
            int g = 20 + by * 200 / Math.max(1, rows - 1);
            for (int x = 0; x < FaceBuilder.BG_W; x++) {
                int bx = x / cell;
                int r = 20 + bx * 215 / Math.max(1, cols - 1);
                px[y * FaceBuilder.BG_W + x] = 0xff000000 | (r << 16) | (g << 8);
            }
        }
        FaceBuilder.Result r = FaceBuilder.build(base,
                FaceBuilder.toRgb565(px, FaceBuilder.BG_W, FaceBuilder.BG_H), from, to, null);
        if (!r.ok()) { log("grid probe build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("grid probe built: " + r.container.length + "B, " + cols + "x" + rows + " cells of " + cell + "px");
        log("red = column 0.." + (cols - 1) + ", green = row 0.." + (rows - 1));
        log("upload, then photo the watch: the digit colour names the source cell");
    }

    /**
     * Strip background probe. The 127342 byte block between the preview and the artwork
     * is 99.95% zeros, i.e. black, which is where the clock bar's own background should
     * come from. A 32 px grid goes into its image part (466x136); artwork and glyph
     * atlas stay stock, so the time keeps working.
     *
     *   strip background turns into the grid -> the bar is a stored image, full bleed is
     *                                          a photo in this block
     *   strip stays black                     -> the bar is firmware drawn, ship the
     *                                          photo-below-clock face as final
     */
    void buildMidProbe() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int w = FaceBuilder.BG_W, h = FaceBuilder.STRIP_ROWS;
        int cell = 32;
        int cols = (w + cell - 1) / cell;
        int rows = (h + cell - 1) / cell;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int by = y / cell;
            int g = 20 + by * 200 / Math.max(1, rows - 1);
            for (int x = 0; x < w; x++) {
                int bx = x / cell;
                int r = 20 + bx * 215 / Math.max(1, cols - 1);
                px[y * w + x] = 0xff000000 | (r << 16) | (g << 8);
            }
        }
        FaceBuilder.Result r = FaceBuilder.buildMid(base, FaceBuilder.toRgb565(px, w, h));
        if (!r.ok()) { log("mid probe build failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("mid probe built: " + r.container.length + "B, grid into block at "
                + FaceBuilder.OFF_MID + " (" + w + "x" + h + " + " + (FaceBuilder.MID_SIZE - FaceBuilder.LEN_MID) + "B tail)");
        log("red = column 0.." + (cols - 1) + ", green = row 0.." + (rows - 1) + "; atlas + artwork stock");
        log("upload, then look at the clock bar background");
    }

    /**
     * Runs the full-bleed pipeline on a synthetic screen: 32 px black grid on white with
     * a coloured 64 px marker in each corner and one in the centre. The artwork half is
     * known to map 1:1, so comparing the marker size and position in the clock bar
     * against the ones below it measures whatever offset or scaling the bar's image gets.
     */
    void buildGeometryProbe() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int w = FaceBuilder.BG_W, h = FaceBuilder.BG_H;
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = 0xfff0f0f0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (x % 32 == 0 || y % 32 == 0) px[y * w + x] = 0xff000000;
        marker(px, w, h, 8, 8, 0xffe00000);              // red, screen top left
        marker(px, w, h, w - 72, 8, 0xff00c000);         // green, screen top right
        marker(px, w, h, 8, h - 72, 0xff0000e0);         // blue, screen bottom left
        marker(px, w, h, (w - 64) / 2, (h - 64) / 2, 0xffffe000);   // yellow, centre
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        FacePipeline.Built b = FacePipeline.buildFullBleed(bmp, base);
        if (!b.ok()) { log("geometry probe failed: " + b.error); return; }
        builtContainer = b.container;
        builtFits = b.fits;
        log("geometry probe built: " + b.container.length + "B");
        log("red = top left, green = top right, yellow = centre, blue = bottom left");
        log("upload: where do the top markers land, and how big are they?");
    }

    /** Fills a 64x64 marker against the current orientation of the probe pattern. */
    static void marker(int[] px, int w, int h, int x0, int y0, int color) {
        int size = 64;
        for (int y = y0; y < Math.min(h, y0 + size); y++)
            for (int x = x0; x < Math.min(w, x0 + size); x++)
                px[y * w + x] = color;
    }

    /**
     * Plate probe. The config header at 0x180 holds 13-byte widget records: x, y, w, h,
     * a kind byte and a u32 offset into the blob. Each offset points at a raw sprite
     * sheet (10 digits of w*h for the time, one cell for the colon, four for the date),
     * stored as RGB565 with a black background.
     *
     * Paints only those black background pixels: date green, digits red, colon blue, on a
     * plain light grey full-bleed screen. Reading the result tells us where the black
     * boxes around the time come from in one upload:
     *
     *   boxes turn red/green/blue -> the sheets are blitted opaque, fix is per-position
     *                                sheets with the photo painted into the cell background
     *   nothing changes           -> the boxes come from the face config, fix is a header
     *                                field (font background colour)
     *   only thin edges change    -> near-black shadow pixels in the sheets, fix is zeroing
     *                                everything below a luma threshold
     */
    void buildPlateProbe() {
        byte[] base = loadBaseBlob();
        if (base == null) return;
        int w = FaceBuilder.BG_W, h = FaceBuilder.BG_H;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xffb4b4b4);                   // light grey: boxes will show
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);

        FacePipeline.Built built = FacePipeline.buildFullBleed(bmp, base);
        if (!built.ok()) { log("plate probe failed: " + built.error); return; }

        byte[] blob = built.blob.clone();
        List<int[]> recs = widgetRecords(blob);
        java.util.Collections.sort(recs, (a, b) -> Integer.compareUnsigned(a[5], b[5]));
        int total = 0;
        for (int i = 0; i < recs.size(); i++) {
            int[] r = recs.get(i);
            int start = r[5];
            int end = (i + 1 < recs.size()) ? recs.get(i + 1)[5] : blob.length;
            if (start <= 0 || end <= start || end > blob.length) continue;
            int colour = r[4] == 1 ? 0x001f : (r[4] == 4 ? 0x07e0 : 0xf800);   // blue : green : red
            int n = paintBlack(blob, start, end, colour);
            total += n;
            log("widget kind " + r[4] + " rect " + r[0] + "," + r[1] + " " + r[2] + "x" + r[3]
                    + " sheet " + start + ".." + end + ", painted " + n);
        }
        if (total == 0) { log("no black pixels found at the sheet offsets - decode is wrong"); return; }

        FaceBuilder.Result r = FaceBuilder.pack(base, blob);
        if (!r.ok()) { log("plate probe pack failed: " + r.error); return; }
        builtContainer = r.container;
        builtFits = r.container.length <= FacePipeline.LIMIT;
        log("plate probe built: " + r.container.length + "B, " + total + " sheet pixels recoloured");
        log("upload on a GREY screen: date = green, digits = red, colon = blue");
        log("report which parts of the clock changed colour");
    }

    /** Widget table: 13 bytes per record, x/y/w/h u16 BE, kind, u32 sheet offset. */
    static final int WIDGET_TABLE = 0x180, WIDGET_REC = 13, WIDGET_END = 514;

    static List<int[]> widgetRecords(byte[] blob) {
        List<int[]> out = new ArrayList<>();
        for (int off = WIDGET_TABLE; off + WIDGET_REC <= WIDGET_END; off += WIDGET_REC) {
            int x = u16(blob, off), y = u16(blob, off + 2);
            int w = u16(blob, off + 4), h = u16(blob, off + 6);
            int kind = blob[off + 8] & 0xff;
            int sheet = u32(blob, off + 9);
            if (x == 0 && y == 0 && w == 0 && h == 0 && kind == 0 && sheet == 0) break;
            out.add(new int[]{x, y, w, h, kind, sheet});
        }
        return out;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    static int u32(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
                | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    /** Replaces black RGB565 pixels (0x0000) in a range with {@code colour}. */
    static int paintBlack(byte[] blob, int start, int end, int colour) {
        int n = 0;
        for (int i = start; i + 1 < end; i += 2) {
            if (blob[i] == 0 && blob[i + 1] == 0) {
                blob[i] = (byte) (colour >> 8);
                blob[i + 1] = (byte) colour;
                n++;
            }
        }
        return n;
    }

    void uploadBuilt() {
        if (builtContainer == null) { log("build a face first"); return; }
        if (chData == null) { log("connect to the watch first"); return; }
        if (!builtFits && !allowBig) {
            log("refusing: " + builtContainer.length + "B is above the " + FacePipeline.LIMIT
                    + "B sanity cap. Tick the box if you want to try anyway.");
            return;
        }
        if (!builtFits) log("WARNING: uploading " + builtContainer.length + "B, above the sanity cap");
        java.util.List<String> script = ReplayBuilder.buildScript(builtContainer);
        replayLines = ReplayBuilder.preamble();
        replayLines.addAll(script);
        log("upload prepared: " + script.size() + " transfer lines for " + builtContainer.length + "B");
        byte[] tr = ReplayBuilder.trailer(builtContainer);
        log("trailer " + String.format("%02X%02X%02X%02X", tr[0], tr[1], tr[2], tr[3])
                + " pieces " + ((builtContainer.length + ReplayBuilder.WRAP_BYTES + ReplayBuilder.PIECE_BYTES - 1) / ReplayBuilder.PIECE_BYTES));
        runReplay();
    }

    /* ---- native-lib callback capture (libVeryFitMulti) ---- */
    static StringBuffer nativeOut = new StringBuffer();
    public static void onNativeWrite(byte[] data) {
        if (data != null && nativeOut.length() < 4_000_000) {
            nativeOut.append("W").append(data.length).append(",");
        }
        Log.i("WFUP", "NW " + (data == null ? -1 : data.length) + " total=" + nativeOut.length());
    }
    public static void onNativeJson(int id, byte[] data) {
        String s = data == null ? "" : new String(data);
        if (s.length() > 300) s = s.substring(0, 300);
        Log.i("WFUP", "NJ " + id + ": " + s);
    }
    public static void onNativeSysEvt(int id) { Log.i("WFUP", "NE " + id); }
    @Override protected void onPause() { super.onPause(); }
    void log(String s) { runOnUiThread(() -> {
        Log.i("WFUP", s);
        if (logView == null) return;
        String prev = logView.getText().toString();
        String[] ls = (prev + "\n" + s).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int k = Math.max(0, ls.length - 12); k < ls.length; k++) sb.append(ls[k]).append('\n');
        logView.setText(sb);
        if (svRef != null) svRef.post(() -> {
            // Only follow the log when the user is already at the bottom. Otherwise
            // a startup/scan line would yank the page away from the SCAN button.
            android.view.View child = svRef.getChildAt(0);
            int max = child == null ? 0 : Math.max(0, child.getHeight() - svRef.getHeight());
            if (svRef.getScrollY() >= max - 200) svRef.fullScroll(android.view.View.FOCUS_DOWN);
        });
    }); }

    /* ---- BLE scan ---- */
    static final int SCAN_MS = 15000;
    boolean scanning = false;
    int scanGen = 0;
    final HashSet<String> foundAddrs = new HashSet<>();
    final List<String> labelOrder = new ArrayList<>();
    TextView devHeader;

    static boolean granted(Activity a, String p) {
        return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /** API 31+ needs only the Nearby devices pair. API 30 and below needs location. */
    boolean hasScanPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return granted(this, "android.permission.BLUETOOTH_SCAN")
                    && granted(this, "android.permission.BLUETOOTH_CONNECT");
        }
        return granted(this, "android.permission.ACCESS_FINE_LOCATION");
    }

    String permWord(String p) { return granted(this, p) ? "granted" : "denied"; }

    String permissionState() {
        if (Build.VERSION.SDK_INT >= 31) {
            return "scan " + permWord("android.permission.BLUETOOTH_SCAN")
                    + ", connect " + permWord("android.permission.BLUETOOTH_CONNECT");
        }
        return "location " + permWord("android.permission.ACCESS_FINE_LOCATION");
    }

    void requestScanPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    "android.permission.BLUETOOTH_SCAN",
                    "android.permission.BLUETOOTH_CONNECT"}, REQ_SCAN);
        } else {
            requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION"}, REQ_SCAN);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_SCAN) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < perms.length; i++) {
            sb.append(perms[i].substring(perms[i].lastIndexOf('.') + 1))
              .append('=')
              .append(i < res.length && res[i] == PackageManager.PERMISSION_GRANTED ? "granted" : "denied")
              .append(' ');
        }
        log("permission result: " + sb);
        if (hasScanPermissions()) {
            startScan();
        } else {
            log("scan blocked: allow Nearby devices (Android 12+) or Location (Android 11-) in app settings");
        }
    }

    @SuppressLint("MissingPermission")
    String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (Exception e) { return null; }
    }

    /** All device-list mutations happen on the UI thread. */
    void addFound(BluetoothDevice d, String name, Integer rssi, String source) {
        if (d == null) return;
        final String addr;
        try { addr = d.getAddress(); } catch (Exception e) { return; }
        final String label = ((name == null || name.isEmpty()) ? "(unknown)" : name) + "  " + addr
                + (rssi == null || rssi == 127 ? "" : "  " + rssi + " dBm")
                + (source == null ? "" : "  [" + source + "]");
        h.post(() -> {
            if (!foundAddrs.add(addr)) return;
            found.put(label, d);
            labelOrder.add(label);
            refreshDeviceList();
            log("device " + label);
        });
    }

    /** Watches first, then other bonded devices, then scan-only hits. */
    static int devRank(String label) {
        String u = label.toUpperCase(java.util.Locale.US);
        if (u.contains("NOISEFIT") || u.contains("HALO")) return 0;
        if (u.contains("[BONDED]")) return 1;
        return 2;
    }

    /** Resorts into the adapter and refreshes the count in the section header. */
    void refreshDeviceList() {
        List<String> sorted = new ArrayList<>(labelOrder);
        java.util.Collections.sort(sorted, (a, b) -> {
            int ra = devRank(a), rb = devRank(b);
            return ra != rb ? ra - rb : a.compareToIgnoreCase(b);
        });
        devAdapter.clear();
        devAdapter.addAll(sorted);
        if (devHeader != null) {
            devHeader.setText("1  CONNECT TO WATCH \u00b7 " + foundAddrs.size() + " found"
                    + (scanning ? " \u00b7 scanning\u2026" : ""));
        }
    }

    static String scanError(int code) {
        switch (code) {
            case 1: return "already started";
            case 2: return "application registration failed";
            case 3: return "internal error";
            case 4: return "feature unsupported";
            case 5: return "out of resources";
            case 6: return "scanning too frequently";
            default: return "unknown";
        }
    }

    final ScanCallback scanCb = new ScanCallback() {
        @SuppressLint("MissingPermission") @Override public void onScanResult(int ct, ScanResult r) {
            if (r == null || r.getDevice() == null) return;
            String name = null;
            try { if (r.getScanRecord() != null) name = r.getScanRecord().getDeviceName(); }
            catch (Exception ignored) {}
            if (name == null || name.isEmpty()) name = safeName(r.getDevice());
            addFound(r.getDevice(), name, r.getRssi(), null);
        }

        @SuppressLint("MissingPermission") @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult r : results) {
                if (r == null || r.getDevice() == null) continue;
                String name = null;
                try { if (r.getScanRecord() != null) name = r.getScanRecord().getDeviceName(); }
                catch (Exception ignored) {}
                if (name == null || name.isEmpty()) name = safeName(r.getDevice());
                addFound(r.getDevice(), name, r.getRssi(), null);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            log("scan FAILED code=" + errorCode + " (" + scanError(errorCode) + ")");
            scanning = false;
            h.post(MainActivity.this::refreshDeviceList);
        }
    };

    @SuppressLint("MissingPermission")
    void addBonded(BluetoothAdapter ad) {
        try {
            Set<BluetoothDevice> bonded = ad.getBondedDevices();
            int n = 0;
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    addFound(d, safeName(d), null, "bonded");
                    n++;
                }
            }
            log("bonded devices: " + n);
        } catch (Exception e) {
            log("bonded list error: " + e);
        }
    }

    @SuppressLint("MissingPermission")
    void startScan() {
        if (!hasScanPermissions()) { log("scan blocked, permissions missing"); return; }
        if (scanning) { log("scan already running"); return; }

        found.clear();
        foundAddrs.clear();
        labelOrder.clear();
        devAdapter.clear();

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { log("no bluetooth adapter"); return; }
        if (!ad.isEnabled()) {
            log("bluetooth is OFF - turn it on, then scan again");
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); }
            catch (Exception e) { log("enable prompt failed: " + e); }
            return;
        }
        BluetoothLeScanner sc = ad.getBluetoothLeScanner();
        if (sc == null) { log("BLE scanner unavailable"); return; }

        addBonded(ad);

        final int gen = ++scanGen;
        try {
            ScanSettings st = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            sc.startScan(null, st, scanCb);
            scanning = true;
            refreshDeviceList();
            log("scan started for " + (SCAN_MS / 1000) + "s (" + permissionState() + ")");
            h.postDelayed(() -> {
                if (gen != scanGen || !scanning) return;
                try { sc.stopScan(scanCb); } catch (Exception e) { log("stop scan: " + e); }
                scanning = false;
                refreshDeviceList();
                log("scan over, " + found.size() + " devices");
                if (found.isEmpty()) {
                    log("no BLE devices: keep the watch awake/in pairing mode, force-stop com.noisefit, or use CONNECT BY MAC");
                }
            }, SCAN_MS);
        } catch (SecurityException e) {
            log("scan permission error: " + e.getMessage());
            scanning = false;
        } catch (Exception e) {
            log("scan error: " + e);
            scanning = false;
        }
    }

    @SuppressLint("MissingPermission")
    void connect(BluetoothDevice dev) {
        log("connecting " + dev.getAddress());
        gatt = dev.connectGatt(this, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission") @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("connected (status " + status + "); mtu request");
                g.requestMtu(247);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("disconnected, status=" + status);
                running = false;
            }
        }
        @SuppressLint("MissingPermission") @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
            log("mtu=" + mtu); g.discoverServices();
        }
        @SuppressLint("MissingPermission") @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            for (BluetoothGattService s : g.getServices()) {
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    log("chr " + c.getUuid().toString() + " h=0x" + Integer.toHexString(c.getInstanceId() >> 1) + " p=0x" + Integer.toHexString(c.getProperties()));
                }
            }
            BluetoothGattService cust = null;
            for (BluetoothGattService s : g.getServices())
                if (s.getUuid().toString().toUpperCase().startsWith("16186F00")) { cust = s; break; }
            if (cust == null) { log("cust service NOT FOUND"); return; }
            log("cust service found " + cust.getUuid().toString());
            for (BluetoothGattCharacteristic c : cust.getCharacteristics())
                g.setCharacteristicNotification(c, true);
            for (BluetoothGattCharacteristic c : cust.getCharacteristics()) {
                String u = c.getUuid().toString().toUpperCase();
                if (u.startsWith("16186F01")) chA = c;
                else if (u.startsWith("16186F02")) chB = c;
                else if (u.startsWith("16186F03")) {}
                else if (u.startsWith("16186F04")) chData = c;
                else if (u.startsWith("16186F05")) chSide = c;
            }
            BluetoothGattCharacteristic firstNotify = null;
            for (BluetoothGattCharacteristic c : cust.getCharacteristics()) {
                BluetoothGattDescriptor d = c.getDescriptor(UUID_CCCD);
                if (d != null) { firstNotify = c; break; }
            }
            if (firstNotify != null) {
                BluetoothGattDescriptor d0 = firstNotify.getDescriptor(UUID_CCCD);
                d0.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d0);
                log("cccd enA " + firstNotify.getUuid().toString());
                return;
            }
            loadReplay();
            log("ready, hit REPLAY");
        }
        @SuppressLint("MissingPermission") @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
            log("cccd done " + d.getCharacteristic().getUuid().toString());
            enableNext(g, d);
        }
        @SuppressLint("MissingPermission") @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
            writePending = false;
            h.postDelayed(() -> stepReplay(g), 8);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
            if (v == null) return;
            StringBuilder sx = new StringBuilder();
            for (byte b : v) sx.append(String.format("%02X", b));
            String hv = sx.toString();
            if (hv.equals("000001020000")) {
                waitAck = null; waitGateChar = null;
                log("REJECTED at write #" + replayIdx + " - watch refused the transfer");
                running = false;
                return;
            }
            if (waitAck != null && ackMatch(waitAck, hv) && (waitGateChar == null || isGateChar(c, waitGateChar))) {
                waitAck = null; waitGateChar = null;
                log("ack ok #" + replayIdx + " " + hv);
                h.post(() -> stepReplay(g));
                return;
            }
            if (!hv.equals("000001000000") && !hv.equals("000001010000")) {
                // every other notification is a state/verdict frame from the watch; never
                // throttle these, the post-transfer error code is what we are hunting
                log("evt " + hv + " @" + (int) ((System.currentTimeMillis() - tStart) / 1000) + "s"
                        + (waitAck == null ? "" : " (waiting " + waitAck + ")"));
            }
            // the watch asks for credit on the response channel before delivering a reply
            if (c == chA && hv.equals("000000000100")) {
                autoReplies.add("000001010000");
                h.post(() -> stepReplay(g));
            }
        }
    };

    @SuppressLint("MissingPermission")
    BluetoothGattService findCust(BluetoothGatt g) {
        for (BluetoothGattService s : g.getServices())
            if (s.getUuid().toString().toUpperCase().startsWith("16186F00")) return s;
        return null;
    }

    @SuppressLint("MissingPermission")
    void enableNext(BluetoothGatt g, BluetoothGattDescriptor justWrote) {
        BluetoothGattService s = findCust(g);
        if (s == null) { log("service lost"); return; }
        boolean next = false;
        for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
            BluetoothGattDescriptor desc = c.getDescriptor(UUID_CCCD);
            if (desc == null) continue;
            boolean current = desc.equals(justWrote);
            if (next) {
                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(desc);
                return;
            }
            next = current;
        }
        loadReplay();
        log("all cccd done, hit REPLAY");
    }

    void loadReplay() {
        try {
            InputStream is = getAssets().open("replay3.txt");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            replayLines = new ArrayList<>();
            String l;
            while ((l = br.readLine()) != null) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                replayLines.add(l);
            }
            br.close();
            // the embedded script already starts with the vendor command handshake;
            // there is only one entry gate to worry about: every window of the data
            // stream has to be flow-controlled or the watch rejects the transfer
            replayLines = ReplayBuilder.withDataGates(replayLines);
            int gates = 0;
            for (String s : replayLines) if (s.toUpperCase().startsWith("R 2A")) gates++;
            log("replay lines: " + replayLines.size() + " (" + gates + " data gates)");
        } catch (Exception e) { log("asset err " + e); }
    }

    @SuppressLint("MissingPermission")
    void runReplay() {
        if (chData == null) { log("chars not ready - reconnect first"); return; }
        running = true; replayIdx = 0; paused = false;
        tStart = System.currentTimeMillis();
        log("replay start");
        h.post(() -> stepReplay(gatt));
    }

    boolean ackMatch(String want, String hv) {
        if (want.equalsIgnoreCase(hv)) return true;
        // trailing '*' matches any notification that starts with the given prefix
        if (want.endsWith("*")) return hv.toLowerCase().startsWith(want.substring(0, want.length() - 1).toLowerCase());
        // 6-byte control ACKs are exact only; longer payload notifies carry
        // session-variant bytes (battery/timestamp) -> match length + 4-byte header
        if (want.length() == 12) return false;
        return want.length() == hv.length() && want.regionMatches(true, 0, hv, 0, 8);
    }

    @SuppressLint("MissingPermission")
    void stepReplay(BluetoothGatt g) {
        if (!running || g == null) return;
        if (replayIdx >= replayLines.size()) { running = false; log("replay DONE - keep the app open, watching for the watch verdict"); return; }
        if (waitAck != null || writePending) { h.postDelayed(() -> stepReplay(g), 10); return; }
        if (!autoReplies.isEmpty()) {
            String rep = autoReplies.poll();
            if (chA == null) { autoReplies.clear(); }
            else {
                byte[] val = new byte[rep.length() / 2];
                for (int k = 0; k < val.length; k++) val[k] = (byte) Integer.parseInt(rep.substring(2 * k, 2 * k + 2), 16);
                chA.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                chA.setValue(val);
                writePending = true;
                wrGen++;
                final int wgen = wrGen;
                boolean ok = g.writeCharacteristic(chA);
                if (!ok) { writePending = false; h.postDelayed(() -> stepReplay(g), 20); }
                else h.postDelayed(new Runnable() { public void run() {
                    if (writePending && wrGen == wgen) { writePending = false; stepReplay(gatt); }
                }}, 4000);
                return;
            }
        }
        String line = replayLines.get(replayIdx++);
        if (line.startsWith("#")) { h.post(() -> stepReplay(g)); return; }
        String[] p = line.split(" ");
        String cmd = p[0].toUpperCase();
        String hnd = p[1].toLowerCase();
        // CCCD lines (legacy) handled during setup
        if (hnd.equals("22") || hnd.equals("25") || hnd.equals("28") || hnd.equals("2b") || hnd.equals("2e")) {
            h.post(() -> stepReplay(g));
            return;
        }
        BluetoothGattCharacteristic c = null;
        switch (hnd) {
            case "21": c = chA; break;
            case "24": c = chB; break;
            case "2a": c = chData; break;
            case "2d": c = chSide; break;
        }
        if (c == null) { log("no char for 0x" + hnd); running = false; return; }
        // event-driven: R = wait for exact notification from watch
        if (cmd.equals("R")) {
            ackGen++;
            final int agen = ackGen;
            final String wa = p[2].toLowerCase();
            waitAck = wa; waitGateChar = hnd;
            final int lineNo = replayIdx;
            log("#" + lineNo + " wait " + hnd.toUpperCase() + " <- " + wa.substring(0, Math.min(40, wa.length())) + "...");
            h.postDelayed(new Runnable() { public void run() {
                if (waitAck != null && waitAck.equals(wa) && ackGen == agen) {
                    log("STALL #" + lineNo + ": no " + wa + " in 15s - halting");
                    waitAck = null; waitGateChar = null; running = false;
                }
            }}, 15000);
            return;
        }
        // W = write to watch (no implicit gates; RX lines drive pacing)
        String hex = p[2];
        byte[] val = new byte[hex.length() / 2];
        for (int k = 0; k < val.length; k++) val[k] = (byte) Integer.parseInt(hex.substring(2 * k, 2 * k + 2), 16);
        c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        c.setValue(val);
        writePending = true;
        wrGen++;
        final int wgen = wrGen;
        writePendingDesc = System.nanoTime();
        boolean ok = g.writeCharacteristic(c);
        if (!ok) { log("write failed at " + (replayIdx - 1)); running = false; return; }
        h.postDelayed(new Runnable() { public void run() {
            if (writePending && wrGen == wgen) {
                log("!(write timeout) #" + (replayIdx - 1) + " - resuming");
                writePending = false;
                stepReplay(gatt);
            }
        }}, 4000);
        if (replayIdx % 100 == 0) log("> " + hnd + " " + (hex.length() / 2) + "B #" + replayIdx);
    }
}
