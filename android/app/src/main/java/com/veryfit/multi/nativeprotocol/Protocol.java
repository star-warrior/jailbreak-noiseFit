package com.veryfit.multi.nativeprotocol;

import com.jay.wfup.MainActivity;

/**
 * JNI-stub matching com.veryfit.multi.nativeprotocol.Protocol from NoiseFit —
 * same package/class so native symbol lookup (Java_com_veryfit_multi_nativeprotocol_...)
 * binds to libVeryFitMulti.so packaged in our APK.
 */
public class Protocol {
    private static Protocol instance;
    public static synchronized Protocol getInstance() {
        if (instance == null) instance = new Protocol();
        return instance;
    }
    private Protocol() {}

    public native void initParameter();
    public native void initType(int i);
    public native int setFilePath(byte[] path);
    public native int makeFileCompression(String path);    public native int mkIwfFile(byte[] a, byte[] b, int mode);
    public native int Png2Bmp(byte[] from, byte[] to, int format);
    public native int setMtu(int rx, int tx);
    public native boolean tranDataisStart();
    public native int tranDataStart();
    public native int tranDataStop();
    public native String smoothData(String s);
    public native int WriteJsonData(byte[] json, int cmdId);
    public native void ProtocolSetLogEnable(boolean a, boolean b, byte[] c);
    public native int tranDataSetBuff(byte[] data, int type, byte[] name, int zipType, int oriSize);
    public native int tranDataSendComplete();

    /* callbacks FROM native into this class — we capture what protocol engine emits */
    public int CallBackWriteDataToBle(byte[] data) {
        MainActivity.onNativeWrite(data);
        return data.length;
    }
    public int CallBackWriteDataToSPP(byte[] data) { return data.length; }
    public void CallBackJsonData(byte[] data, int i, int i2) { MainActivity.onNativeJson(i, data); }
    public void CallBackSysEvt(int i, int i2, int i3, int i4) { MainActivity.onNativeSysEvt(i); }
}
