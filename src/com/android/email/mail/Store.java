/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import com.android.email.Email;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.google.common.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;

/**
 * Store is the access point for an email message store. It's location can be
 * local or remote and no specific protocol is defined. Store is intended to
 * loosely model in combination the JavaMail classes javax.mail.Store and
 * javax.mail.Folder along with some additional functionality to improve
 * performance on mobile devices. Implementations of this class should focus on
 * making as few network connections as possible.
 */
public abstract class Store {

    /**
     * String constants for known store schemes.
     */
    public static final String STORE_SCHEME_IMAP = HostAuth.SCHEME_IMAP;
    public static final String STORE_SCHEME_POP3 = HostAuth.SCHEME_POP3;
    public static final String STORE_SCHEME_EAS = HostAuth.SCHEME_EAS;
    public static final String STORE_SCHEME_LOCAL = "local";

    public static final String STORE_SECURITY_SSL = "+ssl";
    public static final String STORE_SECURITY_TLS = "+tls";
    public static final String STORE_SECURITY_TRUST_CERTIFICATES = "+trustallcerts";

    /**
     * A global suggestion to Store implementors on how much of the body
     * should be returned on FetchProfile.Item.BODY_SANE requests.
     */
    public static final int FETCH_BODY_SANE_SUGGESTED_SIZE = (50 * 1024);

    @VisibleForTesting
    static final HashMap<HostAuth, Store> sStores = new HashMap<HostAuth, Store>();

    protected Context mContext;
    protected Account mAccount;
    protected Transport mTransport;
    protected String mUsername;
    protected String mPassword;

    /**
     * Static named constructor.  It should be overrode by extending class.
     * Because this method will be called through reflection, it can not be protected.
     */
    static Store newInstance(Account account, Context context) throws MessagingException {
        throw new MessagingException("Store#newInstance: Unknown scheme in "
                + account.mDisplayName);
    }

    private static Store instantiateStore(String className, Account account, Context context)
        throws MessagingException {
        Object o = null;
        try {
            Class<?> c = Class.forName(className);
            // and invoke "newInstance" class method and instantiate store object.
            java.lang.reflect.Method m =
                c.getMethod("newInstance", Account.class, Context.class,
                        PersistentDataCallbacks.class);
            // TODO Do the stores _really need a context? Is there a way to not pass it along?
            o = m.invoke(null, account, context);
        } catch (Exception e) {
            Log.d(Logging.LOG_TAG, String.format(
                    "exception %s invoking method %s#newInstance(Account, Context) for %s",
                    e.toString(), className, account.mDisplayName));
            throw new MessagingException("can not instantiate Store for " + account.mDisplayName);
        }
        if (!(o instanceof Store)) {
            throw new MessagingException(
                    account.mDisplayName + ": " + className + " create incompatible object");
        }
        return (Store) o;
    }

    /**
     * Look up descriptive information about a particular type of store.
     */
    public static class StoreInfo {
        public String mScheme;
        public String mClassName;
        public boolean mPushSupported = false;
        public int mVisibleLimitDefault;
        public int mVisibleLimitIncrement;
        public int mAccountInstanceLimit;

        // TODO cache result for performance - silly to keep reading the XML
        public static StoreInfo getStoreInfo(String scheme, Context context) {
            StoreInfo result = getStoreInfo(R.xml.stores_product, scheme, context);
            if (result == null) {
                result = getStoreInfo(R.xml.stores, scheme, context);
            }
            return result;
        }

        public static StoreInfo getStoreInfo(int resourceId, String scheme, Context context) {
            try {
                XmlResourceParser xml = context.getResources().getXml(resourceId);
                int xmlEventType;
                // walk through stores.xml file.
                while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                    if (xmlEventType == XmlResourceParser.START_TAG &&
                            "store".equals(xml.getName())) {
                        String xmlScheme = xml.getAttributeValue(null, "scheme");
                        if (scheme != null && scheme.startsWith(xmlScheme)) {
                            StoreInfo result = new StoreInfo();
                            result.mScheme = xmlScheme;
                            result.mClassName = xml.getAttributeValue(null, "class");
                            result.mPushSupported = xml.getAttributeBooleanValue(
                                    null, "push", false);
                            result.mVisibleLimitDefault = xml.getAttributeIntValue(
                                    null, "visibleLimitDefault", Email.VISIBLE_LIMIT_DEFAULT);
                            result.mVisibleLimitIncrement = xml.getAttributeIntValue(
                                    null, "visibleLimitIncrement", Email.VISIBLE_LIMIT_INCREMENT);
                            result.mAccountInstanceLimit = xml.getAttributeIntValue(
                                    null, "accountInstanceLimit", -1);
                            return result;
                        }
                    }
                }
            } catch (XmlPullParserException e) {
                // ignore
            } catch (IOException e) {
                // ignore
            }
            return null;
        }
    }

    /**
     * Get an instance of a mail store for the given account. The account must be valid (i.e. has
     * at least an incoming server name).
     *
     * NOTE: The internal algorithm used to find a cached store depends upon the account's
     * HostAuth row. If this ever changes (e.g. such as the user updating the
     * host name or port), we will leak entries. This should not be typical, so, it is not
     * a critical problem. However, it is something we should consider fixing.
     *
     * @param account The account of the store.
     * @return an initialized store of the appropriate class
     * @throws MessagingException If the store cannot be obtained or if the account is invalid.
     */
    public synchronized static Store getInstance(Account account, Context context)
            throws MessagingException {
        HostAuth hostAuth = account.getOrCreateHostAuthRecv(context);
        Store store = sStores.get(hostAuth);
        if (store == null) {
            Context appContext = context.getApplicationContext();
            StoreInfo info = StoreInfo.getStoreInfo(hostAuth.mProtocol, context);
            if (info != null) {
                store = instantiateStore(info.mClassName, account, appContext);
            }
            // Don't cache this unless it's we've got a saved HostAUth
            if (store != null && (hostAuth.mId != EmailContent.NOT_SAVED)) {
                sStores.put(hostAuth, store);
            }
        }

        if (store == null) {
            throw new MessagingException("Cannot find store for account " + account.mDisplayName);
        }

        return store;
    }

    /**
     * Delete the mail store associated with the given account. The account must be valid (i.e. has
     * at least an incoming server name).
     *
     * The store should have been notified already by calling delete(), and the caller should
     * also take responsibility for deleting the matching LocalStore, etc.
     *
     * @throws MessagingException If the store cannot be removed or if the account is invalid.
     */
    public synchronized static Store removeInstance(Account account, Context context)
            throws MessagingException {
        return sStores.remove(HostAuth.restoreHostAuthWithId(context, account.mHostAuthKeyRecv));
    }

    /**
     * Get class of SettingActivity for this Store class.
     * @return Activity class that has class method actionEditIncomingSettings().
     */
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        // default SettingActivity class
        return com.android.email.activity.setup.AccountSetupIncoming.class;
    }

    /**
     * Some protocols require that a sent message be copied (uploaded) into the Sent folder
     * while others can take care of it automatically (ideally, on the server).  This function
     * allows a given store to indicate which mode(s) it supports.
     * @return true if the store requires an upload into "sent", false if this happens automatically
     * for any sent message.
     */
    public boolean requireCopyMessageToSentFolder() {
        return true;
    }

    public Folder getFolder(String name) throws MessagingException {
        return null;
    }

    /**
     * Updates the local list of mailboxes according to what is located on the remote server.
     * <em>Note: This does not perform folder synchronization and it will not remove mailboxes
     * that are stored locally but not remotely.</em>
     * @return The set of remote folders
     * @throws MessagingException If there was a problem connecting to the remote server
     */
    public Folder[] updateFolders() throws MessagingException {
        return null;
    }

    public abstract Bundle checkSettings() throws MessagingException;

    /**
     * Delete Store and its corresponding resources.
     * @throws MessagingException
     */
    public void delete() throws MessagingException {
    }

    /**
     * If a Store intends to implement callbacks, it should be prepared to update them
     * via overriding this method.  They may not be available at creation time (in which case they
     * will be passed in as null.
     * @param callbacks The updated provider of store callbacks
     */
    protected void setPersistentDataCallbacks(PersistentDataCallbacks callbacks) {
    }

    /**
     * Callback interface by which a Store can read and write persistent data.
     * TODO This needs to be made more generic & flexible
     */
    public interface PersistentDataCallbacks {

        /**
         * Provides a small place for Stores to store persistent data.
         * @param key identifier for the data (e.g. "sync.key" or "folder.id")
         * @param value The data to persist.  All data must be encoded into a string,
         * so use base64 or some other encoding if necessary.
         */
        public void setPersistentString(String key, String value);

        /**
         * @param key identifier for the data (e.g. "sync.key" or "folder.id")
         * @param defaultValue The data to return if no data was ever saved for this store
         * @return the data saved by the Store, or null if never set.
         */
        public String getPersistentString(String key, String defaultValue);
    }

    /**
     * Handle discovery of account settings using only the user's email address and password
     * @param context the context of the caller
     * @param emailAddress the email address of the exchange user
     * @param password the password of the exchange user
     * @return a Bundle containing an error code and a HostAuth (if successful)
     * @throws MessagingException
     */
    public Bundle autoDiscover(Context context, String emailAddress, String password)
            throws MessagingException {
        return null;
    }

    /**
     * Returns a {@link Mailbox} for the given path. If the path is not in the database, a new
     * mailbox will be created.
     */
    protected static Mailbox getMailboxForPath(Context context, long accountId, String path) {
        Mailbox mailbox = Mailbox.restoreMailboxForPath(context, accountId, path);
        if (mailbox == null) {
            mailbox = new Mailbox();
        }
        return mailbox;
    }

    /**
     * Updates the fields within the given mailbox. Only the fields that are important to
     * non-EAS accounts are modified.
     */
    protected static void updateMailbox(Mailbox mailbox, long accountId, String mailboxPath,
            char delimiter, boolean selectable, int type) {
        mailbox.mAccountKey = accountId;
        mailbox.mDelimiter = delimiter;
        String displayPath = mailboxPath;
        int pathIndex = mailboxPath.lastIndexOf(delimiter);
        if (pathIndex > 0) {
            displayPath = mailboxPath.substring(pathIndex + 1);
        }
        mailbox.mDisplayName = displayPath;
        if (selectable) {
            mailbox.mFlags = Mailbox.FLAG_HOLDS_MAIL | Mailbox.FLAG_ACCEPTS_MOVED_MAIL;
        }
        mailbox.mFlagVisible = true;
        //mailbox.mParentKey;
        //mailbox.mParentServerId;
        mailbox.mServerId = mailboxPath;
        //mailbox.mServerId;
        //mailbox.mSyncFrequency;
        //mailbox.mSyncKey;
        //mailbox.mSyncLookback;
        //mailbox.mSyncTime;
        mailbox.mType = type;
        //box.mUnreadCount;
        mailbox.mVisibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
    }
}
