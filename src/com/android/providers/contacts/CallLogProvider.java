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

import static com.android.providers.contacts.util.DbQueryUtils.checkForSupportedColumns;
import static com.android.providers.contacts.util.DbQueryUtils.getEqualityClause;
import static com.android.providers.contacts.util.DbQueryUtils.getInequalityClause;

import com.android.providers.contacts.ContactsDatabaseHelper.Tables;
import com.android.providers.contacts.util.SelectionBuilder;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.CallLog.Calls;

import java.util.HashMap;

/**
 * Call log content provider.
 */
public class CallLogProvider extends ContentProvider {
    /** Selection clause to use to exclude voicemail records.  */
    private static final String EXCLUDE_VOICEMAIL_SELECTION = getInequalityClause(
            Calls.TYPE, Integer.toString(Calls.VOICEMAIL_TYPE));

    private static final int CALLS = 1;

    private static final int CALLS_ID = 2;

    private static final int CALLS_FILTER = 3;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sURIMatcher.addURI(CallLog.AUTHORITY, "calls", CALLS);
        sURIMatcher.addURI(CallLog.AUTHORITY, "calls/#", CALLS_ID);
        sURIMatcher.addURI(CallLog.AUTHORITY, "calls/filter/*", CALLS_FILTER);
    }

    private static final HashMap<String, String> sCallsProjectionMap;
    static {

        // Calls projection map
        sCallsProjectionMap = new HashMap<String, String>();
        sCallsProjectionMap.put(Calls._ID, Calls._ID);
        sCallsProjectionMap.put(Calls.NUMBER, Calls.NUMBER);
        sCallsProjectionMap.put(Calls.DATE, Calls.DATE);
        sCallsProjectionMap.put(Calls.DURATION, Calls.DURATION);
        sCallsProjectionMap.put(Calls.TYPE, Calls.TYPE);
        sCallsProjectionMap.put(Calls.NEW, Calls.NEW);
        sCallsProjectionMap.put(Calls.VOICEMAIL_URI, Calls.VOICEMAIL_URI);
        sCallsProjectionMap.put(Calls.IS_READ, Calls.IS_READ);
        sCallsProjectionMap.put(Calls.CACHED_NAME, Calls.CACHED_NAME);
        sCallsProjectionMap.put(Calls.CACHED_NUMBER_TYPE, Calls.CACHED_NUMBER_TYPE);
        sCallsProjectionMap.put(Calls.CACHED_NUMBER_LABEL, Calls.CACHED_NUMBER_LABEL);
        sCallsProjectionMap.put(Calls.COUNTRY_ISO, Calls.COUNTRY_ISO);
    }

    private ContactsDatabaseHelper mDbHelper;
    private DatabaseUtils.InsertHelper mCallsInserter;
    private boolean mUseStrictPhoneNumberComparation;
    private CountryMonitor mCountryMonitor;
    private VoicemailPermissions mVoicemailPermissions;

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        mDbHelper = getDatabaseHelper(context);
        mUseStrictPhoneNumberComparation =
            context.getResources().getBoolean(
                    com.android.internal.R.bool.config_use_strict_phone_number_comparation);
        mCountryMonitor = new CountryMonitor(context);
        mVoicemailPermissions = new VoicemailPermissions(context);
        return true;
    }

    /* Visible for testing */
    protected ContactsDatabaseHelper getDatabaseHelper(final Context context) {
        return ContactsDatabaseHelper.getInstance(context);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(Tables.CALLS);
        qb.setProjectionMap(sCallsProjectionMap);
        qb.setStrict(true);

        SelectionBuilder selectionBuilder = new SelectionBuilder(selection);
        checkVoicemailPermissionAndAddRestriction(uri, selectionBuilder);

        int match = sURIMatcher.match(uri);
        switch (match) {
            case CALLS:
                break;

            case CALLS_ID: {
                selectionBuilder.addClause(getEqualityClause(Calls._ID,
                        parseCallIdFromUri(uri)));
                break;
            }

            case CALLS_FILTER: {
                String phoneNumber = uri.getPathSegments().get(2);
                qb.appendWhere("PHONE_NUMBERS_EQUAL(number, ");
                qb.appendWhereEscapeString(phoneNumber);
                qb.appendWhere(mUseStrictPhoneNumberComparation ? ", 1)" : ", 0)");
                break;
            }

            default:
                throw new IllegalArgumentException("Unknown URL " + uri);
        }

        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selectionBuilder.build(), selectionArgs, null, null,
                sortOrder, null);
        if (c != null) {
            c.setNotificationUri(getContext().getContentResolver(), CallLog.CONTENT_URI);
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case CALLS:
                return Calls.CONTENT_TYPE;
            case CALLS_ID:
                return Calls.CONTENT_ITEM_TYPE;
            case CALLS_FILTER:
                return Calls.CONTENT_TYPE;
            default:
                throw new IllegalArgumentException("Unknown URI: " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        checkForSupportedColumns(sCallsProjectionMap, values);
        // Inserting a voicemail record through call_log requires the voicemail
        // permission and also requires the additional voicemail param set.
        if (hasVoicemailValue(values)) {
            checkIsAllowVoicemailRequest(uri);
            mVoicemailPermissions.checkCallerHasFullAccess();
        }

        // Inserted the current country code, so we know the country
        // the number belongs to.
        values.put(Calls.COUNTRY_ISO, getCurrentCountryIso());

        if (mCallsInserter == null) {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            mCallsInserter = new DatabaseUtils.InsertHelper(db, Tables.CALLS);
        }
        long rowId = mCallsInserter.insert(values);
        if (rowId > 0) {
            notifyChange();
            return ContentUris.withAppendedId(uri, rowId);
        }
        return null;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        checkForSupportedColumns(sCallsProjectionMap, values);
        // Request that involves changing record type to voicemail requires the
        // voicemail param set in the uri.
        if (hasVoicemailValue(values)) {
            checkIsAllowVoicemailRequest(uri);
        }

        SelectionBuilder selectionBuilder = new SelectionBuilder(selection);
        checkVoicemailPermissionAndAddRestriction(uri, selectionBuilder);

        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        final int matchedUriId = sURIMatcher.match(uri);
        switch (matchedUriId) {
            case CALLS:
                break;

            case CALLS_ID:
                selectionBuilder.addClause(getEqualityClause(Calls._ID, parseCallIdFromUri(uri)));
                break;

            default:
                throw new UnsupportedOperationException("Cannot update URL: " + uri);
        }

        int count = db.update(Tables.CALLS, values, selectionBuilder.build(), selectionArgs);
        if (count > 0) {
            notifyChange();
        }
        return count;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SelectionBuilder selectionBuilder = new SelectionBuilder(selection);
        checkVoicemailPermissionAndAddRestriction(uri, selectionBuilder);

        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        final int matchedUriId = sURIMatcher.match(uri);
        switch (matchedUriId) {
            case CALLS:
                int count = db.delete(Tables.CALLS, selectionBuilder.build(), selectionArgs);
                if (count > 0) {
                    notifyChange();
                }
                return count;

            default:
                throw new UnsupportedOperationException("Cannot delete that URL: " + uri);
        }
    }

    protected void notifyChange() {
        getContext().getContentResolver().notifyChange(CallLog.CONTENT_URI, null,
                false /* wake up sync adapters */);
    }

    protected String getCurrentCountryIso() {
        return mCountryMonitor.getCountryIso();
    }

    private boolean hasVoicemailValue(ContentValues values) {
        return values.containsKey(Calls.TYPE) &&
                values.getAsInteger(Calls.TYPE).equals(Calls.VOICEMAIL_TYPE);
    }

    /**
     * Checks if the supplied uri requests to include voicemails and take appropriate
     * action.
     * <p> If voicemail is requested, then check for voicemail permissions. Otherwise
     * modify the selection to restrict to non-voicemail entries only.
     */
    private void checkVoicemailPermissionAndAddRestriction(Uri uri,
            SelectionBuilder selectionBuilder) {
        if (isAllowVoicemailRequest(uri)) {
            mVoicemailPermissions.checkCallerHasFullAccess();
        } else {
            selectionBuilder.addClause(EXCLUDE_VOICEMAIL_SELECTION);
        }
    }

    /**
     * Determines if the supplied uri has the request to allow voicemails to be
     * included.
     */
    private boolean isAllowVoicemailRequest(Uri uri) {
        return uri.getBooleanQueryParameter(Calls.ALLOW_VOICEMAILS_PARAM_KEY, false);
    }

    /**
     * Checks to ensure that the given uri has allow_voicemail set. Used by
     * insert and update operations to check that ContentValues with voicemail
     * call type must use the voicemail uri.
     * @throws IllegalArgumentException if allow_voicemail is not set.
     */
    private void checkIsAllowVoicemailRequest(Uri uri) {
        if (!isAllowVoicemailRequest(uri)) {
            throw new IllegalArgumentException(
                    String.format("Uri %s cannot be used for voicemail record." +
                            " Please set '%s=true' in the uri.", uri,
                            Calls.ALLOW_VOICEMAILS_PARAM_KEY));
        }
    }

   /**
    * Parses the call Id from the given uri, assuming that this is a uri that
    * matches CALLS_ID. For other uri types the behaviour is undefined.
    * @throws IllegalArgumentException if the id included in the Uri is not a valid long value.
    */
    private String parseCallIdFromUri(Uri uri) {
        try {
            Long id = Long.valueOf(uri.getPathSegments().get(1));
            return id.toString();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid call id in uri: " + uri, e);
        }
    }
}
