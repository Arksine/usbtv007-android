package com.arksine.libusbtv;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.view.Surface;

import com.sun.jna.Pointer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Retreives packets from the UsbTvDevice, parses them, then
 * sends back to the user or renders them to a surface
 */

// TODO: need a handler to render frames.  This handler will copy raw buffer to input allocation
    // excute the kernel, then call iosend on the output allocation; Do I need to syncall on it as well?

public class FrameProcessor extends Thread {
    private static final int USBTV_PACKET_SIZE = 1024;       // Packet size in bytes
    private static final int USBTV_PAYLOAD_SIZE = 960;       // Size of payload portion
    private static final int USBTV_VIDEO_ENDPOINT = 0x81;

    private Context mContext;
    private AtomicBoolean mIsStreaming = new AtomicBoolean(false);
    private UsbIso mIsonchronousManager;

    private UsbTv.RawFrameCallback mRawFrameCallback = null;

    private InputFramePool mFramePool;
    private UsbTvFrame mCurrentFrame = null;
    private FrameInfo mFrameInfo;

    // Variables for tracking frame data
    private byte[] mHeader;
    private boolean mIsSecondField = false;
    private int mPacketsPerField;
    private int mPacketsProcessed;
    private int mExpectedPacketNumber;
    private boolean mExpectedFieldOdd;

    // Render Variables
    private Surface mDrawingSurface = null;
    private Handler mParseHandler = null;
    private Handler mRenderHandler = null;
    private AtomicBoolean mRsInitialized = new AtomicBoolean(false);
    private RenderScript mRs;
    private Allocation mInputFrameAlloc;
    private Allocation mOutputFrameAlloc;
    private ScriptC_ConvertYUYV mConvertKernel;

    // Debug
    private boolean mDebugFirst = true;

    // TODO:  Base logic seems correct, however it appears that I am constantly dropping packets.
    // Is UsbIso just too slow?  Am I taking too long processing a packet between requests, causing
    // them to be overwritten in memory, or am I just taking too long to process them?

    FrameProcessor(Context context, UsbIso isoManager, FrameInfo frameInfo){
        this(context, isoManager, frameInfo, null);
    }

    FrameProcessor (Context context, UsbIso isoManager, FrameInfo frameInfo,
                           Surface drawingSurface) {
        super("FrameProcessorThread");

        mContext = context;
        mIsonchronousManager = isoManager;
        mFrameInfo = frameInfo;
        mFramePool = new InputFramePool(mFrameInfo, 4);  // 4 buffers should be enough
        mCurrentFrame = mFramePool.getInputBuffer(drawingSurface != null);

        // Frames are received as fields (odd first).  We only process one field at a time,
        // so the number of fields is divided in half
        mPacketsPerField = mFrameInfo.getFrameSizeInBytes() / 2 / USBTV_PAYLOAD_SIZE;
        mHeader = new byte[4];
        mExpectedFieldOdd = true;
        mExpectedPacketNumber = 0;

        setDrawingSurface(drawingSurface);
    }

    void setDrawingSurface(Surface surface) {
        mDrawingSurface = surface;
        if (mDrawingSurface != null) {
            initRenderScript();
            mOutputFrameAlloc.setSurface(mDrawingSurface);
        }
    }

    void stopStreaming() {
        mIsStreaming.set(false);
        mFramePool.dispose();
    }

    void setRawFrameCallback(UsbTv.RawFrameCallback cb) {
        mRawFrameCallback = cb;
    }

    @Override
    public void run() {
        mIsStreaming.set(true);
        while (mIsStreaming.get()) {
            try {
                UsbIso.Request req = mIsonchronousManager.reapRequest(true);

                int numPackets = req.getPacketCount();
                for (int i = 0; i < numPackets; i++) {
                    int status = req.getPacketStatus(i);
                    if (status != 0) {
                        Timber.v("Invalid packet received, status: %d", status);
                        // TODO: if status is enodev, enoent, econnreset, or eshutdown I need
                        // to break from the loop?
                        continue;
                    }

                    int packetlength = req.getPacketActualLength(i);

                    // Get packet and send it for processing
                    if (packetlength > 0) {
                        ByteBuffer packet = req.getPacketDataAsByteBuffer(i, packetlength);
                        if (mDebugFirst) {
                            Timber.d("Packet Length: %d", packetlength);
                            Timber.d("Byte Buffer Order: %s", packet.order().toString());
                            Timber.d("Byte Buffer Postion: %d", packet.position());
                            Timber.d("Byte Buffer Limit: %d", packet.limit());
                            // TODO: get other debug data
                        }
                        int packetCount = packetlength / USBTV_PACKET_SIZE;
                        for (int j = 0; j < packetCount; j++) {
                            packet.position(j * USBTV_PACKET_SIZE);
                            processPacket(packet);
                        }
                    }// else {
                    //    Timber.d("Empty packet Recd");
                   // }
                }

                req.initialize(USBTV_VIDEO_ENDPOINT);
                req.submit();
            } catch (Exception e) {
                Timber.i(e);
            }
        }
    }


    private void processPacket(ByteBuffer packet) {
        packet.get(mHeader);

        if (mDebugFirst) {
            Timber.d("Got Packet Header");
        }

        if (mCurrentFrame == null) {
            // Current working frame not set, attempt to retrieve one from the pool
            mCurrentFrame = mFramePool.getInputBuffer(mDrawingSurface != null);
            if (mCurrentFrame == null) {
                // No frame in pool, skip the packet until one is available.
                Timber.d("No Frames available in Frame Pool");
                return;
            }
        }

        if (frameCheck(mHeader)) {
            int id = getFrameId(mHeader);
            boolean isFieldOdd = isPacketOdd(mHeader) != 0;
            int packetNumber = getPacketNumber(mHeader);

            if (mDebugFirst) {
                mDebugFirst = false;
                Timber.d("Frame Id: %d", id);
                Timber.d("Is Field Odd: %b", isFieldOdd);
                Timber.d("Packet Number: %d", packetNumber);
            }

            if (packetNumber >= mPacketsPerField) {
                Timber.d("Packet number exceeds maximum packets per Field");
                Timber.d("Frame Id: %d", id);
                Timber.d("Is Field Odd: %b", isFieldOdd);
                Timber.d("Packet Number: %d", packetNumber);
                return;
            } else if (mExpectedPacketNumber != packetNumber) {
                Timber.d("Unexpected packet number received, Expected: %d", mExpectedPacketNumber);
                Timber.d("Frame Id: %d", id);
                Timber.d("Is Field Odd: %b", isFieldOdd);
                Timber.d("Packet Number: %d", packetNumber);
                return;
            } else if (mExpectedFieldOdd != isFieldOdd) {
                Timber.d("Unexpected Field Received");
                Timber.d("Frame Id: %d", id);
                Timber.d("Is Field Odd: %b", isFieldOdd);
                Timber.d("Packet Number: %d", packetNumber);
                return;
            }

            if (packetNumber == 0) {
                mCurrentFrame.setFrameId(id);
                mPacketsProcessed = 0;
            } else if (mCurrentFrame.getFrameId() != id) {
                Timber.d("Frame ID mismatch. Current: %d Received :%d",
                        mCurrentFrame.getFrameId(), id);
                return;
            }

            // Copy data to buffer in current tvFrame
            switch (mCurrentFrame.getScanType()) {
                case PROGRESSIVE_60: {
                    writeProgressiveBuf(packetNumber, packet);
                    break;
                }
                case PROGRESIVE_30: {
                    if (isFieldOdd) {
                        writeProgressiveBuf(packetNumber, packet);
                    }
                    break;
                }
                case INTERLEAVED: {
                    writeInterleavedBuf(packetNumber, packet, isFieldOdd);
                    break;
                }
                default:
                   // Don't really need to do anything
            }

            // if callback is provided
            mPacketsProcessed++;
            mExpectedPacketNumber++;

            // All parts of a field have been received
            if (packetNumber == (mPacketsPerField - 1)) {
                if (mPacketsProcessed != mPacketsPerField) {
                    Timber.d("Fewer Packets processed than packets per field");
                    return;
                    // TODO: Frame error.  Should create a flag in usbtvframe
                    // show the error rather than returning?
                }

                // An entire frame has been written to the buffer. Process by ScanType.
                //  - For progressive 60, execute color conversion and render here.
                //  - For progressive 30 only render if this is an ODD frame.
                //  - For interleaved only render after an entire frame is received.
                switch (mCurrentFrame.getScanType()){
                    case PROGRESSIVE_60:
                        processFrame();
                        break;
                    case PROGRESIVE_30:
                        // Discard frames with even fields
                        if (isFieldOdd) {
                            processFrame();
                        }
                        break;
                    case INTERLEAVED:
                        if (mIsSecondField) {
                            processFrame();
                            mIsSecondField = false;
                        } else if(isFieldOdd) {
                            // This is the last packet in an odd field.
                            // Set Frame complete to be true on next pass, but only if the
                            // current frame is an odd field
                            mIsSecondField = true;
                        }
                        break;
                }

                mExpectedPacketNumber = 0;
                mExpectedFieldOdd = !mExpectedFieldOdd;
            }
        } else {
            Timber.d("Invalid Packet Header");
        }
    }

    private void initRenderScript () {
        if (mRsInitialized.compareAndSet(false, true)) {
            // Setup the handler
            HandlerThread renderThread = new HandlerThread("Render Thread",
                    Process.THREAD_PRIORITY_DISPLAY);
            renderThread.start();
            mRenderHandler = new Handler(renderThread.getLooper(), mRenderHandlerCallback);

            mRs = RenderScript.create(mContext);
            Element inputElement = Element.U8_4(mRs);
            Element outputElement = Element.RGBA_8888(mRs);
            int frameSize;
            switch (mFrameInfo.getScanType()) {
                case INTERLEAVED:
                    frameSize = mFrameInfo.getFrameSizeInBytes();
                    break;
                case PROGRESSIVE_60:
                    frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                    break;
                case PROGRESIVE_30:
                    frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                    break;
                default:
                    frameSize = mFrameInfo.getFrameSizeInBytes();
            }
            int inputSize = frameSize / 4;
            int outputSize = inputSize * 2;
            mInputFrameAlloc = Allocation.createSized(mRs, inputElement, inputSize);
            mOutputFrameAlloc = Allocation.createSized(mRs, outputElement, outputSize,
                    Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);
            mConvertKernel = new ScriptC_ConvertYUYV(mRs);
            mConvertKernel.set_output(mOutputFrameAlloc);
        }
    }


    /**
     * Reads packet from memory directly into a progressive buffer.  The location
     * of the buffer depends on the packet index.
     * @param packetNo  The number identifying the packet (range 0 - 359)
     * @param packet ByteBuffer representing the incoming usb packet
     */
    private void writeProgressiveBuf(int packetNo, ByteBuffer packet) {
        int bufferOffset = packetNo * USBTV_PAYLOAD_SIZE;
        byte[] buf = mCurrentFrame.getFrameBuf();
        packet.get(buf, bufferOffset, USBTV_PAYLOAD_SIZE);
    }

    /**
     * Reads packet from memory into an interleaved buffer.  Because a packet contains 2/3 of a
     * line, packets must be read in half.  If the packetNo is even, it is the beginning of a line,
     * if the packetNo is odd then the packet is split between lines.
     *
     * @param packetNo The number identifying the packet (range 0 - 359)
     * @param packet ByteBuffer representing the incoming usb packet
     * @param isOdd Identifies whether the current packet contains an odd or even field
     */
    private void writeInterleavedBuf(int packetNo, ByteBuffer packet, boolean isOdd) {
        int packetHalf ;
        int halfPayloadSize = USBTV_PAYLOAD_SIZE / 2;
        int oddFieldOffset = (isOdd) ? 0 : 1; // TODO: shouldn't odd lines be written to odd lines in the buffer?
        int lineSize = mCurrentFrame.getWidth() * 2;  // Line width in bytes
        byte[] buf = mCurrentFrame.getFrameBuf();

        // Operate on the Packet in halves.
        for (packetHalf = 0; packetHalf < 2; packetHalf++){
            // Get the overall index of the half I am operating on.
            int partIndex = packetNo * 2 + packetHalf;

            // 3 parts makes two lines, so the line index is determined by dividing the part index
            // by 3 and multiplying by 2.  The oddFieldOffset is added to write to the correct
            // line in the buffer
            int lineIndex = (partIndex / 3) * 2 + oddFieldOffset;

            // the starting byte of a line is determined by the lineIndex * lineSize.
            // From there we can determine how far into the line we need to begin our write.
            // packetNumber MOD 3 == 0 - start at beginning of line
            // packetNumber MOD 3 == 2 - offset an entire payload
            // packetNumber MOD 3 == 1 - offset half of a packet payload
            int bufferOffset = (lineIndex * lineSize) + (halfPayloadSize * (packetNo % 3));

            packet.get(buf, bufferOffset, halfPayloadSize);
        }
    }

    /**
     * Processes a complete Frame.  If a framecallback is sent, the frame is sent to the user.
     * The user COULD manipulate the buffer here.  If a surface is set, the frame will
     * be sent to a handler for rendering.
     */
    private void processFrame() {
        if (mRawFrameCallback != null) {
            mRawFrameCallback.onRawFrameReceived(mCurrentFrame);
        }

        if (mDrawingSurface != null) {
            Message msg = mRenderHandler.obtainMessage();
            msg.obj = mCurrentFrame;
            mRenderHandler.sendMessage(msg);
        } else {
            mCurrentFrame.setLocked(false);
            mCurrentFrame.returnFrame();
        }

        mCurrentFrame = mFramePool.getInputBuffer(mDrawingSurface != null);
    }

    private final Handler.Callback mRenderHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {

            UsbTvFrame frameToRender = (UsbTvFrame)message.obj;

            // Copy frame to input allocaton
            byte[] buf = frameToRender.getFrameBuf();
            if (mInputFrameAlloc.getBytesSize() == buf.length) {
                mInputFrameAlloc.copyFromUnchecked(buf);
            } else {
                Timber.d("Buffer/Allocation size mismatch.\n" +
                        "Buffer Size: %d" +
                        "Allocation Size: %d", buf.length, mInputFrameAlloc.getBytesSize());
                return true;
            }

            // Return frame to Frame Pool
            frameToRender.setLocked(false);
            frameToRender.returnFrame();

            mConvertKernel.forEach_convertFromYUYV(mInputFrameAlloc);
            mOutputFrameAlloc.ioSend();  // Send output frame to surface
            return true;
        }
    };

    private static boolean frameCheck(byte[] header) {
        return (header[0] == (byte)0x88);
    }

    private static int getFrameId(byte[] header) {
        return (header[1] & 0xff);
    }

    private static int isPacketOdd(byte[] header) {
        return ((header[2] & 0xf0) >> 7);
    }

    private static int getPacketNumber (byte[] header) {
        int num = (header[3] & 0xff);
        num |= ((header[2] & 0x0f) << 8);
        return num;
    }

}
