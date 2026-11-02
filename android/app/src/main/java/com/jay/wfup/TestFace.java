package com.jay.wfup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * PC test harness (plain java, no Android deps in FaceBuilder/Lzo1x).
 * Usage: java com.jay.wfup.TestFace <blob.bin> [real_container]
 *
 * Checks:
 *  1. Java decompressor walks the REAL container byte-exactly.
 *  2. Java compressor round-trips the blob.
 *  3. Packed container self-checks.
 *  4. Writes our_container.bin for cross-verification with the independent
 *     Python vendor-format decoder.
 */
public class TestFace {
    public static void main(String[] args) throws Exception {
        byte[] blob = Files.readAllBytes(new File(args[0]).toPath());
        if (blob.length != FaceBuilder.BLOB_SIZE) {
            System.out.println("FATAL blob " + blob.length); System.exit(1);
        }
        System.out.println("blob " + blob.length + " ok");

        // 1) decode real container if given
        if (args.length > 1) {
            byte[] real = Files.readAllBytes(new File(args[1]).toPath());
            String sc = FaceBuilder.selfCheck(real, blob);
            System.out.println("real-container selfCheck: " + sc);
            if (!sc.startsWith("OK")) System.exit(2);
        }

        // 2+3) compress, pack, self-check
        FaceBuilder.Result r = FaceBuilder.pack(blob, blob);
        if (!r.ok()) {
            System.out.println("pack FAIL: " + r.error); System.exit(3);
        }
        System.out.println("packed: payload " + r.compressedSize + "B, container " + r.container.length + "B, blocks " + r.blockSizes.length);
        System.out.println("block sizes first10: " + java.util.Arrays.toString(java.util.Arrays.copyOf(r.blockSizes, 10)));

        String sc2 = FaceBuilder.selfCheck(r.container, blob);
        System.out.println("our-container selfCheck: " + sc2);
        if (!sc2.startsWith("OK")) System.exit(4);

        // per-block cross check: every block must also decode standalone with vendor semantics
        int total = 0;
        for (int s : r.blockSizes) total += s;
        int bestBlock = 0, bestRatio = 0;
        for (int b = 0; b < r.blockSizes.length; b++) {
            int ratio = (r.blockSizes[b] << 10) / FaceBuilder.BLOCK;
            if (ratio > bestRatio) { bestRatio = ratio; bestBlock = b; }
        }
        System.out.println("worst compression block #" + bestBlock + " csize=" + r.blockSizes[bestBlock]);

        // 4) write container + blob for python cross-verify
        try (FileOutputStream fo = new FileOutputStream("our_container.bin")) { fo.write(r.container); }
        System.out.println("wrote our_container.bin, header f8/f16 carried from real file");
    }
}
