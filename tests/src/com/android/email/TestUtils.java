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

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.View;
import android.view.ViewParent;

import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase;

/**
 * Utility methods used only by tests.
 */
@LargeTest
public class TestUtils extends TestCase /* It tests itself */ {
    public interface Condition {
        public boolean isMet();
    }

    /** Shortcut to create byte array */
    public static byte[] b(int... array) {
        if (array == null) {
            return null;
        }
        byte[] ret = new byte[array.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = (byte) array[i];
        }
        return ret;
    }

    public void testB() {
        assertNull(b(null));
        MoreAsserts.assertEquals(new byte[] {}, b());
        MoreAsserts.assertEquals(new byte[] {1, 2, (byte) 0xff}, b(1, 2, 0xff));
    }

    /**
     * Run {@code runnable} and fails if it doesn't throw a {@code expectedThrowable} or a subclass
     * of it.
     */
    public static void expectThrowable(Runnable runnable,
            Class<? extends Throwable> expectedThrowable) {
        try {
            runnable.run();
            fail("Expected throwable not thrown.");
        } catch (Throwable th) {
            if (expectedThrowable.isAssignableFrom(th.getClass())) {
                return; // Expcted. OK
            }
            fail("Cought unexpected throwable " + th.getClass().getName());
        }
    }

    public void testExpectThrowable() {
        try {
            expectThrowable(new Runnable() {
                @Override public void run() {
                    // Throwing no exception
                }
            }, Throwable.class);
            fail();
        } catch (AssertionFailedError ok) {
        }

        try {
            expectThrowable(new Runnable() {
                @Override public void run() {
                    // Throw RuntimeException, which is not a subclass of Error.
                    throw new RuntimeException();
                }
            }, Error.class);
            fail();
        } catch (AssertionFailedError ok) {
        }

        expectThrowable(new Runnable() {
            @Override public void run() {
                throw new RuntimeException();
            }
        }, Exception.class);
    }

    /**
     * Wait until a {@code Condition} is met.
     */
    public static void waitUntil(Condition condition, int timeoutSeconds) {
        waitUntil("", condition, timeoutSeconds);
    }

    /**
     * Wait until a {@code Condition} is met.
     */
    public static void waitUntil(String message, Condition condition, int timeoutSeconds) {
        LogUtils.d(Logging.LOG_TAG, message + ": Waiting...");
        final long timeout = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < timeout) {
            if (condition.isMet()) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignore) {
            }
        }
        fail(message + ": Timeout");
    }

    public void testWaitUntil() {
        // Shouldn't fail.
        waitUntil("message", new Condition() {
            @Override public boolean isMet() {
                return true;
            }
        }, 1000000);

        expectThrowable(new Runnable() {
            @Override public void run() {
                // Condition never meets, should fail.
                waitUntil("message", new Condition() {
                    @Override public boolean isMet() {
                        return false;
                    }
                }, 0);
            }
        }, AssertionFailedError.class);
    }

    /**
     * @return true if the screen is on and not locked; false otherwise, in which case tests that
     * send key events will fail.
     */
    public static boolean isScreenOnAndNotLocked(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (!pm.isScreenOn()) {
            return false;
        }
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (km.inKeyguardRestrictedInputMode()) {
            return false;
        }
        return true;
    }

    public static void assertViewVisible(View v) {
        if (v == null) {
            throw new NullPointerException();
        }
        for (;;) {
            assertTrue("visibility for " + v, View.VISIBLE == v.getVisibility());
            ViewParent parent = v.getParent();
            if (parent == null || !(parent instanceof View)) {
                break;
            }
            v = (View) parent;
        }
    }
}
