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

import com.android.emailcommon.Api;
import com.android.emailcommon.Device;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;

/**
 * The EmailServiceProxy class provides a simple interface for the UI to call into the various
 * EmailService classes (e.g. ExchangeService for EAS).  It wraps the service connect/disconnect
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

    // Private intent that will be used to connect to an independent Exchange service
    public static final String EXCHANGE_INTENT = "com.android.email.EXCHANGE_INTENT";

    public static final String AUTO_DISCOVER_BUNDLE_ERROR_CODE = "autodiscover_error_code";
    public static final String AUTO_DISCOVER_BUNDLE_HOST_AUTH = "autodiscover_host_auth";

    public static final String VALIDATE_BUNDLE_RESULT_CODE = "validate_result_code";
    public static final String VALIDATE_BUNDLE_POLICY_SET = "validate_policy_set";
    public static final String VALIDATE_BUNDLE_ERROR_MESSAGE = "validate_error_message";
    public static final String VALIDATE_BUNDLE_UNSUPPORTED_POLICIES =
        "validate_unsupported_policies";

    private final IEmailServiceCallback mCallback;
    private Object mReturn = null;
    private IEmailService mService;

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
        this(_context, _class, null);
    }

    public EmailServiceProxy(Context _context, Class<?> _class, IEmailServiceCallback _callback) {
        super(_context, new Intent(_context, _class));
        mCallback = _callback;
    }

    // The following two constructors are used with remote services that must be referenced by
    // a known action or by a prebuilt intent
    public EmailServiceProxy(Context _context, Intent _intent, IEmailServiceCallback _callback) {
        super(_context, _intent);
        try {
            Device.getDeviceId(_context);
        } catch (IOException e) {
        }
        mCallback = _callback;
    }

    public EmailServiceProxy(Context _context, String _action, IEmailServiceCallback _callback) {
        super(_context, new Intent(_action));
        try {
            Device.getDeviceId(_context);
        } catch (IOException e) {
        }
        mCallback = _callback;
    }

    @Override
    public void onConnected(IBinder binder) {
        mService = IEmailService.Stub.asInterface(binder);
    }

    @Override
    public int getApiLevel() {
        return Api.LEVEL;
    }

    /**
     * Request an attachment to be loaded; the service MUST give higher priority to
     * non-background loading.  The service MUST use the loadAttachmentStatus callback when
     * loading has started and stopped and SHOULD send callbacks with progress information if
     * possible.
     *
     * @param attachmentId the id of the attachment record
     * @param background whether or not this request corresponds to a background action (i.e.
     * prefetch) vs a foreground action (user request)
     */
    public void loadAttachment(final long attachmentId, final boolean background)
            throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                try {
                    if (mCallback != null) mService.setCallback(mCallback);
                    mService.loadAttachment(attachmentId, background);
                } catch (RemoteException e) {
                    try {
                        // Try to send a callback (if set)
                        if (mCallback != null) {
                            mCallback.loadAttachmentStatus(-1, attachmentId,
                                    EmailServiceStatus.REMOTE_EXCEPTION, 0);
                        }
                    } catch (RemoteException e1) {
                    }
                }
            }
        }, "loadAttachment");
    }

    /**
     * Request the sync of a mailbox; the service MUST send the syncMailboxStatus callback
     * indicating "starting" and "finished" (or error), regardless of whether the mailbox is
     * actually syncable.
     *
     * @param mailboxId the id of the mailbox record
     * @param userRequest whether or not the user specifically asked for the sync
     */
    public void startSync(final long mailboxId, final boolean userRequest) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.startSync(mailboxId, userRequest);
            }
        }, "startSync");
    }

    /**
     * Request the immediate termination of a mailbox sync. Although the service is not required to
     * acknowledge this request, it MUST send a "finished" (or error) syncMailboxStatus callback if
     * the sync was started via the startSync service call.
     *
     * @param mailboxId the id of the mailbox record
     * @param userRequest whether or not the user specifically asked for the sync
     */
    public void stopSync(final long mailboxId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.stopSync(mailboxId);
            }
        }, "stopSync");
    }

    /**
     * Validate a user account, given a protocol, host address, port, ssl status, and credentials.
     * The result of this call is returned in a Bundle which MUST include a result code and MAY
     * include a PolicySet that is required by the account. A successful validation implies a host
     * address that serves the specified protocol and credentials sufficient to be authorized
     * by the server to do so.
     *
     * @param hostAuth the hostauth object to validate
     * @return a Bundle as described above
     */
    public Bundle validate(final HostAuth hostAuth) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException{
                if (mCallback != null) mService.setCallback(mCallback);
                mReturn = mService.validate(hostAuth);
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
            Log.v(TAG, "validate returns " + bundle.getInt(VALIDATE_BUNDLE_RESULT_CODE));
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
    public Bundle autoDiscover(final String userName, final String password)
            throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException{
                if (mCallback != null) mService.setCallback(mCallback);
                mReturn = mService.autoDiscover(userName, password);
            }
        }, "autoDiscover");
        waitForCompletion();
        if (mReturn == null) {
            return null;
        } else {
            Bundle bundle = (Bundle) mReturn;
            bundle.setClassLoader(HostAuth.class.getClassLoader());
            Log.v(TAG, "autoDiscover returns " + bundle.getInt(AUTO_DISCOVER_BUNDLE_ERROR_CODE));
            return bundle;
        }
    }

    /**
     * Request that the service reload the folder list for the specified account. The service
     * MUST use the syncMailboxListStatus callback to indicate "starting" and "finished"
     *
     * @param accoundId the id of the account whose folder list is to be updated
     */
    public void updateFolderList(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
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
    public void setLogging(final int flags) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.setLogging(flags);
            }
        }, "setLogging");
    }

    /**
     * Set the global callback object to be used by the service; the service MUST always use the
     * most recently set callback object
     *
     * @param cb a callback object through which all service callbacks are executed
     */
    public void setCallback(final IEmailServiceCallback cb) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.setCallback(cb);
            }
        }, "setCallback");
    }

    /**
     * Alert the sync adapter that the account's host information has (or may have) changed; the
     * service MUST stop all in-process or pending syncs, clear error states related to the
     * account and its mailboxes, and restart necessary sync adapters (e.g. pushed mailboxes)
     *
     * @param accountId the id of the account whose host information has changed
     */
    public void hostChanged(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.hostChanged(accountId);
            }
        }, "hostChanged");
    }

    /**
     * Send a meeting response for the specified message
     *
     * @param messageId the id of the message containing the meeting request
     * @param response the response code, as defined in EmailServiceConstants
     */
    public void sendMeetingResponse(final long messageId, final int response)
            throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.sendMeetingResponse(messageId, response);
            }
        }, "sendMeetingResponse");
    }

    /**
     * Not yet used; intended to request the sync adapter to load a complete message
     *
     * @param messageId the id of the message to be loaded
     */
    public void loadMore(long messageId) throws RemoteException {
    }

    /**
     * Not yet used
     *
     * @param accountId the account in which the folder is to be created
     * @param name the name of the folder to be created
    */
    public boolean createFolder(long accountId, String name) throws RemoteException {
        return false;
    }

    /**
     * Not yet used
     *
     * @param accountId the account in which the folder resides
     * @param name the name of the folder to be deleted
     */
    public boolean deleteFolder(long accountId, String name) throws RemoteException {
        return false;
    }

    /**
     * Not yet used
     *
     * @param accountId the account in which the folder resides
     * @param oldName the name of the existing folder
     * @param newName the new name for the folder
     */
    public boolean renameFolder(long accountId, String oldName, String newName)
            throws RemoteException {
        return false;
    }

    /**
     * Request the service to delete the account's PIM (personal information management) data. This
     * data includes any data that is 1) associated with the account and 2) created/stored by the
     * service or its sync adapters and 3) not stored in the EmailProvider database (e.g. contact
     * and calendar information).
     *
     * @param accountId the account whose data is to be deleted
     */
    public void deleteAccountPIMData(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.deleteAccountPIMData(accountId);
            }
        }, "deleteAccountPIMData");
    }


    /**
     * PRELIMINARY
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
    public int searchMessages(final long accountId, final SearchParams searchParams,
            final long destMailboxId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException{
                if (mCallback != null) mService.setCallback(mCallback);
                mReturn = mService.searchMessages(accountId, searchParams, destMailboxId);
            }
        }, "searchMessages");
        waitForCompletion();
        if (mReturn == null) {
            return 0;
        } else {
            return (Integer)mReturn;
        }
    }
    public IBinder asBinder() {
        return null;
    }
}
