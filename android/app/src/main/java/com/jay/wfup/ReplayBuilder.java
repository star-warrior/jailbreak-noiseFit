package com.jay.wfup;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the BLE write sequence that transfers a finished watch-face container to
 * the watch, byte for byte the way the vendor app does it.
 *
 * Recovered by fully reconstructing the real capture in assets/replay.txt: every
 * byte of the container plus its 4 byte tail was reproduced exactly, so the
 * framing below is measured, not guessed. The tail itself comes from
 * ThemeManager.initUpload: zlib CRC32 over the 22 byte prefix and the file.
 *
 * Transfer layout
 * ---------------
 * The payload stream is  container || <4 byte CRC32>.
 *
 * Each "window" is announced by a 6 byte marker and then carries up to 22 chunks
 * of 177 bytes:
 *
 *   marker  : 00 00 00 00 <chunkCount u16 LE>
 *   chunk 1 : <seq u16 LE> 0E 00 <winIdx u16 LE> <12 byte field> [10 byte meta] <data>
 *   chunk N : <seq u16 LE> <data>
 *
 * Window 1 is special: its 12 byte field is the constant 00 10 00.. and it carries
 * a 10 byte meta block before the data (6 zero bytes + u32 LE container length).
 * Its first chunk therefore holds 149 bytes and the window holds 3824 bytes.
 *
 * Every later window puts the first 12 bytes of its own payload into the field
 * slot, then 159 bytes in chunk 1 and 175 bytes in each following chunk, so the
 * window holds 3846 bytes.
 *
 * The last window is simply the remainder and may be a single chunk.
 */
public final class ReplayBuilder {

    private ReplayBuilder() {}

    /** Size of one BLE write. Matches the 177 byte notifications seen on the wire. */
    public static final int CHUNK = 177;
    /** Bytes available for payload in a continuation chunk (after the 2 byte seq). */
    public static final int DATA_MAX = 175;
    /** Bytes available for payload in chunk 1 of window 1 (after 18 byte header + 10 byte meta). */
    public static final int WIN1_DATA = 149;
    /** Bytes available for payload in chunk 1 of any later window (after 18 byte header). */
    public static final int WIN_DATA = 159;
    /** Hard cap on chunks per window; the vendor app never exceeds this. */
    public static final int MAX_CHUNKS = 22;
    /** Fixed field carried by window 1. */
    public static final byte[] WIN1_FIELD = {0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    /** Bytes per window, measured: 22 byte prefix + 21*175 on the first one. */
    public static final int PIECE_BYTES = 3846;
    /** Bytes the vendor adds around the container: 22 byte prefix + 4 byte CRC. */
    public static final int WRAP_BYTES = 26;

    /**
     * The 22 byte prefix the vendor puts in front of the file, from
     * ThemeManager.initUpload: type byte, 17 zero bytes, u32 LE file length.
     */
    public static byte[] prefix(int containerLength) {
        byte[] p = new byte[22];
        p[1] = 16; // "watch"
        p[18] = (byte) (containerLength & 0xff);
        p[19] = (byte) ((containerLength >> 8) & 0xff);
        p[20] = (byte) ((containerLength >> 16) & 0xff);
        p[21] = (byte) ((containerLength >> 24) & 0xff);
        return p;
    }

    /**
     * The 4 byte tail appended after the container: zlib CRC32 of (prefix + container),
     * stored little endian. Taken verbatim from ThemeManager.initUpload, which does
     * CRC32 over the 22 byte prefix concatenated with the file.
     */
    public static byte[] trailer(byte[] container) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(prefix(container.length), 0, 22);
        crc.update(container, 0, container.length);
        long v = crc.getValue();
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    /**
     * Command exchange the vendor app performs immediately before the data stream.
     *
     * Without it the watch answers every 0x2A write with 00 00 01 02 00 00 and never
     * shows its transfer screen. With it the watch answers 00 00 01 01 00 00 to the
     * window marker and 00 00 01 00 00 00 when a window is complete.
     *
     * Handle 0x24 is the app to watch command channel, 0x21 the watch to app response
     * channel. Every message is preceded by a credit exchange: the sender writes
     * 00 00 00 00 01 00, the peer answers 00 00 01 01 00 00, and the message is
     * acknowledged with 00 00 01 00 00 00.
     *
     * The message itself is 01 00 08 followed by a protobuf body. Body 53 3A means
     * "transfer this face"; its field 1 is the face id as a string (916 is the id of
     * the face this container is derived from), fields 2 and 3 are 50 and field 4 is
     * 1. The watch answers with body 53 3A 04 2A 02 08 00.
     *
     * Recovered from new_face_snoop.log. The same capture contains a failed transfer
     * that got 00 00 01 02 00 00 for every write, which is exactly what this app used
     * to get before the preamble was added.
     */
    public static final String[] PREAMBLE = {
            "W 24 000000000100",
            "R 24 000001010000",
            "W 24 010008533A0D220B0A03393136103218322001",
            "R 24 000001000000",
            "R 21 000000000100",
            "W 21 000001010000",
            "R 21 01000853*",
            "W 21 000001000000",
    };

    /** Lines to write before the data stream. R lines wait for a watch notification. */
    public static List<String> preamble() {
        return new ArrayList<>(java.util.Arrays.asList(PREAMBLE));
    }

    /**
     * Same transfer as {@link #build}, but as replay lines with flow control.
     *
     * The watch is not a dumb pipe: after the window marker it answers
     * 00 00 01 01 00 00 and expects the chunks only then, and after the last chunk
     * of a window it answers 00 00 01 00 00 00. A capture of the vendor app sending
     * without waiting for those ACKs is rejected with 00 00 01 02 00 00 on every
     * write, so every window is gated here.
     */
    public static List<String> buildScript(byte[] container) {
        byte[] trailer = trailer(container);
        byte[] stream = new byte[container.length + trailer.length];
        System.arraycopy(container, 0, stream, 0, container.length);
        System.arraycopy(trailer, 0, stream, container.length, trailer.length);

        // dataPieceTotalPieceLength in ThemeManager: ceil((file + 26) / 3846)
        int totalPieces = (container.length + WRAP_BYTES + PIECE_BYTES - 1) / PIECE_BYTES;

        List<String> out = new ArrayList<>();
        int pos = 0;
        int winIdx = 1;

        while (pos < stream.length) {
            List<byte[]> chunks = new ArrayList<>();
            int seq = 1;

            byte[] field;
            int cap;
            boolean firstWindow = (winIdx == 1);
            if (firstWindow) {
                field = WIN1_FIELD.clone();
                cap = WIN1_DATA;
            } else {
                field = new byte[12];
                int take = Math.min(12, stream.length - pos);
                System.arraycopy(stream, pos, field, 0, take);
                pos += take;
                cap = WIN_DATA;
            }

            int n = Math.min(cap, stream.length - pos);
            ByteArrayOutputStream c1 = new ByteArrayOutputStream(CHUNK);
            writeU16(c1, seq++);
            writeU16(c1, totalPieces);
            writeU16(c1, winIdx);
            c1.write(field, 0, 12);
            if (firstWindow) {
                for (int i = 0; i < 6; i++) c1.write(0);
                writeU32(c1, container.length);
            }
            c1.write(stream, pos, n);
            pos += n;
            chunks.add(c1.toByteArray());

            while (pos < stream.length && chunks.size() < MAX_CHUNKS) {
                int m = Math.min(DATA_MAX, stream.length - pos);
                byte[] c = new byte[2 + m];
                c[0] = (byte) (seq & 0xff);
                c[1] = (byte) ((seq >> 8) & 0xff);
                seq++;
                System.arraycopy(stream, pos, c, 2, m);
                pos += m;
                chunks.add(c);
            }

            byte[] marker = new byte[6];
            marker[4] = (byte) (chunks.size() & 0xff);
            marker[5] = (byte) ((chunks.size() >> 8) & 0xff);

            out.add("W 2a " + hex(marker));
            out.add("R 2a 000001010000");
            for (byte[] c : chunks) out.add("W 2a " + hex(c));
            out.add("R 2a 000001000000");

            winIdx++;
        }

        return out;
    }

    /** Gate line the watch answers right after a window marker. */
    public static final String DATA_GO = "R 2a 000001010000";
    /** Gate line the watch answers when a window's last chunk has been taken. */
    public static final String DATA_WINDOW_DONE = "R 2a 000001000000";

    /**
     * Adds the per-window flow control gates to an existing replay line list.
     *
     * Used for the embedded asset replay, which only holds the 0x2A writes: every
     * marker is followed by a wait for 00 00 01 01 00 00, and the last chunk of the
     * announced window by a wait for 00 00 01 00 00 00, exactly like {@link #buildScript}.
     */
    public static List<String> withDataGates(List<String> lines) {
        for (String line : lines) {
            String[] q = line.split(" ");
            if (q.length >= 2 && q[0].equalsIgnoreCase("R") && q[1].equalsIgnoreCase("2a")) {
                return new ArrayList<>(lines); // already gated
            }
        }
        List<String> out = new ArrayList<>(lines.size() + lines.size() / 8 + 8);
        int pending = 0;
        for (String line : lines) {
            out.add(line);
            String[] p = line.split(" ");
            if (p.length < 3 || !p[0].equalsIgnoreCase("W") || !p[1].equalsIgnoreCase("2a")) continue;
            String hv = p[2];
            if (hv.length() == 12 && hv.startsWith("00000000")) {
                int lo = Integer.parseInt(hv.substring(8, 10), 16);
                int hi = Integer.parseInt(hv.substring(10, 12), 16);
                pending = lo | (hi << 8);
                out.add(DATA_GO);
            } else if (pending > 0) {
                pending--;
                if (pending == 0) out.add(DATA_WINDOW_DONE);
            }
        }
        return out;
    }

    /**
     * Returns the raw writes for handle 0x2A in order: marker, chunks, marker, chunks...
     * Each element is one GATT write.
     */
    public static List<byte[]> build(byte[] container) {
        byte[] trailer = trailer(container);
        byte[] stream = new byte[container.length + trailer.length];
        System.arraycopy(container, 0, stream, 0, container.length);
        System.arraycopy(trailer, 0, stream, container.length, trailer.length);

        int totalPieces = (container.length + WRAP_BYTES + PIECE_BYTES - 1) / PIECE_BYTES;

        List<byte[]> out = new ArrayList<>();
        int pos = 0;
        int winIdx = 1;

        while (pos < stream.length) {
            List<byte[]> chunks = new ArrayList<>();
            int seq = 1;

            byte[] field;
            int cap;
            boolean firstWindow = (winIdx == 1);
            if (firstWindow) {
                field = WIN1_FIELD.clone();
                cap = WIN1_DATA;
            } else {
                field = new byte[12];
                int take = Math.min(12, stream.length - pos);
                System.arraycopy(stream, pos, field, 0, take);
                pos += take;
                cap = WIN_DATA;
            }

            // chunk 1
            int n = Math.min(cap, stream.length - pos);
            ByteArrayOutputStream c1 = new ByteArrayOutputStream(CHUNK);
            writeU16(c1, seq++);
            writeU16(c1, totalPieces);
            writeU16(c1, winIdx);
            c1.write(field, 0, 12);
            if (firstWindow) {
                // 6 zero bytes then the container length as u32 LE (trailer not counted)
                for (int i = 0; i < 6; i++) c1.write(0);
                writeU32(c1, container.length);
            }
            c1.write(stream, pos, n);
            pos += n;
            chunks.add(c1.toByteArray());

            // continuation chunks
            while (pos < stream.length && chunks.size() < MAX_CHUNKS) {
                int m = Math.min(DATA_MAX, stream.length - pos);
                byte[] c = new byte[2 + m];
                c[0] = (byte) (seq & 0xff);
                c[1] = (byte) ((seq >> 8) & 0xff);
                seq++;
                System.arraycopy(stream, pos, c, 2, m);
                pos += m;
                chunks.add(c);
            }

            // marker announcing how many chunks follow
            byte[] marker = new byte[6];
            marker[4] = (byte) (chunks.size() & 0xff);
            marker[5] = (byte) ((chunks.size() >> 8) & 0xff);
            out.add(marker);
            out.addAll(chunks);

            winIdx++;
        }

        return out;
    }

    /** Total bytes that will go over the air, including markers and per-chunk headers. */
    public static int wireBytes(List<byte[]> writes) {
        int t = 0;
        for (byte[] w : writes) t += w.length;
        return t;
    }

    /** Largest container that still fits the transfer (kept for the size gate message). */
    public static int windowCount(int streamLen) {
        int windows = 1;
        int remaining = streamLen - 3824;
        while (remaining > 0) { remaining -= 3846; windows++; }
        return windows;
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
    }

    private static void writeU32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    public static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xff));
        return sb.toString();
    }
}
