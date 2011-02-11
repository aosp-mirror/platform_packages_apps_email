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

import com.android.email.R;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.AsyncTaskLoader;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;

import java.security.InvalidParameterException;

/**
 * "Move (messages) to" dialog.
 *
 * TODO The check logic in MessageCheckerCallback is not efficient.  It shouldn't restore full
 * Message objects.  But we don't bother at this point as the UI is still temporary.
 */
public class MoveMessageToDialog extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String BUNDLE_MESSAGE_IDS = "message_ids";

    /** Message IDs passed to {@link #newInstance} */
    private long[] mMessageIds;
    private MailboxesAdapter mAdapter;

    /** Account ID is restored by {@link MailboxesLoaderCallbacks} */
    private long mAccountId;

    private boolean mDestroyed;

    /**
     * Callback that target fragments, or the owner activity should implement.
     */
    public interface Callback {
        public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds);
    }

    /**
     * Create and return a new instance.
     *
     * @param parent owner activity.
     * @param messageIds IDs of the messages to be moved.
     * @param callbackFragment Fragment that gets a callback.  The fragment must implement
     * {@link Callback}.  If null is passed, then the owner activity is used instead, in which case
     * it must implement {@link Callback} instead.
     */
    public static MoveMessageToDialog newInstance(Activity parent,
            long[] messageIds, Fragment callbackFragment) {
        if (messageIds.length == 0) {
            throw new InvalidParameterException();
        }
        MoveMessageToDialog dialog = new MoveMessageToDialog();
        Bundle args = new Bundle();
        args.putLongArray(BUNDLE_MESSAGE_IDS, messageIds);
        dialog.setArguments(args);
        if (callbackFragment != null) {
            dialog.setTargetFragment(callbackFragment, 0);
        }
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mMessageIds = getArguments().getLongArray(BUNDLE_MESSAGE_IDS);
        setStyle(STYLE_NORMAL, android.R.style.Theme_Holo_Light);
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        super.onDestroy();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();

        // Build adapter & dialog
        // Make sure to pass Builder's context to the adapter, so that it'll get the correct theme.
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(activity.getResources().getString(R.string.move_to_folder_dialog_title));

        mAdapter =
            new MailboxesAdapter(builder.getContext(), MailboxesAdapter.MODE_MOVE_TO_TARGET,
                    new MailboxesAdapter.EmptyCallback());
        builder.setSingleChoiceItems(mAdapter, -1, this);

        getLoaderManager().initLoader(
                ActivityHelper.GLOBAL_LOADER_ID_MOVE_TO_DIALOG_MESSAGE_CHECKER,
                null, new MessageCheckerCallback());

        return builder.show();
    }

    @Override
    public void onClick(DialogInterface dialog, int position) {
        final long mailboxId = mAdapter.getItemId(position);

        getCallback().onMoveToMailboxSelected(mailboxId, mMessageIds);
        dismiss();
    }

    private Callback getCallback() {
        Fragment targetFragment = getTargetFragment();
        if (targetFragment != null) {
            // If a target is set, it MUST implement Callback.
            return (Callback) targetFragment;
        }
        // If not the parent activity MUST implement Callback.
        return (Callback) getActivity();
    }

    /**
     * Delay-call {@link #dismiss()} using a {@link Handler}.  Calling {@link #dismiss()} from
     * {@link LoaderManager.LoaderCallbacks#onLoadFinished} is not allowed, so we use it instead.
     */
    private void dismissAsync() {
        new Handler().post(new Runnable() {
            @Override
            public void run() {
                if (!mDestroyed) {
                    dismiss();
                }
            }
        });
    }

    /**
     * Loader callback for {@link MessageChecker}
     */
    private class MessageCheckerCallback implements LoaderManager.LoaderCallbacks<Long> {
        @Override
        public Loader<Long> onCreateLoader(int id, Bundle args) {
            return new MessageChecker(getActivity(), mMessageIds);
        }

        @Override
        public void onLoadFinished(Loader<Long> loader, Long accountId) {
            if (mDestroyed) {
                return;
            }
            // accountId shouldn't be null, but I'm paranoia.
            if ((accountId == null) || (accountId == -1)) {
                // Some of the messages can't be moved.  Close the dialog.
                dismissAsync();
                return;
            }
            mAccountId = accountId;
            getLoaderManager().initLoader(
                    ActivityHelper.GLOBAL_LOADER_ID_MOVE_TO_DIALOG_MAILBOX_LOADER,
                    null, new MailboxesLoaderCallbacks());
        }

        @Override
        public void onLoaderReset(Loader<Long> loader) {
        }
    }

    /**
     * Loader callback for destination mailbox list.
     */
    private class MailboxesLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return MailboxesAdapter.createLoader(getActivity().getApplicationContext(), mAccountId,
                    MailboxesAdapter.MODE_MOVE_TO_TARGET);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            if (mDestroyed) {
                return;
            }
            mAdapter.swapCursor(data);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mAdapter.swapCursor(null);
        }
    }

    /**
     * A loader that checks if the messages can be moved, and return the Id of the account that owns
     * the messages.  (If any of the messages can't be moved, return -1.)
     */
    private static class MessageChecker extends AsyncTaskLoader<Long> {
        private final Activity mActivity;
        private final long[] mMessageIds;

        public MessageChecker(Activity activity, long[] messageIds) {
            super(activity);
            mActivity = activity;
            mMessageIds = messageIds;
        }

        @Override
        public Long loadInBackground() {
            final Context c = getContext();

            long accountId = -1; // -1 == account not found yet.

            for (long messageId : mMessageIds) {
                // TODO This shouln't restore a full Message object.
                final Message message = Message.restoreMessageWithId(c, messageId);
                if (message == null) {
                    continue; // Skip removed messages.
                }

                // First, check account.
                if (accountId == -1) {
                    // First message -- see if the account supports move.
                    accountId = message.mAccountKey;
                    if (!Account.supportsMoveMessages(c, accountId)) {
                        Utility.showToast(
                                mActivity, R.string.cannot_move_protocol_not_supported_toast);
                        return -1L;
                    }
                } else {
                    // Following messages -- have to belong to the same account
                    if (message.mAccountKey != accountId) {
                        Utility.showToast(mActivity, R.string.cannot_move_multiple_accounts_toast);
                        return -1L;
                    }
                }
                // Second, check mailbox.
                if (!Mailbox.canMoveFrom(c, message.mMailboxKey)) {
                    Utility.showToast(mActivity, R.string.cannot_move_special_mailboxes_toast);
                    return -1L;
                }
            }

            // If all messages have been removed, accountId remains -1, which is what we should
            // return here.
            return accountId;
        }

        @Override
        protected void onStartLoading() {
            cancelLoad();
            forceLoad();
        }

        @Override
        protected void onStopLoading() {
            cancelLoad();
        }

        @Override
        protected void onReset() {
            stopLoading();
        }
    }
}
