/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.providers.contacts;

import com.android.internal.content.SyncStateContentProviderHelper;
import com.android.internal.database.ArrayListCursor;
import com.android.providers.contacts.OpenHelper.AggregationExceptionColumns;
import com.android.providers.contacts.OpenHelper.Clauses;
import com.android.providers.contacts.OpenHelper.ContactsColumns;
import com.android.providers.contacts.OpenHelper.DataColumns;
import com.android.providers.contacts.OpenHelper.GroupsColumns;
import com.android.providers.contacts.OpenHelper.MimetypesColumns;
import com.android.providers.contacts.OpenHelper.PackagesColumns;
import com.android.providers.contacts.OpenHelper.PhoneColumns;
import com.android.providers.contacts.OpenHelper.PhoneLookupColumns;
import com.android.providers.contacts.OpenHelper.RawContactsColumns;
import com.android.providers.contacts.OpenHelper.Tables;
import com.google.android.collect.Lists;

import android.accounts.Account;
import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.OperationApplicationException;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.ContactsContract;
import android.provider.Contacts.Intents;
import android.provider.ContactsContract.AggregationExceptions;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.Presence;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.BaseTypes;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.Contacts.AggregationSuggestions;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

/**
 * Contacts content provider. The contract between this provider and applications
 * is defined in {@link ContactsContract}.
 */
public class ContactsProvider2 extends ContentProvider {
    // TODO: clean up debug tag and rename this class
    private static final String TAG = "ContactsProvider ~~~~";

    // TODO: carefully prevent all incoming nested queries; they can be gaping security holes
    // TODO: check for restricted flag during insert(), update(), and delete() calls

    /** Default for the maximum number of returned aggregation suggestions. */
    private static final int DEFAULT_MAX_SUGGESTIONS = 5;

    private static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final String STREQUENT_ORDER_BY = Contacts.STARRED + " DESC, "
            + Contacts.TIMES_CONTACTED + " DESC, "
            + Contacts.DISPLAY_NAME + " ASC";
    private static final String STREQUENT_LIMIT =
            "(SELECT COUNT(1) FROM " + Tables.CONTACTS + " WHERE "
            + Contacts.STARRED + "=1) + 25";

    private static final int CONTACTS = 1000;
    private static final int CONTACTS_ID = 1001;
    private static final int CONTACTS_DATA = 1002;
    private static final int CONTACTS_SUMMARY = 1003;
    private static final int CONTACTS_RAW_CONTACTS = 1004;
    private static final int CONTACTS_SUMMARY_ID = 1005;
    private static final int CONTACTS_SUMMARY_FILTER = 1006;
    private static final int CONTACTS_SUMMARY_STREQUENT = 1007;
    private static final int CONTACTS_SUMMARY_STREQUENT_FILTER = 1008;
    private static final int CONTACTS_SUMMARY_GROUP = 1009;

    private static final int RAW_CONTACTS = 2002;
    private static final int RAW_CONTACTS_ID = 2003;
    private static final int RAW_CONTACTS_DATA = 2004;
    private static final int CONTACTS_FILTER_EMAIL = 2005;

    private static final int DATA = 3000;
    private static final int DATA_ID = 3001;
    private static final int PHONES = 3002;
    private static final int PHONES_FILTER = 3003;
    private static final int POSTALS = 3004;

    private static final int PHONE_LOOKUP = 4000;

    private static final int AGGREGATION_EXCEPTIONS = 6000;
    private static final int AGGREGATION_EXCEPTION_ID = 6001;

    private static final int PRESENCE = 7000;
    private static final int PRESENCE_ID = 7001;

    private static final int AGGREGATION_SUGGESTIONS = 8000;

    private static final int GROUPS = 10000;
    private static final int GROUPS_ID = 10001;
    private static final int GROUPS_SUMMARY = 10003;

    private static final int SYNCSTATE = 11000;

    private static final int SEARCH_SUGGESTIONS = 12001;
    private static final int SEARCH_SHORTCUT = 12002;

    private interface ContactsQuery {
        public static final String TABLE = Tables.RAW_CONTACTS;

        public static final String[] PROJECTION = new String[] {
            RawContactsColumns.CONCRETE_ID,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE,
        };

        public static final int RAW_CONTACT_ID = 0;
        public static final int ACCOUNT_NAME = 1;
        public static final int ACCOUNT_TYPE = 2;
    }

    private interface DataRawContactsQuery {
        public static final String TABLE = Tables.DATA_JOIN_MIMETYPE_RAW_CONTACTS;

        public static final String[] PROJECTION = new String[] {
            RawContactsColumns.CONCRETE_ID,
            DataColumns.CONCRETE_ID,
            RawContacts.CONTACT_ID,
            RawContacts.IS_RESTRICTED,
            Data.MIMETYPE,
        };

        public static final int RAW_CONTACT_ID = 0;
        public static final int DATA_ID = 1;
        public static final int CONTACT_ID = 2;
        public static final int IS_RESTRICTED = 3;
        public static final int MIMETYPE = 4;
    }

    private interface DataContactsQuery {
        public static final String TABLE = Tables.DATA_JOIN_MIMETYPES_RAW_CONTACTS_CONTACTS;

        public static final String[] PROJECTION = new String[] {
            RawContactsColumns.CONCRETE_ID,
            DataColumns.CONCRETE_ID,
            ContactsColumns.CONCRETE_ID,
            MimetypesColumns.CONCRETE_ID,
            Phone.NUMBER,
            Email.DATA,
            ContactsColumns.OPTIMAL_PRIMARY_PHONE_ID,
            ContactsColumns.FALLBACK_PRIMARY_PHONE_ID,
            ContactsColumns.OPTIMAL_PRIMARY_EMAIL_ID,
            ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID,
        };

        public static final int RAW_CONTACT_ID = 0;
        public static final int DATA_ID = 1;
        public static final int CONTACT_ID = 2;
        public static final int MIMETYPE_ID = 3;
        public static final int PHONE_NUMBER = 4;
        public static final int EMAIL_DATA = 5;
        public static final int OPTIMAL_PHONE_ID = 6;
        public static final int FALLBACK_PHONE_ID = 7;
        public static final int OPTIMAL_EMAIL_ID = 8;
        public static final int FALLBACK_EMAIL_ID = 9;

    }

    private interface DisplayNameQuery {
        public static final String TABLE = Tables.DATA_JOIN_MIMETYPES;

        public static final String[] COLUMNS = new String[] {
            MimetypesColumns.MIMETYPE,
            Data.IS_PRIMARY,
            Data.DATA2,
            StructuredName.DISPLAY_NAME,
        };

        public static final int MIMETYPE = 0;
        public static final int IS_PRIMARY = 1;
        public static final int DATA2 = 2;
        public static final int DISPLAY_NAME = 3;
    }

    private interface DataQuery {
        public static final String TABLE = Tables.DATA_JOIN_MIMETYPES;

        public static final String[] COLUMNS = new String[] {
            DataColumns.CONCRETE_ID,
            MimetypesColumns.MIMETYPE,
            Data.RAW_CONTACT_ID,
            Data.IS_PRIMARY,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA4,
            Data.DATA5,
            Data.DATA6,
            Data.DATA7,
            Data.DATA8,
            Data.DATA9,
            Data.DATA10,
            Data.DATA11,
            Data.DATA12,
            Data.DATA13,
            Data.DATA14,
            Data.DATA15,
        };

        public static final int ID = 0;
        public static final int MIMETYPE = 1;
        public static final int RAW_CONTACT_ID = 2;
        public static final int IS_PRIMARY = 3;
        public static final int DATA1 = 4;
        public static final int DATA2 = 5;
        public static final int DATA3 = 6;
        public static final int DATA4 = 7;
        public static final int DATA5 = 8;
        public static final int DATA6 = 9;
        public static final int DATA7 = 10;
        public static final int DATA8 = 11;
        public static final int DATA9 = 12;
        public static final int DATA10 = 13;
        public static final int DATA11 = 14;
        public static final int DATA12 = 15;
        public static final int DATA13 = 16;
        public static final int DATA14 = 17;
        public static final int DATA15 = 18;
    }

    private interface DataIdQuery {
        String[] COLUMNS = { Data._ID };

        int _ID = 0;
    }

    // Higher number represents higher priority in choosing what data to use for the display name
    private static final int DISPLAY_NAME_PRIORITY_EMAIL = 1;
    private static final int DISPLAY_NAME_PRIORITY_PHONE = 2;
    private static final int DISPLAY_NAME_PRIORITY_ORGANIZATION = 3;
    private static final int DISPLAY_NAME_PRIORITY_STRUCTURED_NAME = 4;

    private static final HashMap<String, Integer> sDisplayNamePriorities;
    static {
        sDisplayNamePriorities = new HashMap<String, Integer>();
        sDisplayNamePriorities.put(StructuredName.CONTENT_ITEM_TYPE,
                DISPLAY_NAME_PRIORITY_STRUCTURED_NAME);
        sDisplayNamePriorities.put(Organization.CONTENT_ITEM_TYPE,
                DISPLAY_NAME_PRIORITY_ORGANIZATION);
        sDisplayNamePriorities.put(Phone.CONTENT_ITEM_TYPE,
                DISPLAY_NAME_PRIORITY_PHONE);
        sDisplayNamePriorities.put(Email.CONTENT_ITEM_TYPE,
                DISPLAY_NAME_PRIORITY_EMAIL);
    }

    /** Contains just the contacts columns */
    private static final HashMap<String, String> sContactsProjectionMap;
    /** Contains the contact columns along with primary phone */
    private static final HashMap<String, String> sContactsSummaryProjectionMap;
    /** Contains the data, contacts, and contact columns, for joined tables. */
    private static final HashMap<String, String> sDataContactsContactProjectionMap;
    /** Contains the data, contacts, group sourceid and contact columns, for joined tables. */
    private static final HashMap<String, String> sDataContactsGroupsContactProjectionMap;
    /** Contains the contacts, and raw contact columns, for joined tables. */
    private static final HashMap<String, String> sRawContactsContactsProjectionMap;
    /** Contains just the contacts columns */
    private static final HashMap<String, String> sRawContactsProjectionMap;
    /** Contains just the data columns */
    private static final HashMap<String, String> sDataGroupsProjectionMap;
    /** Contains the data and contacts columns, for joined tables */
    private static final HashMap<String, String> sDataContactsGroupsProjectionMap;
    /** Contains the data and contacts columns, for joined tables */
    private static final HashMap<String, String> sDataContactsProjectionMap;
    /** Contains the just the {@link Groups} columns */
    private static final HashMap<String, String> sGroupsProjectionMap;
    /** Contains {@link Groups} columns along with summary details */
    private static final HashMap<String, String> sGroupsSummaryProjectionMap;
    /** Contains the agg_exceptions columns */
    private static final HashMap<String, String> sAggregationExceptionsProjectionMap;
    /** Contains Presence columns */
    private static final HashMap<String, String> sPresenceProjectionMap;

    /** Sql select statement that returns the contact id associated with a data record. */
    private static final String sNestedRawContactIdSelect;
    /** Sql select statement that returns the mimetype id associated with a data record. */
    private static final String sNestedMimetypeSelect;
    /** Sql select statement that returns the contact id associated with a contact record. */
    private static final String sNestedContactIdSelect;
    /** Sql select statement that returns a list of contact ids associated with an contact record. */
    private static final String sNestedContactIdListSelect;
    /** Sql where statement used to match all the data records that need to be updated when a new
     * "primary" is selected.*/
    private static final String sSetPrimaryWhere;
    /** Sql where statement used to match all the data records that need to be updated when a new
     * "super primary" is selected.*/
    private static final String sSetSuperPrimaryWhere;
    /** Sql where statement for filtering on groups. */
    private static final String sContactsInGroupSelect;
    /** Precompiled sql statement for setting a data record to the primary. */
    private SQLiteStatement mSetPrimaryStatement;
    /** Precompiled sql statement for setting a data record to the super primary. */
    private SQLiteStatement mSetSuperPrimaryStatement;
    /** Precompiled sql statement for incrementing times contacted for an contact */
    private SQLiteStatement mLastTimeContactedUpdate;
    /** Precompiled sql statement for updating a contact display name */
    private SQLiteStatement mContactDisplayNameUpdate;

    private static final String GTALK_PROTOCOL_STRING = Im
            .encodePredefinedImProtocol(Im.PROTOCOL_GOOGLE_TALK);

    static {
        // Contacts URI matching table
        final UriMatcher matcher = sUriMatcher;
        matcher.addURI(ContactsContract.AUTHORITY, "contacts", CONTACTS);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/#", CONTACTS_ID);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/#/data", CONTACTS_DATA);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/#/raw_contacts", CONTACTS_RAW_CONTACTS);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary", CONTACTS_SUMMARY);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary/#", CONTACTS_SUMMARY_ID);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary/filter/*",
                CONTACTS_SUMMARY_FILTER);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary/strequent/",
                CONTACTS_SUMMARY_STREQUENT);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary/strequent/filter/*",
                CONTACTS_SUMMARY_STREQUENT_FILTER);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts_summary/group/*",
                CONTACTS_SUMMARY_GROUP);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/#/suggestions",
                AGGREGATION_SUGGESTIONS);
        matcher.addURI(ContactsContract.AUTHORITY, "raw_contacts", RAW_CONTACTS);
        matcher.addURI(ContactsContract.AUTHORITY, "raw_contacts/#", RAW_CONTACTS_ID);
        matcher.addURI(ContactsContract.AUTHORITY, "raw_contacts/#/data", RAW_CONTACTS_DATA);
        matcher.addURI(ContactsContract.AUTHORITY, "raw_contacts/filter_email/*",
                CONTACTS_FILTER_EMAIL);

        matcher.addURI(ContactsContract.AUTHORITY, "data", DATA);
        matcher.addURI(ContactsContract.AUTHORITY, "data/#", DATA_ID);
        matcher.addURI(ContactsContract.AUTHORITY, "data/phones", PHONES);
        matcher.addURI(ContactsContract.AUTHORITY, "data/phones/filter/*", PHONES_FILTER);
        matcher.addURI(ContactsContract.AUTHORITY, "data/postals", POSTALS);

        matcher.addURI(ContactsContract.AUTHORITY, "groups", GROUPS);
        matcher.addURI(ContactsContract.AUTHORITY, "groups/#", GROUPS_ID);
        matcher.addURI(ContactsContract.AUTHORITY, "groups_summary", GROUPS_SUMMARY);

        matcher.addURI(ContactsContract.AUTHORITY, SyncStateContentProviderHelper.PATH, SYNCSTATE);

        matcher.addURI(ContactsContract.AUTHORITY, "phone_lookup/*", PHONE_LOOKUP);
        matcher.addURI(ContactsContract.AUTHORITY, "aggregation_exceptions",
                AGGREGATION_EXCEPTIONS);
        matcher.addURI(ContactsContract.AUTHORITY, "aggregation_exceptions/*",
                AGGREGATION_EXCEPTION_ID);

        matcher.addURI(ContactsContract.AUTHORITY, "presence", PRESENCE);
        matcher.addURI(ContactsContract.AUTHORITY, "presence/#", PRESENCE_ID);

        matcher.addURI(ContactsContract.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY,
                SEARCH_SUGGESTIONS);
        matcher.addURI(ContactsContract.AUTHORITY, SearchManager.SUGGEST_URI_PATH_QUERY + "/*",
                SEARCH_SUGGESTIONS);
        matcher.addURI(ContactsContract.AUTHORITY, SearchManager.SUGGEST_URI_PATH_SHORTCUT + "/#",
                SEARCH_SHORTCUT);

        HashMap<String, String> columns;

        // Contacts projection map
        columns = new HashMap<String, String>();
        columns.put(Contacts._ID, "contacts._id AS _id");
        columns.put(Contacts.DISPLAY_NAME, ContactsColumns.CONCRETE_DISPLAY_NAME + " AS "
                + Contacts.DISPLAY_NAME);
        columns.put(Contacts.LAST_TIME_CONTACTED, ContactsColumns.CONCRETE_LAST_TIME_CONTACTED
                + " AS " + Contacts.LAST_TIME_CONTACTED);
        columns.put(Contacts.TIMES_CONTACTED, ContactsColumns.CONCRETE_TIMES_CONTACTED + " AS "
                + Contacts.TIMES_CONTACTED);
        columns.put(Contacts.STARRED, ContactsColumns.CONCRETE_STARRED + " AS "
                + Contacts.STARRED);
        columns.put(Contacts.IN_VISIBLE_GROUP, Contacts.IN_VISIBLE_GROUP);
        columns.put(Contacts.PHOTO_ID, Contacts.PHOTO_ID);
        columns.put(Contacts.PRIMARY_PHONE_ID, Contacts.PRIMARY_PHONE_ID);
        columns.put(Contacts.PRIMARY_EMAIL_ID, Contacts.PRIMARY_EMAIL_ID);
        columns.put(Contacts.CUSTOM_RINGTONE, ContactsColumns.CONCRETE_CUSTOM_RINGTONE + " AS "
                + Contacts.CUSTOM_RINGTONE);
        columns.put(Contacts.SEND_TO_VOICEMAIL, ContactsColumns.CONCRETE_SEND_TO_VOICEMAIL
                + " AS " + Contacts.SEND_TO_VOICEMAIL);
        columns.put(ContactsColumns.FALLBACK_PRIMARY_PHONE_ID,
                ContactsColumns.FALLBACK_PRIMARY_PHONE_ID);
        columns.put(ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID,
                ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID);
        sContactsProjectionMap = columns;

        columns = new HashMap<String, String>();
        columns.putAll(sContactsProjectionMap);

        // Contacts primaries projection map. The overall presence status is
        // the most-present value, as indicated by the largest value.
        columns.put(Contacts.PRESENCE_STATUS, "MAX(" + Presence.PRESENCE_STATUS + ")");
        columns.put(Contacts.PRIMARY_PHONE_TYPE, CommonDataKinds.Phone.TYPE);
        columns.put(Contacts.PRIMARY_PHONE_LABEL, CommonDataKinds.Phone.LABEL);
        columns.put(Contacts.PRIMARY_PHONE_NUMBER, CommonDataKinds.Phone.NUMBER);
        sContactsSummaryProjectionMap = columns;

        // RawContacts projection map
        columns = new HashMap<String, String>();
        columns.put(RawContacts._ID, Tables.RAW_CONTACTS + "." + RawContacts._ID + " AS _id");
        columns.put(Clauses.COUNT_STAR, Clauses.COUNT_STAR);
        columns.put(RawContacts.CONTACT_ID, RawContacts.CONTACT_ID);
        columns.put(RawContacts.ACCOUNT_NAME,
                OpenHelper.RawContactsColumns.CONCRETE_ACCOUNT_NAME
                        + " AS " + RawContacts.ACCOUNT_NAME);
        columns.put(RawContacts.ACCOUNT_TYPE,
                OpenHelper.RawContactsColumns.CONCRETE_ACCOUNT_TYPE
                        + " AS " + RawContacts.ACCOUNT_TYPE);
        columns.put(RawContacts.SOURCE_ID,
                OpenHelper.RawContactsColumns.CONCRETE_SOURCE_ID
                        + " AS " + RawContacts.SOURCE_ID);
        columns.put(RawContacts.VERSION,
                OpenHelper.RawContactsColumns.CONCRETE_VERSION
                        + " AS " + RawContacts.VERSION);
        columns.put(RawContacts.DIRTY,
                OpenHelper.RawContactsColumns.CONCRETE_DIRTY
                        + " AS " + RawContacts.DIRTY);
        columns.put(RawContacts.DELETED,
                OpenHelper.RawContactsColumns.CONCRETE_DELETED
                        + " AS " + RawContacts.DELETED);
        sRawContactsProjectionMap = columns;

        columns = new HashMap<String, String>();
        columns.putAll(sContactsProjectionMap);
        columns.putAll(sRawContactsProjectionMap);
        sRawContactsContactsProjectionMap = columns;

        // Data projection map
        columns = new HashMap<String, String>();
        columns.put(Data._ID, Tables.DATA + "." + Data._ID + " AS _id");
        columns.put(Data.RAW_CONTACT_ID, Data.RAW_CONTACT_ID);
        columns.put(Data.RES_PACKAGE, PackagesColumns.PACKAGE + " AS " + Data.RES_PACKAGE);
        columns.put(Data.MIMETYPE, Data.MIMETYPE);
        columns.put(Data.IS_PRIMARY, Data.IS_PRIMARY);
        columns.put(Data.IS_SUPER_PRIMARY, Data.IS_SUPER_PRIMARY);
        columns.put(Data.DATA_VERSION, Data.DATA_VERSION);
        columns.put(Data.DATA1, "data.data1 as data1");
        columns.put(Data.DATA2, "data.data2 as data2");
        columns.put(Data.DATA3, "data.data3 as data3");
        columns.put(Data.DATA4, "data.data4 as data4");
        columns.put(Data.DATA5, "data.data5 as data5");
        columns.put(Data.DATA6, "data.data6 as data6");
        columns.put(Data.DATA7, "data.data7 as data7");
        columns.put(Data.DATA8, "data.data8 as data8");
        columns.put(Data.DATA9, "data.data9 as data9");
        columns.put(Data.DATA10, "data.data10 as data10");
        columns.put(Data.DATA11, "data.data11 as data11");
        columns.put(Data.DATA12, "data.data12 as data12");
        columns.put(Data.DATA13, "data.data13 as data13");
        columns.put(Data.DATA14, "data.data14 as data14");
        columns.put(Data.DATA15, "data.data15 as data15");
        columns.put(GroupMembership.GROUP_SOURCE_ID, GroupsColumns.CONCRETE_SOURCE_ID + " AS "
                + GroupMembership.GROUP_SOURCE_ID);

        // TODO: remove this projection
        // Mappings used for backwards compatibility.
        columns.put("number", Phone.NUMBER);
        sDataGroupsProjectionMap = columns;

        // Data, groups and contacts projection map for joins. _id comes from the data table
        columns = new HashMap<String, String>();
        columns.putAll(sRawContactsProjectionMap);
        columns.putAll(sDataGroupsProjectionMap); // _id will be replaced with the one from data
        columns.put(Data.RAW_CONTACT_ID, DataColumns.CONCRETE_RAW_CONTACT_ID);
        sDataContactsGroupsProjectionMap = columns;

        // Data and contacts projection map for joins. _id comes from the data table
        columns = new HashMap<String, String>();
        columns.putAll(sDataContactsGroupsProjectionMap);
        columns.remove(GroupMembership.GROUP_SOURCE_ID);
        sDataContactsProjectionMap = columns;

        // Data and contacts projection map for joins. _id comes from the data table
        columns = new HashMap<String, String>();
        columns.putAll(sContactsProjectionMap);
        columns.putAll(sRawContactsProjectionMap); //
        columns.putAll(sDataGroupsProjectionMap); // _id will be replaced with the one from data
        columns.put(Data.RAW_CONTACT_ID, DataColumns.CONCRETE_RAW_CONTACT_ID);
        sDataContactsGroupsContactProjectionMap = columns;

        // Data and contacts projection map for joins. _id comes from the data table
        columns = new HashMap<String, String>();
        columns.putAll(sDataContactsGroupsContactProjectionMap);
        columns.remove(GroupMembership.GROUP_SOURCE_ID);
        sDataContactsContactProjectionMap = columns;

        // Groups projection map
        columns = new HashMap<String, String>();
        columns.put(Groups._ID, "groups._id AS _id");
        columns.put(Groups.ACCOUNT_NAME, Groups.ACCOUNT_NAME);
        columns.put(Groups.ACCOUNT_TYPE, Groups.ACCOUNT_TYPE);
        columns.put(Groups.SOURCE_ID, Groups.SOURCE_ID);
        columns.put(Groups.DIRTY, Groups.DIRTY);
        columns.put(Groups.VERSION, Groups.VERSION);
        columns.put(Groups.RES_PACKAGE, PackagesColumns.PACKAGE + " AS " + Groups.RES_PACKAGE);
        columns.put(Groups.TITLE, Groups.TITLE);
        columns.put(Groups.TITLE_RES, Groups.TITLE_RES);
        columns.put(Groups.GROUP_VISIBLE, Groups.GROUP_VISIBLE);
        sGroupsProjectionMap = columns;

        // RawContacts and groups projection map
        columns = new HashMap<String, String>();
        columns.putAll(sGroupsProjectionMap);

        columns.put(Groups.SUMMARY_COUNT, "(SELECT COUNT(DISTINCT " + ContactsColumns.CONCRETE_ID
                + ") FROM " + Tables.DATA_JOIN_MIMETYPES_RAW_CONTACTS_CONTACTS + " WHERE "
                + Clauses.MIMETYPE_IS_GROUP_MEMBERSHIP + " AND " + Clauses.BELONGS_TO_GROUP
                + ") AS " + Groups.SUMMARY_COUNT);

        columns.put(Groups.SUMMARY_WITH_PHONES, "(SELECT COUNT(DISTINCT "
                + ContactsColumns.CONCRETE_ID + ") FROM "
                + Tables.DATA_JOIN_MIMETYPES_RAW_CONTACTS_CONTACTS + " WHERE "
                + Clauses.MIMETYPE_IS_GROUP_MEMBERSHIP + " AND " + Clauses.BELONGS_TO_GROUP
                + " AND " + Clauses.HAS_PRIMARY_PHONE + ") AS " + Groups.SUMMARY_WITH_PHONES);

        sGroupsSummaryProjectionMap = columns;

        // Aggregate exception projection map
        columns = new HashMap<String, String>();
        columns.put(AggregationExceptionColumns._ID, Tables.AGGREGATION_EXCEPTIONS + "._id AS _id");
        columns.put(AggregationExceptions.TYPE, AggregationExceptions.TYPE);
        columns.put(AggregationExceptions.CONTACT_ID,
                "raw_contacts1." + RawContacts.CONTACT_ID
                + " AS " + AggregationExceptions.CONTACT_ID);
        columns.put(AggregationExceptions.RAW_CONTACT_ID, AggregationExceptionColumns.RAW_CONTACT_ID2);
        sAggregationExceptionsProjectionMap = columns;


        columns = new HashMap<String, String>();
        columns.put(Presence._ID, Presence._ID);
        columns.put(Presence.RAW_CONTACT_ID, Presence.RAW_CONTACT_ID);
        columns.put(Presence.DATA_ID, Presence.DATA_ID);
        columns.put(Presence.IM_ACCOUNT, Presence.IM_ACCOUNT);
        columns.put(Presence.IM_HANDLE, Presence.IM_HANDLE);
        columns.put(Presence.IM_PROTOCOL, Presence.IM_PROTOCOL);
        columns.put(Presence.PRESENCE_STATUS, Presence.PRESENCE_STATUS);
        columns.put(Presence.PRESENCE_CUSTOM_STATUS, Presence.PRESENCE_CUSTOM_STATUS);
        sPresenceProjectionMap = columns;

        sNestedRawContactIdSelect = "SELECT " + Data.RAW_CONTACT_ID + " FROM " + Tables.DATA + " WHERE "
                + Data._ID + "=?";
        sNestedMimetypeSelect = "SELECT " + DataColumns.MIMETYPE_ID + " FROM " + Tables.DATA
                + " WHERE " + Data._ID + "=?";
        sNestedContactIdSelect = "SELECT " + RawContacts.CONTACT_ID + " FROM " + Tables.RAW_CONTACTS
                + " WHERE " + RawContacts._ID + "=(" + sNestedRawContactIdSelect + ")";
        sNestedContactIdListSelect = "SELECT " + RawContacts._ID + " FROM " + Tables.RAW_CONTACTS
                + " WHERE " + RawContacts.CONTACT_ID + "=(" + sNestedContactIdSelect + ")";
        sSetPrimaryWhere = Data.RAW_CONTACT_ID + "=(" + sNestedRawContactIdSelect + ") AND "
                + DataColumns.MIMETYPE_ID + "=(" + sNestedMimetypeSelect + ")";
        sSetSuperPrimaryWhere = Data.RAW_CONTACT_ID + " IN (" + sNestedContactIdListSelect + ") AND "
                + DataColumns.MIMETYPE_ID + "=(" + sNestedMimetypeSelect + ")";
        sContactsInGroupSelect = ContactsColumns.CONCRETE_ID + " IN (SELECT "
                + RawContacts.CONTACT_ID + " FROM " + Tables.RAW_CONTACTS + " WHERE ("
                + RawContactsColumns.CONCRETE_ID + " IN (SELECT " + Tables.DATA + "."
                + Data.RAW_CONTACT_ID + " FROM " + Tables.DATA_JOIN_MIMETYPES + " WHERE ("
                + Data.MIMETYPE + "='" + GroupMembership.CONTENT_ITEM_TYPE + "' AND "
                + GroupMembership.GROUP_ROW_ID + "=(SELECT " + Tables.GROUPS + "."
                + Groups._ID + " FROM " + Tables.GROUPS + " WHERE " + Groups.TITLE + "=?)))))";
    }

    /**
     * Handles inserts and update for a specific Data type.
     */
    private abstract class DataRowHandler {

        protected final String mMimetype;

        public DataRowHandler(String mimetype) {
            mMimetype = mimetype;
        }

        /**
         * Inserts a row into the {@link Data} table.
         */
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            final long dataId = db.insert(Tables.DATA, null, values);

            Integer primary = values.getAsInteger(Data.IS_PRIMARY);
            if (primary != null && primary != 0) {
                setIsPrimary(dataId);
            }

            fixContactDisplayName(db, rawContactId);
            return dataId;
        }

        /**
         * Validates data and updates a {@link Data} row using the cursor, which contains
         * the current data.
         */
        public void update(SQLiteDatabase db, ContentValues values, Cursor cursor) {
            throw new UnsupportedOperationException();
        }

        public int delete(SQLiteDatabase db, Cursor c) {
            long dataId = c.getLong(DataQuery.ID);
            long rawContactId = c.getLong(DataQuery.RAW_CONTACT_ID);
            boolean primary = c.getInt(DataQuery.IS_PRIMARY) != 0;
            int count = db.delete(Tables.DATA, Data._ID + "=" + dataId, null);
            if (count != 0 && primary) {
                fixPrimary(db, rawContactId);
                fixContactDisplayName(db, rawContactId);
            }
            return count;
        }

        private void fixPrimary(SQLiteDatabase db, long rawContactId) {
            long newPrimaryId = findNewPrimaryDataId(db, rawContactId);
            if (newPrimaryId != -1) {
                ContactsProvider2.this.setIsPrimary(newPrimaryId);
            }
        }

        protected long findNewPrimaryDataId(SQLiteDatabase db, long rawContactId) {
            long primaryId = -1;
            int primaryType = -1;
            Cursor c = queryData(db, rawContactId);
            try {
                while (c.moveToNext()) {
                    long dataId = c.getLong(DataQuery.ID);
                    int type = c.getInt(DataQuery.DATA2);
                    if (primaryType == -1 || getTypeRank(type) < getTypeRank(primaryType)) {
                        primaryId = dataId;
                        primaryType = type;
                    }
                }
            } finally {
                c.close();
            }
            return primaryId;
        }

        /**
         * Returns the rank of a specific record type to be used in determining the primary
         * row. Lower number represents higher priority.
         */
        protected int getTypeRank(int type) {
            return 0;
        }

        protected Cursor queryData(SQLiteDatabase db, long rawContactId) {
            // TODO Lookup integer mimetype IDs' instead of joining for speed
            return db.query(DataQuery.TABLE, DataQuery.COLUMNS, Data.RAW_CONTACT_ID + "="
                    + rawContactId + " AND " + MimetypesColumns.MIMETYPE + "='" + mMimetype + "'",
                    null, null, null, null);
        }

        protected void fixContactDisplayName(SQLiteDatabase db, long rawContactId) {
            if (!sDisplayNamePriorities.containsKey(mMimetype)) {
                return;
            }

            String bestDisplayName = null;
            Cursor c = db.query(DisplayNameQuery.TABLE, DisplayNameQuery.COLUMNS,
                    Data.RAW_CONTACT_ID + "=" + rawContactId, null, null, null, null);
            try {
                int maxPriority = -1;
                while (c.moveToNext()) {
                    String mimeType = c.getString(DisplayNameQuery.MIMETYPE);
                    boolean primary;
                    String name;

                    if (StructuredName.CONTENT_ITEM_TYPE.equals(mimeType)) {
                        name = c.getString(DisplayNameQuery.DISPLAY_NAME);
                        primary = true;
                    } else {
                        name = c.getString(DisplayNameQuery.DATA2);
                        primary = (c.getInt(DisplayNameQuery.IS_PRIMARY) != 0);
                    }

                    if (primary && name != null) {
                        Integer priority = sDisplayNamePriorities.get(mimeType);
                        if (priority != null && priority > maxPriority) {
                            maxPriority = priority;
                            bestDisplayName = name;
                        }
                    }
                }

            } finally {
                c.close();
            }

            ContactsProvider2.this.setDisplayName(rawContactId, bestDisplayName);
        }
    }

    public class CustomDataRowHandler extends DataRowHandler {

        public CustomDataRowHandler(String mimetype) {
            super(mimetype);
        }
    }

    public class StructuredNameRowHandler extends DataRowHandler {

        private final NameSplitter mNameSplitter;

        public StructuredNameRowHandler(NameSplitter nameSplitter) {
            super(StructuredName.CONTENT_ITEM_TYPE);
            mNameSplitter = nameSplitter;
        }

        @Override
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            fixStructuredNameComponents(values);
            return super.insert(db, rawContactId, values);
        }

        @Override
        public void update(SQLiteDatabase db, ContentValues values, Cursor cursor) {
            // TODO Parse the full name if it has changed and replace pre-existing piece parts.
        }

        /**
         * Parses the supplied display name, but only if the incoming values do not already contain
         * structured name parts.  Also, if the display name is not provided, generate one by
         * concatenating first name and last name
         *
         * TODO see if the order of first and last names needs to be conditionally reversed for
         * some locales, e.g. China.
         */
        private void fixStructuredNameComponents(ContentValues values) {
            String fullName = values.getAsString(StructuredName.DISPLAY_NAME);
            if (!TextUtils.isEmpty(fullName)
                    && TextUtils.isEmpty(values.getAsString(StructuredName.PREFIX))
                    && TextUtils.isEmpty(values.getAsString(StructuredName.GIVEN_NAME))
                    && TextUtils.isEmpty(values.getAsString(StructuredName.MIDDLE_NAME))
                    && TextUtils.isEmpty(values.getAsString(StructuredName.FAMILY_NAME))
                    && TextUtils.isEmpty(values.getAsString(StructuredName.SUFFIX))) {
                NameSplitter.Name name = new NameSplitter.Name();
                mNameSplitter.split(name, fullName);

                values.put(StructuredName.PREFIX, name.getPrefix());
                values.put(StructuredName.GIVEN_NAME, name.getGivenNames());
                values.put(StructuredName.MIDDLE_NAME, name.getMiddleName());
                values.put(StructuredName.FAMILY_NAME, name.getFamilyName());
                values.put(StructuredName.SUFFIX, name.getSuffix());
            }

            if (TextUtils.isEmpty(fullName)) {
                String givenName = values.getAsString(StructuredName.GIVEN_NAME);
                String familyName = values.getAsString(StructuredName.FAMILY_NAME);
                if (TextUtils.isEmpty(givenName)) {
                    fullName = familyName;
                } else if (TextUtils.isEmpty(familyName)) {
                    fullName = givenName;
                } else {
                    fullName = givenName + " " + familyName;
                }

                if (!TextUtils.isEmpty(fullName)) {
                    values.put(StructuredName.DISPLAY_NAME, fullName);
                }
            }
        }
    }

    public class CommonDataRowHandler extends DataRowHandler {

        private final String mTypeColumn;
        private final String mLabelColumn;

        public CommonDataRowHandler(String mimetype, String typeColumn, String labelColumn) {
            super(mimetype);
            mTypeColumn = typeColumn;
            mLabelColumn = labelColumn;
        }

        @Override
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            int type;
            String label;
            if (values.containsKey(mTypeColumn)) {
                type = values.getAsInteger(mTypeColumn);
            } else {
                type = BaseTypes.TYPE_CUSTOM;
            }
            if (values.containsKey(mLabelColumn)) {
                label = values.getAsString(mLabelColumn);
            } else {
                label = null;
            }

            if (type != BaseTypes.TYPE_CUSTOM && label != null) {
                throw new RuntimeException(mLabelColumn + " value can only be specified with "
                        + mTypeColumn + "=" + BaseTypes.TYPE_CUSTOM + "(custom)");
            }

            if (type == BaseTypes.TYPE_CUSTOM && label == null) {
                throw new RuntimeException(mLabelColumn + " value must be specified when "
                        + mTypeColumn + "=" + BaseTypes.TYPE_CUSTOM + "(custom)");
            }

            return super.insert(db, rawContactId, values);
        }

        @Override
        public void update(SQLiteDatabase db, ContentValues values, Cursor cursor) {
            // TODO read the data and check the constraint
        }
    }

    public class OrganizationDataRowHandler extends CommonDataRowHandler {

        public OrganizationDataRowHandler() {
            super(Organization.CONTENT_ITEM_TYPE, Organization.TYPE, Organization.LABEL);
        }

        @Override
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            long id = super.insert(db, rawContactId, values);
            fixContactDisplayName(db, rawContactId);
            return id;
        }

        @Override
        protected int getTypeRank(int type) {
            switch (type) {
                case Organization.TYPE_WORK: return 0;
                case Organization.TYPE_CUSTOM: return 1;
                case Organization.TYPE_OTHER: return 2;
                default: return 1000;
            }
        }
    }

    public class EmailDataRowHandler extends CommonDataRowHandler {

        public EmailDataRowHandler() {
            super(Email.CONTENT_ITEM_TYPE, Email.TYPE, Email.LABEL);
        }

        @Override
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            long id = super.insert(db, rawContactId, values);
            fixContactDisplayName(db, rawContactId);
            return id;
        }

        @Override
        protected int getTypeRank(int type) {
            switch (type) {
                case Email.TYPE_HOME: return 0;
                case Email.TYPE_WORK: return 1;
                case Email.TYPE_CUSTOM: return 2;
                case Email.TYPE_OTHER: return 3;
                default: return 1000;
            }
        }
    }

    public class PhoneDataRowHandler extends CommonDataRowHandler {

        public PhoneDataRowHandler() {
            super(Phone.CONTENT_ITEM_TYPE, Phone.TYPE, Phone.LABEL);
        }

        @Override
        public long insert(SQLiteDatabase db, long rawContactId, ContentValues values) {
            ContentValues phoneValues = new ContentValues();
            String number = values.getAsString(Phone.NUMBER);
            String normalizedNumber = null;
            if (number != null) {
                normalizedNumber = PhoneNumberUtils.getStrippedReversed(number);
                values.put(PhoneColumns.NORMALIZED_NUMBER, normalizedNumber);
            }

            long id = super.insert(db, rawContactId, values);

            if (number != null) {
                phoneValues.put(PhoneLookupColumns.RAW_CONTACT_ID, rawContactId);
                phoneValues.put(PhoneLookupColumns.DATA_ID, id);
                phoneValues.put(PhoneLookupColumns.NORMALIZED_NUMBER, normalizedNumber);
                db.insert(Tables.PHONE_LOOKUP, null, phoneValues);
            }

            return id;
        }

        @Override
        protected int getTypeRank(int type) {
            switch (type) {
                case Phone.TYPE_MOBILE: return 0;
                case Phone.TYPE_WORK: return 1;
                case Phone.TYPE_HOME: return 2;
                case Phone.TYPE_PAGER: return 3;
                case Phone.TYPE_CUSTOM: return 4;
                case Phone.TYPE_OTHER: return 5;
                case Phone.TYPE_FAX_WORK: return 6;
                case Phone.TYPE_FAX_HOME: return 7;
                default: return 1000;
            }
        }
    }

    private HashMap<String, DataRowHandler> mDataRowHandlers;
    private final ContactAggregationScheduler mAggregationScheduler;
    private OpenHelper mOpenHelper;

    private ContactAggregator mContactAggregator;
    private NameSplitter mNameSplitter;
    private LegacyApiSupport mLegacyApiSupport;

    private ContentValues mValues = new ContentValues();

    public ContactsProvider2() {
        this(new ContactAggregationScheduler());
    }

    /**
     * Constructor for testing.
     */
    /* package */ ContactsProvider2(ContactAggregationScheduler scheduler) {
        mAggregationScheduler = scheduler;
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        mOpenHelper = getOpenHelper(context);
        mLegacyApiSupport = new LegacyApiSupport(context, mOpenHelper, this);
        mContactAggregator = new ContactAggregator(context, mOpenHelper, mAggregationScheduler);

        final SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        mSetPrimaryStatement = db.compileStatement(
                "UPDATE " + Tables.DATA + " SET " + Data.IS_PRIMARY
                + "=(_id=?) WHERE " + sSetPrimaryWhere);
        mSetSuperPrimaryStatement = db.compileStatement(
                "UPDATE " + Tables.DATA + " SET " + Data.IS_SUPER_PRIMARY
                + "=(_id=?) WHERE " + sSetSuperPrimaryWhere);
        mLastTimeContactedUpdate = db.compileStatement("UPDATE " + Tables.RAW_CONTACTS + " SET "
                + RawContacts.TIMES_CONTACTED + "=" + RawContacts.TIMES_CONTACTED + "+1,"
                + RawContacts.LAST_TIME_CONTACTED + "=? WHERE " + RawContacts.CONTACT_ID + "=?");

        mContactDisplayNameUpdate = db.compileStatement("UPDATE " + Tables.RAW_CONTACTS + " SET "
                + RawContactsColumns.DISPLAY_NAME + "=? WHERE " + RawContacts._ID + "=?");

        mNameSplitter = new NameSplitter(
                context.getString(com.android.internal.R.string.common_name_prefixes),
                context.getString(com.android.internal.R.string.common_last_name_prefixes),
                context.getString(com.android.internal.R.string.common_name_suffixes),
                context.getString(com.android.internal.R.string.common_name_conjunctions));

        mDataRowHandlers = new HashMap<String, DataRowHandler>();

        mDataRowHandlers.put(Email.CONTENT_ITEM_TYPE, new EmailDataRowHandler());
        mDataRowHandlers.put(Im.CONTENT_ITEM_TYPE,
                new CommonDataRowHandler(Im.CONTENT_ITEM_TYPE, Im.TYPE, Im.LABEL));
        mDataRowHandlers.put(Nickname.CONTENT_ITEM_TYPE, new CommonDataRowHandler(
                StructuredPostal.CONTENT_ITEM_TYPE, StructuredPostal.TYPE, StructuredPostal.LABEL));
        mDataRowHandlers.put(Organization.CONTENT_ITEM_TYPE, new OrganizationDataRowHandler());
        mDataRowHandlers.put(Phone.CONTENT_ITEM_TYPE, new PhoneDataRowHandler());
        mDataRowHandlers.put(Nickname.CONTENT_ITEM_TYPE, new CommonDataRowHandler(
                Nickname.CONTENT_ITEM_TYPE, Nickname.TYPE, Nickname.LABEL));
        mDataRowHandlers.put(StructuredName.CONTENT_ITEM_TYPE,
                new StructuredNameRowHandler(mNameSplitter));

        return (db != null);
    }

    /* Visible for testing */
    protected OpenHelper getOpenHelper(final Context context) {
        return OpenHelper.getInstance(context);
    }

    @Override
    protected void finalize() throws Throwable {
        if (mContactAggregator != null) {
            mContactAggregator.quit();
        }

        super.finalize();
    }

    /**
     * Wipes all data from the contacts database.
     */
    /* package */ void wipeData() {
        mOpenHelper.wipeData();
    }

    /**
     * Called when a change has been made.
     *
     * @param uri the uri that the change was made to
     */
    private void onChange(Uri uri) {
        getContext().getContentResolver().notifyChange(ContactsContract.AUTHORITY_URI, null);
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    private DataRowHandler getDataRowHandler(final String mimeType) {
        DataRowHandler handler = mDataRowHandlers.get(mimeType);
        if (handler == null) {
            handler = new CustomDataRowHandler(mimeType);
            mDataRowHandlers.put(mimeType, handler);
        }
        return handler;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        final int match = sUriMatcher.match(uri);
        long id = 0;

        switch (match) {
            case SYNCSTATE:
                id = mOpenHelper.getSyncState().insert(mOpenHelper.getWritableDatabase(), values);
                break;

            case CONTACTS: {
                insertContact(values);
                break;
            }

            case RAW_CONTACTS: {
                final Account account = readAccountFromQueryParams(uri);
                id = insertRawContact(values, account);
                break;
            }

            case RAW_CONTACTS_DATA: {
                values.put(Data.RAW_CONTACT_ID, uri.getPathSegments().get(1));
                id = insertData(values);
                break;
            }

            case DATA: {
                id = insertData(values);
                break;
            }

            case GROUPS: {
                final Account account = readAccountFromQueryParams(uri);
                id = insertGroup(values, account);
                break;
            }

            case PRESENCE: {
                id = insertPresence(values);
                break;
            }

            default:
                return mLegacyApiSupport.insert(uri, values);
        }

        if (id < 0) {
            return null;
        }

        final Uri result = ContentUris.withAppendedId(uri, id);
        onChange(result);
        return result;
    }

    /**
     * If account is non-null then store it in the values. If the account is already
     * specified in the values then it must be consistent with the account, if it is non-null.
     * @param values the ContentValues to read from and update
     * @param account the explicitly provided Account
     * @return false if the accounts are inconsistent
     */
    private boolean resolveAccount(ContentValues values, Account account) {
        // If either is specified then both must be specified.
        final String accountName = values.getAsString(RawContacts.ACCOUNT_NAME);
        final String accountType = values.getAsString(RawContacts.ACCOUNT_TYPE);
        if (!TextUtils.isEmpty(accountName) || !TextUtils.isEmpty(accountType)) {
            final Account valuesAccount = new Account(accountName, accountType);
            if (account != null && !valuesAccount.equals(account)) {
                return false;
            }
            account = valuesAccount;
        }
        if (account != null) {
            values.put(RawContacts.ACCOUNT_NAME, account.mName);
            values.put(RawContacts.ACCOUNT_TYPE, account.mType);
        }
        return true;
    }

    /**
     * Inserts an item in the contacts table
     *
     * @param values the values for the new row
     * @return the row ID of the newly created row
     */
    private long insertContact(ContentValues values) {
        throw new UnsupportedOperationException("Aggregates are created automatically");
    }

    /**
     * Inserts an item in the contacts table
     *
     * @param values the values for the new row
     * @param account the account this contact should be associated with. may be null.
     * @return the row ID of the newly created row
     */
    private long insertRawContact(ContentValues values, Account account) {
        /*
         * The contact record is inserted in the contacts table, but it needs to
         * be processed by the aggregator before it will be returned by the
         * "aggregates" queries.
         */
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        ContentValues overriddenValues = new ContentValues(values);
        overriddenValues.putNull(RawContacts.CONTACT_ID);
        if (!resolveAccount(overriddenValues, account)) {
            return -1;
        }

        return db.insert(Tables.RAW_CONTACTS, RawContacts.CONTACT_ID, overriddenValues);
    }

    /**
     * Inserts an item in the data table
     *
     * @param values the values for the new row
     * @return the row ID of the newly created row
     */
    private long insertData(ContentValues values) {
        int aggregationMode = RawContacts.AGGREGATION_MODE_DISABLED;

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long id = 0;
        db.beginTransaction();
        try {
            mValues.clear();
            mValues.putAll(values);

            long rawContactId = mValues.getAsLong(Data.RAW_CONTACT_ID);

            // Replace package with internal mapping
            final String packageName = mValues.getAsString(Data.RES_PACKAGE);
            if (packageName != null) {
                mValues.put(DataColumns.PACKAGE_ID, mOpenHelper.getPackageId(packageName));
            }
            mValues.remove(Data.RES_PACKAGE);

            // Replace mimetype with internal mapping
            final String mimeType = mValues.getAsString(Data.MIMETYPE);
            if (TextUtils.isEmpty(mimeType)) {
                throw new RuntimeException(Data.MIMETYPE + " is required");
            }

            mValues.put(DataColumns.MIMETYPE_ID, mOpenHelper.getMimeTypeId(mimeType));
            mValues.remove(Data.MIMETYPE);

            if (GroupMembership.CONTENT_ITEM_TYPE.equals(mimeType)) {
                boolean containsGroupSourceId = mValues.containsKey(GroupMembership.GROUP_SOURCE_ID);
                boolean containsGroupId = mValues.containsKey(GroupMembership.GROUP_ROW_ID);
                if (containsGroupSourceId && containsGroupId) {
                    throw new IllegalArgumentException(
                            "you are not allowed to set both the GroupMembership.GROUP_SOURCE_ID "
                                    + "and GroupMembership.GROUP_ROW_ID");
                }

                if (!containsGroupSourceId && !containsGroupId) {
                    throw new IllegalArgumentException(
                            "you must set exactly one of GroupMembership.GROUP_SOURCE_ID "
                                    + "and GroupMembership.GROUP_ROW_ID");
                }

                if (containsGroupSourceId) {
                    final String sourceId = mValues.getAsString(GroupMembership.GROUP_SOURCE_ID);
                    final long groupId = getOrMakeGroup(db, rawContactId, sourceId);
                    mValues.remove(GroupMembership.GROUP_SOURCE_ID);
                    mValues.put(GroupMembership.GROUP_ROW_ID, groupId);
                }
            }

            id = getDataRowHandler(mimeType).insert(db, rawContactId, mValues);

            aggregationMode = mContactAggregator.markContactForAggregation(rawContactId);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        triggerAggregation(id, aggregationMode);
        return id;
    }

    private void triggerAggregation(long rawContactId, int aggregationMode) {
        switch (aggregationMode) {
            case RawContacts.AGGREGATION_MODE_DEFAULT:
                mContactAggregator.schedule();
                break;

            case RawContacts.AGGREGATION_MODE_IMMEDITATE:
                mContactAggregator.aggregateContact(rawContactId);
                break;

            case RawContacts.AGGREGATION_MODE_DISABLED:
                // Do nothing
                break;
        }
    }

    /**
     * Returns the group id of the group with sourceId and the same account as rawContactId.
     * If the group doesn't already exist then it is first created,
     * @param db SQLiteDatabase to use for this operation
     * @param rawContactId the contact this group is associated with
     * @param sourceId the sourceIf of the group to query or create
     * @return the group id of the existing or created group
     * @throws IllegalArgumentException if the contact is not associated with an account
     * @throws IllegalStateException if a group needs to be created but the creation failed
     */
    private long getOrMakeGroup(SQLiteDatabase db, long rawContactId, String sourceId) {
        Account account = null;
        Cursor c = db.query(ContactsQuery.TABLE, ContactsQuery.PROJECTION, RawContacts._ID + "="
                + rawContactId, null, null, null, null);
        try {
            if (c.moveToNext()) {
                final String accountName = c.getString(ContactsQuery.ACCOUNT_NAME);
                final String accountType = c.getString(ContactsQuery.ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                    account = new Account(accountName, accountType);
                }
            }
        } finally {
            c.close();
        }
        if (account == null) {
            throw new IllegalArgumentException("if the groupmembership only "
                    + "has a sourceid the the contact must be associate with "
                    + "an account");
        }

        // look up the group that contains this sourceId and has the same account name and type
        // as the contact refered to by rawContactId
        c = db.query(Tables.GROUPS, new String[]{RawContacts._ID},
                Clauses.GROUP_HAS_ACCOUNT_AND_SOURCE_ID,
                new String[]{sourceId, account.mName, account.mType}, null, null, null);
        try {
            if (c.moveToNext()) {
                return c.getLong(0);
            } else {
                ContentValues groupValues = new ContentValues();
                groupValues.put(Groups.ACCOUNT_NAME, account.mName);
                groupValues.put(Groups.ACCOUNT_TYPE, account.mType);
                groupValues.put(Groups.SOURCE_ID, sourceId);
                long groupId = db.insert(Tables.GROUPS, Groups.ACCOUNT_NAME, groupValues);
                if (groupId < 0) {
                    throw new IllegalStateException("unable to create a new group with "
                            + "this sourceid: " + groupValues);
                }
                return groupId;
            }
        } finally {
            c.close();
        }
    }

    /**
     * Delete data row by row so that fixing of primaries etc work correctly.
     */
    private int deleteData(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = 0;
        db.beginTransaction();
        try {

            // Note that the query will return data according to the access restrictions,
            // so we don't need to worry about deleting data we don't have permission to read.
            Cursor c = query(Data.CONTENT_URI, DataIdQuery.COLUMNS, selection, selectionArgs, null);
            try {
                while(c.moveToNext()) {
                    long dataId = c.getLong(DataIdQuery._ID);
                    count += deleteData(dataId);
                }
            } finally {
                c.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return count;
    }

    public int deleteData(long dataId, String[] allowedMimeTypes) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor c = db.query(DataQuery.TABLE, DataQuery.COLUMNS,
                DataColumns.CONCRETE_ID + "=" + dataId, null, null, null, null);
        // TODO apply restrictions
        try {
            if (!c.moveToFirst()) {
                return 0;
            }

            String mimeType = c.getString(DataQuery.MIMETYPE);
            boolean valid = false;
            for (int i = 0; i < allowedMimeTypes.length; i++) {
                if (TextUtils.equals(mimeType, allowedMimeTypes[i])) {
                    valid = true;
                    break;
                }
            }

            if (!valid) {
                throw new RuntimeException("Data type mismatch: expected "
                        + Lists.newArrayList(allowedMimeTypes));
            }

            return getDataRowHandler(mimeType).delete(db, c);
        } finally {
            c.close();
        }
    }

    /**
     * Delete the given {@link Data} row, fixing up any {@link Contacts}
     * primaries that reference it.
     */
    private int deleteData(long dataId) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        final long mimePhone = mOpenHelper.getMimeTypeId(Phone.CONTENT_ITEM_TYPE);
        final long mimeEmail = mOpenHelper.getMimeTypeId(Email.CONTENT_ITEM_TYPE);

        // Check to see if the data about to be deleted was a super-primary on
        // the parent aggregate, and set flags to fix-up once deleted.
        long aggId = -1;
        long mimeId = -1;
        String dataRaw = null;
        boolean fixOptimal = false;
        boolean fixFallback = false;

        Cursor cursor = null;
        try {
            cursor = db.query(DataContactsQuery.TABLE, DataContactsQuery.PROJECTION,
                    DataColumns.CONCRETE_ID + "=" + dataId, null, null, null, null);
            if (cursor.moveToFirst()) {
                aggId = cursor.getLong(DataContactsQuery.CONTACT_ID);
                mimeId = cursor.getLong(DataContactsQuery.MIMETYPE_ID);
                if (mimeId == mimePhone) {
                    dataRaw = cursor.getString(DataContactsQuery.PHONE_NUMBER);
                    fixOptimal = (cursor.getLong(DataContactsQuery.OPTIMAL_PHONE_ID) == dataId);
                    fixFallback = (cursor.getLong(DataContactsQuery.FALLBACK_PHONE_ID) == dataId);
                } else if (mimeId == mimeEmail) {
                    dataRaw = cursor.getString(DataContactsQuery.EMAIL_DATA);
                    fixOptimal = (cursor.getLong(DataContactsQuery.OPTIMAL_EMAIL_ID) == dataId);
                    fixFallback = (cursor.getLong(DataContactsQuery.FALLBACK_EMAIL_ID) == dataId);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
                cursor = null;
            }
        }

        // Delete the requested data item.
        int dataDeleted = db.delete(Tables.DATA, Data._ID + "=" + dataId, null);

        // Fix-up any super-primary values that are now invalid.
        if (fixOptimal || fixFallback) {
            final ContentValues values = new ContentValues();
            final StringBuilder scoreClause = new StringBuilder();

            final String SCORE = "score";

            // Build scoring clause that will first pick data items under the
            // same aggregate that have identical values, otherwise fall back to
            // normal primary scoring from the member contacts.
            scoreClause.append("(CASE WHEN ");
            if (mimeId == mimePhone) {
                scoreClause.append(Phone.NUMBER);
            } else if (mimeId == mimeEmail) {
                scoreClause.append(Email.DATA);
            }
            scoreClause.append("=");
            DatabaseUtils.appendEscapedSQLString(scoreClause, dataRaw);
            scoreClause.append(" THEN 2 ELSE " + Data.IS_PRIMARY + " END) AS " + SCORE);

            final String[] PROJ_PRIMARY = new String[] {
                    DataColumns.CONCRETE_ID,
                    RawContacts.IS_RESTRICTED,
                    scoreClause.toString(),
            };

            final int COL_DATA_ID = 0;
            final int COL_IS_RESTRICTED = 1;
            final int COL_SCORE = 2;

            cursor = db.query(Tables.DATA_JOIN_MIMETYPES_RAW_CONTACTS_CONTACTS, PROJ_PRIMARY,
                    ContactsColumns.CONCRETE_ID + "=" + aggId + " AND " + DataColumns.MIMETYPE_ID
                            + "=" + mimeId, null, null, null, SCORE);

            if (fixOptimal) {
                String colId = null;
                String colIsRestricted = null;
                if (mimeId == mimePhone) {
                    colId = ContactsColumns.OPTIMAL_PRIMARY_PHONE_ID;
                    colIsRestricted = ContactsColumns.OPTIMAL_PRIMARY_PHONE_IS_RESTRICTED;
                } else if (mimeId == mimeEmail) {
                    colId = ContactsColumns.OPTIMAL_PRIMARY_EMAIL_ID;
                    colIsRestricted = ContactsColumns.OPTIMAL_PRIMARY_EMAIL_IS_RESTRICTED;
                }

                // Start by replacing with null, since fixOptimal told us that
                // the previous aggregate values are bad.
                values.putNull(colId);
                values.putNull(colIsRestricted);

                // When finding a new optimal primary, we only care about the
                // highest scoring value, regardless of source.
                if (cursor.moveToFirst()) {
                    final long newOptimal = cursor.getLong(COL_DATA_ID);
                    final long newIsRestricted = cursor.getLong(COL_IS_RESTRICTED);

                    if (newOptimal != 0) {
                        values.put(colId, newOptimal);
                    }
                    if (newIsRestricted != 0) {
                        values.put(colIsRestricted, newIsRestricted);
                    }
                }
            }

            if (fixFallback) {
                String colId = null;
                if (mimeId == mimePhone) {
                    colId = ContactsColumns.FALLBACK_PRIMARY_PHONE_ID;
                } else if (mimeId == mimeEmail) {
                    colId = ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID;
                }

                // Start by replacing with null, since fixFallback told us that
                // the previous aggregate values are bad.
                values.putNull(colId);

                // The best fallback value is the highest scoring data item that
                // hasn't been restricted.
                cursor.moveToPosition(-1);
                while (cursor.moveToNext()) {
                    final boolean isRestricted = (cursor.getInt(COL_IS_RESTRICTED) == 1);
                    if (!isRestricted) {
                        values.put(colId, cursor.getLong(COL_DATA_ID));
                        break;
                    }
                }
            }

            // Push through any contact updates we have
            if (values.size() > 0) {
                db.update(Tables.CONTACTS, values, ContactsColumns.CONCRETE_ID + "=" + aggId,
                        null);
            }
        }

        return dataDeleted;
    }

    /**
     * Inserts an item in the groups table
     */
    private long insertGroup(ContentValues values, Account account) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        ContentValues overriddenValues = new ContentValues(values);
        if (!resolveAccount(overriddenValues, account)) {
            return -1;
        }

        // Replace package with internal mapping
        final String packageName = overriddenValues.getAsString(Groups.RES_PACKAGE);
        if (packageName != null) {
            overriddenValues.put(GroupsColumns.PACKAGE_ID, mOpenHelper.getPackageId(packageName));
        }
        overriddenValues.remove(Groups.RES_PACKAGE);

        return db.insert(Tables.GROUPS, Groups.TITLE, overriddenValues);
    }

    /**
     * Inserts a presence update.
     */
    public long insertPresence(ContentValues values) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        final String handle = values.getAsString(Presence.IM_HANDLE);
        final String protocol = values.getAsString(Presence.IM_PROTOCOL);
        if (TextUtils.isEmpty(handle) || TextUtils.isEmpty(protocol)) {
            throw new IllegalArgumentException("IM_PROTOCOL and IM_HANDLE are required");
        }

        // TODO: generalize to allow other providers to match against email
        boolean matchEmail = GTALK_PROTOCOL_STRING.equals(protocol);

        StringBuilder selection = new StringBuilder();
        String[] selectionArgs;
        if (matchEmail) {
            selection.append("(" + Clauses.WHERE_IM_MATCHES + ") OR ("
                    + Clauses.WHERE_EMAIL_MATCHES + ")");
            selectionArgs = new String[] { protocol, handle, handle };
        } else {
            selection.append(Clauses.WHERE_IM_MATCHES);
            selectionArgs = new String[] { protocol, handle };
        }

        if (values.containsKey(Presence.DATA_ID)) {
            selection.append(" AND " + DataColumns.CONCRETE_ID + "=")
                    .append(values.getAsLong(Presence.DATA_ID));
        }

        if (values.containsKey(Presence.RAW_CONTACT_ID)) {
            selection.append(" AND " + DataColumns.CONCRETE_RAW_CONTACT_ID + "=")
                    .append(values.getAsLong(Presence.RAW_CONTACT_ID));
        }

        selection.append(" AND ").append(getContactsRestrictionExceptions());

        long dataId = -1;
        long rawContactId = -1;

        Cursor cursor = null;
        try {
            cursor = db.query(DataContactsQuery.TABLE, DataContactsQuery.PROJECTION,
                    selection.toString(), selectionArgs, null, null, null);
            if (cursor.moveToFirst()) {
                dataId = cursor.getLong(DataContactsQuery.DATA_ID);
                rawContactId = cursor.getLong(DataContactsQuery.RAW_CONTACT_ID);
            } else {
                // No contact found, return a null URI
                return -1;
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        values.put(Presence.DATA_ID, dataId);
        values.put(Presence.RAW_CONTACT_ID, rawContactId);

        // Insert the presence update
        long presenceId = db.replace(Tables.PRESENCE, null, values);
        return presenceId;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case SYNCSTATE:
                return mOpenHelper.getSyncState().delete(db, selection, selectionArgs);

            case CONTACTS_ID: {
                long contactId = ContentUris.parseId(uri);

                // Remove references to the contact first
                ContentValues values = new ContentValues();
                values.putNull(RawContacts.CONTACT_ID);
                db.update(Tables.RAW_CONTACTS, values,
                        RawContacts.CONTACT_ID + "=" + contactId, null);

                return db.delete(Tables.CONTACTS, BaseColumns._ID + "=" + contactId, null);
            }

            case RAW_CONTACTS_ID: {
                return deleteRawContact(uri);
            }

            case DATA: {
                return deleteData(selection, selectionArgs);
            }

            case DATA_ID: {
                long dataId = ContentUris.parseId(uri);
                return deleteData(dataId);
            }

            case GROUPS_ID: {
                long groupId = ContentUris.parseId(uri);
                final long groupMembershipMimetypeId = mOpenHelper
                        .getMimeTypeId(GroupMembership.CONTENT_ITEM_TYPE);
                int groupsDeleted = db.delete(Tables.GROUPS, Groups._ID + "=" + groupId, null);
                int dataDeleted = db.delete(Tables.DATA, DataColumns.MIMETYPE_ID + "="
                        + groupMembershipMimetypeId + " AND " + GroupMembership.GROUP_ROW_ID + "="
                        + groupId, null);
                mOpenHelper.updateAllVisible();
                return groupsDeleted + dataDeleted;
            }

            case PRESENCE: {
                return db.delete(Tables.PRESENCE, null, null);
            }

            default:
                return mLegacyApiSupport.delete(uri, selection, selectionArgs);
        }
    }

    private int deleteRawContact(Uri uri) {
        boolean permanentDeletion = false;
        String permanent = uri.getQueryParameter(RawContacts.DELETE_PERMANENTLY);
        if (permanent != null && !"false".equals(permanent.toLowerCase())) {
            permanentDeletion = true;
        }

        long rawContactId = ContentUris.parseId(uri);
        return deleteRawContact(rawContactId, permanentDeletion);
    }

    public int deleteRawContact(long rawContactId, boolean permanently) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // TODO delete aggregation exceptions
        mOpenHelper.removeContactIfSingleton(rawContactId);
        if (permanently) {
            db.delete(Tables.PRESENCE, Presence.RAW_CONTACT_ID + "=" + rawContactId, null);
            return db.delete(Tables.RAW_CONTACTS, RawContacts._ID + "=" + rawContactId, null);
        } else {
            mValues.clear();
            mValues.put(RawContacts.DELETED, true);
            mValues.put(RawContacts.AGGREGATION_MODE, RawContacts.AGGREGATION_MODE_DISABLED);
            mValues.putNull(RawContacts.CONTACT_ID);
            return updateRawContact(rawContactId, mValues, null, null);
        }
    }

    private static Account readAccountFromQueryParams(Uri uri) {
        final String name = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
        final String type = uri.getQueryParameter(RawContacts.ACCOUNT_TYPE);
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type)) {
            return null;
        }
        return new Account(name, type);
    }


    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = 0;

        final int match = sUriMatcher.match(uri);
        switch(match) {
            case SYNCSTATE:
                return mOpenHelper.getSyncState().update(db, values, selection, selectionArgs);

            // TODO(emillar): We will want to disallow editing the contacts table at some point.
            case CONTACTS: {
                count = db.update(Tables.CONTACTS, values, selection, selectionArgs);
                break;
            }

            case CONTACTS_ID: {
                count = updateContactData(db, ContentUris.parseId(uri), values);
                break;
            }

            case DATA: {
                count = updateData(values, selection, selectionArgs);
                break;
            }

            case DATA_ID: {
                count = updateData(ContentUris.parseId(uri), values);
                break;
            }

            case RAW_CONTACTS: {
                count = db.update(Tables.RAW_CONTACTS, values, selection, selectionArgs);
                break;
            }

            case RAW_CONTACTS_ID: {
                long rawContactId = ContentUris.parseId(uri);
                count = updateRawContact(rawContactId, values, selection, selectionArgs);
                break;
            }

            case GROUPS: {
                count = db.update(Tables.GROUPS, values, selection, selectionArgs);
                mOpenHelper.updateAllVisible();
                break;
            }

            case GROUPS_ID: {
                long groupId = ContentUris.parseId(uri);
                String selectionWithId = (Groups._ID + "=" + groupId + " ")
                        + (selection == null ? "" : " AND " + selection);
                count = db.update(Tables.GROUPS, values, selectionWithId, selectionArgs);

                // If changing visibility, then update contacts
                if (values.containsKey(Groups.GROUP_VISIBLE)) {
                    mOpenHelper.updateAllVisible();
                }

                break;
            }

            case AGGREGATION_EXCEPTIONS: {
                count = updateAggregationException(db, values);
                break;
            }

            default:
                return mLegacyApiSupport.update(uri, values, selection, selectionArgs);
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(uri, null);
        }
        return count;
    }

    private int updateRawContact(long rawContactId, ContentValues values, String selection,
            String[] selectionArgs) {
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        String selectionWithId = (RawContacts._ID + " = " + rawContactId + " ")
                + (selection == null ? "" : " AND " + selection);
        return db.update(Tables.RAW_CONTACTS, values, selectionWithId, selectionArgs);
    }

    private int updateData(ContentValues values, String selection,
            String[] selectionArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count = 0;
        db.beginTransaction();
        try {

            // Note that the query will return data according to the access restrictions,
            // so we don't need to worry about deleting data we don't have permission to read.
            Cursor c = query(Data.CONTENT_URI, DataIdQuery.COLUMNS, selection, selectionArgs, null);
            try {
                while(c.moveToNext()) {
                    long dataId = c.getLong(DataIdQuery._ID);
                    count += updateData(dataId, values);
                }
            } finally {
                c.close();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return count;
    }

    private int updateData(long dataId, ContentValues values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        mValues.clear();
        mValues.putAll(values);
        mValues.remove(Data._ID);
        mValues.remove(Data.RAW_CONTACT_ID);
        mValues.remove(Data.MIMETYPE);

        String packageName = values.getAsString(Data.RES_PACKAGE);
        if (packageName != null) {
            mValues.remove(Data.RES_PACKAGE);
            mValues.put(DataColumns.PACKAGE_ID, mOpenHelper.getPackageId(packageName));
        }

        boolean containsIsSuperPrimary = mValues.containsKey(Data.IS_SUPER_PRIMARY);
        boolean containsIsPrimary = mValues.containsKey(Data.IS_PRIMARY);

        // Remove primary or super primary values being set to 0. This is disallowed by the
        // content provider.
        if (containsIsSuperPrimary && mValues.getAsInteger(Data.IS_SUPER_PRIMARY) == 0) {
            containsIsSuperPrimary = false;
            mValues.remove(Data.IS_SUPER_PRIMARY);
        }
        if (containsIsPrimary && mValues.getAsInteger(Data.IS_PRIMARY) == 0) {
            containsIsPrimary = false;
            mValues.remove(Data.IS_PRIMARY);
        }

        if (containsIsSuperPrimary) {
            setIsSuperPrimary(dataId);
            setIsPrimary(dataId);

            // Now that we've taken care of setting these, remove them from "values".
            mValues.remove(Data.IS_SUPER_PRIMARY);
            if (containsIsPrimary) {
                mValues.remove(Data.IS_PRIMARY);
            }
        } else if (containsIsPrimary) {
            setIsPrimary(dataId);

            // Now that we've taken care of setting this, remove it from "values".
            mValues.remove(Data.IS_PRIMARY);
        }

        if (mValues.size() > 0) {
            return db.update(Tables.DATA, mValues, Data._ID + " = " + dataId, null);
        }
        return 0;
    }

    private int updateContactData(SQLiteDatabase db, long contactId, ContentValues values) {

        // First update all constituent contacts
        ContentValues optionValues = new ContentValues(5);
        OpenHelper.copyStringValue(optionValues, RawContacts.CUSTOM_RINGTONE,
                values, Contacts.CUSTOM_RINGTONE);
        OpenHelper.copyLongValue(optionValues, RawContacts.SEND_TO_VOICEMAIL,
                values, Contacts.SEND_TO_VOICEMAIL);
        OpenHelper.copyLongValue(optionValues, RawContacts.LAST_TIME_CONTACTED,
                values, Contacts.LAST_TIME_CONTACTED);
        OpenHelper.copyLongValue(optionValues, RawContacts.TIMES_CONTACTED,
                values, Contacts.TIMES_CONTACTED);
        OpenHelper.copyLongValue(optionValues, RawContacts.STARRED,
                values, Contacts.STARRED);

        // Nothing to update - just return
        if (optionValues.size() == 0) {
            return 0;
        }

        db.update(Tables.RAW_CONTACTS, optionValues,
                RawContacts.CONTACT_ID + "=" + contactId, null);
        return db.update(Tables.CONTACTS, values, Contacts._ID + "=" + contactId, null);
    }

    public void updateContactTime(long contactId, long lastTimeContacted) {
        mLastTimeContactedUpdate.bindLong(1, lastTimeContacted);
        mLastTimeContactedUpdate.bindLong(2, contactId);
        mLastTimeContactedUpdate.execute();
    }

    private static class RawContactPair {
        final long rawContactId1;
        final long rawContactId2;

        /**
         * Constructor that ensures that this.rawContactId1 &lt; this.rawContactId2
         */
        public RawContactPair(long rawContactId1, long rawContactId2) {
            if (rawContactId1 < rawContactId2) {
                this.rawContactId1 = rawContactId1;
                this.rawContactId2 = rawContactId2;
            } else {
                this.rawContactId2 = rawContactId1;
                this.rawContactId1 = rawContactId2;
            }
        }
    }

    private int updateAggregationException(SQLiteDatabase db, ContentValues values) {
        int exceptionType = values.getAsInteger(AggregationExceptions.TYPE);
        long contactId = values.getAsInteger(AggregationExceptions.CONTACT_ID);
        long rawContactId = values.getAsInteger(AggregationExceptions.RAW_CONTACT_ID);

        // First, we build a list of contactID-contactID pairs for the given contact and contact.
        ArrayList<RawContactPair> pairs = new ArrayList<RawContactPair>();
        Cursor c = db.query(ContactsQuery.TABLE, ContactsQuery.PROJECTION, RawContacts.CONTACT_ID
                + "=" + contactId, null, null, null, null);
        try {
            while (c.moveToNext()) {
                long aggregatedContactId = c.getLong(ContactsQuery.RAW_CONTACT_ID);
                if (aggregatedContactId != rawContactId) {
                    pairs.add(new RawContactPair(aggregatedContactId, rawContactId));
                }
            }
        } finally {
            c.close();
        }

        // Now we iterate through all contact pairs to see if we need to insert/delete/update
        // the corresponding exception
        ContentValues exceptionValues = new ContentValues(3);
        exceptionValues.put(AggregationExceptions.TYPE, exceptionType);
        for (RawContactPair pair : pairs) {
            final String whereClause =
                    AggregationExceptionColumns.RAW_CONTACT_ID1 + "=" + pair.rawContactId1 + " AND "
                    + AggregationExceptionColumns.RAW_CONTACT_ID2 + "=" + pair.rawContactId2;
            if (exceptionType == AggregationExceptions.TYPE_AUTOMATIC) {
                db.delete(Tables.AGGREGATION_EXCEPTIONS, whereClause, null);
            } else {
                exceptionValues.put(AggregationExceptionColumns.RAW_CONTACT_ID1, pair.rawContactId1);
                exceptionValues.put(AggregationExceptionColumns.RAW_CONTACT_ID2, pair.rawContactId2);
                db.replace(Tables.AGGREGATION_EXCEPTIONS, AggregationExceptions._ID,
                        exceptionValues);
            }
        }

        int aggregationMode = mContactAggregator.markContactForAggregation(rawContactId);
        if (aggregationMode != RawContacts.AGGREGATION_MODE_DISABLED) {
            mContactAggregator.aggregateContact(db, rawContactId);
            if (exceptionType == AggregationExceptions.TYPE_AUTOMATIC
                    || exceptionType == AggregationExceptions.TYPE_KEEP_OUT) {
                mContactAggregator.updateAggregateData(contactId);
            }
        }

        // The return value is fake - we just confirm that we made a change, not count actual
        // rows changed.
        return 1;
    }

    /**
     * Test if a {@link String} value appears in the given list.
     */
    private boolean isContained(String[] array, String value) {
        if (array != null) {
            for (String test : array) {
                if (value.equals(test)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Test if a {@link String} value appears in the given list, and add to the
     * array if the value doesn't already appear.
     */
    private String[] assertContained(String[] array, String value) {
        if (array == null) {
            array = new String[] {value};
        } else if (!isContained(array, value)) {
            String[] newArray = new String[array.length + 1];
            System.arraycopy(array, 0, newArray, 0, array.length);
            newArray[array.length] = value;
            array = newArray;
        }
        return array;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        final SQLiteDatabase db = mOpenHelper.getReadableDatabase();

        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        String groupBy = null;
        String limit = getLimit(uri);

        String contactIdColName = Tables.CONTACTS + "." + Contacts._ID;

        // TODO: Consider writing a test case for RestrictionExceptions when you
        // write a new query() block to make sure it protects restricted data.
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case SYNCSTATE:
                return mOpenHelper.getSyncState().query(db, projection, selection,  selectionArgs,
                        sortOrder);

            case CONTACTS: {
                qb.setTables(Tables.CONTACTS);
                applyAggregateRestrictionExceptions(qb);
                applyAggregatePrimaryRestrictionExceptions(sContactsProjectionMap);
                qb.setProjectionMap(sContactsProjectionMap);
                break;
            }

            case CONTACTS_ID: {
                long aggId = ContentUris.parseId(uri);
                qb.setTables(Tables.CONTACTS);
                qb.appendWhere(ContactsColumns.CONCRETE_ID + "=" + aggId + " AND ");
                applyAggregateRestrictionExceptions(qb);
                applyAggregatePrimaryRestrictionExceptions(sContactsProjectionMap);
                qb.setProjectionMap(sContactsProjectionMap);
                break;
            }

            case CONTACTS_SUMMARY: {
                // TODO: join into social status tables
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                applyAggregateRestrictionExceptions(qb);
                applyAggregatePrimaryRestrictionExceptions(sContactsSummaryProjectionMap);
                projection = assertContained(projection, Contacts.PRIMARY_PHONE_ID);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                groupBy = contactIdColName;
                break;
            }

            case CONTACTS_SUMMARY_ID: {
                // TODO: join into social status tables
                long aggId = ContentUris.parseId(uri);
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                qb.appendWhere(ContactsColumns.CONCRETE_ID + "=" + aggId + " AND ");
                applyAggregateRestrictionExceptions(qb);
                applyAggregatePrimaryRestrictionExceptions(sContactsSummaryProjectionMap);
                projection = assertContained(projection, Contacts.PRIMARY_PHONE_ID);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                groupBy = contactIdColName;
                break;
            }

            case CONTACTS_SUMMARY_FILTER: {
                // TODO: filter query based on callingUid
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                if (uri.getPathSegments().size() > 2) {
                    qb.appendWhere(buildContactLookupWhereClause(uri.getLastPathSegment()));
                }
                groupBy = contactIdColName;
                break;
            }

            case CONTACTS_SUMMARY_STREQUENT_FILTER:
            case CONTACTS_SUMMARY_STREQUENT: {
                // Build the first query for starred
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                if (match == CONTACTS_SUMMARY_STREQUENT_FILTER
                        && uri.getPathSegments().size() > 3) {
                    qb.appendWhere(buildContactLookupWhereClause(uri.getLastPathSegment()));
                }
                final String starredQuery = qb.buildQuery(projection, Contacts.STARRED + "=1",
                        null, contactIdColName, null, null,
                        null /* limit */);

                // Build the second query for frequent
                qb = new SQLiteQueryBuilder();
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                if (match == CONTACTS_SUMMARY_STREQUENT_FILTER
                        && uri.getPathSegments().size() > 3) {
                    qb.appendWhere(buildContactLookupWhereClause(uri.getLastPathSegment()));
                }
                final String frequentQuery = qb.buildQuery(projection,
                        Contacts.TIMES_CONTACTED + " > 0 AND (" + Contacts.STARRED
                        + " = 0 OR " + Contacts.STARRED + " IS NULL)",
                        null, contactIdColName, null, null, null);

                // Put them together
                final String query = qb.buildUnionQuery(new String[] {starredQuery, frequentQuery},
                        STREQUENT_ORDER_BY, STREQUENT_LIMIT);
                Cursor c = db.rawQueryWithFactory(null, query, null,
                        Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);

                if ((c != null) && !isTemporary()) {
                    c.setNotificationUri(getContext().getContentResolver(),
                            ContactsContract.AUTHORITY_URI);
                }
                return c;
            }

            case CONTACTS_SUMMARY_GROUP: {
                qb.setTables(Tables.CONTACTS_JOIN_PRESENCE_PRIMARY_PHONE);
                applyAggregateRestrictionExceptions(qb);
                applyAggregatePrimaryRestrictionExceptions(sContactsSummaryProjectionMap);
                projection = assertContained(projection, Contacts.PRIMARY_PHONE_ID);
                qb.setProjectionMap(sContactsSummaryProjectionMap);
                if (uri.getPathSegments().size() > 2) {
                    qb.appendWhere(" AND " + sContactsInGroupSelect);
                    selectionArgs = appendGroupArg(selectionArgs, uri.getLastPathSegment());
                }
                groupBy = contactIdColName;
                break;
            }

            case CONTACTS_DATA: {
                long aggId = Long.parseLong(uri.getPathSegments().get(1));
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_CONTACTS_GROUPS);
                qb.setProjectionMap(sDataContactsGroupsContactProjectionMap);
                qb.appendWhere(RawContacts.CONTACT_ID + "=" + aggId + " AND ");
                applyDataRestrictionExceptions(qb);
                break;
            }

            case CONTACTS_RAW_CONTACTS: {
                long contactId = Long.parseLong(uri.getPathSegments().get(1));
                qb.setTables(Tables.RAW_CONTACTS_JOIN_CONTACTS);
                qb.setProjectionMap(sRawContactsContactsProjectionMap);
                qb.appendWhere(RawContacts.CONTACT_ID + "=" + contactId + " AND ");
                applyDataRestrictionExceptions(qb);
                break;
            }

            case PHONES_FILTER: {
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_CONTACTS);
                qb.setProjectionMap(sDataContactsContactProjectionMap);
                qb.appendWhere(Data.MIMETYPE + " = '" + Phone.CONTENT_ITEM_TYPE + "'");
                if (uri.getPathSegments().size() > 2) {
                    qb.appendWhere(" AND " + buildContactLookupWhereClause(
                            uri.getLastPathSegment()));
                }
                break;
            }

            case PHONES: {
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_CONTACTS);
                qb.setProjectionMap(sDataContactsContactProjectionMap);
                qb.appendWhere(Data.MIMETYPE + " = \"" + Phone.CONTENT_ITEM_TYPE + "\"");
                break;
            }

            case POSTALS: {
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_CONTACTS);
                qb.setProjectionMap(sDataContactsContactProjectionMap);
                qb.appendWhere(Data.MIMETYPE + " = \"" + StructuredPostal.CONTENT_ITEM_TYPE + "\"");
                break;
            }

            case RAW_CONTACTS: {
                qb.setTables(Tables.RAW_CONTACTS);
                qb.setProjectionMap(sRawContactsProjectionMap);
                applyContactsRestrictionExceptions(qb);
                break;
            }

            case RAW_CONTACTS_ID: {
                long rawContactId = ContentUris.parseId(uri);
                qb.setTables(Tables.RAW_CONTACTS);
                qb.setProjectionMap(sRawContactsProjectionMap);
                qb.appendWhere(RawContactsColumns.CONCRETE_ID + "=" + rawContactId + " AND ");
                applyContactsRestrictionExceptions(qb);
                break;
            }

            case RAW_CONTACTS_DATA: {
                long rawContactId = Long.parseLong(uri.getPathSegments().get(1));
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_GROUPS);
                qb.setProjectionMap(sDataContactsGroupsProjectionMap);
                qb.appendWhere(Data.RAW_CONTACT_ID + "=" + rawContactId + " AND ");
                applyDataRestrictionExceptions(qb);
                break;
            }

            case CONTACTS_FILTER_EMAIL: {
                // TODO: filter query based on callingUid
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_CONTACTS);
                qb.setProjectionMap(sDataContactsProjectionMap);
                qb.appendWhere(Data.MIMETYPE + "='" + CommonDataKinds.Email.CONTENT_ITEM_TYPE + "'");
                qb.appendWhere(" AND " + CommonDataKinds.Email.DATA + "=");
                qb.appendWhereEscapeString(uri.getPathSegments().get(2));
                break;
            }

            case DATA: {
                final String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
                final String accountType = uri.getQueryParameter(RawContacts.ACCOUNT_TYPE);
                if (!TextUtils.isEmpty(accountName)) {
                    qb.appendWhere(RawContactsColumns.CONCRETE_ACCOUNT_NAME + "="
                            + DatabaseUtils.sqlEscapeString(accountName) + " AND "
                            + RawContactsColumns.CONCRETE_ACCOUNT_TYPE + "="
                            + DatabaseUtils.sqlEscapeString(accountType) + " AND ");
                }
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_GROUPS);
                qb.setProjectionMap(sDataGroupsProjectionMap);
                applyDataRestrictionExceptions(qb);
                break;
            }

            case DATA_ID: {
                qb.setTables(Tables.DATA_JOIN_PACKAGES_MIMETYPES_RAW_CONTACTS_GROUPS);
                qb.setProjectionMap(sDataGroupsProjectionMap);
                qb.appendWhere(DataColumns.CONCRETE_ID + "=" + ContentUris.parseId(uri) + " AND ");
                applyDataRestrictionExceptions(qb);
                break;
            }

            case PHONE_LOOKUP: {
                // TODO: filter query based on callingUid
                if (TextUtils.isEmpty(sortOrder)) {
                    // Default the sort order to something reasonable so we get consistent
                    // results when callers don't request an ordering
                    sortOrder = Data.RAW_CONTACT_ID;
                }

                final String number = uri.getLastPathSegment();
                OpenHelper.buildPhoneLookupQuery(qb, number);
                qb.setProjectionMap(sDataContactsProjectionMap);
                break;
            }

            case GROUPS: {
                qb.setTables(Tables.GROUPS_JOIN_PACKAGES);
                qb.setProjectionMap(sGroupsProjectionMap);
                break;
            }

            case GROUPS_ID: {
                long groupId = ContentUris.parseId(uri);
                qb.setTables(Tables.GROUPS_JOIN_PACKAGES);
                qb.setProjectionMap(sGroupsProjectionMap);
                qb.appendWhere(GroupsColumns.CONCRETE_ID + "=" + groupId);
                break;
            }

            case GROUPS_SUMMARY: {
                qb.setTables(Tables.GROUPS_JOIN_PACKAGES_DATA_RAW_CONTACTS_CONTACTS);
                qb.setProjectionMap(sGroupsSummaryProjectionMap);
                groupBy = GroupsColumns.CONCRETE_ID;
                break;
            }

            case AGGREGATION_EXCEPTIONS: {
                qb.setTables(Tables.AGGREGATION_EXCEPTIONS_JOIN_RAW_CONTACTS);
                qb.setProjectionMap(sAggregationExceptionsProjectionMap);
                break;
            }

            case AGGREGATION_SUGGESTIONS: {
                long contactId = Long.parseLong(uri.getPathSegments().get(1));

                // TODO drop MAX_SUGGESTIONS in favor of LIMIT
                final String maxSuggestionsParam =
                        uri.getQueryParameter(AggregationSuggestions.MAX_SUGGESTIONS);

                final int maxSuggestions;
                if (maxSuggestionsParam != null) {
                    maxSuggestions = Integer.parseInt(maxSuggestionsParam);
                } else {
                    maxSuggestions = DEFAULT_MAX_SUGGESTIONS;
                }

                return mContactAggregator.queryAggregationSuggestions(contactId, projection,
                        sContactsProjectionMap, maxSuggestions);
            }

            case PRESENCE: {
                qb.setTables(Tables.PRESENCE);
                qb.setProjectionMap(sPresenceProjectionMap);
                break;
            }

            case PRESENCE_ID: {
                qb.setTables(Tables.PRESENCE);
                qb.setProjectionMap(sPresenceProjectionMap);
                qb.appendWhere(Presence._ID + "=" + ContentUris.parseId(uri));
                break;
            }

            case SEARCH_SUGGESTIONS: {
                return handleSearchSuggestionsQuery(uri, limit);
            }

            case SEARCH_SHORTCUT: {
                // TODO
                break;
            }

            default:
                return mLegacyApiSupport.query(uri, projection, selection, selectionArgs,
                        sortOrder, limit);
        }

        // Perform the query and set the notification uri
        final Cursor c = qb.query(db, projection, selection, selectionArgs,
                groupBy, null, sortOrder, limit);
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), ContactsContract.AUTHORITY_URI);
        }
        return c;
    }

    /**
     * Gets the value of the "limit" URI query parameter.
     *
     * @return A string containing a non-negative integer, or <code>null</code> if
     *         the parameter is not set, or is set to an invalid value.
     */
    private String getLimit(Uri url) {
        String limitParam = url.getQueryParameter("limit");
        if (limitParam == null) {
            return null;
        }
        // make sure that the limit is a non-negative integer
        try {
            int l = Integer.parseInt(limitParam);
            if (l < 0) {
                Log.w(TAG, "Invalid limit parameter: " + limitParam);
                return null;
            }
            return String.valueOf(l);
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Invalid limit parameter: " + limitParam);
            return null;
        }
    }


    public Cursor handleSearchSuggestionsQuery(Uri url, String limit) {
        if (url.getPathSegments().size() <= 1) {
            return null;
        }

        final String searchClause = url.getLastPathSegment();
        if (TextUtils.isDigitsOnly(searchClause)) {
            return buildCursorForSearchSuggestionsBasedOnPhoneNumber(searchClause);
        } else {
            return buildCursorForSearchSuggestionsBasedOnName(searchClause, limit);
        }
    }

    private static final String[] SEARCH_SUGGESTIONS_BASED_ON_PHONE_NUMBER_COLUMNS = {
            "_id",
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
    };

    private Cursor buildCursorForSearchSuggestionsBasedOnPhoneNumber(String searchClause) {
        Resources r = getContext().getResources();
        String s;
        int i;

        ArrayList<Object> dialNumber = new ArrayList<Object>();
        dialNumber.add(0);  // _id
        s = r.getString(com.android.internal.R.string.dial_number_using, searchClause);
        i = s.indexOf('\n');
        if (i < 0) {
            dialNumber.add(s);
            dialNumber.add("");
        } else {
            dialNumber.add(s.substring(0, i));
            dialNumber.add(s.substring(i + 1));
        }
        dialNumber.add(String.valueOf(com.android.internal.R.drawable.call_contact));
        dialNumber.add("tel:" + searchClause);
        dialNumber.add(Intents.SEARCH_SUGGESTION_DIAL_NUMBER_CLICKED);
        dialNumber.add(null);

        ArrayList<Object> createContact = new ArrayList<Object>();
        createContact.add(1);  // _id
        s = r.getString(com.android.internal.R.string.create_contact_using, searchClause);
        i = s.indexOf('\n');
        if (i < 0) {
            createContact.add(s);
            createContact.add("");
        } else {
            createContact.add(s.substring(0, i));
            createContact.add(s.substring(i + 1));
        }
        createContact.add(String.valueOf(com.android.internal.R.drawable.create_contact));
        createContact.add("tel:" + searchClause);
        createContact.add(Intents.SEARCH_SUGGESTION_CREATE_CONTACT_CLICKED);
        createContact.add(SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT);

        @SuppressWarnings({"unchecked"}) ArrayList<ArrayList> rows = new ArrayList<ArrayList>();
        rows.add(dialNumber);
        rows.add(createContact);

        return new ArrayListCursor(SEARCH_SUGGESTIONS_BASED_ON_PHONE_NUMBER_COLUMNS, rows);
    }

    private interface SearchSuggestionQuery {
        public static final String JOIN_RAW_CONTACTS =
                " JOIN raw_contacts ON (data.raw_contact_id = raw_contacts._id) ";

        public static final String JOIN_CONTACTS =
                " JOIN contacts ON (raw_contacts.contact_id = contacts._id)";

        public static final String JOIN_MIMETYPES =
                " JOIN mimetypes ON (data.mimetype_id = mimetypes._id AND mimetypes.mimetype IN ('"
                + StructuredName.CONTENT_ITEM_TYPE + "','" + Email.CONTENT_ITEM_TYPE + "','"
                + Phone.CONTENT_ITEM_TYPE + "','" + Organization.CONTENT_ITEM_TYPE + "','"
                + Photo.CONTENT_ITEM_TYPE + "','" + GroupMembership.CONTENT_ITEM_TYPE + "')) ";

        // TODO join with groups and ensure that suggestions are from the My Contacts group
        public static final String JOIN_GROUPS = " JOIN groups ON (mimetypes.mimetype='"
                + GroupMembership.CONTENT_ITEM_TYPE + "' " + " AND groups._id = data."
                + GroupMembership.GROUP_ROW_ID + ") ";

        public static final String TABLE = "data " + JOIN_RAW_CONTACTS + JOIN_MIMETYPES
                + JOIN_CONTACTS;

        public static final String PRESENCE_SQL = "(SELECT MAX(" + Presence.PRESENCE_STATUS
                + ") FROM " + Tables.PRESENCE + " WHERE " + Tables.PRESENCE + "."
                + Presence.RAW_CONTACT_ID + "=" + RawContactsColumns.CONCRETE_ID + ")";

        public static final String[] COLUMNS = {
            ContactsColumns.CONCRETE_ID + " AS " + Contacts._ID,
            ContactsColumns.CONCRETE_DISPLAY_NAME + " AS " + Contacts.DISPLAY_NAME,
            PRESENCE_SQL + " AS " + Contacts.PRESENCE_STATUS,
            DataColumns.CONCRETE_ID + " AS data_id",
            MimetypesColumns.MIMETYPE,
            Data.IS_SUPER_PRIMARY,
            Data.DATA2,
        };

        public static final int CONTACT_ID = 0;
        public static final int DISPLAY_NAME = 1;
        public static final int PRESENCE_STATUS = 2;
        public static final int DATA_ID = 3;
        public static final int MIMETYPE = 4;
        public static final int IS_SUPER_PRIMARY = 5;
        public static final int DATA2 = 6;
    }

    private static class SearchSuggestion {
        String contactId;
        boolean titleIsName;
        String organization;
        String email;
        String phoneNumber;
        String photoUri;
        String normalizedName;
        int presence = -1;
        boolean processed;
        String text1;
        String text2;
        String icon1;
        String icon2;

        public SearchSuggestion(long contactId) {
            this.contactId = String.valueOf(contactId);
        }

        private void process() {
            if (processed) {
                return;
            }

            boolean hasOrganization = !TextUtils.isEmpty(organization);
            boolean hasEmail = !TextUtils.isEmpty(email);
            boolean hasPhone = !TextUtils.isEmpty(phoneNumber);

            boolean titleIsOrganization = !titleIsName && hasOrganization;
            boolean titleIsEmail = !titleIsName && !titleIsOrganization && hasEmail;
            boolean titleIsPhone = !titleIsName && !titleIsOrganization && !titleIsEmail
                    && hasPhone;

            if (!titleIsOrganization && hasOrganization) {
                text2 = organization;
            } else if (!titleIsEmail && hasEmail) {
                text2 = email;
            } else if (!titleIsPhone && hasPhone) {
                text2 = phoneNumber;
            }

            if (photoUri != null) {
                icon1 = photoUri;
            } else {
                icon1 = String.valueOf(com.android.internal.R.drawable.ic_contact_picture);
            }

            if (presence != -1) {
                icon2 = String.valueOf(Presence.getPresenceIconResourceId(presence));
            }

            processed = true;
        }

        public String getSortKey() {
            if (normalizedName == null) {
                process();
                normalizedName = text1 == null ? "" : NameNormalizer.normalize(text1);
            }
            return normalizedName;
        }

        @SuppressWarnings({"unchecked"})
        public ArrayList asList() {
            process();

            ArrayList<Object> list = new ArrayList<Object>();
            list.add(contactId);
            list.add(text1);
            list.add(text2);
            list.add(icon1);
            list.add(icon2);
            list.add(contactId);
            list.add(contactId);
            return list;
        }
    }

    private static final String[] SEARCH_SUGGESTIONS_BASED_ON_NAME_COLUMNS = {
            "_id",
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_ICON_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
            SearchManager.SUGGEST_COLUMN_SHORTCUT_ID,
    };

    private Cursor buildCursorForSearchSuggestionsBasedOnName(String searchClause, String limit) {
        ArrayList<SearchSuggestion> suggestionList = new ArrayList<SearchSuggestion>();
        HashMap<Long, SearchSuggestion> suggestionMap = new HashMap<Long, SearchSuggestion>();

        StringBuilder selection = new StringBuilder();
        selection.append(getContactsRestrictionExceptions());
        selection.append(" AND " + DataColumns.CONCRETE_RAW_CONTACT_ID + " IN ");
        appendRawContactsByFilterAsNestedQuery(selection, searchClause, limit);

        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor c = db.query(true, SearchSuggestionQuery.TABLE,
                SearchSuggestionQuery.COLUMNS, selection.toString(), null, null, null, null, null);
        try {
            while (c.moveToNext()) {

                long contactId = c.getLong(SearchSuggestionQuery.CONTACT_ID);
                SearchSuggestion suggestion = suggestionMap.get(contactId);
                if (suggestion == null) {
                    suggestion = new SearchSuggestion(contactId);
                    suggestionList.add(suggestion);
                    suggestionMap.put(contactId, suggestion);
                }

                boolean isSuperPrimary = c.getInt(SearchSuggestionQuery.IS_SUPER_PRIMARY) != 0;
                suggestion.text1 = c.getString(SearchSuggestionQuery.DISPLAY_NAME);

                if (!c.isNull(SearchSuggestionQuery.PRESENCE_STATUS)) {
                    suggestion.presence = c.getInt(SearchSuggestionQuery.PRESENCE_STATUS);
                }

                String mimetype = c.getString(SearchSuggestionQuery.MIMETYPE);
                if (StructuredName.CONTENT_ITEM_TYPE.equals(mimetype)) {
                    suggestion.titleIsName = true;
                } else if (Photo.CONTENT_ITEM_TYPE.equals(mimetype)) {
                    if (isSuperPrimary || suggestion.photoUri == null) {

                        // TODO introduce a dedicate URI for contact photo: /contact/#/photo
                        long dataId = c.getLong(SearchSuggestionQuery.DATA_ID);
                        suggestion.photoUri =
                                ContentUris.withAppendedId(Data.CONTENT_URI, dataId).toString();
                    }
                } else if (Organization.CONTENT_ITEM_TYPE.equals(mimetype)) {
                    if (isSuperPrimary || suggestion.organization == null) {
                        suggestion.organization = c.getString(SearchSuggestionQuery.DATA2);
                    }
                } else if (Email.CONTENT_ITEM_TYPE.equals(mimetype)) {
                    if (isSuperPrimary || suggestion.email == null) {
                        suggestion.email = c.getString(SearchSuggestionQuery.DATA2);
                    }
                } else if (Phone.CONTENT_ITEM_TYPE.equals(mimetype)) {
                    if (isSuperPrimary || suggestion.phoneNumber == null) {
                        suggestion.phoneNumber = c.getString(SearchSuggestionQuery.DATA2);
                    }
                }
            }
        } finally {
            c.close();
        }

        Collections.sort(suggestionList, new Comparator<SearchSuggestion>() {
            public int compare(SearchSuggestion row1, SearchSuggestion row2) {
                return row1.getSortKey().compareTo(row2.getSortKey());
            }
        });

        @SuppressWarnings({"unchecked"}) ArrayList<ArrayList> rows = new ArrayList<ArrayList>();
        for (int i = 0; i < suggestionList.size(); i++) {
            rows.add(suggestionList.get(i).asList());
        }

        return new ArrayListCursor(SEARCH_SUGGESTIONS_BASED_ON_NAME_COLUMNS, rows);
    }

    /**
     * List of package names with access to {@link RawContacts#IS_RESTRICTED} data.
     */
    private static final String[] sAllowedPackages = new String[] {
        "com.android.contacts",
        "com.facebook",
    };

    /**
     * Check if {@link Binder#getCallingUid()} should be allowed access to
     * {@link RawContacts#IS_RESTRICTED} data.
     */
    private boolean hasRestrictedAccess() {
        final PackageManager pm = getContext().getPackageManager();
        final String[] callerPackages = pm.getPackagesForUid(Binder.getCallingUid());

        // Has restricted access if caller matches any packages
        for (String callerPackage : callerPackages) {
            for (String allowedPackage : sAllowedPackages) {
                if (allowedPackage.equals(callerPackage)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Restrict selection of {@link Contacts} to only public ones, or those
     * the caller has been granted an exception to.
     */
    private void applyAggregateRestrictionExceptions(SQLiteQueryBuilder qb) {
        if (hasRestrictedAccess()) {
            qb.appendWhere("1");
        } else {
            qb.appendWhere(ContactsColumns.SINGLE_IS_RESTRICTED + "=0");
        }
    }

    /**
     * Find any exceptions that have been granted to the calling process, and
     * add projections to correctly select {@link Contacts#PRIMARY_PHONE_ID}
     * and {@link Contacts#PRIMARY_EMAIL_ID}.
     */
    private void applyAggregatePrimaryRestrictionExceptions(HashMap<String, String> projection) {
        String projectionPhone;
        String projectionEmail;

        if (hasRestrictedAccess()) {
            // With restricted access, always give optimal values
            projectionPhone = ContactsColumns.OPTIMAL_PRIMARY_PHONE_ID + " AS "
                    + Contacts.PRIMARY_PHONE_ID;
            projectionEmail = ContactsColumns.OPTIMAL_PRIMARY_EMAIL_ID + " AS "
                    + Contacts.PRIMARY_EMAIL_ID;
        } else {
            // With general access, always give fallback values
            projectionPhone = ContactsColumns.FALLBACK_PRIMARY_PHONE_ID + " AS "
                    + Contacts.PRIMARY_PHONE_ID;
            projectionEmail = ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID + " AS "
                    + Contacts.PRIMARY_EMAIL_ID;
        }

        projection.remove(Contacts.PRIMARY_PHONE_ID);
        projection.put(Contacts.PRIMARY_PHONE_ID, projectionPhone);

        projection.remove(Contacts.PRIMARY_EMAIL_ID);
        projection.put(Contacts.PRIMARY_EMAIL_ID, projectionEmail);
    }

    /**
     * Find any exceptions that have been granted to the
     * {@link Binder#getCallingUid()}, and add a limiting clause to the given
     * {@link SQLiteQueryBuilder} to hide restricted data.
     */
    private void applyContactsRestrictionExceptions(SQLiteQueryBuilder qb) {
        qb.appendWhere(getContactsRestrictionExceptions());
    }

    private String getContactsRestrictionExceptions() {
        if (hasRestrictedAccess()) {
            return "1";
        } else {
            return RawContacts.IS_RESTRICTED + "=0";
        }
    }

    public String getContactsRestrictionExceptionAsNestedQuery(String contactIdColumn) {
        if (hasRestrictedAccess()) {
            return "1";
        } else {
            return "(SELECT " + RawContacts.IS_RESTRICTED + " FROM " + Tables.RAW_CONTACTS
                    + " WHERE " + RawContactsColumns.CONCRETE_ID + "=" + contactIdColumn + ")=0";
        }
    }

    /**
     * Find any exceptions that have been granted to the
     * {@link Binder#getCallingUid()}, and add a limiting clause to the given
     * {@link SQLiteQueryBuilder} to hide restricted data.
     */
    void applyDataRestrictionExceptions(SQLiteQueryBuilder qb) {
        applyContactsRestrictionExceptions(qb);
    }

    /**
     * An implementation of EntityIterator that joins the contacts and data tables
     * and consumes all the data rows for a contact in order to build the Entity for a contact.
     */
    private static class ContactsEntityIterator implements EntityIterator {
        private final Cursor mEntityCursor;
        private volatile boolean mIsClosed;

        private static final String[] DATA_KEYS = new String[]{
                "data1",
                "data2",
                "data3",
                "data4",
                "data5",
                "data6",
                "data7",
                "data8",
                "data9",
                "data10",
                "data11",
                "data12",
                "data13",
                "data14",
                "data15"};

        private static final String[] PROJECTION = new String[]{
                RawContacts.ACCOUNT_NAME,
                RawContacts.ACCOUNT_TYPE,
                RawContacts.SOURCE_ID,
                RawContacts.VERSION,
                RawContacts.DIRTY,
                RawContacts.Data._ID,
                RawContacts.Data.RES_PACKAGE,
                RawContacts.Data.MIMETYPE,
                RawContacts.Data.DATA1,
                RawContacts.Data.DATA2,
                RawContacts.Data.DATA3,
                RawContacts.Data.DATA4,
                RawContacts.Data.DATA5,
                RawContacts.Data.DATA6,
                RawContacts.Data.DATA7,
                RawContacts.Data.DATA8,
                RawContacts.Data.DATA9,
                RawContacts.Data.DATA10,
                RawContacts.Data.DATA11,
                RawContacts.Data.DATA12,
                RawContacts.Data.DATA13,
                RawContacts.Data.DATA14,
                RawContacts.Data.DATA15,
                RawContacts.Data.RAW_CONTACT_ID,
                RawContacts.Data.IS_PRIMARY,
                RawContacts.Data.DATA_VERSION,
                GroupMembership.GROUP_SOURCE_ID};

        private static final int COLUMN_ACCOUNT_NAME = 0;
        private static final int COLUMN_ACCOUNT_TYPE = 1;
        private static final int COLUMN_SOURCE_ID = 2;
        private static final int COLUMN_VERSION = 3;
        private static final int COLUMN_DIRTY = 4;
        private static final int COLUMN_DATA_ID = 5;
        private static final int COLUMN_RES_PACKAGE = 6;
        private static final int COLUMN_MIMETYPE = 7;
        private static final int COLUMN_DATA1 = 8;
        private static final int COLUMN_CONTACT_ID = 23;
        private static final int COLUMN_IS_PRIMARY = 24;
        private static final int COLUMN_DATA_VERSION = 25;
        private static final int COLUMN_GROUP_SOURCE_ID = 26;

        public ContactsEntityIterator(ContactsProvider2 provider, String contactsIdString, Uri uri,
                String selection, String[] selectionArgs, String sortOrder) {
            mIsClosed = false;

            final String updatedSortOrder = (sortOrder == null)
                    ? RawContacts.Data.RAW_CONTACT_ID
                    : (RawContacts.Data.RAW_CONTACT_ID + "," + sortOrder);

            final SQLiteDatabase db = provider.mOpenHelper.getReadableDatabase();
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables(Tables.CONTACT_ENTITIES);
            if (contactsIdString != null) {
                qb.appendWhere(Data.RAW_CONTACT_ID + "=" + contactsIdString);
            }
            final String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
            final String accountType = uri.getQueryParameter(RawContacts.ACCOUNT_TYPE);
            if (!TextUtils.isEmpty(accountName)) {
                qb.appendWhere(RawContacts.ACCOUNT_NAME + "="
                        + DatabaseUtils.sqlEscapeString(accountName) + " AND "
                        + RawContacts.ACCOUNT_TYPE + "="
                        + DatabaseUtils.sqlEscapeString(accountType));
            }
            mEntityCursor = qb.query(db, PROJECTION, selection, selectionArgs,
                    null, null, updatedSortOrder);
            mEntityCursor.moveToFirst();
        }

        public void close() {
            if (mIsClosed) {
                throw new IllegalStateException("closing when already closed");
            }
            mIsClosed = true;
            mEntityCursor.close();
        }

        public boolean hasNext() throws RemoteException {
            if (mIsClosed) {
                throw new IllegalStateException("calling hasNext() when the iterator is closed");
            }

            return !mEntityCursor.isAfterLast();
        }

        public Entity next() throws RemoteException {
            if (mIsClosed) {
                throw new IllegalStateException("calling next() when the iterator is closed");
            }
            if (!hasNext()) {
                throw new IllegalStateException("you may only call next() if hasNext() is true");
            }

            final SQLiteCursor c = (SQLiteCursor) mEntityCursor;

            final long rawContactId = c.getLong(COLUMN_CONTACT_ID);

            // we expect the cursor is already at the row we need to read from
            ContentValues contactValues = new ContentValues();
            contactValues.put(RawContacts.ACCOUNT_NAME, c.getString(COLUMN_ACCOUNT_NAME));
            contactValues.put(RawContacts.ACCOUNT_TYPE, c.getString(COLUMN_ACCOUNT_TYPE));
            contactValues.put(RawContacts._ID, rawContactId);
            contactValues.put(RawContacts.DIRTY, c.getLong(COLUMN_DIRTY));
            contactValues.put(RawContacts.VERSION, c.getLong(COLUMN_VERSION));
            contactValues.put(RawContacts.SOURCE_ID, c.getString(COLUMN_SOURCE_ID));
            Entity contact = new Entity(contactValues);

            // read data rows until the contact id changes
            do {
                if (rawContactId != c.getLong(COLUMN_CONTACT_ID)) {
                    break;
                }
                // add the data to to the contact
                ContentValues dataValues = new ContentValues();
                dataValues.put(RawContacts.Data._ID, c.getString(COLUMN_DATA_ID));
                dataValues.put(RawContacts.Data.RES_PACKAGE, c.getString(COLUMN_RES_PACKAGE));
                dataValues.put(RawContacts.Data.MIMETYPE, c.getString(COLUMN_MIMETYPE));
                dataValues.put(RawContacts.Data.IS_PRIMARY, c.getString(COLUMN_IS_PRIMARY));
                dataValues.put(RawContacts.Data.DATA_VERSION, c.getLong(COLUMN_DATA_VERSION));
                if (!c.isNull(COLUMN_GROUP_SOURCE_ID)) {
                    dataValues.put(GroupMembership.GROUP_SOURCE_ID,
                            c.getString(COLUMN_GROUP_SOURCE_ID));
                }
                dataValues.put(RawContacts.Data.DATA_VERSION, c.getLong(COLUMN_DATA_VERSION));
                for (int i = 0; i < 10; i++) {
                    final int columnIndex = i + COLUMN_DATA1;
                    String key = DATA_KEYS[i];
                    if (c.isNull(columnIndex)) {
                        // don't put anything
                    } else if (c.isLong(columnIndex)) {
                        dataValues.put(key, c.getLong(columnIndex));
                    } else if (c.isFloat(columnIndex)) {
                        dataValues.put(key, c.getFloat(columnIndex));
                    } else if (c.isString(columnIndex)) {
                        dataValues.put(key, c.getString(columnIndex));
                    } else if (c.isBlob(columnIndex)) {
                        dataValues.put(key, c.getBlob(columnIndex));
                    }
                }
                contact.addSubValue(Data.CONTENT_URI, dataValues);
            } while (mEntityCursor.moveToNext());

            return contact;
        }
    }

    /**
     * An implementation of EntityIterator that joins the contacts and data tables
     * and consumes all the data rows for a contact in order to build the Entity for a contact.
     */
    private static class GroupsEntityIterator implements EntityIterator {
        private final Cursor mEntityCursor;
        private volatile boolean mIsClosed;

        private static final String[] PROJECTION = new String[]{
                Groups._ID,
                Groups.ACCOUNT_NAME,
                Groups.ACCOUNT_TYPE,
                Groups.SOURCE_ID,
                Groups.DIRTY,
                Groups.VERSION,
                Groups.RES_PACKAGE,
                Groups.TITLE,
                Groups.TITLE_RES,
                Groups.GROUP_VISIBLE};

        private static final int COLUMN_ID = 0;
        private static final int COLUMN_ACCOUNT_NAME = 1;
        private static final int COLUMN_ACCOUNT_TYPE = 2;
        private static final int COLUMN_SOURCE_ID = 3;
        private static final int COLUMN_DIRTY = 4;
        private static final int COLUMN_VERSION = 5;
        private static final int COLUMN_RES_PACKAGE = 6;
        private static final int COLUMN_TITLE = 7;
        private static final int COLUMN_TITLE_RES = 8;
        private static final int COLUMN_GROUP_VISIBLE = 9;

        public GroupsEntityIterator(ContactsProvider2 provider, String groupIdString, Uri uri,
                String selection, String[] selectionArgs, String sortOrder) {
            mIsClosed = false;

            final String updatedSortOrder = (sortOrder == null)
                    ? Groups._ID
                    : (Groups._ID + "," + sortOrder);

            final SQLiteDatabase db = provider.mOpenHelper.getReadableDatabase();
            final SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables(Tables.GROUPS_JOIN_PACKAGES);
            qb.setProjectionMap(sGroupsProjectionMap);
            if (groupIdString != null) {
                qb.appendWhere(Groups._ID + "=" + groupIdString);
            }
            final String accountName = uri.getQueryParameter(Groups.ACCOUNT_NAME);
            final String accountType = uri.getQueryParameter(Groups.ACCOUNT_TYPE);
            if (!TextUtils.isEmpty(accountName)) {
                qb.appendWhere(Groups.ACCOUNT_NAME + "="
                        + DatabaseUtils.sqlEscapeString(accountName) + " AND "
                        + Groups.ACCOUNT_TYPE + "="
                        + DatabaseUtils.sqlEscapeString(accountType));
            }
            mEntityCursor = qb.query(db, PROJECTION, selection, selectionArgs,
                    null, null, updatedSortOrder);
            mEntityCursor.moveToFirst();
        }

        public void close() {
            if (mIsClosed) {
                throw new IllegalStateException("closing when already closed");
            }
            mIsClosed = true;
            mEntityCursor.close();
        }

        public boolean hasNext() throws RemoteException {
            if (mIsClosed) {
                throw new IllegalStateException("calling hasNext() when the iterator is closed");
            }

            return !mEntityCursor.isAfterLast();
        }

        public Entity next() throws RemoteException {
            if (mIsClosed) {
                throw new IllegalStateException("calling next() when the iterator is closed");
            }
            if (!hasNext()) {
                throw new IllegalStateException("you may only call next() if hasNext() is true");
            }

            final SQLiteCursor c = (SQLiteCursor) mEntityCursor;

            final long groupId = c.getLong(COLUMN_ID);

            // we expect the cursor is already at the row we need to read from
            ContentValues groupValues = new ContentValues();
            groupValues.put(Groups.ACCOUNT_NAME, c.getString(COLUMN_ACCOUNT_NAME));
            groupValues.put(Groups.ACCOUNT_TYPE, c.getString(COLUMN_ACCOUNT_TYPE));
            groupValues.put(Groups._ID, groupId);
            groupValues.put(Groups.DIRTY, c.getLong(COLUMN_DIRTY));
            groupValues.put(Groups.VERSION, c.getLong(COLUMN_VERSION));
            groupValues.put(Groups.SOURCE_ID, c.getString(COLUMN_SOURCE_ID));
            groupValues.put(Groups.RES_PACKAGE, c.getString(COLUMN_RES_PACKAGE));
            groupValues.put(Groups.TITLE, c.getString(COLUMN_TITLE));
            groupValues.put(Groups.TITLE_RES, c.getString(COLUMN_TITLE_RES));
            groupValues.put(Groups.GROUP_VISIBLE, c.getLong(COLUMN_GROUP_VISIBLE));
            Entity group = new Entity(groupValues);

            mEntityCursor.moveToNext();

            return group;
        }
    }

    @Override
    public EntityIterator queryEntities(Uri uri, String selection, String[] selectionArgs,
            String sortOrder) {
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case RAW_CONTACTS:
            case RAW_CONTACTS_ID:
                String contactsIdString = null;
                if (match == RAW_CONTACTS_ID) {
                    contactsIdString = uri.getPathSegments().get(1);
                }

                return new ContactsEntityIterator(this, contactsIdString,
                        uri, selection, selectionArgs, sortOrder);
            case GROUPS:
            case GROUPS_ID:
                String idString = null;
                if (match == GROUPS_ID) {
                    idString = uri.getPathSegments().get(1);
                }

                return new GroupsEntityIterator(this, idString,
                        uri, selection, selectionArgs, sortOrder);
            default:
                throw new UnsupportedOperationException("Unknown uri: " + uri);
        }
    }

    @Override
    public String getType(Uri uri) {
        final int match = sUriMatcher.match(uri);
        switch (match) {
            case CONTACTS: return Contacts.CONTENT_TYPE;
            case CONTACTS_ID: return Contacts.CONTENT_ITEM_TYPE;
            case RAW_CONTACTS: return RawContacts.CONTENT_TYPE;
            case RAW_CONTACTS_ID: return RawContacts.CONTENT_ITEM_TYPE;
            case DATA_ID:
                final SQLiteDatabase db = mOpenHelper.getReadableDatabase();
                long dataId = ContentUris.parseId(uri);
                return mOpenHelper.getDataMimeType(dataId);
            case AGGREGATION_EXCEPTIONS: return AggregationExceptions.CONTENT_TYPE;
            case AGGREGATION_EXCEPTION_ID: return AggregationExceptions.CONTENT_ITEM_TYPE;
            case AGGREGATION_SUGGESTIONS: return Contacts.CONTENT_TYPE;
            case SEARCH_SUGGESTIONS:
                return SearchManager.SUGGEST_MIME_TYPE;
            case SEARCH_SHORTCUT:
                return SearchManager.SHORTCUT_MIME_TYPE;
        }
        throw new UnsupportedOperationException("Unknown uri: " + uri);
    }

    @Override
    public ContentProviderResult[] applyBatch(ArrayList<ContentProviderOperation> operations)
            throws OperationApplicationException {

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentProviderResult[] results = super.applyBatch(operations);
            db.setTransactionSuccessful();
            return results;
        } finally {
            db.endTransaction();
        }
    }

    private void setDisplayName(long rawContactId, String displayName) {
        if (displayName != null) {
            mContactDisplayNameUpdate.bindString(1, displayName);
        } else {
            mContactDisplayNameUpdate.bindNull(1);
        }
        mContactDisplayNameUpdate.bindLong(2, rawContactId);
        mContactDisplayNameUpdate.execute();
    }

    /*
     * Sets the given dataId record in the "data" table to primary, and resets all data records of
     * the same mimetype and under the same contact to not be primary.
     *
     * @param dataId the id of the data record to be set to primary.
     */
    private void setIsPrimary(long dataId) {
        mSetPrimaryStatement.bindLong(1, dataId);
        mSetPrimaryStatement.bindLong(2, dataId);
        mSetPrimaryStatement.bindLong(3, dataId);
        mSetPrimaryStatement.execute();
    }

    /*
     * Sets the given dataId record in the "data" table to "super primary", and resets all data
     * records of the same mimetype and under the same aggregate to not be "super primary".
     *
     * @param dataId the id of the data record to be set to primary.
     */
    private void setIsSuperPrimary(long dataId) {
        mSetSuperPrimaryStatement.bindLong(1, dataId);
        mSetSuperPrimaryStatement.bindLong(2, dataId);
        mSetSuperPrimaryStatement.bindLong(3, dataId);
        mSetSuperPrimaryStatement.execute();

        // Find the parent aggregate and package for this new primary
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        long aggId = -1;
        boolean isRestricted = false;
        String mimeType = null;

        Cursor cursor = null;
        try {
            cursor = db.query(DataRawContactsQuery.TABLE, DataRawContactsQuery.PROJECTION,
                    DataColumns.CONCRETE_ID + "=" + dataId, null, null, null, null);
            if (cursor.moveToFirst()) {
                aggId = cursor.getLong(DataRawContactsQuery.CONTACT_ID);
                isRestricted = (cursor.getInt(DataRawContactsQuery.IS_RESTRICTED) == 1);
                mimeType = cursor.getString(DataRawContactsQuery.MIMETYPE);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Bypass aggregate update if no parent found, or if we don't keep track
        // of super-primary for this mimetype.
        if (aggId == -1) {
            return;
        }

        boolean isPhone = CommonDataKinds.Phone.CONTENT_ITEM_TYPE.equals(mimeType);
        boolean isEmail = CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mimeType);

        // Record this value as the new primary for the parent aggregate
        final ContentValues values = new ContentValues();
        if (isPhone) {
            values.put(ContactsColumns.OPTIMAL_PRIMARY_PHONE_ID, dataId);
            values.put(ContactsColumns.OPTIMAL_PRIMARY_PHONE_IS_RESTRICTED, isRestricted);
        } else if (isEmail) {
            values.put(ContactsColumns.OPTIMAL_PRIMARY_EMAIL_ID, dataId);
            values.put(ContactsColumns.OPTIMAL_PRIMARY_EMAIL_IS_RESTRICTED, isRestricted);
        }

        // If this data is unrestricted, then also set as fallback
        if (!isRestricted && isPhone) {
            values.put(ContactsColumns.FALLBACK_PRIMARY_PHONE_ID, dataId);
        } else if (!isRestricted && isEmail) {
            values.put(ContactsColumns.FALLBACK_PRIMARY_EMAIL_ID, dataId);
        }

        // Push update into contacts table, if needed
        if (values.size() > 0) {
            db.update(Tables.CONTACTS, values, Contacts._ID + "=" + aggId, null);
        }
    }

    private String buildContactLookupWhereClause(String filterParam) {
        StringBuilder filter = new StringBuilder();
        filter.append(Tables.CONTACTS);
        filter.append(".");
        filter.append(Contacts._ID);
        filter.append(" IN (SELECT ");
        filter.append(RawContacts.CONTACT_ID);
        filter.append(" FROM ");
        filter.append(Tables.RAW_CONTACTS);
        filter.append(" WHERE ");
        filter.append(RawContacts._ID);
        filter.append(" IN ");
        appendRawContactsByFilterAsNestedQuery(filter, filterParam, null);
        filter.append(")");
        return filter.toString();
    }

    public String getRawContactsByFilterAsNestedQuery(String filterParam) {
        StringBuilder sb = new StringBuilder();
        appendRawContactsByFilterAsNestedQuery(sb, filterParam, null);
        return sb.toString();
    }

    private void appendRawContactsByFilterAsNestedQuery(StringBuilder sb, String filterParam,
            String limit) {
        sb.append("(SELECT DISTINCT raw_contact_id FROM name_lookup WHERE normalized_name GLOB '");
        sb.append(NameNormalizer.normalize(filterParam));
        sb.append("*'");
        if (limit != null) {
            sb.append(" LIMIT ").append(limit);
        }
        sb.append(")");
    }

    private String[] appendGroupArg(String[] selectionArgs, String arg) {
        if (selectionArgs == null) {
            return new String[] {arg};
        } else {
            int newLength = selectionArgs.length + 1;
            String[] newSelectionArgs = new String[newLength];
            System.arraycopy(selectionArgs, 0, newSelectionArgs, 0, selectionArgs.length);
            newSelectionArgs[newLength - 1] = arg;
            return newSelectionArgs;
        }
    }
}
