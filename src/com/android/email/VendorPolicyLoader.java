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

package com.android.email;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;

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

    private static final String USE_ALTERNATE_EXCHANGE_STRINGS = "useAlternateExchangeStrings";

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

    private VendorPolicyLoader(Context context) {
        this(context, POLICY_PACKAGE, POLICY_CLASS, false);
    }

    /**
     * Constructor for testing, where we need to use an alternate package/class name, and skip
     * the system apk check.
     */
    /* package */ VendorPolicyLoader(Context context, String packageName, String className,
            boolean allowNonSystemApk) {
        if (!allowNonSystemApk && !isSystemPackage(context, packageName)) {
            mPolicyMethod = null;
            return;
        }

        Class<?> clazz = null;
        Method method = null;
        try {
            final Context policyContext = context.createPackageContext(packageName,
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE);
            final ClassLoader classLoader = policyContext.getClassLoader();
            clazz = classLoader.loadClass(className);
            method = clazz.getMethod(GET_POLICY_METHOD, ARGS);
        } catch (NameNotFoundException ignore) {
            // Package not found -- it's okay.
            // Should be caught in isSystemPackage(), except in unit tests.
        } catch (Exception e) { // NoSuchMethodException/ClassNotFoundException
            // Package found but the class or method not found, or got a RuntimeException (e.g.
            // SecurityException).  Most probably a problem, but we proceed as if the package
            // doesn't exist.
            Log.w(Email.LOG_TAG, "VendorPolicyLoader: " + e);
        }
        mPolicyMethod = method;
    }

    // Not private for testing
    /* package */ static boolean isSystemPackage(Context context, String packageName) {
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
    /* package */ Bundle getPolicy(String policy, Bundle args) {
        Bundle ret = null;
        if (mPolicyMethod != null) {
            try {
                ret = (Bundle) mPolicyMethod.invoke(null, policy, args);
            } catch (Exception e) {
                Log.w(Email.LOG_TAG, "VendorPolicyLoader", e);
            }
        }
        return (ret != null) ? ret : Bundle.EMPTY;
    }

    /**
     * Returns true if alternate exchange descriptive text is required.
     */
    public boolean useAlternateExchangeStrings() {
        return getPolicy(USE_ALTERNATE_EXCHANGE_STRINGS, null)
                .getBoolean(USE_ALTERNATE_EXCHANGE_STRINGS, false);
    }
}
