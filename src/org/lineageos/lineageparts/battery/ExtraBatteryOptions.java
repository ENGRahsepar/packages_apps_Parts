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

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;
import org.lineageos.lineageparts.search.BaseSearchIndexProvider;
import org.lineageos.lineageparts.search.Searchable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Extra Battery Options (Settings > Battery).
 * Bypass charging toggle: writes 0/1 straight to the kernel node.
 * Enabled only while a charger is connected; on unplug the node is
 * forced back to 0 so the battery can never be left idle sourceless.
 */
public class ExtraBatteryOptions extends SettingsPreferenceFragment implements
        Preference.OnPreferenceChangeListener, Searchable {
    private static final String TAG = ExtraBatteryOptions.class.getSimpleName();

    private static final String KEY_BYPASS = "bypass_charging";
    private static final String KEY_STATUS = "bypass_status";

    private static final String NODE_BYPASS = "/sys/class/power_supply/usb/bypass_charging";

    private SwitchPreferenceCompat mBypassPref;
    private Preference mStatusPref;

    private boolean mLastCharging = false;
    private boolean mLastActive = false;
    private boolean mUiInit = false;

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
        mStatusPref = prefSet.findPreference(KEY_STATUS);
        mBypassPref.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        requireContext().registerReceiver(mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        refreshUi();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(mBatteryReceiver);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mBypassPref) {
            final boolean enable = (Boolean) newValue;
            // Gate: only while charging.
            if (enable && !isCharging()) {
                return false;
            }
            writeNode(enable ? "1" : "0");
            refreshUi();
            return true;
        }
        return false;
    }

    private void refreshUi() {
        final boolean charging = isCharging();
        final boolean bypass = "1".equals(readNode().trim());
        if (!charging && bypass) {
            // Safety: never leave the battery idle with no source attached.
            writeNode("0");
        }
        final boolean active = charging && "1".equals(readNode().trim());
        // Battery broadcasts fire often; only touch views on real change to
        // avoid RecyclerView churn (overlapping text while scrolling).
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

    private boolean isCharging() {
        final Intent battery = requireContext().registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery == null) {
            return false;
        }
        final int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    private String readNode() {
        try (BufferedReader reader = new BufferedReader(new FileReader(NODE_BYPASS))) {
            final String line = reader.readLine();
            return line == null ? "" : line;
        } catch (Exception e) {
            return "";
        }
    }

    private void writeNode(String value) {
        try (FileWriter writer = new FileWriter(NODE_BYPASS)) {
            writer.write(value);
        } catch (Exception e) {
            // Node missing or unwritable (old kernel): UI stays truthful on next refresh.
        }
    }

    public static final Searchable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
            new BaseSearchIndexProvider();
}
