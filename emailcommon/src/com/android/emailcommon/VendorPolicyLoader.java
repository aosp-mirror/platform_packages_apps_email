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

package com.android.emailcommon;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;

import com.android.mail.utils.LogUtils;

import java.io.Serializable;
import java.lang.reflect.Method;

/**
 * A bridge class to the email vendor policy apk.
 *
 * <p>Email vendor policy is a system apk named "com.android.email.helper".  When exists, it must
 * contain a class called "com.android.email.policy.EmailPolicy" with a static public method
 * <code>Bundle getPolicy(String, Bundle)</code>, which serves vendor specific configurations.
 *
 * <p>A vendor policy apk is optional.  The email application will operate properly when none is
 * found.
 */
public class VendorPolicyLoader {
    private static final String POLICY_PACKAGE = "com.android.email.policy";
    private static final String POLICY_CLASS = POLICY_PACKAGE + ".EmailPolicy";
    private static final String GET_POLICY_METHOD = "getPolicy";
    private static final Class<?>[] ARGS = new Class<?>[] {String.class, Bundle.class};

    // call keys and i/o bundle keys
    // when there is only one parameter or return value, use call key
    private static final String USE_ALTERNATE_EXCHANGE_STRINGS = "useAlternateExchangeStrings";
    private static final String GET_IMAP_ID = "getImapId";
    private static final String GET_IMAP_ID_USER = "getImapId.user";
    private static final String GET_IMAP_ID_HOST = "getImapId.host";
    private static final String GET_IMAP_ID_CAPA = "getImapId.capabilities";
    private static final String FIND_PROVIDER = "findProvider";
    private static final String FIND_PROVIDER_IN_URI = "findProvider.inUri";
    private static final String FIND_PROVIDER_IN_USER = "findProvider.inUser";
    private static final String FIND_PROVIDER_OUT_URI = "findProvider.outUri";
    private static final String FIND_PROVIDER_OUT_USER = "findProvider.outUser";
    private static final String FIND_PROVIDER_NOTE = "findProvider.note";

    /** Singleton instance */
    private static VendorPolicyLoader sInstance;

    private final Method mPolicyMethod;

    public static VendorPolicyLoader getInstance(Context context) {
        if (sInstance == null) {
            // It's okay to instantiate VendorPolicyLoader multiple times.  No need to synchronize.
            sInstance = new VendorPolicyLoader(context);
        }
        return sInstance;
    }

    /**
     * For testing only.
     *
     * Replaces the instance with a new instance that loads a specified class.
     */
    public static void injectPolicyForTest(Context context, String apkPackageName, Class<?> clazz) {
        String name = clazz.getName();
        LogUtils.d(Logging.LOG_TAG, String.format("Using policy: package=%s name=%s",
                apkPackageName, name));
        sInstance = new VendorPolicyLoader(context, apkPackageName, name, true);
    }

    /**
     * For testing only.
     *
     * Clear the instance so that the next {@link #getInstance} call will return a regular,
     * non-injected instance.
     */
    public static void clearInstanceForTest() {
        sInstance = null;
    }

    private VendorPolicyLoader(Context context) {
        this(context, POLICY_PACKAGE, POLICY_CLASS, false);
    }

    /**
     * Constructor for testing, where we need to use an alternate package/class name, and skip
     * the system apk check.
     */
    public VendorPolicyLoader(Context context, String apkPackageName, String className,
            boolean allowNonSystemApk) {
        if (!allowNonSystemApk && !isSystemPackage(context, apkPackageName)) {
            mPolicyMethod = null;
            return;
        }

        Class<?> clazz = null;
        Method method = null;
        try {
            final Context policyContext = context.createPackageContext(apkPackageName,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            final ClassLoader classLoader = policyContext.getClassLoader();
            clazz = classLoader.loadClass(className);
            method = clazz.getMethod(GET_POLICY_METHOD, ARGS);
        } catch (NameNotFoundException ignore) {
            // Package not found -- it's okay - there's no policy .apk found, which is OK
        } catch (ClassNotFoundException e) {
            // Class not found -- probably not OK, but let's not crash here
            LogUtils.w(Logging.LOG_TAG, "VendorPolicyLoader: " + e);
        } catch (NoSuchMethodException e) {
            // Method not found -- probably not OK, but let's not crash here
            LogUtils.w(Logging.LOG_TAG, "VendorPolicyLoader: " + e);
        }
        mPolicyMethod = method;
    }

    // Not private for testing
    public static boolean isSystemPackage(Context context, String packageName) {
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(packageName, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (NameNotFoundException e) {
            return false; // Package not found.
        }
    }

    /**
     * Calls the getPolicy method in the policy apk, if one exists.  This method never returns null;
     * It returns an empty {@link Bundle} when there is no policy apk (or even if the inner
     * getPolicy returns null).
     */
    // Not private for testing
    public Bundle getPolicy(String policy, Bundle args) {
        Bundle ret = null;
        if (mPolicyMethod != null) {
            try {
                ret = (Bundle) mPolicyMethod.invoke(null, policy, args);
            } catch (Exception e) {
                LogUtils.w(Logging.LOG_TAG, "VendorPolicyLoader", e);
            }
        }
        return (ret != null) ? ret : Bundle.EMPTY;
    }

    /**
     * Returns true if alternate exchange descriptive text is required.
     *
     * Vendor function:
     *  Select: USE_ALTERNATE_EXCHANGE_STRINGS
     *  Params: none
     *  Result: USE_ALTERNATE_EXCHANGE_STRINGS (boolean)
     */
    public boolean useAlternateExchangeStrings() {
        return getPolicy(USE_ALTERNATE_EXCHANGE_STRINGS, null)
                .getBoolean(USE_ALTERNATE_EXCHANGE_STRINGS, false);
    }

    /**
     * Returns additional key/value pairs for the IMAP ID string.
     *
     * Vendor function:
     *  Select: GET_IMAP_ID
     *  Params: GET_IMAP_ID_USER (String)
     *          GET_IMAP_ID_HOST (String)
     *          GET_IMAP_ID_CAPABILITIES (String)
     *  Result: GET_IMAP_ID (String)
     *
     * @param userName the server that is being contacted (e.g. "imap.server.com")
     * @param host the server that is being contacted (e.g. "imap.server.com")
     * @param capabilities reported capabilities, if known.  null is OK
     * @return zero or more key/value pairs, quoted and delimited by spaces.  If there is
     * nothing to add, return null.
     */
    public String getImapIdValues(String userName, String host, String capabilities) {
        Bundle params = new Bundle();
        params.putString(GET_IMAP_ID_USER, userName);
        params.putString(GET_IMAP_ID_HOST, host);
        params.putString(GET_IMAP_ID_CAPA, capabilities);
        String result = getPolicy(GET_IMAP_ID, params).getString(GET_IMAP_ID);
        return result;
    }

    public static class OAuthProvider implements Serializable {
        private static final long serialVersionUID = 8511656164616538990L;

        public String id;
        public String label;
        public String authEndpoint;
        public String tokenEndpoint;
        public String refreshEndpoint;
        public String responseType;
        public String redirectUri;
        public String scope;
        public String clientId;
        public String clientSecret;
        public String state;
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
        public String altIncomingUriTemplate;
        public String altIncomingUsernameTemplate;
        public String altOutgoingUriTemplate;
        public String altOutgoingUsernameTemplate;
        public String incomingUri;
        public String incomingUsername;
        public String outgoingUri;
        public String outgoingUsername;
        public String note;
        public String oauth;

        /**
         * Expands templates in all of the  provider fields that support them. Currently,
         * templates are used in 4 fields -- incoming and outgoing URI and user name.
         * @param email user-specified data used to replace template values
         */
        public void expandTemplates(String email) {
            final String[] emailParts = email.split("@");
            final String user = emailParts[0];

            incomingUri = expandTemplate(incomingUriTemplate, email, user);
            incomingUsername = expandTemplate(incomingUsernameTemplate, email, user);
            outgoingUri = expandTemplate(outgoingUriTemplate, email, user);
            outgoingUsername = expandTemplate(outgoingUsernameTemplate, email, user);
        }

        /**
         * Like the above, but expands the alternate templates instead
         * @param email user-specified data used to replace template values
         */
        public void expandAlternateTemplates(String email) {
            final String[] emailParts = email.split("@");
            final String user = emailParts[0];

            incomingUri = expandTemplate(altIncomingUriTemplate, email, user);
            incomingUsername = expandTemplate(altIncomingUsernameTemplate, email, user);
            outgoingUri = expandTemplate(altOutgoingUriTemplate, email, user);
            outgoingUsername = expandTemplate(altOutgoingUsernameTemplate, email, user);
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
     * Returns provider setup information for a given email address
     *
     * Vendor function:
     *  Select: FIND_PROVIDER
     *  Param:  FIND_PROVIDER (String)
     *  Result: FIND_PROVIDER_IN_URI
     *          FIND_PROVIDER_IN_USER
     *          FIND_PROVIDER_OUT_URI
     *          FIND_PROVIDER_OUT_USER
     *          FIND_PROVIDER_NOTE (optional - null is OK)
     *
     * Note, if we get this far, we expect "correct" results from the policy method.  But throwing
     * checked exceptions requires a bunch of upstream changes, so we're going to catch them here
     * and add logging.  Other exceptions may escape here (such as null pointers) to fail fast.
     *
     * @param domain The domain portion of the user's email address
     * @return suitable Provider definition, or null if no match found
     */
    public Provider findProviderForDomain(String domain) {
        Bundle params = new Bundle();
        params.putString(FIND_PROVIDER, domain);
        Bundle out = getPolicy(FIND_PROVIDER, params);
        if (out != null && !out.isEmpty()) {
            Provider p = new Provider();
            p.id = null;
            p.label = null;
            p.domain = domain;
            p.incomingUriTemplate = out.getString(FIND_PROVIDER_IN_URI);
            p.incomingUsernameTemplate = out.getString(FIND_PROVIDER_IN_USER);
            p.outgoingUriTemplate = out.getString(FIND_PROVIDER_OUT_URI);
            p.outgoingUsernameTemplate = out.getString(FIND_PROVIDER_OUT_USER);
            p.note = out.getString(FIND_PROVIDER_NOTE);
            return p;
        }
        return null;
    }
}
