package com.android.emailcommon.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.v4.util.LongSparseArray;

import com.android.mail.utils.LogUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link EmailContent}-like class for the MessageStateChange table.
 */
public class MessageStateChange extends MessageChangeLogTable {
    /** Logging tag. */
    public static final String LOG_TAG = "MessageStateChange";

    /** The name for this table in the database. */
    public static final String TABLE_NAME = "MessageStateChange";

    /** The path for the URI for interacting with message moves. */
    public static final String PATH = "messageChange";

    /** The URI for dealing with message move data. */
    public static Uri CONTENT_URI;

    // DB columns.
    /** Column name for the old value of flagRead. */
    public static final String OLD_FLAG_READ = "oldFlagRead";
    /** Column name for the new value of flagRead. */
    public static final String NEW_FLAG_READ = "newFlagRead";
    /** Column name for the old value of flagFavorite. */
    public static final String OLD_FLAG_FAVORITE = "oldFlagFavorite";
    /** Column name for the new value of flagFavorite. */
    public static final String NEW_FLAG_FAVORITE = "newFlagFavorite";

    /** Value stored in DB for "new" columns when an update did not touch this particular value. */
    public static final int VALUE_UNCHANGED = -1;

    /**
     * Projection for a query to get all columns necessary for an actual change.
     */
    private static final class ProjectionChangeQuery {
        public static final int COLUMN_ID = 0;
        public static final int COLUMN_MESSAGE_KEY = 1;
        public static final int COLUMN_SERVER_ID = 2;
        public static final int COLUMN_OLD_FLAG_READ = 3;
        public static final int COLUMN_NEW_FLAG_READ = 4;
        public static final int COLUMN_OLD_FLAG_FAVORITE = 5;
        public static final int COLUMN_NEW_FLAG_FAVORITE = 6;

        public static final String[] PROJECTION = new String[] {
                ID, MESSAGE_KEY, SERVER_ID,
                OLD_FLAG_READ, NEW_FLAG_READ,
                OLD_FLAG_FAVORITE, NEW_FLAG_FAVORITE
        };
    }

    // The actual fields.
    private final int mOldFlagRead;
    private int mNewFlagRead;
    private final int mOldFlagFavorite;
    private int mNewFlagFavorite;

    private MessageStateChange(final long messageKey,final String serverId, final long id,
            final int oldFlagRead, final int newFlagRead,
            final int oldFlagFavorite, final int newFlagFavorite) {
        super(messageKey, serverId, id);
        mOldFlagRead = oldFlagRead;
        mNewFlagRead = newFlagRead;
        mOldFlagFavorite = oldFlagFavorite;
        mNewFlagFavorite = newFlagFavorite;
    }

    /**
     * Initialize static state for this class.
     */
    public static void init() {
        CONTENT_URI = EmailContent.CONTENT_URI.buildUpon().appendEncodedPath(PATH).build();
    }

    public static List<MessageStateChange> getChanges(final Context context, final long accountId) {
        final ContentResolver cr = context.getContentResolver();
        final Cursor c = getCursor(cr, CONTENT_URI, ProjectionChangeQuery.PROJECTION, accountId);
        if (c == null) {
            return null;
        }

        // Collapse rows acting on the same message.
        // TODO: Unify with MessageMove, move to base class as much as possible.
        LongSparseArray<MessageStateChange> changesMap = new LongSparseArray();
        try {
            while (c.moveToNext()) {
                final long id = c.getLong(ProjectionChangeQuery.COLUMN_ID);
                final long messageKey = c.getLong(ProjectionChangeQuery.COLUMN_MESSAGE_KEY);
                final String serverId = c.getString(ProjectionChangeQuery.COLUMN_SERVER_ID);
                final int oldFlagRead = c.getInt(ProjectionChangeQuery.COLUMN_OLD_FLAG_READ);
                final int newFlagReadTable =  c.getInt(ProjectionChangeQuery.COLUMN_NEW_FLAG_READ);
                final int newFlagRead = (newFlagReadTable == VALUE_UNCHANGED) ?
                        oldFlagRead : newFlagReadTable;
                final int oldFlagFavorite =
                        c.getInt(ProjectionChangeQuery.COLUMN_OLD_FLAG_FAVORITE);
                final int newFlagFavoriteTable =
                        c.getInt(ProjectionChangeQuery.COLUMN_NEW_FLAG_FAVORITE);
                final int newFlagFavorite = (newFlagFavoriteTable == VALUE_UNCHANGED) ?
                        oldFlagFavorite : newFlagFavoriteTable;
                final MessageStateChange existingChange = changesMap.get(messageKey);
                if (existingChange != null) {
                    if (existingChange.mLastId >= id) {
                        LogUtils.w(LOG_TAG, "DChanges were not in ascending id order");
                    }
                    if (existingChange.mNewFlagRead != oldFlagRead ||
                            existingChange.mNewFlagFavorite != oldFlagFavorite) {
                        LogUtils.w(LOG_TAG, "existing change inconsistent with new change");
                    }
                    existingChange.mNewFlagRead = newFlagRead;
                    existingChange.mNewFlagFavorite = newFlagFavorite;
                    existingChange.mLastId = id;
                } else {
                    changesMap.put(messageKey, new MessageStateChange(messageKey, serverId, id,
                            oldFlagRead, newFlagRead, oldFlagFavorite, newFlagFavorite));
                }
            }
        } finally {
            c.close();
        }

        // Prune no-ops.
        // TODO: Unify with MessageMove, move to base class as much as possible.
        final int count = changesMap.size();
        final long[] unchangedMessages = new long[count];
        int unchangedMessagesCount = 0;
        final ArrayList<MessageStateChange> changes = new ArrayList(count);
        for (int i = 0; i < changesMap.size(); ++i) {
            final MessageStateChange change = changesMap.valueAt(i);
            if (change.mOldFlagRead == change.mNewFlagRead &&
                    change.mOldFlagFavorite == change.mNewFlagFavorite) {
                unchangedMessages[unchangedMessagesCount] = change.mMessageKey;
                ++unchangedMessagesCount;
            } else {
                changes.add(change);
            }
        }
        if (unchangedMessagesCount != 0) {
            deleteRowsForMessages(cr, CONTENT_URI, unchangedMessages, unchangedMessagesCount);
        }
        if (changes.isEmpty()) {
            return null;
        }
        return changes;
    }
}
