package com.android.email;

import android.content.Context;

import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogTag;

public class DebugUtils {
    public static final String LOG_TAG = LogTag.getLogTag();

    public static boolean DEBUG;
    public static boolean DEBUG_EXCHANGE;
    public static boolean DEBUG_FILE;

    public static void init(final Context context) {
        final Preferences prefs = Preferences.getPreferences(context);
        DEBUG = prefs.getEnableDebugLogging();
        DEBUG_EXCHANGE = prefs.getEnableExchangeLogging();
        DEBUG_FILE = prefs.getEnableExchangeFileLogging();

        // Enable logging in the EAS service, so it starts up as early as possible.
        updateLoggingFlags(context);
        enableStrictMode(prefs.getEnableStrictMode());
    }

    /**
     * Load enabled debug flags from the preferences and update the EAS debug flag.
     */
    public static void updateLoggingFlags(Context context) {
        Preferences prefs = Preferences.getPreferences(context);
        int debugLogging = prefs.getEnableDebugLogging() ? EmailServiceProxy.DEBUG_BIT : 0;
        int exchangeLogging =
                prefs.getEnableExchangeLogging() ? EmailServiceProxy.DEBUG_EXCHANGE_BIT: 0;
        int fileLogging =
                prefs.getEnableExchangeFileLogging() ? EmailServiceProxy.DEBUG_FILE_BIT : 0;
        int enableStrictMode =
                prefs.getEnableStrictMode() ? EmailServiceProxy.DEBUG_ENABLE_STRICT_MODE : 0;
        int debugBits = debugLogging | exchangeLogging | fileLogging | enableStrictMode;
        EmailServiceUtils.setRemoteServicesLogging(context, debugBits);
    }

    public static void  enableStrictMode(final boolean enable) {
        Utility.enableStrictMode(enable);
    }

}
