package com.example.adbshelltool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AdbPairingService — Foreground Service that handles ADB pairing and connection.
 *
 * Flow (mirrors Shizuku exactly):
 *   1. User taps "Start Pairing" → service is launched with action="start".
 *   2. Service starts in the foreground and listens for "_adb-tls-pairing._tcp"
 *      via NsdManager (same as rikka.shizuku.m3).
 *   3. User opens Settings > Wireless Debugging > "Pair with code". Android
 *      announces the pairing port via mDNS.
 *   4. NsdManager resolves the port → notification updates with a RemoteInput field.
 *   5. User types the 6-digit code in the notification without leaving Settings.
 *   6. The PendingIntent targets THIS SERVICE (same as Shizuku's
 *      PendingIntent.getForegroundService via rikka.shizuku.e4.a()). The RemoteInput
 *      bundle is read in onStartCommand via android.app.RemoteInput.getResultsFromIntent().
 *   7. Pairing is performed. On success, service listens for "_adb-tls-connect._tcp"
 *      to auto-connect.
 *   8. Session port is detected → AdbManager.connect() is called → broadcast sent to
 *      MainActivity to update the UI.
 *
 * Why the PendingIntent targets the service and NOT a BroadcastReceiver:
 *   Shizuku (AdbPairingService.smali, method o()) uses PendingIntent.getForegroundService
 *   so Android delivers the RemoteInput reply directly to onStartCommand. The bundle is
 *   read with android.app.RemoteInput.getResultsFromIntent(intent) — same as Shizuku
 *   (:cond_4 in AdbPairingService.smali).
 *
 * Why android.app.* and NOT androidx:
 *   Mixing android.app.RemoteInput with androidx.core.app.RemoteInput breaks bundle
 *   delivery. All notification classes must be from the same package.
 */
public class AdbPairingService extends Service {

    private static final String ADB_PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp";
    private static final String ADB_CONNECT_SERVICE_TYPE = "_adb-tls-connect._tcp";

    public static final String CHANNEL_ID       = "adb_pairing";
    private static final int   NOTIF_ID         = 1;

    public static final String ACTION_START     = "start";
    public static final String ACTION_REPLY     = "reply";
    public static final String ACTION_STOP      = "stop";

    // RemoteInput key — same as Shizuku ("paring_code", one 'r', intentional typo match)
    // Also used as the int extra key for the port in the reply intent (same as Shizuku,
    // see AdbPairingService.smali line 1522: getIntExtra("paring_code", -1)).
    public static final String REMOTE_INPUT_KEY = "paring_code";

    // Broadcast sent to MainActivity once the ADB session is established
    public static final String ACTION_CONNECTED     = "com.example.adbshelltool.CONNECTED";
    public static final String EXTRA_CONNECTED_PORT = "connected_port";

    // Pairing port discovered via mDNS (fallback if the int extra is missing from the reply)
    private volatile int detectedPairingPort = -1;

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingDiscoveryListener;
    private NsdManager.DiscoveryListener connectDiscoveryListener;

    private ExecutorService executor;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        String action = intent.getAction();
        if (action == null) action = "";

        switch (action) {

            case ACTION_START:
                startForeground(NOTIF_ID, buildSearchingNotification());
                startPairingMdnsDiscovery();
                break;

            case ACTION_REPLY:
                // Shizuku reads the RemoteInput bundle directly from the intent
                // delivered here — no BroadcastReceiver intermediary.
                Bundle remoteInputBundle = RemoteInput.getResultsFromIntent(intent);

                String code = null;
                if (remoteInputBundle != null) {
                    CharSequence cs = remoteInputBundle.getCharSequence(REMOTE_INPUT_KEY);
                    if (cs != null) code = cs.toString().trim();
                }

                // Port arrives as an int extra with the same key as the RemoteInput
                // (exactly as in Shizuku: getIntExtra("paring_code", -1))
                int portFromIntent = intent.getIntExtra(REMOTE_INPUT_KEY, -1);
                int port = (portFromIntent > 0) ? portFromIntent : detectedPairingPort;

                if (code == null || code.isEmpty() || port <= 0) break;

                stopPairingMdnsDiscovery();
                updateNotification(buildPairingInProgressNotification());

                final String finalCode = code;
                final int finalPort = port;
                executor.submit(() -> doPairing(finalPort, finalCode));
                break;

            case ACTION_STOP:
                stopPairingMdnsDiscovery();
                stopConnectMdnsDiscovery();
                stopForeground(true);
                stopSelf();
                break;

            default:
                stopSelf();
                return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPairingMdnsDiscovery();
        stopConnectMdnsDiscovery();
        if (executor != null) executor.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // mDNS discovery — pairing port
    // -----------------------------------------------------------------------

    private void startPairingMdnsDiscovery() {
        pairingDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                nsdManager.resolveService(info, buildPairingResolveListener());
            }
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        nsdManager.discoverServices(ADB_PAIRING_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
    }

    private void stopPairingMdnsDiscovery() {
        if (pairingDiscoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(pairingDiscoveryListener); }
            catch (Exception ignored) {}
            pairingDiscoveryListener = null;
        }
    }

    private NsdManager.ResolveListener buildPairingResolveListener() {
        return new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
            @Override public void onServiceResolved(NsdServiceInfo info) {
                int port = info.getPort();
                if (!isPortOccupiedLocally(port)) return;
                detectedPairingPort = port;
                updateNotification(buildCodeInputNotification(port));
            }
        };
    }

    // -----------------------------------------------------------------------
    // mDNS discovery — session port (auto-connect after pairing)
    // -----------------------------------------------------------------------

    private void startConnectMdnsDiscovery() {
        connectDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) {}
            @Override public void onStopDiscoveryFailed(String t, int e) {}
            @Override public void onDiscoveryStarted(String t) {}
            @Override public void onDiscoveryStopped(String t) {}
            @Override public void onServiceFound(NsdServiceInfo info) {
                nsdManager.resolveService(info, buildConnectResolveListener());
            }
            @Override public void onServiceLost(NsdServiceInfo info) {}
        };
        nsdManager.discoverServices(ADB_CONNECT_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
    }

    private void stopConnectMdnsDiscovery() {
        if (connectDiscoveryListener != null) {
            try { nsdManager.stopServiceDiscovery(connectDiscoveryListener); }
            catch (Exception ignored) {}
            connectDiscoveryListener = null;
        }
    }

    private NsdManager.ResolveListener buildConnectResolveListener() {
        return new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}
            @Override public void onServiceResolved(NsdServiceInfo info) {
                int port = info.getPort();
                if (!isPortOccupiedLocally(port)) return;
                stopConnectMdnsDiscovery();
                executor.submit(() -> doConnect(port));
            }
        };
    }

    // -----------------------------------------------------------------------
    // Pairing
    // -----------------------------------------------------------------------

    private void doPairing(int port, String code) {
        try {
            AdbManager.getInstance(this).pair(port, code);
            updateNotification(buildResultNotification(true, null));
            stopForeground(false);
            // After pairing, listen for the session port to auto-connect
            startConnectMdnsDiscovery();
        } catch (Exception e) {
            updateNotification(buildResultNotification(false, e.getMessage()));
            stopForeground(false);
            stopSelf();
        }
    }

    // -----------------------------------------------------------------------
    // Auto-connect
    // -----------------------------------------------------------------------

    private void doConnect(int port) {
        try {
            AdbManager.getInstance(this).connect(port);
            Intent broadcast = new Intent(ACTION_CONNECTED);
            broadcast.putExtra(EXTRA_CONNECTED_PORT, port);
            sendBroadcast(broadcast);
            updateNotification(buildConnectedNotification(port));
            stopForeground(false);
            stopSelf();
        } catch (Exception e) {
            updateNotification(buildResultNotification(false, "Connect failed: " + e.getMessage()));
            stopForeground(false);
            stopSelf();
        }
    }

    // -----------------------------------------------------------------------
    // Port validation (same as Shizuku rikka.shizuku.m3.f())
    // Tries to bind 127.0.0.1:port. If bind fails the port is occupied = ADB daemon.
    // -----------------------------------------------------------------------

    private boolean isPortOccupiedLocally(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("127.0.0.1", port), 1);
            return false; // bind succeeded → port is free → not the daemon
        } catch (Exception e) {
            return true;  // bind failed → port occupied → it's the ADB daemon
        }
    }

    // -----------------------------------------------------------------------
    // Notifications — using native android.app.* classes, same as Shizuku
    // -----------------------------------------------------------------------

    private Notification buildSearchingNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ADB Pairing — waiting")
                .setContentText("Go to Settings › Wireless Debugging › \"Pair with code\"")
                .addAction(buildStopAction())
                .setOngoing(true)
                .build();
    }

    /**
     * Notification with inline RemoteInput field.
     *
     * Mirrors Shizuku's method o() in AdbPairingService.smali:
     *   - PendingIntent.getForegroundService (via rikka.shizuku.e4.a())
     *   - action = "reply"
     *   - int extra "paring_code" = port (same key as the RemoteInput)
     *
     * This causes Android to deliver the RemoteInput bundle to onStartCommand,
     * where it is read with RemoteInput.getResultsFromIntent(intent).
     */
    private Notification buildCodeInputNotification(int port) {
        RemoteInput remoteInput = new RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel("6-digit pairing code")
                .build();

        Intent replyIntent = new Intent(this, AdbPairingService.class);
        replyIntent.setAction(ACTION_REPLY);
        replyIntent.putExtra(REMOTE_INPUT_KEY, port); // int extra, same key as RemoteInput

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) piFlags |= PendingIntent.FLAG_MUTABLE;

        PendingIntent replyPending = PendingIntent.getForegroundService(this, 1, replyIntent, piFlags);

        Notification.Action replyAction = new Notification.Action.Builder(
                null, "Enter code", replyPending)
                .addRemoteInput(remoteInput)
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ADB Pairing — port " + port + " detected")
                .setContentText("Pull down and tap \"Enter code\" to type the 6-digit code")
                .addAction(replyAction)
                .addAction(buildStopAction())
                .setOngoing(true)
                .build();
    }

    private Notification buildPairingInProgressNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ADB Pairing")
                .setContentText("Pairing in progress...")
                .setOngoing(true)
                .build();
    }

    private Notification buildResultNotification(boolean success, String errorMsg) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openAppPending = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(success
                        ? android.R.drawable.stat_notify_sync_noanim
                        : android.R.drawable.stat_notify_error)
                .setContentTitle(success ? "Pairing successful" : "Pairing failed")
                .setContentText(success
                        ? "Disable and re-enable Wireless Debugging to connect automatically"
                        : (errorMsg != null ? errorMsg : "Unknown error. Try again."))
                .setContentIntent(openAppPending)
                .setAutoCancel(!success)
                .build();
    }

    private Notification buildConnectedNotification(int port) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent openAppPending = PendingIntent.getActivity(
                this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT | immutable);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("ADB connected!")
                .setContentText("Connected on port " + port + ". Tap to open the app.")
                .setContentIntent(openAppPending)
                .setAutoCancel(true)
                .build();
    }

    private Notification.Action buildStopAction() {
        Intent stopIntent = new Intent(this, AdbPairingService.class);
        stopIntent.setAction(ACTION_STOP);
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | immutable);
        return new Notification.Action.Builder(null, "Cancel", stopPending).build();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void updateNotification(Notification notification) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, notification);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ADB Pairing", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
}
