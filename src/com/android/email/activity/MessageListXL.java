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

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import java.util.ArrayList;

// TODO Where/when/how do we close loaders??  Do we have to?  Getting this error:
// Finalizing a Cursor that has not been deactivated or closed.
// database = /data/data/com.google.android.email/databases/EmailProvider.db,
// table = Account, query = SELECT _id, displayName, emailAddress FROM Account

/**
 * Two pane activity for XL screen devices.
 *
 * TOOD Test it!
 */
public class MessageListXL extends Activity implements View.OnClickListener {
    private static final String BUNDLE_KEY_ACCOUNT_ID = "MessageListXl.account_id";
    private static final String BUNDLE_KEY_MAILBOX_ID = "MessageListXl.mailbox_id";
    private static final String BUNDLE_KEY_MESSAGE_ID = "MessageListXl.message_id";

    private static final int LOADER_ID_DEFAULT_ACCOUNT = 0;

    private Context mContext;

    private long mAccountId = -1;
    private long mMailboxId = -1;
    private long mMessageId = -1;

    private MailboxListFragment mMailboxListFragment;
    private MessageListFragment mMessageListFragment;
    private MessageViewFragment mMessageViewFragment;

    private View mMessageViewButtons;
    private View mMoveToNewer;
    private View mMoveToOlder;

    private boolean mActivityInitialized;
    private final ArrayList<Fragment> mRestoredFragments = new ArrayList<Fragment>();
    private MessageOrderManager mOrderManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.message_list_xl);

        final boolean isRestoring = (savedInstanceState != null);

        mContext = getApplicationContext();

        mMessageViewButtons = findViewById(R.id.message_view_buttons);
        mMoveToNewer = findViewById(R.id.moveToNewer);
        mMoveToOlder = findViewById(R.id.moveToOlder);
        mMoveToNewer.setOnClickListener(this);
        mMoveToOlder.setOnClickListener(this);

        if (isRestoring) {
            restoreSavedState(savedInstanceState);
        }
        if (mAccountId == -1) {
            loadDefaultAccount();
        }
        // TODO Initialize accounts dropdown.

        mActivityInitialized = true;
        if (isRestoring) {
            initRestoredFragments();
        }
    }

    private void restoreSavedState(Bundle savedInstanceState) {
        mAccountId = savedInstanceState.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
        mMailboxId = savedInstanceState.getLong(BUNDLE_KEY_MAILBOX_ID, -1);
        mMessageId = savedInstanceState.getLong(BUNDLE_KEY_MESSAGE_ID, -1);
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXl: Restoring "
                    + mAccountId + "," + mMailboxId + "," + mMessageId);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(BUNDLE_KEY_ACCOUNT_ID, mAccountId);
        outState.putLong(BUNDLE_KEY_MAILBOX_ID, mMailboxId);
        outState.putLong(BUNDLE_KEY_MESSAGE_ID, mMessageId);
    }

    @Override
    protected void onStart() {
        super.onStart();
        startMessageOrderManager();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // TODO Add stuff that's done in MessageList.onResume().
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopMessageOrderManager();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        if (!mActivityInitialized) {
            // Fragments are being restored in super.onCreate().
            // We can't initialize fragments until the activity is initialized, so remember them for
            // now, and initialize them later in initRestoredFragments().
            mRestoredFragments.add(fragment);
            return;
        }
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MessageListXl.onAttachFragment fragment=" + fragment.getClass());
        }
        super.onAttachFragment(fragment);
        initFragment(fragment);
    }

    private void initFragment(Fragment fragment) {
        if (fragment instanceof MailboxListFragment) {
            initMailboxListFragment((MailboxListFragment) fragment);
        } else if (fragment instanceof MessageListFragment) {
            initMessageListFragment((MessageListFragment) fragment);
        } else if (fragment instanceof MessageViewFragment) {
            initMessageViewFragment((MessageViewFragment) fragment);
        }
    }
    /**
     * Called from {@link #onCreate}.
     * Initializes the fragmetns that are restored in super.onCreate().
     */
    private void initRestoredFragments() {
        for (Fragment f : mRestoredFragments) {
            initFragment(f);
        }
        mRestoredFragments.clear();
    }

    private void startMessageOrderManager() {
        if (mMailboxId == -1) {
            return;
        }
        if (mOrderManager != null && mOrderManager.getMailboxId() == mMailboxId) {
            return;
        }
        stopMessageOrderManager();
        mOrderManager = new MessageOrderManager(this, mMailboxId,
                new MessageOrderManager.Callback() {
            @Override
            public void onMessagesChanged() {
                updateNavigationArrows();
            }

            @Override
            public void onMessageNotFound() {
                // Current message removed.
                selectMailbox(mMailboxId);
            }
        });
    }

    private void stopMessageOrderManager() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
    }

    private void loadDefaultAccount() {
        getLoaderManager().initLoader(LOADER_ID_DEFAULT_ACCOUNT, null, new LoaderCallbacks<Long>() {
            @Override
            public Loader<Long> onCreateLoader(int id, Bundle args) {
                return new DefaultAccountLoader(mContext);
            }

            @Override
            public void onLoadFinished(Loader<Long> loader, Long accountId) {
                if (Email.DEBUG) {
                    Log.d(Email.LOG_TAG, "Default account=" + accountId);
                }
                if (accountId == null || accountId == -1) {
                    onNoAccountFound();
                } else {
                    selectAccount(accountId);
                }
            }
        });
    }

    private void onNoAccountFound() {
        // Open Welcome, which in turn shows the adding a new account screen.
        Welcome.actionStart(this);
        finish();
        return;
    }

    // NOTE These selectXxx are *not* only methods where mXxxId's are changed.
    // When the activity is re-created (e.g. for orientation change), the following things happen.
    // - mXxxId's are restored from Bundle
    // - Fragments are restored by the framework (in super.onCreate())
    // - mXxxId's are set to fragments in initXxxFragment()
    //
    // So, if you want to do something when, for example, the current account changes,
    // selectAccount() is probably not a good place to do it, because it'll be skipped when
    // the activity is re-created.
    // Instead, do that in initXxxFragment().  Alternatively, adding the same procedure to
    // initRestoredFragments() too will probably work.

    private void selectAccount(long accountId) {
        // TODO Handle "combined mailboxes".  Who should take care of it?  This activity?
        // MailboxListFragment??
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectAccount mAccountId=" + mAccountId);
        }
        if (accountId == -1) {
            throw new RuntimeException();
        }
        if (mAccountId == accountId) {
            return;
        }
        mAccountId = accountId;
        mMailboxId = -1;
        mMessageId = -1;

        // TODO We don't have to replace the fragment, Just update it.
        // That will be in accordance with our back model.

        final MailboxListFragment fragment = new MailboxListFragment();
        final FragmentTransaction ft = openFragmentTransaction();
        ft.replace(R.id.left_pane, fragment);
        if (mMessageListFragment != null) {
            ft.remove(mMessageListFragment);
        }
        if (mMessageViewFragment != null) {
            ft.remove(mMessageListFragment);
        }
        ft.commit();

        // TODO Open inbox for the selected account.
    }

    private void selectMailbox(long mailboxId) {
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMailbox mMailboxId=" + mMailboxId);
        }
        if (mMailboxId == mailboxId) {
            return;
        }

        // TODO We don't have to replace the fragment, if it's already the message list.  Just
        // update it.
        // That will be in accordance with our back model.

        mMailboxId = mailboxId;
        mMessageId = -1;
        MessageListFragment fragment = new MessageListFragment();
        openFragmentTransaction().replace(R.id.right_pane, fragment).commit();
    }

    private void selectMessage(long messageId) {
        // TODO: Deal with draft messages.  (open MessageCompose instead)
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "selectMessage messageId=" + mMessageId);
        }
        if (mMessageId == messageId) {
            return;
        }
        mMessageId = messageId;
        MessageViewFragment fragment = new MessageViewFragment();
        openFragmentTransaction().replace(R.id.right_pane, fragment).commit();
    }

    private void initMailboxListFragment(MailboxListFragment fragment) {
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "initMailboxListFragment mAccountId=" + mAccountId);
        }
        if (mAccountId == -1) {
            throw new RuntimeException();
        }
        mMessageListFragment = null;
        mMessageViewFragment = null;
        mMailboxListFragment = fragment;
        fragment.bindActivityInfo(mAccountId, new MailboxListFragment.Callback() {
            @Override
            public void onRefresh(long accountId, long mailboxId) {
                // Will be removed.
            }

            // TODO Rename to onSelectMailbox
            @Override
            public void onOpen(long accountId, long mailboxId) {
                selectMailbox(mailboxId);
            }
        });
    }

    private void initMessageListFragment(MessageListFragment fragment) {
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "initMessageListFragment mMailboxId=" + mMailboxId);
        }
        if (mAccountId == -1 || mMailboxId == -1) {
            throw new RuntimeException();
        }
        mMessageListFragment = fragment;
        mMessageViewFragment = null;
        mMessageViewButtons.setVisibility(View.GONE);
        fragment.setCallback(new MessageListFragment.Callback() {
            @Override
            public void onSelectionChanged() {
                // TODO Context mode
            }

            @Override
            // TODO Rename to onSelectMessage
            public void onMessageOpen(long messageId, long mailboxId) { // RENAME: OpenMessage ?
                selectMessage(messageId);
            }

            @Override
            public void onMailboxNotFound() { // RENAME: NotExists? (see MessageViewFragment)
                // TODO: What to do??
            }
        });
        fragment.openMailbox(mAccountId, mMailboxId);
    }

    private void initMessageViewFragment(MessageViewFragment fragment) {
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "initMessageViewFragment messageId=" + mMessageId);
        }
        if (mMessageId == -1) {
            throw new RuntimeException();
        }
        mMessageViewFragment = fragment;
        mMessageListFragment = null;
        mMessageViewButtons.setVisibility(View.VISIBLE);
        fragment.setCallback(new MessageViewFragment.Callback() {
            @Override
            public boolean onUrlInMessageClicked(String url) {
                return false;
            }

            @Override
            public void onRespondedToInvite(int response) {
            }

            @Override
            public void onMessageSetUnread() {
            }

            @Override
            public void onMessageNotExists() {
            }

            @Override
            public void onLoadMessageStarted() {
            }

            @Override
            public void onLoadMessageFinished() {
            }

            @Override
            public void onLoadMessageError() {
            }

            @Override
            public void onFetchAttachmentStarted(String attachmentName) {
            }

            @Override
            public void onFetchAttachmentFinished() {
            }

            @Override
            public void onFetchAttachmentError() {
            }

            @Override
            public void onCalendarLinkClicked(long epochEventStartTime) {
            }
        });
        fragment.openMessage(mMessageId);
        startMessageOrderManager();
        mOrderManager.moveTo(mMessageId);
        updateNavigationArrows();
    }

    private void updateNavigationArrows() {
        mMoveToNewer.setEnabled((mOrderManager != null) && mOrderManager.canMoveToNewer());
        mMoveToOlder.setEnabled((mOrderManager != null) && mOrderManager.canMoveToOlder());
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.moveToOlder:
                moveToOlder();
                break;
            case R.id.moveToNewer:
                moveToNewer();
                break;
        }
    }

    private boolean moveToOlder() {
        if (mOrderManager != null && mOrderManager.moveToOlder()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            mMessageViewFragment.openMessage(mMessageId);
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (mOrderManager != null && mOrderManager.moveToNewer()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            mMessageViewFragment.openMessage(mMessageId);
            return true;
        }
        return false;
    }
}
