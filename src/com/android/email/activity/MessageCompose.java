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

import com.android.email.Account;
import com.android.email.Email;
import com.android.email.EmailAddressAdapter;
import com.android.email.EmailAddressValidator;
import com.android.email.MessagingController;
import com.android.email.MessagingListener;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.Body;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Multipart;
import com.android.email.mail.Part;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeMultipart;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.internet.TextBody;
import com.android.email.mail.store.LocalStore;
import com.android.email.mail.store.LocalStore.LocalAttachmentBody;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.util.Rfc822Tokenizer;
import android.util.Config;
import android.util.Log;
import android.view.KeyEvent;
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
import android.widget.AutoCompleteTextView.Validator;

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MessageCompose extends Activity implements OnClickListener, OnFocusChangeListener {
    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";

    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_FOLDER = "folder";
    private static final String EXTRA_MESSAGE = "message";

    private static final String STATE_KEY_ATTACHMENTS =
        "com.android.email.activity.MessageCompose.attachments";
    private static final String STATE_KEY_CC_SHOWN =
        "com.android.email.activity.MessageCompose.ccShown";
    private static final String STATE_KEY_BCC_SHOWN =
        "com.android.email.activity.MessageCompose.bccShown";
    private static final String STATE_KEY_QUOTED_TEXT_SHOWN =
        "com.android.email.activity.MessageCompose.quotedTextShown";
    private static final String STATE_KEY_SOURCE_MESSAGE_PROCED =
        "com.android.email.activity.MessageCompose.stateKeySourceMessageProced";
    private static final String STATE_KEY_DRAFT_UID =
        "com.android.email.activity.MessageCompose.draftUid";

    private static final int MSG_PROGRESS_ON = 1;
    private static final int MSG_PROGRESS_OFF = 2;
    private static final int MSG_UPDATE_TITLE = 3;
    private static final int MSG_SKIPPED_ATTACHMENTS = 4;
    private static final int MSG_SAVED_DRAFT = 5;
    private static final int MSG_DISCARDED_DRAFT = 6;

    private static final int ACTIVITY_REQUEST_PICK_ATTACHMENT = 1;

    private Account mAccount;
    private String mFolder;
    private String mSourceMessageUid;
    private Message mSourceMessage;
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

    private boolean mDraftNeedsSaving = false;

    /**
     * The draft uid of this message. This is used when saving drafts so that the same draft is
     * overwritten instead of being created anew. This property is null until the first save.
     */
    private String mDraftUid;

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
                case MSG_SAVED_DRAFT:
                    Toast.makeText(
                            MessageCompose.this,
                            getString(R.string.message_saved_toast),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_DISCARDED_DRAFT:
                    Toast.makeText(
                            MessageCompose.this,
                            getString(R.string.message_discarded_toast),
                            Toast.LENGTH_LONG).show();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private Listener mListener = new Listener();
    private EmailAddressAdapter mAddressAdapter;
    private Validator mAddressValidator;

    /**
     * Encapsulates known information about a single attachment.
     */
    private static class Attachment implements Serializable {
        public String name;
        public String contentType;
        public long size;
        public Uri uri;
    }

    /**
     * Compose a new message using the given account. If account is null the default account
     * will be used.
     * @param context
     * @param account
     */
    public static void actionCompose(Context context, Account account) {
       try {
           Intent i = new Intent(context, MessageCompose.class);
           i.putExtra(EXTRA_ACCOUNT, account);
           i.putExtra(EXTRA_FOLDER, account.getDraftsFolderName());
           context.startActivity(i);
       } catch (ActivityNotFoundException anfe) {
           // Swallow it - this is usually a race condition, especially under automated test.
           // (The message composer might have been disabled)
           if (Config.LOGD) {
               Log.d(Email.LOG_TAG, anfe.toString());
           }
       }
    }

    /**
     * Compose a new message as a reply to the given message. If replyAll is true the function
     * is reply all instead of simply reply.
     * @param context
     * @param account
     * @param message
     * @param replyAll
     */
    public static void actionReply(
            Context context,
            Account account,
            Message message,
            boolean replyAll) {
        Intent i = new Intent(context, MessageCompose.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_FOLDER, message.getFolder().getName());
        i.putExtra(EXTRA_MESSAGE, message.getUid());
        if (replyAll) {
            i.setAction(ACTION_REPLY_ALL);
        }
        else {
            i.setAction(ACTION_REPLY);
        }
        context.startActivity(i);
    }

    /**
     * Compose a new message as a forward of the given message.
     * @param context
     * @param account
     * @param message
     */
    public static void actionForward(Context context, Account account, Message message) {
        Intent i = new Intent(context, MessageCompose.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_FOLDER, message.getFolder().getName());
        i.putExtra(EXTRA_MESSAGE, message.getUid());
        i.setAction(ACTION_FORWARD);
        context.startActivity(i);
    }

    /**
     * Continue composition of the given message. This action modifies the way this Activity
     * handles certain actions.
     * Save will attempt to replace the message in the given folder with the updated version.
     * Discard will delete the message from the given folder.
     * @param context
     * @param account
     * @param message
     */
    public static void actionEditDraft(Context context, Account account, Message message) {
        Intent i = new Intent(context, MessageCompose.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_FOLDER, message.getFolder().getName());
        i.putExtra(EXTRA_MESSAGE, message.getUid());
        i.setAction(ACTION_EDIT_DRAFT);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setContentView(R.layout.message_compose);

        mAddressAdapter = new EmailAddressAdapter(this);
        mAddressValidator = new EmailAddressValidator();

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

        mToView.setAdapter(mAddressAdapter);
        mToView.setTokenizer(new Rfc822Tokenizer());
        mToView.setValidator(mAddressValidator);

        mCcView.setAdapter(mAddressAdapter);
        mCcView.setTokenizer(new Rfc822Tokenizer());
        mCcView.setValidator(mAddressValidator);

        mBccView.setAdapter(mAddressAdapter);
        mBccView.setTokenizer(new Rfc822Tokenizer());
        mBccView.setValidator(mAddressValidator);

        mSendButton.setOnClickListener(this);
        mDiscardButton.setOnClickListener(this);
        mSaveButton.setOnClickListener(this);

        mSubjectView.setOnFocusChangeListener(this);
        
        if (savedInstanceState != null) {
            /*
             * This data gets used in onCreate, so grab it here instead of onRestoreIntstanceState
             */
            mSourceMessageProcessed =
                savedInstanceState.getBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, false);
        }

        Intent intent = getIntent();

        String action = intent.getAction();
        
        // Handle the various intents that launch the message composer
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_SENDTO.equals(action) ||
                (Intent.ACTION_SEND.equals(action))) {
            
            // Check first for a valid account
            mAccount = Preferences.getPreferences(this).getDefaultAccount();
            if (mAccount == null) {
                // There are no accounts set up. This should not have happened. Prompt the
                // user to set up an account as an acceptable bailout.
                Accounts.actionShowAccounts(this);
                mDraftNeedsSaving = false;
                finish();
                return;
            }
            
            // Use the fields found in the Intent to prefill as much of the message as possible
            initFromIntent(intent);
        }
        else {
            // Otherwise, handle the internal cases (Message Composer invoked from within app)
            mAccount = (Account) intent.getSerializableExtra(EXTRA_ACCOUNT);
            mFolder = intent.getStringExtra(EXTRA_FOLDER);
            mSourceMessageUid = intent.getStringExtra(EXTRA_MESSAGE);
        }

        if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action) ||
                ACTION_FORWARD.equals(action) || ACTION_EDIT_DRAFT.equals(action)) {
            /*
             * If we need to load the message we add ourself as a message listener here
             * so we can kick it off. Normally we add in onResume but we don't
             * want to reload the message every time the activity is resumed.
             * There is no harm in adding twice.
             */
            MessagingController.getInstance(getApplication()).addListener(mListener);
            MessagingController.getInstance(getApplication()).loadMessageForView(
                    mAccount,
                    mFolder,
                    mSourceMessageUid,
                    mListener);
        }

        updateTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        MessagingController.getInstance(getApplication()).addListener(mListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        saveIfNeeded();
        MessagingController.getInstance(getApplication()).removeListener(mListener);
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
    }

    /**
     * The framework handles most of the fields, but we need to handle stuff that we
     * dynamically show and hide:
     * Attachment list,
     * Cc field,
     * Bcc field,
     * Quoted text,
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        saveIfNeeded();
        ArrayList<Uri> attachments = new ArrayList<Uri>();
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            View view = mAttachments.getChildAt(i);
            Attachment attachment = (Attachment) view.getTag();
            attachments.add(attachment.uri);
        }
        outState.putParcelableArrayList(STATE_KEY_ATTACHMENTS, attachments);
        outState.putBoolean(STATE_KEY_CC_SHOWN, mCcView.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_BCC_SHOWN, mBccView.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_QUOTED_TEXT_SHOWN,
                mQuotedTextBar.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_SOURCE_MESSAGE_PROCED, mSourceMessageProcessed);
        outState.putString(STATE_KEY_DRAFT_UID, mDraftUid);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        ArrayList<Parcelable> attachments = 
                savedInstanceState.getParcelableArrayList(STATE_KEY_ATTACHMENTS);
        mAttachments.removeAllViews();
        for (Parcelable p : attachments) {
            Uri uri = (Uri) p;
            addAttachment(uri);
        }

        mCcView.setVisibility(savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN) ?
                View.VISIBLE : View.GONE);
        mBccView.setVisibility(savedInstanceState.getBoolean(STATE_KEY_BCC_SHOWN) ?
                View.VISIBLE : View.GONE);
        mQuotedTextBar.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        mQuotedText.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN) ?
                View.VISIBLE : View.GONE);
        mDraftUid = savedInstanceState.getString(STATE_KEY_DRAFT_UID);
        mDraftNeedsSaving = false;
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

    private Address[] getAddresses(MultiAutoCompleteTextView view) {
        Address[] addresses = Address.parse(view.getText().toString().trim());
        return addresses;
    }

    private MimeMessage createMessage() throws MessagingException {
        MimeMessage message = new MimeMessage();
        message.setSentDate(new Date());
        Address from = new Address(mAccount.getEmail(), mAccount.getName());
        message.setFrom(from);
        message.setRecipients(RecipientType.TO, getAddresses(mToView));
        message.setRecipients(RecipientType.CC, getAddresses(mCcView));
        message.setRecipients(RecipientType.BCC, getAddresses(mBccView));
        message.setSubject(mSubjectView.getText().toString());
        
        // Preserve Message-ID header if found
        // This makes sure that multiply-saved drafts are identified as the same message
        if (mSourceMessage != null && mSourceMessage instanceof MimeMessage) {
            String messageIdHeader = ((MimeMessage)mSourceMessage).getMessageId();
            if (messageIdHeader != null) {
                message.setMessageId(messageIdHeader);
            }
        }

        /*
         * Build the Body that will contain the text of the message. We'll decide where to
         * include it later.
         */

        String text = mMessageContentView.getText().toString();

        if (mQuotedTextBar.getVisibility() == View.VISIBLE) {
            String action = getIntent().getAction();
            String quotedText = null;
            Part part = MimeUtility.findFirstPartByMimeType(mSourceMessage,
                    "text/plain");
            if (part != null) {
                quotedText = MimeUtility.getTextFromPart(part);
            }
            if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action)) {
                text += String.format(
                        getString(R.string.message_compose_reply_header_fmt),
                        Address.toString(mSourceMessage.getFrom()));
                if (quotedText != null) {
                    text += quotedText.replaceAll("(?m)^", ">");
                }
            }
            else if (ACTION_FORWARD.equals(action)) {
                text += String.format(
                        getString(R.string.message_compose_fwd_header_fmt),
                        mSourceMessage.getSubject(),
                        Address.toString(mSourceMessage.getFrom()),
                        Address.toString(
                                mSourceMessage.getRecipients(RecipientType.TO)),
                        Address.toString(
                                mSourceMessage.getRecipients(RecipientType.CC)));
                if (quotedText != null) {
                    text += quotedText;
                }
            }
        }

        TextBody body = new TextBody(text);

        if (mAttachments.getChildCount() > 0) {
            /*
             * The message has attachments that need to be included. First we add the part
             * containing the text that will be sent and then we include each attachment.
             */

            MimeMultipart mp;

            mp = new MimeMultipart();
            mp.addBodyPart(new MimeBodyPart(body, "text/plain"));

            for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
                Attachment attachment = (Attachment) mAttachments.getChildAt(i).getTag();
                MimeBodyPart bp = new MimeBodyPart(
                        new LocalStore.LocalAttachmentBody(attachment.uri, getApplication()));
                bp.setHeader(MimeHeader.HEADER_CONTENT_TYPE, String.format("%s;\n name=\"%s\"",
                        attachment.contentType,
                        attachment.name));
                bp.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
                bp.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                        String.format("attachment;\n filename=\"%s\"",
                        attachment.name));
                mp.addBodyPart(bp);
            }

            message.setBody(mp);
        }
        else {
            /*
             * No attachments to include, just stick the text body in the message and call
             * it good.
             */
            message.setBody(body);
        }

        return message;
    }

    private void sendOrSaveMessage(boolean save) {
        /*
         * Create the message from all the data the user has entered.
         */
        MimeMessage message;
        try {
            message = createMessage();
        }
        catch (MessagingException me) {
            Log.e(Email.LOG_TAG, "Failed to create new message for send or save.", me);
            throw new RuntimeException("Failed to create a new message for send or save.", me);
        }

        if (save) {
            /*
             * Save a draft
             */
            if (mDraftUid != null) {
                message.setUid(mDraftUid);
            }
            else if (ACTION_EDIT_DRAFT.equals(getIntent().getAction())) {
                /*
                 * We're saving a previously saved draft, so update the new message's uid
                 * to the old message's uid.
                 */
                message.setUid(mSourceMessageUid);
            }
            MessagingController.getInstance(getApplication()).saveDraft(mAccount, message);
            mDraftUid = message.getUid();

            // Don't display the toast if the user is just changing the orientation
            if ((getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION) == 0) {
                mHandler.sendEmptyMessage(MSG_SAVED_DRAFT);
            }
        }
        else {
            /*
             * Send the message
             * If the source message is in other folder than draft, it should not be deleted while
             * sending message.
             */
            if (ACTION_EDIT_DRAFT.equals(getIntent().getAction())
                    && mSourceMessageUid != null
                    && mFolder.equals(mAccount.getDraftsFolderName())) {
                /*
                 * We're sending a previously saved draft, so delete the old draft first.
                 */
                MessagingController.getInstance(getApplication()).deleteMessage(
                        mAccount,
                        mFolder,
                        mSourceMessage,
                        null);
            }
            MessagingController.getInstance(getApplication()).sendMessage(mAccount, message, null);
        }
    }

    private void saveIfNeeded() {
        if (!mDraftNeedsSaving) {
            return;
        }
        mDraftNeedsSaving = false;
        sendOrSaveMessage(true);
    }

    private void onSend() {
        if (getAddresses(mToView).length == 0 &&
                getAddresses(mCcView).length == 0 &&
                getAddresses(mBccView).length == 0) {
            mToView.setError(getString(R.string.message_compose_error_no_recipients));
            Toast.makeText(this, getString(R.string.message_compose_error_no_recipients),
                    Toast.LENGTH_LONG).show();
            return;
        }
        sendOrSaveMessage(false);
        mDraftNeedsSaving = false;
        finish();
    }

    private void onDiscard() {
        if (mSourceMessageUid != null) {
            if (ACTION_EDIT_DRAFT.equals(getIntent().getAction()) && mSourceMessageUid != null) {
                MessagingController.getInstance(getApplication()).deleteMessage(
                        mAccount,
                        mFolder,
                        mSourceMessage,
                        null);
            }
        }
        mHandler.sendEmptyMessage(MSG_DISCARDED_DRAFT);
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

    private void addAttachment(Uri uri) {
        addAttachment(uri, -1, null);
    }

    private void addAttachment(Uri uri, int size, String name) {
        ContentResolver contentResolver = getContentResolver();

        String contentType = contentResolver.getType(uri);

        if (contentType == null) {
            contentType = "";
        }

        Attachment attachment = new Attachment();
        attachment.name = name;
        attachment.contentType = contentType;
        attachment.size = size;
        attachment.uri = uri;

        if (attachment.size == -1 || attachment.name == null) {
            Cursor metadataCursor = contentResolver.query(
                    uri,
                    new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                    null,
                    null,
                    null);
            if (metadataCursor != null) {
                try {
                    if (metadataCursor.moveToFirst()) {
                        if (attachment.name == null) {
                            attachment.name = metadataCursor.getString(0);
                        }
                        if (attachment.size == -1) {
                            attachment.size = metadataCursor.getInt(1);
                        }
                    }
                } finally {
                    metadataCursor.close();
                }
            }
        }

        if (attachment.name == null) {
            attachment.name = uri.getLastPathSegment();
        }

        // Before attaching the attachment, make sure it meets any other pre-attach criteria
        if (attachment.size > Email.MAX_ATTACHMENT_UPLOAD_SIZE) {
            Toast.makeText(this, R.string.message_compose_attachment_size, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        
        View view = getLayoutInflater().inflate(
                R.layout.message_compose_attachment,
                mAttachments,
                false);
        TextView nameView = (TextView)view.findViewById(R.id.attachment_name);
        ImageButton delete = (ImageButton)view.findViewById(R.id.attachment_delete);
        nameView.setText(attachment.name);
        delete.setOnClickListener(this);
        delete.setTag(view);
        view.setTag(attachment);
        mAttachments.addView(view);
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
    private boolean loadAttachments(Part part, int depth) throws MessagingException {
        if (part.getBody() instanceof Multipart) {
            Multipart mp = (Multipart) part.getBody();
            boolean ret = true;
            for (int i = 0, count = mp.getCount(); i < count; i++) {
                if (!loadAttachments(mp.getBodyPart(i), depth + 1)) {
                    ret = false;
                }
            }
            return ret;
        } else {
            String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
            String name = MimeUtility.getHeaderParameter(contentType, "name");
            if (name != null) {
                Body body = part.getBody();
                if (body != null && body instanceof LocalAttachmentBody) {
                    final Uri uri = ((LocalAttachmentBody) body).getContentUri();
                    mHandler.post(new Runnable() {
                        public void run() {
                            addAttachment(uri);
                        }
                    });
                }
                else {
                    return false;
                }
            }
            return true;
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

    /**
     * Pull out the parts of the now loaded source message and apply them to the new message
     * depending on the type of message being composed.
     * @param message
     */
    /* package */ /**
     * @param message
     */
    void processSourceMessage(Message message) {
        String action = getIntent().getAction();
        if (ACTION_REPLY.equals(action) || ACTION_REPLY_ALL.equals(action)) {
            try {
                if (message.getSubject() != null &&
                        !message.getSubject().toLowerCase().startsWith("re:")) {
                    mSubjectView.setText("Re: " + message.getSubject());
                }
                else {
                    mSubjectView.setText(message.getSubject());
                }
                /*
                 * If a reply-to was included with the message use that, otherwise use the from
                 * or sender address.
                 */
                Address[] replyToAddresses;
                if (message.getReplyTo().length > 0) {
                    addAddresses(mToView, replyToAddresses = message.getReplyTo());
                }
                else {
                    addAddresses(mToView, replyToAddresses = message.getFrom());
                }
                if (ACTION_REPLY_ALL.equals(action)) {
                    for (Address address : message.getRecipients(RecipientType.TO)) {
                        if (!address.getAddress().equalsIgnoreCase(mAccount.getEmail())) {
                            addAddress(mToView, address.toString());
                        }
                    }
                    if (message.getRecipients(RecipientType.CC).length > 0) {
                        for (Address address : message.getRecipients(RecipientType.CC)) {
                            if (!Utility.arrayContains(replyToAddresses, address)) {
                                addAddress(mCcView, address.toString());
                            }
                        }
                        mCcView.setVisibility(View.VISIBLE);
                    }
                }

                Boolean plainTextFlag = false;
                Part part = MimeUtility.findFirstPartByMimeType(message, "text/html");
                if (part == null) {
                    part = MimeUtility.findFirstPartByMimeType(message, "text/plain");
                    plainTextFlag = true;
                }

                if (part != null) {
                    String text = MimeUtility.getTextFromPart(part);
                    if (text != null) {
                        if (!plainTextFlag) {
                            text = EmailHtmlUtil.resolveInlineImage(
                                    getContentResolver(), mAccount, text, message, 0);
                        } else {
                            text = EmailHtmlUtil.escapeCharacterToDisplay(text);
                        }
                        mQuotedTextBar.setVisibility(View.VISIBLE);
                        mQuotedText.setVisibility(View.VISIBLE);
                        mQuotedText.loadDataWithBaseURL("email://", text, "text/html",
                                "utf-8", null);
                    }
                }
            }
            catch (MessagingException me) {
                /*
                 * This really should not happen at this point but if it does it's okay.
                 * The user can continue composing their message.
                 */
            }
        }
        else if (ACTION_FORWARD.equals(action)) {
            try {
                if (message.getSubject() != null &&
                        !message.getSubject().toLowerCase().startsWith("fwd:")) {
                    mSubjectView.setText("Fwd: " + message.getSubject());
                }
                else {
                    mSubjectView.setText(message.getSubject());
                }


                Boolean plainTextFlag = false;
                Part part = MimeUtility.findFirstPartByMimeType(message, "text/html");
                if (part == null) {
                    part = MimeUtility.findFirstPartByMimeType(message, "text/plain");
                    plainTextFlag = true;
                }

                if (part != null) {
                    String text = MimeUtility.getTextFromPart(part);
                    if (text != null) {
                        if (!plainTextFlag) {
                            text = EmailHtmlUtil.resolveInlineImage(
                                    getContentResolver(), mAccount, text, message, 0);
                        } else {
                            text = EmailHtmlUtil.escapeCharacterToDisplay(text);
                        }
                        mQuotedTextBar.setVisibility(View.VISIBLE);
                        mQuotedText.setVisibility(View.VISIBLE);
                        mQuotedText.loadDataWithBaseURL("email://", text, "text/html",
                                "utf-8", null);
                    }
                }
                if (!mSourceMessageProcessed) {
                    if (!loadAttachments(message, 0)) {
                        mHandler.sendEmptyMessage(MSG_SKIPPED_ATTACHMENTS);
                    }
                }
            }
            catch (MessagingException me) {
                /*
                 * This really should not happen at this point but if it does it's okay.
                 * The user can continue composing their message.
                 */
            }
        }
        else if (ACTION_EDIT_DRAFT.equals(action)) {
            try {
                mSubjectView.setText(message.getSubject());
                addAddresses(mToView, message.getRecipients(RecipientType.TO));
                if (message.getRecipients(RecipientType.CC).length > 0) {
                    addAddresses(mCcView, message.getRecipients(RecipientType.CC));
                    mCcView.setVisibility(View.VISIBLE);
                }
                if (message.getRecipients(RecipientType.BCC).length > 0) {
                    addAddresses(mBccView, message.getRecipients(RecipientType.BCC));
                    mBccView.setVisibility(View.VISIBLE);
                }
                Part part = MimeUtility.findFirstPartByMimeType(message, "text/plain");
                if (part != null) {
                    String text = MimeUtility.getTextFromPart(part);
                    mMessageContentView.setText(text);
                }
                if (!mSourceMessageProcessed) {
                    loadAttachments(message, 0);
                }
            }
            catch (MessagingException me) {
                // TODO
            }
        }
        
        setNewMessageFocus();
        
        mSourceMessageProcessed = true;
        mDraftNeedsSaving = mFolder != null && !mFolder.equals(mAccount.getDraftsFolderName());
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

    class Listener extends MessagingListener {
        @Override
        public void loadMessageForViewStarted(Account account, String folder, String uid) {
            mHandler.sendEmptyMessage(MSG_PROGRESS_ON);
        }

        @Override
        public void loadMessageForViewFinished(Account account, String folder, String uid,
                Message message) {
            mHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
        }

        @Override
        public void loadMessageForViewBodyAvailable(Account account, String folder, String uid,
                final Message message) {
            mSourceMessage = message;
            runOnUiThread(new Runnable() {
                public void run() {
                    processSourceMessage(message);
                }
            });
        }

        @Override
        public void loadMessageForViewFailed(Account account, String folder, String uid,
                final String message) {
            mHandler.sendEmptyMessage(MSG_PROGRESS_OFF);
            // TODO show network error
        }

        @Override
        public void messageUidChanged(
                Account account,
                String folder,
                String oldUid,
                String newUid) {
            if (account.equals(mAccount)
                    && (folder.equals(mFolder)
                            || (mFolder == null
                                    && folder.equals(mAccount.getDraftsFolderName())))) {
                if (oldUid.equals(mDraftUid)) {
                    mDraftUid = newUid;
                }
                if (oldUid.equals(mSourceMessageUid)) {
                    mSourceMessageUid = newUid;
                }
                if (mSourceMessage != null && (oldUid.equals(mSourceMessage.getUid()))) {
                    mSourceMessage.setUid(newUid);
                }
            }
        }
    }
}
