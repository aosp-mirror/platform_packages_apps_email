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

import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.email.activity.EmailActivity;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SearchParams;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * Information about what is being shown in a message list.
 * This encapsulates the meta-data about the list of messages, which can either be the
 * {@link Mailbox} ID, or {@link SearchParams}.
 */
public class MessageListContext implements Parcelable {

    /**
     * The active account. Changing an account is a destructive enough operation that it warrants
     * the creation of a new {@link MessageListContext}
     */
    public final long mAccountId;

    /**
     * The mailbox ID containing the messages. Must not be {@link Mailbox#NO_MAILBOX}.
     */
    private final long mMailboxId;

    /**
     * The search parameters, if the user is in a search.
     * If non-null, {@link #mMailboxId} will always correspond to the search mailbox for the user.
     */
    private final SearchParams mSearchParams;

    // Private constructor - use static builder methods to generate a validated instance.
    private MessageListContext(long accountId, long searchMailboxId, SearchParams searchParams) {
        mAccountId = accountId;
        mMailboxId = searchMailboxId;
        mSearchParams = searchParams;
    }

    /**
     * Builds an instance from the information provided in an Intent.
     * This method will perform proper validation and throw an {@link IllegalArgumentException}
     * if values in the {@link Intent} are inconsistent.
     * This will also handle the generation of default values if certain fields are unspecified
     * in the {@link Intent}.
     */
    public static MessageListContext forIntent(Context context, Intent intent) {
        long accountId = intent.getLongExtra(EmailActivity.EXTRA_ACCOUNT_ID, Account.NO_ACCOUNT);
        long mailboxId = intent.getLongExtra(EmailActivity.EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);

        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            final String queryTerm = intent.getStringExtra(EmailActivity.EXTRA_QUERY_STRING);
            final long searchMailboxId =
                    Controller.getInstance(context).getSearchMailbox(accountId).mId;
            return forSearch(accountId, searchMailboxId, new SearchParams(mailboxId, queryTerm));
        } else {
            if (accountId == Account.NO_ACCOUNT) {
                accountId = Account.getDefaultAccountId(context);
            }
            if (mailboxId == Mailbox.NO_MAILBOX) {
                mailboxId = (accountId == Account.ACCOUNT_ID_COMBINED_VIEW)
                        ? Mailbox.QUERY_ALL_INBOXES
                        : Mailbox.findMailboxOfType(context, accountId, Mailbox.TYPE_INBOX);
            }

            return forMailbox(accountId, mailboxId);
        }
    }

    /**
     * Creates a view context for a given search.
     */
    public static MessageListContext forSearch(
            long accountId, long searchMailboxId, SearchParams searchParams) {
        Preconditions.checkArgument(
                Account.isNormalAccount(accountId),
                "Can only search in normal accounts");
        return new MessageListContext(accountId, searchMailboxId, searchParams);
    }

    /**
     * Creates a view context for a given mailbox.
     */
    public static MessageListContext forMailbox(long accountId, long mailboxId) {
        Preconditions.checkArgument(accountId != Account.NO_ACCOUNT, "Must specify an account");
        Preconditions.checkArgument(mailboxId != Mailbox.NO_MAILBOX, "Must specify a mailbox");
        return new MessageListContext(accountId, mailboxId, null);
    }

    public boolean isSearch() {
        return mSearchParams != null;
    }

    public long getSearchedMailbox() {
        return isSearch() ? mSearchParams.mMailboxId : Mailbox.NO_MAILBOX;
    }

    public SearchParams getSearchParams() {
        return mSearchParams;
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if ((o == null) || !(o instanceof MessageListContext)) {
            return false;
        }

        MessageListContext om = (MessageListContext) o;
        return mAccountId == om.mAccountId
                && mMailboxId == om.mMailboxId
                && Objects.equal(mSearchParams, om.mSearchParams);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mAccountId, mMailboxId, mSearchParams);
    }

    @Override
    public String toString() {
        return "[MessageListContext " + mAccountId + ":" + mMailboxId + ":" + mSearchParams + "]";
    }


    private MessageListContext(Parcel in) {
        mAccountId = in.readLong();
        mMailboxId = in.readLong();
        mSearchParams = in.readParcelable(SearchParams.class.getClassLoader());
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mAccountId);
        dest.writeLong(mMailboxId);
        dest.writeParcelable(mSearchParams, flags);
    }

    public static Parcelable.Creator<MessageListContext> CREATOR =
                new Parcelable.Creator<MessageListContext>() {
        @Override
        public MessageListContext createFromParcel(Parcel source) {
            return new MessageListContext(source);
        }

        @Override
        public MessageListContext[] newArray(int size) {
            return new MessageListContext[size];
        }
    };
}
