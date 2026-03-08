package com.example.adbshelltool;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "adb_prefs";
    private static final String PREF_CONNECTION_PORT = "connection_port";

    private ExecutorService executor;
    private CommandExecutor commandExecutor;

    // Sections
    private View layoutConnected;
    private View layoutPairing;
    private View layoutConnection;

    private TextView tvConnectedStatus;
    private EditText editConnectionPort;
    private TextView tvPairingStatus;
    private TextView tvConnectionStatus;
    private EditText editCommand;
    private TextView tvOutput;

    private ActivityResultLauncher<String> notifPermissionLauncher;

    private final BroadcastReceiver connectedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int port = intent.getIntExtra(AdbPairingService.EXTRA_CONNECTED_PORT, -1);
            if (port > 0) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putInt(PREF_CONNECTION_PORT, port).apply();
                runOnUiThread(() -> {
                    editConnectionPort.setText(String.valueOf(port));
                    showConnected("Connected — port " + port);
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        executor = Executors.newSingleThreadExecutor();
        commandExecutor = new CommandExecutor(this);

        layoutConnected    = findViewById(R.id.layoutConnected);
        layoutPairing      = findViewById(R.id.layoutPairing);
        layoutConnection   = findViewById(R.id.layoutConnection);
        tvConnectedStatus  = findViewById(R.id.tvConnectedStatus);
        tvPairingStatus    = findViewById(R.id.tvPairingStatus);
        editConnectionPort = findViewById(R.id.editConnectionPort);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        editCommand        = findViewById(R.id.editCommand);
        tvOutput           = findViewById(R.id.tvOutput);

        Button btnStartPairing = findViewById(R.id.btnStartPairing);
        Button btnConnect      = findViewById(R.id.btnConnect);
        Button btnDisconnect   = findViewById(R.id.btnDisconnect);
        Button btnExecute      = findViewById(R.id.btnExecute);

        // Restore saved port
        int savedPort = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_CONNECTION_PORT, 0);
        if (savedPort > 0) editConnectionPort.setText(String.valueOf(savedPort));

        // Reflect current connection state
        if (AdbManager.getInstance(this).isConnected()) {
            showConnected("Connected — port " + savedPort);
        } else {
            showDisconnected();
        }

        // Register for auto-connection broadcast from service
        IntentFilter filter = new IntentFilter(AdbPairingService.ACTION_CONNECTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectedReceiver, filter);
        }

        notifPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) launchPairingService();
                    else {
                        tvPairingStatus.setText("Notification permission denied.");
                        tvPairingStatus.setTextColor(getColor(android.R.color.holo_red_dark));
                    }
                });

        btnStartPairing.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
            }
            launchPairingService();
        });

        btnConnect.setOnClickListener(v -> {
            String portStr = editConnectionPort.getText().toString().trim();
            if (portStr.isEmpty()) {
                tvConnectionStatus.setText("Enter a port");
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                return;
            }
            int port;
            try { port = Integer.parseInt(portStr); }
            catch (NumberFormatException e) {
                tvConnectionStatus.setText("Invalid port");
                tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark));
                return;
            }
            btnConnect.setEnabled(false);
            tvConnectionStatus.setText("Connecting...");
            tvConnectionStatus.setTextColor(getColor(android.R.color.darker_gray));

            final int finalPort = port;
            executor.submit(() -> {
                try {
                    AdbManager.getInstance(this).connect(finalPort);
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putInt(PREF_CONNECTION_PORT, finalPort).apply();
                    runOnUiThread(() -> {
                        btnConnect.setEnabled(true);
                        showConnected("Connected — port " + finalPort);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        tvConnectionStatus.setText("Failed: " + e.getMessage());
                        tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark));
                        btnConnect.setEnabled(true);
                    });
                }
            });
        });

        btnDisconnect.setOnClickListener(v -> {
            AdbManager.getInstance(this).disconnect();
            showDisconnected();
        });

        btnExecute.setOnClickListener(v -> {
            String command = editCommand.getText().toString().trim();
            if (command.isEmpty()) { tvOutput.setText("Enter a command first"); return; }
            if (!AdbManager.getInstance(this).isConnected()) {
                tvOutput.setText("Not connected. Pair and connect first.");
                return;
            }
            tvOutput.setText("Running: " + command + "\n...");
            btnExecute.setEnabled(false);
            commandExecutor.execute(command, new CommandExecutor.Callback() {
                @Override public void onSuccess(String output) {
                    runOnUiThread(() -> {
                        tvOutput.setText(output.isEmpty() ? "(no output)" : output);
                        btnExecute.setEnabled(true);
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> {
                        tvOutput.setText("ERROR:\n" + error);
                        btnExecute.setEnabled(true);
                    });
                }
            });
        });
    }

    /** Show connected state: hide pairing & connection sections, show status bar. */
    private void showConnected(String label) {
        tvConnectedStatus.setText(label);
        layoutConnected.setVisibility(View.VISIBLE);
        layoutPairing.setVisibility(View.GONE);
        layoutConnection.setVisibility(View.GONE);
    }

    /** Show disconnected state: show pairing & connection sections, hide status bar. */
    private void showDisconnected() {
        layoutConnected.setVisibility(View.GONE);
        layoutPairing.setVisibility(View.VISIBLE);
        layoutConnection.setVisibility(View.VISIBLE);
    }

    private void launchPairingService() {
        Intent intent = new Intent(this, AdbPairingService.class);
        intent.setAction(AdbPairingService.ACTION_START);
        startForegroundService(intent);
        tvPairingStatus.setText(
                "Waiting for pairing session...\n"
                + "Go to Settings › Wireless Debugging › \"Pair with code\".\n"
                + "Type the 6-digit code in the notification that appears.");
        tvPairingStatus.setTextColor(getColor(android.R.color.holo_blue_dark));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(connectedReceiver);
        if (executor != null) executor.shutdownNow();
        if (commandExecutor != null) commandExecutor.shutdown();
        AdbManager.getInstance(this).disconnect();
    }
}
