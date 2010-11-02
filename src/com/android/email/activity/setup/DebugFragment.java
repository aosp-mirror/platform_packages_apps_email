/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email.activity.setup;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.exchange.Eas;
import com.android.exchange.utility.FileLogger;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.CompoundButton.OnCheckedChangeListener;

public class DebugFragment extends Fragment implements OnCheckedChangeListener,
        View.OnClickListener {
    private TextView mVersionView;
    private CheckBox mEnableDebugLoggingView;
    private CheckBox mEnableExchangeLoggingView;
    private CheckBox mEnableExchangeFileLoggingView;
    private CheckBox mInhibitGraphicsAccelerationView;

    private Preferences mPreferences;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onCreateView");
        }
        View view = inflater.inflate(R.layout.debug, container, false);

        Context context = getActivity();
        mPreferences = Preferences.getPreferences(context);

        mVersionView = (TextView) view.findViewById(R.id.version);
        mVersionView.setText(String.format(context.getString(R.string.debug_version_fmt).toString(),
                context.getString(R.string.build_number)));

        mEnableDebugLoggingView = (CheckBox) view.findViewById(R.id.debug_logging);
        mEnableDebugLoggingView.setChecked(Email.DEBUG);

        //EXCHANGE-REMOVE-SECTION-START
        mEnableExchangeLoggingView = (CheckBox) view.findViewById(R.id.exchange_logging);
        mEnableExchangeFileLoggingView = (CheckBox) view.findViewById(R.id.exchange_file_logging);
        mEnableExchangeLoggingView.setChecked(Eas.PARSER_LOG);
        mEnableExchangeFileLoggingView.setChecked(Eas.FILE_LOG);
        //EXCHANGE-REMOVE-SECTION-END

        // Note:  To prevent recursion while presetting checkboxes, assign all listeners last
        mEnableDebugLoggingView.setOnCheckedChangeListener(this);
        //EXCHANGE-REMOVE-SECTION-START
        mEnableExchangeLoggingView.setOnCheckedChangeListener(this);
        mEnableExchangeFileLoggingView.setOnCheckedChangeListener(this);
        //EXCHANGE-REMOVE-SECTION-END

        view.findViewById(R.id.clear_webview_cache).setOnClickListener(this);

        mInhibitGraphicsAccelerationView = (CheckBox)
                view.findViewById(R.id.debug_disable_graphics_acceleration);
        mInhibitGraphicsAccelerationView.setChecked(Email.sDebugInhibitGraphicsAcceleration);
        mInhibitGraphicsAccelerationView.setOnCheckedChangeListener(this);

        return view;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.debug_logging:
                Email.DEBUG = isChecked;
                mPreferences.setEnableDebugLogging(Email.DEBUG);
                break;
            //EXCHANGE-REMOVE-SECTION-START
            case R.id.exchange_logging:
                mPreferences.setEnableExchangeLogging(isChecked);
                break;
            case R.id.exchange_file_logging:
                mPreferences.setEnableExchangeFileLogging(isChecked);
                if (!isChecked) {
                    FileLogger.close();
                }
                break;
            //EXCHANGE-REMOVE-SECTION-END
            case R.id.debug_disable_graphics_acceleration:
                Email.sDebugInhibitGraphicsAcceleration = isChecked;
                mPreferences.setInhibitGraphicsAcceleration(isChecked);
                break;
        }

        Email.updateLoggingFlags(getActivity());
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.clear_webview_cache:
                clearWebViewCache();
                break;
        }
    }

    private void clearWebViewCache() {
        WebView webview = new WebView(getActivity());
        try {
            webview.clearCache(true);
            Log.w(Email.LOG_TAG, "Cleard WebView cache.");
        } finally {
            webview.destroy();
        }
    }
}
