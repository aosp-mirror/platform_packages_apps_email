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
import com.android.email.UiUtilities;
import com.android.email.R;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Rfc822Tokenizer;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.webkit.WebView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;


/**
 * Activity to compose a message.
 *
 * TODO Revive shortcuts command for removed menu options.
 * C: add cc/bcc
 * N: add attachment
 */
public class MessageCompose extends Activity implements OnClickListener, OnFocusChangeListener,
        DeleteMessageConfirmationDialog.Callback {
    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";

    private static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EXTRA_MESSAGE_ID = "message_id";
    /** If the intent is sent from the email app itself, it should have this boolean extra. */
    private static final String EXTRA_FROM_WITHIN_APP = "from_within_app";

    private static final String STATE_KEY_CC_SHOWN =
        "com.android.email.activity.MessageCompose.ccShown";
    private static final String STATE_KEY_QUOTED_TEXT_SHOWN =
        "com.android.email.activity.MessageCompose.quotedTextShown";
    private static final String STATE_KEY_SOURCE_MESSAGE_PROCED =
        "com.android.email.activity.MessageCompose.stateKeySourceMessageProced";
    private static final String STATE_KEY_DRAFT_ID =
        "com.android.email.activity.MessageCompose.draftId";

    private static final int ACTIVITY_REQUEST_PICK_ATTACHMENT = 1;

    private static final String[] ATTACHMENT_META_SIZE_PROJECTION = {
        OpenableColumns.SIZE
    };
    private static final int ATTACHMENT_META_SIZE_COLUMN_SIZE = 0;

    // Is set while the draft is saved by a background thread.
    // Is static in order to be shared between the two activity instances
    // on orientation change.
    private static boolean sSaveInProgress = false;
    // lock and condition for sSaveInProgress
    private static final Object sSaveInProgressCondition = new Object();

    private Account mAccount;

    // mDraft has mId > 0 after the first draft save.
    private Message mDraft = new Message();

    // mSource is only set for REPLY, REPLY_ALL and FORWARD, and contains the source message.
    private Message mSource;

    // we use mAction instead of Intent.getAction() because sometimes we need to
    // re-write the action to EDIT_DRAFT.
    private String mAction;

    /**
     * Indicates that the source message has been processed at least once and should not
     * be processed on any subsequent loads. This protects us from adding attachments that
     * have already been added from the restore of the view state.
     */
    private boolean mSourceMessageProcessed = false;

    private TextView mFromView;
    private MultiAutoCompleteTextView mToView;
    private MultiAutoCompleteTextView mCcView;
    private MultiAutoCompleteTextView mBccView;
    private View mCcBccContainer;
    private EditText mSubjectView;
    private EditText mMessageContentView;
    private View mAttachmentContainer;
    private LinearLayout mAttachments;
    private View mQuotedTextBar;
    private CheckBox mIncludeQuotedTextCheckBox;
    private WebView mQuotedText;

    private Controller mController;
    private boolean mDraftNeedsSaving;
    private boolean mMessageLoaded;
    private AsyncTask<Long, Void, Attachment[]> mLoadAttachmentsTask;
    private AsyncTask<Void, Void, Object[]> mLoadMessageTask;

    private EmailAddressAdapter mAddressAdapterTo;
    private EmailAddressAdapter mAddressAdapterCc;
    private EmailAddressAdapter mAddressAdapterBcc;

    /** Whether the save command should be enabled. */
    private boolean mSaveEnabled;

    private static Intent getBaseIntent(Context context) {
        Intent i = new Intent(context, MessageCompose.class);
        i.putExtra(EXTRA_FROM_WITHIN_APP, true);
        return i;
    }

    /**
     * Create an {@link Intent} that can start the message compose activity. If accountId -1,
     * the default account will be used; otherwise, the specified account is used.
     */
    public static Intent getMessageComposeIntent(Context context, long accountId) {
        Intent i = getBaseIntent(context);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        return i;
    }

    /**
     * Compose a new message using the given account. If account is -1 the default account
     * will be used.
     * @param context
     * @param accountId
     */
    public static void actionCompose(Context context, long accountId) {
       try {
           Intent i = getMessageComposeIntent(context, accountId);
           context.startActivity(i);
       } catch (ActivityNotFoundException anfe) {
           // Swallow it - this is usually a race condition, especially under automated test.
           // (The message composer might have been disabled)
           Email.log(anfe.toString());
       }
    }

    /**
     * Compose a new message using a uri (mailto:) and a given account.  If account is -1 the
     * default account will be used.
     * @param context
     * @param uriString
     * @param accountId
     * @return true if startActivity() succeeded
     */
    public static boolean actionCompose(Context context, String uriString, long accountId) {
        try {
            Intent i = getMessageComposeIntent(context, accountId);
            i.setAction(Intent.ACTION_SEND);
            i.setData(Uri.parse(uriString));
            context.startActivity(i);
            return true;
        } catch (ActivityNotFoundException anfe) {
            // Swallow it - this is usually a race condition, especially under automated test.
            // (The message composer might have been disabled)
            Email.log(anfe.toString());
            return false;
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
        Intent i = getBaseIntent(context);
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
            setAccount(Account.restoreAccountWithId(this, accountId));
        }
    }

    private void setAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException();
        }
        mAccount = account;
        mFromView.setText(account.mEmailAddress);
        mAddressAdapterTo.setAccount(account);
        mAddressAdapterCc.setAccount(account);
        mAddressAdapterBcc.setAccount(account);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_compose);

        mController = Controller.getInstance(getApplication());
        initViews();
        setDraftNeedsSaving(false);

        long draftId = -1;
        if (savedInstanceState != null) {
            // This data gets used in onCreate, so grab it here instead of onRestoreInstanceState
            mSourceMessageProcessed =
                savedInstanceState.getBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, false);
            draftId = savedInstanceState.getLong(STATE_KEY_DRAFT_ID, -1);
        }

        // Show the back arrow on the action bar.
        getActionBar().setDisplayOptions(
                ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);

        Intent intent = getIntent();
        mAction = intent.getAction();

        if (draftId != -1) {
            // this means that we saved the draft earlier,
            // so now we need to disregard the intent action and do
            // EDIT_DRAFT instead.
            mAction = ACTION_EDIT_DRAFT;
            mDraft.mId = draftId;
        }

        // Handle the various intents that launch the message composer
        if (Intent.ACTION_VIEW.equals(mAction)
                || Intent.ACTION_SENDTO.equals(mAction)
                || Intent.ACTION_SEND.equals(mAction)
                || Intent.ACTION_SEND_MULTIPLE.equals(mAction)) {
            setAccount(intent);
            // Use the fields found in the Intent to prefill as much of the message as possible
            initFromIntent(intent);
            setDraftNeedsSaving(true);
            mMessageLoaded = true;
            mSourceMessageProcessed = true;
        } else {
            // Otherwise, handle the internal cases (Message Composer invoked from within app)
            long messageId = draftId != -1 ? draftId : intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
            if (messageId != -1) {
                mLoadMessageTask = new LoadMessageTask(messageId).execute();
            } else {
                setAccount(intent);
                // Since this is a new message, we don't need to call LoadMessageTask.
                // But we DO need to set mMessageLoaded to indicate the message can be sent
                mMessageLoaded = true;
                mSourceMessageProcessed = true;
            }
            setInitialComposeText(null, (mAccount != null) ? mAccount.mSignature : null);
        }

        if (ACTION_REPLY.equals(mAction) || ACTION_REPLY_ALL.equals(mAction) ||
                ACTION_FORWARD.equals(mAction) || ACTION_EDIT_DRAFT.equals(mAction)) {
            /*
             * If we need to load the message we add ourself as a message listener here
             * so we can kick it off. Normally we add in onResume but we don't
             * want to reload the message every time the activity is resumed.
             * There is no harm in adding twice.
             */
            // TODO: signal the controller to load the message
        }
    }

    // needed for unit tests
    @Override
    public void setIntent(Intent intent) {
        super.setIntent(intent);
        mAction = intent.getAction();
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
    }

    @Override
    public void onPause() {
        super.onPause();
        saveIfNeeded();
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

        Utility.cancelTaskInterrupt(mLoadAttachmentsTask);
        mLoadAttachmentsTask = null;
        Utility.cancelTaskInterrupt(mLoadMessageTask);
        mLoadMessageTask = null;

        if (mAddressAdapterTo != null) {
            mAddressAdapterTo.close();
        }
        if (mAddressAdapterCc != null) {
            mAddressAdapterCc.close();
        }
        if (mAddressAdapterBcc != null) {
            mAddressAdapterBcc.close();
        }
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
        long draftId = getOrCreateDraftId();
        if (draftId != -1) {
            outState.putLong(STATE_KEY_DRAFT_ID, draftId);
        }
        outState.putBoolean(STATE_KEY_CC_SHOWN, mCcBccContainer.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_QUOTED_TEXT_SHOWN,
                mQuotedTextBar.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, mSourceMessageProcessed);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN)) {
            showCcBccFields();
        }
        mQuotedTextBar.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        mQuotedText.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        setDraftNeedsSaving(false);
    }

    /**
     * @return true if the activity was opened by the email app itself.
     */
    private boolean isOpenedFromWithinApp() {
        Intent i = getIntent();
        return (i != null && i.getBooleanExtra(EXTRA_FROM_WITHIN_APP, false));
    }

    private void setDraftNeedsSaving(boolean needsSaving) {
        mDraftNeedsSaving = needsSaving;
        mSaveEnabled = needsSaving;
        invalidateOptionsMenu();
    }

    public void setFocusShifter(int fromViewId, final int targetViewId) {
        View label = findViewById(fromViewId);
        // Labels don't exist on the phone UI, so null check.
        if (label != null) {
            label.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    findViewById(targetViewId).requestFocus();
                }
            });
        }
    }

    private void initViews() {
        mFromView = (TextView)findViewById(R.id.from);
        mToView = (MultiAutoCompleteTextView)findViewById(R.id.to);
        mCcView = (MultiAutoCompleteTextView)findViewById(R.id.cc);
        mBccView = (MultiAutoCompleteTextView)findViewById(R.id.bcc);
        mCcBccContainer = findViewById(R.id.cc_bcc_container);
        mSubjectView = (EditText)findViewById(R.id.subject);
        mMessageContentView = (EditText)findViewById(R.id.message_content);
        mAttachments = (LinearLayout)findViewById(R.id.attachments);
        mAttachmentContainer = findViewById(R.id.attachment_container);
        mQuotedTextBar = findViewById(R.id.quoted_text_bar);
        mIncludeQuotedTextCheckBox = (CheckBox) findViewById(R.id.include_quoted_text);
        mQuotedText = (WebView)findViewById(R.id.quoted_text);

        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start,
                                          int before, int after) { }

            public void onTextChanged(CharSequence s, int start,
                                          int before, int count) {
                setDraftNeedsSaving(true);
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
        setIncludeQuotedText(false, false);

        mIncludeQuotedTextCheckBox.setOnClickListener(this);

        EmailAddressValidator addressValidator = new EmailAddressValidator();

        setupAddressAdapters();
        mToView.setAdapter(mAddressAdapterTo);
        mToView.setTokenizer(new Rfc822Tokenizer());
        mToView.setValidator(addressValidator);

        mCcView.setAdapter(mAddressAdapterCc);
        mCcView.setTokenizer(new Rfc822Tokenizer());
        mCcView.setValidator(addressValidator);

        mBccView.setAdapter(mAddressAdapterBcc);
        mBccView.setTokenizer(new Rfc822Tokenizer());
        mBccView.setValidator(addressValidator);

        findViewById(R.id.add_cc_bcc).setOnClickListener(this);
        findViewById(R.id.add_attachment).setOnClickListener(this);

        setFocusShifter(R.id.to_label, R.id.to);
        setFocusShifter(R.id.cc_label, R.id.cc);
        setFocusShifter(R.id.bcc_label, R.id.bcc);
        setFocusShifter(R.id.subject_label, R.id.subject);
        setFocusShifter(R.id.tap_trap, R.id.message_content);

        mSubjectView.setOnFocusChangeListener(this);
        mMessageContentView.setOnFocusChangeListener(this);

        updateAttachmentContainer();
    }

    /**
     * Set up address auto-completion adapters.
     */
    private void setupAddressAdapters() {
        mAddressAdapterTo = new EmailAddressAdapter(this);
        mAddressAdapterCc = new EmailAddressAdapter(this);
        mAddressAdapterBcc = new EmailAddressAdapter(this);
    }

    private class LoadMessageTask extends AsyncTask<Void, Void, Object[]> {
        private final long mMessageId;

        public LoadMessageTask(long messageId) {
            mMessageId = messageId;
        }

        @Override
        protected Object[] doInBackground(Void... params) {
            synchronized (sSaveInProgressCondition) {
                while (sSaveInProgress) {
                    try {
                        sSaveInProgressCondition.wait();
                    } catch (InterruptedException e) {
                        // ignore & retry loop
                    }
                }
            }
            Message message = Message.restoreMessageWithId(MessageCompose.this, mMessageId);
            if (message == null) {
                return new Object[] {null, null};
            }
            long accountId = message.mAccountKey;
            Account account = Account.restoreAccountWithId(MessageCompose.this, accountId);
            try {
                // Body body = Body.restoreBodyWithMessageId(MessageCompose.this, message.mId);
                message.mHtml = Body.restoreBodyHtmlWithMessageId(MessageCompose.this, message.mId);
                message.mText = Body.restoreBodyTextWithMessageId(MessageCompose.this, message.mId);
                boolean isEditDraft = ACTION_EDIT_DRAFT.equals(mAction);
                // the reply fields are only filled/used for Drafts.
                if (isEditDraft) {
                    message.mHtmlReply =
                        Body.restoreReplyHtmlWithMessageId(MessageCompose.this, message.mId);
                    message.mTextReply =
                        Body.restoreReplyTextWithMessageId(MessageCompose.this, message.mId);
                    message.mIntroText =
                        Body.restoreIntroTextWithMessageId(MessageCompose.this, message.mId);
                    message.mSourceKey = Body.restoreBodySourceKey(MessageCompose.this,
                                                                   message.mId);
                } else {
                    message.mHtmlReply = null;
                    message.mTextReply = null;
                    message.mIntroText = null;
                }
            } catch (RuntimeException e) {
                Log.d(Logging.LOG_TAG, "Exception while loading message body: " + e);
                return new Object[] {null, null};
            }
            return new Object[]{message, account};
        }

        @Override
        protected void onPostExecute(Object[] messageAndAccount) {
            if (messageAndAccount == null) {
                return;
            }

            final Message message = (Message) messageAndAccount[0];
            final Account account = (Account) messageAndAccount[1];
            if (message == null && account == null) {
                // Something unexpected happened:
                // the message or the body couldn't be loaded by SQLite.
                // Bail out.
                Utility.showToast(MessageCompose.this, R.string.error_loading_message_body);
                finish();
                return;
            }

            // Drafts and "forwards" need to include attachments from the original unless the
            // account is marked as supporting smart forward
            final boolean isEditDraft = ACTION_EDIT_DRAFT.equals(mAction);
            final boolean isForward = ACTION_FORWARD.equals(mAction);
            if (isEditDraft || isForward) {
                if (isEditDraft) {
                    mDraft = message;
                } else {
                    mSource = message;
                }
                mLoadAttachmentsTask = new AsyncTask<Long, Void, Attachment[]>() {
                    @Override
                    protected Attachment[] doInBackground(Long... messageIds) {
                        return Attachment.restoreAttachmentsWithMessageId(MessageCompose.this,
                                messageIds[0]);
                    }
                    @Override
                    protected void onPostExecute(Attachment[] attachments) {
                        if (attachments == null) {
                            return;
                        }
                        final boolean supportsSmartForward =
                            (account.mFlags & Account.FLAGS_SUPPORTS_SMART_FORWARD) != 0;

                        for (Attachment attachment : attachments) {
                            if (supportsSmartForward && isForward) {
                                attachment.mFlags |= Attachment.FLAG_SMART_FORWARD;
                            }
                            // Note allowDelete is set in two cases:
                            // 1. First time a message (w/ attachments) is forwarded,
                            //    where action == ACTION_FORWARD
                            // 2. 1 -> Save -> Reopen, where action == EDIT_DRAFT,
                            //    but FLAG_SMART_FORWARD is already set at 1.
                            // Even if the account supports smart-forward, attachments added
                            // manually are still removable.
                            final boolean allowDelete =
                                    (attachment.mFlags & Attachment.FLAG_SMART_FORWARD) == 0;
                            addAttachment(attachment, allowDelete);
                        }
                    }
                }.execute(message.mId);
            } else if (ACTION_REPLY.equals(mAction) || ACTION_REPLY_ALL.equals(mAction)) {
                mSource = message;
            } else if (Email.LOGD) {
                Email.log("Action " + mAction + " has unexpected EXTRA_MESSAGE_ID");
            }

            setAccount(account);
            processSourceMessageGuarded(message, mAccount);
            mMessageLoaded = true;
        }
    }

    public void onFocusChange(View view, boolean focused) {
        if (focused) {
            switch (view.getId()) {
                case R.id.message_content:
                    setMessageContentSelection((mAccount != null) ? mAccount.mSignature : null);
            }
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

    private ContentValues getUpdateContentValues(Message message) {
        ContentValues values = new ContentValues();
        values.put(MessageColumns.TIMESTAMP, message.mTimeStamp);
        values.put(MessageColumns.FROM_LIST, message.mFrom);
        values.put(MessageColumns.TO_LIST, message.mTo);
        values.put(MessageColumns.CC_LIST, message.mCc);
        values.put(MessageColumns.BCC_LIST, message.mBcc);
        values.put(MessageColumns.SUBJECT, message.mSubject);
        values.put(MessageColumns.DISPLAY_NAME, message.mDisplayName);
        values.put(MessageColumns.FLAG_READ, message.mFlagRead);
        values.put(MessageColumns.FLAG_LOADED, message.mFlagLoaded);
        values.put(MessageColumns.FLAG_ATTACHMENT, message.mFlagAttachment);
        values.put(MessageColumns.FLAGS, message.mFlags);
        return values;
    }

    /**
     * Updates the given message using values from the compose UI.
     *
     * @param message The message to be updated.
     * @param account the account (used to obtain From: address).
     * @param hasAttachments true if it has one or more attachment.
     * @param sending set true if the message is about to sent, in which case we perform final
     *        clean up;
     */
    private void updateMessage(Message message, Account account, boolean hasAttachments,
            boolean sending) {
        if (message.mMessageId == null || message.mMessageId.length() == 0) {
            message.mMessageId = Utility.generateMessageId();
        }
        message.mTimeStamp = System.currentTimeMillis();
        message.mFrom = new Address(account.getEmailAddress(), account.getSenderName()).pack();
        message.mTo = getPackedAddresses(mToView);
        message.mCc = getPackedAddresses(mCcView);
        message.mBcc = getPackedAddresses(mBccView);
        message.mSubject = mSubjectView.getText().toString();
        message.mText = mMessageContentView.getText().toString();
        message.mAccountKey = account.mId;
        message.mDisplayName = makeDisplayName(message.mTo, message.mCc, message.mBcc);
        message.mFlagRead = true;
        message.mFlagLoaded = Message.FLAG_LOADED_COMPLETE;
        message.mFlagAttachment = hasAttachments;
        // Use the Intent to set flags saying this message is a reply or a forward and save the
        // unique id of the source message
        if (mSource != null && mQuotedTextBar.getVisibility() == View.VISIBLE) {
            // If the quote bar is visible; this must either be a reply or forward
            message.mSourceKey = mSource.mId;
            // Get the body of the source message here
            message.mHtmlReply = mSource.mHtml;
            message.mTextReply = mSource.mText;
            String fromAsString = Address.unpackToString(mSource.mFrom);
            if (ACTION_FORWARD.equals(mAction)) {
                message.mFlags |= Message.FLAG_TYPE_FORWARD;
                String subject = mSource.mSubject;
                String to = Address.unpackToString(mSource.mTo);
                String cc = Address.unpackToString(mSource.mCc);
                message.mIntroText =
                    getString(R.string.message_compose_fwd_header_fmt, subject, fromAsString,
                            to != null ? to : "", cc != null ? cc : "");
            } else {
                message.mFlags |= Message.FLAG_TYPE_REPLY;
                message.mIntroText =
                    getString(R.string.message_compose_reply_header_fmt, fromAsString);
            }
        }

        if (includeQuotedText()) {
            message.mFlags &= ~Message.FLAG_NOT_INCLUDE_QUOTED_TEXT;
        } else {
            message.mFlags |= Message.FLAG_NOT_INCLUDE_QUOTED_TEXT;
            if (sending) {
                // If we are about to send a message, and not including the original message,
                // clear the related field.
                // We can't do this until the last minutes, so that the user can change their
                // mind later and want to include it again.
                mDraft.mIntroText = null;
                mDraft.mTextReply = null;
                mDraft.mHtmlReply = null;
                mDraft.mSourceKey = 0;
                mDraft.mFlags &= ~Message.FLAG_TYPE_MASK;
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

    /* This method does DB operations in UI thread because
       the draftId is needed by onSaveInstanceState() which can't wait for it
       to be saved in the background.
       TODO: This will cause ANRs, so we need to find a better solution.
    */
    private long getOrCreateDraftId() {
        synchronized (mDraft) {
            if (mDraft.mId > 0) {
                return mDraft.mId;
            }
            // don't save draft if the source message did not load yet
            if (!mMessageLoaded) {
                return -1;
            }
            final Attachment[] attachments = getAttachmentsFromUI();
            updateMessage(mDraft, mAccount, attachments.length > 0, false);
            mController.saveToMailbox(mDraft, EmailContent.Mailbox.TYPE_DRAFTS);
            return mDraft.mId;
        }
    }

    private class SendOrSaveMessageTask extends AsyncTask<Void, Void, Void> {
        private final boolean mSend;

        public SendOrSaveMessageTask(boolean send) {
            if (send && ActivityManager.isUserAMonkey()) {
                Log.d(Logging.LOG_TAG, "Inhibiting send while monkey is in charge.");
                send = false;
            }
            mSend = send;
        }

        @Override
        protected Void doInBackground(Void... params) {
            synchronized (mDraft) {
                final Attachment[] attachments = getAttachmentsFromUI();
                updateMessage(mDraft, mAccount, attachments.length > 0, mSend);
                ContentResolver resolver = getContentResolver();
                if (mDraft.isSaved()) {
                    // Update the message
                    Uri draftUri =
                        ContentUris.withAppendedId(Message.SYNCED_CONTENT_URI, mDraft.mId);
                    resolver.update(draftUri, getUpdateContentValues(mDraft), null, null);
                    // Update the body
                    ContentValues values = new ContentValues();
                    values.put(BodyColumns.TEXT_CONTENT, mDraft.mText);
                    values.put(BodyColumns.TEXT_REPLY, mDraft.mTextReply);
                    values.put(BodyColumns.HTML_REPLY, mDraft.mHtmlReply);
                    values.put(BodyColumns.INTRO_TEXT, mDraft.mIntroText);
                    values.put(BodyColumns.SOURCE_MESSAGE_KEY, mDraft.mSourceKey);
                    Body.updateBodyWithMessageId(MessageCompose.this, mDraft.mId, values);
                } else {
                    // mDraft.mId is set upon return of saveToMailbox()
                    mController.saveToMailbox(mDraft, EmailContent.Mailbox.TYPE_DRAFTS);
                }
                // For any unloaded attachment, set the flag saying we need it loaded
                boolean hasUnloadedAttachments = false;
                for (Attachment attachment : attachments) {
                    if (attachment.mContentUri == null &&
                            ((attachment.mFlags & Attachment.FLAG_SMART_FORWARD) != 0)) {
                        attachment.mFlags |= Attachment.FLAG_DOWNLOAD_FORWARD;
                        hasUnloadedAttachments = true;
                        if (Email.DEBUG){
                            Log.d("MessageCompose",
                                    "Requesting download of attachment #" + attachment.mId);
                        }
                    }
                    // Make sure the UI version of the attachment has the now-correct id; we will
                    // use the id again when coming back from picking new attachments
                    if (!attachment.isSaved()) {
                        // this attachment is new so save it to DB.
                        attachment.mMessageKey = mDraft.mId;
                        attachment.save(MessageCompose.this);
                    } else if (attachment.mMessageKey != mDraft.mId) {
                        // We clone the attachment and save it again; otherwise, it will
                        // continue to point to the source message.  From this point forward,
                        // the attachments will be independent of the original message in the
                        // database; however, we still need the message on the server in order
                        // to retrieve unloaded attachments
                        attachment.mMessageKey = mDraft.mId;
                        ContentValues cv = attachment.toContentValues();
                        cv.put(Attachment.FLAGS, attachment.mFlags);
                        cv.put(Attachment.MESSAGE_KEY, mDraft.mId);
                        getContentResolver().insert(Attachment.CONTENT_URI, cv);
                    }
                }

                if (mSend) {
                    // Let the user know if message sending might be delayed by background
                    // downlading of unloaded attachments
                    if (hasUnloadedAttachments) {
                        Utility.showToast(MessageCompose.this,
                                R.string.message_view_attachment_background_load);
                    }
                    mController.sendMessage(mDraft.mId, mDraft.mAccountKey);
                }
                return null;
            }
        }

        @Override
        protected void onPostExecute(Void param) {
            synchronized (sSaveInProgressCondition) {
                sSaveInProgress = false;
                sSaveInProgressCondition.notify();
            }
            if (isCancelled()) {
                return;
            }
            // Don't display the toast if the user is just changing the orientation
            if (!mSend && (getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION) == 0) {
                Toast.makeText(MessageCompose.this, R.string.message_saved_toast,
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Send or save a message:
     * - out of the UI thread
     * - write to Drafts
     * - if send, invoke Controller.sendMessage()
     * - when operation is complete, display toast
     */
    private void sendOrSaveMessage(boolean send) {
        if (!mMessageLoaded) {
            // early save, before the message was loaded: do nothing
            return;
        }
        synchronized (sSaveInProgressCondition) {
            sSaveInProgress = true;
        }
        new SendOrSaveMessageTask(send).execute();
   }

    private void saveIfNeeded() {
        if (!mDraftNeedsSaving) {
            return;
        }
        setDraftNeedsSaving(false);
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
            setDraftNeedsSaving(false);
            finish();
        }
    }

    private void onDiscard() {
        DeleteMessageConfirmationDialog.newInstance(1, null).show(getFragmentManager(), "dialog");
    }

    /**
     * Called when ok on the "discard draft" dialog is pressed.  Actually delete the draft.
     */
    @Override
    public void onDeleteMessageConfirmationDialogOkPressed() {
        if (mDraft.mId > 0) {
            // By the way, we can't pass the message ID from onDiscard() to here (using a
            // dialog argument or whatever), because you can rotate the screen when the dialog is
            // shown, and during rotation we save & restore the draft.  If it's the
            // first save, we give it an ID at this point for the first time (and last time).
            // Which means it's possible for a draft to not have an ID in onDiscard(),
            // but here.
            mController.deleteMessage(mDraft.mId, mDraft.mAccountKey);
        }
        Utility.showToast(MessageCompose.this, R.string.message_discarded_toast);
        setDraftNeedsSaving(false);
        finish();
    }

    private void onSave() {
        saveIfNeeded();
        finish();
    }

    private void showCcBccFieldsIfFilled() {
        if ((mCcView.length() > 0) || (mBccView.length() > 0)) {
            showCcBccFields();
        }
    }

    private void showCcBccFields() {
        mCcBccContainer.setVisibility(View.VISIBLE);
        findViewById(R.id.add_cc_bcc).setVisibility(View.INVISIBLE);
    }

    /**
     * Kick off a picker for whatever kind of MIME types we'll accept and let Android take over.
     */
    private void onAddAttachment() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(AttachmentUtilities.ACCEPTABLE_ATTACHMENT_SEND_UI_TYPES[0]);
        startActivityForResult(
                Intent.createChooser(i, getString(R.string.choose_attachment_dialog_title)),
                ACTIVITY_REQUEST_PICK_ATTACHMENT);
    }

    private Attachment loadAttachmentInfo(Uri uri) {
        long size = -1;
        ContentResolver contentResolver = getContentResolver();

        // Load name & size independently, because not all providers support both
        final String name = Utility.getContentFileName(this, uri);

        Cursor metadataCursor = contentResolver.query(uri, ATTACHMENT_META_SIZE_PROJECTION,
                null, null, null);
        if (metadataCursor != null) {
            try {
                if (metadataCursor.moveToFirst()) {
                    size = metadataCursor.getLong(ATTACHMENT_META_SIZE_COLUMN_SIZE);
                }
            } finally {
                metadataCursor.close();
            }
        }

        // When the size is not provided, we need to determine it locally.
        if (size < 0) {
            // if the URI is a file: URI, ask file system for its size
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null) {
                    File file = new File(path);
                    size = file.length();  // Returns 0 for file not found
                }
            }

            if (size <= 0) {
                // The size was not measurable;  This attachment is not safe to use.
                // Quick hack to force a relevant error into the UI
                // TODO: A proper announcement of the problem
                size = AttachmentUtilities.MAX_ATTACHMENT_UPLOAD_SIZE + 1;
            }
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

    private void addAttachment(Attachment attachment, boolean allowDelete) {
        // Before attaching the attachment, make sure it meets any other pre-attach criteria
        if (attachment.mSize > AttachmentUtilities.MAX_ATTACHMENT_UPLOAD_SIZE) {
            Toast.makeText(this, R.string.message_compose_attachment_size, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        View view = getLayoutInflater().inflate(R.layout.message_compose_attachment,
                mAttachments, false);
        TextView nameView = (TextView)view.findViewById(R.id.attachment_name);
        ImageButton delete = (ImageButton)view.findViewById(R.id.attachment_delete);
        TextView sizeView = (TextView)view.findViewById(R.id.attachment_size);

        nameView.setText(attachment.mFileName);
        sizeView.setText(UiUtilities.formatSize(this, attachment.mSize));
        if (allowDelete) {
            delete.setOnClickListener(this);
            delete.setTag(view);
        } else {
            delete.setVisibility(View.INVISIBLE);
        }
        view.setTag(attachment);
        mAttachments.addView(view);
        updateAttachmentContainer();
    }

    private void updateAttachmentContainer() {
        mAttachmentContainer.setVisibility(mAttachments.getChildCount() == 0
                ? View.GONE : View.VISIBLE);
    }

    private void addAttachment(Uri uri) {
        addAttachment(loadAttachmentInfo(uri), true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data == null) {
            return;
        }
        addAttachment(data.getData());
        setDraftNeedsSaving(true);
    }

    private boolean includeQuotedText() {
        return mIncludeQuotedTextCheckBox.isChecked();
    }

    public void onClick(View view) {
        if (handleCommand(view.getId())) {
            return;
        }
        switch (view.getId()) {
            case R.id.attachment_delete:
                onDeleteAttachment(view); // Needs a view; can't be a menu item
                break;
        }
    }

    private void setIncludeQuotedText(boolean include, boolean updateNeedsSaving) {
        mIncludeQuotedTextCheckBox.setChecked(include);
        mQuotedText.setVisibility(mIncludeQuotedTextCheckBox.isChecked()
                ? View.VISIBLE : View.GONE);
        if (updateNeedsSaving) {
            setDraftNeedsSaving(true);
        }
    }

    private void onDeleteAttachment(View delButtonView) {
        /*
         * The view is the delete button, and we have previously set the tag of
         * the delete button to the view that owns it. We don't use parent because the
         * view is very complex and could change in the future.
         */
        View attachmentView = (View) delButtonView.getTag();
        Attachment attachment = (Attachment) attachmentView.getTag();
        mAttachments.removeView(attachmentView);
        updateAttachmentContainer();
        if (attachment.isSaved()) {
            // The following async task for deleting attachments:
            // - can be started multiple times in parallel (to delete multiple attachments).
            // - need not be interrupted on activity exit, instead should run to completion.
            new AsyncTask<Long, Void, Void>() {
                @Override
                protected Void doInBackground(Long... attachmentIds) {
                    mController.deleteAttachment(attachmentIds[0]);
                    return null;
                }
            }.execute(attachment.mId);
        }
        setDraftNeedsSaving(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (handleCommand(item.getItemId())) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean handleCommand(int viewId) {
        switch (viewId) {
        case android.R.id.home:
            onActionBarHomePressed();
            return true;
        case R.id.send:
            onSend();
            return true;
        case R.id.save:
            onSave();
            return true;
        case R.id.discard:
            onDiscard();
            return true;
        case R.id.include_quoted_text:
            // The checkbox is already toggled at this point.
            setIncludeQuotedText(mIncludeQuotedTextCheckBox.isChecked(), true);
            return true;
        case R.id.add_cc_bcc:
            showCcBccFields();
            return true;
        case R.id.add_attachment:
            onAddAttachment();
            return true;
        }
        return false;
    }

    private void onActionBarHomePressed() {
        finish();
        if (isOpenedFromWithinApp()) {
            // If opend from within the app, we just close it.
        } else {
            // Otherwise, need to open the main screen.  Let Welcome do that.
            Welcome.actionStart(this);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_compose_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.save).setEnabled(mSaveEnabled);
        return true;
    }

    /**
     * Set a message body and a signature when the Activity is launched.
     *
     * @param text the message body
     */
    /* package */ void setInitialComposeText(CharSequence text, String signature) {
        int textLength = 0;
        if (text != null) {
            mMessageContentView.append(text);
            textLength = text.length();
        }
        if (!TextUtils.isEmpty(signature)) {
            if (textLength == 0 || text.charAt(textLength - 1) != '\n') {
                mMessageContentView.append("\n");
            }
            mMessageContentView.append(signature);
        }
    }

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
            setInitialComposeText(text, null);
        }

        // Next, convert EXTRA_STREAM into an attachment
        if (Intent.ACTION_SEND.equals(mAction) && intent.hasExtra(Intent.EXTRA_STREAM)) {
            String type = intent.getType();
            Uri stream = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null && type != null) {
                if (MimeUtility.mimeTypeMatches(type,
                        AttachmentUtilities.ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES)) {
                    addAttachment(stream);
                }
            }
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(mAction)
                && intent.hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable parcelable : list) {
                    Uri uri = (Uri) parcelable;
                    if (uri != null) {
                        Attachment attachment = loadAttachmentInfo(uri);
                        if (MimeUtility.mimeTypeMatches(attachment.mMimeType,
                                AttachmentUtilities.ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES)) {
                            addAttachment(attachment, true);
                        }
                    }
                }
            }
        }

        // Finally - expose fields that were filled in but are normally hidden, and set focus
        showCcBccFieldsIfFilled();
        setNewMessageFocus();
        setDraftNeedsSaving(false);
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
            Log.e(Logging.LOG_TAG, e.getMessage() + " while decoding '" + mailToString + "'");
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
            setInitialComposeText(body.get(0), (mAccount != null) ? mAccount.mSignature : null);
        }
    }

    private String decode(String s) throws UnsupportedEncodingException {
        return URLDecoder.decode(s, "UTF-8");
    }

    /**
     * Displays quoted text from the original email
     */
    private void displayQuotedText(String textBody, String htmlBody) {
        // Only use plain text if there is no HTML body
        boolean plainTextFlag = TextUtils.isEmpty(htmlBody);
        String text = plainTextFlag ? textBody : htmlBody;
        if (text != null) {
            text = plainTextFlag ? EmailHtmlUtil.escapeCharacterToDisplay(text) : text;
            // TODO: re-enable EmailHtmlUtil.resolveInlineImage() for HTML
            //    EmailHtmlUtil.resolveInlineImage(getContentResolver(), mAccount,
            //                                     text, message, 0);
            mQuotedTextBar.setVisibility(View.VISIBLE);
            if (mQuotedText != null) {
                mQuotedText.loadDataWithBaseURL("email://", text, "text/html", "utf-8", null);
            }
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
            safeAddAddresses(message.mCc, ourAddress, mCcView, allAddresses);
        }
        showCcBccFieldsIfFilled();
    }

    void processSourceMessageGuarded(Message message, Account account) {
        // Make sure we only do this once (otherwise we'll duplicate addresses!)
        if (!mSourceMessageProcessed) {
            processSourceMessage(message, account);
            mSourceMessageProcessed = true;
        }

        /* The quoted text is displayed in a WebView whose content is not automatically
         * saved/restored by onRestoreInstanceState(), so we need to *always* restore it here,
         * regardless of the value of mSourceMessageProcessed.
         * This only concerns EDIT_DRAFT because after a configuration change we're always
         * in EDIT_DRAFT.
         */
        if (ACTION_EDIT_DRAFT.equals(mAction)) {
            displayQuotedText(message.mTextReply, message.mHtmlReply);
            setIncludeQuotedText((mDraft.mFlags & Message.FLAG_NOT_INCLUDE_QUOTED_TEXT) == 0,
                    false);
        }
    }

    /**
     * Pull out the parts of the now loaded source message and apply them to the new message
     * depending on the type of message being composed.
     * @param message
     */
    /* package */
    void processSourceMessage(Message message, Account account) {
        setDraftNeedsSaving(true);
        final String subject = message.mSubject;
        if (ACTION_REPLY.equals(mAction) || ACTION_REPLY_ALL.equals(mAction)) {
            setupAddressViews(message, account, mToView, mCcView,
                ACTION_REPLY_ALL.equals(mAction));
            if (subject != null && !subject.toLowerCase().startsWith("re:")) {
                mSubjectView.setText("Re: " + subject);
            } else {
                mSubjectView.setText(subject);
            }
            displayQuotedText(message.mText, message.mHtml);
            setIncludeQuotedText(true, false);
            setInitialComposeText(null, (account != null) ? account.mSignature : null);
        } else if (ACTION_FORWARD.equals(mAction)) {
            mSubjectView.setText(subject != null && !subject.toLowerCase().startsWith("fwd:") ?
                    "Fwd: " + subject : subject);
            displayQuotedText(message.mText, message.mHtml);
            setIncludeQuotedText(true, false);
            setInitialComposeText(null, (account != null) ? account.mSignature : null);
        } else if (ACTION_EDIT_DRAFT.equals(mAction)) {
            mSubjectView.setText(subject);
            addAddresses(mToView, Address.unpack(message.mTo));
            Address[] cc = Address.unpack(message.mCc);
            if (cc.length > 0) {
                addAddresses(mCcView, cc);
            }
            Address[] bcc = Address.unpack(message.mBcc);
            if (bcc.length > 0) {
                addAddresses(mBccView, bcc);
            }

            mMessageContentView.setText(message.mText);
            // TODO: re-enable loadAttachments
            // loadAttachments(message, 0);
            setDraftNeedsSaving(false);
        }
        showCcBccFieldsIfFilled();
        setNewMessageFocus();
    }

    /**
     * Set a cursor to the end of a body except a signature
     */
    /* package */ void setMessageContentSelection(String signature) {
        // when selecting the message content, explicitly move IP to the end of the message,
        // so you can quickly resume typing into a draft
        int selection = mMessageContentView.length();
        if (!TextUtils.isEmpty(signature)) {
            int signatureLength = signature.length();
            int estimatedSelection = selection - signatureLength;
            if (estimatedSelection >= 0) {
                CharSequence text = mMessageContentView.getText();
                int i = 0;
                while (i < signatureLength
                       && text.charAt(estimatedSelection + i) == signature.charAt(i)) {
                    ++i;
                }
                if (i == signatureLength) {
                    selection = estimatedSelection;
                    while (selection > 0 && text.charAt(selection - 1) == '\n') {
                        --selection;
                    }
                }
            }
        }
        mMessageContentView.setSelection(selection, selection);
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
            setMessageContentSelection((mAccount != null) ? mAccount.mSignature : null);
        }
    }
}
