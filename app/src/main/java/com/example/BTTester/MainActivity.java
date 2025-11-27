package com.example.BTTester;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseSettings;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ArrayAdapter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.couchbase.lite.internal.p2p.ble.BLEService;
import com.couchbase.lite.internal.p2p.ble.BleGattInboundListener;
import com.couchbase.lite.internal.p2p.ble.BleGattServer;
import com.couchbase.lite.internal.p2p.ble.Peer;
import com.couchbase.lite.internal.p2p.ble.PeerBrowseListener;
import com.couchbase.lite.internal.p2p.ble.BlePublisher;
import com.couchbase.lite.internal.p2p.ble.BlePublisherHandle;
import com.couchbase.lite.internal.p2p.ble.BlePublisherListener;
import com.couchbase.lite.internal.permissions.BLEPermissionRequirements;
public class MainActivity extends AppCompatActivity {
    private TextView statusView;
    private Button startBtn;
    private Button stopBtn;
    private Button startScanBtn;
    private Button stopScanBtn;
    private Button connectL2capBtn;
    private ListView peersList;
    private ArrayAdapter<String> peersAdapter;

    private BlePublisher publisher;
    private BlePublisherHandle handle;
    private BLEService bleService;
    private BleGattServer gattServer; // now started implicitly by publisher when hosting

    private enum PendingAction { NONE, START_ADVERTISE, START_SCAN }
    private PendingAction pendingAction = PendingAction.NONE;

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN})
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // Decide next step based on the action that requested permissions
                switch (pendingAction) {
                    case START_ADVERTISE:
                        if (hasAdvertisePermissions()) { startAdvertising(); }
                        else { setStatus("Advertising permissions denied"); }
                        break;
                    case START_SCAN:
                        if (hasScanPermissions()) { startScanning(); }
                        else { setStatus("Scan permissions denied"); }
                        break;
                    case NONE:
                    default:
                        // No action pending; just reflect current status
                        setStatus("Permissions updated");
                }
                pendingAction = PendingAction.NONE;
            });

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        startBtn = findViewById(R.id.startBtn);
        stopBtn = findViewById(R.id.stopBtn);
        startScanBtn = findViewById(R.id.startScanBtn);
        stopScanBtn = findViewById(R.id.stopScanBtn);
        connectL2capBtn = findViewById(R.id.connectL2capBtn);
        peersList = findViewById(R.id.peersList);
        peersAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        peersList.setAdapter(peersAdapter);

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        publisher = new BlePublisher(adapter);
        bleService = new BLEService(this, adapter);
        gattServer = new BleGattServer(this, adapter);

        startBtn.setOnClickListener(v -> ensurePermissionsThenStartAdvertising());
        stopBtn.setOnClickListener(v -> stopAdvertising());
        startScanBtn.setOnClickListener(v -> ensurePermissionsThenStartScanning());
        stopScanBtn.setOnClickListener(v -> stopScanning());
        connectL2capBtn.setOnClickListener(v -> connectFirstPeerL2cap());
    }

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT})
    private void ensurePermissionsThenStartAdvertising() {
        if (hasAdvertisePermissions()) { startAdvertising(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingAction = PendingAction.START_ADVERTISE;
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
            });
        }
        else {
            pendingAction = PendingAction.START_ADVERTISE;
            permissionLauncher.launch(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION });
        }
    }

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
    private void ensurePermissionsThenStartScanning() {
        if (hasScanPermissions()) { startScanning(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingAction = PendingAction.START_SCAN;
            permissionLauncher.launch(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            });
        }
        else {
            pendingAction = PendingAction.START_SCAN;
            permissionLauncher.launch(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION });
        }
    }

    private boolean hasAdvertisePermissions() { return BLEPermissionRequirements.hasAdvertisePermissions(this); }
    private boolean hasScanPermissions() { return BLEPermissionRequirements.hasScanPermissions(this); }

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT})
    private void startAdvertising() {
        setStatus("Starting hosting...");
        gattServer.setInboundListener(new BleGattInboundListener() {
            @Override public void onInboundOpen(@NonNull String deviceAddress) {
                setStatus("Inbound opened: " + deviceAddress);
            }
            @Override public void onInboundData(@NonNull String deviceAddress, @NonNull byte[] data) {
                String msg = new String(data);
                setStatus("Inbound " + deviceAddress + ": " + msg);
            }
            @Override public void onInboundClosed(@NonNull String deviceAddress, Throwable err) {
                setStatus("Inbound closed: " + deviceAddress + (err != null ? (" err=" + err.getMessage()) : ""));
            }
        });
        handle = publisher.startHosting(
                gattServer,
                "demo-peer",
                null,
                true,
                new BlePublisherListener() {
                    @Override public void onStarting() { setStatus("Advertising starting..."); }
                    @Override public void onStarted(AdvertiseSettings s) { setStatus("Advertising (high)"); }
                    @Override public void onModeChanged(String phase, AdvertiseSettings s) { setStatus("Advertising (" + phase + ")"); }
                    @Override public void onRetry(int attempt, int errorCode) { setStatus("Retry " + attempt + " code " + errorCode); }
                    @Override public void onStopped(Throwable error) { setStatus(error == null ? "Stopped" : "Error: " + error.getMessage()); }
                }
        );
    }

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
    private void startScanning() {
        if (bleService.isBrowsing()) { return; }
        setStatus("Scanning...");
        bleService.addBrowseListener(new PeerBrowseListener() {
            @Override public void onPeerDiscovered(Peer peer) {
                runOnUiThread(() -> updatePeerItem(peer.getId(), peer.getRssi()));
            }
            @Override public void onPeerUpdated(Peer peer) {
                runOnUiThread(() -> updatePeerItem(peer.getId(), peer.getRssi()));
            }
            @Override public void onPeerLost(Peer peer) {
                runOnUiThread(() -> removePeerItem(peer.getId()));
            }
            @Override public void onError(Throwable t) { setStatus("Scan error: " + t.getMessage()); }
            @Override public void onCompleted() { setStatus("Scan completed"); }
        });
        bleService.startBrowsing(new PeerBrowseListener() {
            @Override public void onPeerDiscovered(Peer peer) { /* handled via listeners */ }
            @Override public void onPeerUpdated(Peer peer) { }
            @Override public void onPeerLost(Peer peer) { }
            @Override public void onError(Throwable t) { setStatus("Scan error: " + t.getMessage()); }
        });
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private void stopAdvertising() {
        if (handle != null) { handle.stop(); }
    }

    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT})
    private void stopScanning() {
        bleService.stopBrowsing();
    }

    private void setStatus(@NonNull String msg) {
        Log.d("Aniket", msg);
        runOnUiThread(() -> statusView.setText(msg));
    }

    // Update or insert a peer entry by address; keeps list de-duplicated while refreshing RSSI
    private void updatePeerItem(@NonNull String id, int rssi) {
        String newVal = id + " (RSSI=" + rssi + ")";
        // Find existing entry by address prefix
        int existingIdx = -1;
        for (int i = 0; i < peersAdapter.getCount(); i++) {
            String val = peersAdapter.getItem(i);
            if (val != null && val.startsWith(id)) { existingIdx = i; break; }
        }
        if (existingIdx >= 0) {
            peersAdapter.remove(peersAdapter.getItem(existingIdx));
            peersAdapter.insert(newVal, existingIdx);
            peersAdapter.notifyDataSetChanged();
        }
        else {
            peersAdapter.add(newVal);
        }
    }

    private void removePeerItem(@NonNull String id) {
        for (int i = peersAdapter.getCount() - 1; i >= 0; i--) {
            String val = peersAdapter.getItem(i);
            if (val != null && val.startsWith(id)) { peersAdapter.remove(val); }
        }
        peersAdapter.notifyDataSetChanged();
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void connectFirstPeerL2cap() {
        if (peersAdapter.getCount() == 0) { setStatus("No peers"); return; }
        String item = peersAdapter.getItem(0);
        if (item == null) { return; }
        String id = item.split(" ")[0]; // parse ID before space
        boolean ok = bleService.openPeer(id,
                addr -> runOnUiThread(() -> setStatus("Data: " + addr)),
                (addr, err) -> runOnUiThread(() -> setStatus(err == null ? "Closed" : "Closed with error"))
        );
        if (!ok) { setStatus("Open failed"); }
        else { setStatus("L2CAP connected: " + id); }
        // Optionally send a test message
        bleService.sendToPeer(id, "Hello from client\n");
    }
}
