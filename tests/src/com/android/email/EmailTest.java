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

import junit.framework.TestCase;

public class EmailTest extends TestCase {
    public void testGetColorIndexFromAccountId() {
        // First account (id=1) gets index 0.
        assertEquals(0, Email.getColorIndexFromAccountId(1));
        assertEquals(1, Email.getColorIndexFromAccountId(2));

        // Never return negative.
        assertTrue(Email.getColorIndexFromAccountId(-5) >= 0);

        // Shouldn't throw ArrayIndexOutOfRange or anything.
        for (int i = -100; i < 100; i++) {
            Email.getAccountColorResourceId(i);
            Email.getAccountColor(i);
        }
    }
}
