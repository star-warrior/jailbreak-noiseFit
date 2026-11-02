package com.jay.wfup;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.veryfit.multi.nativeprotocol.Protocol;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Harness v7: offline face compiler with minimal package directory fix */
public class WFCompilerRunner {
    public interface LogFn { void log(String s); }

    static StringBuilder hex(File f, int n) {
        StringBuilder sb = new StringBuilder();
        byte[] h = new byte[n];
        int r = 0;
        try (FileInputStream fi = new FileInputStream(f)) { r = fi.read(h); } catch (Exception ignored) {}
        for (int i = 0; i < r; i++) sb.append(String.format("%02X", h[i]));
        return sb;
    }

    /** Create minimal package directory with placeholder files */
    static boolean setupPackageDir(File dir) {
        try {
            // Create the directory
            if (!dir.mkdirs() && !dir.exists()) return false;
            
            // Create placeholder iwf.json
            File iwfJson = new File(dir, "iwf.json");
            if (!iwfJson.exists()) {
                try (FileOutputStream fos = new FileOutputStream(iwfJson)) {
                    fos.write("{\"frames\":[],\"fonts\":[]}".getBytes(StandardCharsets.UTF_8));
                }
            }
            
            // Create placeholder font directory
            File fontDir = new File(dir, "fonts");
            fontDir.mkdirs();
            // Create a placeholder font file
            File fontFile = new File(fontDir, "font.bmp");
            if (!fontFile.exists()) {
                try (FileOutputStream fos = new FileOutputStream(fontFile)) {
                    // Minimal BMP data - just enough to avoid null pointer
                    byte[] placeholder = new byte[16];
                    Arrays.fill(placeholder, (byte) 0);
                    fos.write(placeholder);
                }
            }
            
            // Create placeholder preview
            File previewDir = new File(dir, "preview");
            previewDir.mkdirs();
            File previewFile = new File(previewDir, "preview.png");
            if (!previewFile.exists()) {
                try (FileOutputStream fos = new FileOutputStream(previewFile)) {
                    // Minimal PNG data
                    fos.write(new byte[0]);
                }
            }
            
            return true;
        } catch (Exception e) {
            Log.e("WFCompiler", "setupPackageDir error: " + e.getMessage());
            return false;
        }
    }

    /** Create a 466x466 test PNG (green border + gradient) */
    static File createTestPng(File dir, String name, LogFn log) {
        File png = new File(dir, name);
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(466, 466, android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 466; y++) {
                for (int x = 0; x < 466; x++) {
                    int color;
                    if (x < 10 || y < 10 || x >= 456 || y >= 456) {
                        color = Color.GREEN; // border
                    } else {
                        int v = ((x + y) % 60) * 4;
                        color = android.graphics.Color.rgb(v, v, 255 - v);
                    }
                    bmp.setPixel(x, y, color);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(png)) {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
            }
            bmp.recycle();
            log.log("created PNG " + png.getAbsolutePath() + " " + png.length() + " bytes");
            return png;
        } catch (Throwable t) {
            log.log("PNG create failed: " + t);
            return null;
        }
    }

    /** Drive makeFileCompression(JSON{fileName,endName,pic_num,format}) */
    public static void run(LogFn log, File filesDir) {
        new Thread(() -> {
            try {
                log.log("loading VeryFitMulti…");
                System.loadLibrary("VeryFitMulti");
                Protocol p = Protocol.getInstance();
                log.log("loaded ok");
              
                int r = p.setFilePath(filesDir.getAbsolutePath().getBytes());
                log.log("setFilePath ret=" + r);
                
                try { p.initParameter(); log.log("initParameter ok"); } catch (Throwable ignored) {}
                try { p.initType(2); log.log("initType ok"); } catch (Throwable ignored) {}
                
                // 1) Setup minimal package directory (fixes SIGSEGV)
                File workDir = new File(filesDir, "watchface_compiler");
                if (setupPackageDir(workDir)) {
                    log.log("package dir setup: " + workDir.getAbsolutePath());
                } else {
                    log.log("WARNING: could not setup package dir, trying without");
                }
                
                // 2) Create a real PNG test image
                File srcPng = createTestPng(workDir, "test_in.png", log);
                if (srcPng == null) { log.log("no PNG, abort"); return; }
                
                File dst = new File(workDir, "out_test.lz");
                if (dst.exists()) dst.delete();
                
                String json = "{\"fileName\":\"" + srcPng.getAbsolutePath()
                            + "\",\"endName\":\"" + dst.getAbsolutePath()
                            + "\",\"pic_num\":0,\"format\":5}";
                log.log("mfc-json: " + json);
                int r1 = p.makeFileCompression(json);
                log.log("  ret=" + r1);
                
                for (File f : workDir.listFiles()) {
                    log.log("  file " + f.getName() + " " + f.length());
                    if (f.getName().endsWith(".lz")) {
                        log.log("  head: " + hex(f, 24));
                    }
                }
                
                // test other formats
                for (int fmt : new int[]{0, 1, 2, 3, 4}) {
                    File dst2 = new File(workDir, "out_f" + fmt + ".lz");
                    if (dst2.exists()) dst2.delete();
                    String j2 = "{\"fileName\":\"" + srcPng.getAbsolutePath()
                                + "\",\"endName\":\"" + dst2.getAbsolutePath()
                                + "\",\"pic_num\":0,\"format\":" + fmt + "}";
                    int r2 = p.makeFileCompression(j2);
                    log.log("fmt=" + fmt + " ret=" + r2 + " out=" + (dst2.exists() ? dst2.length() : -1));
                    if (dst2.exists()) log.log("  head: " + hex(dst2, 16));
                }
                
            } catch (Throwable t) {
                log.log("ERR " + Log.getStackTraceString(t));
            }
        }, "wfcomp").start();
    }
}