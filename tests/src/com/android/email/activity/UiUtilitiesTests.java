/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.R;

import android.app.Activity;
import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;

import java.util.Locale;

@SmallTest
public class UiUtilitiesTests extends AndroidTestCase {
    public void brokentestFormatSize() {
        if (!"en".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
            return; // Only works on the EN locale.
        }
        assertEquals("0B", UiUtilities.formatSize(getContext(), 0));
        assertEquals("1B", UiUtilities.formatSize(getContext(), 1));
        assertEquals("1023B", UiUtilities.formatSize(getContext(), 1023));
        assertEquals("1KB", UiUtilities.formatSize(getContext(), 1024));
        assertEquals("1023KB", UiUtilities.formatSize(getContext(), 1024 * 1024 - 1));
        assertEquals("1MB", UiUtilities.formatSize(getContext(), 1024 * 1024));
        assertEquals("1023MB", UiUtilities.formatSize(getContext(), 1024 * 1024 * 1024 - 1));
        assertEquals("1GB", UiUtilities.formatSize(getContext(), 1024 * 1024 * 1024));
        assertEquals("5GB", UiUtilities.formatSize(getContext(), 5L * 1024 * 1024 * 1024));
    }

    public void brokentestGetMessageCountForUi() {
        final Context c = getContext();

        // Negavive valeus not really expected, but at least shouldn't crash.
        assertEquals("-1", UiUtilities.getMessageCountForUi(c, -1, true));
        assertEquals("-1", UiUtilities.getMessageCountForUi(c, -1, false));

        assertEquals("", UiUtilities.getMessageCountForUi(c, 0, true));
        assertEquals("0", UiUtilities.getMessageCountForUi(c, 0, false));

        assertEquals("1", UiUtilities.getMessageCountForUi(c, 1, true));
        assertEquals("1", UiUtilities.getMessageCountForUi(c, 1, false));

        assertEquals("999", UiUtilities.getMessageCountForUi(c, 999, true));
        assertEquals("999", UiUtilities.getMessageCountForUi(c, 999, false));

        final String moreThan999 = c.getString(R.string.more_than_999);

        assertEquals(moreThan999, UiUtilities.getMessageCountForUi(c, 1000, true));
        assertEquals(moreThan999, UiUtilities.getMessageCountForUi(c, 1000, false));

        assertEquals(moreThan999, UiUtilities.getMessageCountForUi(c, 1001, true));
        assertEquals(moreThan999, UiUtilities.getMessageCountForUi(c, 1001, false));
    }

    public void brokentestSetVisibilitySafe() {
        {
            DummyView v = new DummyView(getContext());
            UiUtilities.setVisibilitySafe(v, View.VISIBLE);
            assertEquals(View.VISIBLE, v.mVisibility);

            // Shouldn't crash
            UiUtilities.setVisibilitySafe(null, View.VISIBLE);
        }

        {
            DummyActivity a = new DummyActivity();
            DummyView v = new DummyView(getContext());
            a.mDummyViewId = 3;
            a.mDummyView = v;

            UiUtilities.setVisibilitySafe(a, 3, View.VISIBLE);
            assertEquals(View.VISIBLE, v.mVisibility);

            // shouldn't crash
            UiUtilities.setVisibilitySafe(a, 5, View.VISIBLE);
        }
        // No test for setVisibilitySafe(View, int, int) -- see testGetView().
    }

    private static class DummyActivity extends Activity {
        public int mDummyViewId;
        public View mDummyView;

        @Override
        public View findViewById(int id) {
            return (id == mDummyViewId) ? mDummyView : null;
        }
    }

    private static class DummyView extends View {
        public int mVisibility = View.GONE;

        public DummyView(Context context) {
            super(context);
        }

        @Override
        public void setVisibility(int visibility) {
            mVisibility = visibility;
        }
    }
}
