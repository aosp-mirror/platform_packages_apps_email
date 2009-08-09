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

package com.android.exchange;

import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.utility.FileLogger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.util.Log;

import java.util.ArrayList;

/**
 * Base class for all protocol services SyncManager (extends Service, implements
 * Runnable) instantiates subclasses to run a sync (either timed, or push, or
 * mail placed in outbox, etc.) EasSyncService is currently implemented; my goal
 * would be to move IMAP to this structure when it comes time to introduce push
 * functionality.
 */
public abstract class AbstractSyncService implements Runnable {

    public String TAG = "ProtocolService";

    public static final String SUMMARY_PROTOCOL = "_SUMMARY_";
    public static final String SYNCED_PROTOCOL = "_SYNCING_";
    public static final String MOVE_FAVORITES_PROTOCOL = "_MOVE_FAVORITES_";
    public static final int CONNECT_TIMEOUT = 30000;
    public static final int NETWORK_WAIT = 15000;
    public static final int SECS = 1000;
    public static final int MINS = 60 * SECS;
    public static final int HRS = 60 * MINS;
    public static final int DAYS = 24 * HRS;
    public static final String IMAP_PROTOCOL = "imap";
    public static final String EAS_PROTOCOL = "eas";
    public static final int EXIT_DONE = 0;
    public static final int EXIT_IO_ERROR = 1;
    public static final int EXIT_LOGIN_FAILURE = 2;
    public static final int EXIT_EXCEPTION = 3;

    // Making SSL connections is so slow that I'd prefer that only one be
    // executed at a time
    // Kindly subclasses will synchronize on this before making an SSL
    // connection
    public static Object sslGovernorToken = new Object();
    public Mailbox mMailbox;
    protected long mMailboxId;
    protected Thread mThread;
    protected int mExitStatus = EXIT_EXCEPTION;
    protected String mMailboxName;
    public Account mAccount;
    protected Context mContext;
    public int mChangeCount = 0;
    public int mSyncReason = 0;
    protected volatile boolean mStop = false;
    private Object mSynchronizer = new Object();

    protected volatile long mRequestTime = 0;
    protected ArrayList<PartRequest> mPartRequests = new ArrayList<PartRequest>();
    protected PartRequest mPendingPartRequest = null;

    /**
     * Sent by SyncManager to request that the service stop itself cleanly
     */
    public abstract void stop();

    /**
     * Sent by SyncManager to indicate a user request requiring service has been
     * added to the service's pending request queue
     */
    public abstract void ping();

    /**
     * Called to validate an account; abstract to allow each protocol to do what
     * is necessary. For consistency with the Email app's original
     * functionality, success is indicated by a failure to throw an Exception
     * (ugh). Parameters are self-explanatory
     *
     * @param host
     * @param userName
     * @param password
     * @param port
     * @param ssl
     * @param context
     * @throws MessagingException
     */
    public abstract void validateAccount(String host, String userName, String password, int port,
            boolean ssl, Context context) throws MessagingException;

    /**
     * Sent by SyncManager to determine the state of a running sync This is
     * currently unused
     *
     * @return status code
     */
    public int getSyncStatus() {
        return 0;
    }

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
     * @param host
     * @param userName
     * @param password
     * @param port
     * @param ssl
     * @param context
     * @throws MessagingException
     */
    static public void validate(Class<? extends AbstractSyncService> klass, String host,
            String userName, String password, int port, boolean ssl, Context context)
            throws MessagingException {
        AbstractSyncService svc;
        try {
            svc = klass.newInstance();
            svc.validateAccount(host, userName, password, port, ssl, context);
        } catch (IllegalAccessException e) {
            throw new MessagingException("internal error", e);
        } catch (InstantiationException e) {
            throw new MessagingException("internal error", e);
        }
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
     * Convenience method to do user logging (i.e. connection activity).  Saves a bunch of
     * repetitive code.
     *
     * @param str the String to log
     */
    public void userLog(String str) {
        if (Eas.USER_LOG) {
            Log.i(TAG, str);
            if (Eas.FILE_LOG) {
                FileLogger.log(TAG, str);
            }
        }
    }

    /**
     * Error log is used for serious issues that should always be logged
     * @param str the string to log
     */
    public void errorLog(String str) {
        Log.e(TAG, str);
        if (Eas.FILE_LOG) {
            FileLogger.log(TAG, str);
        }
    }

    /**
     * Implements a delay until there is some kind of network connectivity available. This method
     * may be supplanted by functionality in SyncManager.
     *
     * @return the type of network connected to
     */
    public int waitForConnectivity() {
        ConnectivityManager cm = (ConnectivityManager)mContext
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        while (true) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                DetailedState state = info.getDetailedState();
                if (state == DetailedState.CONNECTED) {
                    return info.getType();
                } else {
                    // TODO Happens sometimes; find out why...
                    userLog("Not quite connected?  Pause 1 second");
                }
                pause(1000);
            } else {
                userLog("Not connected; waiting 15 seconds");
                pause(NETWORK_WAIT);
            }
        }
    }

    /**
     * Convenience method to generate a small wait
     *
     * @param ms time to wait in milliseconds
     */
    private void pause(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    // What's below here is temporary

    /**
     * PartRequest handling (common functionality)
     * Can be overridden if desired, but IMAP/EAS both use the next three methods as-is
     */

    public void addPartRequest(PartRequest req) {
        synchronized (mPartRequests) {
            mPartRequests.add(req);
            mRequestTime = System.currentTimeMillis();
        }
    }

    public void removePartRequest(PartRequest req) {
        synchronized (mPartRequests) {
            mPartRequests.remove(req);
        }
    }

    public PartRequest hasPartRequest(long emailId, String part) {
        synchronized (mPartRequests) {
            for (PartRequest pr : mPartRequests) {
                if (pr.emailId == emailId && pr.loc.equals(part))
                    return pr;
            }
        }
        return null;
    }

    // CancelPartRequest is sent in response to user input to stop a request
    // (attachment load at this point)
    // that is in progress. This will almost certainly require code overriding
    // the base functionality, as
    // sockets may need to be closed, etc. and this functionality will be
    // service dependent. This returns
    // the canceled PartRequest or null
    public PartRequest cancelPartRequest(long emailId, String part) {
        synchronized (mPartRequests) {
            PartRequest p = null;
            for (PartRequest pr : mPartRequests) {
                if (pr.emailId == emailId && pr.loc.equals(part)) {
                    p = pr;
                    break;
                }
            }
            if (p != null) {
                mPartRequests.remove(p);
                return p;
            }
        }
        return null;
    }
}
