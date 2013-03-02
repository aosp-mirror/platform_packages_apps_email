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

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.FragmentTransaction;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
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
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.MultiAutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.common.contacts.DataUsageStatUpdater;
import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.EmailAddressAdapter;
import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.RecipientAdapter;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.QuickResponseColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.ex.chips.AccountSpecifier;
import com.android.ex.chips.ChipsUtil;
import com.android.ex.chips.RecipientEditTextView;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;


/**
 * Activity to compose a message.
 *
 * TODO Revive shortcuts command for removed menu options.
 * C: add cc/bcc
 * N: add attachment
 */
public class MessageCompose extends Activity implements OnClickListener, OnFocusChangeListener,
        DeleteMessageConfirmationDialog.Callback, InsertQuickResponseDialog.Callback {

    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";

    private static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final String EXTRA_MESSAGE_ID = "message_id";
    /** If the intent is sent from the email app itself, it should have this boolean extra. */
    public static final String EXTRA_FROM_WITHIN_APP = "from_within_app";
    /** If the intent is sent from thw widget. */
    public static final String EXTRA_FROM_WIDGET = "from_widget";

    private static final String STATE_KEY_CC_SHOWN =
        "com.android.email.activity.MessageCompose.ccShown";
    private static final String STATE_KEY_QUOTED_TEXT_SHOWN =
        "com.android.email.activity.MessageCompose.quotedTextShown";
    private static final String STATE_KEY_DRAFT_ID =
        "com.android.email.activity.MessageCompose.draftId";
    private static final String STATE_KEY_LAST_SAVE_TASK_ID =
        "com.android.email.activity.MessageCompose.requestId";
    private static final String STATE_KEY_ACTION =
        "com.android.email.activity.MessageCompose.action";

    private static final int ACTIVITY_REQUEST_PICK_ATTACHMENT = 1;

    private static final String[] ATTACHMENT_META_SIZE_PROJECTION = {
        OpenableColumns.SIZE
    };
    private static final int ATTACHMENT_META_SIZE_COLUMN_SIZE = 0;

    /**
     * A registry of the active tasks used to save messages.
     */
    private static final ConcurrentHashMap<Long, SendOrSaveMessageTask> sActiveSaveTasks =
            new ConcurrentHashMap<Long, SendOrSaveMessageTask>();

    private static long sNextSaveTaskId = 1;

    /**
     * The ID of the latest save or send task requested by this Activity.
     */
    private long mLastSaveTaskId = -1;

    private Account mAccount;

    /**
     * The contents of the current message being edited. This is not always in sync with what's
     * on the UI. {@link #updateMessage(Message, Account, boolean, boolean)} must be called to sync
     * the UI values into this object.
     */
    private Message mDraft = new Message();

    /**
     * A collection of attachments the user is currently wanting to attach to this message.
     */
    private final ArrayList<Attachment> mAttachments = new ArrayList<Attachment>();

    /**
     * The source message for a reply, reply all, or forward. This is asynchronously loaded.
     */
    private Message mSource;

    /**
     * The attachments associated with the source attachments. Usually included in a forward.
     */
    private ArrayList<Attachment> mSourceAttachments = new ArrayList<Attachment>();

    /**
     * The action being handled by this activity. This is initially populated from the
     * {@link Intent}, but can switch between reply/reply all/forward where appropriate.
     * This value is nullable (a null value indicating a regular "compose").
     */
    private String mAction;

    private TextView mFromView;
    private MultiAutoCompleteTextView mToView;
    private MultiAutoCompleteTextView mCcView;
    private MultiAutoCompleteTextView mBccView;
    private View mCcBccContainer;
    private EditText mSubjectView;
    private EditText mMessageContentView;
    private View mAttachmentContainer;
    private ViewGroup mAttachmentContentView;
    private View mQuotedTextArea;
    private CheckBox mIncludeQuotedTextCheckBox;
    private WebView mQuotedText;
    private ActionSpinnerAdapter mActionSpinnerAdapter;

    private Controller mController;
    private boolean mDraftNeedsSaving;
    private boolean mMessageLoaded;
    private boolean mInitiallyEmpty;
    private boolean mPickingAttachment = false;
    private Boolean mQuickResponsesAvailable = true;
    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    private AccountSpecifier mAddressAdapterTo;
    private AccountSpecifier mAddressAdapterCc;
    private AccountSpecifier mAddressAdapterBcc;

    /**
     * Watches the to, cc, bcc, subject, and message body fields.
     */
    private final TextWatcher mWatcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start,
                                      int before, int after) { }

        @Override
        public void onTextChanged(CharSequence s, int start,
                                      int before, int count) {
            setMessageChanged(true);
        }

        @Override
        public void afterTextChanged(android.text.Editable s) { }
    };

    private static Intent getBaseIntent(Context context) {
        return new Intent(context, MessageCompose.class);
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
     * Creates an {@link Intent} that can start the message compose activity from the main Email
     * activity. This should not be used for Intents to be fired from outside of the main Email
     * activity, such as from widgets, as the behavior of the compose screen differs subtly from
     * those cases.
     */
    private static Intent getMainAppIntent(Context context, long accountId) {
        Intent result = getMessageComposeIntent(context, accountId);
        result.putExtra(EXTRA_FROM_WITHIN_APP, true);
        return result;
    }

    /**
     * Compose a new message using the given account. If account is {@link Account#NO_ACCOUNT}
     * the default account will be used.
     * This should only be called from the main Email application.
     * @param context
     * @param accountId
     */
    public static void actionCompose(Context context, long accountId) {
       try {
           Intent i = getMainAppIntent(context, accountId);
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
     * This should only be called from the main Email application.
     * @param context
     * @param uriString
     * @param accountId
     * @return true if startActivity() succeeded
     */
    public static boolean actionCompose(Context context, String uriString, long accountId) {
        try {
            Intent i = getMainAppIntent(context, accountId);
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

    /**
     * Starts a compose activity with a message as a reference message (e.g. for reply or forward).
     */
    private static void startActivityWithMessage(Context context, String action, long messageId) {
        Intent i = getBaseIntent(context);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.setAction(action);
        context.startActivity(i);
    }

    private void setAccount(Intent intent) {
        long accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        Account account = null;
        if (accountId != Account.NO_ACCOUNT) {
            // User supplied an account; make sure it exists
            account = Account.restoreAccountWithId(this, accountId);
            // Deleted account is no account...
            if (account == null) {
                accountId = Account.NO_ACCOUNT;
            }
        }
        // If we still have no account, try the default
        if (accountId == Account.NO_ACCOUNT) {
            accountId = Account.getDefaultAccountId(this);
            if (accountId != Account.NO_ACCOUNT) {
                // Make sure it exists...
                account = Account.restoreAccountWithId(this, accountId);
                // Deleted account is no account...
                if (account == null) {
                    accountId = Account.NO_ACCOUNT;
                }
            }
        }
        // If we can't find an account, set one up
        if (accountId == Account.NO_ACCOUNT || account == null) {
            // There are no accounts set up. This should not have happened. Prompt the
            // user to set up an account as an acceptable bailout.
            Welcome.actionStart(this);
            finish();
        } else {
            setAccount(account);
        }
    }

    private void setAccount(Account account) {
        if (account == null) {
            Utility.showToast(this, R.string.widget_no_accounts);
            Log.d(Logging.LOG_TAG, "The account has been deleted, force finish it");
            finish();
        }
        mAccount = account;
        mFromView.setText(account.mEmailAddress);
        mAddressAdapterTo
                .setAccount(new android.accounts.Account(account.mEmailAddress, "unknown"));
        mAddressAdapterCc
                .setAccount(new android.accounts.Account(account.mEmailAddress, "unknown"));
        mAddressAdapterBcc
                .setAccount(new android.accounts.Account(account.mEmailAddress, "unknown"));

        new QuickResponseChecker(mTaskTracker).executeParallel((Void) null);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.message_compose);

        mController = Controller.getInstance(getApplication());
        initViews();

        // Show the back arrow on the action bar.
        getActionBar().setDisplayOptions(
                ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);

        if (savedInstanceState != null) {
            long draftId = savedInstanceState.getLong(STATE_KEY_DRAFT_ID, Message.NOT_SAVED);
            long existingSaveTaskId = savedInstanceState.getLong(STATE_KEY_LAST_SAVE_TASK_ID, -1);
            setAction(savedInstanceState.getString(STATE_KEY_ACTION));
            SendOrSaveMessageTask existingSaveTask = sActiveSaveTasks.get(existingSaveTaskId);

            if ((draftId != Message.NOT_SAVED) || (existingSaveTask != null)) {
                // Restoring state and there was an existing message saved or in the process of
                // being saved.
                resumeDraft(draftId, existingSaveTask, false /* don't restore views */);
            } else {
                // Restoring state but there was nothing saved - probably means the user rotated
                // the device immediately - just use the Intent.
                resolveIntent(getIntent());
            }
        } else {
            Intent intent = getIntent();
            setAction(intent.getAction());
            resolveIntent(intent);
        }
    }

    private void resolveIntent(Intent intent) {
        if (Intent.ACTION_VIEW.equals(mAction)
                || Intent.ACTION_SENDTO.equals(mAction)
                || Intent.ACTION_SEND.equals(mAction)
                || Intent.ACTION_SEND_MULTIPLE.equals(mAction)) {
            initFromIntent(intent);
            setMessageChanged(true);
            setMessageLoaded(true);
        } else if (ACTION_REPLY.equals(mAction)
                || ACTION_REPLY_ALL.equals(mAction)
                || ACTION_FORWARD.equals(mAction)) {
            long sourceMessageId = getIntent().getLongExtra(EXTRA_MESSAGE_ID, Message.NOT_SAVED);
            loadSourceMessage(sourceMessageId, true);

        } else if (ACTION_EDIT_DRAFT.equals(mAction)) {
            // Assert getIntent.hasExtra(EXTRA_MESSAGE_ID)
            long draftId = getIntent().getLongExtra(EXTRA_MESSAGE_ID, Message.NOT_SAVED);
            resumeDraft(draftId, null, true /* restore views */);

        } else {
            // Normal compose flow for a new message.
            setAccount(intent);
            setInitialComposeText(null, getAccountSignature(mAccount));
            setMessageLoaded(true);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        // Temporarily disable onTextChanged listeners while restoring the fields
        removeListeners();
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState.getBoolean(STATE_KEY_CC_SHOWN)) {
            showCcBccFields();
        }
        mQuotedTextArea.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN)
                ? View.VISIBLE : View.GONE);
        mQuotedText.setVisibility(savedInstanceState.getBoolean(STATE_KEY_QUOTED_TEXT_SHOWN)
                ? View.VISIBLE : View.GONE);
        addListeners();
    }

    // needed for unit tests
    @Override
    public void setIntent(Intent intent) {
        super.setIntent(intent);
        setAction(intent.getAction());
    }

    private void setQuickResponsesAvailable(boolean quickResponsesAvailable) {
        if (mQuickResponsesAvailable != quickResponsesAvailable) {
            mQuickResponsesAvailable = quickResponsesAvailable;
            invalidateOptionsMenu();
        }
    }

    /**
     * Given an accountId and context, finds if the database has any QuickResponse
     * entries and returns the result to the Callback.
     */
    private class QuickResponseChecker extends EmailAsyncTask<Void, Void, Boolean> {
        public QuickResponseChecker(EmailAsyncTask.Tracker tracker) {
            super(tracker);
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return EmailContent.count(MessageCompose.this, QuickResponse.CONTENT_URI,
                    QuickResponseColumns.ACCOUNT_KEY + "=?",
                    new String[] {Long.toString(mAccount.mId)}) > 0;
        }

        @Override
        protected void onSuccess(Boolean quickResponsesAvailable) {
            setQuickResponsesAvailable(quickResponsesAvailable);
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

        // If activity paused and quick responses are removed/added, possibly update options menu
        if (mAccount != null) {
            new QuickResponseChecker(mTaskTracker).executeParallel((Void) null);
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

        mTaskTracker.cancellAllInterrupt();

        if (mAddressAdapterTo != null && mAddressAdapterTo instanceof EmailAddressAdapter) {
            ((EmailAddressAdapter) mAddressAdapterTo).close();
        }
        if (mAddressAdapterCc != null && mAddressAdapterCc instanceof EmailAddressAdapter) {
            ((EmailAddressAdapter) mAddressAdapterCc).close();
        }
        if (mAddressAdapterBcc != null && mAddressAdapterBcc instanceof EmailAddressAdapter) {
            ((EmailAddressAdapter) mAddressAdapterBcc).close();
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

        long draftId = mDraft.mId;
        if (draftId != Message.NOT_SAVED) {
            outState.putLong(STATE_KEY_DRAFT_ID, draftId);
        }
        outState.putBoolean(STATE_KEY_CC_SHOWN, mCcBccContainer.getVisibility() == View.VISIBLE);
        outState.putBoolean(STATE_KEY_QUOTED_TEXT_SHOWN,
                mQuotedTextArea.getVisibility() == View.VISIBLE);
        outState.putString(STATE_KEY_ACTION, mAction);

        // If there are any outstanding save requests, ensure that it's noted in case it hasn't
        // finished by the time the activity is restored.
        outState.putLong(STATE_KEY_LAST_SAVE_TASK_ID, mLastSaveTaskId);
    }

    @Override
    public void onBackPressed() {
        onBack(true /* systemKey */);
    }

    /**
     * Whether or not the current message being edited has a source message (i.e. is a reply,
     * or forward) that is loaded.
     */
    private boolean hasSourceMessage() {
        return mSource != null;
    }

    /**
     * @return true if the activity was opened by the email app itself.
     */
    private boolean isOpenedFromWithinApp() {
        Intent i = getIntent();
        return (i != null && i.getBooleanExtra(EXTRA_FROM_WITHIN_APP, false));
    }

    private boolean isOpenedFromWidget() {
        Intent i = getIntent();
        return (i != null && i.getBooleanExtra(EXTRA_FROM_WIDGET, false));
    }

    /**
     * Sets message as loaded and then initializes the TextWatchers.
     * @param isLoaded - value to which to set mMessageLoaded
     */
    private void setMessageLoaded(boolean isLoaded) {
        if (mMessageLoaded != isLoaded) {
            mMessageLoaded = isLoaded;
            addListeners();
            mInitiallyEmpty = areViewsEmpty();
        }
    }

    private void setMessageChanged(boolean messageChanged) {
        boolean needsSaving = messageChanged && !(mInitiallyEmpty && areViewsEmpty());

        if (mDraftNeedsSaving != needsSaving) {
            mDraftNeedsSaving = needsSaving;
            invalidateOptionsMenu();
        }
    }

    /**
     * @return whether or not all text fields are empty (i.e. the entire compose message is empty)
     */
    private boolean areViewsEmpty() {
        return (mToView.length() == 0)
                && (mCcView.length() == 0)
                && (mBccView.length() == 0)
                && (mSubjectView.length() == 0)
                && isBodyEmpty()
                && mAttachments.isEmpty();
    }

    private boolean isBodyEmpty() {
        return (mMessageContentView.length() == 0)
                || mMessageContentView.getText()
                        .toString().equals("\n" + getAccountSignature(mAccount));
    }

    public void setFocusShifter(int fromViewId, final int targetViewId) {
        View label = findViewById(fromViewId); // xlarge only
        if (label != null) {
            final View target = UiUtilities.getView(this, targetViewId);
            label.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    target.requestFocus();
                }
            });
        }
    }

    /**
     * An {@link InputFilter} that implements special address cleanup rules.
     * The first space key entry following an "@" symbol that is followed by any combination
     * of letters and symbols, including one+ dots and zero commas, should insert an extra
     * comma (followed by the space).
     */
    @VisibleForTesting
    static final InputFilter RECIPIENT_FILTER = new InputFilter() {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest,
                int dstart, int dend) {

            // Quick check - did they enter a single space?
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

    private void initViews() {
        ViewGroup toParent = UiUtilities.getViewOrNull(this, R.id.to_content);
        if (toParent != null) {
            mToView = (MultiAutoCompleteTextView) toParent.findViewById(R.id.to);
            ViewGroup ccParent, bccParent;
            ccParent = (ViewGroup) findViewById(R.id.cc_content);
            mCcView = (MultiAutoCompleteTextView) ccParent.findViewById(R.id.cc);
            bccParent = (ViewGroup) findViewById(R.id.bcc_content);
            mBccView = (MultiAutoCompleteTextView) bccParent.findViewById(R.id.bcc);
        } else {
            mToView = UiUtilities.getView(this, R.id.to);
            mCcView = UiUtilities.getView(this, R.id.cc);
            mBccView = UiUtilities.getView(this, R.id.bcc);
        }

        mFromView = UiUtilities.getView(this, R.id.from);
        mCcBccContainer = UiUtilities.getView(this, R.id.cc_bcc_wrapper);
        mSubjectView = UiUtilities.getView(this, R.id.subject);
        mMessageContentView = UiUtilities.getView(this, R.id.body_text);
        mAttachmentContentView = UiUtilities.getView(this, R.id.attachments);
        mAttachmentContainer = UiUtilities.getView(this, R.id.attachment_container);
        mQuotedTextArea = UiUtilities.getView(this, R.id.quoted_text_area);
        mIncludeQuotedTextCheckBox = UiUtilities.getView(this, R.id.include_quoted_text);
        mQuotedText = UiUtilities.getView(this, R.id.quoted_text);

        InputFilter[] recipientFilters = new InputFilter[] { RECIPIENT_FILTER };

        // NOTE: assumes no other filters are set
        mToView.setFilters(recipientFilters);
        mCcView.setFilters(recipientFilters);
        mBccView.setFilters(recipientFilters);

        /*
         * We set this to invisible by default. Other methods will turn it back on if it's
         * needed.
         */
        mQuotedTextArea.setVisibility(View.GONE);
        setIncludeQuotedText(false, false);

        mIncludeQuotedTextCheckBox.setOnClickListener(this);

        EmailAddressValidator addressValidator = new EmailAddressValidator();

        setupAddressAdapters();
        mToView.setTokenizer(new Rfc822Tokenizer());
        mToView.setValidator(addressValidator);

        mCcView.setTokenizer(new Rfc822Tokenizer());
        mCcView.setValidator(addressValidator);

        mBccView.setTokenizer(new Rfc822Tokenizer());
        mBccView.setValidator(addressValidator);

        final View addCcBccView = UiUtilities.getViewOrNull(this, R.id.add_cc_bcc);
        if (addCcBccView != null) {
            // Tablet view.
            addCcBccView.setOnClickListener(this);
        }

        final View addAttachmentView = UiUtilities.getViewOrNull(this, R.id.add_attachment);
        if (addAttachmentView != null) {
            // Tablet view.
            addAttachmentView.setOnClickListener(this);
        }

        setFocusShifter(R.id.to_label, R.id.to);
        setFocusShifter(R.id.cc_label, R.id.cc);
        setFocusShifter(R.id.bcc_label, R.id.bcc);
        setFocusShifter(R.id.composearea_tap_trap_bottom, R.id.body_text);

        mMessageContentView.setOnFocusChangeListener(this);

        updateAttachmentContainer();
        mToView.requestFocus();
    }

    /**
     * Initializes listeners. Should only be called once initializing of views is complete to
     * avoid unnecessary draft saving.
     */
    private void addListeners() {
        mToView.addTextChangedListener(mWatcher);
        mCcView.addTextChangedListener(mWatcher);
        mBccView.addTextChangedListener(mWatcher);
        mSubjectView.addTextChangedListener(mWatcher);
        mMessageContentView.addTextChangedListener(mWatcher);
    }

    /**
     * Removes listeners from the user-editable fields. Can be used to temporarily disable them
     * while resetting fields (such as when changing from reply to reply all) to avoid
     * unnecessary saving.
     */
    private void removeListeners() {
        mToView.removeTextChangedListener(mWatcher);
        mCcView.removeTextChangedListener(mWatcher);
        mBccView.removeTextChangedListener(mWatcher);
        mSubjectView.removeTextChangedListener(mWatcher);
        mMessageContentView.removeTextChangedListener(mWatcher);
    }

    /**
     * Set up address auto-completion adapters.
     */
    private void setupAddressAdapters() {
        boolean supportsChips = ChipsUtil.supportsChipsUi();

        if (supportsChips && mToView instanceof RecipientEditTextView) {
            mAddressAdapterTo = new RecipientAdapter(this, (RecipientEditTextView) mToView);
            mToView.setAdapter((RecipientAdapter) mAddressAdapterTo);
        } else {
            mAddressAdapterTo = new EmailAddressAdapter(this);
            mToView.setAdapter((EmailAddressAdapter) mAddressAdapterTo);
        }
        if (supportsChips && mCcView instanceof RecipientEditTextView) {
            mAddressAdapterCc = new RecipientAdapter(this, (RecipientEditTextView) mCcView);
            mCcView.setAdapter((RecipientAdapter) mAddressAdapterCc);
        } else {
            mAddressAdapterCc = new EmailAddressAdapter(this);
            mCcView.setAdapter((EmailAddressAdapter) mAddressAdapterCc);
        }
        if (supportsChips && mBccView instanceof RecipientEditTextView) {
            mAddressAdapterBcc = new RecipientAdapter(this, (RecipientEditTextView) mBccView);
            mBccView.setAdapter((RecipientAdapter) mAddressAdapterBcc);
        } else {
            mAddressAdapterBcc = new EmailAddressAdapter(this);
            mBccView.setAdapter((EmailAddressAdapter) mAddressAdapterBcc);
        }
    }

    /**
     * Asynchronously loads a draft message for editing.
     * This may or may not restore the view contents, depending on whether or not callers want,
     * since in the case of screen rotation, those are restored automatically.
     */
    private void resumeDraft(
            long draftId,
            SendOrSaveMessageTask existingSaveTask,
            final boolean restoreViews) {
        // Note - this can be Message.NOT_SAVED if there is an existing save task in progress
        // for the draft we need to load.
        mDraft.mId = draftId;

        new LoadMessageTask(draftId, existingSaveTask, new OnMessageLoadHandler() {
            @Override
            public void onMessageLoaded(Message message, Body body) {
                message.mHtml = body.mHtmlContent;
                message.mText = body.mTextContent;
                message.mHtmlReply = body.mHtmlReply;
                message.mTextReply = body.mTextReply;
                message.mIntroText = body.mIntroText;
                message.mSourceKey = body.mSourceKey;

                mDraft = message;
                processDraftMessage(message, restoreViews);

                // Load attachments related to the draft.
                loadAttachments(message.mId, mAccount, new AttachmentLoadedCallback() {
                    @Override
                    public void onAttachmentLoaded(Attachment[] attachments) {
                        for (Attachment attachment: attachments) {
                            addAttachment(attachment);
                        }
                    }
                });

                // If we're resuming an edit of a reply, reply-all, or forward, re-load the
                // source message if available so that we get more information.
                if (message.mSourceKey != Message.NOT_SAVED) {
                    loadSourceMessage(message.mSourceKey, false /* restore views */);
                }
            }

            @Override
            public void onLoadFailed() {
                Utility.showToast(MessageCompose.this, R.string.error_loading_message_body);
                finish();
            }
        }).executeSerial((Void[]) null);
    }

    @VisibleForTesting
    void processDraftMessage(Message message, boolean restoreViews) {
        if (restoreViews) {
            mSubjectView.setText(message.mSubject);
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

            showCcBccFieldsIfFilled();
            setNewMessageFocus();
        }
        setMessageChanged(false);

        // The quoted text must always be restored.
        displayQuotedText(message.mTextReply, message.mHtmlReply);
        setIncludeQuotedText(
                (mDraft.mFlags & Message.FLAG_NOT_INCLUDE_QUOTED_TEXT) == 0, false);
    }

    /**
     * Asynchronously loads a source message (to be replied or forwarded in this current view),
     * populating text fields and quoted text fields when the load finishes, if requested.
     */
    private void loadSourceMessage(long sourceMessageId, final boolean restoreViews) {
        new LoadMessageTask(sourceMessageId, null, new OnMessageLoadHandler() {
            @Override
            public void onMessageLoaded(Message message, Body body) {
                message.mHtml = body.mHtmlContent;
                message.mText = body.mTextContent;
                message.mHtmlReply = null;
                message.mTextReply = null;
                message.mIntroText = null;
                mSource = message;
                mSourceAttachments = new ArrayList<Attachment>();

                if (restoreViews) {
                    processSourceMessage(mSource, mAccount);
                    setInitialComposeText(null, getAccountSignature(mAccount));
                }

                loadAttachments(message.mId, mAccount, new AttachmentLoadedCallback() {
                    @Override
                    public void onAttachmentLoaded(Attachment[] attachments) {
                        final boolean supportsSmartForward =
                            (mAccount.mFlags & Account.FLAGS_SUPPORTS_SMART_FORWARD) != 0;

                        // Process the attachments to have the appropriate smart forward flags.
                        for (Attachment attachment : attachments) {
                            if (supportsSmartForward) {
                                attachment.mFlags |= Attachment.FLAG_SMART_FORWARD;
                            }
                            mSourceAttachments.add(attachment);
                        }
                        if (isForward() && restoreViews) {
                            if (processSourceMessageAttachments(
                                    mAttachments, mSourceAttachments, true)) {
                                updateAttachmentUi();
                                setMessageChanged(true);
                            }
                        }
                    }
                });

                if (mAction.equals(ACTION_EDIT_DRAFT)) {
                    // Resuming a draft may in fact be resuming a reply/reply all/forward.
                    // Use a best guess and infer the action here.
                    String inferredAction = inferAction();
                    if (inferredAction != null) {
                        setAction(inferredAction);
                        // No need to update the action selector as switching actions should do it.
                        return;
                    }
                }

                updateActionSelector();
            }

            @Override
            public void onLoadFailed() {
                // The loading of the source message is only really required if it is needed
                // immediately to restore the view contents. In the case of resuming draft, it
                // is only needed to gather additional information.
                if (restoreViews) {
                    Utility.showToast(MessageCompose.this, R.string.error_loading_message_body);
                    finish();
                }
            }
        }).executeSerial((Void[]) null);
    }

    /**
     * Infers whether or not the current state of the message best reflects either a reply,
     * reply-all, or forward.
     */
    @VisibleForTesting
    String inferAction() {
        String subject = mSubjectView.getText().toString();
        if (subject == null) {
            return null;
        }
        if (subject.toLowerCase().startsWith("fwd:")) {
            return ACTION_FORWARD;
        } else if (subject.toLowerCase().startsWith("re:")) {
            int numRecipients = getAddresses(mToView).length
                    + getAddresses(mCcView).length
                    + getAddresses(mBccView).length;
            if (numRecipients > 1) {
                return ACTION_REPLY_ALL;
            } else {
                return ACTION_REPLY;
            }
        } else {
            // Unsure.
            return null;
        }
    }

    private interface OnMessageLoadHandler {
        /**
         * Handles a load to a message (e.g. a draft message or a source message).
         */
        void onMessageLoaded(Message message, Body body);

        /**
         * Handles a failure to load a message.
         */
        void onLoadFailed();
    }

    /**
     * Asynchronously loads a message and the account information.
     * This can be used to load a reference message (when replying) or when restoring a draft.
     */
    private class LoadMessageTask extends EmailAsyncTask<Void, Void, Object[]> {
        /**
         * The message ID to load, if available.
         */
        private long mMessageId;

        /**
         * A future-like reference to the save task which must complete prior to this load.
         */
        private final SendOrSaveMessageTask mSaveTask;

        /**
         * A callback to pass the results of the load to.
         */
        private final OnMessageLoadHandler mCallback;

        public LoadMessageTask(
                long messageId, SendOrSaveMessageTask saveTask, OnMessageLoadHandler callback) {
            super(mTaskTracker);
            mMessageId = messageId;
            mSaveTask = saveTask;
            mCallback = callback;
        }

        private long getIdToLoad() throws InterruptedException, ExecutionException {
            if (mMessageId == -1) {
                mMessageId = mSaveTask.get();
            }
            return mMessageId;
        }

        @Override
        protected Object[] doInBackground(Void... params) {
            long messageId;
            try {
                messageId = getIdToLoad();
            } catch (InterruptedException e) {
                // Don't have a good message ID to load - bail.
                Log.e(Logging.LOG_TAG,
                        "Unable to load draft message since existing save task failed: " + e);
                return null;
            } catch (ExecutionException e) {
                // Don't have a good message ID to load - bail.
                Log.e(Logging.LOG_TAG,
                        "Unable to load draft message since existing save task failed: " + e);
                return null;
            }
            Message message = Message.restoreMessageWithId(MessageCompose.this, messageId);
            if (message == null) {
                return null;
            }
            long accountId = message.mAccountKey;
            Account account = Account.restoreAccountWithId(MessageCompose.this, accountId);
            Body body;
            try {
                body = Body.restoreBodyWithMessageId(MessageCompose.this, message.mId);
            } catch (RuntimeException e) {
                Log.d(Logging.LOG_TAG, "Exception while loading message body: " + e);
                return null;
            }
            return new Object[] {message, body, account};
        }

        @Override
        protected void onSuccess(Object[] results) {
            if ((results == null) || (results.length != 3)) {
                mCallback.onLoadFailed();
                return;
            }

            final Message message = (Message) results[0];
            final Body body = (Body) results[1];
            final Account account = (Account) results[2];
            if ((message == null) || (body == null) || (account == null)) {
                mCallback.onLoadFailed();
                return;
            }

            setAccount(account);
            mCallback.onMessageLoaded(message, body);
            setMessageLoaded(true);
        }
    }

    private interface AttachmentLoadedCallback {
        /**
         * Handles completion of the loading of a set of attachments.
         * Callback will always happen on the main thread.
         */
        void onAttachmentLoaded(Attachment[] attachment);
    }

    private void loadAttachments(
            final long messageId,
            final Account account,
            final AttachmentLoadedCallback callback) {
        new EmailAsyncTask<Void, Void, Attachment[]>(mTaskTracker) {
            @Override
            protected Attachment[] doInBackground(Void... params) {
                return Attachment.restoreAttachmentsWithMessageId(MessageCompose.this, messageId);
            }

            @Override
            protected void onSuccess(Attachment[] attachments) {
                if (attachments == null) {
                    attachments = new Attachment[0];
                }
                callback.onAttachmentLoaded(attachments);
            }
        }.executeSerial((Void[]) null);
    }

    @Override
    public void onFocusChange(View view, boolean focused) {
        if (focused) {
            switch (view.getId()) {
                case R.id.body_text:
                    // When focusing on the message content via tabbing to it, or other means of
                    // auto focusing, move the cursor to the end of the body (before the signature).
                    if (mMessageContentView.getSelectionStart() == 0
                            && mMessageContentView.getSelectionEnd() == 0) {
                        // There is no way to determine if the focus change was programmatic or due
                        // to keyboard event, or if it was due to a tap/restore. Use a best-guess
                        // by using the fact that auto-focus/keyboard tabs set the selection to 0.
                        setMessageContentSelection(getAccountSignature(mAccount));
                    }
            }
        }
    }

    private static void addAddresses(MultiAutoCompleteTextView view, Address[] addresses) {
        if (addresses == null) {
            return;
        }
        for (Address address : addresses) {
            addAddress(view, address.toString());
        }
    }

    private static void addAddresses(MultiAutoCompleteTextView view, String[] addresses) {
        if (addresses == null) {
            return;
        }
        for (String oneAddress : addresses) {
            addAddress(view, oneAddress);
        }
    }

    private static void addAddresses(MultiAutoCompleteTextView view, String addresses) {
        if (addresses == null) {
            return;
        }
        Address[] unpackedAddresses = Address.unpack(addresses);
        for (Address address : unpackedAddresses) {
            addAddress(view, address.toString());
        }
    }

    private static void addAddress(MultiAutoCompleteTextView view, String address) {
        view.append(address + ", ");
    }

    private static String getPackedAddresses(TextView view) {
        Address[] addresses = Address.parse(view.getText().toString().trim());
        return Address.pack(addresses);
    }

    private static Address[] getAddresses(TextView view) {
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
        if (mSource != null && mQuotedTextArea.getVisibility() == View.VISIBLE) {
            message.mSourceKey = mSource.mId;
            // If the quote bar is visible; this must either be a reply or forward
            // Get the body of the source message here
            message.mHtmlReply = mSource.mHtml;
            message.mTextReply = mSource.mText;
            String fromAsString = Address.unpackToString(mSource.mFrom);
            if (isForward()) {
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

                // Note that mSourceKey is not cleared out as this is still considered a
                // reply/forward.
            }
        }
    }

    private class SendOrSaveMessageTask extends EmailAsyncTask<Void, Void, Long> {
        private final boolean mSend;
        private final long mTaskId;

        /** A context that will survive even past activity destruction. */
        private final Context mContext;

        public SendOrSaveMessageTask(long taskId, boolean send) {
            super(null /* DO NOT cancel in onDestroy */);
            if (send && ActivityManager.isUserAMonkey()) {
                Log.d(Logging.LOG_TAG, "Inhibiting send while monkey is in charge.");
                send = false;
            }
            mTaskId = taskId;
            mSend = send;
            mContext = getApplicationContext();

            sActiveSaveTasks.put(mTaskId, this);
        }

        @Override
        protected Long doInBackground(Void... params) {
            synchronized (mDraft) {
                updateMessage(mDraft, mAccount, mAttachments.size() > 0, mSend);
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
                    mController.saveToMailbox(mDraft, Mailbox.TYPE_DRAFTS);
                }
                // For any unloaded attachment, set the flag saying we need it loaded
                boolean hasUnloadedAttachments = false;
                for (Attachment attachment : mAttachments) {
                    if (attachment.mContentUri == null &&
                            ((attachment.mFlags & Attachment.FLAG_SMART_FORWARD) == 0)) {
                        attachment.mFlags |= Attachment.FLAG_DOWNLOAD_FORWARD;
                        hasUnloadedAttachments = true;
                        if (Email.DEBUG) {
                            Log.d(Logging.LOG_TAG,
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
                    mController.sendMessage(mDraft);

                    ArrayList<CharSequence> addressTexts = new ArrayList<CharSequence>();
                    addressTexts.add(mToView.getText());
                    addressTexts.add(mCcView.getText());
                    addressTexts.add(mBccView.getText());
                    DataUsageStatUpdater updater = new DataUsageStatUpdater(mContext);
                    updater.updateWithRfc822Address(addressTexts);
                }
                return mDraft.mId;
            }
        }

        private boolean shouldShowSaveToast() {
            // Don't show the toast when rotating, or when opening an Activity on top of this one.
            return !isChangingConfigurations() && !mPickingAttachment;
        }

        @Override
        protected void onSuccess(Long draftId) {
            // Note that send or save tasks are always completed, even if the activity
            // finishes earlier.
            sActiveSaveTasks.remove(mTaskId);
            // Don't display the toast if the user is just changing the orientation
            if (!mSend && shouldShowSaveToast()) {
                Toast.makeText(mContext, R.string.message_saved_toast, Toast.LENGTH_LONG).show();
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
            Log.w(Logging.LOG_TAG,
                    "Attempted to save draft message prior to the state being fully loaded");
            return;
        }
        synchronized (sActiveSaveTasks) {
            mLastSaveTaskId = sNextSaveTaskId++;

            SendOrSaveMessageTask task = new SendOrSaveMessageTask(mLastSaveTaskId, send);

            // Ensure the tasks are executed serially so that rapid scheduling doesn't result
            // in inconsistent data.
            task.executeSerial();
        }
   }

    private void saveIfNeeded() {
        if (!mDraftNeedsSaving) {
            return;
        }
        setMessageChanged(false);
        sendOrSaveMessage(false);
    }

    /**
     * Checks whether all the email addresses listed in TO, CC, BCC are valid.
     */
    @VisibleForTesting
    boolean isAddressAllValid() {
        boolean supportsChips = ChipsUtil.supportsChipsUi();
        for (TextView view : new TextView[]{mToView, mCcView, mBccView}) {
            String addresses = view.getText().toString().trim();
            if (!Address.isAllValid(addresses)) {
                // Don't show an error message if we're using chips as the chips have
                // their own error state.
                if (!supportsChips || !(view instanceof RecipientEditTextView)) {
                    view.setError(getString(R.string.message_compose_error_invalid_email));
                }
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
            setMessageChanged(false);
            finish();
        }
    }

    private void showQuickResponseDialog() {
        if (mAccount == null) {
            // Load not finished, bail.
            return;
        }
        InsertQuickResponseDialog.newInstance(null, mAccount)
                .show(getFragmentManager(), null);
    }

    /**
     * Inserts the selected QuickResponse into the message body at the current cursor position.
     */
    @Override
    public void onQuickResponseSelected(CharSequence text) {
        int start = mMessageContentView.getSelectionStart();
        int end = mMessageContentView.getSelectionEnd();
        mMessageContentView.getEditableText().replace(start, end, text);
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
            mController.deleteMessage(mDraft.mId);
        }
        Utility.showToast(MessageCompose.this, R.string.message_discarded_toast);
        setMessageChanged(false);
        finish();
    }

    /**
     * Handles an explicit user-initiated action to save a draft.
     */
    private void onSave() {
        saveIfNeeded();
    }

    private void showCcBccFieldsIfFilled() {
        if ((mCcView.length() > 0) || (mBccView.length() > 0)) {
            showCcBccFields();
        }
    }

    private void showCcBccFields() {
        if (mCcBccContainer.getVisibility() != View.VISIBLE) {
            mCcBccContainer.setVisibility(View.VISIBLE);
            mCcView.requestFocus();
            UiUtilities.setVisibilitySafe(this, R.id.add_cc_bcc, View.INVISIBLE);
            invalidateOptionsMenu();
        }
    }

    /**
     * Kick off a picker for whatever kind of MIME types we'll accept and let Android take over.
     */
    private void onAddAttachment() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        i.setType(AttachmentUtilities.ACCEPTABLE_ATTACHMENT_SEND_UI_TYPES[0]);
        mPickingAttachment = true;
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

        Attachment attachment = new Attachment();
        attachment.mFileName = name;
        attachment.mContentUri = uri.toString();
        attachment.mSize = size;
        attachment.mMimeType = AttachmentUtilities.inferMimeTypeForUri(this, uri);
        return attachment;
    }

    private void addAttachment(Attachment attachment) {
        // Before attaching the attachment, make sure it meets any other pre-attach criteria
        if (attachment.mSize > AttachmentUtilities.MAX_ATTACHMENT_UPLOAD_SIZE) {
            Toast.makeText(this, R.string.message_compose_attachment_size, Toast.LENGTH_LONG)
                    .show();
            return;
        }

        mAttachments.add(attachment);
        updateAttachmentUi();
    }

    private void updateAttachmentUi() {
        mAttachmentContentView.removeAllViews();

        for (Attachment attachment : mAttachments) {
            // Note: allowDelete is set in two cases:
            // 1. First time a message (w/ attachments) is forwarded,
            //    where action == ACTION_FORWARD
            // 2. 1 -> Save -> Reopen
            //    but FLAG_SMART_FORWARD is already set at 1.
            // Even if the account supports smart-forward, attachments added
            // manually are still removable.
            final boolean allowDelete = (attachment.mFlags & Attachment.FLAG_SMART_FORWARD) == 0;

            View view = getLayoutInflater().inflate(R.layout.attachment, mAttachmentContentView,
                    false);
            TextView nameView = UiUtilities.getView(view, R.id.attachment_name);
            ImageView delete = UiUtilities.getView(view, R.id.remove_attachment);
            TextView sizeView = UiUtilities.getView(view, R.id.attachment_size);

            nameView.setText(attachment.mFileName);
            if (attachment.mSize > 0) {
                sizeView.setText(UiUtilities.formatSize(this, attachment.mSize));
            } else {
                sizeView.setVisibility(View.GONE);
            }
            if (allowDelete) {
                delete.setOnClickListener(this);
                delete.setTag(view);
            } else {
                delete.setVisibility(View.INVISIBLE);
            }
            view.setTag(attachment);
            mAttachmentContentView.addView(view);
        }
        updateAttachmentContainer();
    }

    private void updateAttachmentContainer() {
        mAttachmentContainer.setVisibility(mAttachmentContentView.getChildCount() == 0
                ? View.GONE : View.VISIBLE);
    }

    private void addAttachmentFromUri(Uri uri) {
        addAttachment(loadAttachmentInfo(uri));
    }

    /**
     * Same as {@link #addAttachmentFromUri}, but does the mime-type check against
     * {@link AttachmentUtilities#ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES}.
     */
    private void addAttachmentFromSendIntent(Uri uri) {
        final Attachment attachment = loadAttachmentInfo(uri);
        final String mimeType = attachment.mMimeType;
        if (!TextUtils.isEmpty(mimeType) && MimeUtility.mimeTypeMatches(mimeType,
                AttachmentUtilities.ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES)) {
            addAttachment(attachment);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        mPickingAttachment = false;
        if (data == null) {
            return;
        }
        addAttachmentFromUri(data.getData());
        setMessageChanged(true);
    }

    private boolean includeQuotedText() {
        return mIncludeQuotedTextCheckBox.isChecked();
    }

    @Override
    public void onClick(View view) {
        if (handleCommand(view.getId())) {
            return;
        }
        switch (view.getId()) {
            case R.id.remove_attachment:
                onDeleteAttachmentIconClicked(view);
                break;
        }
    }

    private void setIncludeQuotedText(boolean include, boolean updateNeedsSaving) {
        mIncludeQuotedTextCheckBox.setChecked(include);
        mQuotedText.setVisibility(mIncludeQuotedTextCheckBox.isChecked()
                ? View.VISIBLE : View.GONE);
        if (updateNeedsSaving) {
            setMessageChanged(true);
        }
    }

    private void onDeleteAttachmentIconClicked(View delButtonView) {
        View attachmentView = (View) delButtonView.getTag();
        Attachment attachment = (Attachment) attachmentView.getTag();
        deleteAttachment(mAttachments, attachment);
        updateAttachmentUi();
        setMessageChanged(true);
    }

    /**
     * Removes an attachment from the current message.
     * If the attachment has previous been saved in the db (i.e. this is a draft message which
     * has previously been saved), then the draft is deleted from the db.
     *
     * This does not update the UI to remove the attachment view.
     * @param attachments the list of attachments to delete from. Injected for tests.
     * @param attachment the attachment to delete
     */
    private void deleteAttachment(List<Attachment> attachments, Attachment attachment) {
        attachments.remove(attachment);
        if ((attachment.mMessageKey == mDraft.mId) && attachment.isSaved()) {
            final long attachmentId = attachment.mId;
            EmailAsyncTask.runAsyncParallel(new Runnable() {
                @Override
                public void run() {
                    mController.deleteAttachment(attachmentId);
                }
            });
        }
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
            onBack(false /* systemKey */);
            return true;
        case R.id.send:
            onSend();
            return true;
        case R.id.save:
            onSave();
            return true;
        case R.id.show_quick_text_list_dialog:
            showQuickResponseDialog();
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
        case R.id.settings:
            AccountSettings.actionSettings(this, mAccount.mId);
            return true;
        }
        return false;
    }

    /**
     * Handle a tap to the system back key, or the "app up" button in the action bar.
     * @param systemKey whether or not the system key was pressed
     */
    private void onBack(boolean systemKey) {
        finish();
        if (isOpenedFromWithinApp()) {
            // If opened from within the app, we just close it.
            return;
        }

        if ((isOpenedFromWidget() || !systemKey) && (mAccount != null)) {
            // Otherwise, need to open the main screen for the appropriate account.
            // Note that mAccount should always be set by the time the action bar is set up.
            startActivity(Welcome.createOpenAccountInboxIntent(this, mAccount.mId));
        }
    }

    private void setAction(String action) {
        if (Objects.equal(action, mAction)) {
            return;
        }

        mAction = action;
        onActionChanged();
    }

    /**
     * Handles changing from reply/reply all/forward states. Note: this activity cannot transition
     * from a standard compose state to any of the other three states.
     */
    private void onActionChanged() {
        if (!hasSourceMessage()) {
            return;
        }
        // Temporarily remove listeners so that changing action does not invalidate and save message
        removeListeners();

        processSourceMessage(mSource, mAccount);

        // Note that the attachments might not be loaded yet, but this will safely noop
        // if that's the case, and the attachments will be processed when they load.
        if (processSourceMessageAttachments(mAttachments, mSourceAttachments, isForward())) {
            updateAttachmentUi();
            setMessageChanged(true);
        }

        updateActionSelector();
        addListeners();
    }

    /**
     * Updates UI components that allows the user to switch between reply/reply all/forward.
     */
    private void updateActionSelector() {
        ActionBar actionBar = getActionBar();
        // Spinner based mode switching.
        if (mActionSpinnerAdapter == null) {
            mActionSpinnerAdapter = new ActionSpinnerAdapter(this);
            actionBar.setListNavigationCallbacks(mActionSpinnerAdapter, ACTION_SPINNER_LISTENER);
        }
        actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
        actionBar.setSelectedNavigationItem(ActionSpinnerAdapter.getActionPosition(mAction));
        actionBar.setDisplayShowTitleEnabled(false);
    }

    private final OnNavigationListener ACTION_SPINNER_LISTENER = new OnNavigationListener() {
        @Override
        public boolean onNavigationItemSelected(int itemPosition, long itemId) {
            setAction(ActionSpinnerAdapter.getAction(itemPosition));
            return true;
        }
    };

    private static class ActionSpinnerAdapter extends ArrayAdapter<String> {
        public ActionSpinnerAdapter(final Context context) {
            super(context,
                    android.R.layout.simple_spinner_dropdown_item,
                    android.R.id.text1,
                    Lists.newArrayList(ACTION_REPLY, ACTION_REPLY_ALL, ACTION_FORWARD));
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            View result = super.getDropDownView(position, convertView, parent);
            ((TextView) result.findViewById(android.R.id.text1)).setText(getDisplayValue(position));
            return result;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View result = super.getView(position, convertView, parent);
            ((TextView) result.findViewById(android.R.id.text1)).setText(getDisplayValue(position));
            return result;
        }

        private String getDisplayValue(int position) {
            switch (position) {
                case 0:
                    return getContext().getString(R.string.reply_action);
                case 1:
                    return getContext().getString(R.string.reply_all_action);
                case 2:
                    return getContext().getString(R.string.forward_action);
                default:
                    throw new IllegalArgumentException("Invalid action type for spinner");
            }
        }

        public static String getAction(int position) {
            switch (position) {
                case 0:
                    return ACTION_REPLY;
                case 1:
                    return ACTION_REPLY_ALL;
                case 2:
                    return ACTION_FORWARD;
                default:
                    throw new IllegalArgumentException("Invalid action type for spinner");
            }
        }

        public static int getActionPosition(String action) {
            if (ACTION_REPLY.equals(action)) {
                return 0;
            } else if (ACTION_REPLY_ALL.equals(action)) {
                return 1;
            } else if (ACTION_FORWARD.equals(action)) {
                return 2;
            }
            Log.w(Logging.LOG_TAG, "Invalid action type for spinner");
            return -1;
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
        menu.findItem(R.id.save).setEnabled(mDraftNeedsSaving);
        MenuItem addCcBcc = menu.findItem(R.id.add_cc_bcc);
        if (addCcBcc != null) {
            // Only available on phones.
            addCcBcc.setVisible(
                    (mCcBccContainer == null) || (mCcBccContainer.getVisibility() != View.VISIBLE));
        }
        MenuItem insertQuickResponse = menu.findItem(R.id.show_quick_text_list_dialog);
        insertQuickResponse.setVisible(mQuickResponsesAvailable);
        insertQuickResponse.setEnabled(mQuickResponsesAvailable);
        return true;
    }

    /**
     * Set a message body and a signature when the Activity is launched.
     *
     * @param text the message body
     */
    @VisibleForTesting
    void setInitialComposeText(CharSequence text, String signature) {
        mMessageContentView.setText("");
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

            // Reset cursor to right before the signature.
            mMessageContentView.setSelection(textLength);
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
    @VisibleForTesting
    void initFromIntent(Intent intent) {

        setAccount(intent);

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
        setInitialComposeText(text, getAccountSignature(mAccount));

        // Next, convert EXTRA_STREAM into an attachment
        if (Intent.ACTION_SEND.equals(mAction) && intent.hasExtra(Intent.EXTRA_STREAM)) {
            Uri uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) {
                addAttachmentFromSendIntent(uri);
            }
        }

        if (Intent.ACTION_SEND_MULTIPLE.equals(mAction)
                && intent.hasExtra(Intent.EXTRA_STREAM)) {
            ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) {
                for (Parcelable parcelable : list) {
                    Uri uri = (Uri) parcelable;
                    if (uri != null) {
                        addAttachmentFromSendIntent(uri);
                    }
                }
            }
        }

        // Finally - expose fields that were filled in but are normally hidden, and set focus
        showCcBccFieldsIfFilled();
        setNewMessageFocus();
    }

    /**
     * When we are launched with an intent that includes a mailto: URI, we can actually
     * gather quite a few of our message fields from it.
     *
     * @param mailToString the href (which must start with "mailto:").
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
            setInitialComposeText(body.get(0), getAccountSignature(mAccount));
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
            mQuotedTextArea.setVisibility(View.VISIBLE);
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
    private static boolean safeAddAddresses(String addrs, String ourAddress,
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
     * @param replyAll whether this is a replyAll (vs a reply)
     */
    @VisibleForTesting
    void setupAddressViews(Message message, Account account, boolean replyAll) {
        // Start clean.
        clearAddressViews();

        // If Reply-to: addresses are included, use those; otherwise, use the From: address.
        Address[] replyToAddresses = Address.unpack(message.mReplyTo);
        if (replyToAddresses.length == 0) {
            replyToAddresses = Address.unpack(message.mFrom);
        }

        // Check if ourAddress is one of the replyToAddresses to decide how to populate To: field
        String ourAddress = account.mEmailAddress;
        boolean containsOurAddress = false;
        for (Address address : replyToAddresses) {
            if (ourAddress.equalsIgnoreCase(address.getAddress())) {
                containsOurAddress = true;
                break;
            }
        }

        if (containsOurAddress) {
            addAddresses(mToView, message.mTo);
        } else {
            addAddresses(mToView, replyToAddresses);
        }

        if (replyAll) {
            // Keep a running list of addresses we're sending to
            ArrayList<Address> allAddresses = new ArrayList<Address>();
            for (Address address: replyToAddresses) {
                allAddresses.add(address);
            }

            if (!containsOurAddress) {
                safeAddAddresses(message.mTo, ourAddress, mCcView, allAddresses);
            }

            safeAddAddresses(message.mCc, ourAddress, mCcView, allAddresses);
        }
        showCcBccFieldsIfFilled();
    }

    private void clearAddressViews() {
        mToView.setText("");
        mCcView.setText("");
        mBccView.setText("");
    }

    /**
     * Pull out the parts of the now loaded source message and apply them to the new message
     * depending on the type of message being composed.
     */
    @VisibleForTesting
    void processSourceMessage(Message message, Account account) {
        String subject = message.mSubject;
        if (subject == null) {
            subject = "";
        }
        if (ACTION_REPLY.equals(mAction) || ACTION_REPLY_ALL.equals(mAction)) {
            setupAddressViews(message, account, ACTION_REPLY_ALL.equals(mAction));
            if (!subject.toLowerCase().startsWith("re:")) {
                mSubjectView.setText("Re: " + subject);
            } else {
                mSubjectView.setText(subject);
            }
            displayQuotedText(message.mText, message.mHtml);
            setIncludeQuotedText(true, false);
        } else if (ACTION_FORWARD.equals(mAction)) {
            // If we had previously filled the recipients from a draft, don't erase them here!
            if (!ACTION_EDIT_DRAFT.equals(getIntent().getAction())) {
                clearAddressViews();
            }
            mSubjectView.setText(!subject.toLowerCase().startsWith("fwd:")
                    ? "Fwd: " + subject : subject);
            displayQuotedText(message.mText, message.mHtml);
            setIncludeQuotedText(true, false);
        } else {
            Log.w(Logging.LOG_TAG, "Unexpected action for a call to processSourceMessage "
                    + mAction);
        }
        showCcBccFieldsIfFilled();
        setNewMessageFocus();
    }

    /**
     * Processes the source attachments and ensures they're either included or excluded from
     * a list of active attachments. This can be used to add attachments for a forwarded message, or
     * to remove them if going from a "Forward" to a "Reply"
     * Uniqueness is based on filename.
     *
     * @param current the list of active attachments on the current message. Injected for tests.
     * @param sourceAttachments the list of attachments related with the source message. Injected
     *     for tests.
     * @param include whether or not the sourceMessages should be included or excluded from the
     *     current list of active attachments
     * @return whether or not the current attachments were modified
     */
    @VisibleForTesting
    boolean processSourceMessageAttachments(
            List<Attachment> current, List<Attachment> sourceAttachments, boolean include) {

        // Build a map of filename to the active attachments.
        HashMap<String, Attachment> currentNames = new HashMap<String, Attachment>();
        for (Attachment attachment : current) {
            currentNames.put(attachment.mFileName, attachment);
        }

        boolean dirty = false;
        if (include) {
            // Needs to make sure it's in the list.
            for (Attachment attachment : sourceAttachments) {
                if (!currentNames.containsKey(attachment.mFileName)) {
                    current.add(attachment);
                    dirty = true;
                }
            }
        } else {
            // Need to remove the source attachments.
            HashSet<String> sourceNames = new HashSet<String>();
            for (Attachment attachment : sourceAttachments) {
                if (currentNames.containsKey(attachment.mFileName)) {
                    deleteAttachment(current, currentNames.get(attachment.mFileName));
                    dirty = true;
                }
            }
        }

        return dirty;
    }

    /**
     * Set a cursor to the end of a body except a signature.
     */
    @VisibleForTesting
    void setMessageContentSelection(String signature) {
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
        }
    }

    private boolean isForward() {
        return ACTION_FORWARD.equals(mAction);
    }

    /**
     * @return the signature for the specified account, if non-null. If the account specified is
     *     null or has no signature, {@code null} is returned.
     */
    private static String getAccountSignature(Account account) {
        return (account == null) ? null : account.mSignature;
    }
}
