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

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.emailcommon.Api;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * The EmailServiceProxy class provides a simple interface for the UI to call into the various
 * EmailService classes (e.g. ExchangeService for EAS).  It wraps the service connect/disconnect
 * process so that the caller need not be concerned with it.
 *
 * Use the class like this:
 *   new EmailServiceClass(context, class).loadAttachment(attachmentId, callback)
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

    private final IEmailServiceCallback mCallback;
    private Object mReturn = null;
    private IEmailService mService;

    // Standard debugging
    public static final int DEBUG_BIT = 1;
    // Verbose (parser) logging
    public static final int DEBUG_VERBOSE_BIT = 2;
    // File (SD card) logging
    public static final int DEBUG_FILE_BIT = 4;

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
        mCallback = _callback;
    }

    public EmailServiceProxy(Context _context, String _action, IEmailServiceCallback _callback) {
        super(_context, new Intent(_action));
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

    public void loadAttachment(final long attachmentId, final String destinationFile,
            final String contentUriString, final boolean background) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                try {
                    if (mCallback != null) mService.setCallback(mCallback);
                    mService.loadAttachment(
                            attachmentId, destinationFile, contentUriString, background);
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

    public void startSync(final long mailboxId, final boolean userRequest) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.startSync(mailboxId, userRequest);
            }
        }, "startSync");
    }

    public void stopSync(final long mailboxId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.stopSync(mailboxId);
            }
        }, "stopSync");
    }

    public Bundle validate(final String protocol, final String host, final String userName,
            final String password, final int port, final boolean ssl,
            final boolean trustCertificates) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException{
                if (mCallback != null) mService.setCallback(mCallback);
                mReturn = mService.validate(protocol, host, userName, password, port, ssl,
                        trustCertificates);
            }
        }, "validate");
        waitForCompletion();
        if (mReturn == null) {
            Bundle bundle = new Bundle();
            bundle.putInt(VALIDATE_BUNDLE_RESULT_CODE, MessagingException.UNSPECIFIED_EXCEPTION);
            return bundle;
        } else {
            Bundle bundle = (Bundle) mReturn;
            // STOPSHIP The following line will be necessary when Email and Exchange are split
            //bundle.setClassLoader(PolicySet.class.getClassLoader());
            Log.v(TAG, "validate returns " + bundle.getInt(VALIDATE_BUNDLE_RESULT_CODE));
            return bundle;
        }
    }

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

    public void updateFolderList(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.updateFolderList(accountId);
            }
        }, "updateFolderList");
    }

    public void setLogging(final int on) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.setLogging(on);
            }
        }, "setLogging");
    }

    public void setCallback(final IEmailServiceCallback cb) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.setCallback(cb);
            }
        }, "setCallback");
    }

    public void hostChanged(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.hostChanged(accountId);
            }
        }, "hostChanged");
    }

    public void sendMeetingResponse(final long messageId, final int response)
            throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                if (mCallback != null) mService.setCallback(mCallback);
                mService.sendMeetingResponse(messageId, response);
            }
        }, "sendMeetingResponse");
    }

    public void loadMore(long messageId) throws RemoteException {
        // TODO Auto-generated method stub
    }

    public boolean createFolder(long accountId, String name) throws RemoteException {
        return false;
    }

    public boolean deleteFolder(long accountId, String name) throws RemoteException {
        return false;
    }

    public boolean renameFolder(long accountId, String oldName, String newName)
            throws RemoteException {
        return false;
    }

    public void moveMessage(final long messageId, final long mailboxId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.moveMessage(messageId, mailboxId);
            }
        }, "moveMessage");
    }

    public void deleteAccountPIMData(final long accountId) throws RemoteException {
        setTask(new ProxyTask() {
            public void run() throws RemoteException {
                mService.deleteAccountPIMData(accountId);
            }
        }, "deleteAccountPIMData");
    }

    public IBinder asBinder() {
        return null;
    }
}
