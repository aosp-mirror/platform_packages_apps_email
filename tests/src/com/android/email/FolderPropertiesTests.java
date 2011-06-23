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

package com.android.email;

import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.drawable.Drawable;
import android.test.AndroidTestCase;

import java.util.HashSet;
import java.util.Set;

public class FolderPropertiesTests extends AndroidTestCase {

    private static Cursor buildCursor(String[] columns, Object... values) {
        MatrixCursor c = new MatrixCursor(columns, 1);
        c.addRow(values);
        c.moveToFirst();
        return c;
    }

    /**
     * Tests of the syncronization of array and types of the display folder names
     */
    public void testGetDisplayName() {
        Context context = getContext();
        FolderProperties fp = FolderProperties.getInstance(context);

        assertEquals(
                    context.getString(R.string.mailbox_name_display_inbox),
                        fp.getDisplayName(Mailbox.TYPE_INBOX, 0, "ignored"));

        assertEquals(
                "*name*",
                fp.getDisplayName(Mailbox.TYPE_MAIL, 0, "*name*"));

        assertEquals(
                "*name2*",
                fp.getDisplayName(Mailbox.TYPE_PARENT, 0, "*name2*"));

        assertEquals(
                context.getString(R.string.mailbox_name_display_drafts),
                fp.getDisplayName(Mailbox.TYPE_DRAFTS, 0, "ignored"));

        assertEquals(
                context.getString(R.string.mailbox_name_display_outbox),
                fp.getDisplayName(Mailbox.TYPE_OUTBOX, 0, "ignored"));

        assertEquals(
                context.getString(R.string.mailbox_name_display_sent),
                fp.getDisplayName(Mailbox.TYPE_SENT, 0, "ignored"));

        assertEquals(
                context.getString(R.string.mailbox_name_display_trash),
                fp.getDisplayName(Mailbox.TYPE_TRASH, 0, "ignored"));

        assertEquals(
                context.getString(R.string.mailbox_name_display_junk),
                fp.getDisplayName(Mailbox.TYPE_JUNK, 0, "ignored"));

        // Testing illegal index
        assertEquals(
                "some name",
                fp.getDisplayName(8, 12345678890L, "some name"));


        // Combined mailboxes
        assertEquals(
                context.getString(R.string.account_folder_list_summary_inbox),
                fp.getDisplayName(0, Mailbox.QUERY_ALL_INBOXES, "ignored"));
        assertEquals(
                context.getString(R.string.account_folder_list_summary_starred),
                fp.getDisplayName(0, Mailbox.QUERY_ALL_FAVORITES, "ignored"));
        assertEquals(
                context.getString(R.string.account_folder_list_summary_drafts),
                fp.getDisplayName(0, Mailbox.QUERY_ALL_DRAFTS, "ignored"));
        assertEquals(
                context.getString(R.string.account_folder_list_summary_outbox),
                fp.getDisplayName(0, Mailbox.QUERY_ALL_OUTBOX, "ignored"));
    }

    public void testGetDisplayNameWithCursor() {
        Context context = getContext();
        FolderProperties fp = FolderProperties.getInstance(context);
        String[] columns = new String[] {MailboxColumns.ID, MailboxColumns.TYPE,
                MailboxColumns.DISPLAY_NAME};

        assertEquals(
                context.getString(R.string.mailbox_name_display_inbox),
                fp.getDisplayName(buildCursor(columns, 1, Mailbox.TYPE_INBOX, "ignored"))
                );

        assertEquals(
                "name",
                fp.getDisplayName(buildCursor(columns, 1, Mailbox.TYPE_MAIL, "name"))
                );
    }

    /**
     * Confirm that all of the special icons are available and unique
     */
    public void testSpecialIcons() {
        FolderProperties fp = FolderProperties.getInstance(mContext);

        // Make sure they're available
        Drawable inbox = fp.getIcon(Mailbox.TYPE_INBOX, -1, 0);
        Drawable mail = fp.getIcon(Mailbox.TYPE_MAIL, -1, 0);
        Drawable parent = fp.getIcon(Mailbox.TYPE_PARENT, -1, 0);
        Drawable drafts = fp.getIcon(Mailbox.TYPE_DRAFTS, -1, 0);
        Drawable outbox = fp.getIcon(Mailbox.TYPE_OUTBOX, -1, 0);
        Drawable sent = fp.getIcon(Mailbox.TYPE_SENT, -1, 0);
        Drawable trash = fp.getIcon(Mailbox.TYPE_TRASH, -1, 0);
        Drawable junk = fp.getIcon(Mailbox.TYPE_JUNK, -1, 0);

        // Make sure they're unique
        Set<Drawable> set = new HashSet<Drawable>();
        set.add(inbox);
        set.add(parent);
        set.add(drafts);
        set.add(outbox);
        set.add(sent);
        set.add(trash);
        set.add(junk);
        assertEquals(7, set.size());

        assertNull(mail);
    }

    public void testGetMessageCountWithCursor() {
        Context context = getContext();
        FolderProperties fp = FolderProperties.getInstance(context);
        String[] columns = new String[] {MailboxColumns.TYPE, MailboxColumns.UNREAD_COUNT,
                MailboxColumns.MESSAGE_COUNT};

        assertEquals(
                1,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_INBOX, 1, 2))
                );
        assertEquals(
                1,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_MAIL, 1, 2))
                );

        assertEquals(
                2,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_DRAFTS, 1, 2))
                );
        assertEquals(
                2,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_OUTBOX, 1, 2))
                );

        assertEquals(
                0,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_TRASH, 1, 2))
                );

        assertEquals(
                0,
                fp.getMessageCount(buildCursor(columns, Mailbox.TYPE_SENT, 1, 2))
                );
    }
}
