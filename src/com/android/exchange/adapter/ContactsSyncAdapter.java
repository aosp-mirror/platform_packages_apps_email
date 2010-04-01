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
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;

import android.content.ContentProviderClient;
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
import android.provider.SyncStateContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.RawContactsEntity;
import android.provider.ContactsContract.Settings;
import android.provider.ContactsContract.SyncState;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.Relation;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Sync adapter for EAS Contacts
 *
 */
public class ContactsSyncAdapter extends AbstractSyncAdapter {

    private static final String TAG = "EasContactsSyncAdapter";
    private static final String SERVER_ID_SELECTION = RawContacts.SOURCE_ID + "=?";
    private static final String CLIENT_ID_SELECTION = RawContacts.SYNC1 + "=?";
    private static final String[] ID_PROJECTION = new String[] {RawContacts._ID};
    private static final String[] GROUP_PROJECTION = new String[] {Groups.SOURCE_ID};

    private static final ArrayList<NamedContentValues> EMPTY_ARRAY_NAMEDCONTENTVALUES
        = new ArrayList<NamedContentValues>();

    private static final String FOUND_DATA_ROW = "com.android.exchange.FOUND_ROW";

    private static final int[] HOME_ADDRESS_TAGS = new int[] {Tags.CONTACTS_HOME_ADDRESS_CITY,
        Tags.CONTACTS_HOME_ADDRESS_COUNTRY,
        Tags.CONTACTS_HOME_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_HOME_ADDRESS_STATE,
        Tags.CONTACTS_HOME_ADDRESS_STREET};

    private static final int[] WORK_ADDRESS_TAGS = new int[] {Tags.CONTACTS_BUSINESS_ADDRESS_CITY,
        Tags.CONTACTS_BUSINESS_ADDRESS_COUNTRY,
        Tags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_BUSINESS_ADDRESS_STATE,
        Tags.CONTACTS_BUSINESS_ADDRESS_STREET};

    private static final int[] OTHER_ADDRESS_TAGS = new int[] {Tags.CONTACTS_HOME_ADDRESS_CITY,
        Tags.CONTACTS_OTHER_ADDRESS_COUNTRY,
        Tags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE,
        Tags.CONTACTS_OTHER_ADDRESS_STATE,
        Tags.CONTACTS_OTHER_ADDRESS_STREET};

    private static final int MAX_IM_ROWS = 3;
    private static final int MAX_EMAIL_ROWS = 3;
    private static final int MAX_PHONE_ROWS = 2;
    private static final String COMMON_DATA_ROW = Im.DATA;  // Could have been Email.DATA, etc.
    private static final String COMMON_TYPE_ROW = Phone.TYPE; // Could have been any typed row

    private static final int[] IM_TAGS = new int[] {Tags.CONTACTS2_IM_ADDRESS,
        Tags.CONTACTS2_IM_ADDRESS_2, Tags.CONTACTS2_IM_ADDRESS_3};

    private static final int[] EMAIL_TAGS = new int[] {Tags.CONTACTS_EMAIL1_ADDRESS,
        Tags.CONTACTS_EMAIL2_ADDRESS, Tags.CONTACTS_EMAIL3_ADDRESS};

    private static final int[] WORK_PHONE_TAGS = new int[] {Tags.CONTACTS_BUSINESS_TELEPHONE_NUMBER,
        Tags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER};

    private static final int[] HOME_PHONE_TAGS = new int[] {Tags.CONTACTS_HOME_TELEPHONE_NUMBER,
        Tags.CONTACTS_HOME2_TELEPHONE_NUMBER};

    private static final Object sSyncKeyLock = new Object();

    ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    private boolean mGroupsUsed = false;

    public ContactsSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
    }

    static Uri addCallerIsSyncAdapterParameter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();
    }

    @Override
    public boolean isSyncable() {
        return ContentResolver.getSyncAutomatically(
                mAccountManagerAccount, ContactsContract.AUTHORITY);
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        EasContactsSyncParser p = new EasContactsSyncParser(is, this);
        return p.parse();
    }

    interface UntypedRow {
        public void addValues(RowBuilder builder);
        public boolean isSameAs(int type, String value);
    }

    /**
     * We get our SyncKey from ContactsProvider.  If there's not one, we set it to "0" (the reset
     * state) and save that away.
     */
    @Override
    public String getSyncKey() throws IOException {
        synchronized (sSyncKeyLock) {
            ContentProviderClient client = mService.mContentResolver
                    .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
            try {
                byte[] data = SyncStateContract.Helpers.get(client,
                        ContactsContract.SyncState.CONTENT_URI, mAccountManagerAccount);
                if (data == null || data.length == 0) {
                    // Initialize the SyncKey
                    setSyncKey("0", false);
                    // Make sure ungrouped contacts for Exchange are defaultly visible
                    ContentValues cv = new ContentValues();
                    cv.put(Groups.ACCOUNT_NAME, mAccount.mEmailAddress);
                    cv.put(Groups.ACCOUNT_TYPE,
                            com.android.email.Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                    cv.put(Settings.UNGROUPED_VISIBLE, true);
                    client.insert(addCallerIsSyncAdapterParameter(Settings.CONTENT_URI), cv);
                    return "0";
                } else {
                    return new String(data);
                }
            } catch (RemoteException e) {
                throw new IOException("Can't get SyncKey from ContactsProvider");
            }
        }
    }

    /**
     * We only need to set this when we're forced to make the SyncKey "0" (a reset).  In all other
     * cases, the SyncKey is set within ContactOperations
     */
    @Override
    public void setSyncKey(String syncKey, boolean inCommands) throws IOException {
        synchronized (sSyncKeyLock) {
            if ("0".equals(syncKey) || !inCommands) {
                ContentProviderClient client = mService.mContentResolver
                        .acquireContentProviderClient(ContactsContract.AUTHORITY_URI);
                try {
                    SyncStateContract.Helpers.set(client, ContactsContract.SyncState.CONTENT_URI,
                            mAccountManagerAccount, syncKey.getBytes());
                    userLog("SyncKey set to ", syncKey, " in ContactsProvider");
                } catch (RemoteException e) {
                    throw new IOException("Can't set SyncKey in ContactsProvider");
                }
            }
            mMailbox.mSyncKey = syncKey;
        }
    }

    public static final class EasChildren {
        private EasChildren() {}

        /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_children";
        public static final int MAX_CHILDREN = 8;
        public static final String[] ROWS =
            new String[] {"data2", "data3", "data4", "data5", "data6", "data7", "data8", "data9"};
    }

    public static final class EasPersonal {
        String anniversary;
        String fileAs;

            /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_personal";
        public static final String ANNIVERSARY = "data2";
        public static final String FILE_AS = "data4";

        boolean hasData() {
            return anniversary != null || fileAs != null;
        }
    }

    public static final class EasBusiness {
        String customerId;
        String governmentId;
        String accountName;

        /** MIME type used when storing this in data table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/eas_business";
        public static final String CUSTOMER_ID = "data6";
        public static final String GOVERNMENT_ID = "data7";
        public static final String ACCOUNT_NAME = "data8";

        boolean hasData() {
            return customerId != null || governmentId != null || accountName != null;
        }
    }

    public static final class Address {
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

    class EmailRow implements UntypedRow {
        String email;
        String displayName;

        public EmailRow(String _email) {
            Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(_email);
            // Can't happen, but belt & suspenders
            if (tokens.length == 0) {
                email = "";
                displayName = "";
            } else {
                Rfc822Token token = tokens[0];
                email = token.getAddress();
                displayName = token.getName();
            }
        }

        public void addValues(RowBuilder builder) {
            builder.withValue(Email.DATA, email);
            builder.withValue(Email.DISPLAY_NAME, displayName);
        }

        public boolean isSameAs(int type, String value) {
            return email.equalsIgnoreCase(value);
        }
    }

    class ImRow implements UntypedRow {
        String im;

        public ImRow(String _im) {
            im = _im;
        }

        public void addValues(RowBuilder builder) {
            builder.withValue(Im.DATA, im);
        }

        public boolean isSameAs(int type, String value) {
            return im.equalsIgnoreCase(value);
        }
    }

    class PhoneRow implements UntypedRow {
        String phone;
        int type;

        public PhoneRow(String _phone, int _type) {
            phone = _phone;
            type = _type;
        }

        public void addValues(RowBuilder builder) {
            builder.withValue(Im.DATA, phone);
            builder.withValue(Phone.TYPE, type);
        }

        public boolean isSameAs(int _type, String value) {
            return type == _type && phone.equalsIgnoreCase(value);
        }
    }

   class EasContactsSyncParser extends AbstractSyncParser {

        String[] mBindArgument = new String[1];
        String mMailboxIdAsString;
        Uri mAccountUri;
        ContactOperations ops = new ContactOperations();

        public EasContactsSyncParser(InputStream in, ContactsSyncAdapter adapter)
                throws IOException {
            super(in, adapter);
            mAccountUri = uriWithAccountAndIsSyncAdapter(RawContacts.CONTENT_URI);
        }

        @Override
        public void wipe() {
            mContentResolver.delete(mAccountUri, null, null);
        }

        public void addData(String serverId, ContactOperations ops, Entity entity)
                throws IOException {
            String fileAs = null;
            String prefix = null;
            String firstName = null;
            String lastName = null;
            String middleName = null;
            String suffix = null;
            String companyName = null;
            String yomiFirstName = null;
            String yomiLastName = null;
            String yomiCompanyName = null;
            String title = null;
            String department = null;
            String officeLocation = null;
            Address home = new Address();
            Address work = new Address();
            Address other = new Address();
            EasBusiness business = new EasBusiness();
            EasPersonal personal = new EasPersonal();
            ArrayList<String> children = new ArrayList<String>();
            ArrayList<UntypedRow> emails = new ArrayList<UntypedRow>();
            ArrayList<UntypedRow> ims = new ArrayList<UntypedRow>();
            ArrayList<UntypedRow> homePhones = new ArrayList<UntypedRow>();
            ArrayList<UntypedRow> workPhones = new ArrayList<UntypedRow>();
            if (entity == null) {
                ops.newContact(serverId);
            }

            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.CONTACTS_FIRST_NAME:
                        firstName = getValue();
                        break;
                    case Tags.CONTACTS_LAST_NAME:
                        lastName = getValue();
                        break;
                    case Tags.CONTACTS_MIDDLE_NAME:
                        middleName = getValue();
                        break;
                    case Tags.CONTACTS_FILE_AS:
                        fileAs = getValue();
                        break;
                    case Tags.CONTACTS_SUFFIX:
                        suffix = getValue();
                        break;
                    case Tags.CONTACTS_COMPANY_NAME:
                        companyName = getValue();
                        break;
                    case Tags.CONTACTS_JOB_TITLE:
                        title = getValue();
                        break;
                    case Tags.CONTACTS_EMAIL1_ADDRESS:
                    case Tags.CONTACTS_EMAIL2_ADDRESS:
                    case Tags.CONTACTS_EMAIL3_ADDRESS:
                        emails.add(new EmailRow(getValue()));
                        break;
                    case Tags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER:
                    case Tags.CONTACTS_BUSINESS_TELEPHONE_NUMBER:
                        workPhones.add(new PhoneRow(getValue(), Phone.TYPE_WORK));
                        break;
                    case Tags.CONTACTS2_MMS:
                        ops.addPhone(entity, Phone.TYPE_MMS, getValue());
                        break;
                    case Tags.CONTACTS_BUSINESS_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_WORK, getValue());
                        break;
                    case Tags.CONTACTS2_COMPANY_MAIN_PHONE:
                        ops.addPhone(entity, Phone.TYPE_COMPANY_MAIN, getValue());
                        break;
                    case Tags.CONTACTS_HOME_FAX_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_FAX_HOME, getValue());
                        break;
                    case Tags.CONTACTS_HOME_TELEPHONE_NUMBER:
                    case Tags.CONTACTS_HOME2_TELEPHONE_NUMBER:
                        homePhones.add(new PhoneRow(getValue(), Phone.TYPE_HOME));
                        break;
                    case Tags.CONTACTS_MOBILE_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_MOBILE, getValue());
                        break;
                    case Tags.CONTACTS_CAR_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_CAR, getValue());
                        break;
                    case Tags.CONTACTS_RADIO_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_RADIO, getValue());
                        break;
                    case Tags.CONTACTS_PAGER_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_PAGER, getValue());
                        break;
                    case Tags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER:
                        ops.addPhone(entity, Phone.TYPE_ASSISTANT, getValue());
                        break;
                    case Tags.CONTACTS2_IM_ADDRESS:
                    case Tags.CONTACTS2_IM_ADDRESS_2:
                    case Tags.CONTACTS2_IM_ADDRESS_3:
                        ims.add(new ImRow(getValue()));
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_CITY:
                        work.city = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_COUNTRY:
                        work.country = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE:
                        work.code = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_STATE:
                        work.state = getValue();
                        break;
                    case Tags.CONTACTS_BUSINESS_ADDRESS_STREET:
                        work.street = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_CITY:
                        home.city = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_COUNTRY:
                        home.country = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_POSTAL_CODE:
                        home.code = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_STATE:
                        home.state = getValue();
                        break;
                    case Tags.CONTACTS_HOME_ADDRESS_STREET:
                        home.street = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_CITY:
                        other.city = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_COUNTRY:
                        other.country = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE:
                        other.code = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_STATE:
                        other.state = getValue();
                        break;
                    case Tags.CONTACTS_OTHER_ADDRESS_STREET:
                        other.street = getValue();
                        break;

                    case Tags.CONTACTS_CHILDREN:
                        childrenParser(children);
                        break;

                    case Tags.CONTACTS_YOMI_COMPANY_NAME:
                        yomiCompanyName = getValue();
                        break;
                    case Tags.CONTACTS_YOMI_FIRST_NAME:
                        yomiFirstName = getValue();
                        break;
                    case Tags.CONTACTS_YOMI_LAST_NAME:
                        yomiLastName = getValue();
                        break;

                    case Tags.CONTACTS2_NICKNAME:
                        ops.addNickname(entity, getValue());
                        break;

                    case Tags.CONTACTS_ASSISTANT_NAME:
                        ops.addRelation(entity, Relation.TYPE_ASSISTANT, getValue());
                        break;
                    case Tags.CONTACTS2_MANAGER_NAME:
                        ops.addRelation(entity, Relation.TYPE_MANAGER, getValue());
                        break;
                    case Tags.CONTACTS_SPOUSE:
                        ops.addRelation(entity, Relation.TYPE_SPOUSE, getValue());
                        break;
                    case Tags.CONTACTS_DEPARTMENT:
                        department = getValue();
                        break;
                    case Tags.CONTACTS_TITLE:
                        prefix = getValue();
                        break;

                    // EAS Business
                    case Tags.CONTACTS_OFFICE_LOCATION:
                        officeLocation = getValue();
                        break;
                    case Tags.CONTACTS2_CUSTOMER_ID:
                        business.customerId = getValue();
                        break;
                    case Tags.CONTACTS2_GOVERNMENT_ID:
                        business.governmentId = getValue();
                        break;
                    case Tags.CONTACTS2_ACCOUNT_NAME:
                        business.accountName = getValue();
                        break;

                    // EAS Personal
                    case Tags.CONTACTS_ANNIVERSARY:
                        personal.anniversary = getValue();
                        break;
                    case Tags.CONTACTS_BIRTHDAY:
                        ops.addBirthday(entity, getValue());
                        break;
                    case Tags.CONTACTS_WEBPAGE:
                        ops.addWebpage(entity, getValue());
                        break;

                    case Tags.CONTACTS_PICTURE:
                        ops.addPhoto(entity, getValue());
                        break;

                    case Tags.BASE_BODY:
                        ops.addNote(entity, bodyParser());
                        break;
                    case Tags.CONTACTS_BODY:
                        ops.addNote(entity, getValue());
                        break;

                    case Tags.CONTACTS_CATEGORIES:
                        mGroupsUsed = true;
                        categoriesParser(ops, entity);
                        break;

                    case Tags.CONTACTS_COMPRESSED_RTF:
                        // We don't use this, and it isn't necessary to upload, so we'll ignore it
                        skipTag();
                        break;

                    default:
                        skipTag();
                }
            }

            // We must have first name, last name, or company name
            String name = null;
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
            }

            ops.addName(entity, prefix, firstName, lastName, middleName, suffix, name,
                    yomiFirstName, yomiLastName, fileAs);
            ops.addBusiness(entity, business);
            ops.addPersonal(entity, personal);

            ops.addUntyped(entity, emails, Email.CONTENT_ITEM_TYPE, -1, MAX_EMAIL_ROWS);
            ops.addUntyped(entity, ims, Im.CONTENT_ITEM_TYPE, -1, MAX_IM_ROWS);
            ops.addUntyped(entity, homePhones, Phone.CONTENT_ITEM_TYPE, Phone.TYPE_HOME,
                    MAX_PHONE_ROWS);
            ops.addUntyped(entity, workPhones, Phone.CONTENT_ITEM_TYPE, Phone.TYPE_WORK,
                    MAX_PHONE_ROWS);

            if (!children.isEmpty()) {
                ops.addChildren(entity, children);
            }

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
                ops.addOrganization(entity, Organization.TYPE_WORK, companyName, title, department,
                        yomiCompanyName, officeLocation);
            }

            if (entity != null) {
                // We've been removing rows from the list as they've been found in the xml
                // Any that are left must have been deleted on the server
                ArrayList<NamedContentValues> ncvList = entity.getSubValues();
                for (NamedContentValues ncv: ncvList) {
                    // These rows need to be deleted...
                    Uri u = dataUriFromNamedContentValues(ncv);
                    ops.add(ContentProviderOperation.newDelete(addCallerIsSyncAdapterParameter(u))
                            .build());
                }
            }
        }

        private void categoriesParser(ContactOperations ops, Entity entity) throws IOException {
            while (nextTag(Tags.CONTACTS_CATEGORIES) != END) {
                switch (tag) {
                    case Tags.CONTACTS_CATEGORY:
                        ops.addGroup(entity, getValue());
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private void childrenParser(ArrayList<String> children) throws IOException {
            while (nextTag(Tags.CONTACTS_CHILDREN) != END) {
                switch (tag) {
                    case Tags.CONTACTS_CHILD:
                        if (children.size() < EasChildren.MAX_CHILDREN) {
                            children.add(getValue());
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private String bodyParser() throws IOException {
            String body = null;
            while (nextTag(Tags.BASE_BODY) != END) {
                switch (tag) {
                    case Tags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            return body;
        }

        public void addParser(ContactOperations ops) throws IOException {
            String serverId = null;
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID: // same as
                        serverId = getValue();
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
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

        private Cursor getClientIdCursor(String clientId) {
            mBindArgument[0] = clientId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, CLIENT_ID_SELECTION,
                    mBindArgument, null);
        }

        public void deleteParser(ContactOperations ops) throws IOException {
            while (nextTag(Tags.SYNC_DELETE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                userLog("Deleting ", serverId);
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
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                // TODO Handle deleted individual rows...
                                Uri uri = ContentUris.withAppendedId(
                                        RawContacts.CONTENT_URI, c.getLong(0));
                                uri = Uri.withAppendedPath(
                                        uri, RawContacts.Entity.CONTENT_DIRECTORY);
                                EntityIterator entityIterator = RawContacts.newEntityIterator(
                                    mContentResolver.query(uri, null, null, null, null));
                                if (entityIterator.hasNext()) {
                                    entity = entityIterator.next();
                                }
                                userLog("Changing contact ", serverId);
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops, entity);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        @Override
        public void commandsParser() throws IOException {
            while (nextTag(Tags.SYNC_COMMANDS) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addParser(ops);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_DELETE) {
                    deleteParser(ops);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeParser(ops);
                    incrementChangeCount();
                } else
                    skipTag();
            }
        }

        @Override
        public void commit() throws IOException {
           // Save the syncKey here, using the Helper provider by Contacts provider
            userLog("Contacts SyncKey saved as: ", mMailbox.mSyncKey);
            ops.add(SyncStateContract.Helpers.newSetOperation(SyncState.CONTENT_URI,
                    mAccountManagerAccount, mMailbox.mSyncKey.getBytes()));

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
                        mContentResolver.update(
                                addCallerIsSyncAdapterParameter(RawContacts.CONTENT_URI), cv,
                                RawContacts._ID + "=" + idString, null);
                    }
                }
            }
        }

        public void addResponsesParser() throws IOException {
            String serverId = null;
            String clientId = null;
            ContentValues cv = new ContentValues();
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_CLIENT_ID:
                        clientId = getValue();
                        break;
                    case Tags.SYNC_STATUS:
                        getValue();
                        break;
                    default:
                        skipTag();
                }
            }

            // This is theoretically impossible, but...
            if (clientId == null || serverId == null) return;

            Cursor c = getClientIdCursor(clientId);
            try {
                if (c.moveToFirst()) {
                    cv.put(RawContacts.SOURCE_ID, serverId);
                    cv.put(RawContacts.DIRTY, 0);
                    ops.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(
                                    addCallerIsSyncAdapterParameter(RawContacts.CONTENT_URI),
                                    c.getLong(0)))
                            .withValues(cv)
                            .build());
                    userLog("New contact " + clientId + " was given serverId: " + serverId);
                }
            } finally {
                c.close();
            }
        }

        public void changeResponsesParser() throws IOException {
            String serverId = null;
            String status = null;
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_STATUS:
                        status = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            if (serverId != null && status != null) {
                userLog("Changed contact " + serverId + " failed with status: " + status);
            }
        }


        @Override
        public void responsesParser() throws IOException {
            // Handle server responses here (for Add and Change)
            while (nextTag(Tags.SYNC_RESPONSES) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addResponsesParser();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeResponsesParser();
                } else
                    skipTag();
            }
        }
    }


    private Uri uriWithAccountAndIsSyncAdapter(Uri uri) {
        return uri.buildUpon()
            .appendQueryParameter(RawContacts.ACCOUNT_NAME, mAccount.mEmailAddress)
            .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                    com.android.email.Email.EXCHANGE_ACCOUNT_MANAGER_TYPE)
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .build();
    }

    /**
     * SmartBuilder is a wrapper for the Builder class that is used to create/update rows for a
     * ContentProvider.  It has, in addition to the Builder, ContentValues which, if present,
     * represent the current values of that row, that can be compared against current values to
     * see whether an update is even necessary.  The methods on SmartBuilder are delegated to
     * the Builder.
     */
    private class RowBuilder {
        Builder builder;
        ContentValues cv;

        public RowBuilder(Builder _builder) {
            builder = _builder;
        }

        public RowBuilder(Builder _builder, NamedContentValues _ncv) {
            builder = _builder;
            cv = _ncv.values;
        }

        RowBuilder withValues(ContentValues values) {
            builder.withValues(values);
            return this;
        }

        RowBuilder withValueBackReference(String key, int previousResult) {
            builder.withValueBackReference(key, previousResult);
            return this;
        }

        ContentProviderOperation build() {
            return builder.build();
        }

        RowBuilder withValue(String key, Object value) {
            builder.withValue(key, value);
            return this;
        }
    }

    private class ContactOperations extends ArrayList<ContentProviderOperation> {
        private static final long serialVersionUID = 1L;
        private int mCount = 0;
        private int mContactBackValue = mCount;
        // Make an array big enough for the PIM window (max items we can get)
        private int[] mContactIndexArray =
            new int[Integer.parseInt(EasSyncService.PIM_WINDOW_SIZE)];
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
                .newInsert(uriWithAccountAndIsSyncAdapter(RawContacts.CONTENT_URI));
            ContentValues values = new ContentValues();
            values.put(RawContacts.SOURCE_ID, serverId);
            builder.withValues(values);
            mContactBackValue = mCount;
            mContactIndexArray[mContactIndexCount++] = mCount;
            add(builder.build());
        }

        public void delete(long id) {
            add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
                            .buildUpon()
                            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                            .build())
                    .build());
        }

        public void execute() {
            synchronized (mService.getSynchronizer()) {
                if (!mService.isStopped()) {
                    try {
                        if (!isEmpty()) {
                            mService.userLog("Executing ", size(), " CPO's");
                            mResults = mContext.getContentResolver().applyBatch(
                                    ContactsContract.AUTHORITY, this);
                        }
                    } catch (RemoteException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting contact during server update", e);
                    } catch (OperationApplicationException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting contact during server update", e);
                    }
                }
            }
        }

        /**
         * Given the list of NamedContentValues for an entity, a mime type, and a subtype,
         * tries to find a match, returning it
         * @param list the list of NCV's from the contact entity
         * @param contentItemType the mime type we're looking for
         * @param type the subtype (e.g. HOME, WORK, etc.)
         * @return the matching NCV or null if not found
         */
        private NamedContentValues findTypedData(ArrayList<NamedContentValues> list,
                String contentItemType, int type, String stringType) {
            NamedContentValues result = null;

            // Loop through the ncv's, looking for an existing row
            for (NamedContentValues namedContentValues: list) {
                Uri uri = namedContentValues.uri;
                ContentValues cv = namedContentValues.values;
                if (Data.CONTENT_URI.equals(uri)) {
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(contentItemType)) {
                        if (stringType != null) {
                            if (cv.getAsString(GroupMembership.GROUP_ROW_ID).equals(stringType)) {
                                result = namedContentValues;
                            }
                        // Note Email.TYPE could be ANY type column; they are all defined in
                        // the private CommonColumns class in ContactsContract
                        // We'll accept either type < 0 (don't care), cv doesn't have a type,
                        // or the types are equal
                        } else if (type < 0 || !cv.containsKey(Email.TYPE) ||
                                cv.getAsInteger(Email.TYPE) == type) {
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
         * Given the list of NamedContentValues for an entity and a mime type
         * gather all of the matching NCV's, returning them
         * @param list the list of NCV's from the contact entity
         * @param contentItemType the mime type we're looking for
         * @param type the subtype (e.g. HOME, WORK, etc.)
         * @return the matching NCVs
         */
        private ArrayList<NamedContentValues> findUntypedData(ArrayList<NamedContentValues> list,
                int type, String contentItemType) {
            ArrayList<NamedContentValues> result = new ArrayList<NamedContentValues>();

            // Loop through the ncv's, looking for an existing row
            for (NamedContentValues namedContentValues: list) {
                Uri uri = namedContentValues.uri;
                ContentValues cv = namedContentValues.values;
                if (Data.CONTENT_URI.equals(uri)) {
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(contentItemType)) {
                        if (type != -1) {
                            int subtype = cv.getAsInteger(Phone.TYPE);
                            if (type != subtype) {
                                continue;
                            }
                        }
                        result.add(namedContentValues);
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
         * @param stringType for groups, the name of the group (type will be ignored), or null
         * @return the created SmartBuilder
         */
        public RowBuilder createBuilder(Entity entity, String mimeType, int type,
                String stringType) {
            RowBuilder builder = null;

            if (entity != null) {
                NamedContentValues ncv =
                    findTypedData(entity.getSubValues(), mimeType, type, stringType);
                if (ncv != null) {
                    builder = new RowBuilder(
                            ContentProviderOperation
                                .newUpdate(addCallerIsSyncAdapterParameter(
                                    dataUriFromNamedContentValues(ncv))),
                            ncv);
                }
            }

            if (builder == null) {
                builder = newRowBuilder(entity, mimeType);
            }

            // Return the appropriate builder (insert or update)
            // Caller will fill in the appropriate values; 4 MIMETYPE is already set
            return builder;
        }

        private RowBuilder typedRowBuilder(Entity entity, String mimeType, int type) {
            return createBuilder(entity, mimeType, type, null);
        }

        private RowBuilder untypedRowBuilder(Entity entity, String mimeType) {
            return createBuilder(entity, mimeType, -1, null);
        }

        private RowBuilder newRowBuilder(Entity entity, String mimeType) {
            // This is a new row; first get the contactId
            // If the Contact is new, use the saved back value; otherwise the value in the entity
            int contactId = mContactBackValue;
            if (entity != null) {
                contactId = entity.getEntityValues().getAsInteger(RawContacts._ID);
            }

            // Create an insert operation with the proper contactId reference
            RowBuilder builder =
                new RowBuilder(ContentProviderOperation.newInsert(
                        addCallerIsSyncAdapterParameter(Data.CONTENT_URI)));
            if (entity == null) {
                builder.withValueBackReference(Data.RAW_CONTACT_ID, contactId);
            } else {
                builder.withValue(Data.RAW_CONTACT_ID, contactId);
            }

            // Set the mime type of the row
            builder.withValue(Data.MIMETYPE, mimeType);
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

        public void addChildren(Entity entity, ArrayList<String> children) {
            RowBuilder builder = untypedRowBuilder(entity, EasChildren.CONTENT_ITEM_TYPE);
            int i = 0;
            for (String child: children) {
                builder.withValue(EasChildren.ROWS[i++], child);
            }
            add(builder.build());
        }

        public void addGroup(Entity entity, String group) {
            RowBuilder builder =
                createBuilder(entity, GroupMembership.CONTENT_ITEM_TYPE, -1, group);
            builder.withValue(GroupMembership.GROUP_SOURCE_ID, group);
            add(builder.build());
        }

        public void addBirthday(Entity entity, String birthday) {
            RowBuilder builder =
                    typedRowBuilder(entity, Event.CONTENT_ITEM_TYPE, Event.TYPE_BIRTHDAY);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Event.START_DATE, birthday)) {
                return;
            }
            builder.withValue(Event.START_DATE, birthday);
            builder.withValue(Event.TYPE, Event.TYPE_BIRTHDAY);
            add(builder.build());
        }

        public void addName(Entity entity, String prefix, String givenName, String familyName,
                String middleName, String suffix, String displayName, String yomiFirstName,
                String yomiLastName, String fileAs) {
            RowBuilder builder = untypedRowBuilder(entity, StructuredName.CONTENT_ITEM_TYPE);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, StructuredName.GIVEN_NAME, givenName) &&
                    cvCompareString(cv, StructuredName.FAMILY_NAME, familyName) &&
                    cvCompareString(cv, StructuredName.MIDDLE_NAME, middleName) &&
                    cvCompareString(cv, StructuredName.PREFIX, prefix) &&
                    cvCompareString(cv, StructuredName.PHONETIC_GIVEN_NAME, yomiFirstName) &&
                    cvCompareString(cv, StructuredName.PHONETIC_FAMILY_NAME, yomiLastName) &&
                    //cvCompareString(cv, StructuredName.DISPLAY_NAME, fileAs) &&
                    cvCompareString(cv, StructuredName.SUFFIX, suffix)) {
                return;
            }
            builder.withValue(StructuredName.GIVEN_NAME, givenName);
            builder.withValue(StructuredName.FAMILY_NAME, familyName);
            builder.withValue(StructuredName.MIDDLE_NAME, middleName);
            builder.withValue(StructuredName.SUFFIX, suffix);
            builder.withValue(StructuredName.PHONETIC_GIVEN_NAME, yomiFirstName);
            builder.withValue(StructuredName.PHONETIC_FAMILY_NAME, yomiLastName);
            builder.withValue(StructuredName.PREFIX, prefix);
            //builder.withValue(StructuredName.DISPLAY_NAME, fileAs);
            add(builder.build());
        }

        public void addPersonal(Entity entity, EasPersonal personal) {
            RowBuilder builder = untypedRowBuilder(entity, EasPersonal.CONTENT_ITEM_TYPE);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, EasPersonal.ANNIVERSARY, personal.anniversary) &&
                    cvCompareString(cv, EasPersonal.FILE_AS , personal.fileAs)) {
                return;
            }
            if (!personal.hasData()) {
                return;
            }
            builder.withValue(EasPersonal.FILE_AS, personal.fileAs);
            builder.withValue(EasPersonal.ANNIVERSARY, personal.anniversary);
            add(builder.build());
        }

        public void addBusiness(Entity entity, EasBusiness business) {
            RowBuilder builder = untypedRowBuilder(entity, EasBusiness.CONTENT_ITEM_TYPE);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, EasBusiness.ACCOUNT_NAME, business.accountName) &&
                    cvCompareString(cv, EasBusiness.CUSTOMER_ID, business.customerId) &&
                    cvCompareString(cv, EasBusiness.GOVERNMENT_ID, business.governmentId)) {
                return;
            }
            if (!business.hasData()) {
                return;
            }
            builder.withValue(EasBusiness.ACCOUNT_NAME, business.accountName);
            builder.withValue(EasBusiness.CUSTOMER_ID, business.customerId);
            builder.withValue(EasBusiness.GOVERNMENT_ID, business.governmentId);
            add(builder.build());
        }

        public void addPhoto(Entity entity, String photo) {
            RowBuilder builder = untypedRowBuilder(entity, Photo.CONTENT_ITEM_TYPE);
            // We're always going to add this; it's not worth trying to figure out whether the
            // picture is the same as the one stored.
            byte[] pic = Base64.decode(photo, Base64.DEFAULT);
            builder.withValue(Photo.PHOTO, pic);
            add(builder.build());
        }

        public void addPhone(Entity entity, int type, String phone) {
            RowBuilder builder = typedRowBuilder(entity, Phone.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Phone.NUMBER, phone)) {
                return;
            }
            builder.withValue(Phone.TYPE, type);
            builder.withValue(Phone.NUMBER, phone);
            add(builder.build());
        }

        public void addWebpage(Entity entity, String url) {
            RowBuilder builder = untypedRowBuilder(entity, Website.CONTENT_ITEM_TYPE);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Website.URL, url)) {
                return;
            }
            builder.withValue(Website.TYPE, Website.TYPE_WORK);
            builder.withValue(Website.URL, url);
            add(builder.build());
        }

        public void addRelation(Entity entity, int type, String value) {
            RowBuilder builder = typedRowBuilder(entity, Relation.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Relation.DATA, value)) {
                return;
            }
            builder.withValue(Relation.TYPE, type);
            builder.withValue(Relation.DATA, value);
            add(builder.build());
        }

        public void addNickname(Entity entity, String name) {
            RowBuilder builder =
                typedRowBuilder(entity, Nickname.CONTENT_ITEM_TYPE, Nickname.TYPE_DEFAULT);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Nickname.NAME, name)) {
                return;
            }
            builder.withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT);
            builder.withValue(Nickname.NAME, name);
            add(builder.build());
        }

        public void addPostal(Entity entity, int type, String street, String city, String state,
                String country, String code) {
            RowBuilder builder = typedRowBuilder(entity, StructuredPostal.CONTENT_ITEM_TYPE,
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

       /**
         * We now are dealing with up to maxRows typeless rows of mimeType data.  We need to try to
         * match them with existing rows; if there's a match, everything's great.  Otherwise, we
         * either need to add a new row for the data, or we have to replace an existing one
         * that no longer matches.  This is similar to the way Emails are handled.
         */
        public void addUntyped(Entity entity, ArrayList<UntypedRow> rows, String mimeType,
                int type, int maxRows) {
            // Make a list of all same type rows in the existing entity
            ArrayList<NamedContentValues> oldValues = EMPTY_ARRAY_NAMEDCONTENTVALUES;
            ArrayList<NamedContentValues> entityValues = EMPTY_ARRAY_NAMEDCONTENTVALUES;
            if (entity != null) {
                oldValues = findUntypedData(entityValues, type, mimeType);
                entityValues = entity.getSubValues();
            }

            // These will be rows needing replacement with new values
            ArrayList<UntypedRow> rowsToReplace = new ArrayList<UntypedRow>();

            // The count of existing rows
            int numRows = oldValues.size();
            for (UntypedRow row: rows) {
                boolean found = false;
                // If we already have this row, mark it
                for (NamedContentValues ncv: oldValues) {
                    ContentValues cv = ncv.values;
                    String data = cv.getAsString(COMMON_DATA_ROW);
                    int rowType = -1;
                    if (cv.containsKey(COMMON_TYPE_ROW)) {
                        rowType = cv.getAsInteger(COMMON_TYPE_ROW);
                    }
                    if (row.isSameAs(rowType, data)) {
                        cv.put(FOUND_DATA_ROW, true);
                        // Remove this to indicate it's still being used
                        entityValues.remove(ncv);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    // If we don't, there are two possibilities
                    if (numRows < maxRows) {
                        // If there are available rows, add a new one
                        RowBuilder builder = newRowBuilder(entity, mimeType);
                        row.addValues(builder);
                        add(builder.build());
                        numRows++;
                    } else {
                        // Otherwise, say we need to replace a row with this
                        rowsToReplace.add(row);
                    }
                }
            }

            // Go through rows needing replacement
            for (UntypedRow row: rowsToReplace) {
                for (NamedContentValues ncv: oldValues) {
                    ContentValues cv = ncv.values;
                    // Find a row that hasn't been used (i.e. doesn't match current rows)
                    if (!cv.containsKey(FOUND_DATA_ROW)) {
                        // And update it
                        RowBuilder builder = new RowBuilder(
                                ContentProviderOperation
                                    .newUpdate(addCallerIsSyncAdapterParameter(
                                        dataUriFromNamedContentValues(ncv))),
                                ncv);
                        row.addValues(builder);
                        add(builder.build());
                    }
                }
            }
        }

        public void addOrganization(Entity entity, int type, String company, String title,
                String department, String yomiCompanyName, String officeLocation) {
            RowBuilder builder = typedRowBuilder(entity, Organization.CONTENT_ITEM_TYPE, type);
            ContentValues cv = builder.cv;
            if (cv != null && cvCompareString(cv, Organization.COMPANY, company) &&
                    cvCompareString(cv, Organization.PHONETIC_NAME, yomiCompanyName) &&
                    cvCompareString(cv, Organization.DEPARTMENT, department) &&
                    cvCompareString(cv, Organization.TITLE, title) &&
                    cvCompareString(cv, Organization.OFFICE_LOCATION, officeLocation)) {
                return;
            }
            builder.withValue(Organization.TYPE, type);
            builder.withValue(Organization.COMPANY, company);
            builder.withValue(Organization.TITLE, title);
            builder.withValue(Organization.DEPARTMENT, department);
            builder.withValue(Organization.PHONETIC_NAME, yomiCompanyName);
            builder.withValue(Organization.OFFICE_LOCATION, officeLocation);
            add(builder.build());
        }

        public void addNote(Entity entity, String note) {
            RowBuilder builder = typedRowBuilder(entity, Note.CONTENT_ITEM_TYPE, -1);
            ContentValues cv = builder.cv;
            if (note == null) return;
            note = note.replaceAll("\r\n", "\n");
            if (cv != null && cvCompareString(cv, Note.NOTE, note)) {
                return;
            }

            // Reject notes with nothing in them.  Often, we get something from Outlook when
            // nothing was ever entered.  Sigh.
            int len = note.length();
            int i = 0;
            for (; i < len; i++) {
                char c = note.charAt(i);
                if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            if (i == len) return;

            builder.withValue(Note.NOTE, note);
            add(builder.build());
        }
    }

    /**
     * Generate the uri for the data row associated with this NamedContentValues object
     * @param ncv the NamedContentValues object
     * @return a uri that can be used to refer to this row
     */
    public Uri dataUriFromNamedContentValues(NamedContentValues ncv) {
        long id = ncv.values.getAsLong(RawContacts._ID);
        Uri dataUri = ContentUris.withAppendedId(ncv.uri, id);
        return dataUri;
    }

    @Override
    public void cleanup() {
        // Mark the changed contacts dirty = 0
        // Permanently delete the user deletions
        ContactOperations ops = new ContactOperations();
        for (Long id: mUpdatedIdList) {
            ops.add(ContentProviderOperation
                    .newUpdate(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
                            .buildUpon()
                            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                            .build())
                    .withValue(RawContacts.DIRTY, 0).build());
        }
        for (Long id: mDeletedIdList) {
            ops.add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)
                            .buildUpon()
                            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                            .build())
                    .build());
        }
        ops.execute();
        ContentResolver cr = mContext.getContentResolver();
        if (mGroupsUsed) {
            // Make sure the title column is set for all of our groups
            // And that all of our groups are visible
            // TODO Perhaps the visible part should only happen when the group is created, but
            // this is fine for now.
            Uri groupsUri = uriWithAccountAndIsSyncAdapter(Groups.CONTENT_URI);
            Cursor c = cr.query(groupsUri, new String[] {Groups.SOURCE_ID, Groups.TITLE},
                    Groups.TITLE + " IS NULL", null, null);
            ContentValues values = new ContentValues();
            values.put(Groups.GROUP_VISIBLE, 1);
            try {
                while (c.moveToNext()) {
                    String sourceId = c.getString(0);
                    values.put(Groups.TITLE, sourceId);
                    cr.update(uriWithAccountAndIsSyncAdapter(groupsUri), values,
                            Groups.SOURCE_ID + "=?", new String[] {sourceId});
                }
            } finally {
                c.close();
            }
        }
    }

    @Override
    public String getCollectionName() {
        return "Contacts";
    }

    private void sendEmail(Serializer s, ContentValues cv, int count, String displayName)
            throws IOException {
        // Get both parts of the email address (a newly created one in the UI won't have a name)
        String addr = cv.getAsString(Email.DATA);
        String name = cv.getAsString(Email.DISPLAY_NAME);
        if (name == null) {
            if (displayName != null) {
                name = displayName;
            } else {
                name = addr;
            }
        }
        // Compose address from name and addr
        if (addr != null) {
            String value = '\"' + name + "\" <" + addr + '>';
            if (count < MAX_EMAIL_ROWS) {
                s.data(EMAIL_TAGS[count], value);
            }
        }
    }

    private void sendIm(Serializer s, ContentValues cv, int count) throws IOException {
        String value = cv.getAsString(Im.DATA);
        if (value == null) return;
        if (count < MAX_IM_ROWS) {
            s.data(IM_TAGS[count], value);
        }
    }

    private void sendOnePostal(Serializer s, ContentValues cv, int[] fieldNames)
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

    private void sendStructuredPostal(Serializer s, ContentValues cv) throws IOException {
        switch (cv.getAsInteger(StructuredPostal.TYPE)) {
            case StructuredPostal.TYPE_HOME:
                sendOnePostal(s, cv, HOME_ADDRESS_TAGS);
                break;
            case StructuredPostal.TYPE_WORK:
                sendOnePostal(s, cv, WORK_ADDRESS_TAGS);
                break;
            case StructuredPostal.TYPE_OTHER:
                sendOnePostal(s, cv, OTHER_ADDRESS_TAGS);
                break;
            default:
                break;
        }
    }

    private String sendStructuredName(Serializer s, ContentValues cv) throws IOException {
        String displayName = null;
        if (cv.containsKey(StructuredName.FAMILY_NAME)) {
            s.data(Tags.CONTACTS_LAST_NAME, cv.getAsString(StructuredName.FAMILY_NAME));
        }
        if (cv.containsKey(StructuredName.GIVEN_NAME)) {
            s.data(Tags.CONTACTS_FIRST_NAME, cv.getAsString(StructuredName.GIVEN_NAME));
        }
        if (cv.containsKey(StructuredName.MIDDLE_NAME)) {
            s.data(Tags.CONTACTS_MIDDLE_NAME, cv.getAsString(StructuredName.MIDDLE_NAME));
        }
        if (cv.containsKey(StructuredName.SUFFIX)) {
            s.data(Tags.CONTACTS_SUFFIX, cv.getAsString(StructuredName.SUFFIX));
        }
        if (cv.containsKey(StructuredName.PHONETIC_GIVEN_NAME)) {
            s.data(Tags.CONTACTS_YOMI_FIRST_NAME,
                    cv.getAsString(StructuredName.PHONETIC_GIVEN_NAME));
        }
        if (cv.containsKey(StructuredName.PHONETIC_FAMILY_NAME)) {
            s.data(Tags.CONTACTS_YOMI_LAST_NAME,
                    cv.getAsString(StructuredName.PHONETIC_FAMILY_NAME));
        }
        if (cv.containsKey(StructuredName.PREFIX)) {
            s.data(Tags.CONTACTS_TITLE, cv.getAsString(StructuredName.PREFIX));
        }
        if (cv.containsKey(StructuredName.DISPLAY_NAME)) {
            displayName = cv.getAsString(StructuredName.DISPLAY_NAME);
            s.data(Tags.CONTACTS_FILE_AS, displayName);
        }
        return displayName;
    }

    private void sendBusiness(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(EasBusiness.ACCOUNT_NAME)) {
            s.data(Tags.CONTACTS2_ACCOUNT_NAME, cv.getAsString(EasBusiness.ACCOUNT_NAME));
        }
        if (cv.containsKey(EasBusiness.CUSTOMER_ID)) {
            s.data(Tags.CONTACTS2_CUSTOMER_ID, cv.getAsString(EasBusiness.CUSTOMER_ID));
        }
        if (cv.containsKey(EasBusiness.GOVERNMENT_ID)) {
            s.data(Tags.CONTACTS2_GOVERNMENT_ID, cv.getAsString(EasBusiness.GOVERNMENT_ID));
        }
    }

    private void sendPersonal(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(EasPersonal.ANNIVERSARY)) {
            s.data(Tags.CONTACTS_ANNIVERSARY, cv.getAsString(EasPersonal.ANNIVERSARY));
        }
        if (cv.containsKey(EasPersonal.FILE_AS)) {
            s.data(Tags.CONTACTS_FILE_AS, cv.getAsString(EasPersonal.FILE_AS));
        }
    }

    private void sendBirthday(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Event.START_DATE)) {
            s.data(Tags.CONTACTS_BIRTHDAY, cv.getAsString(Event.START_DATE));
        }
    }

    private void sendPhoto(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Photo.PHOTO)) {
            byte[] bytes = cv.getAsByteArray(Photo.PHOTO);
            String pic = Base64.encodeToString(bytes, Base64.NO_WRAP);
            s.data(Tags.CONTACTS_PICTURE, pic);
        } else {
            // Send an empty tag, which signals the server to delete any pre-existing photo
            s.tag(Tags.CONTACTS_PICTURE);
        }
    }

    private void sendOrganization(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Organization.TITLE)) {
            s.data(Tags.CONTACTS_JOB_TITLE, cv.getAsString(Organization.TITLE));
        }
        if (cv.containsKey(Organization.COMPANY)) {
            s.data(Tags.CONTACTS_COMPANY_NAME, cv.getAsString(Organization.COMPANY));
        }
        if (cv.containsKey(Organization.DEPARTMENT)) {
            s.data(Tags.CONTACTS_DEPARTMENT, cv.getAsString(Organization.DEPARTMENT));
        }
        if (cv.containsKey(Organization.OFFICE_LOCATION)) {
            s.data(Tags.CONTACTS_OFFICE_LOCATION, cv.getAsString(Organization.OFFICE_LOCATION));
        }
    }

    private void sendNickname(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Nickname.NAME)) {
            s.data(Tags.CONTACTS2_NICKNAME, cv.getAsString(Nickname.NAME));
        }
    }

    private void sendWebpage(Serializer s, ContentValues cv) throws IOException {
        if (cv.containsKey(Website.URL)) {
            s.data(Tags.CONTACTS_WEBPAGE, cv.getAsString(Website.URL));
        }
    }

    private void sendNote(Serializer s, ContentValues cv) throws IOException {
        // Even when there is no local note, we must explicitly upsync an empty note,
        // which is the only way to force the server to delete any pre-existing note.
        String note = "";
        if (cv.containsKey(Note.NOTE)) {
            // EAS won't accept note data with raw newline characters
            note = cv.getAsString(Note.NOTE).replaceAll("\n", "\r\n");
        }
        // Format of upsync data depends on protocol version
        if (mService.mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
            s.start(Tags.BASE_BODY);
            s.data(Tags.BASE_TYPE, Eas.BODY_PREFERENCE_TEXT).data(Tags.BASE_DATA, note);
            s.end();
        } else {
            s.data(Tags.CONTACTS_BODY, note);
        }
    }

    private void sendChildren(Serializer s, ContentValues cv) throws IOException {
        boolean first = true;
        for (int i = 0; i < EasChildren.MAX_CHILDREN; i++) {
            String row = EasChildren.ROWS[i];
            if (cv.containsKey(row)) {
                if (first) {
                    s.start(Tags.CONTACTS_CHILDREN);
                    first = false;
                }
                s.data(Tags.CONTACTS_CHILD, cv.getAsString(row));
            }
        }
        if (!first) {
            s.end();
        }
    }

    private void sendPhone(Serializer s, ContentValues cv, int workCount, int homeCount)
            throws IOException {
        String value = cv.getAsString(Phone.NUMBER);
        if (value == null) return;
        switch (cv.getAsInteger(Phone.TYPE)) {
            case Phone.TYPE_WORK:
                if (workCount < MAX_PHONE_ROWS) {
                    s.data(WORK_PHONE_TAGS[workCount], value);
                }
                break;
            case Phone.TYPE_MMS:
                s.data(Tags.CONTACTS2_MMS, value);
                break;
            case Phone.TYPE_ASSISTANT:
                s.data(Tags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_FAX_WORK:
                s.data(Tags.CONTACTS_BUSINESS_FAX_NUMBER, value);
                break;
            case Phone.TYPE_COMPANY_MAIN:
                s.data(Tags.CONTACTS2_COMPANY_MAIN_PHONE, value);
                break;
            case Phone.TYPE_HOME:
                if (homeCount < MAX_PHONE_ROWS) {
                    s.data(HOME_PHONE_TAGS[homeCount], value);
                }
                break;
            case Phone.TYPE_MOBILE:
                s.data(Tags.CONTACTS_MOBILE_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_CAR:
                s.data(Tags.CONTACTS_CAR_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_PAGER:
                s.data(Tags.CONTACTS_PAGER_NUMBER, value);
                break;
            case Phone.TYPE_RADIO:
                s.data(Tags.CONTACTS_RADIO_TELEPHONE_NUMBER, value);
                break;
            case Phone.TYPE_FAX_HOME:
                s.data(Tags.CONTACTS_HOME_FAX_NUMBER, value);
                break;
            default:
                break;
        }
    }

    private void sendRelation(Serializer s, ContentValues cv) throws IOException {
        String value = cv.getAsString(Relation.DATA);
        if (value == null) return;
        switch (cv.getAsInteger(Relation.TYPE)) {
            case Relation.TYPE_ASSISTANT:
                s.data(Tags.CONTACTS_ASSISTANT_NAME, value);
                break;
            case Relation.TYPE_MANAGER:
                s.data(Tags.CONTACTS2_MANAGER_NAME, value);
                break;
            case Relation.TYPE_SPOUSE:
                s.data(Tags.CONTACTS_SPOUSE, value);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        // First, let's find Contacts that have changed.
        ContentResolver cr = mService.mContentResolver;
        Uri uri = RawContactsEntity.CONTENT_URI.buildUpon()
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, mAccount.mEmailAddress)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE,
                        com.android.email.Email.EXCHANGE_ACCOUNT_MANAGER_TYPE)
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build();

        if (getSyncKey().equals("0")) {
            return false;
        }

        // Get them all atomically
        EntityIterator ei = RawContacts.newEntityIterator(
                cr.query(uri, null, RawContacts.DIRTY + "=1", null, null));
        ContentValues cidValues = new ContentValues();
        try {
            boolean first = true;
            final Uri rawContactUri = addCallerIsSyncAdapterParameter(RawContacts.CONTENT_URI);
            while (ei.hasNext()) {
                Entity entity = ei.next();
                // For each of these entities, create the change commands
                ContentValues entityValues = entity.getEntityValues();
                String serverId = entityValues.getAsString(RawContacts.SOURCE_ID);
                ArrayList<Integer> groupIds = new ArrayList<Integer>();
                if (first) {
                    s.start(Tags.SYNC_COMMANDS);
                    userLog("Sending Contacts changes to the server");
                    first = false;
                }
                if (serverId == null) {
                    // This is a new contact; create a clientId
                    String clientId = "new_" + mMailbox.mId + '_' + System.currentTimeMillis();
                    userLog("Creating new contact with clientId: ", clientId);
                    s.start(Tags.SYNC_ADD).data(Tags.SYNC_CLIENT_ID, clientId);
                    // And save it in the raw contact
                    cidValues.put(RawContacts.SYNC1, clientId);
                    cr.update(ContentUris.
                            withAppendedId(rawContactUri,
                                    entityValues.getAsLong(RawContacts._ID)),
                                    cidValues, null, null);
                } else {
                    if (entityValues.getAsInteger(RawContacts.DELETED) == 1) {
                        userLog("Deleting contact with serverId: ", serverId);
                        s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                        mDeletedIdList.add(entityValues.getAsLong(RawContacts._ID));
                        continue;
                    }
                    userLog("Upsync change to contact with serverId: " + serverId);
                    s.start(Tags.SYNC_CHANGE).data(Tags.SYNC_SERVER_ID, serverId);
                }
                s.start(Tags.SYNC_APPLICATION_DATA);
                // Write out the data here
                int imCount = 0;
                int emailCount = 0;
                int homePhoneCount = 0;
                int workPhoneCount = 0;
                String displayName = null;
                ArrayList<ContentValues> emailValues = new ArrayList<ContentValues>();
                for (NamedContentValues ncv: entity.getSubValues()) {
                    ContentValues cv = ncv.values;
                    String mimeType = cv.getAsString(Data.MIMETYPE);
                    if (mimeType.equals(Email.CONTENT_ITEM_TYPE)) {
                        emailValues.add(cv);
                    } else if (mimeType.equals(Nickname.CONTENT_ITEM_TYPE)) {
                        sendNickname(s, cv);
                    } else if (mimeType.equals(EasChildren.CONTENT_ITEM_TYPE)) {
                        sendChildren(s, cv);
                    } else if (mimeType.equals(EasBusiness.CONTENT_ITEM_TYPE)) {
                        sendBusiness(s, cv);
                    } else if (mimeType.equals(Website.CONTENT_ITEM_TYPE)) {
                        sendWebpage(s, cv);
                    } else if (mimeType.equals(EasPersonal.CONTENT_ITEM_TYPE)) {
                        sendPersonal(s, cv);
                    } else if (mimeType.equals(Phone.CONTENT_ITEM_TYPE)) {
                        sendPhone(s, cv, workPhoneCount, homePhoneCount);
                        int type = cv.getAsInteger(Phone.TYPE);
                        if (type == Phone.TYPE_HOME) homePhoneCount++;
                        if (type == Phone.TYPE_WORK) workPhoneCount++;
                    } else if (mimeType.equals(Relation.CONTENT_ITEM_TYPE)) {
                        sendRelation(s, cv);
                    } else if (mimeType.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                        displayName = sendStructuredName(s, cv);
                    } else if (mimeType.equals(StructuredPostal.CONTENT_ITEM_TYPE)) {
                        sendStructuredPostal(s, cv);
                    } else if (mimeType.equals(Organization.CONTENT_ITEM_TYPE)) {
                        sendOrganization(s, cv);
                    } else if (mimeType.equals(Im.CONTENT_ITEM_TYPE)) {
                        sendIm(s, cv, imCount++);
                    } else if (mimeType.equals(Event.CONTENT_ITEM_TYPE)) {
                        Integer eventType = cv.getAsInteger(Event.TYPE);
                        if (eventType != null && eventType.equals(Event.TYPE_BIRTHDAY)) {
                            sendBirthday(s, cv);
                        }
                    } else if (mimeType.equals(GroupMembership.CONTENT_ITEM_TYPE)) {
                        // We must gather these, and send them together (below)
                        groupIds.add(cv.getAsInteger(GroupMembership.GROUP_ROW_ID));
                    } else if (mimeType.equals(Note.CONTENT_ITEM_TYPE)) {
                        sendNote(s, cv);
                    } else if (mimeType.equals(Photo.CONTENT_ITEM_TYPE)) {
                        sendPhoto(s, cv);
                    } else {
                        userLog("Contacts upsync, unknown data: ", mimeType);
                    }
                }

                // We do the email rows last, because we need to make sure we've found the
                // displayName (if one exists); this would be in a StructuredName rnow
                for (ContentValues cv: emailValues) {
                    sendEmail(s, cv, emailCount++, displayName);
                }

                // Now, we'll send up groups, if any
                if (!groupIds.isEmpty()) {
                    boolean groupFirst = true;
                    for (int id: groupIds) {
                        // Since we get id's from the provider, we need to find their names
                        Cursor c = cr.query(ContentUris.withAppendedId(Groups.CONTENT_URI, id),
                                GROUP_PROJECTION, null, null, null);
                        try {
                            // Presumably, this should always succeed, but ...
                            if (c.moveToFirst()) {
                                if (groupFirst) {
                                    s.start(Tags.CONTACTS_CATEGORIES);
                                    groupFirst = false;
                                }
                                s.data(Tags.CONTACTS_CATEGORY, c.getString(0));
                            }
                        } finally {
                            c.close();
                        }
                    }
                    if (!groupFirst) {
                        s.end();
                    }
                }
                s.end().end(); // ApplicationData & Change
                mUpdatedIdList.add(entityValues.getAsLong(RawContacts._ID));
            }
            if (!first) {
                s.end(); // Commands
            }
        } finally {
            ei.close();
        }

        return false;
    }
}
