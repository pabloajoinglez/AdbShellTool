package com.example.adbshelltool;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbStream;

/**
 * CommandExecutor — runs shell commands over the ADB session opened by AdbManager.
 *
 * How a command is executed over ADB:
 *   1. We open a stream for "shell:<command>"
 *   2. The ADB daemon spawns /system/bin/sh with that command
 *   3. stdin/stdout/stderr of the process are wired to the ADB stream
 *   4. When the process exits the stream is closed
 *
 * The process runs as UID 2000 (shell), which can:
 *   - Inject input events (taps, swipes, keys)
 *   - Install/uninstall APKs
 *   - List and manage processes
 *   - Access system settings
 *
 * A single-thread executor ensures commands run sequentially.
 */
public class CommandExecutor {

    // Single thread so commands never overlap
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Context context;

    public interface Callback {
        /** Called with the full output (stdout + stderr combined) when the command finishes. */
        void onSuccess(String output);
        /** Called when an ADB communication error occurs (not a shell error). */
        void onError(String error);
    }

    public CommandExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Runs a shell command asynchronously and delivers the result via callback.
     * Returns immediately; the callback is invoked on the executor thread (not the UI thread).
     *
     * @param command  command to run, e.g. "id", "input tap 500 500", "pm list packages"
     * @param callback receives the result or error
     */
    public void execute(String command, Callback callback) {
        executor.submit(() -> {
            AdbStream stream = null;
            try {
                // Wrap the command so there is always a sentinel written to stdout.
                // This is necessary for commands that produce no output (e.g. "input tap")
                // which would otherwise cause read() to block forever waiting for EOF.
                // stderr is also redirected to stdout so errors are visible.
                String wrappedCommand = "(" + command + ") 2>&1; echo '---CMD_END---'";
                stream = AdbManager.getInstance(context).openStream("shell:" + wrappedCommand);

                StringBuilder output = new StringBuilder();
                InputStream inputStream = stream.openInputStream();
                byte[] buffer = new byte[4096];
                int bytesRead;

                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    output.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                    if (output.toString().contains("---CMD_END---")) break;
                }

                String result = output.toString()
                        .replace("---CMD_END---\n", "")
                        .replace("---CMD_END---", "")
                        .trim();

                callback.onSuccess(result);

            } catch (IllegalStateException e) {
                callback.onError("No active ADB connection. Connect first.");
            } catch (IOException e) {
                callback.onError("Connection error: " + e.getMessage());
            } catch (Exception e) {
                callback.onError("Error: " + e.getMessage());
            } finally {
                if (stream != null) {
                    try { stream.close(); } catch (IOException ignored) {}
                }
            }
        });
    }

    /** Release the executor. Call from onDestroy() to avoid leaked threads. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
