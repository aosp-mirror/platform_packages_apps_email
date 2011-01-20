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

package com.android.email.activity.setup;

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.VendorPolicyLoader;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.AccountColumns;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.text.Editable;
import android.util.Log;
import android.widget.EditText;

import java.io.Serializable;
import java.net.URI;

public class AccountSettingsUtils {

    /**
     * Commits the UI-related settings of an account to the provider.  This is static so that it
     * can be used by the various account activities.  If the account has never been saved, this
     * method saves it; otherwise, it just saves the settings.
     * @param context the context of the caller
     * @param account the account whose settings will be committed
     */
    public static void commitSettings(Context context, EmailContent.Account account) {
        if (!account.isSaved()) {
            account.save(context);
        } else {
            ContentValues cv = getAccountContentValues(account);
            account.update(context, cv);
        }
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backupAccounts(context);
    }

    /**
     * Returns a set of content values to commit account changes (not including HostAuth) to
     * the database.  Does not actually commit anything.
     */
    public static ContentValues getAccountContentValues(EmailContent.Account account) {
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.IS_DEFAULT, account.mIsDefault);
        cv.put(AccountColumns.DISPLAY_NAME, account.getDisplayName());
        cv.put(AccountColumns.SENDER_NAME, account.getSenderName());
        cv.put(AccountColumns.SIGNATURE, account.getSignature());
        cv.put(AccountColumns.SYNC_INTERVAL, account.mSyncInterval);
        cv.put(AccountColumns.RINGTONE_URI, account.mRingtoneUri);
        cv.put(AccountColumns.FLAGS, account.mFlags);
        cv.put(AccountColumns.SYNC_LOOKBACK, account.mSyncLookback);
        cv.put(AccountColumns.SECURITY_FLAGS, account.mSecurityFlags);
        cv.put(AccountColumns.SECURITY_SYNC_KEY, account.mSecuritySyncKey);
        return cv;
    }

    /**
     * Search the list of known Email providers looking for one that matches the user's email
     * domain.  We check for vendor supplied values first, then we look in providers_product.xml,
     * and finally by the entries in platform providers.xml.  This provides a nominal override
     * capability.
     *
     * A match is defined as any provider entry for which the "domain" attribute matches.
     *
     * @param domain The domain portion of the user's email address
     * @return suitable Provider definition, or null if no match found
     */
    public static Provider findProviderForDomain(Context context, String domain) {
        Provider p = VendorPolicyLoader.getInstance(context).findProviderForDomain(domain);
        if (p == null) {
            p = findProviderForDomain(context, domain, R.xml.providers_product);
        }
        if (p == null) {
            p = findProviderForDomain(context, domain, R.xml.providers);
        }
        return p;
    }

    /**
     * Search a single resource containing known Email provider definitions.
     *
     * @param domain The domain portion of the user's email address
     * @param resourceId Id of the provider resource to scan
     * @return suitable Provider definition, or null if no match found
     */
    private static Provider findProviderForDomain(Context context, String domain, int resourceId) {
        try {
            XmlResourceParser xml = context.getResources().getXml(resourceId);
            int xmlEventType;
            Provider provider = null;
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG
                        && "provider".equals(xml.getName())
                        && domain.equalsIgnoreCase(getXmlAttribute(context, xml, "domain"))) {
                    provider = new Provider();
                    provider.id = getXmlAttribute(context, xml, "id");
                    provider.label = getXmlAttribute(context, xml, "label");
                    provider.domain = getXmlAttribute(context, xml, "domain");
                    provider.note = getXmlAttribute(context, xml, "note");
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "incoming".equals(xml.getName())
                        && provider != null) {
                    provider.incomingUriTemplate = new URI(getXmlAttribute(context, xml, "uri"));
                    provider.incomingUsernameTemplate = getXmlAttribute(context, xml, "username");
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "outgoing".equals(xml.getName())
                        && provider != null) {
                    provider.outgoingUriTemplate = new URI(getXmlAttribute(context, xml, "uri"));
                    provider.outgoingUsernameTemplate = getXmlAttribute(context, xml, "username");
                }
                else if (xmlEventType == XmlResourceParser.END_TAG
                        && "provider".equals(xml.getName())
                        && provider != null) {
                    return provider;
                }
            }
        }
        catch (Exception e) {
            Log.e(Email.LOG_TAG, "Error while trying to load provider settings.", e);
        }
        return null;
    }

    /**
     * Attempts to get the given attribute as a String resource first, and if it fails
     * returns the attribute as a simple String value.
     * @param xml
     * @param name
     * @return the requested resource
     */
    private static String getXmlAttribute(Context context, XmlResourceParser xml, String name) {
        int resId = xml.getAttributeResourceValue(null, name, 0);
        if (resId == 0) {
            return xml.getAttributeValue(null, name);
        }
        else {
            return context.getString(resId);
        }
    }

    public static class Provider implements Serializable {
        private static final long serialVersionUID = 8511656164616538989L;

        public String id;
        public String label;
        public String domain;
        public URI incomingUriTemplate;
        public String incomingUsernameTemplate;
        public URI outgoingUriTemplate;
        public String outgoingUsernameTemplate;
        public String note;
    }

    /**
     * Infer potential email server addresses from domain names
     *
     * Incoming: Prepend "imap" or "pop3" to domain, unless "pop", "pop3",
     *          "imap", or "mail" are found.
     * Outgoing: Prepend "smtp" if "pop", "pop3", "imap" are found.
     *          Leave "mail" as-is.
     * TBD: Are there any useful defaults for exchange?
     *
     * @param server name as we know it so far
     * @param incoming "pop3" or "imap" (or null)
     * @param outgoing "smtp" or null
     * @return the post-processed name for use in the UI
     */
    public static String inferServerName(String server, String incoming, String outgoing) {
        // Default values cause entire string to be kept, with prepended server string
        int keepFirstChar = 0;
        int firstDotIndex = server.indexOf('.');
        if (firstDotIndex != -1) {
            // look at first word and decide what to do
            String firstWord = server.substring(0, firstDotIndex).toLowerCase();
            boolean isImapOrPop = "imap".equals(firstWord)
                    || "pop3".equals(firstWord) || "pop".equals(firstWord);
            boolean isMail = "mail".equals(firstWord);
            // Now decide what to do
            if (incoming != null) {
                // For incoming, we leave imap/pop/pop3/mail alone, or prepend incoming
                if (isImapOrPop || isMail) {
                    return server;
                }
            } else {
                // For outgoing, replace imap/pop/pop3 with outgoing, leave mail alone, or
                // prepend outgoing
                if (isImapOrPop) {
                    keepFirstChar = firstDotIndex + 1;
                } else if (isMail) {
                    return server;
                } else {
                    // prepend
                }
            }
        }
        return ((incoming != null) ? incoming : outgoing) + '.' + server.substring(keepFirstChar);
    }

    /**
     * Helper to set error status on password fields that have leading or trailing spaces
     */
    public static void checkPasswordSpaces(Context context, EditText passwordField) {
        // STOPSHIP - there is a bug in the framework that makes these flicker.
        // If the bug cannot be fixed shortly, then this should be pulled before we ship
        Editable password = passwordField.getText();
        int length = password.length();
        if (length > 0) {
            if (password.charAt(0) == ' ' || password.charAt(length-1) == ' ') {
                passwordField.setError(context.getString(R.string.account_password_spaces_error));
            }
        }
    }

}
