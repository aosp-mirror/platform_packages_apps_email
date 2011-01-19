/* Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.provider;

import com.android.email.provider.WidgetProvider.EmailWidget;
import com.android.email.provider.WidgetProvider.WidgetViewSwitcher;

import android.content.Context;
import android.test.ProviderTestCase2;

import java.util.concurrent.ExecutionException;

/**
 * Tests of WidgetProvider
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.WidgetProviderTests email
 */
public class WidgetProviderTests extends ProviderTestCase2<EmailProvider> {
    private Context mMockContext;

    public WidgetProviderTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Switch views synchronously without loading
     */
    private void switchSync(EmailWidget widget) {
        WidgetViewSwitcher switcher = new WidgetProvider.WidgetViewSwitcher(widget);
        try {
            switcher.disableLoadAfterSwitchForTest();
            switcher.execute().get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
    }

    public void testWidgetSwitcher() {
        // Create account
        ProviderTestUtils.setupAccount("account1", true, mMockContext);
        // Manually set up context
        WidgetProvider.setContextForTest(mMockContext);
        // Create a widget
        EmailWidget widget = new EmailWidget(1);
        // Since there is one account, this should switch to the ACCOUNT view
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);

        // Create account
        ProviderTestUtils.setupAccount("account2", true, mMockContext);
        // Create a widget
        widget = new EmailWidget(2);
        // Since there are two accounts, this should switch to the ALL_INBOX view
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ALL_INBOX, widget.mViewType);

        // The next two switches should be to the two accounts
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.ACCOUNT, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.UNREAD, widget.mViewType);
        switchSync(widget);
        assertEquals(WidgetProvider.ViewType.STARRED, widget.mViewType);
    }
}
