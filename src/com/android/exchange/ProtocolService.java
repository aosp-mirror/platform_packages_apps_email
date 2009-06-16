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

import java.util.ArrayList;

import com.android.email.Email;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.DetailedState;
import android.util.Log;

// Base class for all protocol services
// Some common functionality is included here; note that each protocol will implement run() individually
// MailService (extends Service, implements Runnable) instantiates subclasses when it's time to run a sync 
// (either timed, or push, or mail placed in outbox, etc.)
// Current subclasses are IMAPService, EASService, and SMTPService, with POP3Service to come...
public abstract class ProtocolService implements Runnable {

    public static final String TAG = "ProtocolService";

    public static final String SUMMARY_PROTOCOL = "_SUMMARY_";
    public static final String SYNCED_PROTOCOL = "_SYNCING_";
    public static final String MOVE_FAVORITES_PROTOCOL = "_MOVE_FAVORITES_";

    public static final int CONNECT_TIMEOUT = 30000;
    public static final int NETWORK_WAIT = 15000;

    public static final int SECS = 1000;
    public static final int MINS = 60*SECS;
    public static final int HRS = 60*MINS;
    public static final int DAYS = 24*HRS;

    public static final String IMAP_PROTOCOL = "imap";
    public static final String EAS_PROTOCOL = "eas";

    // Making SSL connections is so slow that I'd prefer that only one be executed at a time
    // Kindly subclasses will synchronize on this before making an SSL connection
    public static Object sslGovernorToken = new Object();

    protected EmailContent.Mailbox mMailbox;
    protected long mMailboxId;
    protected Thread mThread;
    protected String mMailboxName;
    protected EmailContent.Account mAccount;
    protected Context mContext;
    protected long mRequestTime;
    protected ArrayList<PartRequest> mPartRequests = new ArrayList<PartRequest>();
    protected PartRequest mPendingPartRequest = null;

    // Stop is sent by the MailService to request that the service stop itself cleanly.  An example
    // would be for the implementation of sleep hours
    public abstract void stop ();
    // Ping is sent by the MailService to indicate that a user request requiring service has been added to
    // request queue; response is service dependent
    public abstract void ping ();
    // MailService calls this to determine the sync state of the protocol service.  By default,
    // this is "SYNC", but it might, for example, be "IDLE" (i.e. push), in which case the method will be
    // overridden.  Could be abstract, but ... nah.
    public int getSyncStatus() {
        return 0;
        //return MailService.SyncStatus.SYNC;
    }

    public ProtocolService (Context _context, EmailContent.Mailbox _mailbox) {
        mContext = _context;
        mMailbox = _mailbox;
        mMailboxId = _mailbox.mId;
        mMailboxName = _mailbox.mServerId;
        mAccount = EmailContent.Account.restoreAccountWithId(_context, _mailbox.mAccountKey);
    }

    // Will be required when subclasses are instantiated by name
    public ProtocolService (String prefix) {
    }

    public abstract void validateAccount (String host, String userName, String password, 
            int port, boolean ssl, Context context) throws MessagingException;

    static public void validate (Class<? extends ProtocolService> klass, String host, 
            String userName, String password, int port, boolean ssl, Context context) 
    throws MessagingException {
        ProtocolService svc;
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

        ValidationResult (boolean _success, int _failure, String _reason) {
            success = _success;
            failure = _failure;
            reason = _reason;
        }

        ValidationResult (boolean _success) {
            success = _success;
        }

        ValidationResult (Exception e) {
            success = false;
            failure = EXCEPTION;
            exception = e;
        }

        public boolean isSuccess () {
            return success;
        }

        public String getReason () {
            return reason;
        }
    }

    public final void runAwake () {
        //MailService.runAwake(mMailboxId);
    }

    public final void runAsleep (long millis) {
        //MailService.runAsleep(mMailboxId, millis);
    }

    // Common call used by the various protocols to send a "mail" message to the UI
    protected void updateUI () {
    }

    protected void log (String str) {
        if (Email.DEBUG) {
            Log.v(Email.LOG_TAG, str);
        }
    }

    // Delay until there is some kind of network connectivity
    // Subclasses should allow some number of retries before failing, and kicking the ball back to MailService
    public int waitForConnectivity () {
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
                    log("Not quite connected?  Pause 1 second");
                }
                pause(1000);
            } else {
                log("Not connected; waiting 15 seconds");
                pause(NETWORK_WAIT);
            }
        }
    }

    // Convenience
    private void pause (int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
        }
    }

    // PartRequest handling (common functionality)
    // Can be overridden if desired, but IMAP/EAS both use the next three methods as-is
    public void addPartRequest (PartRequest req) {
        synchronized(mPartRequests) {
            mPartRequests.add(req);
        }
    }

    public void removePartRequest (PartRequest req) {
        synchronized(mPartRequests) {
            mPartRequests.remove(req);
        }
    }

    public PartRequest hasPartRequest(long emailId, String part) {
        synchronized(mPartRequests) {
            for (PartRequest pr: mPartRequests) {
                if (pr.emailId == emailId && pr.loc.equals(part))
                    return pr;
            }
        }
        return null;
    }

    // CancelPartRequest is sent in response to user input to stop a request (attachment load at this point)
    // that is in progress.  This will almost certainly require code overriding the base functionality, as
    // sockets may need to be closed, etc. and this functionality will be service dependent.  This returns
    // the canceled PartRequest or null
    public PartRequest cancelPartRequest(long emailId, String part) {
        synchronized(mPartRequests) {
            PartRequest p = null;
            for (PartRequest pr: mPartRequests) {
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
