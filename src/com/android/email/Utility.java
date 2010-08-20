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

package com.android.email;

import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.provider.EmailContent.HostAuthColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Parcelable;
import android.security.MessageDigest;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.AbsListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Pattern;

public class Utility {
    public static final Charset UTF_8 = Charset.forName("UTF-8");
    public static final Charset ASCII = Charset.forName("US-ASCII");

    public static final String[] EMPTY_STRINGS = new String[0];
    public static final Long[] EMPTY_LONGS = new Long[0];

    // "GMT" + "+" or "-" + 4 digits
    private static final Pattern DATE_CLEANUP_PATTERN_WRONG_TIMEZONE =
            Pattern.compile("GMT([-+]\\d{4})$");

    public final static String readInputStream(InputStream in, String encoding) throws IOException {
        InputStreamReader reader = new InputStreamReader(in, encoding);
        StringBuffer sb = new StringBuffer();
        int count;
        char[] buf = new char[512];
        while ((count = reader.read(buf)) != -1) {
            sb.append(buf, 0, count);
        }
        return sb.toString();
    }

    public final static boolean arrayContains(Object[] a, Object o) {
        for (int i = 0, count = a.length; i < count; i++) {
            if (a[i].equals(o)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Combines the given array of Objects into a single string using the
     * seperator character and each Object's toString() method. between each
     * part.
     *
     * @param parts
     * @param seperator
     * @return
     */
    public static String combine(Object[] parts, char seperator) {
        if (parts == null) {
            return null;
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < parts.length; i++) {
            sb.append(parts[i].toString());
            if (i < parts.length - 1) {
                sb.append(seperator);
            }
        }
        return sb.toString();
    }
    public static String base64Decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
        return new String(decoded);
    }

    public static String base64Encode(String s) {
        if (s == null) {
            return s;
        }
        return Base64.encodeToString(s.getBytes(), Base64.NO_WRAP);
    }

    public static boolean isTextViewNotEmpty(TextView view) {
        return !TextUtils.isEmpty(view.getText());
    }

    public static boolean isPortFieldValid(TextView view) {
        CharSequence chars = view.getText();
        if (TextUtils.isEmpty(chars)) return false;
        Integer port;
        // In theory, we can't get an illegal value here, since the field is monitored for valid
        // numeric input. But this might be used elsewhere without such a check.
        try {
            port = Integer.parseInt(chars.toString());
        } catch (NumberFormatException e) {
            return false;
        }
        return port > 0 && port < 65536;
     }

    /**
     * Ensures that the given string starts and ends with the double quote character. The string is
     * not modified in any way except to add the double quote character to start and end if it's not
     * already there.
     *
     * TODO: Rename this, because "quoteString()" can mean so many different things.
     *
     * sample -> "sample"
     * "sample" -> "sample"
     * ""sample"" -> "sample"
     * "sample"" -> "sample"
     * sa"mp"le -> "sa"mp"le"
     * "sa"mp"le" -> "sa"mp"le"
     * (empty string) -> ""
     * " -> ""
     * @param s
     * @return
     */
    public static String quoteString(String s) {
        if (s == null) {
            return null;
        }
        if (!s.matches("^\".*\"$")) {
            return "\"" + s + "\"";
        }
        else {
            return s;
        }
    }

    /**
     * Apply quoting rules per IMAP RFC,
     * quoted          = DQUOTE *QUOTED-CHAR DQUOTE
     * QUOTED-CHAR     = <any TEXT-CHAR except quoted-specials> / "\" quoted-specials
     * quoted-specials = DQUOTE / "\"
     *
     * This is used primarily for IMAP login, but might be useful elsewhere.
     *
     * NOTE:  Not very efficient - you may wish to preflight this, or perhaps it should check
     * for trouble chars before calling the replace functions.
     *
     * @param s The string to be quoted.
     * @return A copy of the string, having undergone quoting as described above
     */
    public static String imapQuoted(String s) {

        // First, quote any backslashes by replacing \ with \\
        // regex Pattern:  \\    (Java string const = \\\\)
        // Substitute:     \\\\  (Java string const = \\\\\\\\)
        String result = s.replaceAll("\\\\", "\\\\\\\\");

        // Then, quote any double-quotes by replacing " with \"
        // regex Pattern:  "    (Java string const = \")
        // Substitute:     \\"  (Java string const = \\\\\")
        result = result.replaceAll("\"", "\\\\\"");

        // return string with quotes around it
        return "\"" + result + "\"";
    }

    /**
     * A fast version of  URLDecoder.decode() that works only with UTF-8 and does only two
     * allocations. This version is around 3x as fast as the standard one and I'm using it
     * hundreds of times in places that slow down the UI, so it helps.
     */
    public static String fastUrlDecode(String s) {
        try {
            byte[] bytes = s.getBytes("UTF-8");
            byte ch;
            int length = 0;
            for (int i = 0, count = bytes.length; i < count; i++) {
                ch = bytes[i];
                if (ch == '%') {
                    int h = (bytes[i + 1] - '0');
                    int l = (bytes[i + 2] - '0');
                    if (h > 9) {
                        h -= 7;
                    }
                    if (l > 9) {
                        l -= 7;
                    }
                    bytes[length] = (byte) ((h << 4) | l);
                    i += 2;
                }
                else if (ch == '+') {
                    bytes[length] = ' ';
                }
                else {
                    bytes[length] = bytes[i];
                }
                length++;
            }
            return new String(bytes, 0, length, "UTF-8");
        }
        catch (UnsupportedEncodingException uee) {
            return null;
        }
    }

    /**
     * Returns true if the specified date is within today. Returns false otherwise.
     * @param date
     * @return
     */
    public static boolean isDateToday(Date date) {
        // TODO But Calendar is so slowwwwwww....
        Date today = new Date();
        if (date.getYear() == today.getYear() &&
                date.getMonth() == today.getMonth() &&
                date.getDate() == today.getDate()) {
            return true;
        }
        return false;
    }

    /*
     * TODO disabled this method globally. It is used in all the settings screens but I just
     * noticed that an unrelated icon was dimmed. Android must share drawables internally.
     */
    public static void setCompoundDrawablesAlpha(TextView view, int alpha) {
//        Drawable[] drawables = view.getCompoundDrawables();
//        for (Drawable drawable : drawables) {
//            if (drawable != null) {
//                drawable.setAlpha(alpha);
//            }
//        }
    }

    // TODO: unit test this
    public static String buildMailboxIdSelection(ContentResolver resolver, long mailboxId) {
        // Setup default selection & args, then add to it as necessary
        StringBuilder selection = new StringBuilder(
                MessageColumns.FLAG_LOADED + " IN ("
                + Message.FLAG_LOADED_PARTIAL + "," + Message.FLAG_LOADED_COMPLETE
                + ") AND ");
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES
            || mailboxId == Mailbox.QUERY_ALL_DRAFTS
            || mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            // query for all mailboxes of type INBOX, DRAFTS, or OUTBOX
            int type;
            if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
                type = Mailbox.TYPE_INBOX;
            } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
                type = Mailbox.TYPE_DRAFTS;
            } else {
                type = Mailbox.TYPE_OUTBOX;
            }
            StringBuilder inboxes = new StringBuilder();
            Cursor c = resolver.query(Mailbox.CONTENT_URI,
                        EmailContent.ID_PROJECTION,
                        MailboxColumns.TYPE + "=? AND " + MailboxColumns.FLAG_VISIBLE + "=1",
                        new String[] { Integer.toString(type) }, null);
            // build an IN (mailboxId, ...) list
            // TODO do this directly in the provider
            while (c.moveToNext()) {
                if (inboxes.length() != 0) {
                    inboxes.append(",");
                }
                inboxes.append(c.getLong(EmailContent.ID_PROJECTION_COLUMN));
            }
            c.close();
            selection.append(MessageColumns.MAILBOX_KEY + " IN ");
            selection.append("(").append(inboxes).append(")");
        } else  if (mailboxId == Mailbox.QUERY_ALL_UNREAD) {
            selection.append(Message.FLAG_READ + "=0");
        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            selection.append(Message.FLAG_FAVORITE + "=1");
        } else {
            selection.append(MessageColumns.MAILBOX_KEY + "=" + mailboxId);
        }
        return selection.toString();
    }

    // TODO When the UI is settled, cache all strings/drawables
    // TODO When the UI is settled, write up tests
    // TODO When the UI is settled, remove backward-compatibility methods
    public static class FolderProperties {

        private static FolderProperties sInstance;

        private final Context mContext;

        // Caches for frequently accessed resources.
        private final String[] mSpecialMailbox;
        private final TypedArray mSpecialMailboxDrawable;
        private final Drawable mDefaultMailboxDrawable;
        private final Drawable mSummaryStarredMailboxDrawable;
        private final Drawable mSummaryCombinedInboxDrawable;

        private FolderProperties(Context context) {
            mContext = context.getApplicationContext();
            mSpecialMailbox = context.getResources().getStringArray(R.array.mailbox_display_names);
            for (int i = 0; i < mSpecialMailbox.length; ++i) {
                if ("".equals(mSpecialMailbox[i])) {
                    // there is no localized name, so use the display name from the server
                    mSpecialMailbox[i] = null;
                }
            }
            mSpecialMailboxDrawable =
                context.getResources().obtainTypedArray(R.array.mailbox_display_icons);
            mDefaultMailboxDrawable =
                context.getResources().getDrawable(R.drawable.ic_list_folder);
            mSummaryStarredMailboxDrawable =
                context.getResources().getDrawable(R.drawable.ic_list_starred);
            mSummaryCombinedInboxDrawable =
                context.getResources().getDrawable(R.drawable.ic_list_combined_inbox);
        }

        public static synchronized FolderProperties getInstance(Context context) {
            if (sInstance == null) {
                sInstance = new FolderProperties(context);
            }
            return sInstance;
        }

        // For backward compatibility.
        public String getDisplayName(int type) {
            return getDisplayName(type, -1);
        }

        // For backward compatibility.
        public Drawable getSummaryMailboxIconIds(long id) {
            return getIcon(-1, id);
        }

        public Drawable getIconIds(int type) {
            return getIcon(type, -1);
        }

        /**
         * Lookup names of localized special mailboxes
         */
        public String getDisplayName(int type, long mailboxId) {
            // Special combined mailboxes
            int resId = 0;

            // Can't use long for switch!?
            if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
                resId = R.string.account_folder_list_summary_inbox;
            } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
                resId = R.string.account_folder_list_summary_starred;
            } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
                resId = R.string.account_folder_list_summary_drafts;
            } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
                resId = R.string.account_folder_list_summary_outbox;
            }
            if (resId != 0) {
                return mContext.getString(resId);
            }

            if (type < mSpecialMailbox.length) {
                return mSpecialMailbox[type];
            }
            return null;
        }

        /**
         * Lookup icons of special mailboxes
         */
        public Drawable getIcon(int type, long mailboxId) {
            if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
                return mSummaryCombinedInboxDrawable;
            } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
                return mSummaryStarredMailboxDrawable;
            } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
                return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_DRAFTS);
            } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
                return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_OUTBOX);
            }
            if (0 <= type && type < mSpecialMailboxDrawable.length()) {
                return mSpecialMailboxDrawable.getDrawable(type);
            }
            return mDefaultMailboxDrawable;
        }
    }

    private final static String HOSTAUTH_WHERE_CREDENTIALS = HostAuthColumns.ADDRESS + " like ?"
            + " and " + HostAuthColumns.LOGIN + " like ?"
            + " and " + HostAuthColumns.PROTOCOL + " not like \"smtp\"";
    private final static String ACCOUNT_WHERE_HOSTAUTH = AccountColumns.HOST_AUTH_KEY_RECV + "=?";

    /**
     * Look for an existing account with the same username & server
     *
     * @param context a system context
     * @param allowAccountId this account Id will not trigger (when editing an existing account)
     * @param hostName the server's address
     * @param userLogin the user's login string
     * @result null = no matching account found.  Account = matching account
     */
    public static Account findExistingAccount(Context context, long allowAccountId,
            String hostName, String userLogin) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(HostAuth.CONTENT_URI, HostAuth.ID_PROJECTION,
                HOSTAUTH_WHERE_CREDENTIALS, new String[] { hostName, userLogin }, null);
        try {
            while (c.moveToNext()) {
                long hostAuthId = c.getLong(HostAuth.ID_PROJECTION_COLUMN);
                // Find account with matching hostauthrecv key, and return it
                Cursor c2 = resolver.query(Account.CONTENT_URI, Account.ID_PROJECTION,
                        ACCOUNT_WHERE_HOSTAUTH, new String[] { Long.toString(hostAuthId) }, null);
                try {
                    while (c2.moveToNext()) {
                        long accountId = c2.getLong(Account.ID_PROJECTION_COLUMN);
                        if (accountId != allowAccountId) {
                            Account account = Account.restoreAccountWithId(context, accountId);
                            if (account != null) {
                                return account;
                            }
                        }
                    }
                } finally {
                    c2.close();
                }
            }
        } finally {
            c.close();
        }

        return null;
    }

    /**
     * Generate a random message-id header for locally-generated messages.
     */
    public static String generateMessageId() {
        StringBuffer sb = new StringBuffer();
        sb.append("<");
        for (int i = 0; i < 24; i++) {
            sb.append(Integer.toString((int)(Math.random() * 35), 36));
        }
        sb.append(".");
        sb.append(Long.toString(System.currentTimeMillis()));
        sb.append("@email.android.com>");
        return sb.toString();
    }

    /**
     * Generate a time in milliseconds from a date string that represents a date/time in GMT
     * @param DateTime date string in format 20090211T180303Z (rfc2445, iCalendar).
     * @return the time in milliseconds (since Jan 1, 1970)
     */
    public static long parseDateTimeToMillis(String date) {
        GregorianCalendar cal = parseDateTimeToCalendar(date);
        return cal.getTimeInMillis();
    }

    /**
     * Generate a GregorianCalendar from a date string that represents a date/time in GMT
     * @param DateTime date string in format 20090211T180303Z (rfc2445, iCalendar).
     * @return the GregorianCalendar
     */
    public static GregorianCalendar parseDateTimeToCalendar(String date) {
        GregorianCalendar cal = new GregorianCalendar(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(4, 6)) - 1, Integer.parseInt(date.substring(6, 8)),
                Integer.parseInt(date.substring(9, 11)), Integer.parseInt(date.substring(11, 13)),
                Integer.parseInt(date.substring(13, 15)));
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        return cal;
    }

    /**
     * Generate a time in milliseconds from an email date string that represents a date/time in GMT
     * @param Email style DateTime string in format 2010-02-23T16:00:00.000Z (ISO 8601, rfc3339)
     * @return the time in milliseconds (since Jan 1, 1970)
     */
    public static long parseEmailDateTimeToMillis(String date) {
        GregorianCalendar cal = new GregorianCalendar(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)),
                Integer.parseInt(date.substring(11, 13)), Integer.parseInt(date.substring(14, 16)),
                Integer.parseInt(date.substring(17, 19)));
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        return cal.getTimeInMillis();
    }

    private static byte[] encode(Charset charset, String s) {
        if (s == null) {
            return null;
        }
        final ByteBuffer buffer = charset.encode(CharBuffer.wrap(s));
        final byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        return bytes;
    }

    private static String decode(Charset charset, byte[] b) {
        if (b == null) {
            return null;
        }
        final CharBuffer cb = charset.decode(ByteBuffer.wrap(b));
        return new String(cb.array(), 0, cb.length());
    }

    /** Converts a String to UTF-8 */
    public static byte[] toUtf8(String s) {
        return encode(UTF_8, s);
    }

    /** Builds a String from UTF-8 bytes */
    public static String fromUtf8(byte[] b) {
        return decode(UTF_8, b);
    }

    /** Converts a String to ASCII bytes */
    public static byte[] toAscii(String s) {
        return encode(ASCII, s);
    }

    /** Builds a String from ASCII bytes */
    public static String fromAscii(byte[] b) {
        return decode(ASCII, b);
    }

    /**
     * @return true if the input is the first (or only) byte in a UTF-8 character
     */
    public static boolean isFirstUtf8Byte(byte b) {
        // If the top 2 bits is '10', it's not a first byte.
        return (b & 0xc0) != 0x80;
    }

    public static String byteToHex(int b) {
        return byteToHex(new StringBuilder(), b).toString();
    }

    public static StringBuilder byteToHex(StringBuilder sb, int b) {
        b &= 0xFF;
        sb.append("0123456789ABCDEF".charAt(b >> 4));
        sb.append("0123456789ABCDEF".charAt(b & 0xF));
        return sb;
    }

    public static String replaceBareLfWithCrlf(String str) {
        return str.replace("\r", "").replace("\n", "\r\n");
    }

    /**
     * Cancel an {@link AsyncTask}.  If it's already running, it'll be interrupted.
     */
    public static void cancelTaskInterrupt(AsyncTask<?, ?, ?> task) {
        cancelTask(task, true);
    }

    /**
     * Cancel an {@link AsyncTask}.
     *
     * @param mayInterruptIfRunning <tt>true</tt> if the thread executing this
     *        task should be interrupted; otherwise, in-progress tasks are allowed
     *        to complete.
     */
    public static void cancelTask(AsyncTask<?, ?, ?> task, boolean mayInterruptIfRunning) {
        if (task != null && task.getStatus() != AsyncTask.Status.FINISHED) {
            task.cancel(mayInterruptIfRunning);
        }
    }

    /**
     * @return Device's unique ID if available.  null if the device has no unique ID.
     */
    public static String getConsistentDeviceId(Context context) {
        final String deviceId;
        try {
            TelephonyManager tm =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return null;
            }
            deviceId = tm.getDeviceId();
            if (deviceId == null) {
                return null;
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error in TelephonyManager.getDeviceId(): " + e.getMessage());
            return null;
        }
        final MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            return null;
        }
        sha.update(Utility.toUtf8(deviceId));
        final int hash = getSmallHashFromSha1(sha.digest());
        return Integer.toString(hash);
    }

    /**
     * @return a non-negative integer generated from 20 byte SHA-1 hash.
     */
    /* package for testing */ static int getSmallHashFromSha1(byte[] sha1) {
        final int offset = sha1[19] & 0xf; // SHA1 is 20 bytes.
        return ((sha1[offset]  & 0x7f) << 24)
                | ((sha1[offset + 1] & 0xff) << 16)
                | ((sha1[offset + 2] & 0xff) << 8)
                | ((sha1[offset + 3] & 0xff));
    }

    /**
     * Try to make a date MIME(RFC 2822/5322)-compliant.
     *
     * It fixes:
     * - "Thu, 10 Dec 09 15:08:08 GMT-0700" to "Thu, 10 Dec 09 15:08:08 -0700"
     *   (4 digit zone value can't be preceded by "GMT")
     *   We got a report saying eBay sends a date in this format
     */
    public static String cleanUpMimeDate(String date) {
        if (TextUtils.isEmpty(date)) {
            return date;
        }
        date = DATE_CLEANUP_PATTERN_WRONG_TIMEZONE.matcher(date).replaceFirst("$1");
        return date;
    }

    public static ByteArrayInputStream streamFromAsciiString(String ascii) {
        return new ByteArrayInputStream(toAscii(ascii));
    }

    /**
     * A thread safe way to show a Toast.  This method uses {@link Activity#runOnUiThread}, so it
     * can be called on any thread.
     *
     * @param activity Parent activity.
     * @param resId Resource ID of the message string.
     */
    public static void showToast(Activity activity, int resId) {
        showToast(activity, activity.getResources().getString(resId));
    }

    /**
     * A thread safe way to show a Toast.  This method uses {@link Activity#runOnUiThread}, so it
     * can be called on any thread.
     *
     * @param activity Parent activity.
     * @param message Message to show.
     */
    public static void showToast(final Activity activity, final String message) {
        activity.runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Run {@code r} on a worker thread.
     */
    public static void runAsync(final Runnable r) {
        new AsyncTask<Void, Void, Void>() {
            @Override protected Void doInBackground(Void... params) {
                r.run();
                return null;
            }
        }.execute();
    }

    /**
     * Formats the given size as a String in bytes, kB, MB or GB.  Ex: 12,315,000 = 11 MB
     */
    public static String formatSize(Context context, long size) {
        final Resources res = context.getResources();
        final long KB = 1024;
        final long MB = (KB * 1024);
        final long GB  = (MB * 1024);

        int resId;
        int value;

        if (size < KB) {
            resId = R.plurals.message_view_attachment_bytes;
            value = (int) size;
        } else if (size < MB) {
            resId = R.plurals.message_view_attachment_kilobytes;
            value = (int) (size / KB);
        } else if (size < GB) {
            resId = R.plurals.message_view_attachment_megabytes;
            value = (int) (size / MB);
        } else {
            resId = R.plurals.message_view_attachment_gigabytes;
            value = (int) (size / GB);
        }
        return res.getQuantityString(resId, value, value);
    }

    /**
     * Interface used in {@link #createUniqueFile} instead of {@link File#createNewFile()} to make
     * it testable.
     */
    /* package */ interface NewFileCreator {
        public static final NewFileCreator DEFAULT = new NewFileCreator() {
                    @Override public boolean createNewFile(File f) throws IOException {
                        return f.createNewFile();
                    }
        };
        public boolean createNewFile(File f) throws IOException ;
    }

    /**
     * Creates a new empty file with a unique name in the given directory by appending a hyphen and
     * a number to the given filename.
     *
     * @return a new File object, or null if one could not be created
     */
    public static File createUniqueFile(File directory, String filename) throws IOException {
        return createUniqueFileInternal(NewFileCreator.DEFAULT, directory, filename);
    }

    /* package */ static File createUniqueFileInternal(NewFileCreator nfc,
            File directory, String filename) throws IOException {
        File file = new File(directory, filename);
        if (nfc.createNewFile(file)) {
            return file;
        }
        // Get the extension of the file, if any.
        int index = filename.lastIndexOf('.');
        String format;
        if (index != -1) {
            String name = filename.substring(0, index);
            String extension = filename.substring(index);
            format = name + "-%d" + extension;
        } else {
            format = filename + "-%d";
        }

        for (int i = 2; i < Integer.MAX_VALUE; i++) {
            file = new File(directory, String.format(format, i));
            if (nfc.createNewFile(file)) {
                return file;
            }
        }
        return null;
    }

    /**
     * @return a long in column {@code column} of the first result row, if the query returns at
     * least 1 row.  Otherwise returns {@code defaultValue}.
     */
    public static Long getFirstRowLong(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, int column,
            Long defaultValue) {
        Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                sortOrder);
        try {
            if (c.moveToFirst()) {
                return c.getLong(column);
            }
        } finally {
            c.close();
        }
        return defaultValue;
    }

    /**
     * {@link #getFirstRowLong} with null as a default value.
     */
    public static Long getFirstRowLong(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, int column) {
        return getFirstRowLong(context, uri, projection, selection, selectionArgs,
                sortOrder, column, null);
    }

    /**
     * @return an integer in column {@code column} of the first result row, if the query returns at
     * least 1 row.  Otherwise returns {@code defaultValue}.
     */
    public static Integer getFirstRowInt(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, int column,
            Integer defaultValue) {
        Long longDefault = (defaultValue == null) ? null : defaultValue.longValue();
        Long result = getFirstRowLong(context, uri, projection, selection, selectionArgs, sortOrder,
                column, longDefault);
        return (result == null) ? null : result.intValue();
    }

    /**
     * {@link #getFirstRowInt} with null as a default value.
     */
    public static Integer getFirstRowInt(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder, int column) {
        return getFirstRowInt(context, uri, projection, selection, selectionArgs,
                sortOrder, column, null);
    }

    /**
     * A class used to restore ListView state (e.g. scroll position) when changing adapter.
     *
     * TODO For some reason it doesn't always work.  Investigate and fix it.
     */
    public static class ListStateSaver {
        private final Parcelable mState;

        public ListStateSaver(AbsListView lv) {
            mState = lv.onSaveInstanceState();
        }

        public void restore(AbsListView lv) {
            lv.onRestoreInstanceState(mState);
        }
    }

    public static boolean attachmentExists(Context context, long accountId, Attachment attachment) {
        if ((attachment == null) || (TextUtils.isEmpty(attachment.mContentUri))) {
            Log.w(Email.LOG_TAG, "ContentUri null.");
            return false;
        }
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG, "attachmentExists URI=" + attachment.mContentUri);
        }
        Uri fileUri = Uri.parse(attachment.mContentUri);
        try {
            InputStream inStream = context.getContentResolver().openInputStream(fileUri);
            try {
                inStream.close();
            } catch (IOException e) {
                // Nothing to be done if can't close the stream
            }
            return true;
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    /**
     * Check whether the message with a given id has unloaded attachments.  If the message is
     * a forwarded message, we look instead at the messages's source for the attachments.  If the
     * message or forward source can't be found, we return false
     * @param context the caller's context
     * @param messageId the id of the message
     * @return whether or not the message has unloaded attachments
     */
    public static boolean hasUnloadedAttachments(Context context, long messageId) {
        Message msg = Message.restoreMessageWithId(context, messageId);
        if (msg == null) return false;
        Attachment[] atts = Attachment.restoreAttachmentsWithMessageId(context, messageId);
        for (Attachment att: atts) {
            if (!attachmentExists(context, msg.mAccountKey, att)) {
                // If the attachment doesn't exist and isn't marked for download, we're in trouble
                // since the outbound message will be stuck indefinitely in the Outbox.  Instead,
                // we'll just delete the attachment and continue; this is far better than the
                // alternative.  In theory, this situation shouldn't be possible.
                if ((att.mFlags & (Attachment.FLAG_DOWNLOAD_FORWARD |
                        Attachment.FLAG_DOWNLOAD_USER_REQUEST)) == 0) {
                    Log.d(Email.LOG_TAG, "Unloaded attachment isn't marked for download: " +
                            att.mFileName + ", #" + att.mId);
                    Attachment.delete(context, Attachment.CONTENT_URI, att.mId);
                } else if (att.mContentUri != null) {
                    // In this case, the attachment file is gone from the cache; let's clear the
                    // contentUri; this should be a very unusual case
                    ContentValues cv = new ContentValues();
                    cv.putNull(AttachmentColumns.CONTENT_URI);
                    Attachment.update(context, Attachment.CONTENT_URI, att.mId, cv);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Convenience method wrapping calls to retrieve columns from a single row, via EmailProvider.
     * The arguments are exactly the same as to contentResolver.query().  Results are returned in
     * an array of Strings corresponding to the columns in the projection.  If the cursor has no
     * rows, null is returned.
     */
    public static String[] getRowColumns(Context context, Uri contentUri, String[] projection,
            String selection, String[] selectionArgs) {
        String[] values = new String[projection.length];
        ContentResolver cr = context.getContentResolver();
        Cursor c = cr.query(contentUri, projection, selection, selectionArgs, null);
        try {
            if (c.moveToFirst()) {
                for (int i = 0; i < projection.length; i++) {
                    values[i] = c.getString(i);
                }
            } else {
                return null;
            }
        } finally {
            c.close();
        }
        return values;
    }

    /**
     * Convenience method for retrieving columns from a particular row in EmailProvider.
     * Passed in here are a base uri (e.g. Message.CONTENT_URI), the unique id of a row, and
     * a projection.  This method calls the previous one with the appropriate URI.
     */
    public static String[] getRowColumns(Context context, Uri baseUri, long id,
            String ... projection) {
        return getRowColumns(context, ContentUris.withAppendedId(baseUri, id), projection, null,
                null);
    }

    public static boolean isExternalStorageMounted() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }
    /**
     * STOPSHIP Remove this method
     * Toggle between portrait and landscape.  Developement use only.
     */
    public static void changeOrientation(Activity activity) {
        activity.setRequestedOrientation(
                (activity.getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    /**
     * Class that supports running any operation for each account.
     */
    public abstract static class ForEachAccount extends AsyncTask<Void, Void, Long[]> {
        private final Context mContext;

        public ForEachAccount(Context context) {
            mContext = context;
        }

        @Override
        protected final Long[] doInBackground(Void... params) {
            ArrayList<Long> ids = new ArrayList<Long>();
            Cursor c = mContext.getContentResolver().query(EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.ID_PROJECTION, null, null, null);
            try {
                while (c.moveToNext()) {
                    ids.add(c.getLong(EmailContent.Account.ID_PROJECTION_COLUMN));
                }
            } finally {
                c.close();
            }
            return ids.toArray(EMPTY_LONGS);
        }

        @Override
        protected final void onPostExecute(Long[] ids) {
            if (ids != null && !isCancelled()) {
                for (long id : ids) {
                    performAction(id);
                }
            }
            onFinished();
        }

        /**
         * This method will be called for each account.
         */
        protected abstract void performAction(long accountId);

        /**
         * Called when the iteration is finished.
         */
        protected void onFinished() {
        }
    }
}
