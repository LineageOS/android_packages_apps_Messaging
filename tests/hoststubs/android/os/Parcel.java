/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package android.os;

import java.util.ArrayList;
import java.util.List;

/**
 * Host stand-in for the parts of {@code android.os.Parcel} a hand-written parcelable uses: an
 * ordered value list, so a write/read pair is checked field for field. Reading past the end yields
 * the platform's defaults (null, 0).
 */
public final class Parcel {
    private final List<Object> mValues = new ArrayList<>();
    private int mPos;

    public static Parcel obtain() {
        return new Parcel();
    }

    public void recycle() {}

    public void setDataPosition(final int pos) {
        mPos = pos;
    }

    public int dataAvail() {
        return mValues.size() - mPos;
    }

    public void writeInt(final int v) {
        mValues.add(v);
    }

    public void writeLong(final long v) {
        mValues.add(v);
    }

    public void writeString(final String v) {
        mValues.add(new StringBox(v));
    }

    public int readInt() {
        return mPos < mValues.size() ? (Integer) mValues.get(mPos++) : 0;
    }

    public long readLong() {
        return mPos < mValues.size() ? (Long) mValues.get(mPos++) : 0L;
    }

    public String readString() {
        return mPos < mValues.size() ? ((StringBox) mValues.get(mPos++)).value : null;
    }

    /** Truncates to the first {@code n} values, as an older writer that knew fewer fields. */
    public void truncateTo(final int n) {
        while (mValues.size() > n) mValues.remove(mValues.size() - 1);
    }

    public int valueCount() {
        return mValues.size();
    }

    /** Typed so a read of the wrong kind fails loudly instead of converting. */
    private static final class StringBox {
        final String value;

        StringBox(final String value) {
            this.value = value;
        }
    }
}
