/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.imap2;

import android.accounts.AccountManager;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.android.emailcommon.Logging;
import com.android.emailsync.SyncManager;

/**
 * The service that really handles broadcast intents on a worker thread.
 *
 * We make it a service, because:
 * <ul>
 *   <li>So that it's less likely for the process to get killed.
 *   <li>Even if it does, the Intent that have started it will be re-delivered by the system,
 *   and we can start the process again.  (Using {@link #setIntentRedelivery}).
 * </ul>
 */
public class BroadcastProcessorService extends IntentService {
    // Action used for BroadcastReceiver entry point
    private static final String ACTION_BROADCAST = "broadcast_receiver";

    public BroadcastProcessorService() {
        // Class name will be the thread name.
        super(BroadcastProcessorService.class.getName());
        // Intent should be redelivered if the process gets killed before completing the job.
        setIntentRedelivery(true);
    }

    /**
     * Entry point for {@link Imap2BroadcastReceiver}.
     */
    public static void processBroadcastIntent(Context context, Intent broadcastIntent) {
        Intent i = new Intent(context, BroadcastProcessorService.class);
        i.setAction(ACTION_BROADCAST);
        i.putExtra(Intent.EXTRA_INTENT, broadcastIntent);
        context.startService(i);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // Dispatch from entry point
        final String action = intent.getAction();
        if (ACTION_BROADCAST.equals(action)) {
            final Intent broadcastIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            final String broadcastAction = broadcastIntent.getAction();

            if (Intent.ACTION_BOOT_COMPLETED.equals(broadcastAction)) {
                onBootCompleted();
            } else if (AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION.equals(broadcastAction)) {
                Log.d(Logging.LOG_TAG, "Login accounts changed; reconciling...");
                SyncManager.reconcileAccounts(this);
            }
       }
    }

    /**
     * Handles {@link Intent#ACTION_BOOT_COMPLETED}.  Called on a worker thread.
     */
    private void onBootCompleted() {
        startService(new Intent(this, Imap2SyncManager.class));
    }
}
