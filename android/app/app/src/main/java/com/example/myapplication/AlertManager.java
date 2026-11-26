package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Singleton manager responsible for background alert polling.
 *
 * <p>The manager periodically requests the Flask /alerts endpoint, stores the
 * unseen alerts in a short history list, updates the active UI listener, and
 * broadcasts qualifying events to AlertReceiver for Android notifications.</p>
 */
public class AlertManager {

    private static final String TAG = "AlertManager";
    private static AlertManager instance;

    private final OkHttpClient client = new OkHttpClient();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedList<String> alertHistory = new LinkedList<>();

    private static final int MAX_SIZE = 10;
    private static final int INTERVAL_MS = 1000;

    // Replace with the Flask endpoint used in your local environment.
    private static final String FLASK_URL = "http://server-ip:5000/alerts/sync";

    // One request at a time; cursor and history updates run on the main thread.
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private String streamId;
    private long lastProcessedId = 0;

    private boolean isRunning = false;
    private Runnable fetchTask;
    private OnAlertUpdateListener listener;
    private Context appContext;

    private AlertManager() {}

    public static synchronized AlertManager getInstance() {
        if (instance == null) {
            instance = new AlertManager();
        }
        return instance;
    }

    /** Start periodic background polling. */
    public void start(Context context) {
        if (isRunning) {
            return;
        }

        isRunning = true;
        appContext = context.getApplicationContext();

        fetchTask = new Runnable() {
            @Override
            public void run() {
                fetchFromFlask();
                handler.postDelayed(this, INTERVAL_MS);
            }
        };

        handler.post(fetchTask);
        Log.d(TAG, "AlertManager started background polling.");
    }

    /** Stop periodic background polling. */
    public void stop() {
        if (!isRunning) {
            return;
        }

        handler.removeCallbacks(fetchTask);
        isRunning = false;
        Log.d(TAG, "AlertManager stopped background polling.");
    }

    /** Register or clear the active UI listener. */
    public void setOnAlertUpdateListener(OnAlertUpdateListener listener) {
        this.listener = listener;

        if (listener != null) {
            listener.onAlertUpdate(historySnapshot());
        }
    }

    /** Fetch one bounded snapshot without overlapping network requests. */
    private void fetchFromFlask() {
        if (!requestInFlight.compareAndSet(false, true)) {
            return;
        }

        try {
            Request request = new Request.Builder().url(FLASK_URL).build();
            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    requestInFlight.set(false);
                    Log.e(TAG, "Flask connection failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    boolean posted = false;
                    try (Response closeableResponse = response) {
                        if (!response.isSuccessful() || response.body() == null) {
                            Log.e(TAG, "Server response error: " + response.code());
                            return;
                        }

                        JSONObject snapshot = new JSONObject(response.body().string());
                        posted = handler.post(() -> {
                            try {
                                if (isRunning) {
                                    processSnapshot(snapshot);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Event synchronization failed", e);
                            } finally {
                                requestInFlight.set(false);
                            }
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Alert response parsing failed", e);
                    } finally {
                        if (!posted) {
                            requestInFlight.set(false);
                        }
                    }
                }
            });
        } catch (RuntimeException e) {
            requestInFlight.set(false);
            Log.e(TAG, "Unable to schedule alert request", e);
        }
    }

    /** Apply new events in ascending ID order, once per running client. */
    private void processSnapshot(JSONObject snapshot) throws Exception {
        String incomingStream = snapshot.getString("stream_id");
        JSONArray events = snapshot.getJSONArray("events");

        if (!incomingStream.equals(streamId)) {
            if (streamId != null) {
                Log.w(TAG, "Backend restarted; earlier in-memory events may be unavailable.");
            }
            streamId = incomingStream;
            lastProcessedId = 0;
        }

        boolean changed = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            long id = event.getLong("id");
            if (id <= lastProcessedId) {
                continue;
            }

            String message = event.getString("message");
            if (id > lastProcessedId + 1) {
                Log.w(TAG, "Event gap: expected " + (lastProcessedId + 1)
                        + ", received " + id + ". Older events may have expired.");
            }

            String timestamp = new SimpleDateFormat(
                    "HH:mm:ss", Locale.getDefault()).format(new Date());
            String record = "[" + timestamp + "] " + message;

            synchronized (alertHistory) {
                alertHistory.addFirst(record);
                if (alertHistory.size() > MAX_SIZE) {
                    alertHistory.removeLast();
                }
            }
            lastProcessedId = id;
            changed = true;

            // Each new event is evaluated, even if it falls outside the 10-record UI.
            try {
                if (appContext != null && shouldNotify(message)) {
                    Intent alertIntent = new Intent(appContext, AlertReceiver.class);
                    alertIntent.putExtra("alert_message", record);
                    appContext.sendBroadcast(alertIntent);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Notification broadcast failed for event " + id, e);
            }
        }

        // Refresh the UI once per batch, not once per historical event.
        if (changed && listener != null) {
            listener.onAlertUpdate(historySnapshot());
        }
    }

    private LinkedList<String> historySnapshot() {
        synchronized (alertHistory) {
            return new LinkedList<>(alertHistory);
        }
    }

    /**
     * Determine whether the latest backend message should trigger a system notification.
     *
     * <p>The decision is based on the master notification switch, predefined keyword
     * filters, and optional user-defined keywords stored in SharedPreferences.</p>
     */
    private boolean shouldNotify(String latestMsg) {
        if (appContext == null) {
            return false;
        }

        SharedPreferences prefs =
                appContext.getSharedPreferences(
                        Setting.PREFS_NAME,
                        Context.MODE_PRIVATE
                );

        // Master notification switch.
        boolean enableNotification =
                prefs.getBoolean(Setting.KEY_ENABLE_NOTIFICATION, true);

        if (!enableNotification) {
            return false;
        }

        // Predefined keyword filters.
        boolean kwFall = prefs.getBoolean(Setting.KEY_KW_FALL, true);
        boolean kwAlarm = prefs.getBoolean(Setting.KEY_KW_ALARM, true);
        boolean kwStay = prefs.getBoolean(Setting.KEY_KW_STAY, true);
        boolean kwIdle = prefs.getBoolean(Setting.KEY_KW_IDLE, false);

        if (kwFall && containsKeyword(latestMsg, "跌倒")) {
            return true;
        }

        if (kwAlarm && containsKeyword(latestMsg, "警報")) {
            return true;
        }

        if (kwStay && containsKeyword(latestMsg, "靜止")) {
            return true;
        }

        if (kwIdle && containsKeyword(latestMsg, "久未移動")) {
            return true;
        }

        // Additional custom keywords are stored as a comma-separated list.
        String custom = prefs.getString(Setting.KEY_KW_EXTRA, "");

        if (!custom.isEmpty()) {
            String[] keywords = custom.split(",");

            for (String keyword : keywords) {
                keyword = keyword.trim();

                if (!keyword.isEmpty() && containsKeyword(latestMsg, keyword)) {
                    return true;
                }
            }
        }

        return false;
    }

    /** Case-insensitive substring matching for alert keywords. */
    private boolean containsKeyword(String msg, String keyword) {
        if (msg == null || keyword == null) {
            return false;
        }

        return msg.toLowerCase().contains(keyword.toLowerCase());
    }

    /** Listener used by Activities that display the current alert history. */
    public interface OnAlertUpdateListener {
        void onAlertUpdate(LinkedList<String> alerts);
    }

    /** Add a test alert locally without contacting the Flask backend. */
    public void injectTestAlert(String msg) {
        String timestamp = new SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
        ).format(new Date());

        String record = "[" + timestamp + "] " + msg;

        synchronized (alertHistory) {
            alertHistory.addFirst(record);

            if (alertHistory.size() > MAX_SIZE) {
                alertHistory.removeLast();
            }
        }

        if (listener != null) {
            listener.onAlertUpdate(historySnapshot());
        }
    }
}
