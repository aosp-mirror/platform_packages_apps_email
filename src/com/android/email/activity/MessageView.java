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
import com.android.email.MessagingController;
import com.android.email.MessagingListener;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Multipart;
import com.android.email.mail.Part;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.EmailHtmlUtil;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.store.LocalStore.LocalAttachmentBodyPart;
import com.android.email.mail.store.LocalStore.LocalMessage;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;

import org.apache.commons.io.IOUtils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Contacts;
import android.provider.Contacts.Intents;
import android.provider.Contacts.People;
import android.provider.Contacts.Presence;
import android.text.util.Regex;
import android.util.Config;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageView extends Activity
        implements OnClickListener {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_ACCOUNT_ID = "com.android.email.MessageView_account_id";
    private static final String EXTRA_FOLDER = "com.android.email.MessageView_folder";
    private static final String EXTRA_MESSAGE = "com.android.email.MessageView_message";
    private static final String EXTRA_FOLDER_UIDS = "com.android.email.MessageView_folderUids";
    private static final String EXTRA_NEXT = "com.android.email.MessageView_next";
    
    private static final String[] METHODS_WITH_PRESENCE_PROJECTION = new String[] {
        People.ContactMethods._ID,  // 0
        People.PRESENCE_STATUS,     // 1
    };
    private static final int METHODS_STATUS_COLUMN = 1;

    // Regex that matches start of img tag. '<(?i)img\s+'.
    private static final Pattern IMG_TAG_START_REGEX = Pattern.compile("<(?i)img\\s+");
    // Regex that matches Web URL protocol part as case insensitive.
    private static final Pattern WEB_URL_PROTOCOL = Pattern.compile("(?i)http|https://");

    // Support for LoadBodyTask
    private static final String[] BODY_CONTENT_PROJECTION = new String[] { 
        EmailContent.RECORD_ID, EmailContent.BodyColumns.MESSAGE_KEY,
        EmailContent.BodyColumns.HTML_CONTENT, EmailContent.BodyColumns.TEXT_CONTENT
    };
    private static final int BODY_CONTENT_COLUMN_RECORD_ID = 0;
    private static final int BODY_CONTENT_COLUMN_MESSAGE_KEY = 1;
    private static final int BODY_CONTENT_COLUMN_HTML_CONTENT = 2;
    private static final int BODY_CONTENT_COLUMN_TEXT_CONTENT = 3;

    private TextView mSubjectView;
    private TextView mFromView;
    private TextView mDateView;
    private TextView mTimeView;
    private TextView mToView;
    private TextView mCcView;
    private View mCcContainerView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private ImageView mAttachmentIcon;
    private ImageView mFavoriteIcon;
    private View mShowPicturesSection;
    private ImageView mSenderPresenceView;
    private ProgressDialog mProgressDialog;

    private long mAccountId;
    private EmailContent.Account mAccount;
    private long mMessageId;
    private EmailContent.Message mMessage;

    private LoadMessageTask mLoadMessageTask;
    private LoadBodyTask mLoadBodyTask;

    private String mFolder;
    private String mMessageUid;
    private Cursor mMessageListCursor;

    // TODO all uses of this need to be converted to "mMessage".  Then mOldMessage goes away.
    private Message mOldMessage;

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

    private Drawable mFavoriteIconOn;
    private Drawable mFavoriteIconOff;

    private Listener mListener = new Listener();
    private MessageViewHandler mHandler = new MessageViewHandler();

    class MessageViewHandler extends Handler {
        private static final int MSG_PROGRESS = 2;
        private static final int MSG_ADD_ATTACHMENT = 3;
        private static final int MSG_SET_ATTACHMENTS_ENABLED = 4;
        private static final int MSG_SET_HEADERS = 5;
        private static final int MSG_NETWORK_ERROR = 6;
        private static final int MSG_ATTACHMENT_SAVED = 7;
        private static final int MSG_ATTACHMENT_NOT_SAVED = 8;
        private static final int MSG_SHOW_SHOW_PICTURES = 9;
        private static final int MSG_FETCHING_ATTACHMENT = 10;
        private static final int MSG_SET_SENDER_PRESENCE = 11;
        private static final int MSG_VIEW_ATTACHMENT_ERROR = 12;
        private static final int MSG_UPDATE_ATTACHMENT_ICON = 18;

        @Override
        public void handleMessage(android.os.Message msg) {
            switch (msg.what) {
                case MSG_PROGRESS:
                    if (msg.arg1 != 0) {
                        mProgressDialog.setMessage(
                                getString(R.string.message_view_fetching_attachment_progress,
                                        msg.obj));
                        mProgressDialog.show();
                    } else {
                        mProgressDialog.dismiss();
                    }
                    setProgressBarIndeterminateVisibility(msg.arg1 != 0);
                    break;
                case MSG_ADD_ATTACHMENT:
                    mAttachments.addView((View) msg.obj);
                    mAttachments.setVisibility(View.VISIBLE);
                    break;
                case MSG_SET_ATTACHMENTS_ENABLED:
                    for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
                        Attachment attachment = (Attachment) mAttachments.getChildAt(i).getTag();
                        attachment.viewButton.setEnabled(msg.arg1 == 1);
                        attachment.downloadButton.setEnabled(msg.arg1 == 1);
                    }
                    break;
                case MSG_SET_HEADERS:
                    String[] values = (String[]) msg.obj;
                    mSubjectView.setText(values[0]);
                    mFromView.setText(values[1]);
                    mTimeView.setText(values[2]);
                    mDateView.setText(values[3]);
                    mToView.setText(values[4]);
                    mCcView.setText(values[5]);
                    mCcContainerView.setVisibility((values[5] != null) ? View.VISIBLE : View.GONE);
                    mAttachmentIcon.setVisibility(msg.arg1 == 1 ? View.VISIBLE : View.GONE);
                    break;
                case MSG_NETWORK_ERROR:
                    Toast.makeText(MessageView.this,
                            R.string.status_network_error, Toast.LENGTH_LONG).show();
                    break;
                case MSG_ATTACHMENT_SAVED:
                    Toast.makeText(MessageView.this, String.format(
                            getString(R.string.message_view_status_attachment_saved), msg.obj),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_ATTACHMENT_NOT_SAVED:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_status_attachment_not_saved),
                            Toast.LENGTH_LONG).show();
                    break;
                case MSG_SHOW_SHOW_PICTURES:
                    mShowPicturesSection.setVisibility(msg.arg1 == 1 ? View.VISIBLE : View.GONE);
                    break;
                case MSG_FETCHING_ATTACHMENT:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_fetching_attachment_toast),
                            Toast.LENGTH_SHORT).show();
                    break;
                case MSG_SET_SENDER_PRESENCE:
                    updateSenderPresence(msg.arg1);
                    break;
                case MSG_VIEW_ATTACHMENT_ERROR:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_display_attachment_toast),
                            Toast.LENGTH_SHORT).show();
                    break;
                case MSG_UPDATE_ATTACHMENT_ICON:
                    ((Attachment) mAttachments.getChildAt(msg.arg1).getTag())
                        .iconView.setImageBitmap((Bitmap) msg.obj);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        public void progress(boolean progress, String filename) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_PROGRESS;
            msg.arg1 = progress ? 1 : 0;
            msg.obj = filename;
            sendMessage(msg);
        }

        public void addAttachment(View attachmentView) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_ADD_ATTACHMENT;
            msg.obj = attachmentView;
            sendMessage(msg);
        }

        public void setAttachmentsEnabled(boolean enabled) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SET_ATTACHMENTS_ENABLED;
            msg.arg1 = enabled ? 1 : 0;
            sendMessage(msg);
        }

        public void setHeaders(
                String subject,
                String from,
                String time,
                String date,
                String to,
                String cc,
                boolean hasAttachments) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SET_HEADERS;
            msg.arg1 = hasAttachments ? 1 : 0;
            msg.obj = new String[] { subject, from, time, date, to, cc };
            sendMessage(msg);
        }

        public void networkError() {
            sendEmptyMessage(MSG_NETWORK_ERROR);
        }

        public void attachmentSaved(String filename) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_ATTACHMENT_SAVED;
            msg.obj = filename;
            sendMessage(msg);
        }

        public void attachmentNotSaved() {
            sendEmptyMessage(MSG_ATTACHMENT_NOT_SAVED);
        }

        public void fetchingAttachment() {
            sendEmptyMessage(MSG_FETCHING_ATTACHMENT);
        }

        public void showShowPictures(boolean show) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SHOW_SHOW_PICTURES;
            msg.arg1 = show ? 1 : 0;
            sendMessage(msg);
        }
        
        public void setSenderPresence(int presenceIconId) {
            android.os.Message
                    .obtain(this, MSG_SET_SENDER_PRESENCE,  presenceIconId, 0)
                    .sendToTarget();
        }
        
        public void attachmentViewError() {
            sendEmptyMessage(MSG_VIEW_ATTACHMENT_ERROR);
        }

        public void updateAttachmentIcon(int pos, Bitmap icon) {
            android.os.Message msg = android.os.Message.obtain(this, MSG_UPDATE_ATTACHMENT_ICON);
            msg.arg1 = pos;
            msg.obj = icon;
            sendMessage(msg);
        }
    }

    /**
     * Encapsulates known information about a single attachment.
     */
    private static class Attachment {
        public String name;
        public String contentType;
        public long size;
        public LocalAttachmentBodyPart part;
        public Button viewButton;
        public Button downloadButton;
        public ImageView iconView;
    }

    /**
     * View a specific message found in the Email provider.
     * 
     * TODO: Find a way to pass a cursor so we can iterator prev/next as well
     */
    public static void actionView(Context context, long messageId) {
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        context.startActivity(i);
    }

    @Deprecated
    public static void actionView(Context context, long accountId,
            String folder, String messageUid, ArrayList<String> folderUids) {
        actionView(context, accountId, folder, messageUid, folderUids, null);
    }

    @Deprecated
    public static void actionView(Context context, long accountId,
            String folder, String messageUid, ArrayList<String> folderUids, Bundle extras) {
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        i.putExtra(EXTRA_FOLDER, folder);
        i.putExtra(EXTRA_MESSAGE, messageUid);
        i.putExtra(EXTRA_FOLDER_UIDS, folderUids);
        if (extras != null) {
            i.putExtras(extras);
        }
        context.startActivity(i);
     }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

        setContentView(R.layout.message_view);

        mSubjectView = (TextView) findViewById(R.id.subject);
        mFromView = (TextView) findViewById(R.id.from);
        mToView = (TextView) findViewById(R.id.to);
        mCcView = (TextView) findViewById(R.id.cc);
        mCcContainerView = findViewById(R.id.cc_container);
        mDateView = (TextView) findViewById(R.id.date);
        mTimeView = (TextView) findViewById(R.id.time);
        mMessageContentView = (WebView) findViewById(R.id.message_content);
        mAttachments = (LinearLayout) findViewById(R.id.attachments);
        mAttachmentIcon = (ImageView) findViewById(R.id.attachment);
        mFavoriteIcon = (ImageView) findViewById(R.id.favorite);
        mShowPicturesSection = findViewById(R.id.show_pictures_section);
        mSenderPresenceView = (ImageView) findViewById(R.id.presence);
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);

        mMessageContentView.setVerticalScrollBarEnabled(false);
        mAttachments.setVisibility(View.GONE);
        mAttachmentIcon.setVisibility(View.GONE);

        mFromView.setOnClickListener(this);
        mSenderPresenceView.setOnClickListener(this);
        mFavoriteIcon.setOnClickListener(this);
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.show_pictures).setOnClickListener(this);

        mMessageContentView.getSettings().setBlockNetworkImage(true);
        mMessageContentView.getSettings().setSupportZoom(false);

        setTitle("");

        mDateFormat = android.text.format.DateFormat.getDateFormat(this);   // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format

        mFavoriteIconOn = getResources().getDrawable(android.R.drawable.star_on);
        mFavoriteIconOff = getResources().getDrawable(android.R.drawable.star_off);

        Intent intent = getIntent();
        mMessageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1);
//        mAccountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1);
//        mAccount = EmailStore.Account.restoreAccountWithId(this, mAccountId);
//        mFolder = intent.getStringExtra(EXTRA_FOLDER);
//        mMessageUid = intent.getStringExtra(EXTRA_MESSAGE);
        mMessageListCursor = null;  // TODO - pass message list cursor so we can prev/next

        View next = findViewById(R.id.next);
        View previous = findViewById(R.id.previous);
        /*
         * Next and Previous Message are not shown in landscape mode, so
         * we need to check before we use them.
         */
        if (next != null && previous != null && mMessageListCursor != null) {
            // TODO analyze based on cursor, not on a big nasty array
            next.setVisibility(View.GONE);
            previous.setVisibility(View.GONE);
/*           
            next.setOnClickListener(this);
            previous.setOnClickListener(this);

            findSurroundingMessagesUid();

            previous.setVisibility(mPreviousMessageUid != null ? View.VISIBLE : View.GONE);
            next.setVisibility(mNextMessageUid != null ? View.VISIBLE : View.GONE);

            boolean goNext = intent.getBooleanExtra(EXTRA_NEXT, false);
            if (goNext) {
                next.requestFocus();
            }
*/
        }

/*
        MessagingController.getInstance(getApplication()).addListener(mListener);
        new Thread() {
            @Override
            public void run() {
                // TODO this is a spot that should be eventually handled by a MessagingController
                // thread pool. We want it in a thread but it can't be blocked by the normal
                // synchronization stuff in MC.
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                MessagingController.getInstance(getApplication()).loadMessageForView(
                        mAccount,
                        mFolder,
                        mMessageUid,
                        mListener);
            }
        }.start();
*/
        // Start an AsyncTask to make a new cursor and load the message
        mLoadMessageTask = new LoadMessageTask(mMessageId);
        mLoadMessageTask.execute();
    }

    @Override
    public void onResume() {
        super.onResume();
        MessagingController.getInstance(getApplication()).addListener(mListener);
        if (mOldMessage != null) {
            startPresenceCheck();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        MessagingController.getInstance(getApplication()).removeListener(mListener);
    }

    /**
     * We override onDestroy to make sure that the WebView gets explicitly destroyed.
     * Otherwise it can leak native references.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mLoadMessageTask != null &&
                mLoadMessageTask.getStatus() != AsyncTask.Status.FINISHED) {
            mLoadMessageTask.cancel(true);
            mLoadMessageTask = null;
        }
        if (mLoadBodyTask != null &&
                mLoadBodyTask.getStatus() != AsyncTask.Status.FINISHED) {
            mLoadBodyTask.cancel(true);
            mLoadBodyTask = null;
        }

        // This is synchronized because the listener accesses mMessageContentView from its thread
        synchronized (this) {
            mMessageContentView.destroy();
            mMessageContentView = null;
        }
    }

    private void onDelete() {
        if (mMessage != null) {
            Controller.getInstance(getApplication()).deleteMessage(
                    mMessageId, mMessage.mAccountKey);
            Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();

            if (onPrevious()) {
                return;
            }
            if (onNext()) {
                return;
            }
            finish();
        }
    }
    
    private void onClickSender() {
        if (mOldMessage != null) {
            try {
                Address senderEmail = mOldMessage.getFrom()[0];
                Uri contactUri = Uri.fromParts("mailto", senderEmail.getAddress(), null);
                
                Intent contactIntent = new Intent(Contacts.Intents.SHOW_OR_CREATE_CONTACT);
                contactIntent.setData(contactUri);
                
                // Pass along full E-mail string for possible create dialog  
                contactIntent.putExtra(Contacts.Intents.EXTRA_CREATE_DESCRIPTION,
                        senderEmail.toString());
                
                // Only provide personal name hint if we have one
                String senderPersonal = senderEmail.getPersonal();
                if (senderPersonal != null) {
                    contactIntent.putExtra(Intents.Insert.NAME, senderPersonal);
                }
                
                startActivity(contactIntent);
            } catch (MessagingException me) {
                // this will happen if message has illegal From:, ignore
            } catch (ArrayIndexOutOfBoundsException e) {
                // this will happen if message has no or illegal From:, ignore
            }
        }
    }

    /**
     * Toggle favorite status and write back to provider
     */
    private void onClickFavorite() {
        if (mMessage != null) {
            // Update UI
            boolean newFavorite = ! mMessage.mFlagFavorite;
            mFavoriteIcon.setImageDrawable(newFavorite ? mFavoriteIconOn : mFavoriteIconOff);

            // Update provider
            // TODO this should be a call to the controller, since it may possibly kick off
            // more than just a DB update.  Also, the DB update shouldn't be in the UI thread
            // as it is here.
            mMessage.mFlagFavorite = newFavorite;
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.FLAG_FAVORITE, newFavorite ? 1 : 0);
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.SYNCED_CONTENT_URI, mMessageId);
            getContentResolver().update(uri, cv, null, null);
        }
    }

    private void onReply() {
        if (mOldMessage != null) {
            MessageCompose.actionReply(this, mAccountId, mOldMessage, false);
            finish();
        }
    }

    private void onReplyAll() {
        if (mOldMessage != null) {
            MessageCompose.actionReply(this, mAccountId, mOldMessage, true);
            finish();
        }
    }

    private void onForward() {
        if (mOldMessage != null) {
            MessageCompose.actionForward(this, mAccountId, mOldMessage);
            finish();
        }
    }

    private boolean onNext() {
        // TODO make this work using a cursor
        return false;
/*
        Bundle extras = new Bundle(1);
        extras.putBoolean(EXTRA_NEXT, true);
        MessageView.actionView(this, mAccountId, mFolder, mNextMessageUid, mFolderUids, extras);
        finish();
*/
    }

    private boolean onPrevious() {
        // TODO make this work using a cursor
        return false;
/*
        MessageView.actionView(this, mAccountId, mFolder, mPreviousMessageUid, mFolderUids);
        finish();
*/
    }

    private void onMarkAsRead(boolean isRead) {
        if (mMessage != null && mMessage.mFlagRead != isRead) {
            // TODO this should be a call to the controller, since it may possibly kick off
            // more than just a DB update.  Also, the DB update shouldn't be in the UI thread
            // as it is here.
            mMessage.mFlagRead = isRead;
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.FLAG_READ, isRead ? 1 : 0);
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.SYNCED_CONTENT_URI, mMessageId);
            getContentResolver().update(uri, cv, null, null);
        }
    }

    /**
     * Creates a unique file in the given directory by appending a hyphen
     * and a number to the given filename.
     * @param directory
     * @param filename
     * @return a new File object, or null if one could not be created
     */
    /* package */ static File createUniqueFile(File directory, String filename) {
        File file = new File(directory, filename);
        if (!file.exists()) {
            return file;
        }
        // Get the extension of the file, if any.
        int index = filename.lastIndexOf('.');
        String format;
        if (index != -1) {
            String name = filename.substring(0, index);
            String extension = filename.substring(index);
            format = name + "-%d" + extension;
        }
        else {
            format = filename + "-%d";
        }
        for (int i = 2; i < Integer.MAX_VALUE; i++) {
            file = new File(directory, String.format(format, i));
            if (!file.exists()) {
                return file;
            }
        }
        return null;
    }

    private void onDownloadAttachment(Attachment attachment) {
        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            /*
             * Abort early if there's no place to save the attachment. We don't want to spend
             * the time downloading it and then abort.
             */
            Toast.makeText(this,
                    getString(R.string.message_view_status_attachment_not_saved),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        MessagingController.getInstance(getApplication()).loadAttachment(
                mAccount,
                mOldMessage,
                attachment.part,
                new Object[] { true, attachment },
                mListener);
    }

    private void onViewAttachment(Attachment attachment) {
        MessagingController.getInstance(getApplication()).loadAttachment(
                mAccount,
                mOldMessage,
                attachment.part,
                new Object[] { false, attachment },
                mListener);
    }

    private void onShowPictures() {
        if (mOldMessage != null) {
            mMessageContentView.getSettings().setBlockNetworkImage(false);
            mShowPicturesSection.setVisibility(View.GONE);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.from:
            case R.id.presence:
                onClickSender();
                break;
            case R.id.favorite:
                onClickFavorite();
                break;
            case R.id.reply:
                onReply();
                break;
            case R.id.reply_all:
                onReplyAll();
                break;
            case R.id.delete:
                onDelete();
                break;
            case R.id.next:
                onNext();
                break;
            case R.id.previous:
                onPrevious();
                break;
            case R.id.download:
                onDownloadAttachment((Attachment) view.getTag());
                break;
            case R.id.view:
                onViewAttachment((Attachment) view.getTag());
                break;
            case R.id.show_pictures:
                onShowPictures();
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
               onDelete();
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
               onMarkAsRead(false);
               finish();
               break;
           default:
               return false;
       }
       return true;
   }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.message_view_option, menu);
        return true;
    }

    private Bitmap getPreviewIcon(Attachment attachment) {
        try {
            return BitmapFactory.decodeStream(
                    getContentResolver().openInputStream(
                            AttachmentProvider.getAttachmentThumbnailUri(mAccount,
                                    attachment.part.getAttachmentId(),
                                    62,
                                    62)));
        }
        catch (Exception e) {
            /*
             * We don't care what happened, we just return null for the preview icon.
             */
            return null;
        }
    }

    /*
     * Formats the given size as a String in bytes, kB, MB or GB with a single digit
     * of precision. Ex: 12,315,000 = 12.3 MB
     */
    public static String formatSize(float size) {
        long kb = 1024;
        long mb = (kb * 1024);
        long gb  = (mb * 1024);
        if (size < kb) {
            return String.format("%d bytes", (int) size);
        }
        else if (size < mb) {
            return String.format("%.1f kB", size / kb);
        }
        else if (size < gb) {
            return String.format("%.1f MB", size / mb);
        }
        else {
            return String.format("%.1f GB", size / gb);
        }
    }

    private void updateAttachmentThumbnail(Part part) {
        for (int i = 0, count = mAttachments.getChildCount(); i < count; i++) {
            Attachment attachment = (Attachment) mAttachments.getChildAt(i).getTag();
            if (attachment.part == part) {
                Bitmap previewIcon = getPreviewIcon(attachment);
                if (previewIcon != null) {
                    mHandler.updateAttachmentIcon(i, previewIcon);
                }
                return;
            }
        }
    }

    private void renderAttachments(Part part, int depth) throws MessagingException {
        if (depth >= 10 || part == null) {
            return;
        }
        String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
        String name = MimeUtility.getHeaderParameter(contentType, "name");
        if (name != null) {
            /*
             * We're guaranteed size because LocalStore.fetch puts it there.
             */
            String contentDisposition = MimeUtility.unfoldAndDecode(part.getDisposition());
            int size = Integer.parseInt(MimeUtility.getHeaderParameter(contentDisposition, "size"));

            Attachment attachment = new Attachment();
            attachment.size = size;
            attachment.contentType = part.getMimeType();
            attachment.name = name;
            attachment.part = (LocalAttachmentBodyPart) part;

            LayoutInflater inflater = getLayoutInflater();
            View view = inflater.inflate(R.layout.message_view_attachment, null);

            TextView attachmentName = (TextView)view.findViewById(R.id.attachment_name);
            TextView attachmentInfo = (TextView)view.findViewById(R.id.attachment_info);
            ImageView attachmentIcon = (ImageView)view.findViewById(R.id.attachment_icon);
            Button attachmentView = (Button)view.findViewById(R.id.view);
            Button attachmentDownload = (Button)view.findViewById(R.id.download);

            if ((!MimeUtility.mimeTypeMatches(attachment.contentType,
                    Email.ACCEPTABLE_ATTACHMENT_VIEW_TYPES))
                    || (MimeUtility.mimeTypeMatches(attachment.contentType,
                            Email.UNACCEPTABLE_ATTACHMENT_VIEW_TYPES))) {
                attachmentView.setVisibility(View.GONE);
            }
            if ((!MimeUtility.mimeTypeMatches(attachment.contentType,
                    Email.ACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))
                    || (MimeUtility.mimeTypeMatches(attachment.contentType,
                            Email.UNACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES))) {
                attachmentDownload.setVisibility(View.GONE);
            }

            if (attachment.size > Email.MAX_ATTACHMENT_DOWNLOAD_SIZE) {
                attachmentView.setVisibility(View.GONE);
                attachmentDownload.setVisibility(View.GONE);
            }

            attachment.viewButton = attachmentView;
            attachment.downloadButton = attachmentDownload;
            attachment.iconView = attachmentIcon;

            view.setTag(attachment);
            attachmentView.setOnClickListener(this);
            attachmentView.setTag(attachment);
            attachmentDownload.setOnClickListener(this);
            attachmentDownload.setTag(attachment);

            attachmentName.setText(name);
            attachmentInfo.setText(formatSize(size));

            Bitmap previewIcon = getPreviewIcon(attachment);
            if (previewIcon != null) {
                attachmentIcon.setImageBitmap(previewIcon);
            }

            mHandler.addAttachment(view);
        }

        if (part.getBody() instanceof Multipart) {
            Multipart mp = (Multipart)part.getBody();
            for (int i = 0; i < mp.getCount(); i++) {
                renderAttachments(mp.getBodyPart(i), depth + 1);
            }
        }
    }
    
    /**
     * Launch a thread (because of cross-process DB lookup) to check presence of the sender of the
     * message.  When that thread completes, update the UI.
     * 
     * This must only be called when mMessage is null (it will hide presence indications) or when
     * mMessage has already seen its headers loaded.
     * 
     * Note:  This is just a polling operation.  A more advanced solution would be to keep the
     * cursor open and respond to presence status updates (in the form of content change
     * notifications).  However, because presence changes fairly slowly compared to the duration
     * of viewing a single message, a simple poll at message load (and onResume) should be
     * sufficient.
     */
    private void startPresenceCheck() {
        String email = null;        
        try {
            if (mOldMessage != null) {
                Address sender = mOldMessage.getFrom()[0];
                email = sender.getAddress();
            }
        } catch (MessagingException me) {
            // this will happen if message has illegal From:, ignore
        } catch (ArrayIndexOutOfBoundsException e) {
            // this will happen if message has no or illegal From:, ignore
        }
        if (email == null) {
            mHandler.setSenderPresence(0);
            return;
        }
        final String senderEmail = email;
        
        new Thread() {
            @Override
            public void run() {
                Cursor methodsCursor = getContentResolver().query(
                        Uri.withAppendedPath(Contacts.ContactMethods.CONTENT_URI, "with_presence"),
                        METHODS_WITH_PRESENCE_PROJECTION,
                        Contacts.ContactMethods.DATA + "=?",
                        new String[]{ senderEmail },
                        null);

                int presenceIcon = 0;

                if (methodsCursor != null) {
                    if (methodsCursor.moveToFirst() && 
                            !methodsCursor.isNull(METHODS_STATUS_COLUMN)) {
                        presenceIcon = Presence.getPresenceIconResourceId(
                                methodsCursor.getInt(METHODS_STATUS_COLUMN));
                    }
                    methodsCursor.close();
                }

                mHandler.setSenderPresence(presenceIcon);
            }
        }.start();
    }
    
    /**
     * Update the actual UI.  Must be called from main thread (or handler)
     * @param presenceIconId the presence of the sender, 0 for "unknown"
     */
    private void updateSenderPresence(int presenceIconId) {
        if (presenceIconId == 0) {
            // This is a placeholder used for "unknown" presence, including signed off,
            // no presence relationship.
            presenceIconId = R.drawable.presence_inactive;
        }
        mSenderPresenceView.setImageResource(presenceIconId);
    }
    
    /**
     * Async task for loading a single message outside of the UI thread
     * 
     * TODO: start and stop progress indicator
     * TODO: set up to listen for additional updates (cursor data changes)
     */
    private class LoadMessageTask extends AsyncTask<Void, Void, Cursor> {
        
        private long mMessageId;
        
        /**
         * Special constructor to cache some local info
         */
        public LoadMessageTask(long messageId) {
            mMessageId = messageId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MessageView.this.managedQuery(
                    EmailContent.Message.CONTENT_URI,
                    EmailContent.Message.CONTENT_PROJECTION,
                    EmailContent.RECORD_ID + "=?",
                    new String[] {
                            String.valueOf(mMessageId)
                            }, 
                    null);
        }
        
        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor.moveToFirst()) {
                reloadUiFromCursor(cursor);
            } else {
                // toast?  why would this fail?
            }
        }
    }
    
    /**
     * Async task for loading a single message body outside of the UI thread
     * 
     * TODO: smarter loading of html vs. text
     */
    private class LoadBodyTask extends AsyncTask<Void, Void, Cursor> {
        
        private long mMessageId;
        
        /**
         * Special constructor to cache some local info
         */
        public LoadBodyTask(long messageId) {
            mMessageId = messageId;
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return MessageView.this.managedQuery(
                    EmailContent.Body.CONTENT_URI,
                    BODY_CONTENT_PROJECTION,
                    EmailContent.Body.MESSAGE_KEY + "=?",
                    new String[] {
                            String.valueOf(mMessageId)
                            }, 
                    null);
        }
        
        @Override
        protected void onPostExecute(Cursor cursor) {
            if (cursor.moveToFirst()) {
                reloadBodyFromCursor(cursor);
            } else {
                // toast?  why would this fail?
                reloadBodyFromCursor(null);     // hack to force text for display
            }

            // At this point it's fair to mark the message as "read"
            onMarkAsRead(true);
        }
    }

    /**
     * Reload the UI from a provider cursor.  This must only be called from the UI thread.
     * 
     * @param cursor A cursor loaded from EmailStore.Message.
     * 
     * TODO: attachments
     * TODO: trigger presence check
     */
    private void reloadUiFromCursor(Cursor cursor) {
        EmailContent.Message message = EmailContent.getContent(cursor, EmailContent.Message.class);
        mMessage = message;
        
        mSubjectView.setText(message.mSubject);
        mFromView.setText(Address.toFriendly(Address.unpack(message.mFrom)));
        Date date = new Date(message.mTimeStamp);
        mTimeView.setText(mTimeFormat.format(date));
        mDateView.setText(Utility.isDateToday(date) ? null : mDateFormat.format(date));
        mToView.setText(Address.toFriendly(Address.unpack(message.mTo)));
        mCcView.setText(Address.toFriendly(Address.unpack(message.mCc)));
        mCcContainerView.setVisibility((message.mCc != null) ? View.VISIBLE : View.GONE);
        mAttachmentIcon.setVisibility(message.mAttachments != null ? View.VISIBLE : View.GONE);
        mFavoriteIcon.setImageDrawable(message.mFlagFavorite ? mFavoriteIconOn : mFavoriteIconOff);

        // Ask for body
        mLoadBodyTask = new LoadBodyTask(message.mId);
        mLoadBodyTask.execute();
    }

    /**
     * Reload the body from the provider cursor.  This must only be called from the UI thread.
     * 
     * @param cursor
     * 
     * TODO deal with html vs text and many other issues
     */
    private void reloadBodyFromCursor(Cursor cursor) {
        // TODO Remove this hack that forces some text to test the code
        String text = null;
        if (cursor != null) {
            text = cursor.getString(BODY_CONTENT_COLUMN_TEXT_CONTENT);
        }
        if (text == null) {
            text = "";
        }
        
        // This code is stolen from Listener.loadMessageForViewBodyAvailable
        // And also escape special character, such as "<>&",
        // to HTML escape sequence.
        text = EmailHtmlUtil.escapeCharacterToDisplay(text);

        /*
         * Linkify the plain text and convert it to HTML by replacing
         * \r?\n with <br> and adding a html/body wrapper.
         */
        StringBuffer sb = new StringBuffer("<html><body>");
        if (text != null) {
            Matcher m = Regex.WEB_URL_PATTERN.matcher(text);
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
                        // Regex.WEB_URL_PATTERN matches URL without protocol part,
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
       
        if (mMessageContentView != null) {
            mMessageContentView.loadDataWithBaseURL("email://", text, "text/html", "utf-8", null);
        }
    }

    class Listener extends MessagingListener {
        @Override
        public void loadMessageForViewHeadersAvailable(EmailContent.Account account, String folder,
                String uid, final Message message) {
            MessageView.this.mOldMessage = message;
            try {
                String subjectText = message.getSubject();
                String fromText = Address.toFriendly(message.getFrom());
                Date sentDate = message.getSentDate();
                String timeText = mTimeFormat.format(sentDate);
                String dateText = Utility.isDateToday(sentDate) ? null : 
                        mDateFormat.format(sentDate);
                String toText = Address.toFriendly(message.getRecipients(RecipientType.TO));
                String ccText = Address.toFriendly(message.getRecipients(RecipientType.CC));
                boolean hasAttachments = ((LocalMessage) message).getAttachmentCount() > 0;
                mHandler.setHeaders(subjectText,
                        fromText,
                        timeText,
                        dateText,
                        toText,
                        ccText,
                        hasAttachments);
                startPresenceCheck();
            }
            catch (MessagingException me) {
                if (Config.LOGV) {
                    Log.v(Email.LOG_TAG, "loadMessageForViewHeadersAvailable", me);
                }
            }
        }

        @Override
        public void loadMessageForViewBodyAvailable(EmailContent.Account account, String folder,
                String uid, Message message) {
            MessageView.this.mOldMessage = message;
            try {
                Part part = MimeUtility.findFirstPartByMimeType(mOldMessage, "text/html");
                if (part == null) {
                    part = MimeUtility.findFirstPartByMimeType(mOldMessage, "text/plain");
                }
                if (part != null) {
                    String text = MimeUtility.getTextFromPart(part);
                    if (part.getMimeType().equalsIgnoreCase("text/html")) {
                        text = EmailHtmlUtil.resolveInlineImage(
                                getContentResolver(), mAccount, text, mOldMessage, 0);
                    } else {
                        // And also escape special character, such as "<>&",
                        // to HTML escape sequence.
                        text = EmailHtmlUtil.escapeCharacterToDisplay(text);

                        /*
                         * Linkify the plain text and convert it to HTML by replacing
                         * \r?\n with <br> and adding a html/body wrapper.
                         */
                        StringBuffer sb = new StringBuffer("<html><body>");
                        if (text != null) {
                            Matcher m = Regex.WEB_URL_PATTERN.matcher(text);
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
                                        // Regex.WEB_URL_PATTERN matches URL without protocol part,
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
                    }

                    /*
                     * TODO consider how to get background images and a million other things
                     * that HTML allows.
                     */
                    // Check if text contains img tag.
                    if (IMG_TAG_START_REGEX.matcher(text).find()) {
                        mHandler.showShowPictures(true);
                    }

                    loadMessageContentText(text);
                }
                else {
                    loadMessageContentUrl("file:///android_asset/empty.html");
                }
                renderAttachments(mOldMessage, 0);
            }
            catch (Exception e) {
                if (Config.LOGV) {
                    Log.v(Email.LOG_TAG, "loadMessageForViewBodyAvailable", e);
                }
            }
        }

        @Override
        public void loadMessageForViewFailed(EmailContent.Account account, String folder, String uid,
                final String message) {
            mHandler.post(new Runnable() {
                public void run() {
                    setProgressBarIndeterminateVisibility(false);
                    mHandler.networkError();
                    loadMessageContentUrl("file:///android_asset/empty.html");
                }
            });
        }

        @Override
        public void loadMessageForViewFinished(EmailContent.Account account, String folder,
                String uid, Message message) {
            mHandler.post(new Runnable() {
                public void run() {
                    setProgressBarIndeterminateVisibility(false);
                }
            });
        }

        @Override
        public void loadMessageForViewStarted(EmailContent.Account account, String folder, String uid)
                {
            mHandler.post(new Runnable() {
                public void run() {
                    loadMessageContentUrl("file:///android_asset/loading.html");
                    setProgressBarIndeterminateVisibility(true);
                }
            });
        }
        
        @Override
        public void loadAttachmentStarted(EmailContent.Account account, Message message,
                Part part, Object tag, boolean requiresDownload) {
            mHandler.setAttachmentsEnabled(false);
            Object[] params = (Object[]) tag;
            mHandler.progress(true, ((Attachment) params[1]).name);
            if (requiresDownload) {
                mHandler.fetchingAttachment();
            }
        }

        @Override
        public void loadAttachmentFinished(EmailContent.Account account, Message message,
                Part part, Object tag) {
            mHandler.setAttachmentsEnabled(true);
            mHandler.progress(false, null);
            updateAttachmentThumbnail(part);

            Object[] params = (Object[]) tag;
            boolean download = (Boolean) params[0];
            Attachment attachment = (Attachment) params[1];

            if (download) {
                try {
                    File file = createUniqueFile(Environment.getExternalStorageDirectory(),
                            attachment.name);
                    Uri uri = AttachmentProvider.resolveAttachmentIdToContentUri(
                            getContentResolver(), AttachmentProvider.getAttachmentUri(
                                    mAccount, attachment.part.getAttachmentId()));
                    InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(file);
                    IOUtils.copy(in, out);
                    out.flush();
                    out.close();
                    in.close();
                    mHandler.attachmentSaved(file.getName());
                    new MediaScannerNotifier(MessageView.this, file, mHandler);
                }
                catch (IOException ioe) {
                    mHandler.attachmentNotSaved();
                }
            }
            else {
                try {
                    Uri uri = AttachmentProvider.resolveAttachmentIdToContentUri(
                            getContentResolver(), AttachmentProvider.getAttachmentUri(
                                    mAccount, attachment.part.getAttachmentId()));
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    mHandler.attachmentViewError(); 
                    // TODO: Add a proper warning message (and lots of upstream cleanup to prevent 
                    // it from happening) in the next release.
                }
            }
        }

        @Override
        public void loadAttachmentFailed(EmailContent.Account account, Message message, Part part,
                Object tag, String reason) {
            mHandler.setAttachmentsEnabled(true);
            mHandler.progress(false, null);
            mHandler.networkError();
        }
        
        /**
         * Safely load a URL for mMessageContentView, or drop it if the view is gone
         * TODO this really should be moved into a handler message, avoiding the need
         * for this synchronized() section
         */
        private void loadMessageContentUrl(String fileName) {
            synchronized (MessageView.this) {
                if (mMessageContentView != null) {
                    mMessageContentView.loadUrl(fileName);
                }
            }
        }
        
        /**
         * Safely load text for mMessageContentView, or drop it if the view is gone
         * TODO this really should be moved into a handler message, avoiding the need
         * for this synchronized() section
         */
        private void loadMessageContentText(String text) {
            synchronized (MessageView.this) {
                if (mMessageContentView != null) {
                    mMessageContentView.loadDataWithBaseURL("email://", text, "text/html",
                            "utf-8", null);
                }
            }
        }
    }

    /**
     * This notifier is created after an attachment completes downloaded.  It attaches to the
     * media scanner and waits to handle the completion of the scan.  At that point it tries
     * to start an ACTION_VIEW activity for the attachment.
    */
    private static class MediaScannerNotifier implements MediaScannerConnectionClient {
        private Context mContext;
        private MediaScannerConnection mConnection;
        private File mFile;
        MessageViewHandler mHandler;

        public MediaScannerNotifier(Context context, File file, MessageViewHandler handler) {
            mContext = context;
            mFile = file;
            mHandler = handler;
            mConnection = new MediaScannerConnection(context, this);
            mConnection.connect();
        }

        public void onMediaScannerConnected() {
            mConnection.scanFile(mFile.getAbsolutePath(), null);
        }

        public void onScanCompleted(String path, Uri uri) {
            try {
                if (uri != null) {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    mContext.startActivity(intent);
                }
            } catch (ActivityNotFoundException e) {
                mHandler.attachmentViewError(); 
                // TODO: Add a proper warning message (and lots of upstream cleanup to prevent 
                // it from happening) in the next release.
            } finally {
                mConnection.disconnect();
                mContext = null;
                mHandler = null;
            }
        }
    }
}
