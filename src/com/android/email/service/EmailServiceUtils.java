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

package com.android.email.service;

import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.SyncState;
import android.provider.ContactsContract;
import android.provider.SyncStateContract;
import android.util.Log;

import com.android.email.R;
import com.android.emailcommon.Api;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.SyncWindow;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    private static final ArrayList<EmailServiceInfo> sServiceList =
            new ArrayList<EmailServiceInfo>();

    /**
     * Starts an EmailService by protocol
     */
    public static void startService(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info != null && info.intentAction != null) {
            context.startService(new Intent(info.intentAction));
        }
    }

    /**
     * Starts all remote services
     */
    public static void startRemoteServices(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                context.startService(new Intent(info.intentAction));
            }
        }
    }

    /**
     * Returns whether or not remote services are present on device
     */
    public static boolean areRemoteServicesInstalled(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts all remote services
     */
    public static void setRemoteServicesLogging(Context context, int debugBits) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                EmailServiceProxy service =
                        EmailServiceUtils.getService(context, null, info.protocol);
                if (service != null) {
                    try {
                        service.setLogging(debugBits);
                    } catch (RemoteException e) {
                        // Move along, nothing to see
                    }
                }
            }
        }
    }

    /**
     * Determine if the EmailService is available
     */
    public static boolean isServiceAvailable(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info == null) return false;
        if (info.klass != null) return true;
        return new EmailServiceProxy(context, info.intentAction, null).test();
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    public static EmailServiceProxy getServiceForAccount(Context context,
            IEmailServiceCallback callback, long accountId) {
        return getService(context, callback, Account.getProtocol(context, accountId));
    }

    /**
     * Holder of service information (currently just name and class/intent); if there is a class
     * member, this is a (local, i.e. same process) service; otherwise, this is a remote service
     */
    public static class EmailServiceInfo {
        public String protocol;
        public String name;
        public String accountType;
        Class<? extends Service> klass;
        String intentAction;
        public int port;
        public int portSsl;
        public boolean defaultSsl;
        public boolean offerTls;
        public boolean offerCerts;
        public boolean usesSmtp;
        public boolean offerLocalDeletes;
        public int defaultLocalDeletes;
        public boolean offerPrefix;
        public boolean usesAutodiscover;
        public boolean offerLookback;
        public int defaultLookback;
        public boolean syncChanges;
        public boolean syncContacts;
        public boolean syncCalendar;
        public boolean offerAttachmentPreload;
        public CharSequence[] syncIntervalStrings;
        public CharSequence[] syncIntervals;
        public int defaultSyncInterval;
        public String inferPrefix;
        public boolean requiresAccountUpdate;

        public String toString() {
            StringBuilder sb = new StringBuilder("Protocol: ");
            sb.append(protocol);
            sb.append(", ");
            sb.append(klass != null ? "Local" : "Remote");
            return sb.toString();
        }
    }

    public static EmailServiceProxy getService(Context context, IEmailServiceCallback callback,
            String protocol) {
        EmailServiceInfo info = null;
        // Handle the degenerate case here (account might have been deleted)
        if (protocol != null) {
            info = getServiceInfo(context, protocol);
        }
        if (info == null) {
            Log.w(Logging.LOG_TAG, "Returning NullService for " + protocol);
            return new EmailServiceProxy(context, NullService.class, null);
        } else  {
            return getServiceFromInfo(context, callback, info);
        }
    }

    public static EmailServiceProxy getServiceFromInfo(Context context,
            IEmailServiceCallback callback, EmailServiceInfo info) {
        if (info.klass != null) {
            return new EmailServiceProxy(context, info.klass, callback);
        } else {
            return new EmailServiceProxy(context, info.intentAction, callback);
        }
    }

    public static EmailServiceInfo getServiceInfo(Context context, String protocol) {
        if (sServiceList.isEmpty()) {
            findServices(context);
        }
        for (EmailServiceInfo info: sServiceList) {
            if (info.protocol.equals(protocol)) {
                return info;
            }
        }
        return null;
    }

    public static List<EmailServiceInfo> getServiceInfoList(Context context) {
        synchronized(sServiceList) {
            if (sServiceList.isEmpty()) {
                findServices(context);
            }
            return sServiceList;
        }
    }

    private static void finishAccountManagerBlocker(AccountManagerFuture<?> future) {
        try {
            // Note: All of the potential errors are simply logged
            // here, as there is nothing to actually do about them.
            future.getResult();
        } catch (OperationCanceledException e) {
            Log.w(Logging.LOG_TAG, e.toString());
        } catch (AuthenticatorException e) {
            Log.w(Logging.LOG_TAG, e.toString());
        } catch (IOException e) {
            Log.w(Logging.LOG_TAG, e.toString());
        }
    }

    private static class UpdateAccountManagerTask extends AsyncTask<Void, Void, Void> {
        private final Context mContext;
        private final android.accounts.Account mAccount;
        private final EmailServiceInfo mOldInfo;
        private final EmailServiceInfo mNewInfo;

        public UpdateAccountManagerTask(Context context, android.accounts.Account amAccount,
                EmailServiceInfo oldInfo, EmailServiceInfo newInfo) {
            super();
            mContext = context;
            mAccount = amAccount;
            mOldInfo = oldInfo;
            mNewInfo = newInfo;
        }

        @Override
        protected Void doInBackground(Void... params) {
            updateAccountManagerType(mContext, mAccount, mOldInfo, mNewInfo);
            return null;
        }
    }

    private static class DisableComponentsTask extends AsyncTask<Void, Void, Void> {
        private final Context mContext;

        public DisableComponentsTask(Context context) {
            super();
            mContext = context;
        }

        @Override
        protected Void doInBackground(Void... params) {
            disableComponent(mContext, LegacyEmailAuthenticatorService.class);
            disableComponent(mContext, LegacyEasAuthenticatorService.class);
            return null;
        }
    }

    private static void updateAccountManagerType(Context context,
            android.accounts.Account amAccount, EmailServiceInfo oldInfo,
            EmailServiceInfo newInfo) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                AccountColumns.EMAIL_ADDRESS + "=?", new String[] { amAccount.name }, null);
        // That's odd, isn't it?
        if (c == null) return;
        try {
            if (c.moveToNext()) {
                // Get the EmailProvider Account/HostAuth
                Account account = new Account();
                account.restore(c);
                HostAuth hostAuth =
                        HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
                if (hostAuth == null) return;

                // Make sure this email address is using the expected protocol; our query to
                // AccountManager doesn't know which protocol was being used (com.android.email
                // was used for both pop3 and imap
                if (!hostAuth.mProtocol.equals(oldInfo.protocol)) {
                    return;
                }
                Log.w(Logging.LOG_TAG, "Converting " + amAccount.name + " to " + newInfo.protocol);

                ContentValues accountValues = new ContentValues();
                int oldFlags = account.mFlags;

                // Mark the provider account incomplete so it can't get reconciled away
                account.mFlags |= Account.FLAGS_INCOMPLETE;
                accountValues.put(AccountColumns.FLAGS, account.mFlags);
                Uri accountUri = ContentUris.withAppendedId(Account.CONTENT_URI, account.mId);
                resolver.update(accountUri, accountValues, null, null);

                // Change the HostAuth to reference the new protocol; this has to be done before
                // trying to create the AccountManager account (below)
                ContentValues hostValues = new ContentValues();
                hostValues.put(HostAuth.PROTOCOL, newInfo.protocol);
                resolver.update(ContentUris.withAppendedId(HostAuth.CONTENT_URI, hostAuth.mId),
                        hostValues, null, null);
                Log.w(Logging.LOG_TAG, "Updated HostAuths");

                try {
                    // Get current settings for the existing AccountManager account
                    boolean email = ContentResolver.getSyncAutomatically(amAccount,
                            EmailContent.AUTHORITY);
                    if (!email) {
                        // Try our old provider name
                        email = ContentResolver.getSyncAutomatically(amAccount,
                                "com.android.email.provider");
                    }
                    boolean contacts = ContentResolver.getSyncAutomatically(amAccount,
                            ContactsContract.AUTHORITY);
                    boolean calendar = ContentResolver.getSyncAutomatically(amAccount,
                            CalendarContract.AUTHORITY);
                    Log.w(Logging.LOG_TAG, "Email: " + email + ", Contacts: " + contacts + "," +
                            " Calendar: " + calendar);

                    // Get sync keys for calendar/contacts
                    String amName = amAccount.name;
                    String oldType = amAccount.type;
                    ContentProviderClient client = context.getContentResolver()
                            .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                    byte[] calendarSyncKey = null;
                    try {
                        calendarSyncKey = SyncStateContract.Helpers.get(client,
                                asCalendarSyncAdapter(SyncState.CONTENT_URI, amName, oldType),
                                new android.accounts.Account(amName, oldType));
                    } catch (RemoteException e) {
                        Log.w(Logging.LOG_TAG, "Get calendar key FAILED");
                    } finally {
                        client.release();
                    }
                    client = context.getContentResolver()
                            .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                    byte[] contactsSyncKey = null;
                    try {
                        contactsSyncKey = SyncStateContract.Helpers.get(client,
                                ContactsContract.SyncState.CONTENT_URI,
                                new android.accounts.Account(amName, oldType));
                    } catch (RemoteException e) {
                        Log.w(Logging.LOG_TAG, "Get contacts key FAILED");
                    } finally {
                        client.release();
                    }
                    if (calendarSyncKey != null) {
                        Log.w(Logging.LOG_TAG, "Got calendar key: " + new String(calendarSyncKey));
                    }
                    if (contactsSyncKey != null) {
                        Log.w(Logging.LOG_TAG, "Got contacts key: " + new String(contactsSyncKey));
                    }

                    // Set up a new AccountManager account with new type and old settings
                    AccountManagerFuture<?> amFuture = MailService.setupAccountManagerAccount(
                            context, account, email, calendar, contacts, null);
                    finishAccountManagerBlocker(amFuture);
                    Log.w(Logging.LOG_TAG, "Created new AccountManager account");

                    // Delete the AccountManager account
                    amFuture = AccountManager.get(context)
                            .removeAccount(amAccount, null, null);
                    finishAccountManagerBlocker(amFuture);
                    Log.w(Logging.LOG_TAG, "Deleted old AccountManager account");

                    // Restore sync keys for contacts/calendar
                    if (calendarSyncKey != null && calendarSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    asCalendarSyncAdapter(SyncState.CONTENT_URI, amName,
                                            newInfo.accountType),
                                    new android.accounts.Account(amName, newInfo.accountType),
                                    calendarSyncKey);
                            Log.w(Logging.LOG_TAG, "Set calendar key...");
                        } catch (RemoteException e) {
                            Log.w(Logging.LOG_TAG, "Set calendar key FAILED");
                        } finally {
                            client.release();
                        }
                    }
                    if (contactsSyncKey != null && contactsSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    ContactsContract.SyncState.CONTENT_URI,
                                    new android.accounts.Account(amName, newInfo.accountType),
                                    contactsSyncKey);
                            Log.w(Logging.LOG_TAG, "Set contacts key...");
                        } catch (RemoteException e) {
                            Log.w(Logging.LOG_TAG, "Set contacts key FAILED");
                        }
                    }

                    if (oldInfo.requiresAccountUpdate) {
                        EmailServiceProxy service =
                                EmailServiceUtils.getServiceFromInfo(context, null, newInfo);
                        try {
                            service.serviceUpdated(amAccount.name);
                            Log.w(Logging.LOG_TAG, "Updated account settings");
                        } catch (RemoteException e) {
                            // Old settings won't hurt anyone
                        }
                    }

                    // That's all folks!
                    Log.w(Logging.LOG_TAG, "Account update completed.");
                } finally {
                    // Clear the incomplete flag on the provider account
                    accountValues.put(AccountColumns.FLAGS, oldFlags);
                    resolver.update(accountUri, accountValues, null, null);
                    Log.w(Logging.LOG_TAG, "[Incomplete flag cleared]");
                }
            }
        } finally {
            c.close();
        }
    }

    private static void disableComponent(Context context, Class<?> klass) {
        Log.w(Logging.LOG_TAG, "Disabling legacy authenticator " + klass.getSimpleName());
        final ComponentName c = new ComponentName(context, klass);
        context.getPackageManager().setComponentEnabledSetting(c,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /**
     * Parse services.xml file to find our available email services
     */
    @SuppressWarnings("unchecked")
    private static synchronized void findServices(Context context) {
        try {
            Resources res = context.getResources();
            XmlResourceParser xml = res.getXml(R.xml.services);
            int xmlEventType;
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                        "emailservice".equals(xml.getName())) {
                    EmailServiceInfo info = new EmailServiceInfo();
                    TypedArray ta = res.obtainAttributes(xml, R.styleable.EmailServiceInfo);
                    info.protocol = ta.getString(R.styleable.EmailServiceInfo_protocol);
                    info.accountType = ta.getString(R.styleable.EmailServiceInfo_accountType);
                    // Handle upgrade of one protocol to another (e.g. imap to imap2)
                    String newProtocol = ta.getString(R.styleable.EmailServiceInfo_replaceWith);
                    if (newProtocol != null) {
                        EmailServiceInfo newInfo = getServiceInfo(context, newProtocol);
                        if (newInfo == null) {
                            throw new IllegalStateException(
                                    "Replacement service not found: " + newProtocol);
                        }
                        info.requiresAccountUpdate = ta.getBoolean(
                                R.styleable.EmailServiceInfo_requiresAccountUpdate, false);
                        AccountManager am = AccountManager.get(context);
                        android.accounts.Account[] amAccounts =
                                am.getAccountsByType(info.accountType);
                        for (android.accounts.Account amAccount: amAccounts) {
                            new UpdateAccountManagerTask(context, amAccount, info, newInfo)
                                .executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
                        }
                        continue;
                    }
                    info.name = ta.getString(R.styleable.EmailServiceInfo_name);
                    String klass = ta.getString(R.styleable.EmailServiceInfo_serviceClass);
                    info.intentAction = ta.getString(R.styleable.EmailServiceInfo_intent);
                    info.defaultSsl = ta.getBoolean(R.styleable.EmailServiceInfo_defaultSsl, false);
                    info.port = ta.getInteger(R.styleable.EmailServiceInfo_port, 0);
                    info.portSsl = ta.getInteger(R.styleable.EmailServiceInfo_portSsl, 0);
                    info.offerTls = ta.getBoolean(R.styleable.EmailServiceInfo_offerTls, false);
                    info.offerCerts = ta.getBoolean(R.styleable.EmailServiceInfo_offerCerts, false);
                    info.offerLocalDeletes =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerLocalDeletes, false);
                    info.defaultLocalDeletes =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultLocalDeletes,
                                Account.DELETE_POLICY_ON_DELETE);
                    info.offerPrefix =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerPrefix, false);
                    info.usesSmtp = ta.getBoolean(R.styleable.EmailServiceInfo_usesSmtp, false);
                    info.usesAutodiscover =
                        ta.getBoolean(R.styleable.EmailServiceInfo_usesAutodiscover, false);
                    info.offerLookback =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerLookback, false);
                    info.defaultLookback =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultLookback,
                                SyncWindow.SYNC_WINDOW_3_DAYS);
                    info.syncChanges =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncChanges, false);
                    info.syncContacts =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncContacts, false);
                    info.syncCalendar =
                        ta.getBoolean(R.styleable.EmailServiceInfo_syncCalendar, false);
                    info.offerAttachmentPreload =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerAttachmentPreload, false);
                    info.syncIntervalStrings =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervalStrings);
                    info.syncIntervals =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervals);
                    info.defaultSyncInterval =
                        ta.getInteger(R.styleable.EmailServiceInfo_defaultSyncInterval, 15);
                    info.inferPrefix = ta.getString(R.styleable.EmailServiceInfo_inferPrefix);

                    // Must have either "class" (local) or "intent" (remote)
                    if (klass != null) {
                        try {
                            info.klass = (Class<? extends Service>) Class.forName(klass);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Class not found in service descriptor: " + klass);
                        }
                    }
                    if (info.klass == null && info.intentAction == null) {
                        throw new IllegalStateException(
                                "No class or intent action specified in service descriptor");
                    }
                    if (info.klass != null && info.intentAction != null) {
                        throw new IllegalStateException(
                                "Both class and intent action specified in service descriptor");
                    }
                    sServiceList.add(info);
                }
            }
            // Disable our legacy components
            new DisableComponentsTask(context).executeOnExecutor(AsyncTask.SERIAL_EXECUTOR);
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
    }

    private static Uri asCalendarSyncAdapter(Uri uri, String account, String accountType) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType).build();
    }

    /**
     * A no-op service that can be returned for non-existent/null protocols
     */
    class NullService implements IEmailService {
        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public Bundle validate(HostAuth hostauth) throws RemoteException {
            return null;
        }

        @Override
        public void startSync(long mailboxId, boolean userRequest) throws RemoteException {
        }

        @Override
        public void stopSync(long mailboxId) throws RemoteException {
        }

        @Override
        public void loadMore(long messageId) throws RemoteException {
        }

        @Override
        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
        }

        @Override
        public void updateFolderList(long accountId) throws RemoteException {
        }

        @Override
        public boolean createFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean deleteFolder(long accountId, String name) throws RemoteException {
            return false;
        }

        @Override
        public boolean renameFolder(long accountId, String oldName, String newName)
                throws RemoteException {
            return false;
        }

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
        }

        @Override
        public void setLogging(int on) throws RemoteException {
        }

        @Override
        public void hostChanged(long accountId) throws RemoteException {
        }

        @Override
        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return null;
        }

        @Override
        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        }

        @Override
        public void deleteAccountPIMData(long accountId) throws RemoteException {
        }

        @Override
        public int getApiLevel() throws RemoteException {
            return Api.LEVEL;
        }

        @Override
        public int searchMessages(long accountId, SearchParams params, long destMailboxId)
                throws RemoteException {
            return 0;
        }

        @Override
        public void sendMail(long accountId) throws RemoteException {
        }

        @Override
        public void serviceUpdated(String emailAddress) throws RemoteException {
        }

        @Override
        public int getCapabilities(Account acct) throws RemoteException {
            return 0;
        }
    }
}
