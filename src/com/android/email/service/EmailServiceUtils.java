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
import android.accounts.AccountManagerCallback;
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
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.SyncState;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.provider.SyncStateContract;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.android.email.R;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.EmailServiceVersion;
import com.android.emailcommon.service.HostAuthCompat;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.service.ServiceProxy;
import com.android.emailcommon.service.SyncWindow;
import com.android.mail.utils.LogUtils;
import com.google.common.collect.ImmutableMap;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    /**
     * Ask a service to kill its process. This is used when an account is deleted so that
     * no background thread that happens to be running will continue, possibly hitting an
     * NPE or other error when trying to operate on an account that no longer exists.
     * TODO: This is kind of a hack, it's only needed because we fail so badly if an account
     * is deleted out from under us while a sync or other operation is in progress. It would
     * be a lot cleaner if our background services could handle this without crashing.
     */
    public static void killService(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info != null && info.intentAction != null) {
            final Intent serviceIntent = getServiceIntent(info);
            serviceIntent.putExtra(ServiceProxy.EXTRA_FORCE_SHUTDOWN, true);
            context.startService(serviceIntent);
        }
    }

    /**
     * Starts an EmailService by protocol
     */
    public static void startService(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info != null && info.intentAction != null) {
            final Intent serviceIntent = getServiceIntent(info);
            context.startService(serviceIntent);
        }
    }

    /**
     * Starts all remote services
     */
    public static void startRemoteServices(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                final Intent serviceIntent = getServiceIntent(info);
                context.startService(serviceIntent);
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
                        EmailServiceUtils.getService(context, info.protocol);
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
        final Intent serviceIntent = getServiceIntent(info);
        return new EmailServiceProxy(context, serviceIntent).test();
    }

    private static Intent getServiceIntent(EmailServiceInfo info) {
        final Intent serviceIntent = new Intent(info.intentAction);
        serviceIntent.setPackage(info.intentPackage);
        return serviceIntent;
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @return service proxy, or null if n/a
     */
    public static EmailServiceProxy getServiceForAccount(Context context, long accountId) {
        return getService(context, Account.getProtocol(context, accountId));
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
        String intentPackage;
        public int port;
        public int portSsl;
        public boolean defaultSsl;
        public boolean offerTls;
        public boolean offerCerts;
        public boolean offerOAuth;
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
        public boolean offerLoadMore;
        public boolean offerMoveTo;
        public boolean requiresSetup;
        public boolean hide;
        public boolean isGmailStub;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("Protocol: ");
            sb.append(protocol);
            sb.append(", ");
            sb.append(klass != null ? "Local" : "Remote");
            sb.append(" , Account Type: ");
            sb.append(accountType);
            return sb.toString();
        }
    }

    public static EmailServiceProxy getService(Context context, String protocol) {
        EmailServiceInfo info = null;
        // Handle the degenerate case here (account might have been deleted)
        if (protocol != null) {
            info = getServiceInfo(context, protocol);
        }
        if (info == null) {
            LogUtils.w(LogUtils.TAG, "Returning NullService for %s", protocol);
            return new EmailServiceProxy(context, NullService.class);
        } else  {
            return getServiceFromInfo(context, info);
        }
    }

    public static EmailServiceProxy getServiceFromInfo(Context context, EmailServiceInfo info) {
        if (info.klass != null) {
            return new EmailServiceProxy(context, info.klass);
        } else {
            final Intent serviceIntent = getServiceIntent(info);
            return new EmailServiceProxy(context, serviceIntent);
        }
    }

    public static EmailServiceInfo getServiceInfoForAccount(Context context, long accountId) {
        String protocol = Account.getProtocol(context, accountId);
        return getServiceInfo(context, protocol);
    }

    public static EmailServiceInfo getServiceInfo(Context context, String protocol) {
        return getServiceMap(context).get(protocol);
    }

    public static Collection<EmailServiceInfo> getServiceInfoList(Context context) {
        return getServiceMap(context).values();
    }

    private static void finishAccountManagerBlocker(AccountManagerFuture<?> future) {
        try {
            // Note: All of the potential errors are simply logged
            // here, as there is nothing to actually do about them.
            future.getResult();
        } catch (OperationCanceledException e) {
            LogUtils.w(LogUtils.TAG, e, "finishAccountManagerBlocker");
        } catch (AuthenticatorException e) {
            LogUtils.w(LogUtils.TAG, e, "finishAccountManagerBlocker");
        } catch (IOException e) {
            LogUtils.w(LogUtils.TAG, e, "finishAccountManagerBlocker");
        }
    }

    /**
     * Add an account to the AccountManager.
     * @param context Our {@link Context}.
     * @param account The {@link Account} we're adding.
     * @param email Whether the user wants to sync email on this account.
     * @param calendar Whether the user wants to sync calendar on this account.
     * @param contacts Whether the user wants to sync contacts on this account.
     * @param callback A callback for when the AccountManager is done.
     * @return The result of {@link AccountManager#addAccount}.
     */
    public static AccountManagerFuture<Bundle> setupAccountManagerAccount(final Context context,
            final Account account, final boolean email, final boolean calendar,
            final boolean contacts, final AccountManagerCallback<Bundle> callback) {
        final HostAuth hostAuthRecv =
                HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
        return setupAccountManagerAccount(context, account, email, calendar, contacts,
                hostAuthRecv, callback);
    }

    /**
     * Add an account to the AccountManager.
     * @param context Our {@link Context}.
     * @param account The {@link Account} we're adding.
     * @param email Whether the user wants to sync email on this account.
     * @param calendar Whether the user wants to sync calendar on this account.
     * @param contacts Whether the user wants to sync contacts on this account.
     * @param hostAuth HostAuth that identifies the protocol and password for this account.
     * @param callback A callback for when the AccountManager is done.
     * @return The result of {@link AccountManager#addAccount}.
     */
    public static AccountManagerFuture<Bundle> setupAccountManagerAccount(final Context context,
            final Account account, final boolean email, final boolean calendar,
            final boolean contacts, final HostAuth hostAuth,
            final AccountManagerCallback<Bundle> callback) {
        if (hostAuth == null) {
            return null;
        }
        // Set up username/password
        final Bundle options = new Bundle(5);
        options.putString(EasAuthenticatorService.OPTIONS_USERNAME, account.mEmailAddress);
        options.putString(EasAuthenticatorService.OPTIONS_PASSWORD, hostAuth.mPassword);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CONTACTS_SYNC_ENABLED, contacts);
        options.putBoolean(EasAuthenticatorService.OPTIONS_CALENDAR_SYNC_ENABLED, calendar);
        options.putBoolean(EasAuthenticatorService.OPTIONS_EMAIL_SYNC_ENABLED, email);
        final EmailServiceInfo info = getServiceInfo(context, hostAuth.mProtocol);
        return AccountManager.get(context).addAccount(info.accountType, null, null, options, null,
                callback, null);
    }

    public static void updateAccountManagerType(Context context,
            android.accounts.Account amAccount, final Map<String, String> protocolMap) {
        final ContentResolver resolver = context.getContentResolver();
        final Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                AccountColumns.EMAIL_ADDRESS + "=?", new String[] { amAccount.name }, null);
        // That's odd, isn't it?
        if (c == null) return;
        try {
            if (c.moveToNext()) {
                // Get the EmailProvider Account/HostAuth
                final Account account = new Account();
                account.restore(c);
                final HostAuth hostAuth =
                        HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv);
                if (hostAuth == null) {
                    return;
                }

                final String newProtocol = protocolMap.get(hostAuth.mProtocol);
                if (newProtocol == null) {
                    // This account doesn't need updating.
                    return;
                }

                LogUtils.w(LogUtils.TAG, "Converting %s to %s", amAccount.name, newProtocol);

                final ContentValues accountValues = new ContentValues();
                int oldFlags = account.mFlags;

                // Mark the provider account incomplete so it can't get reconciled away
                account.mFlags |= Account.FLAGS_INCOMPLETE;
                accountValues.put(AccountColumns.FLAGS, account.mFlags);
                final Uri accountUri = ContentUris.withAppendedId(Account.CONTENT_URI, account.mId);
                resolver.update(accountUri, accountValues, null, null);

                // Change the HostAuth to reference the new protocol; this has to be done before
                // trying to create the AccountManager account (below)
                final ContentValues hostValues = new ContentValues();
                hostValues.put(HostAuthColumns.PROTOCOL, newProtocol);
                resolver.update(ContentUris.withAppendedId(HostAuth.CONTENT_URI, hostAuth.mId),
                        hostValues, null, null);
                LogUtils.w(LogUtils.TAG, "Updated HostAuths");

                try {
                    // Get current settings for the existing AccountManager account
                    boolean email = ContentResolver.getSyncAutomatically(amAccount,
                            EmailContent.AUTHORITY);
                    if (!email) {
                        // Try our old provider name
                        email = ContentResolver.getSyncAutomatically(amAccount,
                                "com.android.email.provider");
                    }
                    final boolean contacts = ContentResolver.getSyncAutomatically(amAccount,
                            ContactsContract.AUTHORITY);
                    final boolean calendar = ContentResolver.getSyncAutomatically(amAccount,
                            CalendarContract.AUTHORITY);
                    LogUtils.w(LogUtils.TAG, "Email: %s, Contacts: %s Calendar: %s",
                            email, contacts, calendar);

                    // Get sync keys for calendar/contacts
                    final String amName = amAccount.name;
                    final String oldType = amAccount.type;
                    ContentProviderClient client = context.getContentResolver()
                            .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                    byte[] calendarSyncKey = null;
                    try {
                        calendarSyncKey = SyncStateContract.Helpers.get(client,
                                asCalendarSyncAdapter(SyncState.CONTENT_URI, amName, oldType),
                                new android.accounts.Account(amName, oldType));
                    } catch (RemoteException e) {
                        LogUtils.w(LogUtils.TAG, "Get calendar key FAILED");
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
                        LogUtils.w(LogUtils.TAG, "Get contacts key FAILED");
                    } finally {
                        client.release();
                    }
                    if (calendarSyncKey != null) {
                        LogUtils.w(LogUtils.TAG, "Got calendar key: %s",
                                new String(calendarSyncKey));
                    }
                    if (contactsSyncKey != null) {
                        LogUtils.w(LogUtils.TAG, "Got contacts key: %s",
                                new String(contactsSyncKey));
                    }

                    // Set up a new AccountManager account with new type and old settings
                    AccountManagerFuture<?> amFuture = setupAccountManagerAccount(context, account,
                            email, calendar, contacts, null);
                    finishAccountManagerBlocker(amFuture);
                    LogUtils.w(LogUtils.TAG, "Created new AccountManager account");

                    // TODO: Clean up how we determine the type.
                    final String accountType = protocolMap.get(hostAuth.mProtocol + "_type");
                    // Move calendar and contacts data from the old account to the new one.
                    // We must do this before deleting the old account or the data is lost.
                    moveCalendarData(context.getContentResolver(), amName, oldType, accountType);
                    moveContactsData(context.getContentResolver(), amName, oldType, accountType);

                    // Delete the AccountManager account
                    amFuture = AccountManager.get(context)
                            .removeAccount(amAccount, null, null);
                    finishAccountManagerBlocker(amFuture);
                    LogUtils.w(LogUtils.TAG, "Deleted old AccountManager account");

                    // Restore sync keys for contacts/calendar

                    if (accountType != null &&
                            calendarSyncKey != null && calendarSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(CalendarContract.CONTENT_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    asCalendarSyncAdapter(SyncState.CONTENT_URI, amName,
                                            accountType),
                                    new android.accounts.Account(amName, accountType),
                                    calendarSyncKey);
                            LogUtils.w(LogUtils.TAG, "Set calendar key...");
                        } catch (RemoteException e) {
                            LogUtils.w(LogUtils.TAG, "Set calendar key FAILED");
                        } finally {
                            client.release();
                        }
                    }
                    if (accountType != null &&
                            contactsSyncKey != null && contactsSyncKey.length != 0) {
                        client = context.getContentResolver()
                                .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                        try {
                            SyncStateContract.Helpers.set(client,
                                    ContactsContract.SyncState.CONTENT_URI,
                                    new android.accounts.Account(amName, accountType),
                                    contactsSyncKey);
                            LogUtils.w(LogUtils.TAG, "Set contacts key...");
                        } catch (RemoteException e) {
                            LogUtils.w(LogUtils.TAG, "Set contacts key FAILED");
                        }
                    }

                    // That's all folks!
                    LogUtils.w(LogUtils.TAG, "Account update completed.");
                } finally {
                    // Clear the incomplete flag on the provider account
                    accountValues.put(AccountColumns.FLAGS, oldFlags);
                    resolver.update(accountUri, accountValues, null, null);
                    LogUtils.w(LogUtils.TAG, "[Incomplete flag cleared]");
                }
            }
        } finally {
            c.close();
        }
    }

    private static void moveCalendarData(final ContentResolver resolver, final String name,
            final String oldType, final String newType) {
        final Uri oldCalendars = Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, name)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, oldType)
                .build();

        // Update this calendar to have the new account type.
        final ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, newType);
        resolver.update(oldCalendars, values,
                Calendars.ACCOUNT_NAME + "=? AND " + Calendars.ACCOUNT_TYPE + "=?",
                new String[] {name, oldType});
    }

    private static void moveContactsData(final ContentResolver resolver, final String name,
            final String oldType, final String newType) {
        final Uri oldContacts = RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, oldType)
                .build();

        // Update this calendar to have the new account type.
        final ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, newType);
        resolver.update(oldContacts, values, null, null);
    }

    private static final Configuration sOldConfiguration = new Configuration();
    private static Map<String, EmailServiceInfo> sServiceMap = null;
    private static final Object sServiceMapLock = new Object();

    /**
     * Parse services.xml file to find our available email services
     */
    private static Map<String, EmailServiceInfo> getServiceMap(final Context context) {
        synchronized (sServiceMapLock) {
            /**
             * We cache localized strings here, so make sure to regenerate the service map if
             * the locale changes
             */
            if (sServiceMap == null) {
                sOldConfiguration.setTo(context.getResources().getConfiguration());
            }

            final int delta =
                    sOldConfiguration.updateFrom(context.getResources().getConfiguration());

            if (sServiceMap != null
                    && !Configuration.needNewResources(delta, ActivityInfo.CONFIG_LOCALE)) {
                return sServiceMap;
            }

            final ImmutableMap.Builder<String, EmailServiceInfo> builder = ImmutableMap.builder();

            try {
                final Resources res = context.getResources();
                final XmlResourceParser xml = res.getXml(R.xml.services);
                int xmlEventType;
                // walk through senders.xml file.
                while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (xmlEventType == XmlResourceParser.START_TAG &&
                            "emailservice".equals(xml.getName())) {
                        final EmailServiceInfo info = new EmailServiceInfo();
                        final TypedArray ta =
                                res.obtainAttributes(xml, R.styleable.EmailServiceInfo);
                        info.protocol = ta.getString(R.styleable.EmailServiceInfo_protocol);
                        info.accountType = ta.getString(R.styleable.EmailServiceInfo_accountType);
                        info.name = ta.getString(R.styleable.EmailServiceInfo_name);
                        info.hide = ta.getBoolean(R.styleable.EmailServiceInfo_hide, false);
                        final String klass =
                                ta.getString(R.styleable.EmailServiceInfo_serviceClass);
                        info.intentAction = ta.getString(R.styleable.EmailServiceInfo_intent);
                        info.intentPackage =
                                ta.getString(R.styleable.EmailServiceInfo_intentPackage);
                        info.defaultSsl =
                                ta.getBoolean(R.styleable.EmailServiceInfo_defaultSsl, false);
                        info.port = ta.getInteger(R.styleable.EmailServiceInfo_port, 0);
                        info.portSsl = ta.getInteger(R.styleable.EmailServiceInfo_portSsl, 0);
                        info.offerTls = ta.getBoolean(R.styleable.EmailServiceInfo_offerTls, false);
                        info.offerCerts =
                                ta.getBoolean(R.styleable.EmailServiceInfo_offerCerts, false);
                        info.offerOAuth =
                                ta.getBoolean(R.styleable.EmailServiceInfo_offerOAuth, false);
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
                            ta.getBoolean(R.styleable.EmailServiceInfo_offerAttachmentPreload,
                                    false);
                        info.syncIntervalStrings =
                            ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervalStrings);
                        info.syncIntervals =
                            ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervals);
                        info.defaultSyncInterval =
                            ta.getInteger(R.styleable.EmailServiceInfo_defaultSyncInterval, 15);
                        info.inferPrefix = ta.getString(R.styleable.EmailServiceInfo_inferPrefix);
                        info.offerLoadMore =
                                ta.getBoolean(R.styleable.EmailServiceInfo_offerLoadMore, false);
                        info.offerMoveTo =
                                ta.getBoolean(R.styleable.EmailServiceInfo_offerMoveTo, false);
                        info.requiresSetup =
                                ta.getBoolean(R.styleable.EmailServiceInfo_requiresSetup, false);
                        info.isGmailStub =
                                ta.getBoolean(R.styleable.EmailServiceInfo_isGmailStub, false);

                        // Must have either "class" (local) or "intent" (remote)
                        if (klass != null) {
                            try {
                                // noinspection unchecked
                                info.klass = (Class<? extends Service>) Class.forName(klass);
                            } catch (ClassNotFoundException e) {
                                throw new IllegalStateException(
                                        "Class not found in service descriptor: " + klass);
                            }
                        }
                        if (info.klass == null &&
                                info.intentAction == null &&
                                !info.isGmailStub) {
                            throw new IllegalStateException(
                                    "No class or intent action specified in service descriptor");
                        }
                        if (info.klass != null && info.intentAction != null) {
                            throw new IllegalStateException(
                                    "Both class and intent action specified in service descriptor");
                        }
                        builder.put(info.protocol, info);
                    }
                }
            } catch (XmlPullParserException e) {
                // ignore
            } catch (IOException e) {
                // ignore
            }
            sServiceMap = builder.build();
            return sServiceMap;
        }
    }

    /**
     * Resolves a service name into a protocol name, or null if ambiguous
     * @param context for loading service map
     * @param accountType sync adapter service name
     * @return protocol name or null
     */
    public static @Nullable String getProtocolFromAccountType(final Context context,
            final String accountType) {
        if (TextUtils.isEmpty(accountType)) {
            return null;
        }
        final Map <String, EmailServiceInfo> serviceInfoMap = getServiceMap(context);
        String protocol = null;
        for (final EmailServiceInfo info : serviceInfoMap.values()) {
            if (TextUtils.equals(accountType, info.accountType)) {
                if (!TextUtils.isEmpty(protocol) && !TextUtils.equals(protocol, info.protocol)) {
                    // More than one protocol matches
                    return null;
                }
                protocol = info.protocol;
            }
        }
        return protocol;
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
        public Bundle validate(HostAuthCompat hostauth) throws RemoteException {
            return null;
        }

        @Override
        public void loadAttachment(final IEmailServiceCallback cb, final long accountId,
                final long attachmentId, final boolean background) throws RemoteException {
        }

        @Override
        public void updateFolderList(long accountId) throws RemoteException {}

        @Override
        public void setLogging(int flags) throws RemoteException {
        }

        @Override
        public Bundle autoDiscover(String userName, String password) throws RemoteException {
            return null;
        }

        @Override
        public void sendMeetingResponse(long messageId, int response) throws RemoteException {
        }

        @Override
        public void deleteExternalAccountPIMData(final String emailAddress) throws RemoteException {
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
        public void pushModify(long accountId) throws RemoteException {
        }

        @Override
        public int sync(final long accountId, final Bundle syncExtras) {
            return EmailServiceStatus.SUCCESS;
        }

        public int getApiVersion() {
            return EmailServiceVersion.CURRENT;
        }
    }

    public static void setComponentStatus(final Context context, Class<?> clazz, boolean enabled) {
        final ComponentName c = new ComponentName(context, clazz.getName());
        context.getPackageManager().setComponentEnabledSetting(c,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    /**
     * This is a helper function that enables the proper Exchange component and disables
     * the other Exchange component ensuring that only one is enabled at a time.
     */
    public static void enableExchangeComponent(final Context context) {
        if (VendorPolicyLoader.getInstance(context).useAlternateExchangeStrings()) {
            LogUtils.d(LogUtils.TAG, "Enabling alternate EAS authenticator");
            setComponentStatus(context, EasAuthenticatorServiceAlternate.class, true);
            setComponentStatus(context, EasAuthenticatorService.class, false);
        } else {
            LogUtils.d(LogUtils.TAG, "Enabling EAS authenticator");
            setComponentStatus(context, EasAuthenticatorService.class, true);
            setComponentStatus(context,
                    EasAuthenticatorServiceAlternate.class, false);
        }
    }

    public static void disableExchangeComponents(final Context context) {
        LogUtils.d(LogUtils.TAG, "Disabling EAS authenticators");
        setComponentStatus(context, EasAuthenticatorServiceAlternate.class, false);
        setComponentStatus(context, EasAuthenticatorService.class, false);
    }

}
