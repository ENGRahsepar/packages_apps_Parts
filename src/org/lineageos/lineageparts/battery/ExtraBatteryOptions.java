/*
 * SPDX-FileCopyrightText: 2026 ENGRahsepar
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.lineageparts.battery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;
import org.lineageos.lineageparts.search.BaseSearchIndexProvider;
import org.lineageos.lineageparts.search.Searchable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

/** Extra Battery Options (Settings > Battery). */
public class ExtraBatteryOptions extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Searchable {
    private static final String TAG = ExtraBatteryOptions.class.getSimpleName();

    private static final String KEY_BYPASS = "bypass_charging";
    private static final String KEY_STATUS = "bypass_status";
    private static final String KEY_FAST_STATUS = "force_fast_charge_status";

    private static final String NODE_BYPASS = "/sys/class/power_supply/usb/bypass_charging";

    private SwitchPreferenceCompat mBypassPref;
    private SwitchPreferenceCompat mFastChargePref;
    private Preference mStatusPref;
    private Preference mFastStatusPref;

    private boolean mLastCharging = false;
    private boolean mLastActive = false;
    private boolean mUiInit = false;
    private String mLastFastSignature = "";

    private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshUi();
        }
    };

    @Override
    public void onActivityCreated(final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        addPreferencesFromResource(R.xml.extra_battery_options);
        requireActivity().getActionBar().setTitle(R.string.extra_battery_title);

        final PreferenceScreen prefSet = getPreferenceScreen();
        mBypassPref = prefSet.findPreference(KEY_BYPASS);
        mFastChargePref = prefSet.findPreference(ForceFastChargeService.KEY_ENABLED);
        mStatusPref = prefSet.findPreference(KEY_STATUS);
        mFastStatusPref = prefSet.findPreference(KEY_FAST_STATUS);
        mBypassPref.setOnPreferenceChangeListener(this);
        mFastChargePref.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        requireContext().registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (ForceFastChargeService.isEnabled(requireContext())) {
            ForceFastChargeService.start(requireContext(), true);
        }
        refreshUi();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(mBatteryReceiver);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final boolean enable = (Boolean) newValue;
        if (preference == mBypassPref) {
            if (enable && !isCharging()) {
                return false;
            }
            writeNode(NODE_BYPASS, enable ? "1" : "0");
            refreshUi();
            return true;
        }
        if (preference == mFastChargePref) {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putBoolean(ForceFastChargeService.KEY_ENABLED, enable).apply();
            if (enable && getBatteryTemperature() >= ForceFastChargeService.MAX_TEMPERATURE) {
                Toast.makeText(requireContext(), R.string.fast_charge_temperature_unavailable,
                        Toast.LENGTH_SHORT).show();
                refreshUi();
                return false;
            }
            ForceFastChargeService.start(requireContext(), enable);
            refreshUi();
            return true;
        }
        return false;
    }

    private void refreshUi() {
        final boolean charging = isCharging();
        refreshFastChargeUi(charging);

        final boolean bypass = "1".equals(readNode(NODE_BYPASS).trim());
        if (!charging && bypass) {
            writeNode(NODE_BYPASS, "0");
        }
        final boolean active = charging && "1".equals(readNode(NODE_BYPASS).trim());
        if (mUiInit && charging == mLastCharging && active == mLastActive) {
            return;
        }
        mUiInit = true;
        mLastCharging = charging;
        mLastActive = active;
        mBypassPref.setEnabled(charging);
        mBypassPref.setChecked(active);
        if (!charging) {
            mBypassPref.setSummary(R.string.bypass_summary_unplugged);
        } else if (active) {
            mBypassPref.setSummary(R.string.bypass_summary_on);
        } else {
            mBypassPref.setSummary(R.string.bypass_summary_off);
        }
        mStatusPref.setSummary(getString(R.string.bypass_status_value,
                active ? "1" : "0", charging ? getString(R.string.bypass_charging_yes)
                        : getString(R.string.bypass_charging_no)));
    }

    private void refreshFastChargeUi(boolean charging) {
        final boolean enabled = ForceFastChargeService.isEnabled(requireContext());
        final int temperature = getBatteryTemperature();
        final String realType = readNode(ForceFastChargeService.NODE_REAL_TYPE);
        final boolean pd = ForceFastChargeService.isUsbPd(realType);
        final int limit = readIntNode(ForceFastChargeService.NODE_CURRENT_LIMIT);
        final boolean active = enabled && charging && pd && temperature >= 0
                && temperature < ForceFastChargeService.MAX_TEMPERATURE
                && limit == ForceFastChargeService.FAST_CHARGE_LIMIT_UA;
        final String signature = enabled + "|" + charging + "|" + realType + "|"
                + temperature + "|" + limit;
        if (signature.equals(mLastFastSignature)) {
            return;
        }
        mLastFastSignature = signature;

        mFastChargePref.setEnabled(temperature < 0
                || temperature < ForceFastChargeService.MAX_TEMPERATURE);
        mFastChargePref.setChecked(enabled);
        if (temperature >= ForceFastChargeService.MAX_TEMPERATURE) {
            mFastChargePref.setSummary(R.string.fast_charge_summary_temperature);
        } else if (!enabled) {
            mFastChargePref.setSummary(R.string.fast_charge_summary_off);
        } else if (active) {
            mFastChargePref.setSummary(R.string.fast_charge_summary_active);
        } else if (!charging || !pd) {
            mFastChargePref.setSummary(R.string.fast_charge_summary_waiting_pd);
        } else {
            mFastChargePref.setSummary(R.string.fast_charge_summary_waiting_safe);
        }

        final String limitText = limit > 0 ? Integer.toString(limit / 1000) : "?";
        final String temperatureText = temperature >= 0
                ? String.format(Locale.US, "%.1f", temperature / 10.0f) : "?";
        mFastStatusPref.setSummary(getString(R.string.fast_charge_status_value,
                realType.isEmpty() ? "Unknown" : realType, limitText, temperatureText));
    }

    private boolean isCharging() {
        final Intent battery = requireContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return false;
        }
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private int getBatteryTemperature() {
        final Intent battery = requireContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) : -1;
    }

    private static String readNode(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final String line = reader.readLine();
            return line == null ? "" : line;
        } catch (Exception e) {
            return "";
        }
    }

    private static int readIntNode(String path) {
        try {
            return Integer.parseInt(readNode(path).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void writeNode(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
        } catch (Exception e) {
            // Node missing or unwritable: UI reads the real state on next refresh.
        }
    }

    public static final Searchable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider();
}
