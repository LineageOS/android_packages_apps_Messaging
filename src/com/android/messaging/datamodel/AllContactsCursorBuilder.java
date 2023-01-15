/*
 * Copyright (C) 2023 The LineageOS Project
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
package com.android.messaging.datamodel;

import android.database.Cursor;
import android.database.MatrixCursor;

import com.android.messaging.util.Assert;
import com.android.messaging.util.ContactUtil;

import java.util.ArrayList;

/**
 * A cursor builder that takes the all contacts cursor and strips away duplicate numbers.
 */
public class AllContactsCursorBuilder {
    /**
     * Build the cursor records from the all contacts cursor
     * @return the frequent contact cursor if built successfully, or null if it can't be built yet.
     */
    public static Cursor build(final Cursor allContactsCursor) {
        if (allContactsCursor != null) {
            Assert.isTrue(!allContactsCursor.isClosed());

            // Frequent contacts cursor has one record per contact, plus it doesn't contain info
            // such as phone number and type. In order for the records to be usable by Bugle, we
            // would like to populate it with information from the all contacts cursor.
            final MatrixCursor retCursor = new MatrixCursor(ContactUtil.PhoneQuery.PROJECTION);

            // Go through the all contacts cursor once and retrieve all information
            // (multiple phone numbers etc.) and store that in an array list.
            final ArrayList<Object[]> rows = new ArrayList<>(allContactsCursor.getCount());
            int oldPosition = allContactsCursor.getPosition();
            allContactsCursor.moveToPosition(-1);
            while (allContactsCursor.moveToNext()) {
                final Object[] row = new Object[ContactUtil.PhoneQuery.PROJECTION.length];
                row[ContactUtil.INDEX_DATA_ID] =
                        allContactsCursor.getLong(ContactUtil.INDEX_DATA_ID);
                row[ContactUtil.INDEX_CONTACT_ID] =
                        allContactsCursor.getLong(ContactUtil.INDEX_CONTACT_ID);
                row[ContactUtil.INDEX_LOOKUP_KEY] =
                        allContactsCursor.getString(ContactUtil.INDEX_LOOKUP_KEY);
                row[ContactUtil.INDEX_DISPLAY_NAME] =
                        allContactsCursor.getString(ContactUtil.INDEX_DISPLAY_NAME);
                row[ContactUtil.INDEX_PHOTO_URI] =
                        allContactsCursor.getString(ContactUtil.INDEX_PHOTO_URI);
                row[ContactUtil.INDEX_PHONE_EMAIL] =
                        allContactsCursor.getString(ContactUtil.INDEX_PHONE_EMAIL);
                row[ContactUtil.INDEX_PHONE_EMAIL_TYPE] =
                        allContactsCursor.getInt(ContactUtil.INDEX_PHONE_EMAIL_TYPE);
                row[ContactUtil.INDEX_PHONE_EMAIL_LABEL] =
                        allContactsCursor.getString(ContactUtil.INDEX_PHONE_EMAIL_LABEL);
                row[ContactUtil.INDEX_SORT_KEY] = allContactsCursor.getString(
                        ContactUtil.INDEX_SORT_KEY);

                boolean numberAlreadyAdded = false;
                for (Object[] oldRow : rows) {
                    int idxPhoneType = ContactUtil.INDEX_PHONE_EMAIL_TYPE;
                    int idxPhoneEmail = ContactUtil.INDEX_PHONE_EMAIL;
                    if (oldRow[idxPhoneType] == row[idxPhoneType]) {
                        String prevPhoneEmail = oldRow[idxPhoneEmail].toString()
                                .replace(" ", "");
                        String currPhoneEmail = row[idxPhoneEmail].toString()
                                .replace(" ", "");
                        if (prevPhoneEmail.equals(currPhoneEmail)) {
                            numberAlreadyAdded = true;
                            break;
                        }
                    }
                }
                if (!numberAlreadyAdded) {
                    rows.add(row);
                }
            }
            allContactsCursor.moveToPosition(oldPosition);

            // Finally, add all the rows to this cursor.
            for (final Object[] row : rows) {
                retCursor.addRow(row);
            }
            return retCursor;
        }
        return null;
    }
}
