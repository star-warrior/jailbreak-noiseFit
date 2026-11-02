package com.jay.wfup;

/**
 * The vendor checksum used in watch-face container fields 8 and 16.
 *
 * Recovered from com.zhapp.ble.utils.lzo.LzoCrcUtils in classes10.dex:
 *
 *   long crc = Long.valueOf("ffffffff", 16);
 *   for (byte b : data) crc = table[((int) crc ^ b) & 0xFF] ^ ((crc >> 8) & 0xFFFFFF);
 *   return LE(~crc)
 *
 * The table is NOT a polynomial CRC table (entry 0 is 0x60454269, not 0), so no
 * standard CRC parameter set reproduces it. It is a vendor-specific 256 entry
 * mixing table and is reproduced here verbatim.
 */
public final class LzoCrc32 {

    private LzoCrc32() {}

    private static final int[] T = {
        (int)0x60454269L, (int)0x46B94FC8L, (int)0xE84AFF3CL, (int)0x914BDD8CL,
        (int)0xD51F2ECFL, (int)0x706CDA12L, (int)0x714BD769L, (int)0xF44FD109L,
        (int)0x75AB709DL, (int)0x3922E546L, (int)0x9983D5EDL, (int)0xE0CE529DL,
        (int)0xD56DE380L, (int)0xDC38DB5EL, (int)0x631767DDL, (int)0x1B49E4E5L,
        (int)0xE6BA93B7L, (int)0xB09DDA2BL, (int)0x93B3F4F9L, (int)0xACDC939DL,
        (int)0x9F8E54D9L, (int)0x3B048A03L, (int)0xCBCA8202L, (int)0xC5243A13L,
        (int)0x2527160FL, (int)0xD7744729L, (int)0x8AFBC395L, (int)0xAE85F196L,
        (int)0x1D7426C8L, (int)0x3D269DDEL, (int)0xC5D99079L, (int)0x2FEC0E54L,
        (int)0x7C058E1EL, (int)0xFE1BF6EAL, (int)0x9B4A2ADFL, (int)0x58287762L,
        (int)0xAEF4CA33L, (int)0x9DC7A7F8L, (int)0xB2CF2D61L, (int)0x753C9E49L,
        (int)0x54259135L, (int)0xC717DF17L, (int)0x7691E883L, (int)0xEC685A39L,
        (int)0xF2DD2D80L, (int)0x65ACCFD8L, (int)0xE461A836L, (int)0x6A3CA714L,
        (int)0x26E8A844L, (int)0x137F5536L, (int)0xD15F47C8L, (int)0x6739F2C0L,
        (int)0x51E5B15EL, (int)0x362C90D8L, (int)0x9928F212L, (int)0x48EE7077L,
        (int)0xD3AF0062L, (int)0xCB77CBEAL, (int)0xBF3D3004L, (int)0xE1A060C3L,
        (int)0xD2D12EA2L, (int)0x2467110FL, (int)0xDD52CE38L, (int)0xA50E1B37L,
        (int)0x2037B4F5L, (int)0x9ABAB281L, (int)0x738BAA6BL, (int)0xEBF62EF4L,
        (int)0x8CE1B7E7L, (int)0x9EFDCDA2L, (int)0x594CBE5BL, (int)0x140F44E2L,
        (int)0x391F5E0EL, (int)0x54FCCBD2L, (int)0x9836CE5BL, (int)0x231DD482L,
        (int)0x35453900L, (int)0x255324DCL, (int)0x1638FFECL, (int)0x1B86CD9EL,
        (int)0xFBEBD20EL, (int)0x9C72B863L, (int)0xFCCE1404L, (int)0xDA4D47C4L,
        (int)0x652D2782L, (int)0xA86B57C7L, (int)0x9337230BL, (int)0x2C80C036L,
        (int)0x88584D9DL, (int)0xA5BF8095L, (int)0x1E59EB5DL, (int)0xBFF4A1F6L,
        (int)0xD5336680L, (int)0x1B667ABBL, (int)0xFE003B1BL, (int)0x4BDBF5D0L,
        (int)0x4695354AL, (int)0x46BC0DA6L, (int)0x22F81FD5L, (int)0xDCC85C8DL,
        (int)0xC5592A46L, (int)0x210243C2L, (int)0xF8CE204AL, (int)0x55A2ECDBL,
        (int)0x302EA3EBL, (int)0x22DBD600L, (int)0x6558A27EL, (int)0x203BF4F5L,
        (int)0x68ECA8E4L, (int)0x48E7FB1AL, (int)0x911ED357L, (int)0xDA6A5BBDL,
        (int)0xEFB35F73L, (int)0x2B263422L, (int)0x25CDEDC5L, (int)0x1B97A159L,
        (int)0x71E6A890L, (int)0x8C579EFCL, (int)0xFD0736A1L, (int)0x8068B26BL,
        (int)0x8F7A11F8L, (int)0x6AEF12EBL, (int)0xEBF98B50L, (int)0xAEF5E355L,
        (int)0x7FD98589L, (int)0x6249D41DL, (int)0x5D50D84BL, (int)0x930ACF34L,
        (int)0xFCAF71BCL, (int)0xFB8AAAE6L, (int)0x375077BDL, (int)0xEFBA0986L,
        (int)0xAACF8D46L, (int)0x7FEF096BL, (int)0x8E5A44E2L, (int)0x8AB919F8L,
        (int)0xC4E913D3L, (int)0x5D7C50BAL, (int)0xCBA20EE0L, (int)0xACDD7DFDL,
        (int)0x78DCF345L, (int)0xFC945786L, (int)0x8B4AF3A5L, (int)0xE12AE39AL,
        (int)0xE478A534L, (int)0xDE28DD22L, (int)0x2287E6FEL, (int)0xA571BF42L,
        (int)0xCC1892A1L, (int)0x6EE5936BL, (int)0xDEA354EBL, (int)0x4483C582L,
        (int)0xD665F09CL, (int)0x7EF314F3L, (int)0x332D89D4L, (int)0x38E8152CL,
        (int)0xF9E1E2E8L, (int)0x520C944BL, (int)0xB5DC35DEL, (int)0x6D98DEE1L,
        (int)0x291839BAL, (int)0xB5DBAAB0L, (int)0xC0CDE96EL, (int)0x3F1678D9L,
        (int)0x8B497565L, (int)0x6DFA7975L, (int)0xFCB79BB0L, (int)0x69778992L,
        (int)0x17844310L, (int)0x9846FBE7L, (int)0xB1557F9AL, (int)0x39245A93L,
        (int)0x75603F49L, (int)0x5C059AD6L, (int)0x15F64974L, (int)0xD202DBA3L,
        (int)0x77C564E4L, (int)0x45E001B0L, (int)0xBC86F5E8L, (int)0xD845ABEDL,
        (int)0x80BA7D65L, (int)0xF65C242AL, (int)0x61CFCE88L, (int)0x68F4C291L,
        (int)0xA66E8208L, (int)0xDAA0FCF8L, (int)0xD5114520L, (int)0xA508726FL,
        (int)0xB8840F5DL, (int)0x73FE8E19L, (int)0xC6CF3FA9L, (int)0xCCD3C924L,
        (int)0xEB34FCAEL, (int)0x5DF955F9L, (int)0xF58EC3AAL, (int)0x152C5A44L,
        (int)0x8650F4FEL, (int)0xCB7DB0D3L, (int)0x14E9F000L, (int)0x3C3B65D4L,
        (int)0x27DC1197L, (int)0x53F71546L, (int)0x5FA8DEA2L, (int)0x901DE276L,
        (int)0xB418DCA4L, (int)0xF8DA4E3DL, (int)0x321CF6FCL, (int)0x6018F3DCL,
        (int)0xF57A333FL, (int)0x555A5CE0L, (int)0x397A1726L, (int)0x1561EFA4L,
        (int)0x58775C48L, (int)0xC5061386L, (int)0xC644B639L, (int)0x902A23C2L,
        (int)0x50FECB85L, (int)0x7AF0BF22L, (int)0x8EE1772BL, (int)0xEEA4F838L,
        (int)0xCD062A99L, (int)0xE3EC789EL, (int)0x7AD0817EL, (int)0x31D67CF1L,
        (int)0x94AB96D5L, (int)0x1BC984A4L, (int)0xBB699FDFL, (int)0x58320E9CL,
        (int)0xF61E24D4L, (int)0xE9A59FAEL, (int)0x9A6C0270L, (int)0x8D3945B3L,
        (int)0xAB23BB18L, (int)0xB3294767L, (int)0xD05482B7L, (int)0x931E95F2L,
        (int)0xB018BB04L, (int)0x4FFDDF3FL, (int)0xADC902BCL, (int)0x120F04D7L,
        (int)0x357F894FL, (int)0x5989B031L, (int)0x2A5CD646L, (int)0x8699908DL,
        (int)0xF62B5C52L, (int)0x8AD1F6FBL, (int)0xFC4DE0E6L, (int)0x31F58BC7L,
        (int)0x8CC2C7B4L, (int)0xDBF222CDL, (int)0x167A1385L, (int)0x57EF98F4L,
        (int)0x8C4BD22EL, (int)0x446A99E0L, (int)0x92179EA8L, (int)0x334B5608L,
    };

    /** Returns the checksum as a 32 bit value (the wire form is this value little endian). */
    public static int compute(byte[] data, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = T[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return ~crc;
    }

    public static int compute(byte[] data) { return compute(data, 0, data.length); }
}
