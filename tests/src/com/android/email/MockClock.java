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

import com.android.mail.utils.Clock;

public class MockClock extends Clock {
    public static final long DEFAULT_TIME = 10000; // Arbitrary value

    public long mTime = DEFAULT_TIME;

    @Override
    public long getTime() {
        return mTime;
    }

    public void advance() {
        mTime++;
    }

    public void advance(long milliseconds) {
        mTime += milliseconds;
    }
}
