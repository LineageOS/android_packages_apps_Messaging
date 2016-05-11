package com.cyanogenmod.messaging.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.support.v7.appcompat.R;

public class RoundedCornerTransformation implements com.squareup.picasso.Transformation {
    private Context mContext;
    private String mTag;

    public RoundedCornerTransformation(Context context, String tag) {
        mContext = context;
        mTag = tag;
    }

    @Override
    public Bitmap transform(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        final RectF rect = new RectF(0, 0, width, height);

        final int radius =
                mContext.getResources().getDimensionPixelSize(R.dimen.maps_corner_radius);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        paint.setShader(shader);
        canvas.drawRoundRect(rect, radius, radius, paint);

        source.recycle();

        return bitmap;
    }

    @Override
    public String key() {
        return mTag;
    }
}
