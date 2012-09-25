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
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.google.common.base.Preconditions;

import android.content.Context;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;


// TODO When the UI is settled, cache all strings/drawables
/**
 * Stores names and icons for system folders. System folders are those with special
 * meaning, such as Inbox, Drafts, Trash, etc... Although these folders may or may
 * not exist on the server, we want to ensure they are displayed in a very specific
 * manner.
 *
 * Some methods probably should belong to {@link Mailbox}, but as this class uses resources,
 * we can't move them to emailcommon...
 */
public class FolderProperties {

    private static FolderProperties sInstance;

    private final Context mContext;

    // Caches for frequently accessed resources.
    private final String[] mSpecialMailbox;
    private final TypedArray mSpecialMailboxDrawable;
    private final Drawable mSummaryStarredMailboxDrawable;
    private final Drawable mSummaryCombinedInboxDrawable;

    private FolderProperties(Context context) {
        mContext = context.getApplicationContext();
        mSpecialMailbox = context.getResources().getStringArray(R.array.mailbox_display_names);
        for (int i = 0; i < mSpecialMailbox.length; ++i) {
            if ("".equals(mSpecialMailbox[i])) {
                // there is no localized name, so use the display name from the server
                mSpecialMailbox[i] = null;
            }
        }
        mSpecialMailboxDrawable =
            context.getResources().obtainTypedArray(R.array.mailbox_display_icons);
        mSummaryStarredMailboxDrawable =
            context.getResources().getDrawable(R.drawable.ic_menu_star_holo_light);
        mSummaryCombinedInboxDrawable =
            context.getResources().getDrawable(R.drawable.ic_list_combined_inbox);
    }

    public static synchronized FolderProperties getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FolderProperties(context);
        }
        return sInstance;
    }

    public String getCombinedMailboxName(long mailboxId) {
        // Special combined mailboxes
        int resId = 0;

        // Can't use long for switch!?
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            resId = R.string.account_folder_list_summary_inbox;
        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            resId = R.string.account_folder_list_summary_starred;
        } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            resId = R.string.account_folder_list_summary_drafts;
        } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            resId = R.string.account_folder_list_summary_outbox;
        }
        if (resId != 0) {
            return mContext.getString(resId);
        }
        return null;
    }

    /**
     * Lookup names of localized special mailboxes
     */
    private String getDisplayName(int type, long mailboxId) {
        String name = getCombinedMailboxName(mailboxId);

        if ((name == null) && (type < mSpecialMailbox.length)) {
            name = mSpecialMailbox[type];
        }
        return name;
    }

    /**
     * Return the display name for a mailbox for UI.  For normal mailboxes, it just returns
     * {@code originalDisplayName}, but for special mailboxes (such as combined mailboxes) it
     * returns a name obtained from the resource.
     *
     * @param mailboxType Such as {@link Mailbox#TYPE_INBOX}
     * @param mailboxId ID of a mailbox.
     * @param originalDisplayName Display name of the mailbox stored in the database.
     */
    public String getDisplayName(int mailboxType, long mailboxId, String originalDisplayName) {
        String name = getDisplayName(mailboxType, mailboxId);
        if (name != null) {
            return name;
        }
        return originalDisplayName;
    }

    /**
     * Same as {@link #getDisplayName(int, long, String)}, but gets information form a mailbox
     * cursor.  The cursor must contain the following columns:
     * {@link MailboxColumns#ID}, {@link MailboxColumns#TYPE} and
     * {@link MailboxColumns#DISPLAY_NAME}.
     */
    public String getDisplayName(Cursor mailboxCursor) {
        final Cursor c = mailboxCursor;
        return getDisplayName(
                c.getInt(c.getColumnIndex(MailboxColumns.TYPE)),
                c.getLong(c.getColumnIndex(MailboxColumns.ID)),
                c.getString(c.getColumnIndex(MailboxColumns.DISPLAY_NAME))
                );
    }

    public String getDisplayName(Mailbox mailbox) {
        return getDisplayName(mailbox.mType, mailbox.mId, mailbox.mDisplayName);
    }

    /**
     * Return the message count which should be shown with a mailbox name.  Depending on
     * the mailbox type, we change what to show.
     *
     * @param mailboxType Such as {@link Mailbox#TYPE_INBOX}
     * @param unreadCount Count obtained from {@link MailboxColumns#UNREAD_COUNT}
     * @param totalCount Count obtained from {@link MailboxColumns#MESSAGE_COUNT}
     */
    public int getMessageCount(int mailboxType, int unreadCount, int totalCount) {
        switch (mailboxType) {
            case Mailbox.TYPE_DRAFTS:
            case Mailbox.TYPE_OUTBOX:
                return totalCount;
            case Mailbox.TYPE_SENT:
            case Mailbox.TYPE_TRASH:
                return 0; // We don't show a count for sent/trash.
        }
        return unreadCount;
    }

    /**
     * Same as {@link #getMessageCount(int, int, int)}, but gets information form a mailbox
     * cursor.  The cursor must contain the following columns:
     * {@link MailboxColumns#TYPE}, {@link MailboxColumns#UNREAD_COUNT} and
     * {@link MailboxColumns#MESSAGE_COUNT}.
     */
    public int getMessageCount(Cursor mailboxCursor) {
        final Cursor c = mailboxCursor;
        return getMessageCount(
                c.getInt(c.getColumnIndex(MailboxColumns.TYPE)),
                c.getInt(c.getColumnIndex(MailboxColumns.UNREAD_COUNT)),
                c.getInt(c.getColumnIndex(MailboxColumns.MESSAGE_COUNT))
                );
    }

    /**
     * @return message count to show for the UI for a combined inbox.
     *
     * Note this method doesn't use mContext so we can inject a mock context for provider
     * access.  So it's static.
     */
    public static int getMessageCountForCombinedMailbox(Context context, long mailboxId) {
        Preconditions.checkState(mailboxId < -1L);
        if ((mailboxId == Mailbox.QUERY_ALL_INBOXES)
                || (mailboxId == Mailbox.QUERY_ALL_UNREAD)) {
            return Mailbox.getUnreadCountByMailboxType(context, Mailbox.TYPE_INBOX);

        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            return Message.getFavoriteMessageCount(context);

        } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            return Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_DRAFTS);

        } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            return Mailbox.getMessageCountByMailboxType(context, Mailbox.TYPE_OUTBOX);
        }
        throw new IllegalStateException("Invalid mailbox ID");
    }

    /**
     * Lookup icons of special mailboxes
     */
    public Drawable getIcon(int type, long mailboxId, int mailboxFlags) {
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            return mSummaryCombinedInboxDrawable;
        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            return mSummaryStarredMailboxDrawable;
        } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_DRAFTS);
        } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_OUTBOX);
        }
        if (0 <= type && type < mSpecialMailboxDrawable.length()) {
            final int resId = mSpecialMailboxDrawable.getResourceId(type, -1);
            if (resId != -1) {
                return mContext.getResources().getDrawable(resId);
            }
        }
        return null; // No icon
    }
}

