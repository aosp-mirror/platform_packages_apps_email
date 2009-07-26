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

import org.apache.commons.io.IOUtils;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Process;
import android.provider.Contacts;
import android.provider.Contacts.Intents;
import android.provider.Contacts.People;
import android.provider.Contacts.Presence;
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
import java.util.regex.Pattern;

public class MessageView extends Activity
        implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "com.android.email.MessageView_account";
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
    
    private TextView mSubjectView;
    private TextView mFromView;
    private TextView mDateView;
    private TextView mTimeView;
    private TextView mToView;
    private TextView mCcView;
    private TextView mBccView;
    private View mCcContainerView;
    private View mBccContainerView;
    private WebView mMessageContentView;
    private LinearLayout mAttachments;
    private ImageView mAttachmentIcon;
    private View mShowPicturesSection;
    private ImageView mSenderPresenceView;
    private ProgressDialog mProgressDialog;

    private Account mAccount;
    private String mFolder;
    private String mMessageUid;
    private ArrayList<String> mFolderUids;

    private Message mMessage;
    private String mNextMessageUid = null;
    private String mPreviousMessageUid = null;

    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat;

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
        private static final int MSG_FETCHING_PICTURES = 17;
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
                    String bcc = values[6];
                    mBccView.setText(bcc);
                    mBccContainerView.setVisibility(bcc != null ? View.VISIBLE : View.GONE);
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
                case MSG_FETCHING_PICTURES:
                    Toast.makeText(MessageView.this,
                            getString(R.string.message_view_fetching_pictures_toast),
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
                String bcc,
                boolean hasAttachments) {
            android.os.Message msg = new android.os.Message();
            msg.what = MSG_SET_HEADERS;
            msg.arg1 = hasAttachments ? 1 : 0;
            msg.obj = new String[] { subject, from, time, date, to, cc, bcc};
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

        public void fetchingPictures() {
            sendEmptyMessage(MSG_FETCHING_PICTURES);
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

    public static void actionView(Context context, Account account,
            String folder, String messageUid, ArrayList<String> folderUids) {
        actionView(context, account, folder, messageUid, folderUids, null);
    }

    public static void actionView(Context context, Account account,
            String folder, String messageUid, ArrayList<String> folderUids, Bundle extras) {
        Intent i = new Intent(context, MessageView.class);
        i.putExtra(EXTRA_ACCOUNT, account);
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
        mBccView = (TextView) findViewById(R.id.bcc);
        mBccContainerView = findViewById(R.id.bcc_container);
        mDateView = (TextView) findViewById(R.id.date);
        mTimeView = (TextView) findViewById(R.id.time);
        mMessageContentView = (WebView) findViewById(R.id.message_content);
        mAttachments = (LinearLayout) findViewById(R.id.attachments);
        mAttachmentIcon = (ImageView) findViewById(R.id.attachment);
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
        findViewById(R.id.reply).setOnClickListener(this);
        findViewById(R.id.reply_all).setOnClickListener(this);
        findViewById(R.id.delete).setOnClickListener(this);
        findViewById(R.id.show_pictures).setOnClickListener(this);

        mMessageContentView.getSettings().setBlockNetworkImage(true);
        mMessageContentView.getSettings().setSupportZoom(false);

        setTitle("");
        
        mDateFormat = android.text.format.DateFormat.getDateFormat(this);   // short format
        mTimeFormat = android.text.format.DateFormat.getTimeFormat(this);   // 12/24 date format

        Intent intent = getIntent();
        mAccount = (Account) intent.getSerializableExtra(EXTRA_ACCOUNT);
        mFolder = intent.getStringExtra(EXTRA_FOLDER);
        mMessageUid = intent.getStringExtra(EXTRA_MESSAGE);
        mFolderUids = intent.getStringArrayListExtra(EXTRA_FOLDER_UIDS);

        View next = findViewById(R.id.next);
        View previous = findViewById(R.id.previous);
        /*
         * Next and Previous Message are not shown in landscape mode, so
         * we need to check before we use them.
         */
        if (next != null && previous != null) {
            next.setOnClickListener(this);
            previous.setOnClickListener(this);

            findSurroundingMessagesUid();

            previous.setVisibility(mPreviousMessageUid != null ? View.VISIBLE : View.GONE);
            next.setVisibility(mNextMessageUid != null ? View.VISIBLE : View.GONE);

            boolean goNext = intent.getBooleanExtra(EXTRA_NEXT, false);
            if (goNext) {
                next.requestFocus();
            }
        }

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
    }

    private void findSurroundingMessagesUid() {
        for (int i = 0, count = mFolderUids.size(); i < count; i++) {
            String messageUid = mFolderUids.get(i);
            if (messageUid.equals(mMessageUid)) {
                if (i != 0) {
                    mPreviousMessageUid = mFolderUids.get(i - 1);
                }

                if (i != count - 1) {
                    mNextMessageUid = mFolderUids.get(i + 1);
                }
                break;
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        MessagingController.getInstance(getApplication()).addListener(mListener);
        if (mMessage != null) {
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
        // This is synchronized because the listener accesses mMessageContentView from its thread
        synchronized (this) {
            mMessageContentView.destroy();
            mMessageContentView = null;
        }
    }

    private void onDelete() {
        if (mMessage != null) {
            MessagingController.getInstance(getApplication()).deleteMessage(
                    mAccount,
                    mFolder,
                    mMessage,
                    null);
            Toast.makeText(this, R.string.message_deleted_toast, Toast.LENGTH_SHORT).show();

            // Remove this message's Uid locally
            mFolderUids.remove(mMessage.getUid());
            // Check if we have previous/next messages available before choosing
            // which one to display
            findSurroundingMessagesUid();

            if (mPreviousMessageUid != null) {
                onPrevious();
            } else if (mNextMessageUid != null) {
                onNext();
            } else {
                finish();
            }
        }
    }
    
    private void onClickSender() {
        if (mMessage != null) {
            try {
                Address senderEmail = mMessage.getFrom()[0];
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

    private void onReply() {
        if (mMessage != null) {
            MessageCompose.actionReply(this, mAccount, mMessage, false);
            finish();
        }
    }

    private void onReplyAll() {
        if (mMessage != null) {
            MessageCompose.actionReply(this, mAccount, mMessage, true);
            finish();
        }
    }

    private void onForward() {
        if (mMessage != null) {
            MessageCompose.actionForward(this, mAccount, mMessage);
            finish();
        }
    }

    private void onNext() {
        Bundle extras = new Bundle(1);
        extras.putBoolean(EXTRA_NEXT, true);
        MessageView.actionView(this, mAccount, mFolder, mNextMessageUid, mFolderUids, extras);
        finish();
    }

    private void onPrevious() {
        MessageView.actionView(this, mAccount, mFolder, mPreviousMessageUid, mFolderUids);
        finish();
    }

    private void onMarkAsUnread() {
        if (mMessage != null) {
            MessagingController.getInstance(getApplication()).markMessageRead(
                    mAccount,
                    mFolder,
                    mMessage.getUid(),
                    false);
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
                mMessage,
                attachment.part,
                new Object[] { true, attachment },
                mListener);
    }

    private void onViewAttachment(Attachment attachment) {
        MessagingController.getInstance(getApplication()).loadAttachment(
                mAccount,
                mMessage,
                attachment.part,
                new Object[] { false, attachment },
                mListener);
    }

    private void onShowPictures() {
        if (mMessage != null) {
            mMessageContentView.getSettings().setBlockNetworkImage(false);
            mShowPicturesSection.setVisibility(View.GONE);
            new Thread() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    MessagingController.getInstance(getApplication()).loadInlineImagesForView(
                            mAccount,
                            mMessage,
                            mListener);
                }
            }.start();
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.from:
            case R.id.presence:
                onClickSender();
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
               onMarkAsUnread();
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
            if (mMessage != null) {
                Address sender = mMessage.getFrom()[0];
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

    class Listener extends MessagingListener {
        @Override
        public void loadMessageForViewHeadersAvailable(Account account, String folder, String uid,
                final Message message) {
            MessageView.this.mMessage = message;
            try {
                String subjectText = message.getSubject();
                String fromText = Address.toFriendly(message.getFrom());
                Date sentDate = message.getSentDate();
                String timeText = mTimeFormat.format(sentDate);
                String dateText = Utility.isDateToday(sentDate) ? null : 
                        mDateFormat.format(sentDate);
                String toText = Address.toFriendly(message.getRecipients(RecipientType.TO));
                String ccText = Address.toFriendly(message.getRecipients(RecipientType.CC));
                String bccText = Address.toFriendly(message.getRecipients(RecipientType.BCC));
                boolean hasAttachments = ((LocalMessage) message).getAttachmentCount() > 0;
                mHandler.setHeaders(subjectText,
                        fromText,
                        timeText,
                        dateText,
                        toText,
                        ccText,
                        bccText,
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
        public void loadMessageForViewBodyAvailable(Account account, String folder, String uid,
                Message message) {
            MessageView.this.mMessage = message;
            String text = EmailHtmlUtil.renderMessageText(MessageView.this, account, message);
            if (text != null) {
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
            try {
                renderAttachments(mMessage, 0);
            } catch (MessagingException me) {
                // ignore
            }
        }

        @Override
        public void loadMessageForViewFailed(Account account, String folder, String uid,
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
        public void loadMessageForViewFinished(Account account, String folder, String uid,
                Message message) {
            mHandler.post(new Runnable() {
                public void run() {
                    setProgressBarIndeterminateVisibility(false);
                }
            });
        }

        @Override
        public void loadMessageForViewStarted(Account account, String folder, String uid) {
            mHandler.post(new Runnable() {
                public void run() {
                    loadMessageContentUrl("file:///android_asset/loading.html");
                    setProgressBarIndeterminateVisibility(true);
                }
            });
        }

        @Override
        public void loadInlineImagesForViewStarted(Account account, Message message) {
            mHandler.fetchingPictures();
        }
        
        @Override
        public void loadInlineImagesForViewOneAvailable(final Account account,
                final Message message, final Part part) {
            mHandler.post(new Runnable() {
                public void run() {
                    String text = EmailHtmlUtil.renderMessageText(
                            MessageView.this, account, message);
                    if (text != null) {
                        loadMessageContentText(text);
                        updateAttachmentThumbnail(part);
                    }
                }
            });
        }
        
        @Override
        public void loadInlineImagesForViewFinished(Account account, Message message) {
            mHandler.progress(false, null);
        }
        
        @Override
        public void loadInlineImagesForViewFailed(Account account, Message message) {
            mHandler.progress(false, null);
        }

        @Override
        public void loadAttachmentStarted(Account account, Message message,
                Part part, Object tag, boolean requiresDownload) {
            mHandler.setAttachmentsEnabled(false);
            Object[] params = (Object[]) tag;
            mHandler.progress(true, ((Attachment) params[1]).name);
            if (requiresDownload) {
                mHandler.fetchingAttachment();
            }
        }

        @Override
        public void loadAttachmentFinished(Account account, Message message,
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
        public void loadAttachmentFailed(Account account, Message message, Part part,
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
