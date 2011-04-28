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
import com.android.email.R;
import com.android.email.VendorPolicyLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.text.Editable;
import android.util.Log;
import android.widget.EditText;

import java.io.Serializable;
import java.util.regex.Pattern;

public class AccountSettingsUtils {

    /** Pattern to match globals in the domain */
    private final static Pattern DOMAIN_GLOB_PATTERN = Pattern.compile("\\*");
    /** Will match any, single character */
    private final static char WILD_CHARACTER = '?';

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
     * Returns a set of content values to commit account changes (not including the foreign keys
     * for the two host auth's and policy) to the database.  Does not actually commit anything.
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
    /*package*/ static Provider findProviderForDomain(
            Context context, String domain, int resourceId) {
        try {
            XmlResourceParser xml = context.getResources().getXml(resourceId);
            int xmlEventType;
            Provider provider = null;
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG
                        && "provider".equals(xml.getName())) {
                    String providerDomain = getXmlAttribute(context, xml, "domain");
                    try {
                        if (globMatchIgnoreCase(domain, providerDomain)) {
                            provider = new Provider();
                            provider.id = getXmlAttribute(context, xml, "id");
                            provider.label = getXmlAttribute(context, xml, "label");
                            provider.domain = domain.toLowerCase();
                            provider.note = getXmlAttribute(context, xml, "note");
                        }
                    } catch (IllegalArgumentException e) {
                        Log.w(Logging.LOG_TAG, "providers line: " + xml.getLineNumber() +
                                "; Domain contains multiple globals");
                    }
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "incoming".equals(xml.getName())
                        && provider != null) {
                    provider.incomingUriTemplate = getXmlAttribute(context, xml, "uri");
                    provider.incomingUsernameTemplate = getXmlAttribute(context, xml, "username");
                }
                else if (xmlEventType == XmlResourceParser.START_TAG
                        && "outgoing".equals(xml.getName())
                        && provider != null) {
                    provider.outgoingUriTemplate = getXmlAttribute(context, xml, "uri");
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
            Log.e(Logging.LOG_TAG, "Error while trying to load provider settings.", e);
        }
        return null;
    }

    /**
     * Returns if the string <code>s1</code> matches the string <code>s2</code>. The string
     * <code>s2</code> may contain any number of wildcards -- a '?' character -- and/or a
     * single global character -- a '*' character. Wildcards match any, single character
     * while a global character matches zero or more characters.
     * @throws IllegalArgumentException if either string is null or <code>s2</code> has
     * multiple globals.
     */
    /*package*/ static boolean globMatchIgnoreCase(String s1, String s2)
            throws IllegalArgumentException {
        if (s1 == null || s2 == null) {
            throw new IllegalArgumentException("one or both strings are null");
        }

        // Handle the possible global in the domain name
        String[] globParts = DOMAIN_GLOB_PATTERN.split(s2);
        switch (globParts.length) {
            case 1:
                // No globals; test for simple equality
                if (!wildEqualsIgnoreCase(s1, globParts[0])) {
                    return false;
                }
                break;
            case 2:
                // Global; test the front & end parts of the domain
                String d1 = globParts[0];
                String d2 = globParts[1];
                if (!wildStartsWithIgnoreCase(s1, d1) ||
                        !wildEndsWithIgnoreCase(s1.substring(d1.length()), d2)) {
                    return false;
                }
                break;
            default:
                throw new IllegalArgumentException("Multiple globals");
        }
        return true;
    }

    /**
     * Returns if the string <code>s1</code> equals the string <code>s2</code>. The string
     * <code>s2</code> may contain zero or more wildcards -- a '?' character.
     * @throws IllegalArgumentException if the strings are null.
     */
    /*package*/ static boolean wildEqualsIgnoreCase(String s1, String s2)
            throws IllegalArgumentException {
        if (s1 == null || s2 == null) {
            throw new IllegalArgumentException("one or both strings are null");
        }
        if (s1.length() != s2.length()) {
            return false;
        }
        char[] charArray1 = s1.toLowerCase().toCharArray();
        char[] charArray2 = s2.toLowerCase().toCharArray();
        for (int i = 0; i < charArray2.length; i++) {
            if (charArray2[i] == WILD_CHARACTER || charArray1[i] == charArray2[i]) continue;
            return false;
        }
        return true;
    }

    /**
     * Returns if the string <code>s1</code> starts with the string <code>s2</code>. The string
     * <code>s2</code> may contain zero or more wildcards -- a '?' character.
     * @throws IllegalArgumentException if the strings are null.
     */
    /*package*/ static boolean wildStartsWithIgnoreCase(String s1, String s2)
            throws IllegalArgumentException {
        if (s1 == null || s2 == null) {
            throw new IllegalArgumentException("one or both strings are null");
        }
        if (s1.length() < s2.length()) {
            return false;
        }
        s1 = s1.substring(0, s2.length());
        return wildEqualsIgnoreCase(s1, s2);
    }

    /**
     * Returns if the string <code>s1</code> ends with the string <code>s2</code>. The string
     * <code>s2</code> may contain zero or more wildcards -- a '?' character.
     * @throws IllegalArgumentException if the strings are null.
     */
    /*package*/ static boolean wildEndsWithIgnoreCase(String s1, String s2)
            throws IllegalArgumentException {
        if (s1 == null || s2 == null) {
            throw new IllegalArgumentException("one or both strings are null");
        }
        if (s1.length() < s2.length()) {
            return false;
        }
        s1 = s1.substring(s1.length() - s2.length(), s1.length());
        return wildEqualsIgnoreCase(s1, s2);
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
        public String incomingUriTemplate;
        public String incomingUsernameTemplate;
        public String outgoingUriTemplate;
        public String outgoingUsernameTemplate;
        public String incomingUri;
        public String incomingUsername;
        public String outgoingUri;
        public String outgoingUsername;
        public String note;

        /**
         * Expands templates in all of the  provider fields that support them. Currently,
         * templates are used in 4 fields -- incoming and outgoing URI and user name.
         * @param email user-specified data used to replace template values
         */
        public void expandTemplates(String email) {
            String[] emailParts = email.split("@");
            String user = emailParts[0];

            incomingUri = expandTemplate(incomingUriTemplate, email, user);
            incomingUsername = expandTemplate(incomingUsernameTemplate, email, user);
            outgoingUri = expandTemplate(outgoingUriTemplate, email, user);
            outgoingUsername = expandTemplate(outgoingUsernameTemplate, email, user);
        }

        /**
         * Replaces all parameterized values in the given template. The values replaced are
         * $domain, $user and $email.
         */
        private String expandTemplate(String template, String email, String user) {
            String returnString = template;
            returnString = returnString.replaceAll("\\$email", email);
            returnString = returnString.replaceAll("\\$user", user);
            returnString = returnString.replaceAll("\\$domain", domain);
            return returnString;
        }
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
        Editable password = passwordField.getText();
        int length = password.length();
        if (length > 0) {
            if (password.charAt(0) == ' ' || password.charAt(length-1) == ' ') {
                passwordField.setError(context.getString(R.string.account_password_spaces_error));
            }
        }
    }

}
