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
import com.android.email.EmailAddressAdapter;
import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.mail.Address;
import com.android.email.mail.MessagingException;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.BodyColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.util.Rfc822Tokenizer;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;


public class MessageCompose extends Activity implements OnClickListener, OnFocusChangeListener {
    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";

    private static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EXTRA_MESSAGE_ID = "message_id";
    private static final String STATE_KEY_CC_SHOWN =
        "com.android.email.activity.MessageCompose.ccShown";
    private static final String STATE_KEY_BCC_SHOWN =
        "com.android.email.activity.MessageCompose.bccShown";
    private static final String STATE_KEY_QUOTED_TEXT_SHOWN =
        "com.android.email.activity.MessageCompose.quotedTextShown";
    private static final String STATE_KEY_SOURCE_MESSAGE_PROCED =
        "com.android.email.activity.MessageCompose.stateKeySourceMessageProced";

    private static final int MSG_PROGRESS_ON = 1;
    private static final int MSG_PROGRESS_OFF = 2;
    private static final int MSG_UPDATE_TITLE = 3;
    private static final int MSG_SKIPPED_ATTACHMENTS = 4;
    private static final int MSG_DISCARDED_DRAFT = 6;

    private static final int ACTIVITY_REQUEST_PICK_ATTACHMENT = 1;

    private static final String[] ATTACHMENT_META_COLUMNS = {
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE
    };

    private Account mAccount;

    // mDraft is null until the first save, afterwards it contains the last saved version.
    private Message mDraft;

    // mSource is only set for REPLY, REPLY_ALL and FORWARD, and contains the source message.
    private Message mSource;

    /**
     * Indicates that the source message has been processed at least once and should not
     * be processed on any subsequent loads. This protects us from adding attachments that
     * have already been added from the restore of the view state.
     */
    private boolean mSourceMessageProcessed = false;

    private MultiAutoCompleteTextView mToView;
    private MultiAutoCompleteTextView mCcView;
    private MultiAutoCompleteTextView mBccView;
    private EditText mSubjectView;
    private EditText mMessageContentView;
    private Button mSendButton;
    private Button mDiscardButton;
    private Button mSaveButton;
    private LinearLayout mAttachments;
    private View mQuotedTextBar;
    private ImageButton mQuotedTextDelete;
    private WebView mQuotedText;

    private Controller mController;
    private Listener mListener = new Listener();
    private boolean mDraftNeedsSaving;
    private AsyncTask mLoadAttachmentsTask;
    private AsyncTask mSaveMessageTask;
    private AsyncTask mLoadMessageTask;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS_ON:
                    setProgressBarIndeterminateVisibility(true);
                    break;
                case MSG_PROGRESS_OFF:
                    setProgressBarIndeterminateVisibility(false);
                    break;
                case MSG_UPDATE_TITLE:
                    updateTitle();
                    break;
                case MSG_SKIPPED_ATTACHMENTS:
                    Toast.makeText(
                            MessageCompose.this,
                            getString(R.string.message_compose_attachments_skipped_toast),
                            Toast.LENGTH_LONG).show();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    /**
     * Compose a new message using the given account. If account is -1 the default account
     * will be used.
     * @param context
     * @param account
     */
    public static void actionCompose(Context context, long accountId) {
       try {
           Intent i = new Intent(context, MessageCompose.class);
           i.putExtra(EXTRA_ACCOUNT_ID, accountId);
           context.startActivity(i);
       } catch (ActivityNotFoundException anfe) {
           // Swallow it - this is usually a race condition, especially under automated test.
           // (The message composer might have been disabled)
           Email.log(anfe.toString());
       }
    }

    /**
     * Compose a new message as a reply to the given message. If replyAll is true the function
     * is reply all instead of simply reply.
     * @param context
     * @param messageId
     * @param replyAll
     */
    public static void actionReply(Context context, long messageId, boolean replyAll) {
        startActivityWithMessage(context, replyAll ? ACTION_REPLY_ALL : ACTION_REPLY, messageId);
    }

    /**
     * Compose a new message as a forward of the given message.
     * @param context
     * @param messageId
     */
    public static void actionForward(Context context, long messageId) {
        startActivityWithMessage(context, ACTION_FORWARD, messageId);
    }

    /**
     * Continue composition of the given message. This action modifies the way this Activity
     * handles certain actions.
     * Save will attempt to replace the message in the given folder with the updated version.
     * Discard will delete the message from the given folder.
     * @param context
     * @param messageId the message id.
     */
    public static void actionEditDraft(Context context, long messageId) {
        startActivityWithMessage(context, ACTION_EDIT_DRAFT, messageId);
    }

    private static void startActivityWithMessage(Context context, String action, long messageId) {
        Intent i = new Intent(context, MessageCompose.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.setAction(action);
        context.startActivity(i);
    }

    private void setAccount(Intent intent) {
        long accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        if (accountId == -1) {
            accountId = Account.getDefaultAccountId(this);
        }
        if (accountId == -1) {
            // There are no accounts set up. This should not have happened. Prompt the
            // user to set up an account as an acceptable bailout.
            AccountFolderList.actionShowAccounts(this);
            finish();
        } else {
            mAccount = Account.restoreAccountWithId(this, accountId);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.message_compose);
        mController = Controller.getInstance(getApplication());
        initViews();

        if (savedInstanceState != null) {
            /*
             * This data gets used in onCreate, so grab it here instead of onRestoreIntstanceState
             */
            mSourceMessageProcessed =
                savedInstanceState.getBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, false);
        }

        Intent intent = getIntent();
        final String action = intent.getAction();

        // Handle the various intents that launch the message composer
        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_SENDTO.equals(action)
                || Intent.ACTION_SEND.equals(action)
                || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            setAccount(intent);
            // Use the fields found in the Intent to prefill as much of the message as possible
            initFromIntent(intent);
        } else {
            // Otherwise, handle the internal cases (Message Composer invoked from within app)
            long messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
            if (messageId != -1) {
                mLoadMessageTask = new LoadMessageTask().execute(messageId);
            } else {
                setAccount(intent);
            }
            if (ACTION_EDIT_DRAFT.equals(action) && messageId != -1) {
                mLoadAttachmentsTask = new AsyncTask<Long, Void, Attachment[]>() {
                    @Override
                    protected Attachment[] doInBackground(Long... messageIds) {
                        return Attachment.restoreAttachmentsWithMessageId(MessageCompose.this,
                                messageIds[0]);
                    }
                    @Override
                    protected void onPostExecute(Attachment[] attachments) {
                        for (Attachment attachment : attachments) {
                            addAttachment(attachment);
                        }
                    }
                }.execute(messageId);
            }
        }

        if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action) ||
                ACTION_FORWARD.equals(action) || ACTION_EDIT_DRAFT.equals(action)) {
            /*
             * If we need to load the message we add ourself as a message listener here
             * so we can kick it off. Normally we add in onResume but we don't
             * want to reload the message every time the activity is resumed.
             * There is no harm in adding twice.
             */
            // TODO: signal the controller to load the message
        }
        updateTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        mController.addResultCallback(mListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveIfNeeded();
        mController.removeResultCallback(mListener);
    }

    private static void cancelTask(AsyncTask<?, ?, ?> task) {
        if (task != null && task.getStatus() != AsyncTask.Status.FINISHED) {
            task.cancel(true);
        }
    }

    /**
     * We override onDestroy to make sure that the WebView gets explicitly destroyed.
     * Otherwise it can leak native references.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        mQuotedText.destroy();
        mQuotedText = null;
        cancelTask(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
        cancelTask(mLoadMessageTask);
        mLoadMessageTask = null;
        // don't cancel mSaveMessageTask, let it do its job to the end.
        // cancelTask(mSaveMessageTask);
    }

    /**
     * The framework handles most of the fields, but we need to handle stuff that we
     * dynamically show and hide:
     * Cc field,
     * Bcc field,
     * Quoted text,
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveIfNeeded();
        outState.putBoolean(STATE_KEY_CC_SHOWN, mCcView.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_BCC_SHOWN, mBccView.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_QUOTED_TEXT_SHOWN,
                mQuotedTextBar.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, mSourceMessageProcessed);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mCcView.setVisibility(savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN) ?
                View.VISIBLE : View.GONE);
        mBccView.setVisibility(savedInstanceState.getBoolean(STATE_KEY_BCC_SHOWN) ?
                View.VISIBLE : View.GONE);
        mQuotedTextBar.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        mQuotedText.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        mDraftNeedsSaving = false;
    }

    private void initViews() {
        mToView = (MultiAutoCompleteTextView)findViewById(R.id.to);
        mCcView = (MultiAutoCompleteTextView)findViewById(R.id.cc);
        mBccView = (MultiAutoCompleteTextView)findViewById(R.id.bcc);
        mSubjectView = (EditText)findViewById(R.id.subject);
        mMessageContentView = (EditText)findViewById(R.id.message_content);
        mSendButton = (Button)findViewById(R.id.send);
        mDiscardButton = (Button)findViewById(R.id.discard);
        mSaveButton = (Button)findViewById(R.id.save);
        mAttachments = (LinearLayout)findViewById(R.id.attachments);
        mQuotedTextBar = findViewById(R.id.quoted_text_bar);
        mQuotedTextDelete = (ImageButton)findViewById(R.id.quoted_text_delete);
        mQuotedText = (WebView)findViewById(R.id.quoted_text);

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start,
                                          int before, int after) { }

            public void onTextChanged(CharSequence s, int start,
                                          int before, int count) {
                mDraftNeedsSaving = true;
            }

            public void afterTextChanged(android.text.Editable s) { }
        };

        /**
         * Implements special address cleanup rules:
         * The first space key entry following an "@" symbol that is followed by any combination
         * of letters and symbols, including one+ dots and zero commas, should insert an extra
         * comma (followed by the space).
         */
        InputFilter recipientFilter = new InputFilter() {

            public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                    int dstart, int dend) {

                // quick check - did they enter a single space?
                if (end-start != 1 || source.charAt(start) != ' ') {
                    return null;
                }

                // determine if the characters before the new space fit the pattern
                // follow backwards and see if we find a comma, dot, or @
                int scanBack = dstart;
                boolean dotFound = false;
                while (scanBack > 0) {
                    char c = dest.charAt(--scanBack);
                    switch (c) {
                        case '.':
                            dotFound = true;    // one or more dots are req'd
                            break;
                        case ',':
                            return null;
                        case '@':
                            if (!dotFound) {
                                return null;
                            }

                            // we have found a comma-insert case.  now just do it
                            // in the least expensive way we can.
                            if (source instanceof Spanned) {
                                SpannableStringBuilder sb = new SpannableStringBuilder(",");
                                sb.append(source);
                                return sb;
                            } else {
                                return ", ";
                            }
                        default:
                            // just keep going
                    }
                }

                // no termination cases were found, so don't edit the input
                return null;
            }
        };
        InputFilter[] recipientFilters = new InputFilter[] { recipientFilter };

        mToView.addTextChangedListener(watcher);
        mCcView.addTextChangedListener(watcher);
        mBccView.addTextChangedListener(watcher);
        mSubjectView.addTextChangedListener(watcher);
        mMessageContentView.addTextChangedListener(watcher);

        // NOTE: assumes no other filters are set
        mToView.setFilters(recipientFilters);
        mCcView.setFilters(recipientFilters);
        mBccView.setFilters(recipientFilters);

        /*
         * We set this to invisible by default. Other methods will turn it back on if it's
         * needed.
         */
        mQuotedTextBar.setVisibility(View.GONE);
        mQuotedText.setVisibility(View.GONE);

        mQuotedTextDelete.setOnClickListener(this);

        EmailAddressAdapter addressAdapter = new EmailAddressAdapter(this);
        EmailAddressValidator addressValidator = new EmailAddressValidator();

        mToView.setAdapter(addressAdapter);
        mToView.setTokenizer(new Rfc822Tokenizer());
        mToView.setValidator(addressValidator);

        mCcView.setAdapter(addressAdapter);
        mCcView.setTokenizer(new Rfc822Tokenizer());
        mCcView.setValidator(addressValidator);

        mBccView.setAdapter(addressAdapter);
        mBccView.setTokenizer(new Rfc822Tokenizer());
        mBccView.setValidator(addressValidator);

        mSendButton.setOnClickListener(this);
        mDiscardButton.setOnClickListener(this);
        mSaveButton.setOnClickListener(this);

        mSubjectView.setOnFocusChangeListener(this);
    }

    // TODO: is there any way to unify this with MessageView.LoadMessageTask?
    private class LoadMessageTask extends AsyncTask<Long, Void, Object[]> {
        @Override
        protected Object[] doInBackground(Long... messageIds) {
            Message message = Message.restoreMessageWithId(MessageCompose.this, messageIds[0]);
            long accountId = message.mAccountKey;
            Account account = Account.restoreAccountWithId(MessageCompose.this, accountId);
            Body body = Body.restoreBodyWithMessageId(MessageCompose.this, message.mId);
            message.mHtml = body.mHtmlContent;
            message.mText = body.mTextContent;
            return new Object[]{message, account};
        }

        @Override
        protected void onPostExecute(Object[] messageAndAccount) {
            final Message message = (Message) messageAndAccount[0];
            final Account account = (Account) messageAndAccount[1];
            final String action = getIntent().getAction();
            if (ACTION_EDIT_DRAFT.equals(action)) {
                mDraft = message;
            } else if (ACTION_REPLY.equals(action)
                       || ACTION_REPLY_ALL.equals(action)
                       || ACTION_FORWARD.equals(action)) {
                mSource = message;
            } else if (Email.LOGD) {
                Email.log("Action " + action + " has unexpected EXTRA_MESSAGE_ID");
            }

            mAccount = account;
            // Make sure we only do this once (otherwise we'll duplicate addresses!)
            if (!mSourceMessageProcessed) {
                processSourceMessage(message, mAccount);
            }
        }
    }

    private void updateTitle() {
        if (mSubjectView.getText().length() == 0) {
            setTitle(R.string.compose_title);
        } else {
            setTitle(mSubjectView.getText().toString());
        }
    }

    public void onFocusChange(View view, boolean focused) {
        if (!focused) {
            updateTitle();
        }
    }

    private void addAddresses(MultiAutoCompleteTextView view, Address[] addresses) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            addAddress(view, address.toString());
        }
    }

    private void addAddresses(MultiAutoCompleteTextView view, String[] addresses) {
        if (addresses == null) {
            return;
        }
        for (String oneAddress : addresses) {
            addAddress(view, oneAddress);
        }
    }

    private void addAddress(MultiAutoCompleteTextView view, String address) {
        view.append(address + ", ");
    }

    private String getPackedAddresses(TextView view) {
        Address[] addresses = Address.parse(view.getText().toString().trim());
        return Address.pack(addresses);
    }

    private Address[] getAddresses(TextView view) {
        Address[] addresses = Address.parse(view.getText().toString().trim());
        return addresses;
    }

    private ContentValues getUpdateContentValues(Message message) {
        ContentValues values = new ContentValues();
        values.put(MessageColumns.TIMESTAMP, message.mTimeStamp);
        values.put(MessageColumns.FROM_LIST, message.mFrom);
        values.put(MessageColumns.TO_LIST, message.mTo);
        values.put(MessageColumns.CC_LIST, message.mCc);
        values.put(MessageColumns.BCC_LIST, message.mBcc);
        values.put(MessageColumns.SUBJECT, message.mSubject);
        values.put(MessageColumns.DISPLAY_NAME, message.mDisplayName);
        values.put(MessageColumns.FLAG_LOADED, message.mFlagLoaded);
        values.put(MessageColumns.FLAG_ATTACHMENT, message.mFlagAttachment);
        values.put(MessageColumns.FLAGS, message.mFlags);
        return values;
    }

    /*
     * Computes a short string indicating the destination of the message based on To, Cc, Bcc.
     * If only one address appears, returns the friendly form of that address.
     * Otherwise returns the friendly form of the first address appended with "and N others".
     */
    private String makeDisplayName(String packedTo, String packedCc, String packedBcc) {
        Address first = null;
        int nRecipients = 0;
        for (String packed: new String[] {packedTo, packedCc, packedBcc}) {
            Address[] addresses = Address.unpack(packed);
            nRecipients += addresses.length;
            if (first == null && addresses.length > 0) {
                first = addresses[0];
            }
        }
        if (nRecipients == 0) {
            return "";
        }
        String friendly = first.toFriendly();
        if (nRecipients == 1) {
            return friendly;
        }
        return this.getString(R.string.message_compose_display_name, friendly, nRecipients - 1);
    }

    /**
     * @param message The message to be updated.
     * @param account the account (used to obtain From: address).
     * @param bodyText the body text.
     */
    private void updateMessage(Message message, Account account, boolean hasAttachments) {
        message.mTimeStamp = System.currentTimeMillis();
        message.mFrom = new Address(account.getEmailAddress(), account.getSenderName()).pack();
        message.mTo = getPackedAddresses(mToView);
        message.mCc = getPackedAddresses(mCcView);
        message.mBcc = getPackedAddresses(mBccView);
        message.mSubject = mSubjectView.getText().toString();
        message.mText = mMessageContentView.getText().toString();
        message.mAccountKey = account.mId;
        message.mDisplayName = makeDisplayName(message.mTo, message.mCc, message.mBcc);
        message.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
        message.mFlagAttachment = hasAttachments;
        String action = getIntent().getAction();
        // Use the Intent to set flags saying this message is a reply or a forward and save the
        // unique id of the source message
        if (mSource != null && mQuotedTextBar.getVisibility() == View.VISIBLE) {
            if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action)
                    || ACTION_FORWARD.equals(action)) {
                message.mSourceKey = mSource.mId;
                // Get the body of the source message here
                // Note that the following commented line will be useful when we use HTML in replies
                //message.mHtmlReply = mSource.mHtml;
                message.mTextReply = mSource.mText;
            }
            if (ACTION_FORWARD.equals(action)) {
                message.mFlags |= Message.FLAG_TYPE_FORWARD;
            } else {
                message.mFlags |= Message.FLAG_TYPE_REPLY;
            }
        }
    }

    private Attachment[] getAttachmentsFromUI() {
        int count = mAttachments.getChildCount();
        Attachment[] attachments = new Attachment[count];
        for (int i = 0; i < count; ++i) {
            attachments[i] = (Attachment) mAttachments.getChildAt(i).getTag();
        }
        return attachments;
    }

    /**
     * Send or save a message:
     * - out of the UI thread
     * - write to Drafts
     * - if send, invoke Controller.sendMessage()
     * - when operation is complete, display toast
     */
    private void sendOrSaveMessage(final boolean send) {
        if (mDraft == null) {
            mDraft = new Message();
        }
        final Attachment[] attachments = getAttachmentsFromUI();
        updateMessage(mDraft, mAccount, attachments.length > 0);

        mSaveMessageTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                synchronized (mDraft) {
                    if (mDraft.isSaved()) {
                        mDraft.update(MessageCompose.this, getUpdateContentValues(mDraft));
                        ContentValues values = new ContentValues();
                        values.put(BodyColumns.TEXT_CONTENT, mDraft.mText);
                        values.put(BodyColumns.TEXT_REPLY, mDraft.mTextReply);
                        values.put(BodyColumns.HTML_REPLY, mDraft.mHtmlReply);
                        Body.updateBodyWithMessageId(MessageCompose.this, mDraft.mId, values);
                    } else {
                        // mDraft.mId is set upon return of saveToMailbox()
                        mController.saveToMailbox(mDraft, EmailContent.Mailbox.TYPE_DRAFTS);
                    }

                    // TODO: remove from DB the attachments that were removed from UI
                    for (Attachment attachment : attachments) {
                        if (!attachment.isSaved()) {
                            // this attachment is new so save it to DB.
                            attachment.mMessageKey = mDraft.mId;
                            attachment.save(MessageCompose.this);
                        }
                    }

                    if (send) {
                        mController.sendMessage(mDraft.mId, mDraft.mAccountKey);
                    }
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Void dummy) {
                // Don't display the toast if the user is just changing the orientation
                if (!send && (getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION) == 0) {
                    Toast.makeText(MessageCompose.this, R.string.message_saved_toast,
                            Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private void saveIfNeeded() {
        if (!mDraftNeedsSaving) {
            return;
        }
        mDraftNeedsSaving = false;
        sendOrSaveMessage(false);
    }

    /**
     * Checks whether all the email addresses listed in TO, CC, BCC are valid.
     */
    /* package */ boolean isAddressAllValid() {
        for (TextView view : new TextView[]{mToView, mCcView, mBccView}) {
            String addresses = view.getText().toString().trim();
            if (!Address.isAllValid(addresses)) {
                view.setError(getString(R.string.message_compose_error_invalid_email));
                return false;
            }
        }
        return true;
    }

    private void onSend() {
        if (!isAddressAllValid()) {
            Toast.makeText(this, getString(R.string.message_compose_error_invalid_email),
                           Toast.LENGTH_LONG).show();
        } else if (getAddresses(mToView).length == 0 &&
                getAddresses(mCcView).length == 0 &&
                getAddresses(mBccView).length == 0) {
            mToView.setError(getString(R.string.message_compose_error_no_recipients));
            Toast.makeText(this, getString(R.string.message_compose_error_no_recipients),
                    Toast.LENGTH_LONG).show();
        } else {
            sendOrSaveMessage(true);
            mDraftNeedsSaving = false;
            finish();
        }
    }

    private void onDiscard() {
        if (mDraft != null) {
            mController.deleteMessage(mDraft.mId, mDraft.mAccountKey);
        }
        Toast.makeText(this, getString(R.string.message_discarded_toast), Toast.LENGTH_LONG).show();
        mDraftNeedsSaving = false;
        finish();
    }

    private void onSave() {
        saveIfNeeded();
        finish();
    }

    private void onAddCcBcc() {
        mCcView.setVisibility(View.VISIBLE);
        mBccView.setVisibility(View.VISIBLE);
    }

    /**
     * Kick off a picker for whatever kind of MIME types we'll accept and let Android take over.
     */
    private void onAddAttachment() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Email.ACCEPTABLE_ATTACHMENT_SEND_TYPES[0]);
        startActivityForResult(
                Intent.createChooser(i, getString(R.string.choose_attachment_dialog_title)),
                ACTIVITY_REQUEST_PICK_ATTACHMENT);
    }

    private Attachment loadAttachmentInfo(Uri uri) {
        int size = -1;
        String name = null;
        ContentResolver contentResolver = getContentResolver();
        Cursor metadataCursor = contentResolver.query(uri,
                ATTACHMENT_META_COLUMNS, null, null, null);
        if (metadataCursor != null) {
            try {
                if (metadataCursor.moveToFirst()) {
                    name = metadataCursor.getString(0);
                    size = metadataCursor.getInt(1);
                }
            } finally {
                metadataCursor.close();
            }
        }
        if (name == null) {
            name = uri.getLastPathSegment();
        }

        String contentType = contentResolver.getType(uri);
        if (contentType == null) {
            contentType = "";
        }

        Attachment attachment = new Attachment();
        attachment.mFileName = name;
        attachment.mContentUri = uri.toString();
        attachment.mSize = size;
        attachment.mMimeType = contentType;
        return attachment;
    }

    private void addAttachment(Attachment attachment) {
        // Before attaching the attachment, make sure it meets any other pre-attach criteria
        if (attachment.mSize > Email.MAX_ATTACHMENT_UPLOAD_SIZE) {
            Toast.makeText(this, R.string.message_compose_attachment_size, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.message_compose_attachment,
                mAttachments, false);
        TextView nameView = (TextView)view.findViewById(R.id.attachment_name);
        ImageButton delete = (ImageButton)view.findViewById(R.id.attachment_delete);
        nameView.setText(attachment.mFileName);
        delete.setOnClickListener(this);
        delete.setTag(view);
        view.setTag(attachment);
        mAttachments.addView(view);
    }

    private void addAttachment(Uri uri) {
        addAttachment(loadAttachmentInfo(uri));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) {
            return;
        }
        addAttachment(data.getData());
        mDraftNeedsSaving = true;
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.send:
                onSend();
                break;
            case R.id.save:
                onSave();
                break;
            case R.id.discard:
                onDiscard();
                break;
            case R.id.attachment_delete:
                /*
                 * The view is the delete button, and we have previously set the tag of
                 * the delete button to the view that owns it. We don't use parent because the
                 * view is very complex and could change in the future.
                 */
                mAttachments.removeView((View) view.getTag());
                mDraftNeedsSaving = true;
                break;
            case R.id.quoted_text_delete:
                mQuotedTextBar.setVisibility(View.GONE);
                mQuotedText.setVisibility(View.GONE);
                mDraftNeedsSaving = true;
                break;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.send:
                onSend();
                break;
            case R.id.save:
                onSave();
                break;
            case R.id.discard:
                onDiscard();
                break;
            case R.id.add_cc_bcc:
                onAddCcBcc();
                break;
            case R.id.add_attachment:
                onAddAttachment();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_compose_option, menu);
        return true;
    }

    /**
     * Returns true if all attachments were able to be attached, otherwise returns false.
     */
//     private boolean loadAttachments(Part part, int depth) throws MessagingException {
//         if (part.getBody() instanceof Multipart) {
//             Multipart mp = (Multipart) part.getBody();
//             boolean ret = true;
//             for (int i = 0, count = mp.getCount(); i < count; i++) {
//                 if (!loadAttachments(mp.getBodyPart(i), depth + 1)) {
//                     ret = false;
//                 }
//             }
//             return ret;
//         } else {
//             String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
//             String name = MimeUtility.getHeaderParameter(contentType, "name");
//             if (name != null) {
//                 Body body = part.getBody();
//                 if (body != null && body instanceof LocalAttachmentBody) {
//                     final Uri uri = ((LocalAttachmentBody) body).getContentUri();
//                     mHandler.post(new Runnable() {
//                         public void run() {
//                             addAttachment(uri);
//                         }
//                     });
//                 }
//                 else {
//                     return false;
//                 }
//             }
//             return true;
//         }
//     }

    /**
     * Fill all the widgets with the content found in the Intent Extra, if any.
     *
     * Note that we don't actually check the intent action  (typically VIEW, SENDTO, or SEND).
     * There is enough overlap in the definitions that it makes more sense to simply check for
     * all available data and use as much of it as possible.
     *
     * With one exception:  EXTRA_STREAM is defined as only valid for ACTION_SEND.
     *
     * @param intent the launch intent
     */
    /* package */ void initFromIntent(Intent intent) {

        // First, add values stored in top-level extras

        String[] extraStrings = intent.getStringArrayExtra(Intent.EXTRA_EMAIL);
        if (extraStrings != null) {
            addAddresses(mToView, extraStrings);
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_CC);
        if (extraStrings != null) {
            addAddresses(mCcView, extraStrings);
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_BCC);
        if (extraStrings != null) {
            addAddresses(mBccView, extraStrings);
        }
        String extraString = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (extraString != null) {
            mSubjectView.setText(extraString);
        }

        // Next, if we were invoked with a URI, try to interpret it
        // We'll take two courses here.  If it's mailto:, there is a specific set of rules
        // that define various optional fields.  However, for any other scheme, we'll simply
        // take the entire scheme-specific part and interpret it as a possible list of addresses.

        final Uri dataUri = intent.getData();
        if (dataUri != null) {
            if ("mailto".equals(dataUri.getScheme())) {
                initializeFromMailTo(dataUri.toString());
            } else {
                String toText = dataUri.getSchemeSpecificPart();
                if (toText != null) {
                    addAddresses(mToView, toText.split(","));
                }
            }
        }

        // Next, fill in the plaintext (note, this will override mailto:?body=)

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) {
            mMessageContentView.setText(text);
        }

        // Next, convert EXTRA_STREAM into an attachment

        if (Intent.ACTION_SEND.equals(intent.getAction()) && intent.hasExtra(Intent.EXTRA_STREAM)) {
            String type = intent.getType();
            Uri stream = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null && type != null) {
                if (MimeUtility.mimeTypeMatches(type, Email.ACCEPTABLE_ATTACHMENT_SEND_TYPES)) {
                    addAttachment(stream);
                }
            }
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())
                && intent.hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable parcelable : list) {
                    Uri uri = (Uri) parcelable;
                    if (uri != null) {
                        Attachment attachment = loadAttachmentInfo(uri);
                        if (MimeUtility.mimeTypeMatches(attachment.mMimeType,
                                                        Email.ACCEPTABLE_ATTACHMENT_SEND_TYPES)) {
                            addAttachment(attachment);
                        }
                    }
                }
            }
        }

        // Finally - expose fields that were filled in but are normally hidden, and set focus

        if (mCcView.length() > 0) {
            mCcView.setVisibility(View.VISIBLE);
        }
        if (mBccView.length() > 0) {
            mBccView.setVisibility(View.VISIBLE);
        }
        setNewMessageFocus();
        mDraftNeedsSaving = false;
    }

    /**
     * When we are launched with an intent that includes a mailto: URI, we can actually
     * gather quite a few of our message fields from it.
     *
     * @mailToString the href (which must start with "mailto:").
     */
    private void initializeFromMailTo(String mailToString) {

        // Chop up everything between mailto: and ? to find recipients
        int index = mailToString.indexOf("?");
        int length = "mailto".length() + 1;
        String to;
        try {
            // Extract the recipient after mailto:
            if (index == -1) {
                to = decode(mailToString.substring(length));
            } else {
                to = decode(mailToString.substring(length, index));
            }
            addAddresses(mToView, to.split(" ,"));
        } catch (UnsupportedEncodingException e) {
            Log.e(Email.LOG_TAG, e.getMessage() + " while decoding '" + mailToString + "'");
        }

        // Extract the other parameters

        // We need to disguise this string as a URI in order to parse it
        Uri uri = Uri.parse("foo://" + mailToString);

        List<String> cc = uri.getQueryParameters("cc");
        addAddresses(mCcView, cc.toArray(new String[cc.size()]));

        List<String> otherTo = uri.getQueryParameters("to");
        addAddresses(mCcView, otherTo.toArray(new String[otherTo.size()]));

        List<String> bcc = uri.getQueryParameters("bcc");
        addAddresses(mBccView, bcc.toArray(new String[bcc.size()]));

        List<String> subject = uri.getQueryParameters("subject");
        if (subject.size() > 0) {
            mSubjectView.setText(subject.get(0));
        }

        List<String> body = uri.getQueryParameters("body");
        if (body.size() > 0) {
            mMessageContentView.setText(body.get(0));
        }
    }

    private String decode(String s) throws UnsupportedEncodingException {
        return URLDecoder.decode(s, "UTF-8");
    }

    // used by processSourceMessage()
    private void displayQuotedText(Message message) {
        boolean plainTextFlag = message.mHtml == null;
        String text = plainTextFlag ? message.mText : message.mHtml;
        if (text != null) {
            text = plainTextFlag ? EmailHtmlUtil.escapeCharacterToDisplay(text) : text;
            // TODO: re-enable EmailHtmlUtil.resolveInlineImage() for HTML
            //    EmailHtmlUtil.resolveInlineImage(getContentResolver(), mAccount,
            //                                     text, message, 0);
            mQuotedTextBar.setVisibility(View.VISIBLE);
            mQuotedText.setVisibility(View.VISIBLE);
            mQuotedText.loadDataWithBaseURL("email://", text, "text/html",
                                            "utf-8", null);
        }
    }

    /**
     * Given a packed address String, the address of our sending account, a view, and a list of
     * addressees already added to other addressing views, adds unique addressees that don't
     * match our address to the passed in view
     */
    private boolean safeAddAddresses(String addrs, String ourAddress,
            MultiAutoCompleteTextView view, ArrayList<Address> addrList) {
        boolean added = false;
        for (Address address : Address.unpack(addrs)) {
            // Don't send to ourselves or already-included addresses
            if (!address.getAddress().equalsIgnoreCase(ourAddress) && !addrList.contains(address)) {
                addrList.add(address);
                addAddress(view, address.toString());
                added = true;
            }
        }
        return added;
    }

    /**
     * Set up the to and cc views properly for the "reply" and "replyAll" cases.  What's important
     * is that we not 1) send to ourselves, and 2) duplicate addressees.
     * @param message the message we're replying to
     * @param account the account we're sending from
     * @param toView the "To" view
     * @param ccView the "Cc" view
     * @param replyAll whether this is a replyAll (vs a reply)
     */
    /*package*/ void setupAddressViews(Message message, Account account,
            MultiAutoCompleteTextView toView, MultiAutoCompleteTextView ccView, boolean replyAll) {
        /*
         * If a reply-to was included with the message use that, otherwise use the from
         * or sender address.
         */
        Address[] replyToAddresses = Address.unpack(message.mReplyTo);
        if (replyToAddresses.length == 0) {
            replyToAddresses = Address.unpack(message.mFrom);
        }
        addAddresses(mToView, replyToAddresses);

        if (replyAll) {
            // Keep a running list of addresses we're sending to
            ArrayList<Address> allAddresses = new ArrayList<Address>();
            String ourAddress = account.mEmailAddress;

            for (Address address: replyToAddresses) {
                allAddresses.add(address);
            }

            safeAddAddresses(message.mTo, ourAddress, mToView, allAddresses);
            if (safeAddAddresses(message.mCc, ourAddress, mCcView, allAddresses)) {
                mCcView.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Pull out the parts of the now loaded source message and apply them to the new message
     * depending on the type of message being composed.
     * @param message
     */
    /* package */
    void processSourceMessage(Message message, Account account) {
        final String action = getIntent().getAction();
        mDraftNeedsSaving = true;
        final String subject = message.mSubject;
        if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action)) {
            setupAddressViews(message, account, mToView, mCcView, ACTION_REPLY_ALL.equals(action));

            if (subject != null && !subject.toLowerCase().startsWith("re:")) {
                mSubjectView.setText("Re: " + subject);
            } else {
                mSubjectView.setText(subject);
            }

            displayQuotedText(message);
        } else if (ACTION_FORWARD.equals(action)) {
            mSubjectView.setText(subject != null && !subject.toLowerCase().startsWith("fwd:") ?
                                 "Fwd: " + subject : subject);
            displayQuotedText(message);
            if (!mSourceMessageProcessed) {
                // TODO: re-enable loadAttachments below
//                 if (!loadAttachments(message, 0)) {
//                     mHandler.sendEmptyMessage(MSG_SKIPPED_ATTACHMENTS);
//                 }
            }
        } else if (ACTION_EDIT_DRAFT.equals(action)) {
            mSubjectView.setText(subject);
            addAddresses(mToView, Address.unpack(message.mTo));
            Address[] cc = Address.unpack(message.mCc);
            if (cc.length > 0) {
                addAddresses(mCcView, cc);
                mCcView.setVisibility(View.VISIBLE);
            }
            Address[] bcc = Address.unpack(message.mBcc);
            if (bcc.length > 0) {
                addAddresses(mBccView, bcc);
                mBccView.setVisibility(View.VISIBLE);
            }

            // TODO: why not the same text handling as in displayQuotedText() ?
            mMessageContentView.setText(message.mText);

            if (!mSourceMessageProcessed) {
                // TODO: re-enable loadAttachments
                // loadAttachments(message, 0);
            }
            mDraftNeedsSaving = false;
        }
        setNewMessageFocus();
        mSourceMessageProcessed = true;
    }

    /**
     * In order to accelerate typing, position the cursor in the first empty field,
     * or at the end of the body composition field if none are empty.  Typically, this will
     * play out as follows:
     *   Reply / Reply All - put cursor in the empty message body
     *   Forward - put cursor in the empty To field
     *   Edit Draft - put cursor in whatever field still needs entry
     */
    private void setNewMessageFocus() {
        if (mToView.length() == 0) {
            mToView.requestFocus();
        } else if (mSubjectView.length() == 0) {
            mSubjectView.requestFocus();
        } else {
            mMessageContentView.requestFocus();
            // when selecting the message content, explicitly move IP to the end, so you can
            // quickly resume typing into a draft
            int selection = mMessageContentView.length();
            mMessageContentView.setSelection(selection, selection);
        }
    }

    class Listener implements Controller.Result {
        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
        }

        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
            if (result != null || progress == 100) {
                Email.updateMailboxRefreshTime(mailboxId);
            }
        }

        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
        }

        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
        }

        public void sendMailCallback(MessagingException result, long accountId, long messageId,
                int progress) {
        }
    }

//     class Listener extends MessagingListener {
//         @Override
//         public void loadMessageForViewStarted(Account account, String folder,
//                 String uid) {
//             mHandler.sendEmptyMessage(MSG_PROGRESS_ON);
//         }

//         @Override
//         public void loadMessageForViewFinished(Account account, String folder,
//                 String uid, Message message) {
//             mHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
//         }

//         @Override
//         public void loadMessageForViewBodyAvailable(Account account, String folder,
//                 String uid, final Message message) {
//            // TODO: convert uid to EmailContent.Message and re-do what's below
//             mSourceMessage = message;
//             runOnUiThread(new Runnable() {
//                 public void run() {
//                     processSourceMessage(message);
//                 }
//             });
//         }

//         @Override
//         public void loadMessageForViewFailed(Account account, String folder, String uid,
//                 final String message) {
//             mHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
//             // TODO show network error
//         }
//     }
}
