package com.arksine.libusbtv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.ImageFormat;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * UsbTv class.  Handles user interface for managing the usb driver
 */

public class UsbTv {
    public static final boolean DEBUG = true;  // TODO: set to false for release

    public interface DriverCallbacks {
        void onOpen(boolean status);
        void onClose();
    }

    public interface RawFrameCallback {
        void onRawFrameReceived(UsbTvFrame frame);
    }

    public enum TvNorm {NTSC, PAL}
    public enum InputSelection {COMPOSITE, SVIDEO}
    public enum ScanType {PROGRESSIVE_60, PROGRESIVE_30, INTERLEAVED}

    private final Object DEV_LOCK = new Object();
    private final Object STREAM_LOCK = new Object();
    private static final String ACTION_USB_PERMISSION = "com.arksine.usbtv.USB_PERMISSION";
    private static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    private static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";

    /**
     * Driver Constants
     */
    private static final int USBTV_VIDEO_ENDPOINT = 0x81;
    private static final int USBTV_AUDIO_ENDPOINT = 0x83;


    /**
     * Video Constants
     */
    private static final int MAX_ISO_TRANSFERS = 16;
    private static final int MAX_ISO_PACKETS = 8;


    /**
     * Audio Constatants
     */
    private static final int USBTV_AUDIO_PAYLOAD_SIZE = 20480;
    private static final int USBTV_AUDIO_HDR_SIZE = 4;
    private static final int USBTV_AUDIO_BUFFER_SIZE = 65536;

    /**
     * Endpoint Size Constants
     */
    private static final int USB_ENDPOINT_MAXP_MASK = 0x07ff;
    private static final int USB_EP_MULT_SHIFT = 11;
    private static final int USB_EP_MULT_MASK = 3 << USB_EP_MULT_SHIFT;

    private boolean mUsbReceiverRegistered = false;
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_USB_PERMISSION: {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null) {
                        synchronized (DEV_LOCK) {
                            if (dev.equals(mUsbtvDevice)) {
                                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                    if (!mIsOpen.get()) {
                                        mHasUsbPermission = true;
                                        initDevice();
                                    } else {
                                        Timber.i("Device is already open");
                                    }
                                } else {
                                    Timber.v("Cannot open device, permission denied");
                                }
                            }
                        }
                        mDriverCallbacks.onOpen(mIsOpen.get());
                    }
                    break;
                }
                case ACTION_USB_ATTACHED: {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    deviceAttached(dev);
                    break;
                }
                case ACTION_USB_DETACHED: {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    deviceDetached(dev);
                    break;
                }
            }
        }
    };

    private Context mContext;
    private UsbIso mIsonchronousManager;
    private UsbDevice mUsbtvDevice;
    private UsbDeviceConnection mUsbtvConnection;
    private UsbTvControl mUsbtvControl;
    private FrameProcessor mFrameProcessor = null;

    private boolean mHasUsbPermission = false;
    private boolean mUserManagedUsbEvents = false;
    private AtomicBoolean mIsOpen = new AtomicBoolean(false);
    private AtomicBoolean mIsStreaming = new AtomicBoolean(false);

    private InputSelection mInput;
    private FrameInfo mFrameInfo;

    private DriverCallbacks mDriverCallbacks;
    private RawFrameCallback mRawFrameCallback = null;
    private Surface mDrawingSurface = null;

    public UsbTv(@NonNull Context appContext, @NonNull DriverCallbacks driverCbs,
                 boolean userManagedEvents) {
        mContext = appContext;
        mDriverCallbacks = driverCbs;
        mUserManagedUsbEvents = userManagedEvents;

        // TODO: get from shared prefs?
        mFrameInfo = new FrameInfo(ImageFormat.YUY2, ScanType.PROGRESSIVE_60, TvNorm.NTSC);
        setTvNorm(TvNorm.NTSC);
        setInput(InputSelection.COMPOSITE);
    }

    public void setRawFrameCallback(RawFrameCallback cb) {
        mRawFrameCallback = cb;
        if (mFrameProcessor != null) {
            mFrameProcessor.setRawFrameCallback(cb);
        }
    }

    public void setTvNorm(TvNorm norm) {
        mFrameInfo = new FrameInfo(ImageFormat.YUY2, mFrameInfo.getScanType(), norm);
        if (mIsStreaming.get()) {
            stopStreaming();
            startStreaming();
        }

    }

    public void setScanType(ScanType type) {
        mFrameInfo = new FrameInfo(ImageFormat.YUY2, type, mFrameInfo.getTvNorm());
        if (mIsStreaming.get()) {
            stopStreaming();
            startStreaming();
        }
    }

    public void setInput(InputSelection input) {

        mInput = input;
        if (mIsStreaming.get()) {
            stopStreaming();
            startStreaming();
        }
    }

    public void setDrawingSurface(Surface surface) {
        mDrawingSurface = surface;
        if (mFrameProcessor != null) {
            mFrameProcessor.setDrawingSurface(surface);
        }
    }

    /**
     * Gets a list of Usbtv007 devices connected to the system
     *
     * @param context   Application context
     * @return a list of connected usbtv devices, or an empty list if none are connected
     */
    public static ArrayList<UsbDevice> enumerateUsbtvDevices(Context context) {
        ArrayList<UsbDevice> devList = new ArrayList<>(3);

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        HashMap<String, UsbDevice> usbMap = manager.getDeviceList();

        for (UsbDevice dev : usbMap.values()) {
            if (dev.getVendorId() == 0x1b71 && dev.getProductId() == 0x3002) {
                devList.add(dev);
            }
        }

        return devList;
    }

    /**
     * Opens a usbtv007 device.
     *
     * @param usbtvDevice the device to open
     */
    public void open(@NonNull UsbDevice usbtvDevice) {
        boolean execCallback = true;
        synchronized (DEV_LOCK) {
            if (usbtvDevice.getVendorId() == 0x1b71 && usbtvDevice.getProductId() == 0x3002) {
                if (!mIsOpen.get()) {
                    if (!mUserManagedUsbEvents && !mUsbReceiverRegistered) {
                        mUsbReceiverRegistered = true;
                        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                        filter.addAction(ACTION_USB_ATTACHED);
                        filter.addAction(ACTION_USB_DETACHED);
                        mContext.registerReceiver(mUsbReceiver, filter);
                        Timber.v("Usb Broadcast Receiver Registered");
                    }

                    mUsbtvDevice = usbtvDevice;
                    UsbManager manager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

                    if (manager.hasPermission(mUsbtvDevice)) {
                        mHasUsbPermission = true;
                        initDevice();
                    } else if (!mUsbReceiverRegistered) {
                        Timber.e("Error, Usb is user managed but device does not have permission");
                    } else {
                        execCallback = false; // Permission must be requested, so do not execute CB
                        PendingIntent mPendingIntent = PendingIntent.getBroadcast(
                                mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
                        manager.requestPermission(mUsbtvDevice, mPendingIntent);
                    }
                } else {
                    Timber.i("Device already open, close it first");
                }
            } else {
                Timber.i("Invalid Usbtv007 capture device, ids do not match");
            }
        }

        if (execCallback) {
            mDriverCallbacks.onOpen(mIsOpen.get());
        }

    }

    /**
     * Closes Usbtv007 usb device.  Unregisters usb reciever if it is registerd and frees
     * all resources
     */
    public void close() {
        synchronized (DEV_LOCK) {
            if (mUsbReceiverRegistered) {
                mUsbReceiverRegistered = false;
                mContext.unregisterReceiver(mUsbReceiver);
            }

            if (mIsOpen.get()) {
                if (mIsStreaming.get()) {
                    stopStreaming();
                }
                try {
                    mIsonchronousManager.dispose();
                } catch (IOException e) {
                    Timber.i(e);
                }

                mIsOpen.set(false);
                mUsbtvConnection.close();
                mUsbtvConnection = null;
                mIsonchronousManager = null;
                mUsbtvDevice = null;
                mUsbtvControl = null;

            }
        }
        mDriverCallbacks.onClose();
    }

    /**
     * Starts usb streaming.  Sends initialization registers via control transfer, sets the
     * tv norm and input, initializes and submits isonchronous urb requests, and starts
     * a thread that listens for such requests.
     *
     * @return true on success or if already streaming.  False on error or if device is not open
     */
    public void startStreaming() {
        Thread streamStart = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (STREAM_LOCK) {
                    if (mIsOpen.get() && !mIsStreaming.get()) {
                        boolean success = true;
                        try {
                            mIsonchronousManager.setInterface(0, 0);
                        } catch (IOException e) {
                            Timber.i(e);
                            success = false;
                        }

                        if (!success) {
                            Timber.e("Error setting Usb Device to interface 0");
                            return;
                        }

                        // TODO: attempting to start/stop audio for debug purposes
                        /*success = mUsbtvControl.startAudio();
                        if (!success) {
                            Timber.e("Error initializing audio stream");
                            return;
                        }*/


                        success = mUsbtvControl.initializeVideo();
                        if (!success) {
                            Timber.e("Error initializing video stream");
                            return;
                        }

                        success = mUsbtvControl.setTvNorm(mFrameInfo.getTvNorm());
                        if (!success) {
                            Timber.e("Error setting TV Norm");
                            return;
                        }

                        success = mUsbtvControl.setInput(mInput);
                        if (!success) {
                            Timber.e("Error setting video input");
                            return;
                        }

                        try {
                            mIsonchronousManager.setInterface(0, 1);
                        } catch (IOException e) {
                            Timber.i(e);
                            success = false;
                        }

                        if (!success) {
                            Timber.e("Error setting interface to alternate setting 1");
                            return;
                        }

                        // Allocate, Initialize and Submit Iso requests/urbs
                        mIsonchronousManager.preallocateRequests(MAX_ISO_TRANSFERS);
                        for (int i = 0; i < MAX_ISO_TRANSFERS; i++) {
                            UsbIso.Request req = mIsonchronousManager.getRequest();
                            req.initialize(USBTV_VIDEO_ENDPOINT);
                            try {
                                req.submit();
                            } catch (IOException e) {
                                Timber.i(e);
                                success = false;
                                break;
                            }
                        }

                        if (!success) {
                            Timber.e("Error initializing usb isonchronous requests/urbs");
                            try {
                                mIsonchronousManager.flushRequests();
                            } catch (IOException e) {
                                Timber.i(e);
                            }
                            return;
                        }

                        mIsStreaming.set(true);
                        mFrameProcessor = new FrameProcessor(mContext, mIsonchronousManager,
                                mFrameInfo, mDrawingSurface);
                        mFrameProcessor.setRawFrameCallback(mRawFrameCallback);
                        mFrameProcessor.start();
                    }
                }
            }
        });
        streamStart.start();
    }


    /**
     * Stops device from streaming video.  Flushes all urb requests and set the interface
     * altsetting to 0.
     */
    public void stopStreaming() {
        synchronized (STREAM_LOCK) {
            mIsStreaming.set(false);
            if (mFrameProcessor != null) {
                mFrameProcessor.stopStreaming();
                mFrameProcessor = null;
            }
            if (mIsonchronousManager != null) {
                try {
                    mIsonchronousManager.flushRequests();
                } catch (IOException e) {
                    Timber.i(e);
                }

                try {
                    mIsonchronousManager.setInterface(0, 0);
                } catch (IOException e) {
                    Timber.i(e);
                }

                // TODO: attempting to start/stop audio for debug purposes
                /*int success = mUsbtvControl.stopAudio();
                if (!success) {
                    Timber.e("Error stopping audio stream");
                    return;
                }*/
            }
        }
    }

    private boolean initDevice() {
        if (!mHasUsbPermission) {
            Timber.i("Do not have necessary permissions to open the device");
            return false;
        }

        UsbManager manager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

        mUsbtvConnection = manager.openDevice(mUsbtvDevice);
        if (mUsbtvConnection == null) {
            // Error, exit
            Timber.v("Error creating UsbDevice Connection");
            return false;
        }

        int intfCount = mUsbtvDevice.getInterfaceCount();
        Timber.d("Interface count: %d", intfCount);
        if (intfCount < 2) {
            Timber.e("Too few interfaces");
            return false;
        }

        // Debug output.  List all interfaces, all endpoints, and maxpacketsizes
        if (DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (int i = 0; i < intfCount; i++) {
                Timber.d("Interface number: %d", i);
                UsbInterface intf = mUsbtvDevice.getInterface(i);
                Timber.d("Alternate Setting: %d", intf.getAlternateSetting());

                int numEps = intf.getEndpointCount();
                Timber.d("Number of Endpoints: %d", numEps);
                for (int j = 0; j < numEps; j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    Timber.d("Endpoint index: %d\n" +
                            "Endpoint Address: %#x\n" +
                            "Endpoint MaxPacketSize: %d", j, ep.getAddress(),
                            ep.getMaxPacketSize());
                }
            }
        }

        // The Isochronous  audio endpoint is on Interface 0, Alternate Setting 1.  Retreive
        // it to get the max packet size (max packet size SHOULD be 3072 bytes according to dev desc)
        UsbInterface intf = mUsbtvDevice.getInterface(1);  // Should be first alternate setting
        UsbEndpoint ep = intf.getEndpoint(0);
        if (ep.getAddress() != USBTV_VIDEO_ENDPOINT) {
            Timber.v("Error, retreived endpoint address does not match, Endpoint: %d",
                    ep.getAddress());
            return false;
        }
        Timber.v("Endpoint description reported max size: %d", ep.getMaxPacketSize());
        int maxPacketSize = calculateMaxEndpointSize(ep.getMaxPacketSize());

        mUsbtvControl = new UsbTvControl(mUsbtvConnection);
        mIsonchronousManager = new UsbIso(mUsbtvConnection.getFileDescriptor(),
                MAX_ISO_PACKETS, maxPacketSize);

        try {
            mIsonchronousManager.setInterface(0, 0);
        } catch (IOException e) {
            Timber.v(e);
        }
        mIsOpen.set(true);
        return true;

    }

    /**
     * Calculates the maximum endpoint size based on the value retreived from an endpoint descriptor
     *
     * @param descMaxSize   Maximum packet size read from endpoint's descriptor
     * @return calculated endpoint size
     */
    private static int calculateMaxEndpointSize(int descMaxSize) {
        int base = descMaxSize & USB_ENDPOINT_MAXP_MASK;
        int multplier = ((descMaxSize & USB_EP_MULT_MASK) >> USB_EP_MULT_SHIFT) + 1;

        Timber.d("Endpoint Packet Base: %d\nEndpoint Multiplier: %d\nEndpoint Max Size: %d",
                base, multplier, base * multplier);

        return base * multplier;
    }

    public void deviceDetached(UsbDevice device) {
        //TODO:
    }

    public void deviceAttached(UsbDevice device) {
        //TODO:
    }


}
