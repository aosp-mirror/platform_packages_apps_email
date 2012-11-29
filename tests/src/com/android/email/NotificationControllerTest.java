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

import android.app.Notification;
import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.test.AndroidTestCase;

import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;

/**
 * Test for {@link NotificationController}.
 *
 * TODO Add tests for all methods.
 */
public class NotificationControllerTest extends AndroidTestCase {
    private Context mProviderContext;
    private NotificationController mTarget;

    private final MockClock mMockClock = new MockClock();
    private int mRingerMode;

    /**
     * Subclass {@link NotificationController} to override un-mockable operations.
     */
    private class NotificationControllerForTest extends NotificationController {
        NotificationControllerForTest(Context context) {
            super(context, mMockClock);
        }

        @Override
        int getRingerMode() {
            return mRingerMode;
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(mContext);
        mTarget = new NotificationControllerForTest(mProviderContext);
    }

    public void testSetupSoundAndVibration() {
        final Context c = mProviderContext;
        final Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        final Notification.Builder nb = new Notification.Builder(c);
        final Uri expectedRingtone = Uri.parse(a1.mRingtoneUri);
        Notification n;

        // === Ringer mode change ===
        mRingerMode = AudioManager.RINGER_MODE_NORMAL;

        // VIBRATE, with a ringer tone
        a1.mFlags = Account.FLAGS_VIBRATE;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertTrue((n.defaults & Notification.DEFAULT_VIBRATE) != 0);
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // No VIBRATE flags, with a ringer tone
        a1.mFlags = 0;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertFalse((n.defaults & Notification.DEFAULT_VIBRATE) != 0); // no vibe
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // === Ringer mode change ===
        mRingerMode = AudioManager.RINGER_MODE_VIBRATE;

        // VIBRATE, with a ringer tone
        a1.mFlags = Account.FLAGS_VIBRATE;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertTrue((n.defaults & Notification.DEFAULT_VIBRATE) != 0);
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // No VIBRATE flags, with a ringer tone
        a1.mFlags = 0;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertFalse((n.defaults & Notification.DEFAULT_VIBRATE) != 0); // no vibe
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // === Ringer mode change ===
        mRingerMode = AudioManager.RINGER_MODE_SILENT;

        // VIBRATE, with a ringer tone
        a1.mFlags = Account.FLAGS_VIBRATE;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertTrue((n.defaults & Notification.DEFAULT_VIBRATE) != 0);
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // No VIBRATE flags, with a ringer tone
        a1.mFlags = 0;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertEquals(expectedRingtone, n.sound);
        assertFalse((n.defaults & Notification.DEFAULT_VIBRATE) != 0); // no vibe
        assertTrue((n.flags & Notification.FLAG_SHOW_LIGHTS) != 0); // always set
        assertTrue((n.defaults & Notification.DEFAULT_LIGHTS) != 0); // always set

        // No ringer tone
        a1.mRingtoneUri = null;

        nb.setDefaults(0);
        nb.setSound(null);
        mTarget.setupSoundAndVibration(nb, a1);
        n = nb.getNotification();

        assertNull(n.sound);
    }

    public void testCreateNewMessageNotification() {
        final Context c = mProviderContext;
        Notification n;

        // Case 1: 1 account, 1 unseen message
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("inbox", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Message m1 = ProviderTestUtils.setupMessage("message", a1.mId, b1.mId, true, true, c);

        n = mTarget.createNewMessageNotification(a1.mId, b1.mId, null, m1.mId, 1, 1);

        assertEquals(R.drawable.stat_notify_email_generic, n.icon);
        assertEquals(mMockClock.mTime, n.when);
        assertNotNull(n.largeIcon);
        assertEquals(0, n.number);

        // TODO Check content -- how?

        // Case 2: 1 account, 2 unseen message
        n = mTarget.createNewMessageNotification(a1.mId, b1.mId, null, m1.mId, 2, 2);

        assertEquals(R.drawable.stat_notify_email_generic, n.icon);
        assertEquals(mMockClock.mTime, n.when);
        assertNotNull(n.largeIcon);
        assertEquals(2, n.number);

        // TODO Check content -- how?

        // TODO Add 2 account test, if we find a way to check content
    }

    public void testCreateNewMessageNotificationWithEmptyFrom() {
        final Context c = mProviderContext;
        Notification n;

        // Message with no from fields.
        Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        Mailbox b1 = ProviderTestUtils.setupMailbox("inbox", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Message m1 = ProviderTestUtils.setupMessage("message", a1.mId, b1.mId, true, false, c);
        m1.mFrom = null;
        m1.save(c);

        // This shouldn't crash.
        n = mTarget.createNewMessageNotification(a1.mId, b1.mId, null, m1.mId, 1, 1);

        // Minimum test for the result
        assertEquals(R.drawable.stat_notify_email_generic, n.icon);
    }
}
