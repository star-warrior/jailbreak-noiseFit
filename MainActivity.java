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
import android.content.Context;
import android.content.pm.PackageManager;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {

    static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
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
    boolean writePending = false;
    long writePendingDesc = 0;
    int ackGen = 0, wrGen = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this); svRef = sv;
        android.widget.LinearLayout col = new android.widget.LinearLayout(this);
        col.setOrientation(android.widget.LinearLayout.VERTICAL); sv.addView(col); setContentView(sv);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Button btnScan = new Button(this); btnScan.setText("SCAN"); col.addView(btnScan);
        android.widget.EditText macIn = new android.widget.EditText(this);
        macIn.setHint("or paste watch MAC XX:XX:XX:XX:9F:33");
        macIn.setText("DB:81:C2:15:9F:33");
        macIn.setTextSize(14); col.addView(macIn);
        Button btnMac = new Button(this); btnMac.setText("CONNECT BY MAC"); col.addView(btnMac);
        listDevices = new ListView(this); listDevices.setAdapter(devAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1)); col.addView(listDevices);
        Button btnRun = new Button(this); btnRun.setText("REPLAY"); col.addView(btnRun);
        Button btnTest = new Button(this); btnTest.setText("TEST COMPILER (offline)"); col.addView(btnTest);
        logView = new TextView(this); col.addView(logView);

        btnScan.setOnClickListener(v -> {
            if (checkSelfPermission("android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED
              || checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED
              || checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.BLUETOOTH_SCAN","android.permission.BLUETOOTH_CONNECT","android.permission.ACCESS_FINE_LOCATION"}, 1);
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
        btnTest.setOnClickListener(v -> {
            WFCompilerRunner.run(s -> log("COMP " + s), getFilesDir());
        });
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
        String prev = logView.getText().toString();
        String[] ls = (prev + "\n" + s).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int k = Math.max(0, ls.length - 12); k < ls.length; k++) sb.append(ls[k]).append('\n');
        logView.setText(sb);
        if (svRef != null) svRef.post(() -> svRef.fullScroll(android.view.View.FOCUS_DOWN));
        Log.i("WFUP", s); }); }

    boolean scanning = false;
    final ScanCallback scanCb = new ScanCallback() {
        @SuppressLint("MissingPermission") public void onScanResult(int ct, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String name = (d.getName() == null ? "(unknown)" : d.getName()) + " " + d.getAddress();
            if (!found.containsKey(name)) { found.put(name, d); devAdapter.add(name); }
        }
        @Override public void onScanFailed(int errorCode) { log("scan FAILED code=" + errorCode); }
    };

    @SuppressLint("MissingPermission")
    void startScan() {
        found.clear(); devAdapter.clear();
        BluetoothAdapter ad = ((BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        try {
            if (ad == null || ad.getState() != BluetoothAdapter.STATE_ON) { log("bluetooth not ready yet"); return; }
            android.bluetooth.le.ScanSettings st = new android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            ad.getBluetoothLeScanner().startScan(null, st, scanCb);
            scanning = true;
            log("scan started...");
            h.postDelayed(new Runnable() { public void run() {
                try { ad.getBluetoothLeScanner().stopScan(scanCb); } catch(Exception e){ log("stop scan err " + e); }
                scanning = false;
                log("scan over, " + found.size() + " devices");
            }}, 12000);
        } catch (Exception e) { log("scan err " + e); scanning = false; }
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
            if (waitAck != null && ackMatch(waitAck, hv) && (waitGateChar == null || isGateChar(c, waitGateChar))) {
                waitAck = null; waitGateChar = null;
                log("ack ok " + hv);
                h.post(() -> stepReplay(g));
                return;
            }
            if (!hv.equals("000001000000") && !hv.equals("000001010000")) {
                if (hnotify++ % 20 == 0) log("notify " + hv + (waitAck == null ? "" : " (waiting " + waitAck + ")"));
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
            log("replay lines: " + replayLines.size());
        } catch (Exception e) { log("asset err " + e); }
    }

    @SuppressLint("MissingPermission")
    void runReplay() {
        if (chData == null) { log("chars not ready - reconnect first"); return; }
        running = true; replayIdx = 0; paused = false;
        log("replay start");
        h.post(() -> stepReplay(gatt));
    }

    boolean ackMatch(String want, String hv) {
        if (want.equalsIgnoreCase(hv)) return true;
        // 6-byte control ACKs are exact only; longer payload notifies carry
        // session-variant bytes (battery/timestamp) -> match length + 4-byte header
        if (want.length() == 12) return false;
        return want.length() == hv.length() && want.regionMatches(true, 0, hv, 0, 8);
    }

    @SuppressLint("MissingPermission")
    void stepReplay(BluetoothGatt g) {
        if (!running || g == null) return;
        if (replayIdx >= replayLines.size()) { running = false; log("replay DONE"); return; }
        if (waitAck != null || writePending) { h.postDelayed(() -> stepReplay(g), 10); return; }
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
