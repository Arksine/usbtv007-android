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
import android.support.annotation.NonNull;
import android.support.v7.appcompat.BuildConfig;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * UsbtvDriver class.  Handles user interface for managing the usb driver
 */

public class UsbtvDriver {

    public interface DriverCallbacks {
        void onOpen(boolean status);
        void onClose();
        void onFrameReceived(byte[] frame);
    }

    public interface RawFrameCallback {
        void onRawFrameReceived(byte[] rawframe);
    }

    public enum TvNorm {NTSC, PAL}
    public enum InputSelection {COMPOSITE, SVIDEO}
    public enum ScanType {PROGRESSIVE_60, PROGRESIVE_30, INTERLEAVED}

    private static final String ACTION_USB_PERMISSION = "com.arksine.usbtv.USB_PERMISSION";
    private static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    private static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";

    private static final int USBTV_PACKET_SIZE = 1024;       // Packet size in bytes
    private static final int USBTV_PAYLOAD_SIZE = 960;       // Size of payload portion

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
                        if (dev.equals(mUsbtvDevice)) {
                            mDriverCallbacks.onOpen(initDevice());
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
    private UsbIso mIsonchronousManager;
    private UsbDevice mUsbtvDevice;
    private UsbDeviceConnection mUsbtvConnection;
    private UsbtvControl mUsbtvControl;

    private boolean mHasUsbPermission = false;
    private boolean mUserManagedUsbEvents = false;
    private AtomicBoolean mIsOpen = new AtomicBoolean(false);
    private AtomicBoolean mIsStreaming = new AtomicBoolean(false);

    private TvNorm mTvNorm;
    private InputSelection mInput;
    private int mPixelWidth;
    private int mPixelHeight;

    private DriverCallbacks mDriverCallbacks;
    private RawFrameCallback mRawFrameCallback = null;
    private Surface mDrawingSurface = null;

    public UsbtvDriver(@NonNull Context appContext, @NonNull DriverCallbacks driverCbs,
                       boolean userManagedEvents) {
        mContext = appContext;
        mDriverCallbacks = driverCbs;
        mUserManagedUsbEvents = userManagedEvents;

        // TODO: get from shared prefs?
        setTvNorm(TvNorm.NTSC);
        setInput(InputSelection.COMPOSITE);
    }

    public void setRawFrameCallback(RawFrameCallback cb) {
        mRawFrameCallback = cb;
    }

    public void setTvNorm(TvNorm norm) {
        mTvNorm = norm;
        switch (mTvNorm) {
            case NTSC:
                mPixelWidth = 720;
                mPixelHeight = 480;
                break;

            case PAL:
                mPixelWidth = 720;
                mPixelHeight = 480;
                break;
        }

        // TODO: if streaming, restart
    }

    public void setInput(InputSelection input) {

        mInput = input;
        // TODO: if streaming, restart
    }

    public void setDrawingSurface(Surface surface) {
        mDrawingSurface = surface;
        // TODO: set surface for video data handler
    }

    /**
     * Gets a list of Usbtv007 devices connected to the system
     *
     * @param context   Application context
     * @return a list of connected usbtv devices, or an empty list if none are connected
     */
    public static List<UsbDevice> enumerateUsbtvDevices(Context context) {
        List<UsbDevice> devList = new ArrayList<>(3);

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
     * TODO: implement user callbacks?  OnOpen, OnClose, OnFrameReceived?  RawFrameCallback
     *
     * @param usbtvDevice the device to open
     */
    public void open(@NonNull UsbDevice usbtvDevice) {
        if (mIsOpen.get()) {
            mDriverCallbacks.onOpen(true);
            return;
        }

        if (!mUserManagedUsbEvents && !mUsbReceiverRegistered) {
            mUsbReceiverRegistered = true;
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            filter.addAction(ACTION_USB_ATTACHED);
            filter.addAction(ACTION_USB_DETACHED);
            mContext.registerReceiver(mUsbReceiver, filter);
        }

        mUsbtvDevice = usbtvDevice;
        UsbManager manager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

        if (manager.hasPermission(mUsbtvDevice)) {
            mHasUsbPermission = true;
            mDriverCallbacks.onOpen(initDevice());
        } else if (!mUsbReceiverRegistered) {
            Timber.e("Error, Usb is user managed but device does not have permission");
            mDriverCallbacks.onOpen(false);
        } else {
            PendingIntent mPendingIntent = PendingIntent.getBroadcast(
                    mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
            manager.requestPermission(mUsbtvDevice, mPendingIntent);
        }

    }

    /**
     * Closes Usbtv007 usb device.  Unregisters usb reciever if it is registerd and frees
     * all resources
     */
    public void close() {
        if (mUsbReceiverRegistered) {
            mUsbReceiverRegistered = false;
            mContext.unregisterReceiver(mUsbReceiver);
        }
        // TODO: should probably synchronize open and close if they can be called on diff threads
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

    /**
     * Starts usb streaming.  Sends initialization registers via control transfer, sets the
     * tv norm and input, initializes and submits isonchronous urb requests, and starts
     * a thread that listens for such requests.
     *
     * @return true on success or if already streaming.  False on error or if device is not open
     */
    public boolean startStreaming() {
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
                return false;
            }

            success = mUsbtvControl.initializeVideo();
            if (!success) {
                Timber.e("Error initializing video stream");
                return false;
            }

            if (mTvNorm == TvNorm.NTSC) {
                success = mUsbtvControl.setTvNormNtsc();
                if (!success) {
                    Timber.e("Error setting TV Norm");
                    return false;
                }
            } else {
                success = mUsbtvControl.setTvNormPal();
                if (!success) {
                    Timber.e("Error setting TV Norm");
                    return false;
                }
            }

            if (mInput == InputSelection.COMPOSITE) {
                success = mUsbtvControl.setVideoInputComposite();
                if (!success) {
                    Timber.e("Error setting video input");
                    return false;
                }
            } else {
                success = mUsbtvControl.setVideoInputSVideo();
                if (!success) {
                    Timber.e("Error setting video input");
                    return false;
                }
            }

            try {
                mIsonchronousManager.setInterface(0, 1);
            } catch (IOException e) {
                Timber.i(e);
                success = false;
            }

            if (!success) {
                Timber.e("Error setting interface to alternate setting 1");
                return false;
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
                return false;
            }

            mIsStreaming.set(true);
            Thread isoRequestThread = new Thread(mIsoRequestRunnable, "Isonchronous Request Thread");
            isoRequestThread.start();
            return true;
        }

        return mIsStreaming.get();
    }

    /**
     * Stops device from streaming video.  Flushes all urb requests and set the interface
     * altsetting to 0.
     */
    public void stopStreaming() {
        mIsStreaming.set(false);
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
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            for (int i = 0; i < intfCount; i++) {
                Timber.d("Interface number: %d", i);
                UsbInterface intf = mUsbtvDevice.getInterface(i);
                Timber.d("Alternate Setting: %d", intf.getAlternateSetting());

                int numEps = intf.getEndpointCount();
                Timber.d("Number of Endpoints: %d", numEps);
                for (int j = 0; j < numEps; j++) {
                    UsbEndpoint ep = intf.getEndpoint(j);
                    Timber.d("Endpoint index: %d\n" +
                            "Endpoint Address: %d\n" +
                            "Endpoint MaxPacketSize: %d", i, ep.getAddress(),
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

        mUsbtvControl = new UsbtvControl(mUsbtvConnection);
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
    private int calculateMaxEndpointSize(int descMaxSize) {
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

    private final Runnable mIsoRequestRunnable = new Runnable() {
        @Override
        public void run() {
            while (mIsStreaming.get()) {
                boolean first = true;  // For debugging to make sure the byte order of the buffer is what I want
                try {
                    UsbIso.Request req = mIsonchronousManager.reapRequest(true);

                    int numPackets = req.getPacketCount();
                    for (int i = 0; i < numPackets; i++) {
                        int status = req.getPacketStatus(i);
                        if (i != 0) {
                            Timber.v("Invalid packet received, status: %d", status);
                            // TODO: if status is enodev, enoent, econnreset, or eshutdown I need
                            // to break from the loop?
                            continue;
                        }
                        int packetlength = req.getPacketActualLength(i);
                        if (packetlength > 0) {
                            //byte[] packetdata = new byte[packetlength];
                            //req.getPacketData(i, packetdata, packetlength);
                            ByteBuffer packetData = req.getPacketDataAsByteBuffer(i, packetlength);
                            if (first) {
                                Timber.d("Packet Length: %d", packetlength);
                                Timber.d("ByteBuffer Limit is: %d", packetData.limit());
                                Timber.d("ByteBuffer Byte Order %s", packetData.order().toString());

                                first = false;
                            }
                            packetData.order(ByteOrder.BIG_ENDIAN);
                            for (int offset = 0; offset * USBTV_PACKET_SIZE < packetlength; offset++) {
                                packetData.position(offset * USBTV_PACKET_SIZE);
                                int header = packetData.getInt();
                                byte[] payload = new byte[USBTV_PAYLOAD_SIZE];
                                packetData.get(payload);
                                // TODO: send header and payload to handler for processing
                            }

                        }
                    }

                    req.initialize(USBTV_VIDEO_ENDPOINT);
                    req.submit();
                } catch (Exception e) {
                    Timber.i(e);
                }
            }
        }
    };

}
