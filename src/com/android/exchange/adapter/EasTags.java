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

/**
 * The wbxml tags for EAS are all defined here.
 *
 * The static final int's, of the form <page>_<tag> = <constant> are used in parsing incoming
 * responses from the server (i.e. EasParser and its subclasses).
 *
 * The array of String arrays is used to construct server requests with EasSerializer.  One thing
 * we might do eventually is to "precompile" these requests, in part, although they should be
 * fairly fast to begin with (each tag requires one HashMap lookup, and there aren't all that many
 * of them in a given command).
 *
 */
public class EasTags {

    // Wbxml page definitions for EAS
    static final int AIRSYNC = 0x00;
    static final int CONTACTS = 0x01;
    static final int EMAIL = 0x02;
    static final int CALENDAR = 0x04;
    static final int MOVE = 0x05;
    static final int FOLDER = 0x07;
    static final int CONTACTS2 = 0x0C;
    static final int PING = 0x0D;
    static final int GAL = 0x10;
    static final int BASE = 0x11;

    // Shift applied to page numbers to generate tag
    static final int PAGE_SHIFT = 6;
    static final int PAGE_MASK = 0x3F;  // 6 bits

    static final int SYNC_PAGE = 0 << PAGE_SHIFT;
    static final int SYNC_SYNC = SYNC_PAGE + 5;
    static final int SYNC_RESPONSES = SYNC_PAGE + 6;
    static final int SYNC_ADD = SYNC_PAGE + 7;
    static final int SYNC_CHANGE = SYNC_PAGE + 8;
    static final int SYNC_DELETE = SYNC_PAGE + 9;
    static final int SYNC_FETCH = SYNC_PAGE + 0xA;
    static final int SYNC_SYNC_KEY = SYNC_PAGE + 0xB;
    static final int SYNC_CLIENT_ID = SYNC_PAGE + 0xC;
    static final int SYNC_SERVER_ID = SYNC_PAGE + 0xD;
    static final int SYNC_STATUS = SYNC_PAGE + 0xE;
    static final int SYNC_COLLECTION = SYNC_PAGE + 0xF;
    static final int SYNC_CLASS = SYNC_PAGE + 0x10;
    static final int SYNC_VERSION = SYNC_PAGE + 0x11;
    static final int SYNC_COLLECTION_ID = SYNC_PAGE + 0x12;
    static final int SYNC_GET_CHANGES = SYNC_PAGE + 0x13;
    static final int SYNC_MORE_AVAILABLE = SYNC_PAGE + 0x14;
    static final int SYNC_WINDOW_SIZE = SYNC_PAGE + 0x15;
    static final int SYNC_COMMANDS = SYNC_PAGE + 0x16;
    static final int SYNC_OPTIONS = SYNC_PAGE + 0x17;
    static final int SYNC_FILTER_TYPE = SYNC_PAGE + 0x18;
    static final int SYNC_TRUNCATION = SYNC_PAGE + 0x19;
    static final int SYNC_RTF_TRUNCATION = SYNC_PAGE + 0x1A;
    static final int SYNC_CONFLICT = SYNC_PAGE + 0x1B;
    static final int SYNC_COLLECTIONS = SYNC_PAGE + 0x1C;
    static final int SYNC_APPLICATION_DATA = SYNC_PAGE + 0x1D;
    static final int SYNC_DELETES_AS_MOVES = SYNC_PAGE + 0x1E;
    static final int SYNC_NOTIFY_GUID = SYNC_PAGE + 0x1F;
    static final int SYNC_SUPPORTED = SYNC_PAGE + 0x20;
    static final int SYNC_SOFT_DELETE = SYNC_PAGE + 0x21;
    static final int SYNC_MIME_SUPPORT = SYNC_PAGE + 0x22;
    static final int SYNC_MIME_TRUNCATION = SYNC_PAGE + 0x23;
    static final int SYNC_WAIT = SYNC_PAGE + 0x24;
    static final int SYNC_LIMIT = SYNC_PAGE + 0x25;
    static final int SYNC_PARTIAL = SYNC_PAGE + 0x26;

    static final int CONTACTS_PAGE = CONTACTS << PAGE_SHIFT;
    static final int CONTACTS_ANNIVERSARY = CONTACTS_PAGE + 5;
    static final int CONTACTS_ASSISTANT_NAME = CONTACTS_PAGE + 6;
    static final int CONTACTS_ASSISTANT_TELEPHONE_NUMBER = CONTACTS_PAGE + 7;
    static final int CONTACTS_BIRTHDAY = CONTACTS_PAGE + 8;
    static final int CONTACTS_BODY = CONTACTS_PAGE + 9;
    static final int CONTACTS_BODY_SIZE = CONTACTS_PAGE + 0xA;
    static final int CONTACTS_BODY_TRUNCATED = CONTACTS_PAGE + 0xB;
    static final int CONTACTS_BUSINESS2_TELEPHONE_NUMBER = CONTACTS_PAGE + 0xC;
    static final int CONTACTS_BUSINESS_ADDRESS_CITY = CONTACTS_PAGE + 0xD;
    static final int CONTACTS_BUSINESS_ADDRESS_COUNTRY = CONTACTS_PAGE + 0xE;
    static final int CONTACTS_BUSINESS_ADDRESS_POSTAL_CODE = CONTACTS_PAGE + 0xF;
    static final int CONTACTS_BUSINESS_ADDRESS_STATE = CONTACTS_PAGE + 0x10;
    static final int CONTACTS_BUSINESS_ADDRESS_STREET = CONTACTS_PAGE + 0x11;
    static final int CONTACTS_BUSINESS_FAX_NUMBER = CONTACTS_PAGE + 0x12;
    static final int CONTACTS_BUSINESS_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x13;
    static final int CONTACTS_CAR_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x14;
    static final int CONTACTS_CATEGORIES = CONTACTS_PAGE + 0x15;
    static final int CONTACTS_CATEGORY = CONTACTS_PAGE + 0x16;
    static final int CONTACTS_CHILDREN = CONTACTS_PAGE + 0x17;
    static final int CONTACTS_CHILD = CONTACTS_PAGE + 0x18;
    static final int CONTACTS_COMPANY_NAME = CONTACTS_PAGE + 0x19;
    static final int CONTACTS_DEPARTMENT = 0x1A;
    static final int CONTACTS_EMAIL1_ADDRESS = CONTACTS_PAGE + 0x1B;
    static final int CONTACTS_EMAIL2_ADDRESS = CONTACTS_PAGE + 0x1C;
    static final int CONTACTS_EMAIL3_ADDRESS = CONTACTS_PAGE + 0x1D;
    static final int CONTACTS_FILE_AS = CONTACTS_PAGE + 0x1E;
    static final int CONTACTS_FIRST_NAME = CONTACTS_PAGE + 0x1F;
    static final int CONTACTS_HOME2_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x20;
    static final int CONTACTS_HOME_ADDRESS_CITY = CONTACTS_PAGE + 0x21;
    static final int CONTACTS_HOME_ADDRESS_COUNTRY = CONTACTS_PAGE + 0x22;
    static final int CONTACTS_HOME_ADDRESS_POSTAL_CODE = CONTACTS_PAGE + 0x23;
    static final int CONTACTS_HOME_ADDRESS_STATE = CONTACTS_PAGE + 0x24;
    static final int CONTACTS_HOME_ADDRESS_STREET = CONTACTS_PAGE + 0x25;
    static final int CONTACTS_HOME_FAX_NUMBER = CONTACTS_PAGE + 0x26;
    static final int CONTACTS_HOME_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x27;
    static final int CONTACTS_JOB_TITLE = CONTACTS_PAGE + 0x28;
    static final int CONTACTS_LAST_NAME = CONTACTS_PAGE + 0x29;
    static final int CONTACTS_MIDDLE_NAME = CONTACTS_PAGE + 0x2A;
    static final int CONTACTS_MOBILE_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x2B;
    static final int CONTACTS_OFFICE_LOCATION = CONTACTS_PAGE + 0x2C;
    static final int CONTACTS_OTHER_ADDRESS_CITY = CONTACTS_PAGE + 0x2D;
    static final int CONTACTS_OTHER_ADDRESS_COUNTRY = CONTACTS_PAGE + 0x2E;
    static final int CONTACTS_OTHER_ADDRESS_POSTAL_CODE = CONTACTS_PAGE + 0x2F;
    static final int CONTACTS_OTHER_ADDRESS_STATE = CONTACTS_PAGE + 0x30;
    static final int CONTACTS_OTHER_ADDRESS_STREET = CONTACTS_PAGE + 0x31;
    static final int CONTACTS_PAGER_NUMBER = CONTACTS_PAGE + 0x32;
    static final int CONTACTS_RADIO_TELEPHONE_NUMBER = CONTACTS_PAGE + 0x33;
    static final int CONTACTS_SPOUSE = CONTACTS_PAGE + 0x34;
    static final int CONTACTS_SUFFIX = CONTACTS_PAGE + 0x35;
    static final int CONTACTS_TITLE = CONTACTS_PAGE + 0x36;
    static final int CONTACTS_WEBPAGE = CONTACTS_PAGE + 0x37;
    static final int CONTACTS_YOMI_COMPANY_NAME = CONTACTS_PAGE + 0x38;
    static final int CONTACTS_YOMI_FIRST_NAME = CONTACTS_PAGE + 0x39;
    static final int CONTACTS_YOMI_LAST_NAME = CONTACTS_PAGE + 0x3A;
    static final int CONTACTS_COMPRESSED_RTF = CONTACTS_PAGE + 0x3B;
    static final int CONTACTS_PICTURE = CONTACTS_PAGE + 0x3C;

    static final int CALENDAR_PAGE = CALENDAR << PAGE_SHIFT;
    static final int CALENDAR_TIME_ZONE = CALENDAR_PAGE + 5;
    static final int CALENDAR_ALL_DAY_EVENT = CALENDAR_PAGE + 6;
    static final int CALENDAR_ATTENDEES = CALENDAR_PAGE + 7;
    static final int CALENDAR_ATTENDEE = CALENDAR_PAGE + 8;
    static final int CALENDAR_ATTENDEE_EMAIL = CALENDAR_PAGE + 9;
    static final int CALENDAR_ATTENDEE_NAME = CALENDAR_PAGE + 0xA;
    static final int CALENDAR_BODY = CALENDAR_PAGE + 0xB;
    static final int CALENDAR_BODY_TRUNCATED = CALENDAR_PAGE + 0xC;
    static final int CALENDAR_BUSY_STATUS = CALENDAR_PAGE + 0xD;
    static final int CALENDAR_CATEGORIES = CALENDAR_PAGE + 0xE;
    static final int CALENDAR_CATEGORY = CALENDAR_PAGE + 0xF;
    static final int CALENDAR_COMPRESSED_RTF = CALENDAR_PAGE + 0x10;
    static final int CALENDAR_DTSTAMP = CALENDAR_PAGE + 0x11;
    static final int CALENDAR_END_TIME = CALENDAR_PAGE + 0x12;
    static final int CALENDAR_EXCEPTION = CALENDAR_PAGE + 0x13;
    static final int CALENDAR_EXCEPTIONS = CALENDAR_PAGE + 0x14;
    static final int CALENDAR_EXCEPTION_IS_DELETED = CALENDAR_PAGE + 0x15;
    static final int CALENDAR_EXCEPTION_START_TIME = CALENDAR_PAGE + 0x16;
    static final int CALENDAR_LOCATION = CALENDAR_PAGE + 0x17;
    static final int CALENDAR_MEETING_STATUS = CALENDAR_PAGE + 0x18;
    static final int CALENDAR_ORGANIZER_EMAIL = CALENDAR_PAGE + 0x19;
    static final int CALENDAR_ORGANIZER_NAME = CALENDAR_PAGE + 0x1A;
    static final int CALENDAR_RECURRENCE = CALENDAR_PAGE + 0x1B;
    static final int CALENDAR_RECURRENCE_TYPE = CALENDAR_PAGE + 0x1C;
    static final int CALENDAR_RECURRENCE_UNTIL = CALENDAR_PAGE + 0x1D;
    static final int CALENDAR_RECURRENCE_OCCURRENCES = CALENDAR_PAGE + 0x1E;
    static final int CALENDAR_RECURRENCE_INTERVAL = CALENDAR_PAGE + 0x1F;
    static final int CALENDAR_RECURRENCE_DAYOFWEEK = CALENDAR_PAGE + 0x20;
    static final int CALENDAR_RECURRENCE_DAYOFMONTH = CALENDAR_PAGE + 0x21;
    static final int CALENDAR_RECURRENCE_WEEKOFMONTH = CALENDAR_PAGE + 0x22;
    static final int CALENDAR_RECURRENCE_MONTHOFYEAR = CALENDAR_PAGE + 0x23;
    static final int CALENDAR_REMINDER_MINS_BEFORE = CALENDAR_PAGE + 0x24;
    static final int CALENDAR_SENSITIVITY = CALENDAR_PAGE + 0x25;
    static final int CALENDAR_SUBJECT = CALENDAR_PAGE + 0x26;
    static final int CALENDAR_START_TIME = CALENDAR_PAGE + 0x27;
    static final int CALENDAR_UID = CALENDAR_PAGE + 0x28;
    static final int CALENDAR_ATTENDEE_STATUS = CALENDAR_PAGE + 0x29;
    static final int CALENDAR_ATTENDEE_TYPE = CALENDAR_PAGE + 0x2A;

    static final int FOLDER_PAGE = FOLDER << PAGE_SHIFT;
    static final int FOLDER_FOLDERS = FOLDER_PAGE + 5;
    static final int FOLDER_FOLDER = FOLDER_PAGE + 6;
    static final int FOLDER_DISPLAY_NAME = FOLDER_PAGE + 7;
    static final int FOLDER_SERVER_ID = FOLDER_PAGE + 8;
    static final int FOLDER_PARENT_ID = FOLDER_PAGE + 9;
    static final int FOLDER_TYPE = FOLDER_PAGE + 0xA;
    static final int FOLDER_RESPONSE = FOLDER_PAGE + 0xB;
    static final int FOLDER_STATUS = FOLDER_PAGE + 0xC;
    static final int FOLDER_CONTENT_CLASS = FOLDER_PAGE + 0xD;
    static final int FOLDER_CHANGES = FOLDER_PAGE + 0xE;
    static final int FOLDER_ADD = FOLDER_PAGE + 0xF;
    static final int FOLDER_DELETE = FOLDER_PAGE + 0x10;
    static final int FOLDER_UPDATE = FOLDER_PAGE + 0x11;
    static final int FOLDER_SYNC_KEY = FOLDER_PAGE + 0x12;
    static final int FOLDER_FOLDER_CREATE = FOLDER_PAGE + 0x13;
    static final int FOLDER_FOLDER_DELETE= FOLDER_PAGE + 0x14;
    static final int FOLDER_FOLDER_UPDATE = FOLDER_PAGE + 0x15;
    static final int FOLDER_FOLDER_SYNC = FOLDER_PAGE + 0x16;
    static final int FOLDER_COUNT = FOLDER_PAGE + 0x17;
    static final int FOLDER_VERSION = FOLDER_PAGE + 0x18;

    static final int EMAIL_PAGE = EMAIL << PAGE_SHIFT;
    static final int EMAIL_ATTACHMENT = EMAIL_PAGE + 5;
    static final int EMAIL_ATTACHMENTS = EMAIL_PAGE + 6;
    static final int EMAIL_ATT_NAME = EMAIL_PAGE + 7;
    static final int EMAIL_ATT_SIZE = EMAIL_PAGE + 8;
    static final int EMAIL_ATT0ID = EMAIL_PAGE + 9;
    static final int EMAIL_ATT_METHOD = EMAIL_PAGE + 0xA;
    static final int EMAIL_ATT_REMOVED = EMAIL_PAGE + 0xB;
    static final int EMAIL_BODY = EMAIL_PAGE + 0xC;
    static final int EMAIL_BODY_SIZE = EMAIL_PAGE + 0xD;
    static final int EMAIL_BODY_TRUNCATED = EMAIL_PAGE + 0xE;
    static final int EMAIL_DATE_RECEIVED = EMAIL_PAGE + 0xF;
    static final int EMAIL_DISPLAY_NAME = EMAIL_PAGE + 0x10;
    static final int EMAIL_DISPLAY_TO = EMAIL_PAGE + 0x11;
    static final int EMAIL_IMPORTANCE = EMAIL_PAGE + 0x12;
    static final int EMAIL_MESSAGE_CLASS = EMAIL_PAGE + 0x13;
    static final int EMAIL_SUBJECT = EMAIL_PAGE + 0x14;
    static final int EMAIL_READ = EMAIL_PAGE + 0x15;
    static final int EMAIL_TO = EMAIL_PAGE + 0x16;
    static final int EMAIL_CC = EMAIL_PAGE + 0x17;
    static final int EMAIL_FROM = EMAIL_PAGE + 0x18;
    static final int EMAIL_REPLY_TO = EMAIL_PAGE + 0x19;
    static final int EMAIL_ALL_DAY_EVENT = EMAIL_PAGE + 0x1A;
    static final int EMAIL_CATEGORIES = EMAIL_PAGE + 0x1B;
    static final int EMAIL_CATEGORY = EMAIL_PAGE + 0x1C;
    static final int EMAIL_DTSTAMP = EMAIL_PAGE + 0x1D;
    static final int EMAIL_END_TIME = EMAIL_PAGE + 0x1E;
    static final int EMAIL_INSTANCE_TYPE = EMAIL_PAGE + 0x1F;
    static final int EMAIL_INTD_BUSY_STATUS = EMAIL_PAGE + 0x20;
    static final int EMAIL_LOCATION = EMAIL_PAGE + 0x21;
    static final int EMAIL_MEETING_REQUEST = EMAIL_PAGE + 0x22;
    static final int EMAIL_ORGANIZER = EMAIL_PAGE + 0x23;
    static final int EMAIL_RECURRENCE_ID = EMAIL_PAGE + 0x24;
    static final int EMAIL_REMINDER = EMAIL_PAGE + 0x25;
    static final int EMAIL_RESPONSE_REQUESTED = EMAIL_PAGE + 0x26;
    static final int EMAIL_RECURRENCES = EMAIL_PAGE + 0x27;
    static final int EMAIL_RECURRENCE = EMAIL_PAGE + 0x28;
    static final int EMAIL_RECURRENCE_TYPE = EMAIL_PAGE + 0x29;
    static final int EMAIL_RECURRENCE_UNTIL = EMAIL_PAGE + 0x2A;
    static final int EMAIL_RECURRENCE_OCCURRENCES = EMAIL_PAGE + 0x2B;
    static final int EMAIL_RECURRENCE_INTERVAL = EMAIL_PAGE + 0x2C;
    static final int EMAIL_RECURRENCE_DAYOFWEEK = EMAIL_PAGE + 0x2D;
    static final int EMAIL_RECURRENCE_DAYOFMONTH = EMAIL_PAGE + 0x2E;
    static final int EMAIL_RECURRENCE_WEEKOFMONTH = EMAIL_PAGE + 0x2F;
    static final int EMAIL_RECURRENCE_MONTHOFYEAR = EMAIL_PAGE + 0x30;
    static final int EMAIL_START_TIME = EMAIL_PAGE + 0x31;
    static final int EMAIL_SENSITIVITY = EMAIL_PAGE + 0x32;
    static final int EMAIL_TIME_ZONE = EMAIL_PAGE + 0x33;
    static final int EMAIL_GLOBAL_OBJID = EMAIL_PAGE + 0x34;
    static final int EMAIL_THREAD_TOPIC = EMAIL_PAGE + 0x35;
    static final int EMAIL_MIME_DATA = EMAIL_PAGE + 0x36;
    static final int EMAIL_MIME_TRUNCATED = EMAIL_PAGE + 0x37;
    static final int EMAIL_MIME_SIZE = EMAIL_PAGE + 0x38;
    static final int EMAIL_INTERNET_CPID = EMAIL_PAGE + 0x39;
    static final int EMAIL_FLAG = EMAIL_PAGE + 0x3A;
    static final int EMAIL_FLAG_STATUS = EMAIL_PAGE + 0x3B;
    static final int EMAIL_CONTENT_CLASS = EMAIL_PAGE + 0x3C;
    static final int EMAIL_FLAG_TYPE = EMAIL_PAGE + 0x3D;
    static final int EMAIL_COMPLETE_TIME = EMAIL_PAGE + 0x3E;

    static final int MOVE_PAGE = MOVE << PAGE_SHIFT;
    static final int MOVE_MOVE_ITEMS = MOVE_PAGE + 5;
    static final int MOVE_MOVE = MOVE_PAGE + 6;
    static final int MOVE_SRCMSGID = MOVE_PAGE + 7;
    static final int MOVE_SRCFLDID = MOVE_PAGE + 8;
    static final int MOVE_DSTFLDID = MOVE_PAGE + 9;
    static final int MOVE_RESPONSE = MOVE_PAGE + 0xA;
    static final int MOVE_STATUS = MOVE_PAGE + 0xB;
    static final int MOVE_DSTMSGID = MOVE_PAGE + 0xC;

    static final int CONTACTS2_PAGE = CONTACTS2 << PAGE_SHIFT;
    static final int CONTACTS2_CUSTOMER_ID = CONTACTS2_PAGE + 5;
    static final int CONTACTS2_GOVERNMENT_ID = CONTACTS2_PAGE + 6;
    static final int CONTACTS2_IM_ADDRESS = CONTACTS2_PAGE + 7;
    static final int CONTACTS2_IM_ADDRESS_2 = CONTACTS2_PAGE + 8;
    static final int CONTACTS2_IM_ADDRESS_3 = CONTACTS2_PAGE + 9;
    static final int CONTACTS2_MANAGER_NAME = CONTACTS2_PAGE + 0xA;
    static final int CONTACTS2_COMPANY_MAIN_PHONE = CONTACTS2_PAGE + 0xB;
    static final int CONTACTS2_ACCOUNT_NAME = CONTACTS2_PAGE + 0xC;
    static final int CONTACTS2_NICKNAME = CONTACTS2_PAGE + 0xD;
    static final int CONTACTS2_MMS = CONTACTS2_PAGE + 0xE;

    // The Ping constants are used by EasSyncService, and need to be public
    static final int PING_PAGE = PING << PAGE_SHIFT;
    public static final int PING_PING = PING_PAGE + 5;
    public static final int PING_AUTD_STATE = PING_PAGE + 6;
    public static final int PING_STATUS = PING_PAGE + 7;
    public static final int PING_HEARTBEAT_INTERVAL = PING_PAGE + 8;
    public static final int PING_FOLDERS = PING_PAGE + 9;
    public static final int PING_FOLDER = PING_PAGE + 0xA;
    public static final int PING_ID = PING_PAGE + 0xB;
    public static final int PING_CLASS = PING_PAGE + 0xC;
    public static final int PING_MAX_FOLDERS = PING_PAGE + 0xD;

    static final int BASE_PAGE = BASE << PAGE_SHIFT;
    static final int BASE_BODY_PREFERENCE = BASE_PAGE + 5;
    static final int BASE_TYPE = BASE_PAGE + 6;
    static final int BASE_TRUNCATION_SIZE = BASE_PAGE + 7;
    static final int BASE_ALL_OR_NONE = BASE_PAGE + 8;
    static final int BASE_RESERVED = BASE_PAGE + 9;
    static final int BASE_BODY = BASE_PAGE + 0xA;
    static final int BASE_DATA = BASE_PAGE + 0xB;
    static final int BASE_ESTIMATED_DATA_SIZE = BASE_PAGE + 0xC;
    static final int BASE_TRUNCATED = BASE_PAGE + 0xD;
    static final int BASE_ATTACHMENTS = BASE_PAGE + 0xE;
    static final int BASE_ATTACHMENT = BASE_PAGE + 0xF;
    static final int BASE_DISPLAY_NAME = BASE_PAGE + 0x10;
    static final int BASE_FILE_REFERENCE = BASE_PAGE + 0x11;
    static final int BASE_METHOD = BASE_PAGE + 0x12;
    static final int BASE_CONTENT_ID = BASE_PAGE + 0x13;
    static final int BASE_CONTENT_LOCATION = BASE_PAGE + 0x14;
    static final int BASE_IS_INLINE = BASE_PAGE + 0x15;
    static final int BASE_NATIVE_BODY_TYPE = BASE_PAGE + 0x16;
    static final int BASE_CONTENT_TYPE = BASE_PAGE + 0x17;

    static public String[][] pages = {
        {    // 0x00 AirSync
            "Sync", "Responses", "Add", "Change", "Delete", "Fetch", "SyncKey", "ClientId",
            "ServerId", "Status", "Collection", "Class", "Version", "CollectionId", "GetChanges",
            "MoreAvailable", "WindowSize", "Commands", "Options", "FilterType", "Truncation",
            "RTFTruncation", "Conflict", "Collections", "ApplicationData", "DeletesAsMoves",
            "NotifyGUID", "Supported", "SoftDelete", "MIMESupport", "MIMETruncation", "Wait",
            "Limit", "Partial"
        },
        {
            // 0x01 Contacts
            "Anniversary", "AssistantName", "AssistantTelephoneNumber", "Birthday", "Body",
            "BodySize", "BodyTruncated", "Business2TelephoneNumber", "BusinessAddressCity",
            "BusinessAddressCountry", "BusinessAddressPostalCode", "BusinessAddressState",
            "BusinessAddressStreet", "BusinessFaxNumber", "BusinessTelephoneNumber",
            "CarTelephoneNumber", "ContactsCategories", "ContactsCategory", "Children", "Child",
            "CompanyName", "Department", "Email1Address", "Email2Address", "Email3Address",
            "FileAs", "FirstName", "Home2TelephoneNumber", "HomeAddressCity", "HomeAddressCountry",
            "HomeAddressPostalCode", "HomeAddressState", "HomeAddressStreet", "HomeFaxNumber",
            "HomeTelephoneNumber", "JobTitle", "LastName", "MiddleName", "MobileTelephoneNumber",
            "OfficeLocation", "OfficeAddressCity", "OfficeAddressCountry",
            "OfficeAddressPostalCode", "OfficeAddressState", "OfficeAddressStreet", "PagerNumber",
            "RadioTelephoneNumber", "Spouse", "Suffix", "Title", "Webpage", "YomiCompanyName",
            "YomiFirstName", "YomiLastName", "CompressedRTF", "Picture"
        },
        {
            // 0x02 Email
            "Attachment", "Attachments", "AttName", "AttSize", "Add0Id", "AttMethod", "AttRemoved",
            "Body", "BodySize", "BodyTruncated", "DateReceived", "DisplayName", "DisplayTo",
            "Importance", "MessageClass", "Subject", "Read", "To", "CC", "From", "ReplyTo",
            "AllDayEvent", "Categories", "Category", "DTStamp", "EndTime", "InstanceType",
            "IntDBusyStatus", "Location", "MeetingRequest", "Organizer", "RecurrenceId", "Reminder",
            "ResponseRequested", "Recurrences", "Recurence", "Recurrence_Type", "Recurrence_Until",
            "Recurrence_Occurrences", "Recurrence_Interval", "Recurrence_DayOfWeek",
            "Recurrence_DayOfMonth", "Recurrence_WeekOfMonth", "Recurrence_MonthOfYear",
            "StartTime", "Sensitivity", "TimeZone", "GlobalObjId", "ThreadTopic", "MIMEData",
            "MIMETruncated", "MIMESize", "InternetCPID", "Flag", "FlagStatus", "ContentClass",
            "FlagType", "CompleteTime"
        },
        {
            // 0x03 AirNotify
        },
        {
            // 0x04 Calendar
            "CalTimeZone", "CalAllDayEvent", "CalAttendees", "CalAttendee", "CalAttendee_Email",
            "CalAttendee_Name", "CalBody", "CalBodyTruncated", "CalBusyStatus", "CalCategories",
            "CalCategory", "CalCompressed_RTF", "CalDTStamp", "CalEndTime", "CalExeption",
            "CalExceptions", "CalException_IsDeleted", "CalException_StartTime", "CalLocation",
            "CalMeetingStatus", "CalOrganizer_Email", "CalOrganizer_Name", "CalRecurrence",
            "CalRecurrence_Type", "CalRecurrence_Until", "CalRecurrence_Occurrences",
            "CalRecurrence_Interval", "CalRecurrence_DayOfWeek", "CalRecurrence_DayOfMonth",
            "CalRecurrence_WeekOfMonth", "CalRecurrence_MonthOfYear", "CalReminder_MinsBefore",
            "CalSensitivity", "CalSubject", "CalStartTime", "CalUID", "CalAttendee_Status",
            "CalAttendee_Type"
        },
        {
            // 0x05 Move
            "MoveItems", "Move", "SrcMsgId", "SrcFldId", "DstFldId", "Response", "Status",
            "DstMsgId"
        },
        {
            // 0x06 ItemEstimate
        },
        {
            // 0x07 FolderHierarchy
            "Folders", "Folder", "FolderDisplayName", "FolderServerId", "FolderParentId", "Type",
            "Response", "Status", "ContentClass", "Changes", "FolderAdd", "FolderDelete",
            "FolderUpdate", "FolderSyncKey", "FolderCreate", "FolderDelete", "FolderUpdate",
            "FolderSync", "Count", "Version"
        },
        {
            // 0x08 MeetingResponse
        },
        {
            // 0x09 Tasks
        },
        {
            // 0x0A ResolveRecipients
        },
        {
            // 0x0B ValidateCert
        },
        {
            // 0x0C Contacts2
            "CustomerId", "GovernmentId", "IMAddress", "IMAddress2", "IMAddress3", "ManagerName",
            "CompanyMainPhone", "AccountName", "NickName", "MMS"
        },
        {
            // 0x0D Ping
            "Ping", "AutdState", "Status", "HeartbeatInterval", "PingFolders", "PingFolder",
            "PingId", "PingClass", "MaxFolders"
        },
        {
            // 0x0E Provision
            "Provision", "Policies", "Policy", "PolicyType", "PolicyKey", "Data", "Status",
            "RemoteWipe", "EASProvidionDoc", "DevicePasswordEnabled",
            "AlphanumericDevicePasswordRequired",
            "DeviceEncryptionEnabled", "-unused-", "AttachmentsEnabled", "MinDevicePasswordLength",
            "MaxInactivityTimeDeviceLock", "MaxDevicePasswordFailedAttempts", "MaxAttachmentSize",
            "AllowSimpleDevicePassword", "DevicePasswordExpiration", "DevicePasswordHistory",
            "AllowStorageCard", "AllowCamera", "RequireDeviceEncryption",
            "AllowUnsignedApplications", "AllowUnsignedInstallationPackages",
            "MinDevicePasswordComplexCharacters", "AllowWiFi", "AllowTextMessaging",
            "AllowPOPIMAPEmail", "AllowBluetooth", "AllowIrDA", "RequireManualSyncWhenRoaming",
            "AllowDesktopSync",
            "MaxCalendarAgeFilder", "AllowHTMLEmail", "MaxEmailAgeFilder",
            "MaxEmailBodyTruncationSize", "MaxEmailHTMLBodyTruncationSize",
            "RequireSignedSMIMEMessages", "RequireEncryptedSMIMEMessages",
            "RequireSignedSMIMEAlgorithm", "RequireEncryptionSMIMEAlgorithm",
            "AllowSMIMEEncryptionAlgorithmNegotiation", "AllowSMIMESoftCerts", "AllowBrowser",
            "AllowConsumerEmail", "AllowRemoteDesktop", "AllowInternetSharing",
            "UnapprovedInROMApplicationList", "ApplicationName", "ApprovedApplicationList", "Hash"
        },
        {
            // 0x0F Search
        },
        {
            // 0x10 Gal
            "DisplayName", "Phone", "Office", "Title", "Company", "Alias", "FirstName", "LastName",
            "HomePhone", "MobilePhone", "EmailAddress"
        },
        {
            // 0x11 AirSyncBase
            "BodyPreference", "BodyPreferenceType", "BodyPreferenceTruncationSize", "AllOrNone",
            "Body", "Data", "EstimatedDataSize", "Truncated", "Attachments", "Attachment",
            "DisplayName", "FileReference", "Method", "ContentId", "ContentLocation", "IsInline",
            "NativeBodyType", "ContentType"
        },
        {
            // 0x12 Settings
        },
        {
            // 0x13 DocumentLibrary
        },
        {
            // 0x14 ItemOperations
        }
    };
}
