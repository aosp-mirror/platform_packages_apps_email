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

package com.android.exchange;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;

import java.io.File;

public class SyncManagerTest extends AndroidTestCase {
    private static class MyContext extends ContextWrapper {
        public boolean isGetFileStreamPathCalled;

        public MyContext(Context base) {
            super(base);
        }

        @Override
        public File getFileStreamPath(String name) {
            isGetFileStreamPathCalled = true;
            return super.getFileStreamPath(name);
        }
    }

    public void testGetDeviceId() throws Exception {
        final MyContext context = new MyContext(getContext());

        final String id = SyncManager.getDeviceId(context);

        // Consists of alpha-numeric
        assertTrue(id.matches("^[a-zA-Z0-9]+$"));

        // getDeviceId may have been called in other tests, so we don't check
        // isGetFileStreamPathCalled here.

        context.isGetFileStreamPathCalled = false;
        final String cachedId = SyncManager.getDeviceId(context);

        // Should be the same.
        assertEquals(id, cachedId);
        // Should be cached.  (If cached, this method won't be called.)
        assertFalse(context.isGetFileStreamPathCalled);
    }
}
