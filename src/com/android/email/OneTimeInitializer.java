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

package com.android.email;

import com.android.email.service.EasAuthenticatorService;
import com.android.email.service.EasAuthenticatorServiceAlternate;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Config;
import android.util.Log;

/**
 * A class that performs one-time initialization after installation.
 *
 * <p>Android doesn't offer any mechanism to trigger an app right after installation, so we use the
 * BOOT_COMPLETED broadcast intent instead.  This means, when the app is upgraded, the
 * initialization code here won't run until the device reboots.
 */
public class OneTimeInitializer extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            initialize(context);
        }
    }

    /**
     * Perform the one-time initialization.
     */
    private void initialize(Context context) {
        if (Config.LOGD) {
            Log.d(Email.LOG_TAG, "OneTimeInitializer: initializing...");
        }
        final Preferences pref = Preferences.getPreferences(context);
        int progress = pref.getOneTimeInitializationProgress();

        if (progress < 1) {
            progress = 1;
            if (VendorPolicyLoader.getInstance(context).useAlternateExchangeStrings()) {
                setComponentEnabled(context, EasAuthenticatorServiceAlternate.class, true);
                setComponentEnabled(context, EasAuthenticatorService.class, false);
            }

            ExchangeUtils.enableEasCalendarSync(context);
        }

        // If we need other initializations in the future...
        // - add your initialization code here, and
        // - rename this class to something like "OneTimeInitializer2" (and modify AndroidManifest
        //   accordingly)
        // Renaming is necessary because once we disable a component, it won't be automatically
        // enabled again even when the app is upgraded.

        // Use "progress" to skip the initializations that's already done before.
        // Using this preference also makes it safe when a user skips an upgrade.  (i.e. upgrading
        // version N to version N+2)

        // Save progress and disable itself.
        pref.setOneTimeInitializationProgress(progress);
        setComponentEnabled(context, getClass(), false);
    }

    private void setComponentEnabled(Context context, Class<?> clazz, boolean enabled) {
        final ComponentName c = new ComponentName(context, clazz.getName());
        context.getPackageManager().setComponentEnabledSetting(c,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
