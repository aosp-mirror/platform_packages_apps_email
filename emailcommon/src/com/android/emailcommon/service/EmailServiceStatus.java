/*
 *  Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.emailcommon.service;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;

/**
 * Definitions of service status codes returned to IEmailServiceCallback's status method.
 *
 * Now that all sync requests are sent through the system SyncManager, there's no way to specify the
 * {@link IEmailServiceCallback} to {@link ContentResolver#requestSync} since all we have is a
 * {@link Bundle}. Instead, the caller requesting the sync specifies values with which to call
 * {@link ContentResolver#call} in order to receive a callback, and the
 * {@link android.content.ContentProvider} must handle this call.
 */
public abstract class EmailServiceStatus {
    public static final int SUCCESS = 0;
    public static final int IN_PROGRESS = 1;

    public static final int MESSAGE_NOT_FOUND = 0x10;
    public static final int ATTACHMENT_NOT_FOUND = 0x11;
    public static final int FOLDER_NOT_DELETED = 0x12;
    public static final int FOLDER_NOT_RENAMED = 0x13;
    public static final int FOLDER_NOT_CREATED = 0x14;
    public static final int REMOTE_EXCEPTION = 0x15;
    public static final int LOGIN_FAILED = 0x16;
    public static final int SECURITY_FAILURE = 0x17;
    public static final int ACCOUNT_UNINITIALIZED = 0x18;
    public static final int ACCESS_DENIED = 0x19;

    // Maybe we should automatically retry these?
    public static final int CONNECTION_ERROR = 0x20;

    // Client certificates used to authenticate cannot be retrieved from the system.
    public static final int CLIENT_CERTIFICATE_ERROR = 0x21;

    // Data is invalid on the client side, sync cannot proceed.
    public static final int HARD_DATA_ERROR = 0x22;

    // Sync failed due to some type of IO error.
    public static final int IO_ERROR = 0x23;

    // The sync call encountered a protocol error.
    public static final int PROTOCOL_ERROR = 0x24;

    // The sync call encountered too many redirects.
    public static final int TOO_MANY_REDIRECTS = 0x25;

    // The sync call encountered a provisioning error.
    public static final int PROVISIONING_ERROR = 0x26;

    // We have encountered some sort of unexpected illegal state.
    public static final int INTERNAL_ERROR = 0x27;

    // Keys for the sync extras Bundle that specify the callback.
    public static final String SYNC_EXTRAS_CALLBACK_URI = "callback_uri";
    public static final String SYNC_EXTRAS_CALLBACK_METHOD = "callback_method";
    public static final String SYNC_EXTRAS_CALLBACK_ARG = "callback_arg";

    // Keys for the status Bundle sent to the callback. These keys are used in every status type.
    public static final String SYNC_STATUS_TYPE = "type";
    public static final String SYNC_STATUS_ID = "id";
    public static final String SYNC_STATUS_CODE = "status_code";
    public static final String SYNC_RESULT = "result";
    public static final String SYNC_STATUS_PROGRESS = "progress";

    // Values for the SYNC_STATUS_TYPE to specify what kind of sync status we're returning.
    public static final int SYNC_STATUS_TYPE_MAILBOX = 0;

    /**
     * Some status updates need to provide values in addition to the core id, code, and progress.
     * Those updates will provide an appropriate StatusWriter to fill in those extras.
     */
    private static interface StatusWriter {
        public void addToStatus(final Bundle statusExtras);
    }

    /**
     * Generic function to check if the callback is necessary and, if so, perform it.
     * The function is the common parts for the following functions, which are for use by the
     * {@link android.content.AbstractThreadedSyncAdapter} to communicate the status of a sync
     * action to the caller.
     * @param cr A ContentResolver.
     * @param syncExtras The extras provided to the sync request.
     * @param statusType The type of sync status update to send.
     * @param id The id of the thing whose status is being updated (type depends on statusType).
     * @param statusCode The status code for this sync operation.
     * @param progress The progress of this sync operation.
     * @param writer If not null, an object which will write additional status fields.
     */
    private static void syncStatus(final ContentResolver cr, final Bundle syncExtras,
            final int statusType, final long id, final int statusCode, final int progress,
            int syncResult,
            final StatusWriter writer) {
        final String callbackUri = syncExtras.getString(SYNC_EXTRAS_CALLBACK_URI);
        final String callbackMethod = syncExtras.getString(SYNC_EXTRAS_CALLBACK_METHOD);
        if (callbackUri != null && callbackMethod != null) {
            final String callbackArg = syncExtras.getString(SYNC_EXTRAS_CALLBACK_ARG, "");
            final Bundle statusExtras = new Bundle(4);
            statusExtras.putInt(SYNC_STATUS_TYPE, statusType);
            statusExtras.putLong(SYNC_STATUS_ID, id);
            statusExtras.putInt(SYNC_STATUS_CODE, statusCode);
            if (statusCode != IN_PROGRESS) {
                statusExtras.putInt(SYNC_RESULT, syncResult);
            }
            statusExtras.putInt(SYNC_STATUS_PROGRESS, progress);
            if (writer != null) {
                writer.addToStatus(statusExtras);
            }
            cr.call(Uri.parse(callbackUri), callbackMethod, callbackArg, statusExtras);
        }
    }

    /**
     * If the sync extras specify a callback, then notify the sync requester of the mailbox's
     * sync status. This function is for use by the
     * {@link android.content.AbstractThreadedSyncAdapter}.
     * @param cr A ContentResolver.
     * @param syncExtras The extras provided to the sync request.
     * @param mailboxId The mailbox whose status is changing.
     * @param statusCode The status code for this sync operation.
     * @param progress The progress of this sync operation.
     */
    public static void syncMailboxStatus(final ContentResolver cr, final Bundle syncExtras,
            final long mailboxId, final int statusCode, final int progress, int syncResult) {
        syncStatus(cr, syncExtras, SYNC_STATUS_TYPE_MAILBOX, mailboxId, statusCode, progress,
                syncResult, null);
    }


}
