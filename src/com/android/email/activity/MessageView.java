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

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

/**
 * Activity to show (non-EML) email messages.
 *
 * This activity shows regular email messages, which are not file-based.  (i.e. not *.eml or *.msg)
 *
 * See {@link MessageViewBase} for the class relation diagram.
 */
public class MessageView extends MessageViewBase implements View.OnClickListener,
        MessageOrderManager.Callback, MessageViewFragment.Callback {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";

    // for saveInstanceState()
    private static final String STATE_MESSAGE_ID = "messageId";

    private long mMessageId;
    private long mMailboxId;

    private MessageOrderManager mOrderManager;

    private MessageViewFragment mFragment;

    private View mMoveToNewer;
    private View mMoveToOlder;

    // False when a message can't be forwarded/replied, such as trashed messages
    private boolean mReplyAndForwardEnabled;

    /**
     * View a specific message found in the Email provider.
     * @param messageId the message to view.
     * @param mailboxId identifies the sequence of messages used for newer/older navigation.
     */
    public static void actionView(Context context, long messageId, long mailboxId) {
        context.startActivity(getActionViewIntent(context, messageId, mailboxId));
    }

    public static Intent getActionViewIntent(Context context, long messageId, long mailboxId) {
        if (messageId < 0) {
            throw new IllegalArgumentException("MessageView invalid messageId " + messageId);
        }
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        return i;
    }

    @Override
    protected int getLayoutId() {
        return R.layout.message_view;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mFragment = (MessageViewFragment) getFragmentManager()
                .findFragmentById(R.id.message_view_fragment);
        mFragment.setCallback(this);

        // TODO Remove these bottom buttons, and make use of the ones in MessageViewFragments.

        mMoveToNewer = findViewById(R.id.moveToNewer);
        mMoveToOlder = findViewById(R.id.moveToOlder);
        mMoveToNewer.setOnClickListener(this);
        mMoveToOlder.setOnClickListener(this);

        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);

        initFromIntent();
        if (icicle != null) {
            mMessageId = icicle.getLong(STATE_MESSAGE_ID, mMessageId);
        }
    }

    private void initFromIntent() {
        Intent intent = getIntent();
        mMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
        mMailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        if (mMessageId == -1 || mMailboxId == -1) {
            Log.w(Email.LOG_TAG, "Insufficient intent parameter.  Closing...");
            finish();
            return;
        }

        enableForwardReply(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (mMessageId != -1) {
            state.putLong(STATE_MESSAGE_ID, mMessageId);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }
        mOrderManager = new MessageOrderManager(this, mMailboxId, this);
        messageChanged();
    }

    @Override
    public void onPause() {
        if (mOrderManager != null) {
            mOrderManager.close();
            mOrderManager = null;
        }
        super.onPause();
    }

    // Note the return type is a subclass of that of the super class method.
    @Override
    protected MessageViewFragment getFragment() {
        return mFragment;
    }

    @Override
    protected long getAccountId() {
        return getFragment().getAccountId();
    }

    @Override
    public void onMessageViewShown(int mailboxType) {
        super.onMessageViewShown(mailboxType);
        enableForwardReply(mailboxType != Mailbox.TYPE_TRASH);
    }

    private void onDeleteMessage() {
        // the delete triggers mCursorObserver in MessageOrderManager.
        // first move to older/newer before the actual delete
        long messageIdToDelete = mMessageId;
        boolean moved = moveToOlder() || moveToNewer(); // TODO use "auto-advance" preference
        ActivityHelper.deleteMessage(this, messageIdToDelete);
        if (!moved) {
            // this generates a benign warning "Duplicate finish request" because
            // MessageOrderManager detects that the current message is gone, and we finish() it
            // in the onMessageNotFound() callback.
            finish();
        }
    }

    private boolean moveToOlder() {
        if (mOrderManager != null && mOrderManager.moveToOlder()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            messageChanged();
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (mOrderManager != null && mOrderManager.moveToNewer()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            messageChanged();
            return true;
        }
        return false;
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.reply:
                onReply();
                break;
            case R.id.reply_all:
                onReplyAll();
                break;
            case R.id.delete:
                onDeleteMessage();
                break;
            case R.id.moveToOlder:
                moveToOlder();
                break;
            case R.id.moveToNewer:
                moveToNewer();
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean handled = handleMenuItem(item.getItemId());
        if (!handled) {
            handled = super.onOptionsItemSelected(item);
        }
        return handled;
    }

    /**
     * This is the core functionality of onOptionsItemSelected() but broken out and exposed
     * for testing purposes (because it's annoying to mock a MenuItem).
     *
     * @param menuItemId id that was clicked
     * @return true if handled here
     */
    /* package */ boolean handleMenuItem(int menuItemId) {
        switch (menuItemId) {
            case R.id.delete:
                onDeleteMessage();
                break;
            case R.id.reply:
                onReply();
                break;
            case R.id.reply_all:
                onReplyAll();
                break;
            case R.id.forward:
                onForward();
                break;
            case R.id.mark_as_unread:
                getFragment().onMarkMessageAsRead(false);
                break;
            default:
                return false;
        }
        return true;
    }

    @Override
    public void onMessageSetUnread() {
        finish();
    }

    private void enableForwardReply(boolean enabled) {
        mReplyAndForwardEnabled = enabled;
        findViewById(R.id.reply).setEnabled(enabled);
        findViewById(R.id.reply_all).setEnabled(enabled);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_view_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.forward).setEnabled(mReplyAndForwardEnabled);
        menu.findItem(R.id.reply).setEnabled(mReplyAndForwardEnabled);
        menu.findItem(R.id.reply_all).setEnabled(mReplyAndForwardEnabled);
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * Sync the current message.
     * - Set message id to the fragment and the message order manager.
     * - Update the navigation arrows.
     */
    private void messageChanged() {
        getFragment().openMessage(mMessageId);
        if (mOrderManager != null) {
            mOrderManager.moveTo(mMessageId);
        }
        updateNavigationArrows();
    }

    /**
     * Update the arrows based on the current position of the older/newer cursor.
     */
    private void updateNavigationArrows() {
        mMoveToNewer.setEnabled((mOrderManager != null) && mOrderManager.canMoveToNewer());
        mMoveToOlder.setEnabled((mOrderManager != null) && mOrderManager.canMoveToOlder());
    }

    /** Implements {@link MessageOrderManager.Callback#onMessageNotFound()}. */
    // TODO Name too generic.  Rename this.
    @Override
    public void onMessageNotFound() {
        finish();
    }

    /** Implements {@link MessageOrderManager.Callback#onMessagesChanged()}. */
    // TODO Name too generic.  Rename this.
    @Override
    public void onMessagesChanged() {
        updateNavigationArrows();
    }

    @Override
    public void onRespondedToInvite(int response) {
        // TODO use "auto-advance" preference
        if (!moveToOlder()) {
            finish(); // if this is the last message, move up to message-list.
        }
    }

    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        ActivityHelper.openCalendar(this, epochEventStartTime);
    }

    @Override
    public void onReply() {
        MessageCompose.actionReply(this, mMessageId, false);
        finish();
    }

    @Override
    public void onReplyAll() {
        MessageCompose.actionReply(this, mMessageId, true);
        finish();
    }

    @Override
    public void onForward() {
        MessageCompose.actionForward(this, mMessageId);
        finish();
    }

    @Override
    public void onMoveToNewer() {
        // TODO Implement this
    }

    @Override
    public void onMoveToOlder() {
        // TODO Implement this
    }

    @Override
    public void onBeforeMessageDelete() {
        // TODO Implement this
    }

    @Override
    public void onMoveMessage() {
        // TODO Implement this
    }
}
