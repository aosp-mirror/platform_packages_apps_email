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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.provider.EmailContent.Mailbox;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.security.InvalidParameterException;
import java.util.ArrayList;

/*
  TODO: When opening a mailbox I see this:
D Email   : com.android.email.activity.MailboxListFragment openMailboxes
D Email   : com.android.email.activity.MailboxListFragment onCreate *1 <- Why second instance???
D Email   : com.android.email.activity.MailboxListFragment onActivityCreated
D Email   : com.android.email.activity.MailboxListFragment onStart
D Email   : com.android.email.activity.MailboxListFragment onResume
 */

/**
 * A class manages what are showing on {@link MessageListXL} (i.e. account id, mailbox id, and
 * message id), and show/hide fragments accordingly.
 *
 * TODO: Test it.  It's testable if we implement MockFragmentTransaction, which may be too early
 * to do so at this point.  (API may not be stable enough yet.)
 *
 * TODO: See if the "restored fragments" hack can be removed if the fragments restore their
 * state by themselves.  (That'll require phone activity changes as well.)
 */
class MessageListXLFragmentManager {
    private static final String BUNDLE_KEY_ACCOUNT_ID = "MessageListXl.state.account_id";
    private static final String BUNDLE_KEY_MAILBOX_ID = "MessageListXl.state.mailbox_id";
    private static final String BUNDLE_KEY_MESSAGE_ID = "MessageListXl.state.message_id";
    private static final String BUNDLE_KEY_MESSAGE_LIST_STATE
            = "MessageListXl.state.message_list_state";

    private final Context mContext;

    /**
     * List of fragments that are restored by the framework when the activity is being re-created.
     * (e.g. for orientation change)
     */
    private final ArrayList<Fragment> mRestoredFragments = new ArrayList<Fragment>();

    private boolean mIsActivityStarted;

    /** Current account id. (-1 = not selected) */
    private long mAccountId = -1;

    /** Current mailbox id. (-1 = not selected) */
    private long mMailboxId = -1;

    /** Current message id. (-1 = not selected) */
    private long mMessageId = -1;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private MailboxListFragment.Callback mMailboxListFragmentCallback;
    private MessageListFragment.Callback mMessageListFragmentCallback;
    private MessageViewFragment.Callback mMessageViewFragmentCallback;

    private MailboxFinder mMailboxFinder;
    private final MailboxFinderCallback mMailboxFinderCallback = new MailboxFinderCallback();

    /**
     * Save state for the "message list -> message view -[back press]-> message list" transition.
     */
    private MessageListFragment.State mMessageListFragmentState;

    /**
     * The interface that {@link MessageListXL} implements.  We don't call its methods directly,
     * in the hope that it'll make writing tests easier, and make it clear which methods are needed
     * for MessageListXLFragmentManager.
     */
    public interface TargetActivity {
        public FragmentManager getFragmentManager();
        /**
         * Called when MessageViewFragment is being shown.
         * {@link MessageListXL} uses it to show the navigation buttons.
         */
        public void onMessageViewFragmentShown(long accountId, long mailboxId, long messageId);
        /**
         * Called when MessageViewFragment is being hidden.
         * {@link MessageListXL} uses it to hide the navigation buttons.
         */
        public void onMessageViewFragmentHidden();

        /**
         * Called when the selected account is on security-hold.
         */
        public void onAccountSecurityHold();
    }

    private final TargetActivity mTargetActivity;
    private final FragmentManager mFragmentManager;

    public MessageListXLFragmentManager(MessageListXL activity) {
        mContext = activity;
        mTargetActivity = activity;
        mFragmentManager = mTargetActivity.getFragmentManager();
    }

    /** Set callback for fragment. */
    public void setMailboxListFragmentCallback(
            MailboxListFragment.Callback mailboxListFragmentCallback) {
        mMailboxListFragmentCallback = mailboxListFragmentCallback;
    }

    /** Set callback for fragment. */
    public void setMessageListFragmentCallback(
            MessageListFragment.Callback messageListFragmentCallback) {
        mMessageListFragmentCallback = messageListFragmentCallback;
    }

    /** Set callback for fragment. */
    public void setMessageViewFragmentCallback(
            MessageViewFragment.Callback messageViewFragmentCallback) {
        mMessageViewFragmentCallback = messageViewFragmentCallback;
    }

    public long getAccountId() {
        return mAccountId;
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    public long getMessageId() {
        return mMessageId;
    }

    public boolean isAccountSelected() {
        return getAccountId() != -1;
    }

    public boolean isMailboxSelected() {
        return getMailboxId() != -1;
    }

    public boolean isMessageSelected() {
        return getMessageId() != -1;
    }

    public MailboxListFragment getMailboxListFragment() {
        return mMailboxListFragment;
    }

    public MessageListFragment getMessageListFragment() {
        return mMessageListFragment;
    }

    public MessageViewFragment getMessageViewFragment() {
        return mMessageViewFragment;
    }

    /**
     * Called from {@link MessageListXL#onStart()}.
     *
     * When the activity is being started, we initialize the "restored" fragments.
     *
     * @see #initRestoredFragments
     */
    public void onStart() {
        if (mIsActivityStarted) {
            return;
        }
        mIsActivityStarted = true;
        initRestoredFragments();
    }

    /**
     * Called from {@link MessageListXL#onStop()}.
     */
    public void onStop() {
        if (!mIsActivityStarted) {
            return;
        }
        mIsActivityStarted = false;
        closeMailboxFinder();
        saveMessageListFragmentState();
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
        outState.putParcelable(BUNDLE_KEY_MESSAGE_LIST_STATE, mMessageListFragmentState);
    }

    public void loadState(Bundle savedInstanceState) {
        mAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
        mMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, -1);
        mMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, -1);
        mMessageListFragmentState = savedInstanceState.getParcelable(BUNDLE_KEY_MESSAGE_LIST_STATE);
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager: Restoring "
                    + mAccountId + "," + mMailboxId + "," + mMessageId);
        }
    }

    private void saveMessageListFragmentState() {
        if (mMessageListFragment != null) {
            mMessageListFragmentState = mMessageListFragment.getState();
        }
    }

    private void restoreMesasgeListState() {
        if ((mMessageListFragment != null) && (mMessageListFragmentState != null)) {
            mMessageListFragmentState.restore(mMessageListFragment);
            mMessageListFragmentState = null;
        }
    }

    /**
     * Called by {@link MessageListXL#onAttachFragment}.
     *
     * If the activity is not started yet, just store it in {@link #mRestoredFragments} to
     * initialize it later.
     */
    public void onAttachFragment(Fragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager.onAttachFragment fragment=" +
                    fragment.getClass());
        }
        if (!mIsActivityStarted) {
            mRestoredFragments.add(fragment);
            return;
        }
        if (fragment instanceof MailboxListFragment) {
            updateMailboxListFragment((MailboxListFragment) fragment);
        } else if (fragment instanceof MessageListFragment) {
            updateMessageListFragment((MessageListFragment) fragment);
        } else if (fragment instanceof MessageViewFragment) {
            updateMessageViewFragment((MessageViewFragment) fragment);
        }
    }

    /**
     * Called by {@link #onStart} to initialize the "restored" fragments.
     */
    private void initRestoredFragments() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXLFragmentManager.initRestoredFragments");
        }
        for (Fragment f : mRestoredFragments) {
            onAttachFragment(f);
        }
        mRestoredFragments.clear();
    }

    /**
     * Call it to select an account.
     *
     * @param accountId account ID.  Must not be -1.
     * @param mailboxId mailbox ID.  Pass -1 to open account's inbox.
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectAccount(long accountId, long mailboxId, boolean byExplicitUserAction) {
        // TODO Handle "combined mailboxes".
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectAccount mAccountId=" + accountId);
        }
        if (accountId == -1) {
            throw new InvalidParameterException();
        }
        if (mAccountId == accountId) {
            return;
        }

        // Update members.
        mAccountId = accountId;
        mMailboxId = -1;
        mMessageId = -1;

        // In case of "message list -> message view -> change account", we don't have to keep it.
        mMessageListFragmentState = null;

        // Replace fragments if necessary.
        final FragmentTransaction ft = mFragmentManager.openTransaction();
        if (mMailboxListFragment == null) {
            // The left pane not set yet.

            // We can put it directly in the layout file, but then it'll have slightly different
            // lifecycle as the other fragments.  Let's create it here this way for now.
            MailboxListFragment f = new MailboxListFragment();
            ft.replace(R.id.left_pane, f);
        }
        if (mMessageListFragment != null) {
            ft.remove(mMessageListFragment);
            mMessageListFragment = null;
        }
        if (mMessageViewFragment != null) {
            ft.remove(mMessageViewFragment);
            mMessageViewFragment = null;
            mTargetActivity.onMessageViewFragmentHidden(); // Don't forget to tell the activity.
        }
        ft.commit();

        // If it's already shown, update it.
        if (mMailboxListFragment != null) {
            updateMailboxListFragment(mMailboxListFragment);
        } else {
            Log.w(Email.LOG_TAG, "MailboxListFragment not set yet.");
        }

        if (mailboxId == -1) {
            startInboxLookup();
        } else {
            selectMailbox(mailboxId, byExplicitUserAction);
        }
    }

    private void updateMailboxListFragment(MailboxListFragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMailboxListFragment mAccountId=" + mAccountId);
        }
        if (mAccountId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMailboxListFragment = fragment;
        fragment.setCallback(mMailboxListFragmentCallback);
        fragment.openMailboxes(mAccountId);
        if (mMailboxId != -1) {
            mMailboxListFragment.setSelectedMailbox(mMailboxId);
        }
    }

    /**
     * If the current view is MessageView, go back to MessageList.
     */
    public void goBackToMailbox() {
        if (isMessageSelected()) {
            selectMailbox(getMailboxId(), false);
        }
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the mailbox selected here belongs to the account selected with
     * {@link #selectAccount}.
     *
     * @param mailboxId ID of mailbox
     * @param byExplicitUserAction set true if the user is explicitly opening the mailbox,
     *     in which case we perform "auto-refresh".
     */
    public void selectMailbox(long mailboxId, boolean byExplicitUserAction) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMailbox mMailboxId=" + mailboxId);
        }
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        if ((mMailboxId == mailboxId) && !isMessageSelected()) {
            return;
        }

        // Update members.
        mMailboxId = mailboxId;
        mMessageId = -1;

        // Update fragments.
        if (mMessageListFragment == null) {
            MessageListFragment f = new MessageListFragment();
            if (byExplicitUserAction) {
                f.doAutoRefresh();
            }
            mFragmentManager.openTransaction().replace(R.id.right_pane, f).commit();

            if (mMessageViewFragment != null) {
                // Message view will disappear.
                mMessageViewFragment = null;
                mTargetActivity.onMessageViewFragmentHidden(); // Don't forget to tell the activity.
            }
        } else {
            if (byExplicitUserAction) {
                mMessageListFragment.doAutoRefresh();
            }
            updateMessageListFragment(mMessageListFragment);
        }
        if (mMailboxListFragment != null) {
            mMailboxListFragment.setSelectedMailbox(mMailboxId);
        }
    }

    private void updateMessageListFragment(MessageListFragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMessageListFragment mMailboxId=" + mMailboxId);
        }
        if (mAccountId == -1 || mMailboxId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMessageListFragment = fragment;

        mMessageListFragment.setCallback(mMessageListFragmentCallback);
        mMessageListFragment.openMailbox(mMailboxId);
        restoreMesasgeListState();
    }

    /**
     * Call it to select a mailbox.
     *
     * We assume the message passed here belongs to the account/mailbox selected with
     * {@link #selectAccount} and {@link #selectMailbox}.
     */
    public void selectMessage(long messageId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMessage messageId=" + messageId);
        }
        if (messageId == -1) {
            throw new InvalidParameterException();
        }
        if (mMessageId == messageId) {
            return;
        }

        // Save state for back
        saveMessageListFragmentState();

        // Update member.
        mMessageId = messageId;

        // Update fragments.
        if (mMessageViewFragment == null) {
            MessageViewFragment f = new MessageViewFragment();

            // We don't use the built-in back mechanism.
            // See MessageListXL.onBackPressed().
            mFragmentManager.openTransaction().replace(R.id.right_pane, f)
//                    .addToBackStack(null)
                    .commit();
            mMessageListFragment = null;
        } else {
            updateMessageViewFragment(mMessageViewFragment);
        }
    }

    private void updateMessageViewFragment(MessageViewFragment fragment) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "updateMessageViewFragment messageId=" + mMessageId);
        }
        if (mAccountId == -1 || mMailboxId == -1 || mMessageId == -1) { // Shouldn't happen
            throw new RuntimeException();
        }
        mMessageViewFragment = fragment;
        fragment.setCallback(mMessageViewFragmentCallback);
        fragment.openMessage(mMessageId);
        mTargetActivity.onMessageViewFragmentShown(getAccountId(), getMailboxId(), getMessageId());
    }

    private void startInboxLookup() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "startLookForInbox account=" + mAccountId);
        }
        closeMailboxFinder();
        mMailboxFinder = new MailboxFinder(mContext, mAccountId, Mailbox.TYPE_INBOX,
                mMailboxFinderCallback);
        mMailboxFinder.startLookup();
    }

    private void closeMailboxFinder() {
        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();
            mMailboxFinder = null;
        }
    }

    private class MailboxFinderCallback implements MailboxFinder.Callback {
        @Override
        public void onAccountNotFound() {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onAccountNotFound");
            }
            // Shouldn't happen
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onAccountSecurityHold");
            }
            mTargetActivity.onAccountSecurityHold();
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "  Found inbox");
            }
            selectMailbox(mailboxId, true);
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
                Log.d(Email.LOG_TAG, "MailboxFinderCallback.onMailboxNotFound");
            }
            // Shouldn't happen
        }
    }

}
