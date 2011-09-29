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
import android.util.Log;

import com.android.email.Email;
import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

/**
 * "Move (messages) to" dialog. This is a modal dialog and the design is such so that only one is
 * active. If a new instance is created while an existing one is active, the existing one is
 * dismissed.
 *
 * TODO The check logic in MessageCheckerCallback is not efficient.  It shouldn't restore full
 * Message objects.
 */
public class MoveMessageToDialog extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String BUNDLE_MESSAGE_IDS = "message_ids";

    private static final int LOADER_ID_MOVE_TO_DIALOG_MAILBOX_LOADER = 1;
    private static final int LOADER_ID_MOVE_TO_DIALOG_MESSAGE_CHECKER = 2;

    /** Message IDs passed to {@link #newInstance} */
    private long[] mMessageIds;
    private MailboxMoveToAdapter mAdapter;

    /** ID of the account that contains all of the messages to move */
    private long mAccountId;
    /** ID of the mailbox that contains all of the messages to move */
    private long mMailboxId;

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
     * @param messageIds IDs of the messages to be moved.
     * @param callbackFragment Fragment that gets a callback.  The fragment must implement
     *     {@link Callback}.
     */
    public static <T extends Fragment & Callback> MoveMessageToDialog newInstance(long[] messageIds,
            T callbackFragment) {
        if (messageIds.length == 0) {
            throw new IllegalArgumentException();
        }
        if (callbackFragment == null) {
            throw new IllegalArgumentException(); // fail fast
        }
        MoveMessageToDialog dialog = new MoveMessageToDialog();
        Bundle args = new Bundle();
        args.putLongArray(BUNDLE_MESSAGE_IDS, messageIds);
        dialog.setArguments(args);
        dialog.setTargetFragment(callbackFragment, 0);
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "" + this + " onCreate  target=" + getTargetFragment());
        }
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

        mAdapter = new MailboxMoveToAdapter(builder.getContext());
        builder.setSingleChoiceItems(mAdapter, -1, this);

        getLoaderManager().initLoader(
                LOADER_ID_MOVE_TO_DIALOG_MESSAGE_CHECKER,
                null, new MessageCheckerCallback());

        return builder.create();
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mAdapter.getCursor() == null) {
            // Data isn't ready - don't show yet.
            getDialog().hide();
        }
    }

    /**
     * The active move message dialog. This dialog is fairly modal so it only makes sense to have
     * one instance active, and for debounce purposes, we dismiss any existing ones.
     *
     * Only touched on the UI thread so doesn't require synchronization.
     */
    static MoveMessageToDialog sActiveDialog;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        if (sActiveDialog != null) {
            // Something is already attached. Dismiss it!
            sActiveDialog.dismissAsync();
        }

        sActiveDialog = this;
    }

    @Override
    public void onDetach() {
        super.onDetach();

        if (sActiveDialog == this) {
            sActiveDialog = null;
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int position) {
        final long mailboxId = mAdapter.getItemId(position);

        ((Callback) getTargetFragment()).onMoveToMailboxSelected(mailboxId, mMessageIds);
        dismiss();
    }

    /**
     * Delay-call {@link #dismissAllowingStateLoss()} using a {@link Handler}.  Calling
     * {@link #dismissAllowingStateLoss()} from {@link LoaderManager.LoaderCallbacks#onLoadFinished}
     * is not allowed, so we use it instead.
     */
    private void dismissAsync() {
        Utility.getMainThreadHandler().post(new Runnable() {
            @Override
            public void run() {
                if (!mDestroyed) {
                    dismissAllowingStateLoss();
                }
            }
        });
    }

    /**
     * Loader callback for {@link MessageChecker}
     */
    private class MessageCheckerCallback implements LoaderManager.LoaderCallbacks<IdContainer> {
        @Override
        public Loader<IdContainer> onCreateLoader(int id, Bundle args) {
            return new MessageChecker(getActivity(), mMessageIds);
        }

        @Override
        public void onLoadFinished(Loader<IdContainer> loader, IdContainer idSet) {
            if (mDestroyed) {
                return;
            }
            // accountId shouldn't be null, but I'm paranoia.
            if (idSet == null || idSet.mAccountId == Account.NO_ACCOUNT
                    || idSet.mMailboxId == Mailbox.NO_MAILBOX) {
                // Some of the messages can't be moved.  Close the dialog.
                dismissAsync();
                return;
            }
            mAccountId = idSet.mAccountId;
            mMailboxId = idSet.mMailboxId;
            getLoaderManager().initLoader(
                    LOADER_ID_MOVE_TO_DIALOG_MAILBOX_LOADER,
                    null, new MailboxesLoaderCallbacks());
        }

        @Override
        public void onLoaderReset(Loader<IdContainer> loader) {
        }
    }

    /**
     * Loader callback for destination mailbox list.
     */
    private class MailboxesLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return MailboxMoveToAdapter.createLoader(getActivity().getApplicationContext(),
                    mAccountId, mMailboxId);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            if (mDestroyed) {
                return;
            }
            boolean needsShowing = (mAdapter.getCursor() == null);
            mAdapter.swapCursor(data);

            // The first time data is loaded, we need to show the dialog.
            if (needsShowing && isAdded()) {
                getDialog().show();
            }
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mAdapter.swapCursor(null);
        }
    }

    /**
     * A loader that checks if the messages can be moved. If messages can be moved, it returns
     * the account and mailbox IDs where the messages are currently located. If any the messages
     * cannot be moved (such as the messages belong to different accounts), the IDs returned
     * will be {@link Account#NO_ACCOUNT} and {@link Mailbox#NO_MAILBOX}.
     */
    private static class MessageChecker extends AsyncTaskLoader<IdContainer> {
        private final Activity mActivity;
        private final long[] mMessageIds;

        public MessageChecker(Activity activity, long[] messageIds) {
            super(activity);
            mActivity = activity;
            mMessageIds = messageIds;
        }

        @Override
        public IdContainer loadInBackground() {
            final Context c = getContext();

            long accountId = Account.NO_ACCOUNT;
            long mailboxId = Mailbox.NO_MAILBOX;

            for (long messageId : mMessageIds) {
                // TODO This shouln't restore a full Message object.
                final Message message = Message.restoreMessageWithId(c, messageId);
                if (message == null) {
                    continue; // Skip removed messages.
                }

                // First, check account.
                if (accountId == Account.NO_ACCOUNT) {
                    // First, check if the account supports move
                    accountId = message.mAccountKey;
                    if (!Account.restoreAccountWithId(c, accountId).supportsMoveMessages(c)) {
                        Utility.showToast(
                                mActivity, R.string.cannot_move_protocol_not_supported_toast);
                        accountId = Account.NO_ACCOUNT;
                        break;
                    }
                    mailboxId = message.mMailboxKey;
                    // Second, check if the mailbox supports move
                    if (!Mailbox.restoreMailboxWithId(c, mailboxId).canHaveMessagesMoved()) {
                        Utility.showToast(mActivity, R.string.cannot_move_special_mailboxes_toast);
                        accountId = Account.NO_ACCOUNT;
                        mailboxId = Mailbox.NO_MAILBOX;
                        break;
                    }
                } else {
                    // Subsequent messages; all messages must to belong to the same mailbox
                    if (message.mAccountKey != accountId || message.mMailboxKey != mailboxId) {
                        Utility.showToast(mActivity, R.string.cannot_move_multiple_accounts_toast);
                        accountId = Account.NO_ACCOUNT;
                        mailboxId = Mailbox.NO_MAILBOX;
                        break;
                    }
                }
            }
            return new IdContainer(accountId, mailboxId);
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

    /** Container for multiple types of IDs */
    private static class IdContainer {
        private final long mAccountId;
        private final long mMailboxId;

        private IdContainer(long accountId, long mailboxId) {
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }
    }
}
