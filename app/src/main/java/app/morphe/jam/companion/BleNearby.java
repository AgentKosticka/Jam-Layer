package app.morphe.jam.companion;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Low-power LE discovery and L2CAP data, subordinate to Wi-Fi and audio playback. */
final class BleNearby implements AutoCloseable {
  interface Listener {
    void connected(ChannelTransport connection);
    void state(String state);
  }

  private final Context context;
  private final boolean host;
  private final Listener listener;
  private final ParcelUuid service;
  private final BluetoothAdapter adapter;
  private final AudioManager audio;
  private final BooleanSupplier audioBlocked;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newCachedThreadPool();
  private final Set<BluetoothSocket> pending = ConcurrentHashMap.newKeySet();
  private final Set<ChannelTransport> connections = ConcurrentHashMap.newKeySet();
  private final Runnable tick = this::refresh;
  private final AudioDeviceCallback audioCallback = new AudioDeviceCallback() {
    public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { refresh(); }
    public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { refresh(); }
  };
  private final BroadcastReceiver receiver = new BroadcastReceiver() {
    public void onReceive(Context ignored, Intent intent) { refresh(); }
  };
  private BluetoothServerSocket server;
  private BluetoothLeAdvertiser advertiser;
  private BluetoothLeScanner scanner;
  private AdvertiseCallback advertising;
  private ScanCallback scanning;
  private boolean wanted, observing, opening, connecting;
  private volatile boolean closed;
  private volatile int generation;
  private long retryAt, scanStarted, nextScan;
  private String lastState = "idle";

  BleNearby(Context context, Invitation invitation, boolean host, Listener listener) {
    this(context, invitation.jamId, host, listener);
  }

  BleNearby(Context context, String discoveryId, boolean host, Listener listener) {
    this(context, discoveryId, host, listener, () -> bluetoothAudioConnected(context));
  }

  // Dependency injection keeps audio-policy tests independent of paired headphones.
  BleNearby(Context context, String discoveryId, boolean host, Listener listener,
      BooleanSupplier audioBlocked) {
    this.context = context.getApplicationContext();
    this.host = host;
    this.listener = listener;
    this.audioBlocked = audioBlocked;
    service = new ParcelUuid(UUID.nameUUIDFromBytes(
        ("morphejam-ble-v1/" + discoveryId).getBytes(StandardCharsets.UTF_8)));
    BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
    adapter = manager == null ? null : manager.getAdapter();
    audio = this.context.getSystemService(AudioManager.class);
  }

  static boolean isBluetoothAudioType(int type) {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
        type == AudioDeviceInfo.TYPE_HEARING_AID;
  }

  static boolean bluetoothAudioConnected(Context context) {
    AudioManager audio = context.getSystemService(AudioManager.class);
    if (audio == null) return true; // Cannot establish that coexistence is safe.
    try {
      for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
        if (isBluetoothAudioType(device.getType())) return true;
      return audio.isBluetoothA2dpOn() || audio.isBluetoothScoOn();
    } catch (RuntimeException unknown) { return true; }
  }

  void start() {
    handler.post(() -> {
      if (closed) return;
      wanted = true;
      if (!observing) {
        observing = true;
        if (audio != null) audio.registerAudioDeviceCallback(audioCallback, handler);
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33)
          context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
      }
      refresh();
    });
  }

  void suspend() {
    handler.post(() -> {
      wanted = false;
      handler.removeCallbacks(tick);
      stopRadio();
      state("standby; faster transport active");
    });
  }

  private String unavailable() {
    if (Build.VERSION.SDK_INT < 29) return "requires Android 10";
    if (adapter == null) return "unsupported";
    if (Build.VERSION.SDK_INT >= 31) {
      for (String permission : new String[] { "android.permission.BLUETOOTH_CONNECT",
          host ? "android.permission.BLUETOOTH_ADVERTISE" : "android.permission.BLUETOOTH_SCAN" })
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
          return "permission required";
    } else if (!host && context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
        != PackageManager.PERMISSION_GRANTED) return "location permission required";
    if (!adapter.isEnabled()) return "Bluetooth off";
    if (audioBlocked.getAsBoolean()) return "paused for Bluetooth audio";
    if (host && !adapter.isMultipleAdvertisementSupported()) return "advertising unsupported";
    return null;
  }

  private void refresh() {
    handler.removeCallbacks(tick);
    if (closed || !wanted) return;
    try {
      String reason = unavailable();
      if (reason != null) {
        stopRadio();
        state(reason);
      } else {
        connections.removeIf(ChannelTransport::isClosed);
        long now = SystemClock.elapsedRealtime();
        if (now >= retryAt) {
          if (host && server == null && !opening) openServer();
          if (!host && connections.isEmpty() && !connecting) {
            if (scanning != null && now - scanStarted >= 12000) {
              stopScan();
              nextScan = now + 8000;
            } else if (scanning == null && now >= nextScan) scan();
          }
        }
      }
    } catch (RuntimeException error) {
      stopRadio();
      state("unavailable");
      retryAt = SystemClock.elapsedRealtime() + 30000;
    }
    handler.postDelayed(tick, 1000);
  }

  private void openServer() {
    opening = true;
    int epoch = generation;
    workers.execute(() -> {
      try {
        BluetoothServerSocket socket = adapter.listenUsingInsecureL2capChannel();
        handler.post(() -> {
          if (closed || !wanted || epoch != generation) { close(socket); return; }
          opening = false;
          server = socket;
          advertise(socket.getPsm(), epoch);
          workers.execute(() -> accept(socket, epoch));
        });
      } catch (Exception error) { handler.post(() -> failed(epoch, "L2CAP unavailable")); }
    });
  }

  private void advertise(int psm, int epoch) {
    advertiser = adapter.getBluetoothLeAdvertiser();
    if (advertiser == null) { failed(epoch, "advertising unavailable"); return; }
    advertising = new AdvertiseCallback() {
      public void onStartSuccess(AdvertiseSettings settings) {
        handler.post(() -> { if (epoch == generation && !closed) state("advertising; low power"); });
      }
      public void onStartFailure(int error) {
        handler.post(() -> failed(epoch, "advertising unavailable: " + error));
      }
    };
    byte[] data = { 1, (byte)(psm >>> 8), (byte)psm };
    advertiser.startAdvertising(new AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
        .setConnectable(true).build(),
        new AdvertiseData.Builder().addServiceData(service, data).build(), advertising);
  }

  private void accept(BluetoothServerSocket socket, int epoch) {
    while (!closed && epoch == generation) {
      try {
        BluetoothSocket incoming = socket.accept();
        pending.add(incoming);
        handler.post(() -> {
          pending.remove(incoming);
          if (closed || epoch != generation || connections.size() >= 4) { close(incoming); return; }
          deliver(incoming, epoch);
        });
      } catch (Exception error) { break; }
    }
  }

  private void scan() {
    scanner = adapter.getBluetoothLeScanner();
    if (scanner == null) { state("scanning unavailable"); return; }
    int epoch = generation;
    scanning = new ScanCallback() {
      public void onScanResult(int type, ScanResult result) {
        handler.post(() -> {
          if (closed || epoch != generation || connecting || !connections.isEmpty()) return;
          ScanRecord record = result.getScanRecord();
          byte[] data = record == null ? null : record.getServiceData(service);
          if (data == null || data.length != 3 || data[0] != 1) return;
          int psm = ((data[1] & 255) << 8) | (data[2] & 255);
          if (psm < 1) return;
          connect(result.getDevice(), psm, epoch);
        });
      }
      public void onScanFailed(int error) {
        handler.post(() -> failed(epoch, "scan unavailable: " + error));
      }
    };
    scanStarted = SystemClock.elapsedRealtime();
    nextScan = scanStarted + 6500;
    scanner.startScan(Collections.singletonList(new ScanFilter.Builder()
        .setServiceData(service, new byte[] {1}, new byte[] {(byte)255}).build()),
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanning);
    state("scanning; low power");
  }

  private void connect(BluetoothDevice device, int psm, int epoch) {
    connecting = true;
    stopScan();
    state("connecting");
    workers.execute(() -> {
      BluetoothSocket socket = null;
      try {
        socket = device.createInsecureL2capChannel(psm);
        pending.add(socket);
        if (closed || epoch != generation) { close(socket); return; }
        BleChannelTransport.connect(socket, 8000);
        BluetoothSocket connected = socket;
        handler.post(() -> {
          pending.remove(connected);
          connecting = false;
          deliver(connected, epoch);
        });
      } catch (Exception error) {
        if (socket != null) { pending.remove(socket); close(socket); }
        handler.post(() -> failed(epoch, "connection retrying"));
      }
    });
  }

  private void deliver(BluetoothSocket socket, int epoch) {
    if (closed || !wanted || epoch != generation) { close(socket); return; }
    try {
      ChannelTransport connection = new BleChannelTransport(socket);
      connections.add(connection);
      state("socket connected");
      listener.connected(connection);
    } catch (Exception error) { close(socket); }
  }

  private void failed(int epoch, String reason) {
    if (closed || epoch != generation) return;
    stopRadio();
    retryAt = SystemClock.elapsedRealtime() + 10000;
    state(reason);
  }

  private void stopScan() {
    if (scanner != null && scanning != null) try { scanner.stopScan(scanning); } catch (RuntimeException ignored) {}
    scanning = null;
  }

  private void stopRadio() {
    generation++;
    opening = connecting = false;
    stopScan();
    if (advertiser != null && advertising != null)
      try { advertiser.stopAdvertising(advertising); } catch (RuntimeException ignored) {}
    advertising = null;
    if (server != null) close(server);
    server = null;
    for (BluetoothSocket socket : pending) close(socket);
    pending.clear();
    for (ChannelTransport connection : connections) close(connection);
    connections.clear();
  }

  private void state(String state) {
    if (!state.equals(lastState)) { lastState = state; listener.state(state); }
  }

  private static void close(AutoCloseable value) {
    try { value.close(); } catch (Exception ignored) {}
  }

  @Override public void close() {
    closed = true;
    handler.post(() -> {
      wanted = false;
      handler.removeCallbacks(tick);
      stopRadio();
      if (observing) {
        if (audio != null) audio.unregisterAudioDeviceCallback(audioCallback);
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        observing = false;
      }
      workers.shutdownNow();
    });
  }
}
