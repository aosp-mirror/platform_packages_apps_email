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

package com.android.exchange;

public class EasTags {

    static final int AIRSYNC = 0x00;
    static final int CONTACTS = 0x01;
    static final int EMAIL = 0x02;
    static final int FOLDER = 0x07;
    static final int PING = 0x0D;
    static final int GAL = 0x10;

    static final int SYNC_SYNC = 5;
    static final int SYNC_RESPONSES = 6;
    static final int SYNC_ADD = 7;
    static final int SYNC_CHANGE = 8;
    static final int SYNC_DELETE = 9;
    static final int SYNC_FETCH = 0xA;
    static final int SYNC_SYNC_KEY = 0xB;
    static final int SYNC_CLIENT_ID = 0xC;
    static final int SYNC_SERVER_ID = 0xD;
    static final int SYNC_STATUS = 0xE;
    static final int SYNC_COLLECTION = 0xF;
    static final int SYNC_CLASS = 0x10;
    static final int SYNC_VERSION = 0x11;
    static final int SYNC_COLLECTION_ID = 0x12;
    static final int SYNC_GET_CHANGES = 0x13;
    static final int SYNC_MORE_AVAILABLE = 0x14;
    static final int SYNC_WINDOW_SIZE = 0x15;
    static final int SYNC_COMMANDS = 0x16;
    static final int SYNC_OPTIONS = 0x17;
    static final int SYNC_FILTER_TYPE = 0x18;
    static final int SYNC_TRUNCATION = 0x19;
    static final int SYNC_RTF_TRUNCATION = 0x1A;
    static final int SYNC_CONFLICT = 0x1B;
    static final int SYNC_COLLECTIONS = 0x1C;
    static final int SYNC_APPLICATION_DATA = 0x1D;
    static final int SYNC_DELETES_AS_MOVES = 0x1E;
    static final int SYNC_NOTIFY_GUID = 0x1F;
    static final int SYNC_SUPPORTED = 0x20;
    static final int SYNC_SOFT_DELETE = 0x21;
    static final int SYNC_MIME_SUPPORT = 0x22;
    static final int SYNC_MIME_TRUNCATION = 0x23;
    static final int SYNC_WAIT = 0x24;
    static final int SYNC_LIMIT = 0x25;
    static final int SYNC_PARTIAL = 0x26;

    static final int CALENDAR_TIME_ZONE = 5;
    static final int CALENDAR_ALL_DAY_EVENT = 6;
    static final int CALENDAR_ATTENDEES = 7;
    static final int CALENDAR_ATTENDEE = 8;
    static final int CALENDAR_ATTENDEE_EMAIL = 9;
    static final int CALENDAR_ATTENDEE_NAME = 0xA;
    static final int CALENDAR_BODY = 0xB;
    static final int CALENDAR_BODY_TRUNCATED = 0xC;
    static final int CALENDAR_BUSY_STATUS = 0xD;
    static final int CALENDAR_CATEGORIES = 0xE;
    static final int CALENDAR_CATEGORY = 0xF;
    static final int CALENDAR_COMPRESSED_RTF = 0x10;
    static final int CALENDAR_DTSTAMP = 0x11;
    static final int CALENDAR_END_TIME = 0x12;
    static final int CALENDAR_EXCEPTION = 0x13;
    static final int CALENDAR_EXCEPTIONS = 0x14;
    static final int CALENDAR_EXCEPTION_IS_DELETED = 0x15;
    static final int CALENDAR_EXCEPTION_START_TIME = 0x16;
    static final int CALENDAR_LOCATION = 0x17;
    static final int CALENDAR_MEETING_STATUS = 0x18;
    static final int CALENDAR_ORGANIZER_EMAIL = 0x19;
    static final int CALENDAR_ORGANIZER_NAME = 0x1A;
    static final int CALENDAR_RECURRENCE = 0x1B;
    static final int CALENDAR_RECURRENCE_TYPE = 0x1C;
    static final int CALENDAR_RECURRENCE_UNTIL = 0x1D;
    static final int CALENDAR_RECURRENCE_OCCURRENCES = 0x1E;
    static final int CALENDAR_RECURRENCE_INTERVAL = 0x1F;
    static final int CALENDAR_RECURRENCE_DAYOFWEEK = 0x20;
    static final int CALENDAR_RECURRENCE_DAYOFMONTH = 0x21;
    static final int CALENDAR_RECURRENCE_WEEKOFMONTH = 0x22;
    static final int CALENDAR_RECURRENCE_MONTHOFYEAR = 0x23;
    static final int CALENDAR_REMINDER_MINS_BEFORE = 0x24;
    static final int CALENDAR_SENSITIVITY = 0x25;
    static final int CALENDAR_SUBJECT = 0x26;
    static final int CALENDAR_START_TIME = 0x27;
    static final int CALENDAR_UID = 0x28;
    static final int CALENDAR_ATTENDEE_STATUS = 0x29;
    static final int CALENDAR_ATTENDEE_TYPE = 0x2A;

    static final int FOLDER_FOLDERS = 5;
    static final int FOLDER_FOLDER = 6;
    static final int FOLDER_DISPLAY_NAME = 7;
    static final int FOLDER_SERVER_ID = 8;
    static final int FOLDER_PARENT_ID = 9;
    static final int FOLDER_TYPE = 0xA;
    static final int FOLDER_RESPONSE = 0xB;
    static final int FOLDER_STATUS = 0xC;
    static final int FOLDER_CONTENT_CLASS = 0xD;
    static final int FOLDER_CHANGES = 0xE;
    static final int FOLDER_ADD = 0xF;
    static final int FOLDER_DELETE = 0x10;
    static final int FOLDER_UPDATE = 0x11;
    static final int FOLDER_SYNC_KEY = 0x12;
    static final int FOLDER_FOLDER_CREATE = 0x13;
    static final int FOLDER_FOLDER_DELETE= 0x14;
    static final int FOLDER_FOLDER_UPDATE = 0x15;
    static final int FOLDER_FOLDER_SYNC = 0x16;
    static final int FOLDER_COUNT = 0x17;
    static final int FOLDER_VERSION = 0x18;

    static final int EMAIL_ATTACHMENT = 5;
    static final int EMAIL_ATTACHMENTS = 6;
    static final int EMAIL_ATT_NAME = 7;
    static final int EMAIL_ATT_SIZE = 8;
    static final int EMAIL_ATT0ID = 9;
    static final int EMAIL_ATT_METHOD = 0xA;
    static final int EMAIL_ATT_REMOVED = 0xB;
    static final int EMAIL_BODY = 0xC;
    static final int EMAIL_BODY_SIZE = 0xD;
    static final int EMAIL_BODY_TRUNCATED = 0xE;
    static final int EMAIL_DATE_RECEIVED = 0xF;
    static final int EMAIL_DISPLAY_NAME = 0x10;
    static final int EMAIL_DISPLAY_TO = 0x11;
    static final int EMAIL_IMPORTANCE = 0x12;
    static final int EMAIL_MESSAGE_CLASS = 0x13;
    static final int EMAIL_SUBJECT = 0x14;
    static final int EMAIL_READ = 0x15;
    static final int EMAIL_TO = 0x16;
    static final int EMAIL_CC = 0x17;
    static final int EMAIL_FROM = 0x18;
    static final int EMAIL_REPLY_TO = 0x19;
    static final int EMAIL_ALL_DAY_EVENT = 0x1A;
    static final int EMAIL_CATEGORIES = 0x1B;
    static final int EMAIL_CATEGORY = 0x1C;
    static final int EMAIL_DTSTAMP = 0x1D;
    static final int EMAIL_END_TIME = 0x1E;
    static final int EMAIL_INSTANCE_TYPE = 0x1F;
    static final int EMAIL_INTD_BUSY_STATUS = 0x20;
    static final int EMAIL_LOCATION = 0x21;
    static final int EMAIL_MEETING_REQUEST = 0x22;
    static final int EMAIL_ORGANIZER = 0x23;
    static final int EMAIL_RECURRENCE_ID = 0x24;
    static final int EMAIL_REMINDER = 0x25;
    static final int EMAIL_RESPONSE_REQUESTED = 0x26;
    static final int EMAIL_RECURRENCES = 0x27;
    static final int EMAIL_RECURRENCE = 0x28;
    static final int EMAIL_RECURRENCE_TYPE = 0x29;
    static final int EMAIL_RECURRENCE_UNTIL = 0x2A;
    static final int EMAIL_RECURRENCE_OCCURRENCES = 0x2B;
    static final int EMAIL_RECURRENCE_INTERVAL = 0x2C;
    static final int EMAIL_RECURRENCE_DAYOFWEEK = 0x2D;
    static final int EMAIL_RECURRENCE_DAYOFMONTH = 0x2E;
    static final int EMAIL_RECURRENCE_WEEKOFMONTH = 0x2F;
    static final int EMAIL_RECURRENCE_MONTHOFYEAR = 0x30;
    static final int EMAIL_START_TIME = 0x31;
    static final int EMAIL_SENSITIVITY = 0x32;
    static final int EMAIL_TIME_ZONE = 0x33;
    static final int EMAIL_GLOBAL_OBJID = 0x34;
    static final int EMAIL_THREAD_TOPIC = 0x35;
    static final int EMAIL_MIME_DATA = 0x36;
    static final int EMAIL_MIME_TRUNCATED = 0x37;
    static final int EMAIL_MIME_SIZE = 0x38;
    static final int EMAIL_INTERNET_CPID = 0x39;
    static final int EMAIL_FLAG = 0x3A;
    static final int EMAIL_FLAG_STATUS = 0x3B;
    static final int EMAIL_CONTENT_CLASS = 0x3C;
    static final int EMAIL_FLAG_TYPE = 0x3D;
    static final int EMAIL_COMPLETE_TIME = 0x3E;

    static final int MOVE_MOVE_ITEMS = 5;
    static final int MOVE_MOVE = 6;
    static final int MOVE_SRCMSGID = 7;
    static final int MOVE_SRCFLDID = 8;
    static final int MOVE_DSTFLDID = 9;
    static final int MOVE_RESPONSE = 0xA;
    static final int MOVE_STATUS = 0xB;
    static final int MOVE_DSTMSGID = 0xC;

    static final int PING_PING = 5;
    static final int PING_AUTD_STATE = 6;
    static final int PING_STATUS = 7;
    static final int PING_HEARTBEAT_INTERVAL = 8;
    static final int PING_FOLDERS = 9;
    static final int PING_FOLDER = 0xA;
    static final int PING_ID = 0xB;
    static final int PING_CLASS = 0xC;
    static final int PING_MAX_FOLDERS = 0xD;

    static final int BASE_BODY_PREFERENCE = 5;
    static final int BASE_TYPE = 6;
    static final int BASE_TRUNCATION_SIZE = 7;
    static final int BASE_ALL_OR_NONE = 8;
    static final int BASE_RESERVED = 9;
    static final int BASE_BODY = 0xA;
    static final int BASE_DATA = 0xB;
    static final int BASE_ESTIMATED_DATA_SIZE = 0xC;
    static final int BASE_TRUNCATED = 0xD;
    static final int BASE_ATTACHMENTS = 0xE;
    static final int BASE_ATTACHMENT = 0xF;
    static final int BASE_DISPLAY_NAME = 0x10;
    static final int BASE_FILE_REFERENCE = 0x11;
    static final int BASE_METHOD = 0x12;
    static final int BASE_CONTENT_ID = 0x13;
    static final int BASE_CONTENT_LOCATION = 0x14;
    static final int BASE_IS_INLINE = 0x15;
    static final int BASE_NATIVE_BODY_TYPE = 0x16;
    static final int BASE_CONTENT_TYPE = 0x17;

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
