/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;

// TODO Spin-off a new activity for EML files.

public class MessageView extends Activity implements OnClickListener, MessageOrderManager.Callback,
        MessageViewFragment.Callback {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";
    /* package */ static final String EXTRA_DISABLE_REPLY =
        "com.android.email.MessageView_disable_reply";

    // for saveInstanceState()
    private static final String STATE_MESSAGE_ID = "messageId";

    private ProgressDialog mFetchAttachmentProgressDialog;
    private MessageViewFragment mMessageViewFragment;

    /**
     * If set, URI to the email (i.e. *.eml files, and possibly *.msg files) file that's being
     * viewed.
     *
     * Use {@link #isViewingEmailFile()} to see if the activity is created for opening an EML file.
     *
     * TODO: We probably should split it into two different MessageViews, one for regular messages
     * and the other for for EML files (these two will share the same base MessageView class) to
     * eliminate the bunch of 'if {@link #isViewingEmailFile()}'s.
     * Do this after making it into a fragment.
     */
    private Uri mFileEmailUri;
    private long mMessageId;
    private long mMailboxId;

    private MessageOrderManager mOrderManager;

    private Controller mController;

    private View mMoveToNewer;
    private View mMoveToOlder;

    // this is true when reply & forward are disabled, such as messages in the trash
    private boolean mDisableReplyAndForward;

    /**
     * View a specific message found in the Email provider.
     * @param messageId the message to view.
     * @param mailboxId identifies the sequence of messages used for newer/older navigation.
     * @param disableReplyAndForward set if reply/forward do not make sense for this message
     *        (e.g. messages in Trash).
     */
    public static void actionView(Context context, long messageId, long mailboxId,
            boolean disableReplyAndForward) {
        if (messageId < 0) {
            throw new IllegalArgumentException("MessageView invalid messageId " + messageId);
        }
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        i.putExtra(EXTRA_DISABLE_REPLY, disableReplyAndForward);
        context.startActivity(i);
    }

    public static void actionView(Context context, long messageId, long mailboxId) {
        actionView(context, messageId, mailboxId, false);
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.message_view);

        mMessageViewFragment = (MessageViewFragment) findFragmentById(R.id.message_view_fragment);
        mMessageViewFragment.setCallback(this);

        mMoveToNewer = findViewById(R.id.moveToNewer);
        mMoveToOlder = findViewById(R.id.moveToOlder);
        mMoveToNewer.setOnClickListener(this);
        mMoveToOlder.setOnClickListener(this);

        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);

        // TODO Turn it into a "managed" dialog?
        // Managed dialogs survive activity re-creation.  (e.g. orientation change)
        mFetchAttachmentProgressDialog = new ProgressDialog(this);
        mFetchAttachmentProgressDialog.setIndeterminate(true);
        mFetchAttachmentProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);

        initFromIntent();
        if (icicle != null) {
            mMessageId = icicle.getLong(STATE_MESSAGE_ID, mMessageId);
        }

        mController = Controller.getInstance(getApplication());
    }

    /* package */ void initFromIntent() {
        Intent intent = getIntent();
        mFileEmailUri = intent.getData();
        if (mFileEmailUri == null) {
            mMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
            mMailboxId = intent.getLongExtra(EXTRA_MAILBOX_ID, -1);
        } else {
            mMessageId = -1;
            mMailboxId = -1;
        }
        mDisableReplyAndForward = intent.getBooleanExtra(EXTRA_DISABLE_REPLY, false)
                || isViewingEmailFile();
        if (mDisableReplyAndForward) {
            findViewById(R.id.reply).setEnabled(false);
            findViewById(R.id.reply_all).setEnabled(false);
        }
        if (isViewingEmailFile()) {
            // TODO set title here: "Viewing XXX.eml".
            findViewById(R.id.delete).setEnabled(false);
            findViewById(R.id.favorite).setVisibility(View.GONE);
        }
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

        if (!isViewingEmailFile()) {
            // Exit immediately if the accounts list has changed (e.g. externally deleted)
            if (Email.getNotifyUiAccountsChanged()) {
                Welcome.actionStart(this);
                finish();
                return;
            }
            mOrderManager = new MessageOrderManager(this, mMailboxId, this);
        }
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

    /**
     * We override onDestroy to make sure that the WebView gets explicitly destroyed.
     * Otherwise it can leak native references.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    // TODO Make EML specific activity, and this will be gone.
    /**
     * @return true if viewing an email file.  (i.e. *.eml files)
     */
    private boolean isViewingEmailFile() {
        return mFileEmailUri != null;
    }

    /**
     * {@inheritDoc}
     *
     * This is intended to mirror the operation of the original
     * (see android.webkit.CallbackProxy) with one addition of intent flags
     * "FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET".  This improves behavior when sublaunching
     * other apps via embedded URI's.
     *
     * We also use this hook to catch "mailto:" links and handle them locally.
     */
    @Override
    public boolean onUrlInMessageClicked(String url) {
        // hijack mailto: uri's and handle locally
        if (url != null && url.toLowerCase().startsWith("mailto:")) {
            // If it's showing an EML file, there might be no accounts, but then MessageCompose
            // should close itself.
            long senderAccountId = isViewingEmailFile() ? -1 : mMessageViewFragment.getAccountId();

            // TODO if MessageCompose implements the account selector, we'll be able to just pass -1
            // as the account id.
            return MessageCompose.actionCompose(MessageView.this, url, senderAccountId);
        }

        // Handle most uri's via intent launch
        boolean result = false;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.putExtra(Browser.EXTRA_APPLICATION_ID, getPackageName());
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        try {
            startActivity(intent);
            result = true;
        } catch (ActivityNotFoundException ex) {
            // No applications can handle it.  Ignore.
        }
        return result;
    }

    private void onReply() {
        if (isViewingEmailFile()) {
            return;
        }
        MessageCompose.actionReply(this, mMessageId, false);
        finish();
    }

    private void onReplyAll() {
        if (isViewingEmailFile()) {
            return;
        }
        MessageCompose.actionReply(this, mMessageId, true);
        finish();
    }

    private void onForward() {
        if (isViewingEmailFile()) {
            return;
        }
        MessageCompose.actionForward(this, mMessageId);
        finish();
    }

    private void onDeleteMessage() {
        if (isViewingEmailFile()) {
            return;
        }
        // the delete triggers mCursorObserver in MessageOrderManager.
        // first move to older/newer before the actual delete
        long messageIdToDelete = mMessageId;
        boolean moved = moveToOlder() || moveToNewer();
        mController.deleteMessage(messageIdToDelete, -1);
        Utility.showToast(this,
                getResources().getQuantityString(R.plurals.message_deleted_toast, 1));
        if (!moved) {
            // this generates a benign warning "Duplicate finish request" because
            // MessageOrderManager detects that the current message is gone, and we finish() it
            // in the onMessageNotFound() callback.
            finish();
        }
    }

    private boolean moveToOlder() {
        if (isViewingEmailFile()) {
            return false;
        }
        if (mOrderManager != null && mOrderManager.moveToOlder()) {
            mMessageId = mOrderManager.getCurrentMessageId();
            messageChanged();
            return true;
        }
        return false;
    }

    private boolean moveToNewer() {
        if (isViewingEmailFile()) {
            return false;
        }
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
                mMessageViewFragment.onMarkMessageAsRead(false);
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (isViewingEmailFile()) {
            return false; // No menu options for EML files.
        }
        getMenuInflater().inflate(R.menu.message_view_option, menu);
        if (mDisableReplyAndForward) {
            menu.findItem(R.id.forward).setEnabled(false);
            menu.findItem(R.id.reply).setEnabled(false);
            menu.findItem(R.id.reply_all).setEnabled(false);
        }
        return true;
    }

    // TODO This method name and logic are both not too great.  Make it cleaner.
    // - This method wouldn't be needed for the activity for EML.
    // - Change it to something like openMessage(long messageId).
    private void messageChanged() {
        if (mFileEmailUri != null) {
            mMessageViewFragment.openMessage(mFileEmailUri);
        } else {
            mMessageViewFragment.openMessage(mMessageId);
            if (mOrderManager != null) {
                mOrderManager.moveTo(mMessageId);
            }
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
    public void onLoadMessageStarted() {
        setProgressBarIndeterminateVisibility(true);
    }

    @Override
    public void onLoadMessageFinished() {
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onLoadMessageError() {
        onLoadMessageFinished();
    }

    @Override
    public void onFetchAttachmentStarted(String attachmentName) {
        mFetchAttachmentProgressDialog.setMessage(
                getString(R.string.message_view_fetching_attachment_progress,
                        attachmentName));
        mFetchAttachmentProgressDialog.show();
        setProgressBarIndeterminateVisibility(true);
    }

    @Override
    public void onFetchAttachmentFinished() {
        mFetchAttachmentProgressDialog.dismiss();
        setProgressBarIndeterminateVisibility(false);
    }

    @Override
    public void onFetchAttachmentError() {
        onFetchAttachmentFinished();
    }

    @Override
    public void onMessageNotExists() { // Probably meessage deleted.
        finish();
    }

    @Override
    public void onRespondedToInvite(int response) {
        if (!moveToOlder()) {
            finish(); // if this is the last message, move up to message-list.
        }
    }

    @Override
    public void onCalendarLinkClicked(long epochEventStartTime) {
        Uri uri = Uri.parse("content://com.android.calendar/time/" + epochEventStartTime);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(uri);
        intent.putExtra("VIEW", "DAY");
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        startActivity(intent);
    }
}
