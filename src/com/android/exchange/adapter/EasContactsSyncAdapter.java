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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.Contacts.People;

import com.android.exchange.EasSyncService;
import com.android.exchange.EmailContent.Mailbox;

/**
 * Sync adapter for EAS Contacts
 *
 */
public class EasContactsSyncAdapter extends EasSyncAdapter {

    private static final String WHERE_SERVER_ID_AND_ACCOUNT = "_sync_id=?";

    ArrayList<Long> mDeletedIdList = new ArrayList<Long>();

    ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    public EasContactsSyncAdapter(Mailbox mailbox) {
        super(mailbox);
    }

    @Override
    public boolean parse(ByteArrayInputStream is, EasSyncService service) throws IOException {
        EasContactsSyncParser p = new EasContactsSyncParser(is, service);
        return p.parse();
    }

    class EasContactsSyncParser extends EasContentParser {

        String[] mBindArgument = new String[1];

        String mMailboxIdAsString;

        StringBuilder mExtraData = new StringBuilder(1024);

        public EasContactsSyncParser(InputStream in, EasSyncService service) throws IOException {
            super(in, service);
            //setDebug(true); // DON'T CHECK IN WITH THIS UNCOMMENTED
        }

        class ContactMethod {
            ContentValues values = new ContentValues();

            ContactMethod(int kind, int type, String value) {
                values.put(Contacts.ContactMethods.KIND, kind);
                values.put(Contacts.ContactMethods.TYPE, type);
                values.put(Contacts.ContactMethods.DATA, value);
            }
        }

        class Phone {
            ContentValues values = new ContentValues();

            Phone(int type, String value) {
                values.put(Contacts.Phones.TYPE, type);
                values.put(Contacts.Phones.NUMBER, value);
            }
        }

        @Override
        public void wipe() {
            // TODO Auto-generated method stub
        }

        void saveExtraData (int tag) throws IOException {
            mExtraData.append(name);
            mExtraData.append("~");
            mExtraData.append(getValue());
            mExtraData.append('~');
        }

        public void addData(String serverId, ArrayList<ContentProviderOperation> ops) 
                throws IOException {
            String firstName = null;
            String lastName = null;
            String companyName = null;
            ArrayList<ContactMethod> contactMethods = new ArrayList<ContactMethod>();
            ArrayList<Phone> phones = new ArrayList<Phone>();
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
                    case EasTags.CONTACTS_EMAIL1_ADDRESS:
                    case EasTags.CONTACTS_EMAIL2_ADDRESS:
                    case EasTags.CONTACTS_EMAIL3_ADDRESS:
                        contactMethods.add(new ContactMethod(Contacts.KIND_EMAIL, 
                                Contacts.ContactMethods.TYPE_OTHER, getValue()));
                        break;
                    case EasTags.CONTACTS_BUSINESS2_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_BUSINESS_TELEPHONE_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_WORK, getValue()));
                        break;
                    case EasTags.CONTACTS_BUSINESS_FAX_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_FAX_WORK, getValue()));
                        break;
                    case EasTags.CONTACTS_HOME_FAX_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_FAX_HOME, getValue()));
                        break;
                    case EasTags.CONTACTS_HOME_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_HOME2_TELEPHONE_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_HOME, getValue()));
                        break;
                    case EasTags.CONTACTS_MOBILE_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_CAR_TELEPHONE_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_MOBILE, getValue()));
                        break;
                    case EasTags.CONTACTS_PAGER_NUMBER:
                        phones.add(new Phone(Contacts.Phones.TYPE_PAGER, getValue()));
                        break;
                    // All tags that we don't use (except for a few like picture and body) need to
                    // be saved, even if we're not using them.  Otherwise, when we upload changes,
                    // those items will be deleted back on the server.
                    case EasTags.CONTACTS_ANNIVERSARY:
                    case EasTags.CONTACTS_ASSISTANT_NAME:
                    case EasTags.CONTACTS_ASSISTANT_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_BIRTHDAY:
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_CITY:
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_COUNTRY:
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE:
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_STATE:
                    case EasTags.CONTACTS_BUSINESS_ADDRESS_STREET:
                    case EasTags.CONTACTS_CATEGORIES:
                    case EasTags.CONTACTS_CATEGORY:
                    case EasTags.CONTACTS_CHILDREN:
                    case EasTags.CONTACTS_CHILD:
                    case EasTags.CONTACTS_DEPARTMENT:
                    case EasTags.CONTACTS_FILE_AS:
                    case EasTags.CONTACTS_HOME_ADDRESS_CITY:
                    case EasTags.CONTACTS_HOME_ADDRESS_COUNTRY:
                    case EasTags.CONTACTS_HOME_ADDRESS_POSTAL_CODE:
                    case EasTags.CONTACTS_HOME_ADDRESS_STATE:
                    case EasTags.CONTACTS_HOME_ADDRESS_STREET:
                    case EasTags.CONTACTS_JOB_TITLE:
                    case EasTags.CONTACTS_MIDDLE_NAME:
                    case EasTags.CONTACTS_OFFICE_LOCATION:
                    case EasTags.CONTACTS_OTHER_ADDRESS_CITY:
                    case EasTags.CONTACTS_OTHER_ADDRESS_COUNTRY:
                    case EasTags.CONTACTS_OTHER_ADDRESS_POSTAL_CODE:
                    case EasTags.CONTACTS_OTHER_ADDRESS_STATE:
                    case EasTags.CONTACTS_OTHER_ADDRESS_STREET:
                    case EasTags.CONTACTS_RADIO_TELEPHONE_NUMBER:
                    case EasTags.CONTACTS_SPOUSE:
                    case EasTags.CONTACTS_SUFFIX:
                    case EasTags.CONTACTS_TITLE:
                    case EasTags.CONTACTS_WEBPAGE:
                    case EasTags.CONTACTS_YOMI_COMPANY_NAME:
                    case EasTags.CONTACTS_YOMI_FIRST_NAME:
                    case EasTags.CONTACTS_YOMI_LAST_NAME:
                    case EasTags.CONTACTS_COMPRESSED_RTF:
                    //case EasTags.CONTACTS_PICTURE:
                    case EasTags.CONTACTS2_CUSTOMER_ID:
                    case EasTags.CONTACTS2_GOVERNMENT_ID:
                    case EasTags.CONTACTS2_IM_ADDRESS:
                    case EasTags.CONTACTS2_IM_ADDRESS_2:
                    case EasTags.CONTACTS2_IM_ADDRESS_3:
                    case EasTags.CONTACTS2_MANAGER_NAME:
                    case EasTags.CONTACTS2_COMPANY_MAIN_PHONE:
                    case EasTags.CONTACTS2_ACCOUNT_NAME:
                    case EasTags.CONTACTS2_NICKNAME:
                    case EasTags.CONTACTS2_MMS:
                        saveExtraData(tag);
                        break;
                    default:
                        skipTag();
                }
            }

            // Ok, ready to create our contact...
            // First pass, no batch...  Eventually, move to changesParser
            ContentValues values = new ContentValues();

            // TODO Do something with the extras (i.e. find a home for them)
            String extraData = mExtraData.toString();
            mService.userLog(extraData);

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

            values.put(Contacts.People.NAME, name);
            values.put("_sync_id", serverId);
            // TODO Use proper value here; need to ask jham
            //values.put("_sync_account", "EAS");
            Uri contactUri = 
                Contacts.People.createPersonInMyContactsGroup(mContentResolver, values);

            Uri contactMethodsUri = Uri.withAppendedPath(contactUri,
                    Contacts.People.ContactMethods.CONTENT_DIRECTORY);
            for (ContactMethod cm: contactMethods) {
                mContentResolver.insert(contactMethodsUri, cm.values);
                //ops.add(ContentProviderOperation
                //        .newInsert(contactMethodsUri).withValues(cm.values).build());
            }

            Uri phoneUri = Uri.withAppendedPath(contactUri, People.Phones.CONTENT_DIRECTORY);
            for (Phone phone: phones) {
                mContentResolver.insert(phoneUri, phone.values);
                //ops.add(ContentProviderOperation
                //        .newInsert(phoneUri).withValues(phone.values).build());
            }
        }

        public void addParser(ArrayList<ContentProviderOperation> ops) throws IOException {
            String serverId = null;
            while (nextTag(EasTags.SYNC_ADD) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID: // same as
                        serverId = getValue();
                        break;
                    case EasTags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private Cursor getServerIdCursor(String serverId) {
            mBindArgument[0] = serverId;
            //bindArguments[1] = "EAS";
            // TODO Find proper constant for _id
            return mContentResolver.query(Contacts.People.CONTENT_URI, new String[] {"_id"},
                    WHERE_SERVER_ID_AND_ACCOUNT, mBindArgument, null);
        }

        public void deleteParser(ArrayList<ContentProviderOperation> ops) throws IOException {
            while (nextTag(EasTags.SYNC_DELETE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the message in this mailbox with the given serverId
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                mService.userLog("Deleting " + serverId);
                                mContentResolver.delete(ContentUris
                                        .withAppendedId(Contacts.People.CONTENT_URI, c.getLong(0)),
                                        null, null);
                                //ops.add(ContentProviderOperation.newDelete(
                                //        ContentUris.withAppendedId(Contacts.People.CONTENT_URI, 
                                //                c.getLong(0))).build());
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
         * A change operation on a contact is implemented as a delete followed by an add, since the
         * change data is always a full contact.
         * 
         * @param ops the array of pending ContactProviderOperations.
         * @throws IOException
         */
        public void changeParser(ArrayList<ContentProviderOperation> ops) throws IOException {
            String serverId = null;
            while (nextTag(EasTags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case EasTags.SYNC_SERVER_ID:
                        serverId = getValue();
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                mContentResolver.delete(ContentUris
                                        .withAppendedId(Contacts.People.CONTENT_URI, c.getLong(0)),
                                        null, null);
                                //ops.add(ContentProviderOperation.newDelete(
                                //        ContentUris.withAppendedId(Contacts.People.CONTENT_URI, 
                                //                c.getLong(0))).build());
                               mService.userLog("Changing " + serverId);
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    case EasTags.SYNC_APPLICATION_DATA:
                        addData(serverId, ops);
                    default:
                        skipTag();
                }
            }
        }

        public void commandsParser() throws IOException {
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
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

            // Batch provider operations here
//            try {
//                mService.mContext.getContentResolver()
//                        .applyBatch(ContactsProvider.EMAIL_AUTHORITY, ops);
//            } catch (RemoteException e) {
//                // There is nothing to be done here; fail by returning null
//            } catch (OperationApplicationException e) {
//                // There is nothing to be done here; fail by returning null
//            }

            mService.userLog("Contacts SyncKey confirmed as: " + mMailbox.mSyncKey);
        }
    }

    @Override
    public void cleanup(EasSyncService service) {
    }

    @Override
    public String getCollectionName() {
        return "Contacts";
    }

    @Override
    public boolean sendLocalChanges(EasSerializer s, EasSyncService service) throws IOException {
        return false;
    }
}
