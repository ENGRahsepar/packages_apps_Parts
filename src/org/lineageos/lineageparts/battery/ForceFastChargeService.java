/*
 * SPDX-FileCopyrightText: 2026 ENGRahsepar
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.battery;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/** Applies a conservative 25 W-class current cap only during USB-PD charging. */
public final class ForceFastChargeService extends Service {
    private static final String TAG = "ForceFastCharge";
    private static final String EXTRA_SET_ENABLED = "set_enabled";
    private static final String KEY_PREVIOUS_LIMIT = "force_fast_charge_previous_limit";

    public static final String KEY_ENABLED = "force_fast_charge";
    public static final String NODE_CURRENT_LIMIT =
            "/sys/class/power_supply/battery/constant_charge_current";
    public static final String NODE_REAL_TYPE = "/sys/class/power_supply/usb/real_type";

    /* 6 A at approximately 4.2 V is approximately 25 W at the battery. */
    public static final int FAST_CHARGE_LIMIT_UA = 6_000_000;
    public static final int MAX_TEMPERATURE = 500; // tenths of a degree Celsius

    private static final long POLL_INTERVAL_MS = 5_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mReceiverRegistered;

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            evaluate(intent);
        }
    };

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            evaluate(null);
            if (isEnabled(ForceFastChargeService.this)) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    public static boolean isEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(KEY_ENABLED, false);
    }

    public static boolean isUsbPd(String realType) {
        return realType != null && realType.startsWith("USB_PD");
    }

    public static void start(Context context, boolean enabled) {
        final Intent intent = new Intent(context, ForceFastChargeService.class)
                .putExtra(EXTRA_SET_ENABLED, enabled);
        try {
            context.startService(intent);
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start charging monitor", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        registerReceiver(mBatteryReceiver, filter);
        mReceiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_SET_ENABLED)) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean(KEY_ENABLED, intent.getBooleanExtra(EXTRA_SET_ENABLED, false))
                    .commit();
        }
        mHandler.removeCallbacks(mPoll);
        evaluate(null);
        if (isEnabled(this)) {
            mHandler.postDelayed(mPoll, POLL_INTERVAL_MS);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mPoll);
        if (mReceiverRegistered) {
            unregisterReceiver(mBatteryReceiver);
            mReceiverRegistered = false;
        }
        restorePreviousLimit(PreferenceManager.getDefaultSharedPreferences(this));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void evaluate(Intent batteryIntent) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!isEnabled(this)) {
            restorePreviousLimit(prefs);
            stopSelf();
            return;
        }

        final Intent battery = batteryIntent != null ? batteryIntent
                : registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        final int plugged = battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) : 0;
        final int temperature = battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;

        if (temperature >= MAX_TEMPERATURE) {
            Log.w(TAG, "Battery reached 50 C; disabling Force Fast Charge");
            prefs.edit().putBoolean(KEY_ENABLED, false).commit();
            restorePreviousLimit(prefs);
            stopSelf();
            return;
        }

        final boolean pdConnected = plugged != 0 && isUsbPd(readNode(NODE_REAL_TYPE));
        final boolean safeTemperature = temperature >= 0 && temperature < MAX_TEMPERATURE;
        if (pdConnected && safeTemperature) {
            if (!prefs.contains(KEY_PREVIOUS_LIMIT)) {
                final int previous = readCurrentLimit();
                if (previous <= 0) {
                    Log.w(TAG, "Cannot read the stock charging-current limit");
                    return;
                }
                // Persist before changing the hardware limit so it can always be restored.
                prefs.edit().putInt(KEY_PREVIOUS_LIMIT, previous).commit();
            }
            writeLimit(FAST_CHARGE_LIMIT_UA);
        } else {
            restorePreviousLimit(prefs);
        }
    }

    private void restorePreviousLimit(SharedPreferences prefs) {
        final int previous = prefs.getInt(KEY_PREVIOUS_LIMIT, -1);
        if (previous > 0 && writeLimit(previous)) {
            prefs.edit().remove(KEY_PREVIOUS_LIMIT).apply();
        }
    }

    private int readCurrentLimit() {
        try {
            return Integer.parseInt(readNode(NODE_CURRENT_LIMIT));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean writeLimit(int microAmps) {
        if (readCurrentLimit() == microAmps) {
            return true;
        }
        try (FileWriter writer = new FileWriter(NODE_CURRENT_LIMIT)) {
            writer.write(Integer.toString(microAmps));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Unable to write charging-current limit", e);
            return false;
        }
    }

    private static String readNode(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }
}
