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

package com.android.email.data;

import com.android.email.MockClock;

import android.test.AndroidTestCase;

public class ThrottlingCursorLoaderTest extends AndroidTestCase {

    public void testUpdateTimeout() {
        MockClock clock = new MockClock();
        ThrottlingCursorLoader l = new ThrottlingCursorLoader(getContext(), null, null, null, null,
                null, 100, 500, clock);

        // Check initial value
        assertEquals(100, l.getTimeoutForTest());

        // First call -- won't change the timeout
        l.updateTimeout();
        assertEquals(100, l.getTimeoutForTest());

        // Call again in 10 ms -- will extend timeout.
        clock.advance(10);
        l.updateTimeout();
        assertEquals(200, l.getTimeoutForTest());

        // Call again in TIMEOUT_EXTEND_INTERAVL ms -- will extend timeout.
        clock.advance(ThrottlingCursorLoader.TIMEOUT_EXTEND_INTERVAL);
        l.updateTimeout();
        assertEquals(400, l.getTimeoutForTest());

        // Again -- timeout reaches max.
        clock.advance(ThrottlingCursorLoader.TIMEOUT_EXTEND_INTERVAL);
        l.updateTimeout();
        assertEquals(500, l.getTimeoutForTest());

        // Call in TIMEOUT_EXTEND_INTERAVL + 1 ms -- timeout will get reset.
        clock.advance(ThrottlingCursorLoader.TIMEOUT_EXTEND_INTERVAL + 1);
        l.updateTimeout();
        assertEquals(100, l.getTimeoutForTest());
    }
}
