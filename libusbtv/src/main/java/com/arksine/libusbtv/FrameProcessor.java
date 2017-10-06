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
    private boolean mIsSecondField = false;
    private int mPacketsPerField;
    private int mPacketsProcessed;
    private int mExpectedPacketNumber;
    private boolean mExpectedFieldOdd;

    // Render Variables
    private Surface mDrawingSurface = null;
    private Handler mRenderHandler = null;
    private AtomicBoolean mRsInitialized = new AtomicBoolean(false);
    private RenderScript mRs;
    private Allocation mInputFrameAlloc;
    private Allocation mOutputFrameAlloc;
    private ScriptC_ConvertYUYV mConvertKernel;

    // Debug
    private boolean mDebugFirst = true;

    FrameProcessor(Context context, UsbIso isoManager, FrameInfo frameInfo){
        this(context, isoManager, frameInfo, null);
    }

    FrameProcessor (Context context, UsbIso isoManager, FrameInfo frameInfo,
                           Surface drawingSurface) {
        super("FrameProcessorThread");
        this.setPriority(MAX_PRIORITY); // This should be a high priority thread

        mContext = context;
        mIsonchronousManager = isoManager;
        mFrameInfo = frameInfo;
        mFramePool = new InputFramePool(mFrameInfo, 4);  // 4 buffers should be enough
        mCurrentFrame = mFramePool.getInputBuffer(drawingSurface != null);

        // Frames are received as fields (odd first).  We only process one field at a time,
        // so the number of fields is divided in half
        mPacketsPerField = mFrameInfo.getFrameSizeInBytes() / 2 / USBTV_PAYLOAD_SIZE;

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

                    if (mDebugFirst) {
                        int packetlength = req.getPacketActualLength(i);
                        Timber.d("Packet Length: %d", packetlength);
                        // TODO: get other debug data
                    }

                    // Get packet and send it for processing
                    UsbTvPacket packet = req.getTvPacket(i);
                    processPacket(packet);
                }

                req.initialize(USBTV_VIDEO_ENDPOINT);
                req.submit();
            } catch (Exception e) {
                Timber.i(e);
            }
        }
    }





    private void processPacket(UsbTvPacket packet) {
        int count = packet.getNumberOfFrames();
        for (int i = 0; i < count; i++) {
            int header = packet.getPacketHeader(i);
            if (mDebugFirst) {
                Timber.d("Got Packet Header");
            }

            if (frameCheck(header) && mCurrentFrame != null) {
                int id = getFrameId(header);
                boolean isFieldOdd = isPacketOdd(header) != 0;
                int packetNumber = getPacketNumber(header);

                if (mDebugFirst) {
                    mDebugFirst = false;
                    Timber.d("Frame Id: %d", id);
                    Timber.d("Is Field Odd: %b", isFieldOdd);
                    Timber.d("Packet Number: %d", packetNumber);
                }

                if (packetNumber >= mPacketsPerField) {
                    Timber.d("Packet number exceeds maximum packets per Field");
                    return;
                }

                if (packetNumber != mExpectedPacketNumber) {
                    Timber.d("Unexpected Packet Number received.\n" +
                            "Expected: %d Receved %d", mExpectedPacketNumber, packetNumber);
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

                if (isFieldOdd != mExpectedFieldOdd) {
                    Timber.d("Odd field mismatch.  Expected Odd Field: %b Received: %b",
                            mExpectedFieldOdd, isFieldOdd);
                    return;
                }


                // Copy data to buffer in current tvFrame
                switch (mCurrentFrame.getScanType()) {
                    case PROGRESSIVE_60: {
                        writeProgressiveBuf(packetNumber, packet.getPayloadPointer(i));
                        break;
                    }
                    case PROGRESIVE_30: {
                        if (isFieldOdd) {
                            writeProgressiveBuf(packetNumber, packet.getPayloadPointer(i));
                        }
                        break;
                    }
                    case INTERLEAVED: {
                        writeInterleavedBuf(packetNumber, packet.getPayloadPointer(i), isFieldOdd);
                        break;
                    }
                }

                // if callback is provided
                mPacketsProcessed++;
                mExpectedPacketNumber++;

                // All parts of a field have been received
                if (packetNumber == mPacketsPerField + 1) {
                    if (mPacketsProcessed != mPacketsPerField) {
                        // TODO: Frame error.  Should create a flag in usbtvframe
                        // show the error
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
                            } else {
                                // Set Frame complete to be true on next pass
                                mIsSecondField = true;
                            }
                            break;
                    }

                    mExpectedPacketNumber = 0;
                    mExpectedFieldOdd = !mExpectedFieldOdd;
                }

            } else {
                Timber.d("Invalid Packet");
            }

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
     * @param payload A pointer to the memory address containing the incoming payload
     */
    private void writeProgressiveBuf(int packetNo, Pointer payload) {
        int bufferOffset = packetNo * USBTV_PAYLOAD_SIZE;
        byte[] buf = mCurrentFrame.getFrameBuf();
        payload.read(0, buf, bufferOffset, USBTV_PAYLOAD_SIZE);
    }

    /**
     * Reads packet from memory into an interleaved buffer.  Because a packet contains 2/3 of a
     * line, packets must be read in half.  If the packetNo is even, it is the beginning of a line,
     * if the packetNo is odd then the packet is split between lines.
     *
     * @param packetNo The number identifying the packet (range 0 - 359)
     * @param payload A pointer to the memory address containing the incoming payload
     * @param isOdd Identifies whether the current packet contains an odd or even field
     */
    private void writeInterleavedBuf(int packetNo, Pointer payload, boolean isOdd) {
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

            payload.read((packetHalf * halfPayloadSize), buf, bufferOffset, halfPayloadSize);
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
