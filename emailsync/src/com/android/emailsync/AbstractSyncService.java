/*
 * Copyright (C) 2008-2009 Marc Blank
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

package com.android.emailsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.text.format.DateUtils;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.utils.LogUtils;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * Base class for all protocol services SyncManager (extends Service, implements
 * Runnable) instantiates subclasses to run a sync (either timed, or push, or
 * mail placed in outbox, etc.) EasSyncService is currently implemented; my goal
 * would be to move IMAP to this structure when it comes time to introduce push
 * functionality.
 */
public abstract class AbstractSyncService implements Runnable {

    public String TAG = "AbstractSyncService";

    public static final int EXIT_DONE = 0;
    public static final int EXIT_IO_ERROR = 1;
    public static final int EXIT_LOGIN_FAILURE = 2;
    public static final int EXIT_EXCEPTION = 3;
    public static final int EXIT_SECURITY_FAILURE = 4;
    public static final int EXIT_ACCESS_DENIED = 5;

    public Mailbox mMailbox;
    protected long mMailboxId;
    protected int mExitStatus = EXIT_EXCEPTION;
    protected String mExitReason;
    protected String mMailboxName;
    public Account mAccount;
    public Context mContext;
    public int mChangeCount = 0;
    public volatile int mSyncReason = 0;
    protected volatile boolean mStop = false;
    public volatile Thread mThread;
    protected final Object mSynchronizer = new Object();
    // Whether or not the sync service is valid (usable)
    public boolean mIsValid = true;

    public boolean mUserLog = true; // STOPSHIP
    public boolean mFileLog = false;

    protected volatile long mRequestTime = 0;
    protected LinkedBlockingQueue<Request> mRequestQueue = new LinkedBlockingQueue<Request>();

    /**
     * Sent by SyncManager to request that the service stop itself cleanly
     */
    public abstract void stop();

    /**
     * Sent by SyncManager to indicate that an alarm has fired for this service, and that its
     * pending (network) operation has timed out. The service is NOT automatically stopped,
     * although the behavior is service dependent.
     *
     * @return true if the operation was stopped normally; false if the thread needed to be
     * interrupted.
     */
    public abstract boolean alarm();

    /**
     * Sent by SyncManager to request that the service reset itself cleanly; the meaning of this
     * operation is service dependent.
     */
    public abstract void reset();

    /**
     * Called to validate an account; abstract to allow each protocol to do what
     * is necessary. For consistency with the Email app's original
     * functionality, success is indicated by a failure to throw an Exception
     * (ugh). Parameters are self-explanatory
     *
     * @param hostAuth
     * @return a Bundle containing a result code and, depending on the result, a PolicySet or an
     * error message
     */
    public abstract Bundle validateAccount(HostAuth hostAuth, Context context);

    /**
     * Called to clear the syncKey for the calendar associated with this service; this is necessary
     * because changes to calendar sync state cause a reset of data.
     */
    public abstract void resetCalendarSyncKey();

    public AbstractSyncService(Context _context, Mailbox _mailbox) {
        mContext = _context;
        mMailbox = _mailbox;
        mMailboxId = _mailbox.mId;
        mMailboxName = _mailbox.mServerId;
        mAccount = Account.restoreAccountWithId(_context, _mailbox.mAccountKey);
    }

    // Will be required when subclasses are instantiated by name
    public AbstractSyncService(String prefix) {
    }

    /**
     * The UI can call this static method to perform account validation.  This method wraps each
     * protocol's validateAccount method.   Arguments are self-explanatory, except where noted.
     *
     * @param klass the protocol class (EasSyncService.class for example)
     * @param hostAuth
     * @param context
     * @return a Bundle containing a result code and, depending on the result, a PolicySet or an
     * error message
     */
    public static Bundle validate(Class<? extends AbstractSyncService> klass,
            HostAuth hostAuth, Context context) {
        AbstractSyncService svc;
        try {
            svc = klass.newInstance();
            return svc.validateAccount(hostAuth, context);
        } catch (IllegalAccessException e) {
        } catch (InstantiationException e) {
        }
        return null;
    }

    public static class ValidationResult {
        static final int NO_FAILURE = 0;
        static final int CONNECTION_FAILURE = 1;
        static final int VALIDATION_FAILURE = 2;
        static final int EXCEPTION = 3;

        static final ValidationResult succeeded = new ValidationResult(true, NO_FAILURE, null);
        boolean success;
        int failure = NO_FAILURE;
        String reason = null;
        Exception exception = null;

        ValidationResult(boolean _success, int _failure, String _reason) {
            success = _success;
            failure = _failure;
            reason = _reason;
        }

        ValidationResult(boolean _success) {
            success = _success;
        }

        ValidationResult(Exception e) {
            success = false;
            failure = EXCEPTION;
            exception = e;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getReason() {
            return reason;
        }
    }

    public boolean isStopped() {
        return mStop;
    }

    public Object getSynchronizer() {
        return mSynchronizer;
    }

    /**
     * Convenience methods to do user logging (i.e. connection activity).  Saves a bunch of
     * repetitive code.
     */
    public void userLog(String string, int code, String string2) {
        if (mUserLog) {
            userLog(string + code + string2);
        }
    }

    public void userLog(String string, int code) {
        if (mUserLog) {
            userLog(string + code);
        }
    }

    public void userLog(String str, Exception e) {
        if (mUserLog) {
            LogUtils.e(TAG, str, e);
        } else {
            LogUtils.e(TAG, str + e);
        }
        if (mFileLog) {
            FileLogger.log(e);
        }
    }

    /**
     * Standard logging for EAS.
     * If user logging is active, we concatenate any arguments and log them using LogUtils.d
     * We also check for file logging, and log appropriately
     * @param strings strings to concatenate and log
     */
    public void userLog(String ...strings) {
        if (mUserLog) {
            String logText;
            if (strings.length == 1) {
                logText = strings[0];
            } else {
                StringBuilder sb = new StringBuilder(64);
                for (String string: strings) {
                    sb.append(string);
                }
                logText = sb.toString();
            }
            LogUtils.d(TAG, logText);
            if (mFileLog) {
                FileLogger.log(TAG, logText);
            }
        }
    }

    /**
     * Error log is used for serious issues that should always be logged
     * @param str the string to log
     */
    public void errorLog(String str) {
        LogUtils.e(TAG, str);
        if (mFileLog) {
            FileLogger.log(TAG, str);
        }
    }

    /**
     * Waits for up to 10 seconds for network connectivity; returns whether or not there is
     * network connectivity.
     *
     * @return whether there is network connectivity
     */
    public boolean hasConnectivity() {
        ConnectivityManager cm =
                (ConnectivityManager)mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        int tries = 0;
        while (tries++ < 1) {
            // Use the same test as in ExchangeService#waitForConnectivity
            // TODO: Create common code for this test in emailcommon
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null) {
                return true;
            }
            try {
                Thread.sleep(10 * DateUtils.SECOND_IN_MILLIS);
            } catch (InterruptedException e) {
            }
        }
        return false;
    }

    /**
     * Request handling (common functionality)
     * Can be overridden if desired
     */

    public void addRequest(Request req) {
        if (!mRequestQueue.contains(req)) {
            mRequestQueue.offer(req);
        }
    }

    public void removeRequest(Request req) {
        mRequestQueue.remove(req);
    }

    public boolean hasPendingRequests() {
        return !mRequestQueue.isEmpty();
    }

    public void clearRequests() {
        mRequestQueue.clear();
    }
}
