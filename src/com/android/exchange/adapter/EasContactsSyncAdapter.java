/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange.adapter;

import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.OperationApplicationException;
import android.content.ContentProviderOperation.Builder;
import android.content.Entity.NamedContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Sync adapter for EAS Contacts
 *
 */
public class EasContactsSyncAdapter extends EasSyncAdapter {

    private static final String TAG = "EasContactsSyncAdapter";
    private static final String SERVER_ID_SELECTION = RawContacts.SOURCE_ID + "=?";
    private static final String[] ID_PROJECTION = new String[] {RawContacts._ID};

    // Note: These constants are likely to change; they are internal to this class now, but
    // may end up in the provider.
    private static final int TYPE_EMAIL1 = 20;
    private static final int TYPE_EMAIL2 = 21;
    private static final int TYPE_EMAIL3 = 22;

    private static final int TYPE_IM1 = 23;
    private static final int TYPE_IM2 = 24;
    private static final int TYPE_IM3 = 25;

    private static final int TYPE_WORK2 = 26;
    private static final int TYPE_HOME2 = 27;
    private static final int TYPE_CAR = 28;
    private static final int TYPE_COMPANY_MAIN = 29;
    private static final int TYPE_MMS = 30;
    private static final int TYPE_RADIO = 31;

    ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    public EasContactsSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
    }

    @Override
    public boolean parse(ByteArrayInputStream is, EasSyncService service) throws IOException {
        EasContactsSyncParser p = new EasContactsSyncParser(is, service);
        return p.parse();
    }

    public static final class Extras {
        private Extras() {}

        /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/easextras";

        /**
         * The note text.
         * <P>Type: TEXT</P>
         */
        public static final String EXTRAS = "data2";
    }

    class EasContactsSyncParser extends EasContentParser {

        String[] mBindArgument = new String[1];
        String mMailboxIdAsString;
        Uri mAccountUri;

        public EasContactsSyncParser(InputStream in, EasSyncService service) throws IOException {
            super(in, service);
            mAccountUri = uriWithAccount(RawContacts.CONTENT_URI);
            setDebug(false); // DON'T CHECK IN WITH THIS UNCOMMENTED
        }

        @Override
        public void wipe() {
            // TODO Uncomment when the new provider works with this
            //mContentResolver.delete(mAccountUri, null, null);
        }

        void saveExtraData (StringBuilder extras, int tag) throws IOException {
            // TODO Handle containers (categories/children)
            extras.append(tag);
            extras.append("~");
            extras.append(getValue());
            extras.append('~');
        }

        class Address {
            String city;
            String country;
            String code;
            String street;
            String state;

            boolean hasData() {
                return city != null || country != null || code != null || state != null
                    || street != null;
            }
        }

        public void addData(String serverId, ContactOperations ops, Entity entity)
                throws IOException {
            String firstName = null;
            String lastName = null;
            String companyName = null;
            String title = null;
            Address home = new Address();
            Address work = new Address();
            Address other = new Address();

            if (entity == null) {
                ops.newContact(serverId);
            }

            StringBuilder extraData = new StringBuilder(1024);

            while (nextTag(EasTags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case EasTags.CONTACTS_FIRST_NAME:
                        firstName = getValue();
                        break;
                    case EasTags.CONTACTS_LAST_NAME:
                        lastName = getValue();
                        break;
                    case EasTags.CONTACTS_COMPANY_NAME:
                        companyName = getValue();
                        break;
                    case EasTags.CONTACTS_JOB_TITLE:
                        title = getValue();
                        break;
                    case EasTags.CONTACTS_EMAIL1_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL1, getValue());
                        break;
                    case EasTags.CONTACTS_EMAIL2_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL2, getValue());
                        break;
                    case EasTags.CONTACTS_EMAIL3_ADDRESS:
                        ops.addEmail(entity, TYPE_EMAIL3, getValue());
                        break;
                    case EasTags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_WORK2, getValue());
                        break;
                    case EasTags.CONTACTS_BUSINESS_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_WORK, getValue());
                        break;
                    case EasTags.CONTACTS2_MMS:
                        ops.addPhone(entity, TYPE_MMS, getValue());
                        break;
                    case EasTags.CONTACTS_BUSINESS_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_WORK, getValue());
                        break;
                    case EasTags.CONTACTS2_COMPANY_MAIN_PHONE:
                        ops.addPhone(entity, TYPE_COMPANY_MAIN, getValue());
                        break;
                    case EasTags.CONTACTS_HOME_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_HOME, getValue());
                        break;
                    case EasTags.CONTACTS_HOME_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_HOME, getValue());
                        break;
                    case EasTags.CONTACTS_HOME2_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_HOME2, getValue());
                        break;
                    case EasTags.CONTACTS_MOBILE_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_MOBILE, getValue());
                        break;
                    case EasTags.CONTACTS_CAR_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_CAR, getValue());
                        break;
                    case EasTags.CONTACTS_RADIO_TELEPHONE_NUMBER:
                        ops.addPhone(entity, TYPE_RADIO, getValue());
                        break;
                    case EasTags.CONTACTS_PAGER_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_PAGER, getValue());
                        break;
                    case EasTags.CONTACTS2_IM_ADDRESS:
                        ops.addIm(entity, TYPE_IM1, getValue());
                        break;
                    case EasTags.CONTACTS2_IM_ADDRESS_2:
                        ops.addIm(entity, TYPE_IM2, getValue());
                        break;
                    case EasTags.CONTACTS2_IM_ADDRESS_3:
                        ops.addIm(entity, TYPE_IM3, getValue());
                        break;
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_CITY:
                        work.city = getValue();
                        break;
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_COUNTRY:
                        work.country = getValue();
                        break;
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE:
                        work.code = getValue();
                        break;
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_STATE:
                        work.state = getValue();
                        break;
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_STREET:
                        work.street = getValue();
                        break;
                    case EasTags.CONTACTS_HOME_ADDRESS_CITY:
                        home.city = getValue();
                        break;
                    case EasTags.CONTACTS_HOME_ADDRESS_COUNTRY:
                        home.country = getValue();
                        break;
                    case EasTags.CONTACTS_HOME_ADDRESS_POSTAL_CODE:
                        home.code = getValue();
                        break;
                    case EasTags.CONTACTS_HOME_ADDRESS_STATE:
                        home.state = getValue();
                        break;
                    case EasTags.CONTACTS_HOME_ADDRESS_STREET:
                        home.street = getValue();
                        break;
                    case EasTags.CONTACTS_OTHER_ADDRESS_CITY:
                        other.city = getValue();
                        break;
                    case EasTags.CONTACTS_OTHER_ADDRESS_COUNTRY:
                        other.country = getValue();
                        break;
                    case EasTags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE:
                        other.code = getValue();
                        break;
                    case EasTags.CONTACTS_OTHER_ADDRESS_STATE:
                        other.state = getValue();
                        break;
                    case EasTags.CONTACTS_OTHER_ADDRESS_STREET:
                        other.street = getValue();
                        break;

                    case EasTags.CONTACTS_CHILDREN:
                        childrenParser(extraData);
                        break;

                    case EasTags.CONTACTS_CATEGORIES:
                        categoriesParser(extraData);
                        break;

                        // TODO We'll add this later
                    case EasTags.CONTACTS_PICTURE:
                        getValue();
                        break;

                    // All tags that we don't use (except for a few like picture and body) need to
                    // be saved, even if we're not using them.  Otherwise, when we upload changes,
                    // those items will be deleted back on the server.
                    case EasTags.CONTACTS_ANNIVERSARY:
                    case EasTags.CONTACTS_ASSISTANT_NAME:
                    case EasTags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_BIRTHDAY:
                    case EasTags.CONTACTS_DEPARTMENT:
                    case EasTags.CONTACTS_FILE_AS:
                    case EasTags.CONTACTS_TITLE:
                    case EasTags.CONTACTS_MIDDLE_NAME:
                    case EasTags.CONTACTS_OFFICE_LOCATION:
                    case EasTags.CONTACTS_SPOUSE:
                    case EasTags.CONTACTS_SUFFIX:
                    case EasTags.CONTACTS_WEBPAGE:
                    case EasTags.CONTACTS_YOMI_COMPANY_NAME:
                    case EasTags.CONTACTS_YOMI_FIRST_NAME:
                    case EasTags.CONTACTS_YOMI_LAST_NAME:
                    case EasTags.CONTACTS_COMPRESSED_RTF:
                    case EasTags.CONTACTS2_CUSTOMER_ID:
                    case EasTags.CONTACTS2_GOVERNMENT_ID:
                    case EasTags.CONTACTS2_MANAGER_NAME:
                    case EasTags.CONTACTS2_ACCOUNT_NAME:
                    case EasTags.CONTACTS2_NICKNAME:
                        saveExtraData(extraData, tag);
                        break;
                    default:
                        skipTag();
                }
            }

            ops.addExtras(entity, extraData.toString());

            // We must have first name, last name, or company name
            String name;
            if (firstName != null || lastName != null) {
                if (firstName == null) {
                    name = lastName;
                } else if (lastName == null) {
                    name = firstName;
                } else {
                    name = firstName + ' ' + lastName;
                }
            } else if (companyName != null) {
                name = companyName;
            } else {
                return;
            }
            ops.addName(entity, firstName, lastName, name);

            if (work.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_WORK, work.street, work.city,
                        work.state, work.country, work.code);
            }
            if (home.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_HOME, home.street, home.city,
                        home.state, home.country, home.code);
            }
            if (other.hasData()) {
                ops.addPostal(entity, StructuredPostal.TYPE_OTHER, other.street, other.city,
                        other.state, other.country, other.code);
            }

            if (companyName != null) {
                ops.addOrganization(entity, Organization.TYPE_WORK, companyName, title);
            }
        }

        private void categoriesParser(StringBuilder extras) throws IOException {
            while (nextTag(EasTags.CONTACTS_CATEGORIES) != END) {
                switch (tag) {
                    case EasTags.CONTACTS_CATEGORY:
                        saveExtraData(extras, tag);
                    default:
                        skipTag();
                }
            }
        }

        private void childrenParser(StringBuilder extras) throws IOException {
            while (nextTag(EasTags.CONTACTS_CHILDREN) != END) {
                switch (tag) {
                    case EasTags.CONTACTS_CHILD:
                        saveExtraData(extras, tag);
                    default:
                        skipTag();
                }
            }
        }

        public void addParser(ContactOperations ops) throws IOException {
            String serverId = null;
            while (nextTag(EasTags.SYNC_ADD) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID: // same as
                        serverId = getValue();
                        break;
                    case EasTags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops, null);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private Cursor getServerIdCursor(String serverId) {
            mBindArgument[0] = serverId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, SERVER_ID_SELECTION,
                    mBindArgument, null);
        }

        public void deleteParser(ContactOperations ops) throws IOException {
            while (nextTag(EasTags.SYNC_DELETE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                mService.userLog("Deleting " + serverId);
                                ops.delete(c.getLong(0));
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        class ServerChange {
            long id;
            boolean read;

            ServerChange(long _id, boolean _read) {
                id = _id;
                read = _read;
            }
        }

        /**
         * Changes are handled row by row, and only changed/new rows are acted upon
         * @param ops the array of pending ContactProviderOperations.
         * @throws IOException
         */
        public void changeParser(ContactOperations ops) throws IOException {
            String serverId = null;
            Entity entity = null;
            while (nextTag(EasTags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                // TODO Handle deleted individual rows...
                                try {
                                    EntityIterator entityIterator =
                                        mContentResolver.queryEntities(ContentUris
                                            .withAppendedId(RawContacts.CONTENT_URI, c.getLong(0)),
                                            null, null, null);
                                    if (entityIterator.hasNext()) {
                                        entity = entityIterator.next();
                                    }
                                    mService.userLog("Changing contact " + serverId);
                                } catch (RemoteException e) {
                                }
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    case EasTags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops, entity);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        @Override
        public void commandsParser() throws IOException {
            ContactOperations ops = new ContactOperations();
            while (nextTag(EasTags.SYNC_COMMANDS) != END) {
                if (tag == EasTags.SYNC_ADD) {
                    addParser(ops);
                } else if (tag == EasTags.SYNC_DELETE) {
                    deleteParser(ops);
                } else if (tag == EasTags.SYNC_CHANGE) {
                    changeParser(ops);
                } else
                    skipTag();
            }

            // Execute these all at once...
            ops.execute();

            if (ops.mResults != null) {
                ContentValues cv = new ContentValues();
                cv.put(RawContacts.DIRTY, 0);
                for (int i = 0; i < ops.mContactIndexCount; i++) {
                    int index = ops.mContactIndexArray[i];
                    Uri u = ops.mResults[index].uri;
                    if (u != null) {
                        String idString = u.getLastPathSegment();
                        mService.mContentResolver.update(RawContacts.CONTENT_URI, cv,
                                RawContacts._ID + "=" + idString, null);
                    }
                }
            }

            // Update the sync key in the database
            mService.userLog("Contacts SyncKey saved as: " + mMailbox.mSyncKey);
            ContentValues cv = new ContentValues();
            cv.put(MailboxColumns.SYNC_KEY, mMailbox.mSyncKey);
            Mailbox.update(mContext, Mailbox.CONTENT_URI, mMailbox.mId, cv);

            mService.userLog("Contacts SyncKey confirmed as: " + mMailbox.mSyncKey);
        }
    }


    private Uri uriWithAccount(Uri uri) {
        return uri.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, mService.mAccount.mEmailAddress)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE)
            .build();
    }

    /**
     * SmartBuilder is a wrapper for the Builder class that is used to create/update rows for a
     * ContentProvider.  It has, in addition to the Builder, ContentValues which, if present,
     * represent the current values of that row, that can be compared against current values to
     * see whether an update is even necessary.  The methods on SmartBuilder are delegated to
     * the Builder.
     */
    private class SmartBuilder {
        Builder builder;
        ContentValues cv;

        public SmartBuilder(Builder _builder) {
            builder = _builder;
        }

        public SmartBuilder(Builder _builder, NamedContentValues _ncv) {
            builder = _builder;
            cv = _ncv.values;
        }

        SmartBuilder withValues(ContentValues values) {
            builder.withValues(values);
            return this;
        }

        SmartBuilder withValueBackReference(String key, int previousResult) {
            builder.withValueBackReference(key, previousResult);
            return this;
        }

        ContentProviderOperation build() {
            return builder.build();
        }

        SmartBuilder withValue(String key, Object value) {
            builder.withValue(key, value);
            return this;
        }
    }

    private class ContactOperations extends ArrayList<ContentProviderOperation> {
        private static final long serialVersionUID = 1L;
        private int mCount = 0;
        private int mContactBackValue = mCount;
        private int[] mContactIndexArray = new int[10];
        private int mContactIndexCount = 0;
        private ContentProviderResult[] mResults = null;

        @Override
        public boolean add(ContentProviderOperation op) {
            super.add(op);
            mCount++;
            return true;
        }

        public void newContact(String serverId) {
            Builder builder = ContentProviderOperation
                .newInsert(uriWithAccount(RawContacts.CONTENT_URI));
            ContentValues values = new ContentValues();
            values.put(RawContacts.SOURCE_ID, serverId);
            builder.withValues(values);
            mContactBackValue = mCount;
            mContactIndexArray[mContactIndexCount++] = mCount;
            add(builder.build());
        }

        public void delete(long id) {
            add(ContentProviderOperation.newDelete(ContentUris
                    .withAppendedId(RawContacts.CONTENT_URI, id)).build());
        }

        public void execute() {
            try {
                mService.userLog("Executing " + size() + " CPO's");
                mResults = mService.mContext.getContentResolver()
                    .applyBatch(ContactsContract.AUTHORITY, this);
            } catch (RemoteException e) {
                // There is nothing sensible to be done here
                Log.e(TAG, "problem inserting contact during server update", e);
            } catch (OperationApplicationException e) {
                // There is nothing sensible to be done here
                Log.e(TAG, "problem inserting contact during server update", e);
            }
        }

        /**
         * Generate the uri for the data row associated with this NamedContentValues object
         * @param ncv the NamedContentValues object
         * @return a uri that can be used to refer to this row
         */
        private Uri dataUriFromNamedContentValues(NamedContentValues ncv) {
            long id = ncv.values.getAsLong(RawContacts._ID);
            Uri dataUri = ContentUris.withAppendedId(ncv.uri, id);
            return dataUri;
        }

        /**
         * Given the list of NamedContentValues for an entity, a mime type, and a subtype,
         * tries to find a match, returning it
         * @param list the list of NCV's from the contact entity
         * @param contentItemType the mime type we're looking for
         * @param type the subtype (e.g. HOME, WORK, etc.)
         * @return the matching NCV or null if not found
         */
        private NamedContentValues findExistingData(ArrayList<NamedContentValues> list,
                String contentItemType, int type) {
            NamedContentValues result = null;

            // Loop through the ncv's, looking for an existing row
            for (NamedContentValues namedContentValues: list) {
                Uri uri = namedContentValues.uri;
                ContentValues cv = namedContentValues.values;
                if (Data.CONTENT_URI.equals(uri)) {
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(contentItemType)) {
                        if (type < 0 || cv.getAsInteger(Email.TYPE) == type) {
                            result = namedContentValues;
                        }
                    }
                }
            }

            // If we've found an existing data row, we'll delete it.  Any rows left at the
            // end should be deleted...
            if (result != null) {
                list.remove(result);
            }

            // Return the row found (or null)
            return result;
        }

        /**
         * Create a wrapper for a builder (insert or update) that also includes the NCV for
         * an existing row of this type.   If the SmartBuilder's cv field is not null, then
         * it represents the current (old) values of this field.  The caller can then check
         * whether the field is now different and needs to be updated; if it's not different,
         * the caller will simply return and not generate a new CPO.  Otherwise, the builder
         * should have its content values set, and the built CPO should be added to the
         * ContactOperations list.
         *
         * @param entity the contact entity (or null if this is a new contact)
         * @param mimeType the mime type of this row
         * @param type the subtype of this row
         * @return the created SmartBuilder
         */
        public SmartBuilder createBuilder(Entity entity, String mimeType, int type) {
            int contactId = mContactBackValue;
            SmartBuilder builder = null;

            if (entity != null) {
                NamedContentValues ncv =
                    findExistingData(entity.getSubValues(), mimeType, type);
                if (ncv != null) {
                    builder = new SmartBuilder(
                            ContentProviderOperation
                                .newUpdate(dataUriFromNamedContentValues(ncv)),
                            ncv);
                } else {
                    contactId = entity.getEntityValues().getAsInteger(RawContacts._ID);
                }
            }

            if (builder == null) {
                builder =
                    new SmartBuilder(ContentProviderOperation.newInsert(Data.CONTENT_URI));
                if (entity == null) {
                    builder.withValueBackReference(Data.RAW_CONTACT_ID, contactId);
                } else {
                    builder.withValue(Data.RAW_CONTACT_ID, contactId);
                }

                builder.withValue(Data.MIMETYPE, mimeType);
            }

            // Return the appropriate builder (insert or update)
            // Caller will fill in the appropriate values; note MIMETYPE is already set
            return builder;
        }

        /**
         * Compare a column in a ContentValues with an (old) value, and see if they are the
         * same.  For this purpose, null and an empty string are considered the same.
         * @param cv a ContentValues object, from a NamedContentValues
         * @param column a column that might be in the ContentValues
         * @param oldValue an old value (or null) to check against
         * @return whether the column's value in the ContentValues matches oldValue
         */
        private boolean cvCompareString(ContentValues cv, String column, String oldValue) {
            if (cv.containsKey(column)) {
                if (oldValue != null && cv.getAsString(column).equals(oldValue)) {
                    return true;
                }
            } else if (oldValue == null || oldValue.length() == 0) {
                return true;
            }
            return false;
        }

        public void addEmail(Entity entity, int type, String email) {
            SmartBuilder builder = createBuilder(entity, Email.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Email.DATA, email)) {
                return;
            }
            builder.withValue(Email.TYPE, type);
            builder.withValue(Email.DATA, email);
            add(builder.build());
        }

        public void addName(Entity entity, String givenName, String familyName,
                String displayName) {
            SmartBuilder builder = createBuilder(entity, StructuredName.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, StructuredName.GIVEN_NAME, givenName) &&
                    cvCompareString(cv, StructuredName.FAMILY_NAME, familyName)) {
                return;
            }
            builder.withValue(StructuredName.GIVEN_NAME, givenName);
            builder.withValue(StructuredName.FAMILY_NAME, familyName);
            add(builder.build());
        }

        public void addPhoto() {
//            final int photoRes = Generator.pickRandom(PHOTO_POOL);
//
//            Builder builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
//            builder.withValueBackReference(Data.CONTACT_ID, 0);
//            builder.withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE);
//            builder.withValue(Photo.PHOTO, getPhotoBytes(photoRes));
//
//            this.add(builder.build());
        }

        public void addPhone(Entity entity, int type, String phone) {
            SmartBuilder builder = createBuilder(entity, Phone.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Phone.NUMBER, phone)) {
                return;
            }
            builder.withValue(Phone.TYPE, type);
            builder.withValue(Phone.NUMBER, phone);
            add(builder.build());
        }

        public void addPostal(Entity entity, int type, String street, String city, String state,
                String country, String code) {
            SmartBuilder builder = createBuilder(entity, StructuredPostal.CONTENT_ITEM_TYPE,
                    type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, StructuredPostal.CITY, city) &&
                    cvCompareString(cv, StructuredPostal.STREET, street) &&
                    cvCompareString(cv, StructuredPostal.COUNTRY, country) &&
                    cvCompareString(cv, StructuredPostal.POSTCODE, code) &&
                    cvCompareString(cv, StructuredPostal.REGION, state)) {
                return;
            }
            builder.withValue(StructuredPostal.TYPE, type);
            builder.withValue(StructuredPostal.CITY, city);
            builder.withValue(StructuredPostal.STREET, street);
            builder.withValue(StructuredPostal.COUNTRY, country);
            builder.withValue(StructuredPostal.POSTCODE, code);
            builder.withValue(StructuredPostal.REGION, state);
            add(builder.build());
        }

        public void addIm(Entity entity, int type, String account) {
            SmartBuilder builder = createBuilder(entity, Im.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Im.DATA, account)) {
                return;
            }
            builder.withValue(Im.TYPE, type);
            builder.withValue(Im.DATA, account);
            add(builder.build());
        }

        public void addOrganization(Entity entity, int type, String company, String title) {
            SmartBuilder builder = createBuilder(entity, Organization.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Organization.COMPANY, company) &&
                    cvCompareString(cv, Organization.TITLE, title)) {
                return;
            }
            builder.withValue(Organization.TYPE, type);
            builder.withValue(Organization.COMPANY, company);
            builder.withValue(Organization.TITLE, title);
            add(builder.build());
        }

        // TODO
        public void addNote(String note) {
            Builder builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(Data.RAW_CONTACT_ID, mContactBackValue);
            builder.withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE);
            builder.withValue(Note.NOTE, note);
            add(builder.build());
        }

        public void addExtras(Entity entity, String extras) {
            SmartBuilder builder = createBuilder(entity, Extras.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Extras.EXTRAS, extras)) {
                return;
            }
            builder.withValue(Extras.EXTRAS, extras);
            add(builder.build());
        }
    }

    @Override
    public void cleanup(EasSyncService service) {
        // Mark the changed contacts dirty = 0
        // TODO Put this in a single batch
        ContactOperations ops = new ContactOperations();
        for (Long id: mUpdatedIdList) {
            ops.add(ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
                    .withValue(RawContacts.DIRTY, 0).build());
        }

        ops.execute();
    }

    @Override
    public String getCollectionName() {
        return "Contacts";
    }

    private void sendEmail(EasSerializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Email.DATA);
        switch (cv.getAsInteger(Email.TYPE)) {
            case TYPE_EMAIL1:
                s.data("Email1Address", value);
                break;
            case TYPE_EMAIL2:
                s.data("Email2Address", value);
                break;
            case TYPE_EMAIL3:
                s.data("Email3Address", value);
                break;
            default:
                break;
        }
    }

    private void sendIm(EasSerializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Email.DATA);
        switch (cv.getAsInteger(Email.TYPE)) {
            case TYPE_IM1:
                s.data("IMAddress", value);
                break;
            case TYPE_IM2:
                s.data("IMAddress2", value);
                break;
            case TYPE_IM3:
                s.data("IMAddress3", value);
                break;
            default:
                break;
        }
    }

    private void sendOnePostal(EasSerializer s, ContentValues cv, String[] fieldNames)
            throws IOException{
        if (cv.containsKey(StructuredPostal.CITY)) {
            s.data(fieldNames[0], cv.getAsString(StructuredPostal.CITY));
        }
        if (cv.containsKey(StructuredPostal.COUNTRY)) {
            s.data(fieldNames[1], cv.getAsString(StructuredPostal.COUNTRY));
        }
        if (cv.containsKey(StructuredPostal.POSTCODE)) {
            s.data(fieldNames[2], cv.getAsString(StructuredPostal.POSTCODE));
        }
        if (cv.containsKey(StructuredPostal.REGION)) {
            s.data(fieldNames[3], cv.getAsString(StructuredPostal.REGION));
        }
        if (cv.containsKey(StructuredPostal.STREET)) {
            s.data(fieldNames[4], cv.getAsString(StructuredPostal.STREET));
        }
    }

    private void sendStructuredPostal(EasSerializer s, ContentValues cv) throws IOException {
        switch (cv.getAsInteger(StructuredPostal.TYPE)) {
            case StructuredPostal.TYPE_HOME:
                sendOnePostal(s, cv, new String[] {"HomeAddressCity", "HomeAddressCountry",
                        "HomeAddressPostalCode", "HomeAddressState", "HomeAddressStreet"});
                break;
            case StructuredPostal.TYPE_WORK:
                sendOnePostal(s, cv, new String[] {"BusinessAddressCity", "BusinessAddressCountry",
                        "BusinessAddressPostalCode", "BusinessAddressState",
                        "BusinessAddressStreet"});
                break;
            case StructuredPostal.TYPE_OTHER:
                sendOnePostal(s, cv, new String[] {"OtherAddressCity", "OtherAddressCountry",
                        "OtherAddressPostalCode", "OtherAddressState", "OtherAddressStreet"});
                break;
            default:
                break;
        }
    }

    private void sendStructuredName(EasSerializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(StructuredName.FAMILY_NAME)) {
            s.data("LastName", cv.getAsString(StructuredName.FAMILY_NAME));
        }
        if (cv.containsKey(StructuredName.GIVEN_NAME)) {
            s.data("FirstName", cv.getAsString(StructuredName.GIVEN_NAME));
        }
    }

    private void sendOrganization(EasSerializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Organization.TITLE)) {
            s.data("JobTitle", cv.getAsString(Organization.TITLE));
        }
        if (cv.containsKey(Organization.COMPANY)) {
            s.data("CompanyName", cv.getAsString(Organization.COMPANY));
        }
    }

    private void sendPhone(EasSerializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Phone.NUMBER);
        switch (cv.getAsInteger(Phone.TYPE)) {
            case TYPE_WORK2:
                s.data("Business2TelephoneNumber", value);
                break;
            case Phone.TYPE_WORK:
                s.data("BusinessTelephoneNumber", value);
                break;
            case TYPE_MMS:
                s.data("MMS", value);
                break;
            case Phone.TYPE_FAX_WORK:
                s.data("BusinessFaxNumber", value);
                break;
            case TYPE_COMPANY_MAIN:
                s.data("CompanyMainPhone", value);
                break;
            case Phone.TYPE_HOME:
                s.data("HomeTelephoneNumber", value);
                break;
            case TYPE_HOME2:
                s.data("Home2TelephoneNumber", value);
                break;
            case Phone.TYPE_MOBILE:
                s.data("MobileTelephoneNumber", value);
                break;
            case TYPE_CAR:
                s.data("CarTelephoneNumber", value);
                break;
            case Phone.TYPE_PAGER:
                s.data("PagerNumber", value);
                break;
            case TYPE_RADIO:
                s.data("RadioTelephoneNumber", value);
                break;
            case Phone.TYPE_FAX_HOME:
                s.data("HomeFaxNumber", value);
                break;
            case TYPE_EMAIL2:
                s.data("Email2Address", value);
                break;
            case TYPE_EMAIL3:
                s.data("Email3Address", value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean sendLocalChanges(EasSerializer s, EasSyncService service) throws IOException {
        // First, let's find Contacts that have changed.
        ContentResolver cr = service.mContentResolver;
        Uri uri = RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, service.mAccount.mEmailAddress)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE)
                .build();

        try {
            // Get them all atomically
            //EntityIterator ei = cr.queryEntities(uri, RawContacts.DIRTY + "=1", null, null);
            EntityIterator ei = cr.queryEntities(uri, null, null, null);
            boolean first = true;
            while (ei.hasNext()) {
                Entity entity = ei.next();
                // For each of these entities, create the change commands
                ContentValues entityValues = entity.getEntityValues();
                String serverId = entityValues.getAsString(RawContacts.SOURCE_ID);
                if (first) {
                    s.start("Commands");
                    first = false;
                }
                s.start("Change").data("ServerId", serverId).start("ApplicationData");
                // Write out the data here
                for (NamedContentValues ncv: entity.getSubValues()) {
                    ContentValues cv = ncv.values;
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(Email.CONTENT_ITEM_TYPE)) {
                        sendEmail(s, cv);
                    } else if (mimeType.equals(Phone.CONTENT_ITEM_TYPE)) {
                        sendPhone(s, cv);
                    } else if (mimeType.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                        sendStructuredName(s, cv);
                    } else if (mimeType.equals(StructuredPostal.CONTENT_ITEM_TYPE)) {
                        sendStructuredPostal(s, cv);
                    } else if (mimeType.equals(Organization.CONTENT_ITEM_TYPE)) {
                        sendOrganization(s, cv);
                    } else if (mimeType.equals(Im.CONTENT_ITEM_TYPE)) {
                        sendIm(s, cv);
                    } else if (mimeType.equals(Note.CONTENT_ITEM_TYPE)) {

                    } else if (mimeType.equals(Extras.CONTENT_ITEM_TYPE)) {

                    } else {
                        mService.userLog("Contacts upsync, unknown data: " + mimeType);
                    }
                }
                s.end("ApplicationData").end("Change");
                mUpdatedIdList.add(entityValues.getAsLong(RawContacts._ID));
            }
           if (!first) {
                s.end("Commands");
            }

        } catch (RemoteException e) {
            Log.e(TAG, "Could not read dirty contacts.");
        }

        return false;
    }
}
