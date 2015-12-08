package com.android.messaging.metrics;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class MetricsDatabaseHelper extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "metrics.db";
    private static final int DATABASE_VERSION = 1;

    public MetricsDatabaseHelper(Context appContext) {
        super(appContext, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(MetricsDatabase.CREATE_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}
