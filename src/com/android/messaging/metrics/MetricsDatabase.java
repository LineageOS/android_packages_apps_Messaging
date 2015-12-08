package com.android.messaging.metrics;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

class MetricsDatabase {
    private static final String TABLE_NAME = "Metrics";
    static final String VALUE = "value";
    static final String CATEGORY = "category";
    static final String ACTION = "action";
    static final String LABEL = "label";

    static final String CREATE_TABLE = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME +
            " (" +
            CATEGORY + " text," +
            ACTION + " text," +
            LABEL + " text," +
            VALUE + " int," +
            "CONSTRAINT `main` UNIQUE (" + CATEGORY + ", " + ACTION + ", " + LABEL + ") ON CONFLICT FAIL" +
            ");";

    private static final String INSERT = "INSERT INTO " + TABLE_NAME + " (" + CATEGORY + ", " +
            ACTION + ", " + LABEL + ", " + VALUE + ") " +
            "VALUES (?, ?, ?, 1);\n";
    private static final String UPDATE = "UPDATE " + TABLE_NAME + " SET " + VALUE + " = " + VALUE + " + 1 WHERE " +
            CATEGORY + "=? AND " + ACTION + "=? AND " + LABEL + "=?" ;

    private static final String SELECT_STATISTICS = "SELECT " + CATEGORY + ", "
            + ACTION + ", " + LABEL + ", " + VALUE + " FROM " +
            TABLE_NAME;

    private static final String CLEAR_STATS = "DELETE FROM " + TABLE_NAME;

    private static volatile MetricsDatabase mInstance;

    public static MetricsDatabase getInstance(Context context) {
        if (mInstance == null) {
            synchronized (MetricsDatabase.class) {
                if (mInstance == null) {
                    mInstance = new MetricsDatabase(context.getApplicationContext());
                }
            }
        }
        return mInstance;
    }

    private SQLiteDatabase mDatabase;
    private SQLiteStatement mInsertStatement;
    private SQLiteStatement mUpdateStatement;

    private MetricsDatabase(Context context) {
        mDatabase = new MetricsDatabaseHelper(context).getWritableDatabase();
        mInsertStatement = mDatabase.compileStatement(INSERT);
        mUpdateStatement = mDatabase.compileStatement(UPDATE);
    }

    public synchronized void insert(Event event) {
        mUpdateStatement.bindAllArgsAsStrings(new String[]{event.getCategory(), event.getAction(), event.getLabel()});
        if (mUpdateStatement.executeUpdateDelete() <= 0) {
            mInsertStatement.bindAllArgsAsStrings(new String[]{event.getCategory(), event.getAction(),
                    event.getLabel()});
            mInsertStatement.executeInsert();
        }
    }

    public List<EventStatistic> getAllStatistics() {
        Cursor cursor = null;
        try {
            cursor = mDatabase.rawQuery(SELECT_STATISTICS, null);
            List<EventStatistic> statistics = new ArrayList<>(cursor.getCount());

            int actionColumnIndex = cursor.getColumnIndex(ACTION);
            int categoryColumnIndex = cursor.getColumnIndex(CATEGORY);
            int valueColumnIndex = cursor.getColumnIndex(VALUE);
            int labelColumnIndex = cursor.getColumnIndex(LABEL);

            while (cursor.moveToNext()) {
                EventStatistic statistic = new EventStatistic.Builder()
                        .setAction(cursor.getString(actionColumnIndex))
                        .setCategory(cursor.getString(categoryColumnIndex))
                        .setValue(cursor.getInt(valueColumnIndex))
                        .setLabel(cursor.getString(labelColumnIndex))
                        .build();
                statistics.add(statistic);
            }

            return statistics;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public synchronized void clearStatistics() {
        mDatabase.execSQL(CLEAR_STATS);
    }
}
