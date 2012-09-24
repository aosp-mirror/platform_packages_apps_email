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

package com.android.email.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Browser;
import android.view.WindowManager;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.setup.AccountSecurity;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

/**
 * Various methods that are used by both 1-pane and 2-pane activities.
 *
 * Common code used by the activities and the fragments.
 */
public final class ActivityHelper {
    private ActivityHelper() {
    }

    /**
     * Open an URL in a message.
     *
     * This is intended to mirror the operation of the original
     * (see android.webkit.CallbackProxy) with one addition of intent flags
     * "FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET".  This improves behavior when sublaunching
     * other apps via embedded URI's.
     *
     * We also use this hook to catch "mailto:" links and handle them locally.
     *
     * @param activity parent activity
     * @param url URL to open
     * @param senderAccountId if the URL is mailto:, we use this account as the sender.
     *        TODO When MessageCompose implements the account selector, this won't be necessary.
     *        Pass {@link Account#NO_ACCOUNT} to use the default account.
     * @return true if the URI has successfully been opened.
     */
    public static boolean openUrlInMessage(Activity activity, String url, long senderAccountId) {
        // hijack mailto: uri's and handle locally
        if (url != null && url.toLowerCase().startsWith("mailto:")) {
            return MessageCompose.actionCompose(activity, url, senderAccountId);
        }

        // Handle most uri's via intent launch
        boolean result = false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, activity.getPackageName());
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        try {
            activity.startActivity(intent);
            result = true;
        } catch (ActivityNotFoundException ex) {
            // No applications can handle it.  Ignore.
        }
        return result;
    }

    /**
     * Open Calendar app with specific time
     */
    public static void openCalendar(Activity activity, long epochEventStartTime) {
        Uri uri = Uri.parse("content://com.android.calendar/time/" + epochEventStartTime);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.putExtra("VIEW", "DAY");
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        activity.startActivity(intent);
    }

    public static void deleteMessage(Context context, long messageId) {
        Controller.getInstance(context).deleteMessage(messageId);
        Utility.showToast(context,
                context.getResources().getQuantityString(R.plurals.message_deleted_toast, 1));
    }

    public static void moveMessages(final Context context, final long newMailboxId,
            final long[] messageIds) {
        Controller.getInstance(context).moveMessages(messageIds, newMailboxId);
        EmailAsyncTask.runAsyncSerial(new Runnable() {
            @Override
            public void run() {
                String mailboxName = Mailbox.getDisplayName(context, newMailboxId);
                if (mailboxName == null) {
                    return; // Mailbox gone??
                }
                String message = context.getResources().getQuantityString(
                        R.plurals.message_moved_toast, messageIds.length, messageIds.length ,
                        mailboxName);
                Utility.showToast(context, message);
            }
        });
    }

    /**
     * If configured via debug flags, inhibit hardware graphics acceleration.  Must be called
     * early in onCreate().
     *
     * NOTE: Currently, this only works if HW accel is *not* enabled via the manifest.
     */
    public static void debugSetWindowFlags(Activity activity) {
        if (Email.sDebugInhibitGraphicsAcceleration) {
            // Clear the flag in the activity's window
            activity.getWindow().setFlags(0,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        } else {
            // Set the flag in the activity's window
            activity.getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
    }

    public static void showSecurityHoldDialog(Activity callerActivity, long accountId) {
        callerActivity.startActivity(
                AccountSecurity.actionUpdateSecurityIntent(callerActivity, accountId, true));
    }

}
