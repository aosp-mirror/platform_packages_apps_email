/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.emailcommon.Device;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;
import com.android.mail.utils.LogUtils;

import java.io.IOException;

/**
 * The EmailServiceProxy class provides a simple interface for the UI to call into the various
 * EmailService classes (e.g. EasService for EAS).  It wraps the service connect/disconnect
 * process so that the caller need not be concerned with it.
 *
 * Use the class like this:
 *   new EmailServiceProxy(context, class).loadAttachment(attachmentId, callback)
 *
 * Methods without a return value return immediately (i.e. are asynchronous); methods with a
 * return value wait for a result from the Service (i.e. they should not be called from the UI
 * thread) with a default timeout of 30 seconds (settable)
 *
 * An EmailServiceProxy object cannot be reused (trying to do so generates a RemoteException)
 */

public class EmailServiceProxy extends ServiceProxy implements IEmailService {
    private static final String TAG = "EmailServiceProxy";

    public static final String AUTO_DISCOVER_BUNDLE_ERROR_CODE = "autodiscover_error_code";
    public static final String AUTO_DISCOVER_BUNDLE_HOST_AUTH = "autodiscover_host_auth";

    public static final String VALIDATE_BUNDLE_RESULT_CODE = "validate_result_code";
    public static final String VALIDATE_BUNDLE_POLICY_SET = "validate_policy_set";
    public static final String VALIDATE_BUNDLE_ERROR_MESSAGE = "validate_error_message";
    public static final String VALIDATE_BUNDLE_UNSUPPORTED_POLICIES =
        "validate_unsupported_policies";
    public static final String VALIDATE_BUNDLE_PROTOCOL_VERSION = "validate_protocol_version";
    public static final String VALIDATE_BUNDLE_REDIRECT_ADDRESS = "validate_redirect_address";

    private Object mReturn = null;
    private IEmailService mService;
    private final boolean isRemote;

    // Standard debugging
    public static final int DEBUG_BIT = 1;
    // Verbose (parser) logging
    public static final int DEBUG_VERBOSE_BIT = 2;
    // File (SD card) logging
    public static final int DEBUG_FILE_BIT = 4;
    // Enable strict mode
    public static final int DEBUG_ENABLE_STRICT_MODE = 8;

    // The first two constructors are used with local services that can be referenced by class
    public EmailServiceProxy(Context _context, Class<?> _class) {
        super(_context, new Intent(_context, _class));
        TempDirectory.setTempDirectory(_context);
        isRemote = false;
    }

    // The following two constructors are used with remote services that must be referenced by
    // a known action or by a prebuilt intent
    public EmailServiceProxy(Context _context, Intent _intent) {
        super(_context, _intent);
        try {
            Device.getDeviceId(_context);
        } catch (IOException e) {
        }
        TempDirectory.setTempDirectory(_context);
        isRemote = true;
    }

    @Override
    public void onConnected(IBinder binder) {
        mService = IEmailService.Stub.asInterface(binder);
    }

    public boolean isRemote() {
        return isRemote;
    }

    /**
     * Request an attachment to be loaded; the service MUST give higher priority to
     * non-background loading.  The service MUST use the loadAttachmentStatus callback when
     * loading has started and stopped and SHOULD send callbacks with progress information if
     * possible.
     *
     * @param cb The {@link IEmailServiceCallback} to use for this operation.
     * @param accountId the id of the account in question
     * @param attachmentId the id of the attachment record
     * @param background whether or not this request corresponds to a background action (i.e.
     * prefetch) vs a foreground action (user request)
     */
    @Override
    public void loadAttachment(final IEmailServiceCallback cb, final long accountId,
            final long attachmentId, final boolean background)
            throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                try {
                    mService.loadAttachment(cb, accountId, attachmentId, background);
                } catch (RemoteException e) {
                    try {
                        // Try to send a callback (if set)
                        if (cb != null) {
                            cb.loadAttachmentStatus(-1, attachmentId,
                                    EmailServiceStatus.REMOTE_EXCEPTION, 0);
                        }
                    } catch (RemoteException e1) {
                    }
                }
            }
        }, "loadAttachment");
    }

    /**
     * Validate a user account, given a protocol, host address, port, ssl status, and credentials.
     * The result of this call is returned in a Bundle which MUST include a result code and MAY
     * include a PolicySet that is required by the account. A successful validation implies a host
     * address that serves the specified protocol and credentials sufficient to be authorized
     * by the server to do so.
     *
     * @param hostAuthCom the hostAuthCom object to validate
     * @return a Bundle as described above
     */
    @Override
    public Bundle validate(final HostAuthCompat hostAuthCom) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mReturn = mService.validate(hostAuthCom);
            }
        }, "validate");
        waitForCompletion();
        if (mReturn == null) {
            Bundle bundle = new Bundle();
            bundle.putInt(VALIDATE_BUNDLE_RESULT_CODE, MessagingException.UNSPECIFIED_EXCEPTION);
            return bundle;
        } else {
            Bundle bundle = (Bundle) mReturn;
            bundle.setClassLoader(Policy.class.getClassLoader());
            LogUtils.v(TAG, "validate returns " + bundle.getInt(VALIDATE_BUNDLE_RESULT_CODE));
            return bundle;
        }
    }

    /**
     * Attempt to determine a user's host address and credentials from an email address and
     * password. The result is returned in a Bundle which MUST include an error code and MAY (on
     * success) include a HostAuth record sufficient to enable the service to validate the user's
     * account.
     *
     * @param userName the user's email address
     * @param password the user's password
     * @return a Bundle as described above
     */
    @Override
    public Bundle autoDiscover(final String userName, final String password)
            throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mReturn = mService.autoDiscover(userName, password);
            }
        }, "autoDiscover");
        waitForCompletion();
        if (mReturn == null) {
            return null;
        } else {
            Bundle bundle = (Bundle) mReturn;
            bundle.setClassLoader(HostAuth.class.getClassLoader());
            LogUtils.v(TAG, "autoDiscover returns "
                    + bundle.getInt(AUTO_DISCOVER_BUNDLE_ERROR_CODE));
            return bundle;
        }
    }

    /**
     * Request that the service reload the folder list for the specified account. The service
     * MUST use the syncMailboxListStatus callback to indicate "starting" and "finished"
     *
     * @param accountId the id of the account whose folder list is to be updated
     */
    @Override
    public void updateFolderList(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.updateFolderList(accountId);
            }
        }, "updateFolderList");
    }

    /**
     * Specify the debug flags selected by the user.  The service SHOULD log debug information as
     * requested.
     *
     * @param flags an integer whose bits represent logging flags as defined in DEBUG_* flags above
     */
    @Override
    public void setLogging(final int flags) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.setLogging(flags);
            }
        }, "setLogging");
    }

    /**
     * Send a meeting response for the specified message
     *
     * @param messageId the id of the message containing the meeting request
     * @param response the response code, as defined in EmailServiceConstants
     */
    @Override
    public void sendMeetingResponse(final long messageId, final int response)
            throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.sendMeetingResponse(messageId, response);
            }
        }, "sendMeetingResponse");
    }

    /**
     * Request the service to delete the account's PIM (personal information management) data. This
     * data includes any data that is 1) associated with the account and 2) created/stored by the
     * service or its sync adapters and 3) not stored in the EmailProvider database (e.g. contact
     * and calendar information).
     *
     * @param emailAddress the email address for the account whose data should be deleted
     */
    @Override
    public void deleteExternalAccountPIMData(final String emailAddress) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException {
                mService.deleteExternalAccountPIMData(emailAddress);
            }
        }, "deleteAccountPIMData");
        // This can be called when deleting accounts. After making this call, the caller will
        // ask for account reconciliation, which will kill the processes. We wait for completion
        // to avoid the race.
        waitForCompletion();
    }

    /**
     * Search for messages given a query string.  The string is interpreted as the logical AND of
     * terms separated by white space.  The search is performed on the specified mailbox in the
     * specified account (including subfolders, as specified by the includeSubfolders parameter).
     * At most numResults messages matching the query term(s) will be added to the mailbox specified
     * as destMailboxId. If mailboxId is -1, the entire account will be searched. If firstResult is
     * specified and non-zero, results will be added starting with the firstResult'th match (i.e.
     * for the continuation of a previous search)
     *
     * @param accountId the id of the account to be searched
     * @param searchParams the search specification
     * @param destMailboxId the id of the mailbox into which search results are appended
     * @return the total number of matches for this search (regardless of how many were requested)
     */
    @Override
    public int searchMessages(final long accountId, final SearchParams searchParams,
            final long destMailboxId) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mReturn = mService.searchMessages(accountId, searchParams, destMailboxId);
            }
        }, "searchMessages");
        waitForCompletion();
        if (mReturn == null) {
            return 0;
        } else {
            return (Integer) mReturn;
        }
    }

    /**
     * Request the service to send mail in the specified account's Outbox
     *
     * @param accountId the account whose outgoing mail should be sent.
     */
    @Override
    public void sendMail(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mService.sendMail(accountId);
            }
        }, "sendMail");
    }

    /**
     * Request the service to refresh its push notification status (e.g. to start or stop receiving
     * them, or to change which folders we want notifications for).
     * @param accountId The account whose push settings to modify.
     */
    @Override
    public void pushModify(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mService.pushModify(accountId);
            }
        }, "pushModify");
    }

    @Override
    public int sync(final long accountId, final Bundle syncExtras) {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mReturn = mService.sync(accountId, syncExtras);
            }
        }, "sync");
        waitForCompletion();
        if (mReturn == null) {
            // This occurs if sync times out.
            // TODO: Sync may take a long time, maybe we should extend the timeout here.
            return EmailServiceStatus.IO_ERROR;
        } else {
            return (Integer)mReturn;
        }
    }

    @Override
    public IBinder asBinder() {
        return null;
    }

    public int getApiVersion() {
        setTask(new ProxyTask() {
            @Override
            public void run() throws RemoteException{
                mReturn = mService.getApiVersion();
            }
        }, "getApiVersion");
        waitForCompletion();
        if (mReturn == null) {
            // This occurs if there is a timeout or remote exception. Is not expected to happen.
            LogUtils.wtf(TAG, "failed to get api version");
            return -1;
        } else {
            return (Integer) mReturn;
        }
    }
}
