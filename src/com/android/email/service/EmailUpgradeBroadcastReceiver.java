package com.android.email.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * {@link BroadcastReceiver} for app upgrade. This listens to package replacement (for unbundled
 * upgrade) and reboot (for OTA upgrade). The code in the {@link EmailBroadcastProcessorService}
 * disables this receiver after it runs.
 */
public class EmailUpgradeBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context context, final Intent intent) {
        EmailBroadcastProcessorService.processUpgradeBroadcastIntent(context);
    }
}
