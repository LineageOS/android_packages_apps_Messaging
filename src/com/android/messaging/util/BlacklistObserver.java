package com.android.messaging.util;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import com.android.messaging.BugleApplication;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.MessagingContentProvider;
import com.android.messaging.datamodel.action.UpdateConversationArchiveStatusAction;

/**
 * Created by gkipnis on 11/16/15.
 */
// ContentObserver class to monitor changes to the Framework Blacklist DB
public class BlacklistObserver extends ContentObserver {

    private static final String TAG = BlacklistObserver.class.getSimpleName();
    private ContentResolver mResolver;

    public BlacklistObserver(Handler handler, ContentResolver resolver) {
        super(handler);
        mResolver = resolver;
    }

    @Override
    public void onChange(boolean selfChange, final Uri uri) {
        // depending on the Thread in which the handler was created, this function
        // may or may not run on the UI thread, to be sure that it doesn't, launch
        // it via AsyncTask
        new SafeAsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackgroundTimed(Void... params) {
                Log.i(TAG, "BlacklistObserver: onChange: Uri:" + uri.toString());
                // we need to find the phone number being blacklisted and add/update it
                // in the bugle database
                Cursor cursor = null;
                try {
                    cursor = mResolver.query(uri, null, null, null, null);
                    int normalizedNumberIndex = cursor.getColumnIndex("normalized_number");
                    int blockedIndex = cursor.getColumnIndex("message");

                    // if the column indices are not valid, don't perform the queries
                    if (normalizedNumberIndex < 0 || blockedIndex < 0) {
                        if (cursor != null) {
                            cursor.close();
                        }
                        return null;
                    }

                    DatabaseWrapper db = DataModel.get().getDatabase();

                    while(cursor.moveToNext()) {
                        String number = cursor.getString(normalizedNumberIndex);
                        String blocked = cursor.getString(blockedIndex);
                        boolean isBlocked = blocked.compareTo("1") == 0;

                        // don't update the framework db - the 'false' argument
                        BugleDatabaseOperations.updateDestination(db, number, isBlocked, false);
                        String conversationId = BugleDatabaseOperations
                                .getConversationFromOtherParticipantDestination(db, number);
                        if (conversationId != null) {
                            if (isBlocked) {
                                UpdateConversationArchiveStatusAction.archiveConversation(conversationId);
                            } else {
                                UpdateConversationArchiveStatusAction.unarchiveConversation(conversationId);
                            }
                            MessagingContentProvider.notifyParticipantsChanged(conversationId);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "BlacklistObserver: onChange: " + e.getMessage());
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
                return null;
            }
        }.execute();

    }
}