package com.cyanogenmod.messaging.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import com.cyanogen.ambient.common.ConnectionResult;
import com.cyanogen.ambient.common.api.AmbientApiClient;
import com.cyanogen.ambient.common.api.ComponentNameResult;
import com.cyanogen.ambient.common.api.PendingResult;
import com.cyanogen.ambient.common.api.ResultCallback;
import com.cyanogen.ambient.ridesharing.RideSharingApi;
import com.cyanogen.ambient.ridesharing.RideSharingServices;
import com.cyanogen.ambient.ridesharing.results.ProviderInfoResult;

/**
 * Ridesharing services util
 */
public class RidesharingUtil {
    private static final String TAG = RidesharingUtil.class.getSimpleName();

    private Context mContext;
    private AmbientApiClient mAmbientApiClient;
    private RideSharingApi mRideSharingApi;

    private Bitmap mBrandBitmap;

    public RidesharingUtil(Context context) {
        mContext = context;
        connectAmbientApiClientIfNeeded();
    }

    /**
     * Sets brand bitmap from the active ridesharing provider to the specified {@link ImageView}
     * @param imageView ImageView to set the brand bitmap on
     */
    public void setBrandBitmap(final ImageView imageView) {
        if (mBrandBitmap == null) {
            connectAmbientApiClientIfNeeded();

            PendingResult<ComponentNameResult> pendingComponentResult =
                    mRideSharingApi.getActivePlugin(mAmbientApiClient);
            pendingComponentResult.setResultCallback(new ResultCallback<ComponentNameResult>() {
                @Override
                public void onResult(final ComponentNameResult componentResult) {
                    if (componentResult != null && componentResult.component != null) {
                        PendingResult<ProviderInfoResult> pendingInfoResult =
                                mRideSharingApi.getPluginInfo(mAmbientApiClient,
                                        componentResult.component);
                        pendingInfoResult.setResultCallback(new ResultCallback<ProviderInfoResult>() {
                            @Override
                            public void onResult(ProviderInfoResult infoResult) {
                                if (infoResult != null && infoResult.getProviderInfo() != null) {
                                    mBrandBitmap = infoResult.getProviderInfo().getBrandIcon();
                                    imageView.setImageBitmap(mBrandBitmap);
                                } else {
                                    Log.e(TAG, "Unable to get provider info for active plugin: "
                                            + componentResult.component);
                                }
                            }
                        });
                    } else {
                        Log.e(TAG, "Unable to get active plugin");
                    }
                }
            });
        } else {
            imageView.setImageBitmap(mBrandBitmap);
        }
    }

    /**
     * Helper method to initialize AmbientApiClient if it is null and connect AmbientApiClient if
     * it is not connected and not currently trying to connect
     */
    private void connectAmbientApiClientIfNeeded() {
        if (mAmbientApiClient == null) {
            Log.d(TAG, "mAmbientApiClient is null, initializing");
            mAmbientApiClient = new AmbientApiClient.Builder(mContext)
                    .addApi(RideSharingServices.API)
                    .build();
            if (mAmbientApiClient == null) {
                Log.e(TAG, "AmbientApiClient couldn't initialize, returning");
                return;
            }
            mAmbientApiClient.registerConnectionFailedListener(new AmbientApiClient.OnConnectionFailedListener() {
                @Override
                public void onConnectionFailed(ConnectionResult connectionResult) {
                    Log.e(TAG, "Unable to connect with Ambient. ConnectionResult.ErrorCode : " + connectionResult.getErrorCode());
                }
            });
            mAmbientApiClient.registerConnectionCallbacks(new AmbientApiClient.ConnectionCallbacks() {
                @Override
                public void onConnected(Bundle bundle) {
                    Log.d(TAG, "Connected with Ambient.");
                }

                @Override
                public void onConnectionSuspended(int i) {
                    Log.d(TAG, "Ambient client disconnected.");
                }
            });
        }

        if (!mAmbientApiClient.isConnected() && !mAmbientApiClient.isConnecting()) {
            Log.d(TAG, "ambientApiClient is not connect or connecting, attempting to connect now");
            mAmbientApiClient.connect();
            mRideSharingApi = RideSharingServices.getInstance();
        }
    }
}
