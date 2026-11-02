package com.jay.wfup;

/**
 * LZO1X block codec, no external dependencies.
 *
 * Compressor emits only the instruction forms that this decompressor accepts:
 *   - literal run            : token 0..15 (len-3) or token 0 + zero-extended length
 *   - M3 match               : token 0x40..0xff, 1 extra byte  (offset 1..2048, len 3..8)
 *   - M2 match               : token 0x20..0x3f, 2 extra bytes (offset 1..16384, len 3..33, ext)
 *   - end of stream marker   : 0x11 0x00 0x00
 *
 * Matches are never emitted at a distance > 16384, and each block is 4096 bytes
 * so M4 is not required. The decompressor still implements M4 so it can read
 * streams produced by the vendor encoder.
 */
public final class Lzo1x {

    private Lzo1x() {}

    public static final int MAX_OFFSET_M2 = 0x4000; // 16384

    // ------------------------------------------------------------------
    // decompressor
    // ------------------------------------------------------------------

    /** Returns number of bytes written to dst. */
    public static int decompress(byte[] src, int srcOff, int srcLen,
                                 byte[] dst, int dstOff, int dstMax) {
        if (srcLen < 3) throw new IllegalArgumentException("lzo: short input");
        int ip = srcOff;
        final int ipEnd = srcOff + srcLen;
        int op = dstOff;
        final int opEnd = dstOff + dstMax;
        int state = 0;
        int bv = 0;

        if (srcLen >= 5 && (src[srcOff] & 0xff) == 17) {
            bv = src[srcOff + 1] & 0xff;
            ip += 2;
        }

        if ((src[ip] & 0xff) > 17) {
            int t = (src[ip++] & 0xff) - 17;
            if (t < 4) {
                state = t;
                op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
                ip += t;
            } else {
                op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
                ip += t;
                state = 4;
            }
        }

        while (true) {
            if (ip >= ipEnd) throw new IllegalArgumentException("lzo: input overrun");
            int t = src[ip++] & 0xff;
            int mPos;
            int next;
            boolean copyMatch = false;

            if (t < 16) {
                if (state == 0) {
                    if (t == 0) {
                        int z = 0;
                        while (ip < ipEnd && (src[ip] & 0xff) == 0) { ip++; z++; }
                        if (ip >= ipEnd) throw new IllegalArgumentException("lzo: literal length overrun");
                        int offset = (z << 8) - z;
                        t += offset + 15 + (src[ip++] & 0xff);
                    }
                    t += 3;
                    op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
                    ip += t;
                    state = 4;
                    continue;
                } else if (state != 4) {
                    next = t & 3;
                    mPos = op - 1 - (t >> 2) - ((src[ip++] & 0xff) << 2);
                    if (mPos < dstOff) throw new IllegalArgumentException("lzo: lookbehind");
                    if (op + 2 > opEnd) throw new IllegalArgumentException("lzo: output overrun");
                    dst[op++] = dst[mPos++];
                    dst[op++] = dst[mPos++];
                    state = next;
                    t = next;
                    op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
                    ip += t;
                    continue;
                } else {
                    next = t & 3;
                    mPos = op - (1 + 0x0800) - (t >> 2) - ((src[ip++] & 0xff) << 2);
                    t = 3;
                    copyMatch = true;
                }
            } else if (t >= 64) {
                next = t & 3;
                mPos = op - 1 - ((t >> 2) & 7) - ((src[ip++] & 0xff) << 3);
                t = (t >> 5) + 1;
                copyMatch = true;
            } else if (t >= 32) {
                t = (t & 31) + 2;
                if (t == 2) {
                    int z = 0;
                    while (ip < ipEnd && (src[ip] & 0xff) == 0) { ip++; z++; }
                    if (ip >= ipEnd) throw new IllegalArgumentException("lzo: length overrun");
                    int offset = (z << 8) - z;
                    t += offset + 31 + (src[ip++] & 0xff);
                }
                mPos = op - 1;
                if (ip + 2 > ipEnd) throw new IllegalArgumentException("lzo: offset overrun");
                int v = (src[ip] & 0xff) | ((src[ip + 1] & 0xff) << 8);
                ip += 2;
                mPos -= v >> 2;
                next = v & 3;
                copyMatch = true;
            } else {
                if (ip + 2 > ipEnd) throw new IllegalArgumentException("lzo: offset overrun");
                int v = (src[ip] & 0xff) | ((src[ip + 1] & 0xff) << 8);
                if ((v & 0xfffc) == 0xfffc && (t & 0xf8) == 0x18 && bv != 0) {
                    if (ip + 3 > ipEnd) throw new IllegalArgumentException("lzo: rle overrun");
                    t = (t & 7) | ((src[ip + 2] & 0xff) << 3);
                    t += 4;
                    if (op + t > opEnd) throw new IllegalArgumentException("lzo: output overrun");
                    for (int k = 0; k < t; k++) dst[op++] = 0;
                    next = v & 3;
                    ip += 3;
                    state = next;
                    t = next;
                    op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
                    ip += t;
                    continue;
                }
                mPos = op - ((t & 8) << 11);
                t = (t & 7) + 2;
                if (t == 2) {
                    int z = 0;
                    while (ip < ipEnd && (src[ip] & 0xff) == 0) { ip++; z++; }
                    if (ip >= ipEnd) throw new IllegalArgumentException("lzo: length overrun");
                    int offset = (z << 8) - z;
                    t += offset + 7 + (src[ip++] & 0xff);
                    if (ip + 2 > ipEnd) throw new IllegalArgumentException("lzo: offset overrun");
                    v = (src[ip] & 0xff) | ((src[ip + 1] & 0xff) << 8);
                }
                ip += 2;
                mPos -= v >> 2;
                next = v & 3;
                if (mPos == op) {
                    if (t != 3) throw new IllegalArgumentException("lzo: bad eof marker");
                    return op - dstOff;
                }
                mPos -= 0x4000;
                copyMatch = true;
            }

            if (!copyMatch) throw new IllegalStateException("lzo: state");
            if (mPos < dstOff) throw new IllegalArgumentException("lzo: lookbehind");
            if (op + t > opEnd) throw new IllegalArgumentException("lzo: output overrun");
            for (int k = 0; k < t; k++) dst[op++] = dst[mPos++];
            state = next;
            t = next;
            op = copyLit(src, ip, ipEnd, dst, op, opEnd, t);
            ip += t;
        }
    }

    private static int copyLit(byte[] src, int ip, int ipEnd, byte[] dst, int op, int opEnd, int n) {
        if (n == 0) return op;
        if (ip + n > ipEnd) throw new IllegalArgumentException("lzo: literal overrun");
        if (op + n > opEnd) throw new IllegalArgumentException("lzo: output overrun");
        System.arraycopy(src, ip, dst, op, n);
        return op + n;
    }

    // ------------------------------------------------------------------
    // compressor
    // ------------------------------------------------------------------

    private static final int HASH_BITS = 16;
    private static final int MAX_CHAIN = 64;

    private static int hash(byte[] in, int p) {
        int v = (in[p] & 0xff) | ((in[p + 1] & 0xff) << 8) | ((in[p + 2] & 0xff) << 16);
        return ((v * 0x9E3779B1) >>> (32 - HASH_BITS)) & ((1 << HASH_BITS) - 1);
    }

    private static int matchLen(byte[] in, int a, int b, int max) {
        int n = 0;
        while (n < max && in[a + n] == in[b + n]) n++;
        return n;
    }

    /**
     * Compresses in[inOff .. inOff+inLen) into out at outOff.
     * Caller must supply an output buffer of at least inLen + inLen/8 + 64 bytes.
     * Returns compressed length.
     */
    public static int compress(byte[] in, int inOff, int inLen, byte[] out, int outOff) {
        int op = outOff;
        final int end = inLen;

        int[] head = new int[1 << HASH_BITS];
        java.util.Arrays.fill(head, -1);
        int[] prev = new int[inLen];

        int litStart = 0;
        boolean hasPending = false;
        int pendLen = 0, pendOff = 0;

        int i = 0;
        while (i + 3 <= end) {
            int h = hash(in, inOff + i);
            int cand = head[h];
            prev[i] = cand;
            head[h] = i;

            int bestLen = 0, bestOff = 0;
            int chain = 0;
            while (cand >= 0 && chain < MAX_CHAIN) {
                int off = i - cand;
                if (off > MAX_OFFSET_M2) break;
                if (off > 0) {
                    int l = matchLen(in, inOff + cand, inOff + i, end - i);
                    if (l > bestLen) {
                        bestLen = l;
                        bestOff = off;
                        if (l >= 512) break;
                    }
                }
                cand = prev[cand];
                chain++;
            }

            if (bestLen >= 3) {
                if (!hasPending && i < 4) { i++; continue; }

                int litCount = i - litStart;
                if (hasPending) {
                    if (litCount <= 3) {
                        op = emitMatch(out, op, pendOff, pendLen, litCount);
                        op = emitBytes(in, inOff + litStart, out, op, litCount);
                    } else {
                        op = emitMatch(out, op, pendOff, pendLen, 0);
                        op = emitLiteralRun(in, inOff + litStart, out, op, litCount);
                    }
                } else {
                    op = emitLiteralRun(in, inOff + litStart, out, op, litCount);
                }

                pendLen = bestLen;
                pendOff = bestOff;
                hasPending = true;

                for (int k = i + 1; k < i + bestLen; k++) {
                    if (k + 3 > end) break;
                    int hh = hash(in, inOff + k);
                    prev[k] = head[hh];
                    head[hh] = k;
                }
                i += bestLen;
                litStart = i;
            } else {
                i++;
            }
        }

        int litCount = end - litStart;
        if (hasPending) {
            if (litCount <= 3) {
                op = emitMatch(out, op, pendOff, pendLen, litCount);
                op = emitBytes(in, inOff + litStart, out, op, litCount);
            } else {
                op = emitMatch(out, op, pendOff, pendLen, 0);
                op = emitLiteralRun(in, inOff + litStart, out, op, litCount);
            }
        } else {
            op = emitLiteralRun(in, inOff, out, op, end);
        }

        out[op++] = 0x11;
        out[op++] = 0x00;
        out[op++] = 0x00;
        return op - outOff;
    }

    private static int emitBytes(byte[] in, int inPos, byte[] out, int op, int n) {
        for (int k = 0; k < n; k++) out[op++] = in[inPos + k];
        return op;
    }

    /** Literal run, n >= 4. */
    private static int emitLiteralRun(byte[] in, int inPos, byte[] out, int op, int n) {
        if (n <= 18) {
            out[op++] = (byte) (n - 3);
        } else {
            out[op++] = 0;
            int d = n - 18;
            int z = d / 255;
            int b = d % 255;
            if (b == 0) { z--; b = 255; }
            for (int k = 0; k < z; k++) out[op++] = 0;
            out[op++] = (byte) b;
        }
        return emitBytes(in, inPos, out, op, n);
    }

    /** Match with 1 <= off <= 16384 and len >= 3, lit in 0..3. */
    private static int emitMatch(byte[] out, int op, int off, int len, int lit) {
        if (off <= 2048 && len <= 8) {
            int token = ((len - 1) << 5) | (((off - 1) & 7) << 2) | lit;
            out[op++] = (byte) token;
            out[op++] = (byte) ((off - 1) >> 3);
            return op;
        }
        if (len <= 33) {
            out[op++] = (byte) (32 | (len - 2));
        } else {
            out[op++] = 32;
            int d = len - 33;
            int z = d / 255;
            int b = d % 255;
            if (b == 0) { z--; b = 255; }
            for (int k = 0; k < z; k++) out[op++] = 0;
            out[op++] = (byte) b;
        }
        int v = ((off - 1) << 2) | lit;
        out[op++] = (byte) (v & 0xff);
        out[op++] = (byte) ((v >> 8) & 0xff);
        return op;
    }
}
