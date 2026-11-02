"""LZO1X block codec, pure Python, no dependencies.

Mirror of the Java implementation in
``uploader/app/src/main/java/com/jay/wfup/Lzo1x.java`` so the PC tooling and the
Android app produce and accept exactly the same bytes.

The compressor emits only literal runs, M3 matches (offset <= 2048, len 3..8),
M2 matches (offset <= 16384, len 3..33 + extension) and the EOF marker
``11 00 00``. The decompressor implements M4 as well, because the vendor encoder
uses it.
"""

EOF_MARKER = b"\x11\x00\x00"
MAX_OFFSET_M2 = 0x4000


# ----------------------------------------------------------------------------
# decompressor
# ----------------------------------------------------------------------------

def decompress(src):
    """Decompress one LZO1X block. Raises ValueError on malformed input."""
    src = bytes(src)
    n = len(src)
    if n < 3:
        raise ValueError("lzo: short input")
    dst = bytearray()
    ip = 0
    state = 0
    bv = 0

    if n >= 5 and src[0] == 17:
        bv = src[1]
        ip = 2

    if src[ip] > 17:
        t = src[ip] - 17
        ip += 1
        if t < 4:
            state = t
            dst += src[ip:ip + t]
            ip += t
        else:
            dst += src[ip:ip + t]
            ip += t
            state = 4

    while True:
        if ip >= n:
            raise ValueError("lzo: input overrun")
        t = src[ip]
        ip += 1
        copy_match = False
        mpos = 0

        if t < 16:
            if state == 0:
                if t == 0:
                    z = 0
                    while ip < n and src[ip] == 0:
                        ip += 1
                        z += 1
                    if ip >= n:
                        raise ValueError("lzo: literal length overrun")
                    t += (z << 8) - z + 15 + src[ip]
                    ip += 1
                t += 3
                if ip + t > n:
                    raise ValueError("lzo: literal overrun")
                dst += src[ip:ip + t]
                ip += t
                state = 4
                continue
            elif state != 4:
                nxt = t & 3
                mpos = len(dst) - 1 - (t >> 2) - (src[ip] << 2)
                ip += 1
                if mpos < 0:
                    raise ValueError("lzo: lookbehind")
                dst.append(dst[mpos])
                dst.append(dst[mpos + 1])
                state = nxt
                if ip + nxt > n:
                    raise ValueError("lzo: literal overrun")
                dst += src[ip:ip + nxt]
                ip += nxt
                continue
            else:
                nxt = t & 3
                mpos = len(dst) - (1 + 0x0800) - (t >> 2) - (src[ip] << 2)
                ip += 1
                t = 3
                copy_match = True
        elif t >= 64:
            nxt = t & 3
            mpos = len(dst) - 1 - ((t >> 2) & 7) - (src[ip] << 3)
            ip += 1
            t = (t >> 5) + 1
            copy_match = True
        elif t >= 32:
            t = (t & 31) + 2
            if t == 2:
                z = 0
                while ip < n and src[ip] == 0:
                    ip += 1
                    z += 1
                if ip >= n:
                    raise ValueError("lzo: length overrun")
                t += (z << 8) - z + 31 + src[ip]
                ip += 1
            if ip + 2 > n:
                raise ValueError("lzo: offset overrun")
            v = src[ip] | (src[ip + 1] << 8)
            ip += 2
            mpos = len(dst) - 1 - (v >> 2)
            nxt = v & 3
            copy_match = True
        else:
            if ip + 2 > n:
                raise ValueError("lzo: offset overrun")
            v = src[ip] | (src[ip + 1] << 8)
            if (v & 0xFFFC) == 0xFFFC and (t & 0xF8) == 0x18 and bv != 0:
                if ip + 3 > n:
                    raise ValueError("lzo: rle overrun")
                t = (t & 7) | (src[ip + 2] << 3)
                t += 4
                dst += b"\x00" * t
                nxt = v & 3
                ip += 3
                state = nxt
                if ip + nxt > n:
                    raise ValueError("lzo: literal overrun")
                dst += src[ip:ip + nxt]
                ip += nxt
                continue
            mpos = len(dst) - ((t & 8) << 11)
            t = (t & 7) + 2
            if t == 2:
                z = 0
                while ip < n and src[ip] == 0:
                    ip += 1
                    z += 1
                if ip >= n:
                    raise ValueError("lzo: length overrun")
                t += (z << 8) - z + 7 + src[ip]
                ip += 1
                if ip + 2 > n:
                    raise ValueError("lzo: offset overrun")
                v = src[ip] | (src[ip + 1] << 8)
            ip += 2
            mpos -= v >> 2
            nxt = v & 3
            if mpos == len(dst):
                if t != 3:
                    raise ValueError("lzo: bad eof marker")
                return bytes(dst)
            mpos -= 0x4000
            copy_match = True

        if not copy_match:
            raise ValueError("lzo: bad state")
        if mpos < 0:
            raise ValueError("lzo: lookbehind")
        for _ in range(t):
            dst.append(dst[mpos])
            mpos += 1
        state = nxt
        if ip + nxt > n:
            raise ValueError("lzo: literal overrun")
        dst += src[ip:ip + nxt]
        ip += nxt


# ----------------------------------------------------------------------------
# compressor
# ----------------------------------------------------------------------------

_HASH_BITS = 16
_HASH_MASK = (1 << _HASH_BITS) - 1
_MAX_CHAIN = 64


def _hash(buf, p):
    v = buf[p] | (buf[p + 1] << 8) | (buf[p + 2] << 16)
    return ((v * 0x9E3779B1) & 0xFFFFFFFF) >> (32 - _HASH_BITS) & _HASH_MASK


def _emit_literal(out, buf, pos, n):
    if n <= 18:
        out.append(n - 3)
    else:
        out.append(0)
        d = n - 18
        z, b = divmod(d, 255)
        if b == 0:
            z -= 1
            b = 255
        out.extend(b"\x00" * z)
        out.append(b)
    out.extend(buf[pos:pos + n])


def _emit_match(out, off, length, lit):
    if off <= 2048 and length <= 8:
        out.append(((length - 1) << 5) | (((off - 1) & 7) << 2) | lit)
        out.append((off - 1) >> 3)
        return
    if length <= 33:
        out.append(32 | (length - 2))
    else:
        out.append(32)
        d = length - 33
        z, b = divmod(d, 255)
        if b == 0:
            z -= 1
            b = 255
        out.extend(b"\x00" * z)
        out.append(b)
    v = ((off - 1) << 2) | lit
    out.append(v & 0xFF)
    out.append((v >> 8) & 0xFF)


def compress(buf):
    """Compress one block (4096 bytes or the remainder) and return bytes."""
    buf = bytes(buf)
    end = len(buf)
    out = bytearray()
    if end == 0:
        return EOF_MARKER

    head = [-1] * (1 << _HASH_BITS)
    prev = [-1] * end

    lit_start = 0
    has_pending = False
    pend_len = 0
    pend_off = 0

    i = 0
    while i + 3 <= end:
        h = _hash(buf, i)
        cand = head[h]
        prev[i] = cand
        head[h] = i

        best_len = 0
        best_off = 0
        chain = 0
        while cand >= 0 and chain < _MAX_CHAIN:
            off = i - cand
            if off > MAX_OFFSET_M2:
                break
            if off > 0:
                max_len = end - i
                l = 0
                while l < max_len and buf[cand + l] == buf[i + l]:
                    l += 1
                if l > best_len:
                    best_len = l
                    best_off = off
                    if l >= 512:
                        break
            cand = prev[cand]
            chain += 1

        if best_len >= 3:
            if not has_pending and i < 4:
                i += 1
                continue

            lit_count = i - lit_start
            if has_pending:
                if lit_count <= 3:
                    _emit_match(out, pend_off, pend_len, lit_count)
                    out.extend(buf[lit_start:lit_start + lit_count])
                else:
                    _emit_match(out, pend_off, pend_len, 0)
                    _emit_literal(out, buf, lit_start, lit_count)
            else:
                _emit_literal(out, buf, lit_start, lit_count)

            pend_len = best_len
            pend_off = best_off
            has_pending = True

            k = i + 1
            while k < i + best_len:
                if k + 3 > end:
                    break
                hh = _hash(buf, k)
                prev[k] = head[hh]
                head[hh] = k
                k += 1
            i += best_len
            lit_start = i
        else:
            i += 1

    lit_count = end - lit_start
    if has_pending:
        if lit_count <= 3:
            _emit_match(out, pend_off, pend_len, lit_count)
            out.extend(buf[lit_start:lit_start + lit_count])
        else:
            _emit_match(out, pend_off, pend_len, 0)
            _emit_literal(out, buf, lit_start, lit_count)
    else:
        _emit_literal(out, buf, 0, end)

    out += EOF_MARKER
    return bytes(out)
