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

import com.android.email.Controller;
import com.android.email.R;
import com.android.email.Utility;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Browser;

/**
 * Various methods that are used by both 1-pane and 2-pane activities.
 *
 * <p>Common code used by {@link MessageListXL}, {@link MessageList} and other activities go here.
 * Probably there's a nicer way to do this, if we re-design these classes more throughly.
 * However, without knowing what the phone UI will be, all such work can easily end up being
 * over-designed or totally useless.  For now this pattern will do...
 */
public final class ActivityHelper {
    /**
     * Loader IDs have to be unique in a fragment.  We reserve ID(s) here for loaders created
     * outside of fragments.
     */
    public static final int GLOBAL_LOADER_ID_MOVE_TO_DIALOG_LOADER = 1000;

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

    public static void deleteMessage(Activity activity, long messageId) {
        Controller.getInstance(activity).deleteMessage(messageId, -1);
        Utility.showToast(activity,
                activity.getResources().getQuantityString(R.plurals.message_deleted_toast, 1));
    }

    public static void moveMessages(Activity activity, long newMailboxId, long[] messageIds) {
        // TODO Support moving multiple messages
        Controller.getInstance(activity).moveMessage(messageIds[0], newMailboxId);
        String message = activity.getResources().getQuantityString(R.plurals.message_moved_toast,
                messageIds.length, messageIds.length , "a mailbox"); // STOPSHIP get mailbox name
        Utility.showToast(activity, message);
    }
}
