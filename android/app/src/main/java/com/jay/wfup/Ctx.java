package com.jay.wfup;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;

import com.veryfit.multi.nativeprotocol.Protocol;

/** app_process CLI entry: java Ctx <pic_num> <format> <fileName> <endName> */
public class Ctx {
    public static void main(String[] args) {
        try {
            String base = "/data/local/tmp";
            int pic = args.length > 0 ? Integer.parseInt(args[0]) : 0;
            int fmt = args.length > 1 ? Integer.parseInt(args[1]) : 5;
            String fName = args.length > 2 ? args[2] : base + "/test_in.bin";
            String eName = args.length > 3 ? args[3] : base + "/out_cl.lz";
            System.load(base + "/libVeryFitMulti.so");
            System.out.println("lib loaded");
            Protocol p = Protocol.getInstance();
            byte[] raw = new byte[754880];
            for (int y = 0; y < 466; y++) for (int x = 0; x < 466; x++) {
                int v = ((x + y) % 33 == 0) ? 0xFFFF : 0x1F1F;
                raw[(y * 466 + x) * 2] = (byte)(v & 0xFF);
                raw[(y * 466 + x) * 2 + 1] = (byte)(v >> 8);
            }
            File src = new File(fName);
            try (FileOutputStream fo = new FileOutputStream(src)) { fo.write(raw); }
            File dst = new File(eName);
            if (dst.exists()) dst.delete();
            String json = "{\"fileName\":\"" + fName + "\",\"endName\":\"" + eName
                    + "\",\"pic_num\":" + pic + ",\"format\":" + fmt + "}";
            System.out.println("json=" + json);
            int r = p.makeFileCompression(json);
            System.out.println("ret=" + r);
            for (File f : new File(base).listFiles()) {
                System.out.println("  " + f.getName() + " " + f.length());
            }
            if (dst.exists()) {
                System.out.println("head=" + hex(dst, 40));
                System.out.println("tail=" + hexTail(dst, 16));
                System.out.println("size=" + dst.length());
            } else System.out.println("no output");
            System.out.println("OK done");
        } catch (Throwable t) {
            System.out.println("ERR " + t);
        }
    }
    static String hex(File f, int n) {
        StringBuilder sb = new StringBuilder();
        try { FileInputStream fi = new FileInputStream(f); byte[] b = new byte[n]; int r1 = fi.read(b); fi.close();
            for (int i = 0; i < r1; i++) sb.append(String.format("%02X", b[i])); } catch (Exception ignored) {}
        return sb.toString();
    }
    static String hexTail(File f, int n) {
        StringBuilder sb = new StringBuilder();
        try { long le = f.length(); FileInputStream fi = new FileInputStream(f); fi.skip(Math.max(0, le - n));
            byte[] b = new byte[n]; int r1 = fi.read(b); fi.close();
            for (int i = 0; i < r1; i++) sb.append(String.format("%02X", b[i])); } catch (Exception ignored) {}
        return sb.toString();
    }
}
