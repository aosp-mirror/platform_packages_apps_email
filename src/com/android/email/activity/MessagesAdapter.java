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

package com.android.email.activity;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.MessageListContext;
import com.android.email.ResourceHelper;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.TextUtilities;
import com.android.emailcommon.utility.Utility;
import com.google.common.base.Preconditions;

import java.util.HashSet;
import java.util.Set;


/**
 * This class implements the adapter for displaying messages based on cursors.
 */
/* package */ class MessagesAdapter extends CursorAdapter {
    private static final String STATE_CHECKED_ITEMS =
            "com.android.email.activity.MessagesAdapter.checkedItems";

    /* package */ static final String[] MESSAGE_PROJECTION = new String[] {
        EmailContent.RECORD_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
        MessageColumns.DISPLAY_NAME, MessageColumns.SUBJECT, MessageColumns.TIMESTAMP,
        MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_ATTACHMENT,
        MessageColumns.FLAGS, MessageColumns.SNIPPET
    };

    public static final int COLUMN_ID = 0;
    public static final int COLUMN_MAILBOX_KEY = 1;
    public static final int COLUMN_ACCOUNT_KEY = 2;
    public static final int COLUMN_DISPLAY_NAME = 3;
    public static final int COLUMN_SUBJECT = 4;
    public static final int COLUMN_DATE = 5;
    public static final int COLUMN_READ = 6;
    public static final int COLUMN_FAVORITE = 7;
    public static final int COLUMN_ATTACHMENTS = 8;
    public static final int COLUMN_FLAGS = 9;
    public static final int COLUMN_SNIPPET = 10;

    private final ResourceHelper mResourceHelper;

    /** If true, show color chips. */
    private boolean mShowColorChips;

    /** If not null, the query represented by this group of messages */
    private String mQuery;

    /**
     * Set of seleced message IDs.
     */
    private final HashSet<Long> mSelectedSet = new HashSet<Long>();

    /**
     * Callback from MessageListAdapter.  All methods are called on the UI thread.
     */
    public interface Callback {
        /** Called when the use starts/unstars a message */
        void onAdapterFavoriteChanged(MessageListItem itemView, boolean newFavorite);
        /** Called when the user selects/unselects a message */
        void onAdapterSelectedChanged(MessageListItem itemView, boolean newSelected,
                int mSelectedCount);
    }

    private final Callback mCallback;

    /**
     * The actual return type from the loader.
     */
    public static class MessagesCursor extends CursorWrapper {
        /**  Whether the mailbox is found. */
        public final boolean mIsFound;
        /** {@link Account} that owns the mailbox.  Null for combined mailboxes. */
        public final Account mAccount;
        /** {@link Mailbox} for the loaded mailbox. Null for combined mailboxes. */
        public final Mailbox mMailbox;
        /** {@code true} if the account is an EAS account */
        public final boolean mIsEasAccount;
        /** {@code true} if the loaded mailbox can be refreshed. */
        public final boolean mIsRefreshable;
        /** the number of accounts currently configured. */
        public final int mCountTotalAccounts;

        private MessagesCursor(Cursor cursor,
                boolean found, Account account, Mailbox mailbox, boolean isEasAccount,
                boolean isRefreshable, int countTotalAccounts) {
            super(cursor);
            mIsFound = found;
            mAccount = account;
            mMailbox = mailbox;
            mIsEasAccount = isEasAccount;
            mIsRefreshable = isRefreshable;
            mCountTotalAccounts = countTotalAccounts;
        }
    }

    public MessagesAdapter(Context context, Callback callback) {
        super(context.getApplicationContext(), null, 0 /* no auto requery */);
        mResourceHelper = ResourceHelper.getInstance(context);
        mCallback = callback;
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putLongArray(STATE_CHECKED_ITEMS, Utility.toPrimitiveLongArray(getSelectedSet()));
    }

    public void loadState(Bundle savedInstanceState) {
        Set<Long> checkedset = getSelectedSet();
        checkedset.clear();
        for (long l: savedInstanceState.getLongArray(STATE_CHECKED_ITEMS)) {
            checkedset.add(l);
        }
        notifyDataSetChanged();
    }

    /**
     * Set true for combined mailboxes.
     */
    public void setShowColorChips(boolean show) {
        mShowColorChips = show;
    }

    public void setQuery(String query) {
        mQuery = query;
    }

    public Set<Long> getSelectedSet() {
        return mSelectedSet;
    }

    /**
     * Clear the selection.  It's preferable to calling {@link Set#clear()} on
     * {@link #getSelectedSet()}, because it also notifies observers.
     */
    public void clearSelection() {
        Set<Long> checkedset = getSelectedSet();
        if (checkedset.size() > 0) {
            checkedset.clear();
            notifyDataSetChanged();
        }
    }

    public boolean isSelected(MessageListItem itemView) {
        return getSelectedSet().contains(itemView.mMessageId);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        // Reset the view (in case it was recycled) and prepare for binding
        MessageListItem itemView = (MessageListItem) view;
        itemView.bindViewInit(this);

        // TODO: just move thise all to a MessageListItem.bindTo(cursor) so that the fields can
        // be private, and their inter-dependence when they change can be abstracted away.

        // Load the public fields in the view (for later use)
        itemView.mMessageId = cursor.getLong(COLUMN_ID);
        itemView.mMailboxId = cursor.getLong(COLUMN_MAILBOX_KEY);
        final long accountId = cursor.getLong(COLUMN_ACCOUNT_KEY);
        itemView.mAccountId = accountId;

        boolean isRead = cursor.getInt(COLUMN_READ) != 0;
        boolean readChanged = isRead != itemView.mRead;
        itemView.mRead = isRead;
        itemView.mIsFavorite = cursor.getInt(COLUMN_FAVORITE) != 0;
        final int flags = cursor.getInt(COLUMN_FLAGS);
        itemView.mHasInvite = (flags & Message.FLAG_INCOMING_MEETING_INVITE) != 0;
        itemView.mHasBeenRepliedTo = (flags & Message.FLAG_REPLIED_TO) != 0;
        itemView.mHasBeenForwarded = (flags & Message.FLAG_FORWARDED) != 0;
        itemView.mHasAttachment = cursor.getInt(COLUMN_ATTACHMENTS) != 0;
        itemView.setTimestamp(cursor.getLong(COLUMN_DATE));
        itemView.mSender = cursor.getString(COLUMN_DISPLAY_NAME);
        itemView.setText(
                cursor.getString(COLUMN_SUBJECT), cursor.getString(COLUMN_SNIPPET), readChanged);
        itemView.mColorChipPaint =
            mShowColorChips ? mResourceHelper.getAccountColorPaint(accountId) : null;

        if (mQuery != null && itemView.mSnippet != null) {
            itemView.mSnippet =
                TextUtilities.highlightTermsInText(cursor.getString(COLUMN_SNIPPET), mQuery);
        }
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        MessageListItem item = new MessageListItem(context);
        item.setVisibility(View.VISIBLE);
        return item;
    }

    public void toggleSelected(MessageListItem itemView) {
        updateSelected(itemView, !isSelected(itemView));
    }

    /**
     * This is used as a callback from the list items, to set the selected state
     *
     * <p>Must be called on the UI thread.
     *
     * @param itemView the item being changed
     * @param newSelected the new value of the selected flag (checkbox state)
     */
    private void updateSelected(MessageListItem itemView, boolean newSelected) {
        if (newSelected) {
            mSelectedSet.add(itemView.mMessageId);
        } else {
            mSelectedSet.remove(itemView.mMessageId);
        }
        if (mCallback != null) {
            mCallback.onAdapterSelectedChanged(itemView, newSelected, mSelectedSet.size());
        }
    }

    /**
     * This is used as a callback from the list items, to set the favorite state
     *
     * <p>Must be called on the UI thread.
     *
     * @param itemView the item being changed
     * @param newFavorite the new value of the favorite flag (star state)
     */
    public void updateFavorite(MessageListItem itemView, boolean newFavorite) {
        changeFavoriteIcon(itemView, newFavorite);
        if (mCallback != null) {
            mCallback.onAdapterFavoriteChanged(itemView, newFavorite);
        }
    }

    private void changeFavoriteIcon(MessageListItem view, boolean isFavorite) {
        view.invalidate();
    }

    /**
     * Creates the loader for {@link MessageListFragment}.
     *
     * @return always of {@link MessagesCursor}.
     */
    public static Loader<Cursor> createLoader(Context context, MessageListContext listContext) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MessagesAdapter createLoader listContext=" + listContext);
        }
        return listContext.isSearch()
                ? new SearchCursorLoader(context, listContext)
                : new MessagesCursorLoader(context, listContext.getMailboxId());
    }

    private static class MessagesCursorLoader extends ThrottlingCursorLoader {
        protected final Context mContext;
        private final long mMailboxId;

        public MessagesCursorLoader(Context context, long mailboxId) {
            // Initialize with no where clause.  We'll set it later.
            super(context, EmailContent.Message.CONTENT_URI,
                    MESSAGE_PROJECTION, null, null,
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
            mContext = context;
            mMailboxId = mailboxId;
        }

        @Override
        public Cursor loadInBackground() {
            // Build the where cause (which can't be done on the UI thread.)
            setSelection(Message.buildMessageListSelection(mContext, mMailboxId));
            // Then do a query to get the cursor
            return loadExtras(super.loadInBackground());
        }

        private Cursor loadExtras(Cursor baseCursor) {
            boolean found = false;
            Account account = null;
            Mailbox mailbox = null;
            boolean isEasAccount = false;
            boolean isRefreshable = false;

            if (mMailboxId < 0) {
                // Magic mailbox.
                found = true;
            } else {
                mailbox = Mailbox.restoreMailboxWithId(mContext, mMailboxId);
                if (mailbox != null) {
                    account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
                    if (account != null) {
                        found = true;
                        isEasAccount = account.isEasAccount(mContext) ;
                        isRefreshable = Mailbox.isRefreshable(mContext, mMailboxId);
                    } else { // Account removed?
                        mailbox = null;
                    }
                }
            }
            final int countAccounts = EmailContent.count(mContext, Account.CONTENT_URI);
            return wrapCursor(baseCursor, found, account, mailbox, isEasAccount,
                    isRefreshable, countAccounts);
        }

        /**
         * Wraps a basic cursor containing raw messages with information about the context of
         * the list that's being loaded, such as the account and the mailbox the messages
         * are for.
         * Subclasses may extend this to wrap with additional data.
         */
        protected Cursor wrapCursor(Cursor cursor,
                boolean found, Account account, Mailbox mailbox, boolean isEasAccount,
                boolean isRefreshable, int countTotalAccounts) {
            return new MessagesCursor(cursor, found, account, mailbox, isEasAccount,
                    isRefreshable, countTotalAccounts);
        }
    }

    public static class SearchResultsCursor extends MessagesCursor {
        private final Mailbox mSearchedMailbox;
        private final int mResultsCount;
        private SearchResultsCursor(Cursor cursor,
                boolean found, Account account, Mailbox mailbox, boolean isEasAccount,
                boolean isRefreshable, int countTotalAccounts,
                Mailbox searchedMailbox, int resultsCount) {
            super(cursor, found, account, mailbox, isEasAccount,
                    isRefreshable, countTotalAccounts);
            mSearchedMailbox = searchedMailbox;
            mResultsCount = resultsCount;
        }

        /**
         * @return the total number of results that match the given search query. Note that
         *     there may not be that many items loaded in the cursor yet.
         */
        public int getResultsCount() {
            return mResultsCount;
        }

        public Mailbox getSearchedMailbox() {
            return mSearchedMailbox;
        }
    }

    /**
     * A special loader used to perform a search.
     */
    private static class SearchCursorLoader extends MessagesCursorLoader {
        private final MessageListContext mListContext;
        private int mResultsCount = -1;
        private Mailbox mSearchedMailbox = null;

        public SearchCursorLoader(Context context, MessageListContext listContext) {
            super(context, listContext.getMailboxId());
            Preconditions.checkArgument(listContext.isSearch());
            mListContext = listContext;
        }

        @Override
        public Cursor loadInBackground() {
            if (mResultsCount >= 0) {
                // Result count known - the initial search meta data must have completed.
                return super.loadInBackground();
            }

            if (mSearchedMailbox == null) {
                mSearchedMailbox = Mailbox.restoreMailboxWithId(
                        mContext, mListContext.getSearchedMailbox());
            }

            // The search results info hasn't even been loaded yet, so the Controller has not yet
            // initialized the search mailbox properly. Kick off the search first.
            Controller controller = Controller.getInstance(mContext);
            try {
                mResultsCount = controller.searchMessages(
                        mListContext.mAccountId, mListContext.getSearchParams());
            } catch (MessagingException e) {
            }

            // Return whatever the super would do, now that we know the results are ready.
            // After this point, it should behave as a normal mailbox load for messages.
            return super.loadInBackground();
        }

        @Override
        protected Cursor wrapCursor(Cursor cursor,
                boolean found, Account account, Mailbox mailbox, boolean isEasAccount,
                boolean isRefreshable, int countTotalAccounts) {
            return new SearchResultsCursor(cursor, found, account, mailbox, isEasAccount,
                    isRefreshable, countTotalAccounts, mSearchedMailbox, mResultsCount);
        }
    }
}
