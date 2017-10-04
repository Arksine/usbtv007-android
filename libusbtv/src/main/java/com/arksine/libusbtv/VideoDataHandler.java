package com.arksine.libusbtv;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.renderscript.Allocation;
import android.renderscript.RenderScript;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Handles Video Data received from Usbtv device.  Builds video frames out of "chunks" received.
 */

public class VideoDataHandler extends Handler {
    private static final int USBTV_PACKET_SIZE = 1024;       // Packet size in bytes
    private static final int USBTV_PAYLOAD_SIZE = 960;       // Size of payload portion

    // TODO: This handler has to process packets received from the usb device and build frames
    // Need logic to do that.  When a frame is built, it either needs to be sent back to the
    // user via callback, or processed internally by a renderscript kernel to do color conversion
    // and deinterlacing

    // TODO: May not need the driver callback.  Instead crate a surface and render to it.
    // Allow the user to get that surface.  Will need to setup renderscript and write
    // directly to allocations.  Write to two dimensional array for raw buffer? 720 x 480?

    // Notes:  Video data is received in "chunks".  The linux driver has some confusing documentation
    // on this.  It claims a that a chunk is 256-bits (32 bytes), and in another
    // it claims that the image portion of a chunk is 240 words(960 bytes).
    // My assumption is that it meant a 256 word chunk (1024 bytes), where the
    // first word (4 bytes) is the header, the next 240 words are data, and the remaining 15 words
    // are padding.  This would fit with the reported maxpacketsize

    // TODO: 10/3/2017
    /**
     * After doing some calculations I beleive I understand how frames are received from the device.
     * They are not sent interleaved, but rather as scans of even and oddd lines.
     * These scans are referred to as frames, each scan should have a unique id.
     * So basically it already comes deinterlaced, simply at half height.  The linux kernel driver
     * actually interleaves the lines, odd first (the driver claims it de-interlaces, but it actually
     * does the opposite).
     *
     * The driver documentation states that odd scans are received first, followed by even.  The
     * code logic suggests the opposite, as frames are submitted after the last odd chunk is received.
     * I need to dive deeper to figure out what is going on there.
     */

    private UsbtvDriver.ScanType mScanType;
    private UsbtvDriver.RawFrameCallback mRawFrameCallback = null;
    private byte[] rawFrameBuffer;

    // Variables for tracking frame data
    private int mFrameId = 0;
    private int mPacketsPerFrame;
    private int mPacketsProcessed;
    private int mExpectedPacketNumber;
    private boolean mExpectedFieldOdd;

    // Renderscript Variables
    private Surface mDrawingSurface = null; // TODO: do I need this?
    private AtomicBoolean mRsInitialized = new AtomicBoolean(false);
    private RenderScript rs;
    private Allocation input;  // TODO: I probably need a class to manage multiple input buffers so I don't overwrite as the allocation is rendering
    private Allocation output;
    // TODO: create a renderscript



    public VideoDataHandler(Looper looper, int width, int height) {
        super(looper);
        mPacketsPerFrame = width * height / USBTV_PAYLOAD_SIZE;
    }

    public void initialize() {}


    @Override
    public void handleMessage(Message msg) {
        if (mDrawingSurface == null && mRawFrameCallback == null) {
            //
        }
        int header = msg.what;
        byte[] data = (byte[])msg.obj;
        processPacket(header, data);
    }

    public void setRawFrameCallback(UsbtvDriver.RawFrameCallback cb) {
        mRawFrameCallback = cb;
    }

    public void setDrawingSurface(Surface surface) {
        mDrawingSurface = surface;
        if (mDrawingSurface != null) {
            // TODO: init renderscript
        }
    }


    private void processPacket(int header, byte[] payload) {

        if (frameCheck(header)) {
            int id = getFrameId(header);
            boolean isFieldOdd = isPacketOdd(header) != 0;
            int packetNumber = getPacketNumber(header);

            if (packetNumber >= mPacketsPerFrame) {
                Timber.d("Packet number exceeds maximum packets per frame");
                return;
            }

            if (packetNumber != mExpectedPacketNumber) {
                Timber.d("Unexpected Packet Number received.\n" +
                        "Expected: %d Receved %d", mExpectedPacketNumber, packetNumber);
                resetVariables();
                return;
            }

            if (packetNumber == 0) {
                mFrameId = id;
                mPacketsProcessed = 0;
            } else if (mFrameId != id) {
                Timber.d("Frame ID mismatch. Current: %d Received :%d",
                        mFrameId, id);
                resetVariables();
                return;
            }

            if (isFieldOdd != mExpectedFieldOdd) {
                Timber.d("Odd field mismatch.  Expected Odd Field: %b Received: %b",
                        mExpectedFieldOdd, isFieldOdd);
                resetVariables();
                return;
            }

            // TODO: Copy data to allocation if using surface, or copy to raw buffer.  How it is
            // copied depends on the scantype and whether or not a rawframecallback is provided

            // if callback is provided
            mPacketsProcessed++;
            mExpectedPacketNumber++;

            // All parts of a field have been received
            if (packetNumber == mPacketsPerFrame + 1) {
                if (mPacketsProcessed != mPacketsPerFrame) {
                    // TODO: Frame error, frame is not whole.  Discard it?
                }
                // TODO:
                // An entire frame has been written to the buffer.
                // First, I need to check to see if what kind of scantype is requested:
                //  - For progressive 60, execute color conversion and render here.
                //  - For progressive 30 only render if this is an ODD frame.  Even frames are discarded
                //  - For interleaved only render after an entire frame is received.
                //
                // If using a raw buffer, simply execute the provided callback rather than render

                mExpectedPacketNumber = 0;
                mExpectedFieldOdd = !mExpectedFieldOdd;
            }

        } else {
            Timber.d("Invalid Packet");
        }
    }

    private void resetVariables() {
        mExpectedPacketNumber = 0;
        mExpectedFieldOdd = true;

        // TODO: need to reset vars for buffer/allocation offsets as well
    }

    private static boolean frameCheck(int chunkHeader) {
        return (chunkHeader & 0xff000000) == 0x88000000;
    }

    private static int getFrameId(int chunkHeader) {
        return (chunkHeader & 0x00ff0000) >> 16;
    }

    private static int isPacketOdd(int chunkHeader) {
        return (chunkHeader & 0x0000f000) >> 15;
    }

    private static int getPacketNumber (int chunkHeader) {
        return (chunkHeader & 0x00000fff);
    }

}
