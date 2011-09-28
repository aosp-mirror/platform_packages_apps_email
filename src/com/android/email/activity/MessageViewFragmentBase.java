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
import android.app.DownloadManager;
import android.app.Fragment;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.ContactsContract;
import android.provider.ContactsContract.QuickContact;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.email.AttachmentInfo;
import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.Throttle;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.service.AttachmentDownloadService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.collect.Maps;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Formatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// TODO Better handling of config changes.
// - Retain the content; don't kick 3 async tasks every time

/**
 * Base class for {@link MessageViewFragment} and {@link MessageFileViewFragment}.
 */
public abstract class MessageViewFragmentBase extends Fragment implements View.OnClickListener {
    private static final String BUNDLE_KEY_CURRENT_TAB = "MessageViewFragmentBase.currentTab";
    private static final String BUNDLE_KEY_PICTURE_LOADED = "MessageViewFragmentBase.pictureLoaded";
    private static final int PHOTO_LOADER_ID = 1;
    protected Context mContext;

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    private static int PREVIEW_ICON_WIDTH = 62;
    private static int PREVIEW_ICON_HEIGHT = 62;

    private TextView mSubjectView;
    private TextView mFromNameView;
    private TextView mFromAddressView;
    private TextView mDateTimeView;
    private TextView mAddressesView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private View mTabSection;
    private ImageView mFromBadge;
    private ImageView mSenderPresenceView;
    private View mMainView;
    private View mLoadingProgress;
    private View mDetailsCollapsed;
    private View mDetailsExpanded;
    private boolean mDetailsFilled;

    private TextView mMessageTab;
    private TextView mAttachmentTab;
    private TextView mInviteTab;
    // It is not really a tab, but looks like one of them.
    private TextView mShowPicturesTab;
    private View mAlwaysShowPicturesButton;

    private View mAttachmentsScroll;
    private View mInviteScroll;

    private long mAccountId = Account.NO_ACCOUNT;
    private long mMessageId = Message.NO_MESSAGE;
    private Message mMessage;

    private Controller mController;
    private ControllerResultUiThreadWrapper<ControllerResults> mControllerCallback;

    // contains the HTML body. Is used by LoadAttachmentTask to display inline images.
    // is null most of the time, is used transiently to pass info to LoadAttachementTask
    private String mHtmlTextRaw;

    // contains the HTML content as set in WebView.
    private String mHtmlTextWebView;

    private boolean mIsMessageLoadedForTest;

    private MessageObserver mMessageObserver;

    private static final int CONTACT_STATUS_STATE_UNLOADED = 0;
    private static final int CONTACT_STATUS_STATE_UNLOADED_TRIGGERED = 1;
    private static final int CONTACT_STATUS_STATE_LOADED = 2;

    private int mContactStatusState;
    private Uri mQuickContactLookupUri;

    /** Flag for {@link #mTabFlags}: Message has attachment(s) */
    protected static final int TAB_FLAGS_HAS_ATTACHMENT = 1;

    /**
     * Flag for {@link #mTabFlags}: Message contains invite.  This flag is only set by
     * {@link MessageViewFragment}.
     */
    protected static final int TAB_FLAGS_HAS_INVITE = 2;

    /** Flag for {@link #mTabFlags}: Message contains pictures */
    protected static final int TAB_FLAGS_HAS_PICTURES = 4;

    /** Flag for {@link #mTabFlags}: "Show pictures" has already been pressed */
    protected static final int TAB_FLAGS_PICTURE_LOADED = 8;

    /**
     * Flags to control the tabs.
     * @see #updateTabs(int)
     */
    private int mTabFlags;

    /** # of attachments in the current message */
    private int mAttachmentCount;

    // Use (random) large values, to avoid confusion with TAB_FLAGS_*
    protected static final int TAB_MESSAGE = 101;
    protected static final int TAB_INVITE = 102;
    protected static final int TAB_ATTACHMENT = 103;
    private static final int TAB_NONE = 0;

    /** Current tab */
    private int mCurrentTab = TAB_NONE;
    /**
     * Tab that was selected in the previous activity instance.
     * Used to restore the current tab after screen rotation.
     */
    private int mRestoredTab = TAB_NONE;

    private boolean mRestoredPictureLoaded;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    /**
     * Zoom scales for webview.  Values correspond to {@link Preferences#TEXT_ZOOM_TINY}..
     * {@link Preferences#TEXT_ZOOM_HUGE}.
     */
    private static final float[] ZOOM_SCALE_ARRAY = new float[] {0.8f, 0.9f, 1.0f, 1.2f, 1.5f};

    public interface Callback {
        /**
         * Called when a link in a message is clicked.
         *
         * @param url link url that's clicked.
         * @return true if handled, false otherwise.
         */
        public boolean onUrlInMessageClicked(String url);

        /**
         * Called when the message specified doesn't exist, or is deleted/moved.
         */
        public void onMessageNotExists();

        /** Called when it starts loading a message. */
        public void onLoadMessageStarted();

        /** Called when it successfully finishes loading a message. */
        public void onLoadMessageFinished();

        /** Called when an error occurred during loading a message. */
        public void onLoadMessageError(String errorMessage);
    }

    public static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onLoadMessageError(String errorMessage) {}
        @Override public void onLoadMessageFinished() {}
        @Override public void onLoadMessageStarted() {}
        @Override public void onMessageNotExists() {}
        @Override
        public boolean onUrlInMessageClicked(String url) {
            return false;
        }
    }

    private Callback mCallback = EmptyCallback.INSTANCE;

    @Override
    public void onAttach(Activity activity) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onAttach");
        }
        super.onAttach(activity);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreate");
        }
        super.onCreate(savedInstanceState);

        mContext = getActivity().getApplicationContext();

        // Initialize components, but don't "start" them.  Registering the controller callbacks
        // and starting MessageObserver, should be done in onActivityCreated or later and be stopped
        // in onDestroyView to prevent from getting callbacks when the fragment is in the back
        // stack, but they'll start again when it's back from the back stack.
        mController = Controller.getInstance(mContext);
        mControllerCallback = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mMessageObserver = new MessageObserver(new Handler(), mContext);

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onCreateView");
        }
        final View view = inflater.inflate(R.layout.message_view_fragment, container, false);

        cleanupDetachedViews();

        mSubjectView = (TextView) UiUtilities.getView(view, R.id.subject);
        mFromNameView = (TextView) UiUtilities.getView(view, R.id.from_name);
        mFromAddressView = (TextView) UiUtilities.getView(view, R.id.from_address);
        mAddressesView = (TextView) UiUtilities.getView(view, R.id.addresses);
        mDateTimeView = (TextView) UiUtilities.getView(view, R.id.datetime);
        mMessageContentView = (WebView) UiUtilities.getView(view, R.id.message_content);
        mAttachments = (LinearLayout) UiUtilities.getView(view, R.id.attachments);
        mTabSection = UiUtilities.getView(view, R.id.message_tabs_section);
        mFromBadge = (ImageView) UiUtilities.getView(view, R.id.badge);
        mSenderPresenceView = (ImageView) UiUtilities.getView(view, R.id.presence);
        mMainView = UiUtilities.getView(view, R.id.main_panel);
        mLoadingProgress = UiUtilities.getView(view, R.id.loading_progress);
        mDetailsCollapsed = UiUtilities.getView(view, R.id.sub_header_contents_collapsed);
        mDetailsExpanded = UiUtilities.getView(view, R.id.sub_header_contents_expanded);

        mFromNameView.setOnClickListener(this);
        mFromAddressView.setOnClickListener(this);
        mFromBadge.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);

        mMessageTab = UiUtilities.getView(view, R.id.show_message);
        mAttachmentTab = UiUtilities.getView(view, R.id.show_attachments);
        mShowPicturesTab = UiUtilities.getView(view, R.id.show_pictures);
        mAlwaysShowPicturesButton = UiUtilities.getView(view, R.id.always_show_pictures_button);
        // Invite is only used in MessageViewFragment, but visibility is controlled here.
        mInviteTab = UiUtilities.getView(view, R.id.show_invite);

        mMessageTab.setOnClickListener(this);
        mAttachmentTab.setOnClickListener(this);
        mShowPicturesTab.setOnClickListener(this);
        mAlwaysShowPicturesButton.setOnClickListener(this);
        mInviteTab.setOnClickListener(this);
        mDetailsCollapsed.setOnClickListener(this);
        mDetailsExpanded.setOnClickListener(this);

        mAttachmentsScroll = UiUtilities.getView(view, R.id.attachments_scroll);
        mInviteScroll = UiUtilities.getView(view, R.id.invite_scroll);

        WebSettings webSettings = mMessageContentView.getSettings();
        boolean supportMultiTouch = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH);
        webSettings.setDisplayZoomControls(!supportMultiTouch);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        mMessageContentView.setWebViewClient(new CustomWebViewClient());
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
        mController.addResultCallback(mControllerCallback);

        resetView();
        new LoadMessageTask(true).executeParallel();

        UiUtilities.installFragment(this);
    }

    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStart");
        }
        super.onStart();
    }

    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onResume");
        }
        super.onResume();

        // We might have comes back from other full-screen activities.  If so, we need to update
        // the attachment tab as system settings may have been updated that affect which
        // options are available to the user.
        updateAttachmentTab();
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onPause");
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onStop");
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroyView");
        }
        UiUtilities.uninstallFragment(this);
        mController.removeResultCallback(mControllerCallback);
        cancelAllTasks();

        // We should clean up the Webview here, but it can't release resources until it is
        // actually removed from the view tree.

        super.onDestroyView();
    }

    private void cleanupDetachedViews() {
        // WebView cleanup must be done after it leaves the rendering tree, according to
        // its contract
        if (mMessageContentView != null) {
            mMessageContentView.destroy();
            mMessageContentView = null;
        }
    }

    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDestroy");
        }

        cleanupDetachedViews();
        super.onDestroy();
    }

    @Override
    public void onDetach() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onDetach");
        }
        super.onDetach();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        outState.putInt(BUNDLE_KEY_CURRENT_TAB, mCurrentTab);
        outState.putBoolean(BUNDLE_KEY_PICTURE_LOADED, (mTabFlags & TAB_FLAGS_PICTURE_LOADED) != 0);
    }

    private void restoreInstanceState(Bundle state) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, this + " restoreInstanceState");
        }
        // At this point (in onCreate) no tabs are visible (because we don't know if the message has
        // an attachment or invite before loading it).  We just remember the tab here.
        // We'll make it current when the tab first becomes visible in updateTabs().
        mRestoredTab = state.getInt(BUNDLE_KEY_CURRENT_TAB);
        mRestoredPictureLoaded = state.getBoolean(BUNDLE_KEY_PICTURE_LOADED);
    }

    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    private void cancelAllTasks() {
        mMessageObserver.unregister();
        mTaskTracker.cancellAllInterrupt();
    }

    protected final Controller getController() {
        return mController;
    }

    protected final Callback getCallback() {
        return mCallback;
    }

    public final Message getMessage() {
        return mMessage;
    }

    protected final boolean isMessageOpen() {
        return mMessage != null;
    }

    /**
     * Returns the account id of the current message, or -1 if unknown (message not open yet, or
     * viewing an EML message).
     */
    public long getAccountId() {
        return mAccountId;
    }

    /**
     * Show/hide the content.  We hide all the content (except for the bottom buttons) when loading,
     * to avoid flicker.
     */
    private void showContent(boolean showContent, boolean showProgressWhenHidden) {
        makeVisible(mMainView, showContent);
        makeVisible(mLoadingProgress, !showContent && showProgressWhenHidden);
    }

    // TODO: clean this up - most of this is not needed since the WebView and Fragment is not
    // reused for multiple messages.
    protected void resetView() {
        showContent(false, false);
        updateTabs(0);
        setCurrentTab(TAB_MESSAGE);
        if (mMessageContentView != null) {
            blockNetworkLoads(true);
            mMessageContentView.scrollTo(0, 0);

            // Dynamic configuration of WebView
            final WebSettings settings = mMessageContentView.getSettings();
            settings.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
            mMessageContentView.setInitialScale(getWebViewZoom());
        }
        mAttachmentsScroll.scrollTo(0, 0);
        mInviteScroll.scrollTo(0, 0);
        mAttachments.removeAllViews();
        mAttachments.setVisibility(View.GONE);
        initContactStatusViews();
    }

    /**
     * Returns the zoom scale (in percent) which is a combination of the user setting
     * (tiny, small, normal, large, huge) and the device density. The intention
     * is for the text to be physically equal in size over different density
     * screens.
     */
    private int getWebViewZoom() {
        float density = mContext.getResources().getDisplayMetrics().density;
        int zoom = Preferences.getPreferences(mContext).getTextZoom();
        return (int) (ZOOM_SCALE_ARRAY[zoom] * density * 100);
    }

    private void initContactStatusViews() {
        mContactStatusState = CONTACT_STATUS_STATE_UNLOADED;
        mQuickContactLookupUri = null;
        showDefaultQuickContactBadgeImage();
    }

    private void showDefaultQuickContactBadgeImage() {
        mFromBadge.setImageResource(R.drawable.ic_contact_picture);
    }

    protected final void addTabFlags(int tabFlags) {
        updateTabs(mTabFlags | tabFlags);
    }

    private final void clearTabFlags(int tabFlags) {
        updateTabs(mTabFlags & ~tabFlags);
    }

    private void setAttachmentCount(int count) {
        mAttachmentCount = count;
        if (mAttachmentCount > 0) {
            addTabFlags(TAB_FLAGS_HAS_ATTACHMENT);
        } else {
            clearTabFlags(TAB_FLAGS_HAS_ATTACHMENT);
        }
    }

    private static void makeVisible(View v, boolean visible) {
        final int visibility = visible ? View.VISIBLE : View.GONE;
        if ((v != null) && (v.getVisibility() != visibility)) {
            v.setVisibility(visibility);
        }
    }

    private static boolean isVisible(View v) {
        return (v != null) && (v.getVisibility() == View.VISIBLE);
    }

    /**
     * Update the visual of the tabs.  (visibility, text, etc)
     */
    private void updateTabs(int tabFlags) {
        mTabFlags = tabFlags;

        if (getView() == null) {
            return;
        }

        boolean messageTabVisible = (tabFlags & (TAB_FLAGS_HAS_INVITE | TAB_FLAGS_HAS_ATTACHMENT))
                != 0;
        makeVisible(mMessageTab, messageTabVisible);
        makeVisible(mInviteTab, (tabFlags & TAB_FLAGS_HAS_INVITE) != 0);
        makeVisible(mAttachmentTab, (tabFlags & TAB_FLAGS_HAS_ATTACHMENT) != 0);

        final boolean hasPictures = (tabFlags & TAB_FLAGS_HAS_PICTURES) != 0;
        final boolean pictureLoaded = (tabFlags & TAB_FLAGS_PICTURE_LOADED) != 0;
        makeVisible(mShowPicturesTab, hasPictures && !pictureLoaded);

        mAttachmentTab.setText(mContext.getResources().getQuantityString(
                R.plurals.message_view_show_attachments_action,
                mAttachmentCount, mAttachmentCount));

        // Hide the entire section if no tabs are visible.
        makeVisible(mTabSection, isVisible(mMessageTab) || isVisible(mInviteTab)
                || isVisible(mAttachmentTab) || isVisible(mShowPicturesTab)
                || isVisible(mAlwaysShowPicturesButton));

        // Restore previously selected tab after rotation
        if (mRestoredTab != TAB_NONE && isVisible(getTabViewForFlag(mRestoredTab))) {
            setCurrentTab(mRestoredTab);
            mRestoredTab = TAB_NONE;
        }
    }

    /**
     * Set the current tab.
     *
     * @param tab any of {@link #TAB_MESSAGE}, {@link #TAB_ATTACHMENT} or {@link #TAB_INVITE}.
     */
    private void setCurrentTab(int tab) {
        mCurrentTab = tab;

        // Hide & unselect all tabs
        makeVisible(getTabContentViewForFlag(TAB_MESSAGE), false);
        makeVisible(getTabContentViewForFlag(TAB_ATTACHMENT), false);
        makeVisible(getTabContentViewForFlag(TAB_INVITE), false);
        getTabViewForFlag(TAB_MESSAGE).setSelected(false);
        getTabViewForFlag(TAB_ATTACHMENT).setSelected(false);
        getTabViewForFlag(TAB_INVITE).setSelected(false);

        makeVisible(getTabContentViewForFlag(mCurrentTab), true);
        getTabViewForFlag(mCurrentTab).setSelected(true);
    }

    private View getTabViewForFlag(int tabFlag) {
        switch (tabFlag) {
            case TAB_MESSAGE:
                return mMessageTab;
            case TAB_ATTACHMENT:
                return mAttachmentTab;
            case TAB_INVITE:
                return mInviteTab;
        }
        throw new IllegalArgumentException();
    }

    private View getTabContentViewForFlag(int tabFlag) {
        switch (tabFlag) {
            case TAB_MESSAGE:
                return mMessageContentView;
            case TAB_ATTACHMENT:
                return mAttachmentsScroll;
            case TAB_INVITE:
                return mInviteScroll;
        }
        throw new IllegalArgumentException();
    }

    private void blockNetworkLoads(boolean block) {
        if (mMessageContentView != null) {
            mMessageContentView.getSettings().setBlockNetworkLoads(block);
        }
    }

    private void setMessageHtml(String html) {
        if (html == null) {
            html = "";
        }
        if (mMessageContentView != null) {
            mMessageContentView.loadDataWithBaseURL("email://", html, "text/html", "utf-8", null);
        }
    }

    /**
     * Handle clicks on sender, which shows {@link QuickContact} or prompts to add
     * the sender as a contact.
     */
    private void onClickSender() {
        if (!isMessageOpen()) return;
        final Address senderEmail = Address.unpackFirst(mMessage.mFrom);
        if (senderEmail == null) return;

        if (mContactStatusState == CONTACT_STATUS_STATE_UNLOADED) {
            // Status not loaded yet.
            mContactStatusState = CONTACT_STATUS_STATE_UNLOADED_TRIGGERED;
            return;
        }
        if (mContactStatusState == CONTACT_STATUS_STATE_UNLOADED_TRIGGERED) {
            return; // Already clicked, and waiting for the data.
        }

        if (mQuickContactLookupUri != null) {
            QuickContact.showQuickContact(mContext, mFromBadge, mQuickContactLookupUri,
                        QuickContact.MODE_MEDIUM, null);
        } else {
            // No matching contact, ask user to create one
            final Uri mailUri = Uri.fromParts("mailto", senderEmail.getAddress(), null);
            final Intent intent = new Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT,
                    mailUri);

            // Pass along full E-mail string for possible create dialog
            intent.putExtra(ContactsContract.Intents.EXTRA_CREATE_DESCRIPTION,
                    senderEmail.toString());

            // Only provide personal name hint if we have one
            final String senderPersonal = senderEmail.getPersonal();
            if (!TextUtils.isEmpty(senderPersonal)) {
                intent.putExtra(ContactsContract.Intents.Insert.NAME, senderPersonal);
            }
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);

            startActivity(intent);
        }
    }

    private static class ContactStatusLoaderCallbacks
            implements LoaderCallbacks<ContactStatusLoader.Result> {
        private static final String BUNDLE_EMAIL_ADDRESS = "email";
        private final MessageViewFragmentBase mFragment;

        public ContactStatusLoaderCallbacks(MessageViewFragmentBase fragment) {
            mFragment = fragment;
        }

        public static Bundle createArguments(String emailAddress) {
            Bundle b = new Bundle();
            b.putString(BUNDLE_EMAIL_ADDRESS, emailAddress);
            return b;
        }

        @Override
        public Loader<ContactStatusLoader.Result> onCreateLoader(int id, Bundle args) {
            return new ContactStatusLoader(mFragment.mContext,
                    args.getString(BUNDLE_EMAIL_ADDRESS));
        }

        @Override
        public void onLoadFinished(Loader<ContactStatusLoader.Result> loader,
                ContactStatusLoader.Result result) {
            boolean triggered =
                    (mFragment.mContactStatusState == CONTACT_STATUS_STATE_UNLOADED_TRIGGERED);
            mFragment.mContactStatusState = CONTACT_STATUS_STATE_LOADED;
            mFragment.mQuickContactLookupUri = result.mLookupUri;

            if (result.isUnknown()) {
                mFragment.mSenderPresenceView.setVisibility(View.GONE);
            } else {
                mFragment.mSenderPresenceView.setVisibility(View.VISIBLE);
                mFragment.mSenderPresenceView.setImageResource(result.mPresenceResId);
            }
            if (result.mPhoto != null) { // photo will be null if unknown.
                mFragment.mFromBadge.setImageBitmap(result.mPhoto);
            }
            if (triggered) {
                mFragment.onClickSender();
            }
        }

        @Override
        public void onLoaderReset(Loader<ContactStatusLoader.Result> loader) {
        }
    }

    private void onSaveAttachment(MessageViewAttachmentInfo info) {
        if (!Utility.isExternalStorageMounted()) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
            return;
        }

        if (info.isFileSaved()) {
            // Nothing to do - we have the file saved.
            return;
        }

        File savedFile = performAttachmentSave(info);
        if (savedFile != null) {
            Utility.showToast(getActivity(), String.format(
                    mContext.getString(R.string.message_view_status_attachment_saved),
                    savedFile.getName()));
        } else {
            Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
        }
    }

    private File performAttachmentSave(MessageViewAttachmentInfo info) {
        Attachment attachment = Attachment.restoreAttachmentWithId(mContext, info.mId);
        Uri attachmentUri = AttachmentUtilities.getAttachmentUri(mAccountId, attachment.mId);

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            downloads.mkdirs();
            File file = Utility.createUniqueFile(downloads, attachment.mFileName);
            Uri contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    mContext.getContentResolver(), attachmentUri);
            InputStream in = mContext.getContentResolver().openInputStream(contentUri);
            OutputStream out = new FileOutputStream(file);
            IOUtils.copy(in, out);
            out.flush();
            out.close();
            in.close();

            String absolutePath = file.getAbsolutePath();

            // Although the download manager can scan media files, scanning only happens after the
            // user clicks on the item in the Downloads app. So, we run the attachment through
            // the media scanner ourselves so it gets added to gallery / music immediately.
            MediaScannerConnection.scanFile(mContext, new String[] {absolutePath},
                    null, null);

            DownloadManager dm =
                    (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            dm.addCompletedDownload(info.mName, info.mName,
                    false /* do not use media scanner */,
                    info.mContentType, absolutePath, info.mSize,
                    true /* show notification */);

            // Cache the stored file information.
            info.setSavedPath(absolutePath);

            // Update our buttons.
            updateAttachmentButtons(info);

            return file;

        } catch (IOException ioe) {
            // Ignore. Callers will handle it from the return code.
        }

        return null;
    }

    private void onOpenAttachment(MessageViewAttachmentInfo info) {
        if (info.mAllowInstall) {
            // The package installer is unable to install files from a content URI; it must be
            // given a file path. Therefore, we need to save it first in order to proceed
            if (!info.mAllowSave || !Utility.isExternalStorageMounted()) {
                Utility.showToast(getActivity(), R.string.message_view_status_attachment_not_saved);
                return;
            }

            if (!info.isFileSaved()) {
                if (performAttachmentSave(info) == null) {
                    // Saving failed for some reason - bail.
                    Utility.showToast(
                            getActivity(), R.string.message_view_status_attachment_not_saved);
                    return;
                }
            }
        }
        try {
            Intent intent = info.getAttachmentIntent(mContext, mAccountId);
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Utility.showToast(getActivity(), R.string.message_view_display_attachment_toast);
        }
    }

    private void onInfoAttachment(final MessageViewAttachmentInfo attachment) {
        AttachmentInfoDialog dialog =
            AttachmentInfoDialog.newInstance(getActivity(), attachment.mDenyFlags);
        dialog.show(getActivity().getFragmentManager(), null);
    }

    private void onLoadAttachment(final MessageViewAttachmentInfo attachment) {
        attachment.loadButton.setVisibility(View.GONE);
        // If there's nothing in the download queue, we'll probably start right away so wait a
        // second before showing the cancel button
        if (AttachmentDownloadService.getQueueSize() == 0) {
            // Set to invisible; if the button is still in this state one second from now, we'll
            // assume the download won't start right away, and we make the cancel button visible
            attachment.cancelButton.setVisibility(View.GONE);
            // Create the timed task that will change the button state
            new EmailAsyncTask<Void, Void, Void>(mTaskTracker) {
                @Override
                protected Void doInBackground(Void... params) {
                    try {
                        Thread.sleep(1000L);
                    } catch (InterruptedException e) { }
                    return null;
                }
                @Override
                protected void onSuccess(Void result) {
                    // If the timeout completes and the attachment has not loaded, show cancel
                    if (!attachment.loaded) {
                        attachment.cancelButton.setVisibility(View.VISIBLE);
                    }
                }
            }.executeParallel();
        } else {
            attachment.cancelButton.setVisibility(View.VISIBLE);
        }
        attachment.showProgressIndeterminate();
        mController.loadAttachment(attachment.mId, mMessageId, mAccountId);
    }

    private void onCancelAttachment(MessageViewAttachmentInfo attachment) {
        // Don't change button states if we couldn't cancel the download
        if (AttachmentDownloadService.cancelQueuedAttachment(attachment.mId)) {
            attachment.loadButton.setVisibility(View.VISIBLE);
            attachment.cancelButton.setVisibility(View.GONE);
            attachment.hideProgress();
        }
    }

    /**
     * Called by ControllerResults. Show the "View" and "Save" buttons; hide "Load" and "Stop"
     *
     * @param attachmentId the attachment that was just downloaded
     */
    private void doFinishLoadAttachment(long attachmentId) {
        MessageViewAttachmentInfo info = findAttachmentInfo(attachmentId);
        if (info != null) {
            info.loaded = true;
            updateAttachmentButtons(info);
        }
    }

    private void showPicturesInHtml() {
        boolean picturesAlreadyLoaded = (mTabFlags & TAB_FLAGS_PICTURE_LOADED) != 0;
        if ((mMessageContentView != null) && !picturesAlreadyLoaded) {
            blockNetworkLoads(false);
            // TODO: why is this calling setMessageHtml just because the images can load now?
            setMessageHtml(mHtmlTextWebView);

            // Prompt the user to always show images from this sender.
            makeVisible(UiUtilities.getView(getView(), R.id.always_show_pictures_button), true);

            addTabFlags(TAB_FLAGS_PICTURE_LOADED);
        }
    }

    private void showDetails() {
        if (!isMessageOpen()) {
            return;
        }

        if (!mDetailsFilled) {
            String date = formatDate(mMessage.mTimeStamp, true);
            final String SEPARATOR = "\n";
            String to = Address.toString(Address.unpack(mMessage.mTo), SEPARATOR);
            String cc = Address.toString(Address.unpack(mMessage.mCc), SEPARATOR);
            String bcc = Address.toString(Address.unpack(mMessage.mBcc), SEPARATOR);
            setDetailsRow(mDetailsExpanded, date, R.id.date, R.id.date_row);
            setDetailsRow(mDetailsExpanded, to, R.id.to, R.id.to_row);
            setDetailsRow(mDetailsExpanded, cc, R.id.cc, R.id.cc_row);
            setDetailsRow(mDetailsExpanded, bcc, R.id.bcc, R.id.bcc_row);
            mDetailsFilled = true;
        }

        mDetailsCollapsed.setVisibility(View.GONE);
        mDetailsExpanded.setVisibility(View.VISIBLE);
    }

    private void hideDetails() {
        mDetailsCollapsed.setVisibility(View.VISIBLE);
        mDetailsExpanded.setVisibility(View.GONE);
    }

    private static void setDetailsRow(View root, String text, int textViewId, int rowViewId) {
        if (TextUtils.isEmpty(text)) {
            root.findViewById(rowViewId).setVisibility(View.GONE);
            return;
        }
        ((TextView) UiUtilities.getView(root, textViewId)).setText(text);
    }


    @Override
    public void onClick(View view) {
        if (!isMessageOpen()) {
            return; // Ignore.
        }
        switch (view.getId()) {
            case R.id.from_name:
            case R.id.from_address:
            case R.id.badge:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.load:
                onLoadAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.info:
                onInfoAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.save:
                onSaveAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.open:
                onOpenAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.cancel:
                onCancelAttachment((MessageViewAttachmentInfo) view.getTag());
                break;
            case R.id.show_message:
                setCurrentTab(TAB_MESSAGE);
                break;
            case R.id.show_invite:
                setCurrentTab(TAB_INVITE);
                break;
            case R.id.show_attachments:
                setCurrentTab(TAB_ATTACHMENT);
                break;
            case R.id.show_pictures:
                showPicturesInHtml();
                break;
            case R.id.always_show_pictures_button:
                setShowImagesForSender();
                break;
            case R.id.sub_header_contents_collapsed:
                showDetails();
                break;
            case R.id.sub_header_contents_expanded:
                hideDetails();
                break;
        }
    }

    /**
     * Start loading contact photo and presence.
     */
    private void queryContactStatus() {
        if (!isMessageOpen()) return;
        initContactStatusViews(); // Initialize the state, just in case.

        // Find the sender email address, and start presence check.
        Address sender = Address.unpackFirst(mMessage.mFrom);
        if (sender != null) {
            String email = sender.getAddress();
            if (email != null) {
                getLoaderManager().restartLoader(PHOTO_LOADER_ID,
                        ContactStatusLoaderCallbacks.createArguments(email),
                        new ContactStatusLoaderCallbacks(this));
            }
        }
    }

    /**
     * Called by {@link LoadMessageTask} and {@link ReloadMessageTask} to load a message in a
     * subclass specific way.
     *
     * NOTE This method is called on a worker thread!  Implementations must properly synchronize
     * when accessing members.
     *
     * @param activity the parent activity.  Subclass use it as a context, and to show a toast.
     */
    protected abstract Message openMessageSync(Activity activity);

    /**
     * Called in a background thread to reload a new copy of the Message in case something has
     * changed.
     */
    protected Message reloadMessageSync(Activity activity) {
        return openMessageSync(activity);
    }

    /**
     * Async task for loading a single message outside of the UI thread
     */
    private class LoadMessageTask extends EmailAsyncTask<Void, Void, Message> {

        private final boolean mOkToFetch;
        private Mailbox mMailbox;

        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(boolean okToFetch) {
            super(mTaskTracker);
            mOkToFetch = okToFetch;
        }

        @Override
        protected Message doInBackground(Void... params) {
            Activity activity = getActivity();
            Message message = null;
            if (activity != null) {
                message = openMessageSync(activity);
            }
            if (message != null) {
                mMailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
                if (mMailbox == null) {
                    message = null; // mailbox removed??
                }
            }
            return message;
        }

        @Override
        protected void onSuccess(Message message) {
            if (message == null) {
                resetView();
                mCallback.onMessageNotExists();
                return;
            }
            mMessageId = message.mId;

            reloadUiFromMessage(message, mOkToFetch);
            queryContactStatus();
            onMessageShown(mMessageId, mMailbox);
            RecentMailboxManager.getInstance(mContext).touch(mAccountId, message.mMailboxKey);
        }
    }

    /**
     * Kicked by {@link MessageObserver}.  Reload the message and update the views.
     */
    private class ReloadMessageTask extends EmailAsyncTask<Void, Void, Message> {
        public ReloadMessageTask() {
            super(mTaskTracker);
        }

        @Override
        protected Message doInBackground(Void... params) {
            Activity activity = getActivity();
            if (activity == null) {
                return null;
            } else {
                return reloadMessageSync(activity);
            }
        }

        @Override
        protected void onSuccess(Message message) {
            if (message == null || message.mMailboxKey != mMessage.mMailboxKey) {
                // Message deleted or moved.
                mCallback.onMessageNotExists();
                return;
            }
            mMessage = message;
            updateHeaderView(mMessage);
        }
    }

    /**
     * Called when a message is shown to the user.
     */
    protected void onMessageShown(long messageId, Mailbox mailbox) {
    }

    /**
     * Called when the message body is loaded.
     */
    protected void onPostLoadBody() {
    }

    /**
     * Async task for loading a single message body outside of the UI thread
     */
    private class LoadBodyTask extends EmailAsyncTask<Void, Void, String[]> {

        private final long mId;
        private boolean mErrorLoadingMessageBody;
        private final boolean mAutoShowPictures;

        /**
         * Special constructor to cache some local info
         */
        public LoadBodyTask(long messageId, boolean autoShowPictures) {
            super(mTaskTracker);
            mId = messageId;
            mAutoShowPictures = autoShowPictures;
        }

        @Override
        protected String[] doInBackground(Void... params) {
            try {
                String text = null;
                String html = Body.restoreBodyHtmlWithMessageId(mContext, mId);
                if (html == null) {
                    text = Body.restoreBodyTextWithMessageId(mContext, mId);
                }
                return new String[] { text, html };
            } catch (RuntimeException re) {
                // This catches SQLiteException as well as other RTE's we've seen from the
                // database calls, such as IllegalStateException
                Log.d(Logging.LOG_TAG, "Exception while loading message body", re);
                mErrorLoadingMessageBody = true;
                return null;
            }
        }

        @Override
        protected void onSuccess(String[] results) {
            if (results == null) {
                if (mErrorLoadingMessageBody) {
                    Utility.showToast(getActivity(), R.string.error_loading_message_body);
                }
                resetView();
                return;
            }
            reloadUiFromBody(results[0], results[1], mAutoShowPictures);    // text, html
            onPostLoadBody();
        }
    }

    /**
     * Async task for loading attachments
     *
     * Note:  This really should only be called when the message load is complete - or, we should
     * leave open a listener so the attachments can fill in as they are discovered.  In either case,
     * this implementation is incomplete, as it will fail to refresh properly if the message is
     * partially loaded at this time.
     */
    private class LoadAttachmentsTask extends EmailAsyncTask<Long, Void, Attachment[]> {
        public LoadAttachmentsTask() {
            super(mTaskTracker);
        }

        @Override
        protected Attachment[] doInBackground(Long... messageIds) {
            return Attachment.restoreAttachmentsWithMessageId(mContext, messageIds[0]);
        }

        @Override
        protected void onSuccess(Attachment[] attachments) {
            try {
                if (attachments == null) {
                    return;
                }
                boolean htmlChanged = false;
                int numDisplayedAttachments = 0;
                for (Attachment attachment : attachments) {
                    if (mHtmlTextRaw != null && attachment.mContentId != null
                            && attachment.mContentUri != null) {
                        // for html body, replace CID for inline images
                        // Regexp which matches ' src="cid:contentId"'.
                        String contentIdRe =
                            "\\s+(?i)src=\"cid(?-i):\\Q" + attachment.mContentId + "\\E\"";
                        String srcContentUri = " src=\"" + attachment.mContentUri + "\"";
                        mHtmlTextRaw = mHtmlTextRaw.replaceAll(contentIdRe, srcContentUri);
                        htmlChanged = true;
                    } else {
                        addAttachment(attachment);
                        numDisplayedAttachments++;
                    }
                }
                setAttachmentCount(numDisplayedAttachments);
                mHtmlTextWebView = mHtmlTextRaw;
                mHtmlTextRaw = null;
                if (htmlChanged) {
                    setMessageHtml(mHtmlTextWebView);
                }
            } finally {
                showContent(true, false);
            }
        }
    }

    private static Bitmap getPreviewIcon(Context context, AttachmentInfo attachment) {
        try {
            return BitmapFactory.decodeStream(
                    context.getContentResolver().openInputStream(
                            AttachmentUtilities.getAttachmentThumbnailUri(
                                    attachment.mAccountKey, attachment.mId,
                                    PREVIEW_ICON_WIDTH,
                                    PREVIEW_ICON_HEIGHT)));
        } catch (Exception e) {
            Log.d(Logging.LOG_TAG, "Attachment preview failed with exception " + e.getMessage());
            return null;
        }
    }

    /**
     * Subclass of AttachmentInfo which includes our views and buttons related to attachment
     * handling, as well as our determination of suitability for viewing (based on availability of
     * a viewer app) and saving (based upon the presence of external storage)
     */
    private static class MessageViewAttachmentInfo extends AttachmentInfo {
        private Button openButton;
        private Button saveButton;
        private Button loadButton;
        private Button infoButton;
        private Button cancelButton;
        private ImageView iconView;

        private static final Map<AttachmentInfo, String> sSavedFileInfos = Maps.newHashMap();

        // Don't touch it directly from the outer class.
        private final ProgressBar mProgressView;
        private boolean loaded;

        private MessageViewAttachmentInfo(Context context, Attachment attachment,
                ProgressBar progressView) {
            super(context, attachment);
            mProgressView = progressView;
        }

        /**
         * Create a new attachment info based upon an existing attachment info. Display
         * related fields (such as views and buttons) are copied from old to new.
         */
        private MessageViewAttachmentInfo(Context context, MessageViewAttachmentInfo oldInfo) {
            super(context, oldInfo);
            openButton = oldInfo.openButton;
            saveButton = oldInfo.saveButton;
            loadButton = oldInfo.loadButton;
            infoButton = oldInfo.infoButton;
            cancelButton = oldInfo.cancelButton;
            iconView = oldInfo.iconView;
            mProgressView = oldInfo.mProgressView;
            loaded = oldInfo.loaded;
        }

        public void hideProgress() {
            // Don't use GONE, which'll break the layout.
            if (mProgressView.getVisibility() != View.INVISIBLE) {
                mProgressView.setVisibility(View.INVISIBLE);
            }
        }

        public void showProgress(int progress) {
            if (mProgressView.getVisibility() != View.VISIBLE) {
                mProgressView.setVisibility(View.VISIBLE);
            }
            if (mProgressView.isIndeterminate()) {
                mProgressView.setIndeterminate(false);
            }
            mProgressView.setProgress(progress);

            // Hide on completion.
            if (progress == 100) {
                hideProgress();
            }
        }

        public void showProgressIndeterminate() {
            if (mProgressView.getVisibility() != View.VISIBLE) {
                mProgressView.setVisibility(View.VISIBLE);
            }
            if (!mProgressView.isIndeterminate()) {
                mProgressView.setIndeterminate(true);
            }
        }

        /**
         * Determines whether or not this attachment has a saved file in the external storage. That
         * is, the user has at some point clicked "save" for this attachment.
         *
         * Note: this is an approximation and uses an in-memory cache that can get wiped when the
         * process dies, and so is somewhat conservative. Additionally, the user can modify the file
         * after saving, and so the file may not be the same (though this is unlikely).
         */
        public boolean isFileSaved() {
            String path = getSavedPath();
            if (path == null) {
                return false;
            }
            boolean savedFileExists = new File(path).exists();
            if (!savedFileExists) {
                // Purge the cache entry.
                setSavedPath(null);
            }
            return savedFileExists;
        }

        private void setSavedPath(String path) {
            if (path == null) {
                sSavedFileInfos.remove(this);
            } else {
                sSavedFileInfos.put(this, path);
            }
        }

        /**
         * Returns an absolute file path for the given attachment if it has been saved. If one is
         * not found, {@code null} is returned.
         *
         * Clients are expected to validate that the file at the given path is still valid.
         */
        private String getSavedPath() {
            return sSavedFileInfos.get(this);
        }

        @Override
        protected Uri getUriForIntent(Context context, long accountId) {
            // Prefer to act on the saved file for intents.
            String path = getSavedPath();
            return (path != null)
                    ? Uri.parse("file://" + getSavedPath())
                    : super.getUriForIntent(context, accountId);
        }
    }

    /**
     * Updates all current attachments on the attachment tab.
     */
    private void updateAttachmentTab() {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            View view = mAttachments.getChildAt(i);
            MessageViewAttachmentInfo oldInfo = (MessageViewAttachmentInfo)view.getTag();
            MessageViewAttachmentInfo newInfo =
                    new MessageViewAttachmentInfo(getActivity(), oldInfo);
            updateAttachmentButtons(newInfo);
            view.setTag(newInfo);
        }
    }

    /**
     * Updates the attachment buttons. Adjusts the visibility of the buttons as well
     * as updating any tag information associated with the buttons.
     */
    private void updateAttachmentButtons(MessageViewAttachmentInfo attachmentInfo) {
        ImageView attachmentIcon = attachmentInfo.iconView;
        Button openButton = attachmentInfo.openButton;
        Button saveButton = attachmentInfo.saveButton;
        Button loadButton = attachmentInfo.loadButton;
        Button infoButton = attachmentInfo.infoButton;
        Button cancelButton = attachmentInfo.cancelButton;

        if (!attachmentInfo.mAllowView) {
            openButton.setVisibility(View.GONE);
        }
        if (!attachmentInfo.mAllowSave) {
            saveButton.setVisibility(View.GONE);
        }

        if (!attachmentInfo.mAllowView && !attachmentInfo.mAllowSave) {
            // This attachment may never be viewed or saved, so block everything
            attachmentInfo.hideProgress();
            openButton.setVisibility(View.GONE);
            saveButton.setVisibility(View.GONE);
            loadButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            infoButton.setVisibility(View.VISIBLE);
        } else if (attachmentInfo.loaded) {
            // If the attachment is loaded, show 100% progress
            // Note that for POP3 messages, the user will only see "Open" and "Save",
            // because the entire message is loaded before being shown.
            // Hide "Load" and "Info", show "View" and "Save"
            attachmentInfo.showProgress(100);
            if (attachmentInfo.mAllowSave) {
                saveButton.setVisibility(View.VISIBLE);

                boolean isFileSaved = attachmentInfo.isFileSaved();
                saveButton.setEnabled(!isFileSaved);
                if (!isFileSaved) {
                    saveButton.setText(R.string.message_view_attachment_save_action);
                } else {
                    saveButton.setText(R.string.message_view_attachment_saved);
                }
            }
            if (attachmentInfo.mAllowView) {
                // Set the attachment action button text accordingly
                if (attachmentInfo.mContentType.startsWith("audio/") ||
                        attachmentInfo.mContentType.startsWith("video/")) {
                    openButton.setText(R.string.message_view_attachment_play_action);
                } else if (attachmentInfo.mAllowInstall) {
                    openButton.setText(R.string.message_view_attachment_install_action);
                } else {
                    openButton.setText(R.string.message_view_attachment_view_action);
                }
                openButton.setVisibility(View.VISIBLE);
            }
            if (attachmentInfo.mDenyFlags == AttachmentInfo.ALLOW) {
                infoButton.setVisibility(View.GONE);
            } else {
                infoButton.setVisibility(View.VISIBLE);
            }
            loadButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);

            updatePreviewIcon(attachmentInfo);
        } else {
            // The attachment is not loaded, so present UI to start downloading it

            // Show "Load"; hide "View", "Save" and "Info"
            saveButton.setVisibility(View.GONE);
            openButton.setVisibility(View.GONE);
            infoButton.setVisibility(View.GONE);

            // If the attachment is queued, show the indeterminate progress bar.  From this point,.
            // any progress changes will cause this to be replaced by the normal progress bar
            if (AttachmentDownloadService.isAttachmentQueued(attachmentInfo.mId)) {
                attachmentInfo.showProgressIndeterminate();
                loadButton.setVisibility(View.GONE);
                cancelButton.setVisibility(View.VISIBLE);
            } else {
                loadButton.setVisibility(View.VISIBLE);
                cancelButton.setVisibility(View.GONE);
            }
        }
        openButton.setTag(attachmentInfo);
        saveButton.setTag(attachmentInfo);
        loadButton.setTag(attachmentInfo);
        infoButton.setTag(attachmentInfo);
        cancelButton.setTag(attachmentInfo);
    }

    /**
     * Copy data from a cursor-refreshed attachment into the UI.  Called from UI thread.
     *
     * @param attachment A single attachment loaded from the provider
     */
    private void addAttachment(Attachment attachment) {
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.message_view_attachment, null);

        TextView attachmentName = (TextView) UiUtilities.getView(view, R.id.attachment_name);
        TextView attachmentInfoView = (TextView) UiUtilities.getView(view, R.id.attachment_info);
        ImageView attachmentIcon = (ImageView) UiUtilities.getView(view, R.id.attachment_icon);
        Button openButton = (Button) UiUtilities.getView(view, R.id.open);
        Button saveButton = (Button) UiUtilities.getView(view, R.id.save);
        Button loadButton = (Button) UiUtilities.getView(view, R.id.load);
        Button infoButton = (Button) UiUtilities.getView(view, R.id.info);
        Button cancelButton = (Button) UiUtilities.getView(view, R.id.cancel);
        ProgressBar attachmentProgress = (ProgressBar) UiUtilities.getView(view, R.id.progress);

        MessageViewAttachmentInfo attachmentInfo = new MessageViewAttachmentInfo(
                mContext, attachment, attachmentProgress);

        // Check whether the attachment already exists
        if (Utility.attachmentExists(mContext, attachment)) {
            attachmentInfo.loaded = true;
        }

        attachmentInfo.openButton = openButton;
        attachmentInfo.saveButton = saveButton;
        attachmentInfo.loadButton = loadButton;
        attachmentInfo.infoButton = infoButton;
        attachmentInfo.cancelButton = cancelButton;
        attachmentInfo.iconView = attachmentIcon;

        updateAttachmentButtons(attachmentInfo);

        view.setTag(attachmentInfo);
        openButton.setOnClickListener(this);
        saveButton.setOnClickListener(this);
        loadButton.setOnClickListener(this);
        infoButton.setOnClickListener(this);
        cancelButton.setOnClickListener(this);

        attachmentName.setText(attachmentInfo.mName);
        attachmentInfoView.setText(UiUtilities.formatSize(mContext, attachmentInfo.mSize));

        mAttachments.addView(view);
        mAttachments.setVisibility(View.VISIBLE);
    }

    private MessageViewAttachmentInfo findAttachmentInfoFromView(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            MessageViewAttachmentInfo attachmentInfo =
                    (MessageViewAttachmentInfo) mAttachments.getChildAt(i).getTag();
            if (attachmentInfo.mId == attachmentId) {
                return attachmentInfo;
            }
        }
        return null;
    }

    /**
     * Reload the UI from a provider cursor.  {@link LoadMessageTask#onSuccess} calls it.
     *
     * Update the header views, and start loading the body.
     *
     * @param message A copy of the message loaded from the database
     * @param okToFetch If true, and message is not fully loaded, it's OK to fetch from
     * the network.  Use false to prevent looping here.
     */
    protected void reloadUiFromMessage(Message message, boolean okToFetch) {
        mMessage = message;
        mAccountId = message.mAccountKey;

        mMessageObserver.register(ContentUris.withAppendedId(Message.CONTENT_URI, mMessage.mId));

        updateHeaderView(mMessage);

        // Handle partially-loaded email, as follows:
        // 1. Check value of message.mFlagLoaded
        // 2. If != LOADED, ask controller to load it
        // 3. Controller callback (after loaded) should trigger LoadBodyTask & LoadAttachmentsTask
        // 4. Else start the loader tasks right away (message already loaded)
        if (okToFetch && message.mFlagLoaded != Message.FLAG_LOADED_COMPLETE) {
            mControllerCallback.getWrappee().setWaitForLoadMessageId(message.mId);
            mController.loadMessageForView(message.mId);
        } else {
            Address[] fromList = Address.unpack(mMessage.mFrom);
            boolean autoShowImages = false;
            for (Address sender : fromList) {
                String email = sender.getAddress();
                if (shouldShowImagesFor(email)) {
                    autoShowImages = true;
                    break;
                }
            }
            mControllerCallback.getWrappee().setWaitForLoadMessageId(Message.NO_MESSAGE);
            // Ask for body
            new LoadBodyTask(message.mId, autoShowImages).executeParallel();
        }
    }

    protected void updateHeaderView(Message message) {
        mSubjectView.setText(message.mSubject);
        final Address from = Address.unpackFirst(message.mFrom);

        // Set sender address/display name
        // Note we set " " for empty field, so TextView's won't get squashed.
        // Otherwise their height will be 0, which breaks the layout.
        if (from != null) {
            final String fromFriendly = from.toFriendly();
            final String fromAddress = from.getAddress();
            mFromNameView.setText(fromFriendly);
            mFromAddressView.setText(fromFriendly.equals(fromAddress) ? " " : fromAddress);
        } else {
            mFromNameView.setText(" ");
            mFromAddressView.setText(" ");
        }
        mDateTimeView.setText(DateUtils.getRelativeTimeSpanString(mContext, message.mTimeStamp)
                .toString());

        // To/Cc/Bcc
        final Resources res = mContext.getResources();
        final SpannableStringBuilder ssb = new SpannableStringBuilder();
        final String friendlyTo = Address.toFriendly(Address.unpack(message.mTo));
        final String friendlyCc = Address.toFriendly(Address.unpack(message.mCc));
        final String friendlyBcc = Address.toFriendly(Address.unpack(message.mBcc));

        if (!TextUtils.isEmpty(friendlyTo)) {
            Utility.appendBold(ssb, res.getString(R.string.message_view_to_label));
            ssb.append(" ");
            ssb.append(friendlyTo);
        }
        if (!TextUtils.isEmpty(friendlyCc)) {
            ssb.append("  ");
            Utility.appendBold(ssb, res.getString(R.string.message_view_cc_label));
            ssb.append(" ");
            ssb.append(friendlyCc);
        }
        if (!TextUtils.isEmpty(friendlyBcc)) {
            ssb.append("  ");
            Utility.appendBold(ssb, res.getString(R.string.message_view_bcc_label));
            ssb.append(" ");
            ssb.append(friendlyBcc);
        }
        mAddressesView.setText(ssb);
    }

    /**
     * @return the given date/time in a human readable form.  The returned string always have
     *     month and day (and year if {@code withYear} is set), so is usually long.
     *     Use {@link DateUtils#getRelativeTimeSpanString} instead to save the screen real estate.
     */
    private String formatDate(long millis, boolean withYear) {
        StringBuilder sb = new StringBuilder();
        Formatter formatter = new Formatter(sb);
        DateUtils.formatDateRange(mContext, formatter, millis, millis,
                DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_ALL
                | DateUtils.FORMAT_SHOW_TIME
                | (withYear ? DateUtils.FORMAT_SHOW_YEAR : DateUtils.FORMAT_NO_YEAR));
        return sb.toString();
    }

    /**
     * Reload the body from the provider cursor.  This must only be called from the UI thread.
     *
     * @param bodyText text part
     * @param bodyHtml html part
     *
     * TODO deal with html vs text and many other issues <- WHAT DOES IT MEAN??
     */
    private void reloadUiFromBody(String bodyText, String bodyHtml, boolean autoShowPictures) {
        String text = null;
        mHtmlTextRaw = null;
        boolean hasImages = false;

        if (bodyHtml == null) {
            text = bodyText;
            /*
             * Convert the plain text to HTML
             */
            StringBuffer sb = new StringBuffer("<html><body>");
            if (text != null) {
                // Escape any inadvertent HTML in the text message
                text = EmailHtmlUtil.escapeCharacterToDisplay(text);
                // Find any embedded URL's and linkify
                Matcher m = Patterns.WEB_URL.matcher(text);
                while (m.find()) {
                    int start = m.start();
                    /*
                     * WEB_URL_PATTERN may match domain part of email address. To detect
                     * this false match, the character just before the matched string
                     * should not be '@'.
                     */
                    if (start == 0 || text.charAt(start - 1) != '@') {
                        String url = m.group();
                        Matcher proto = WEB_URL_PROTOCOL.matcher(url);
                        String link;
                        if (proto.find()) {
                            // This is work around to force URL protocol part be lower case,
                            // because WebView could follow only lower case protocol link.
                            link = proto.group().toLowerCase() + url.substring(proto.end());
                        } else {
                            // Patterns.WEB_URL matches URL without protocol part,
                            // so added default protocol to link.
                            link = "http://" + url;
                        }
                        String href = String.format("<a href=\"%s\">%s</a>", link, url);
                        m.appendReplacement(sb, href);
                    }
                    else {
                        m.appendReplacement(sb, "$0");
                    }
                }
                m.appendTail(sb);
            }
            sb.append("</body></html>");
            text = sb.toString();
        } else {
            text = bodyHtml;
            mHtmlTextRaw = bodyHtml;
            hasImages = IMG_TAG_START_REGEX.matcher(text).find();
        }

        // TODO this is not really accurate.
        // - Images aren't the only network resources.  (e.g. CSS)
        // - If images are attached to the email and small enough, we download them at once,
        //   and won't need network access when they're shown.
        if (hasImages) {
            if (mRestoredPictureLoaded || autoShowPictures) {
                blockNetworkLoads(false);
                addTabFlags(TAB_FLAGS_PICTURE_LOADED); // Set for next onSaveInstanceState

                // Make sure to reset the flag -- otherwise this will keep taking effect even after
                // moving to another message.
                mRestoredPictureLoaded = false;
            } else {
                addTabFlags(TAB_FLAGS_HAS_PICTURES);
            }
        }
        setMessageHtml(text);

        // Ask for attachments after body
        new LoadAttachmentsTask().executeParallel(mMessage.mId);

        mIsMessageLoadedForTest = true;
    }

    /**
     * Overrides for WebView behaviors.
     */
    private class CustomWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mCallback.onUrlInMessageClicked(url);
        }
    }

    private View findAttachmentView(long attachmentId) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            View view = mAttachments.getChildAt(i);
            MessageViewAttachmentInfo attachment = (MessageViewAttachmentInfo) view.getTag();
            if (attachment.mId == attachmentId) {
                return view;
            }
        }
        return null;
    }

    private MessageViewAttachmentInfo findAttachmentInfo(long attachmentId) {
        View view = findAttachmentView(attachmentId);
        if (view != null) {
            return (MessageViewAttachmentInfo)view.getTag();
        }
        return null;
    }

    /**
     * Controller results listener.  We wrap it with {@link ControllerResultUiThreadWrapper},
     * so all methods are called on the UI thread.
     */
    private class ControllerResults extends Controller.Result {
        private long mWaitForLoadMessageId;

        public void setWaitForLoadMessageId(long messageId) {
            mWaitForLoadMessageId = messageId;
        }

        @Override
        public void loadMessageForViewCallback(MessagingException result, long accountId,
                long messageId, int progress) {
            if (messageId != mWaitForLoadMessageId) {
                // We are not waiting for this message to load, so exit quickly
                return;
            }
            if (result == null) {
                switch (progress) {
                    case 0:
                        mCallback.onLoadMessageStarted();
                        // Loading from network -- show the progress icon.
                        showContent(false, true);
                        break;
                    case 100:
                        mWaitForLoadMessageId = -1;
                        mCallback.onLoadMessageFinished();
                        // reload UI and reload everything else too
                        // pass false to LoadMessageTask to prevent looping here
                        cancelAllTasks();
                        new LoadMessageTask(false).executeParallel();
                        break;
                    default:
                        // do nothing - we don't have a progress bar at this time
                        break;
                }
            } else {
                mWaitForLoadMessageId = Message.NO_MESSAGE;
                String error = mContext.getString(R.string.status_network_error);
                mCallback.onLoadMessageError(error);
                resetView();
            }
        }

        @Override
        public void loadAttachmentCallback(MessagingException result, long accountId,
                long messageId, long attachmentId, int progress) {
            if (messageId == mMessageId) {
                if (result == null) {
                    showAttachmentProgress(attachmentId, progress);
                    switch (progress) {
                        case 100:
                            final MessageViewAttachmentInfo attachmentInfo =
                                    findAttachmentInfoFromView(attachmentId);
                            if (attachmentInfo != null) {
                                updatePreviewIcon(attachmentInfo);
                            }
                            doFinishLoadAttachment(attachmentId);
                            break;
                        default:
                            // do nothing - we don't have a progress bar at this time
                            break;
                    }
                } else {
                    MessageViewAttachmentInfo attachment = findAttachmentInfo(attachmentId);
                    if (attachment == null) {
                        // Called before LoadAttachmentsTask finishes.
                        // (Possible if you quickly close & re-open a message)
                        return;
                    }
                    attachment.cancelButton.setVisibility(View.GONE);
                    attachment.loadButton.setVisibility(View.VISIBLE);
                    attachment.hideProgress();

                    final String error;
                    if (result.getCause() instanceof IOException) {
                        error = mContext.getString(R.string.status_network_error);
                    } else {
                        error = mContext.getString(
                                R.string.message_view_load_attachment_failed_toast,
                                attachment.mName);
                    }
                    mCallback.onLoadMessageError(error);
                }
            }
        }

        private void showAttachmentProgress(long attachmentId, int progress) {
            MessageViewAttachmentInfo attachment = findAttachmentInfo(attachmentId);
            if (attachment != null) {
                if (progress == 0) {
                    attachment.cancelButton.setVisibility(View.GONE);
                }
                attachment.showProgress(progress);
            }
        }
    }

    /**
     * Class to detect update on the current message (e.g. toggle star).  When it gets content
     * change notifications, it kicks {@link ReloadMessageTask}.
     */
    private class MessageObserver extends ContentObserver implements Runnable {
        private final Throttle mThrottle;
        private final ContentResolver mContentResolver;

        private boolean mRegistered;

        public MessageObserver(Handler handler, Context context) {
            super(handler);
            mContentResolver = context.getContentResolver();
            mThrottle = new Throttle("MessageObserver", this, handler);
        }

        public void unregister() {
            if (!mRegistered) {
                return;
            }
            mThrottle.cancelScheduledCallback();
            mContentResolver.unregisterContentObserver(this);
            mRegistered = false;
        }

        public void register(Uri notifyUri) {
            unregister();
            mContentResolver.registerContentObserver(notifyUri, true, this);
            mRegistered = true;
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mRegistered) {
                mThrottle.onEvent();
            }
        }

        /** This method is delay-called by {@link Throttle} on the UI thread. */
        @Override
        public void run() {
            // This method is delay-called, so need to make sure if it's still registered.
            if (mRegistered) {
                new ReloadMessageTask().cancelPreviousAndExecuteParallel();
            }
        }
    }

    private void updatePreviewIcon(MessageViewAttachmentInfo attachmentInfo) {
        new UpdatePreviewIconTask(attachmentInfo).executeParallel();
    }

    private class UpdatePreviewIconTask extends EmailAsyncTask<Void, Void, Bitmap> {
        @SuppressWarnings("hiding")
        private final Context mContext;
        private final MessageViewAttachmentInfo mAttachmentInfo;

        public UpdatePreviewIconTask(MessageViewAttachmentInfo attachmentInfo) {
            super(mTaskTracker);
            mContext = getActivity();
            mAttachmentInfo = attachmentInfo;
        }

        @Override
        protected Bitmap doInBackground(Void... params) {
            return getPreviewIcon(mContext, mAttachmentInfo);
        }

        @Override
        protected void onSuccess(Bitmap result) {
            if (result == null) {
                return;
            }
            mAttachmentInfo.iconView.setImageBitmap(result);
        }
    }

    private boolean shouldShowImagesFor(String senderEmail) {
        return Preferences.getPreferences(getActivity()).shouldShowImagesFor(senderEmail);
    }

    private void setShowImagesForSender() {
        makeVisible(UiUtilities.getView(getView(), R.id.always_show_pictures_button), false);
        Utility.showToast(getActivity(), R.string.message_view_always_show_pictures_confirmation);

        // Force redraw of the container.
        updateTabs(mTabFlags);

        Address[] fromList = Address.unpack(mMessage.mFrom);
        Preferences prefs = Preferences.getPreferences(getActivity());
        for (Address sender : fromList) {
            String email = sender.getAddress();
            prefs.setSenderAsTrusted(email);
        }
    }

    public boolean isMessageLoadedForTest() {
        return mIsMessageLoadedForTest;
    }

    public void clearIsMessageLoadedForTest() {
        mIsMessageLoadedForTest = true;
    }
}
