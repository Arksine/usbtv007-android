package com.arksine.libusbtv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.NonNull;
import android.view.Surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;


// TODO:10/10/2017 Delete all local Usb functionality (outside of permission and open).  Create
// Native functions to communicate with JNI.  Use a handler with a seperate handler thread
// to call all Native Functions, as some of them may block.  It also makes sure that
// all calls happen from the same java thread, preventing any threading issues with the native code.
// Allowing the threads to block in native code also allows me to return a value and execute
// a Callback in the handler thread (such as onOpen, onConnected, onError, etc)


/**
 * UsbTv class.  Handles user interface for managing the usb driver
 */

public class UsbTv {
    public static final boolean DEBUG = true;  // TODO: set to false for release

    public interface DriverCallbacks {
        void onOpen(IUsbTvDriver driver, boolean status);
        void onClose();
        void onError();
    }

    public interface FrameCallback {
        void onFrameReceived(UsbTvFrame frame);
    }

    public enum TvNorm {NTSC, PAL}
    public enum InputSelection {COMPOSITE, SVIDEO}
    public enum ScanType {PROGRESSIVE, DISCARD, INTERLEAVED}
    public enum ColorControl {
        BRIGHTNESS,
        CONTRAST,
        SATURATION,
        HUE,
        SHARPNESS
    }

    private enum NativeAction {
        NONE,
        OPEN_DEVICE,
        CLOSE_DEVICE,
        START_STREAMING,
        STOP_STREAMING,
        SET_INPUT,
        SET_NORM,
        SET_SCANTYPE,
        SET_CONTROL,
        SET_SURFACE,
        SET_CALLBACK;

        private static final NativeAction[] ACTION_ARRAY = NativeAction.values();

        public static NativeAction fromOrdinal(int ordinal) {
            if (ordinal >=0 && ordinal < ACTION_ARRAY.length) {
                return ACTION_ARRAY[ordinal];
            } else {
                return NONE;
            }
        }
    }

    // TODO: Need Enums for Error

    private static final Object OPEN_LOCK = new Object();
    private static final String ACTION_USB_PERMISSION = "com.arksine.usbtv.USB_PERMISSION";
    private static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    private static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";

    /**
     * Driver Constants
     */
    private static final int USBTV_VIDEO_ENDPOINT = 0x81;
    private static final int USBTV_AUDIO_ENDPOINT = 0x83;

    /**
     * Endpoint Size Constants
     */
    private static final int USB_ENDPOINT_MAXP_MASK = 0x07ff;
    private static final int USB_EP_MULT_SHIFT = 11;
    private static final int USB_EP_MULT_MASK = 3 << USB_EP_MULT_SHIFT;

    private static boolean mUsbReceiverRegistered = false;
    private static final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            switch (action) {
                case ACTION_USB_PERMISSION: {
                    UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (dev != null) {
                        synchronized (OPEN_LOCK) {
                            for (UsbTv usbtv : mReferenceList) {
                                if (dev.equals(usbtv.mUsbtvDevice)) {
                                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                        if (!usbtv.mIsOpen.get()) {
                                            usbtv.mHasUsbPermission = true;

                                            // Since Initialization requires blocking calls
                                            // to native code, the nativehandler takes care of it
                                            Message msg = usbtv.mNativeHander.
                                                    obtainMessage(NativeAction.OPEN_DEVICE.ordinal());
                                            usbtv.mNativeHander.sendMessage(msg);
                                        } else {
                                            Timber.i("Device is already open");
                                        }
                                    } else {
                                        // Permission denied
                                        mReferenceList.remove(usbtv);
                                        Timber.v("Cannot open device, permission denied");
                                    }
                                    usbtv.mDriverCallbacks.onOpen(null, usbtv.mIsOpen.get());
                                    return;
                                }
                            }
                        }
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
    private UsbDevice mUsbtvDevice;
    private UsbDeviceConnection mUsbtvConnection;

    private boolean mHasUsbPermission = false;
    private AtomicBoolean mIsOpen = new AtomicBoolean(false);
    private AtomicBoolean mIsStreaming = new AtomicBoolean(false);

    private InputSelection mInput;
    private TvNorm mNorm;
    private ScanType mScanType;

    private DriverCallbacks mDriverCallbacks;
    private FrameCallback mFrameCallback = null;
    private Surface mDrawingSurface = null;
    private Handler mNativeHander;

    private static ArrayList<UsbTv> mReferenceList = new ArrayList<>();

    static {
        System.loadLibrary("usbtv");
    }

    public static boolean open(@NonNull UsbDevice dev,@NonNull Context appContext,
                               @NonNull DriverCallbacks cbs) {
        return open(dev, appContext, cbs, false);
    }

    public static boolean open(@NonNull UsbDevice dev, @NonNull Context appContext,
                               @NonNull DriverCallbacks cbs, boolean userManagedReceiver) {
        synchronized (OPEN_LOCK) {

            // Check to see if the received USB Device is already open
            for (UsbTv usbtv : mReferenceList) {
                if (dev.equals(usbtv.mUsbtvDevice)) {
                    Timber.i("Instance containing UsbDevice already instantiated");
                    // If the usbtv device isn't open, it should be removed as there is an error
                    if (!usbtv.mIsOpen.get()) {
                        mReferenceList.remove(usbtv);
                        break;
                    } else {
                        Timber.i("UsbTv device already open");
                        // Already open
                        return false;
                    }
                }
            }


            if (dev.getVendorId() != 0x1b71 || dev.getProductId() != 0x3002) {
                Timber.i("Not a valid UsbTv Capture device");
                return false;
            }

            // Register Usb Receiver if requested  // TODO: Don't forget to unregister it when mReferenceList is empty
            if (!userManagedReceiver && !mUsbReceiverRegistered) {
                mUsbReceiverRegistered = true;
                IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
                filter.addAction(ACTION_USB_ATTACHED);
                filter.addAction(ACTION_USB_DETACHED);
                appContext.registerReceiver(mUsbReceiver, filter);
                Timber.v("Usb Broadcast Receiver Registered");
            }

            UsbTv usbtv = new UsbTv(appContext, dev, cbs);
            mReferenceList.add(usbtv);

            UsbManager manager = (UsbManager) appContext.getSystemService(Context.USB_SERVICE);

            if (manager.hasPermission(dev)) {
                usbtv.mHasUsbPermission = true;

                // Since Initialization requires blocking calls
                // to native code, the nativehandler takes care of it
                Message msg = usbtv.mNativeHander.
                        obtainMessage(NativeAction.OPEN_DEVICE.ordinal());
                usbtv.mNativeHander.sendMessage(msg);
                return true;
            } else if (!mUsbReceiverRegistered) {
                Timber.e("Error, Usb is user managed but device does not have permission");
                return false;
            } else {
                PendingIntent mPendingIntent = PendingIntent.getBroadcast(
                        appContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
                manager.requestPermission(dev, mPendingIntent);
                return true;
            }
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

    private UsbTv(@NonNull Context appContext, @NonNull UsbDevice dev,
                  @NonNull DriverCallbacks driverCbs) {
        mContext = appContext;
        mUsbtvDevice = dev;
        mDriverCallbacks = driverCbs;

        // TODO: get from shared prefs?
        mInput = InputSelection.COMPOSITE;
        mNorm = TvNorm.NTSC;
        mScanType = ScanType.PROGRESSIVE;

        // Create HandlerThread and NativeHandler
        HandlerThread nativeThread = new HandlerThread("Native Call Thread");
        nativeThread.start();
        mNativeHander = new Handler(nativeThread.getLooper(), mNativeHandlerCallback);
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

       if (!initialize(mUsbtvConnection.getFileDescriptor(), USBTV_VIDEO_ENDPOINT, maxPacketSize,
               mInput.ordinal(), mNorm.ordinal(), mScanType.ordinal())) {
           mUsbtvConnection.close();
           return false;
       }

        mIsOpen.set(true);
        return true;

    }

    private void closeDevice() {

        synchronized (OPEN_LOCK) {
            if (mIsOpen.get()) {
                if (mIsStreaming.compareAndSet(true, false)) {
                    stopStreaming();        // Native stop streaming
                }
                dispose();  // Native Dispose, native code should stop streaming
                mIsOpen.set(false);
                mUsbtvConnection.close();
                mUsbtvConnection = null;
                mUsbtvDevice = null;
            }

            mReferenceList.remove(this);

            if (mReferenceList.isEmpty()) {
                if (mUsbReceiverRegistered) {
                    mUsbReceiverRegistered = false;
                    mContext.unregisterReceiver(mUsbReceiver);
                }
            }
        }
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

    public static void deviceDetached(UsbDevice device) {
        //TODO:
    }

    public static void deviceAttached(UsbDevice device) {
        //TODO:
    }

    // Callback From JNI
    void frameCallback(byte[] frame, int width, int height, int id) {
        if (mFrameCallback != null) {
            UsbTvFrame tvFrame = new UsbTvFrame(frame, width, height, id, mScanType, mNorm);
            mFrameCallback.onFrameReceived(tvFrame);
        }
    }

    // Native Methods
    private native boolean initialize(int fileDescriptor, int isoEndpoint, int maxIsoPacketSize,
                                      int input, int norm, int scantype);
    private native void dispose();
    private native void setSurface(Surface renderSurface);
    private native void useCallback(boolean shouldUse);
    private native boolean startStreaming();
    private native void stopStreaming();
    private native boolean setInput(int input);
    private native boolean setTvNorm(int tvNorm);
    private native boolean setScanType(int scanType);
    private native boolean setControl(int control, int value);
    private native int getControl(int control);


    private final IUsbTvDriver mDriverInterface = new IUsbTvDriver(this) {

        @Override
        public void close() {
            Message msg = mNativeHander.obtainMessage(NativeAction.CLOSE_DEVICE.ordinal());
            mNativeHander.sendMessage(msg);
        }

        @Override
        public boolean isOpen() {
            return mIsOpen.get();
        }

        @Override
        public boolean isStreaming() {
            return mIsStreaming.get();
        }

        @Override
        public void startStreaming() {
            Message msg = mNativeHander.obtainMessage(NativeAction.START_STREAMING.ordinal());
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void stopStreaming() {
            Message msg = mNativeHander.obtainMessage(NativeAction.STOP_STREAMING.ordinal());
            mNativeHander.sendMessage(msg);
        }

        @Override
        public InputSelection getInputSelection() {
            return mInput;
        }

        @Override
        public void setDrawingSurface(Surface drawingSurface) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_SURFACE.ordinal(),
                    drawingSurface);
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void setFrameCallback(FrameCallback cb) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_CALLBACK.ordinal(), cb);
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void setInput(InputSelection input) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_INPUT.ordinal(), input);
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void setNorm(TvNorm norm) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_NORM.ordinal(), norm);
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void setScanType(ScanType scanType) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_SCANTYPE.ordinal(), scanType);
            mNativeHander.sendMessage(msg);
        }

        @Override
        public void setControl(ColorControl control, int value) {
            Message msg = mNativeHander.obtainMessage(NativeAction.SET_CONTROL.ordinal());
            msg.arg1 = value;
            mNativeHander.sendMessage(msg);
        }

        @Override
        public int getColorControl(ColorControl control) {
            return getControl(control.ordinal());
        }
    };

    private final Handler.Callback mNativeHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            NativeAction action = NativeAction.fromOrdinal(message.what);

            switch(action) {
                case OPEN_DEVICE:
                    if (initDevice()) {
                        mDriverCallbacks.onOpen(mDriverInterface, true);
                    } else {
                        mReferenceList.remove(UsbTv.this);
                        mDriverCallbacks.onOpen(null, false);
                    }
                    break;
                case CLOSE_DEVICE:
                    closeDevice();
                    break;
                case START_STREAMING:
                    if (!startStreaming()) {
                        mIsStreaming.set(false);
                        mDriverCallbacks.onError();  // TODO: add error
                    } else {
                        mIsStreaming.set(true);
                    }
                    break;
                case STOP_STREAMING:
                    stopStreaming();
                    mIsStreaming.set(false);
                    break;
                case SET_INPUT:
                    mInput = (InputSelection) message.obj;
                    if (!setInput(mInput.ordinal())) {
                        mDriverCallbacks.onError();
                    }
                    break;
                case SET_NORM:
                    mNorm = (TvNorm) message.obj;
                    if (!setTvNorm(mNorm.ordinal())) {
                        mDriverCallbacks.onError();
                    }
                    break;
                case SET_SCANTYPE:
                    mScanType = (ScanType) message.obj;
                    if (!setScanType(mScanType.ordinal())) {
                        mDriverCallbacks.onError();
                    }
                    break;
                case SET_CONTROL:
                    ColorControl control = (ColorControl) message.obj;
                    if (!setControl(control.ordinal(), message.arg1)) {
                        mDriverCallbacks.onError();
                    }
                    break;
                case SET_SURFACE:
                    Surface surface = (Surface)message.obj;
                    setSurface(surface);
                    break;
                case SET_CALLBACK:
                    mFrameCallback = (FrameCallback) message.obj;
                    boolean use = mFrameCallback != null;
                    useCallback(use);
                    break;
                default:
                    Timber.i("Unknown Native Command Received");
            }
            return true;
        }
    };

}
