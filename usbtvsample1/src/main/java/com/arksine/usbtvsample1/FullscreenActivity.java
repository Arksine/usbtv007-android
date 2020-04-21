// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license
package com.arksine.usbtvsample1;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.hardware.usb.UsbDevice;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.arksine.libusbtv.DeviceParams;
import com.arksine.libusbtv.IUsbTvDriver;
import com.arksine.libusbtv.UsbTv;
import com.arksine.libusbtv.UsbTvFrame;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity {
    private final Object CAM_LOCK = new Object();


    /**
     * Whether or not the system UI should be auto-hidden after
     * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
     */
    private static final boolean AUTO_HIDE = true;

    /**
     * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
     * user interaction before hiding the system UI.
     */
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler mHideHandler = new Handler();

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mCameraView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };

    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };
    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS);
            }
            return false;
        }
    };

    private FrameLayout mRootLayout;
    private SurfaceView mCameraView;
    private Surface mPreviewSurface;
    private SurfaceHolder mSurfaceHolder;

    private boolean mIsFullScreen = true;
    AtomicBoolean mIsStreaming = new AtomicBoolean(false);

    private IUsbTvDriver mTestDriver;
    private TestRenderer mRenderer = null;

    private Runnable mSetAspectRatio = new Runnable() {
        @Override
        public void run() {
            setViewLayout();
        }
    };

    private UsbTv.onFrameReceivedListener mOnFrameReceivedListener = new UsbTv.onFrameReceivedListener() {
        @Override
        public void onFrameReceived(UsbTvFrame frame) {
            if (mRenderer != null) {
                mRenderer.processFrame(frame);
            }
        }
    };

    private final UsbTv.DriverCallbacks mCallbacks = new UsbTv.DriverCallbacks() {
        @Override
        public void onOpen(IUsbTvDriver driver, boolean status) {
            Timber.i("UsbTv Open Status: %b", status);
            synchronized (CAM_LOCK) {
                mTestDriver = driver;
                if (mTestDriver != null) {
                    mTestDriver.setOnFrameReceivedListener(mOnFrameReceivedListener);

                    // If I have a preview surface, we can fetch the renderer and start it
                    if (mPreviewSurface != null) {
                        mIsStreaming.set(true);
                        if (mRenderer == null) {
                            mRenderer = new TestRenderer(getApplicationContext(), mPreviewSurface);
                        } else {
                            mRenderer.setSurface(mPreviewSurface);
                        }
                        mRenderer.startRenderer(mTestDriver.getDeviceParams());
                        mTestDriver.startStreaming();
                    }
                }
            }
        }

        @Override
        public void onClose() {
            Timber.i("UsbTv Device Closed");

            if (mRenderer != null) {
                mRenderer.stopRenderer();
            }

            if (mPreviewSurface != null) {
                mPreviewSurface.release();
            }

            // Unregister Library Receiver
            UsbTv.unregisterUsbReceiver(FullscreenActivity.this);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }

        @Override
        public void onError() {
            Timber.i("Error received");
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        mVisible = true;


        mRootLayout = (FrameLayout) findViewById(R.id.activity_main);
        mCameraView = (SurfaceView) findViewById(R.id.camera_view);

        // Set up the user interaction to manually show or hide the system UI.
        mCameraView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });

        // Register Library Broadcast Receiver
        UsbTv.registerUsbReceiver(this);


        mSurfaceHolder = mCameraView.getHolder();
        mSurfaceHolder.addCallback(mCameraViewCallback);

        /*
            Enumerate available UsbTv Devices.  If there are more than one connected then
            you will need to parse and decide which one you want to use based on
            criteria of your choosing.
         */
        ArrayList<UsbDevice> devList = UsbTv.enumerateUsbtvDevices(this);
        UsbDevice device = null;
        if (!devList.isEmpty()) {
            device = devList.get(0);
        } else {
            Timber.i("Dev List Empty");
        }

        if (device == null) {
            Timber.i("Can't open");
            return;
        }

         /*
            Create Usbtv Device Params to initialize device settings.
         */
        DeviceParams params = new DeviceParams.Builder()
                .setUsbDevice(device)
                .setDriverCallbacks(mCallbacks)
                .setInput(UsbTv.InputSelection.COMPOSITE)
                .setScanType(UsbTv.ScanType.PROGRESSIVE)
                .setTvNorm(UsbTv.TvNorm.NTSC)
                .build();

        /*
            When using renderscript's surface producer functionality, you must set
            the surface to a fixed size.  We can use the size calculated after
            setting the device params.  NOTE: If you change the params and it results
            in a different frame height (ie changing from NTSC to PAL), then
            the surface size will need to be reset.
         */

        mSurfaceHolder.setFixedSize(params.getFrameWidth(), params.getFrameHeight());
        mSurfaceHolder.setFormat(PixelFormat.RGBA_8888);



        /*
            TODO: This doesn't work well when the device is rotated.  When the surface is destroyed
             streaming stops, however its almost as if the device is disconnected  (its possible
             the otg cable shook loose when I rotated it)

             Regardless, the device crashed, then onCreate is called again, attempts to open the
             device, and its already open.

             The best way to handle it is to probably close the device when the surface is destroyed, or in onPause.
             Then open it in onResume.  Truthfully most apps using this library should lock rotation to landscape anyway

            Attempt to open the device.  A driver interface will be passed to the onOpen
            callback if the open was successful.
         */

        Timber.i("Open Device");
        UsbTv.open(this, params);

    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);
    }

    @Override
    public void onBackPressed() {

        mRootLayout.removeCallbacks(mSetAspectRatio);
        //  I need to stop the renderer here, its possible (although unlikely) that the
        // renderer could reference freed memory if its still running when shared memory is
        // freed in jni
        if (mRenderer != null) {
            mRenderer.stopRenderer();
        }

        if (mTestDriver != null && mTestDriver.isOpen()) {
            mTestDriver.close();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (mCameraView == null) {
            return;
        }

        if (hasFocus) {
            mRootLayout.post(mSetAspectRatio);
        }
    }


    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }

        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mCameraView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in [delay] milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }



    private void setViewLayout() {
        if (mCameraView == null) {
            return;
        }

        FrameLayout.LayoutParams params;

        if (mIsFullScreen)  {
            params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            mCameraView.setLayoutParams(params);
        } else {
            int currentWidth = mRootLayout.getWidth();
            int currentHeight = mRootLayout.getHeight();

            if (currentWidth >= (4 * currentHeight) / 3) {
                int destWidth = (4 * currentHeight) / 3 + 1;
                params = new FrameLayout.LayoutParams(destWidth,
                        FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            } else {
                int destHeight = (3 * currentWidth) / 4 + 1;
                params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        destHeight, Gravity.CENTER);
            }

            mCameraView.setLayoutParams(params);

        }

        Timber.v("Current view size %d x %d: ", mCameraView.getWidth(), mCameraView.getHeight());
    }

    private final SurfaceHolder.Callback mCameraViewCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(final SurfaceHolder holder) {
            Timber.v("Camera surfaceCreated:");
        }

        @Override
        public void surfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height) {
            if ((width == 0) || (height == 0)) return;
            Timber.v("Camera surfaceChanged:");
            mPreviewSurface = holder.getSurface();
            synchronized (CAM_LOCK) {
                if (mTestDriver!= null) {
                    if (mRenderer == null) {
                        mRenderer = new TestRenderer(getApplicationContext(), mPreviewSurface);
                    } else {
                        mRenderer.setSurface(mPreviewSurface);
                    }

                    // If not streaming, start
                    if (mIsStreaming.compareAndSet(false, true)) {
                        mRenderer.startRenderer(mTestDriver.getDeviceParams());
                        mTestDriver.startStreaming();
                    }
                }
            }
        }

        @Override
        public void surfaceDestroyed(final SurfaceHolder holder) {
            Timber.v("Camera surfaceDestroyed:");
            synchronized (CAM_LOCK) {
                if (mRenderer != null) {
                    mRenderer.stopRenderer();
                }

                if (mTestDriver != null && mIsStreaming.get()) {
                    mTestDriver.stopStreaming();
                }
                mIsStreaming.set(false);
                mPreviewSurface = null;
            }
        }
    };


}
