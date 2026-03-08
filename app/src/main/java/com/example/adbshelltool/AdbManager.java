package com.example.adbshelltool;

import android.content.Context;

import io.github.muntashirakon.adb.AdbStream;

/**
 * AdbManager — thin facade over MyAdbConnectionManager.
 *
 * Responsibilities:
 *  1. Pairing:    authenticate the app with the ADB daemon using SPAKE2 + PIN
 *  2. Connect:    open the ADB session once pairing is done
 *  3. Disconnect: close the session when no longer needed
 *  4. openStream: open a channel to an ADB service (e.g. "shell:id")
 *
 * How the two-phase ADB protocol works:
 * ----------------------------------------
 * PHASE 1 — PAIRING (first time only):
 *   Android 11+ ADB daemon listens on TWO separate ports:
 *     - Pairing port (e.g. 37829): used only for the SPAKE2 key exchange.
 *       The user sees this port and a 6-digit PIN in Settings > Wireless Debugging > Pair.
 *     - Session port (e.g. 42135): used for real ADB sessions (shell, sync, etc.)
 *
 *   During pairing:
 *     - Client and daemon derive a symmetric key from the PIN.
 *     - TLS certificates are exchanged over that key.
 *     - The daemon stores the client certificate as trusted.
 *     - From then on the client can connect without a PIN.
 *
 * PHASE 2 — CONNECTION (every time ADB is used):
 *   - Client connects to the session port with TLS.
 *   - Can then open streams (shell, sync, etc.).
 */
public class AdbManager {

    // Host is always localhost: the app connects to the ADB daemon on the same device
    public static final String ADB_HOST = "127.0.0.1";

    // Singleton
    private static AdbManager instance;

    private final Context context;

    private AdbManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized AdbManager getInstance(Context context) {
        if (instance == null) {
            instance = new AdbManager(context);
        }
        return instance;
    }

    /**
     * Phase 1: Pair the app with the ADB daemon using a PIN code.
     * Blocks the calling thread — do NOT call from the UI thread.
     *
     * @param port  Pairing port shown in Settings > Wireless Debugging > Pair with code
     * @param code  6-digit PIN
     * @throws Exception if pairing fails
     */
    public void pair(int port, String code) throws Exception {
        MyAdbConnectionManager.getInstance(context).pair(ADB_HOST, port, code);
    }

    /**
     * Phase 2: Open the ADB session with the daemon.
     * Requires pairing to have been completed at least once.
     * Blocks the calling thread — do NOT call from the UI thread.
     *
     * @param port Session port shown on the main Wireless Debugging screen
     * @throws Exception if the connection fails
     */
    public void connect(int port) throws Exception {
        MyAdbConnectionManager.getInstance(context).connect(ADB_HOST, port);
    }

    /** Closes the active ADB session. Safe to call even if not connected. */
    public void disconnect() {
        try {
            MyAdbConnectionManager.getInstance(context).disconnect();
        } catch (Exception ignored) {}
    }

    /**
     * Opens an ADB stream to the given service.
     * An ADB stream is a bidirectional channel to a service:
     *   "shell:<cmd>"  → runs a command as UID shell (2000)
     *   "shell:"       → interactive shell
     *
     * @param service service identifier (e.g. "shell:id")
     * @return ready-to-use AdbStream
     * @throws Exception if not connected or the service does not exist
     */
    public AdbStream openStream(String service) throws Exception {
        return MyAdbConnectionManager.getInstance(context).openStream(service);
    }

    /** @return true if there is an active ADB session */
    public boolean isConnected() {
        try {
            return MyAdbConnectionManager.getInstance(context).isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
