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

import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Retreives packets from the UsbTvDevice, parses them, then
 * sends back to the user or renders them to a surface
 */


public class FrameProcessor extends Thread {
    private static final boolean DEBUG = true;

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

    private byte[] mPacketBuffer;
    private long mProfileTime;


    // Variables for tracking frame data
    private boolean mIsSecondField = false;
    private int mPacketsPerField;
    private int mPacketsProcessed;

    // Debug Variables
    private int mExpectedId = -1;
    private int mExpectedPacket = 0;

    // Render Variables
    private Surface mDrawingSurface = null;
    private Handler mRenderHandler = null;
    private AtomicBoolean mRsInitialized = new AtomicBoolean(false);
    private RenderScript mRs;
    private Allocation mInputFrameAlloc;
    private Allocation mOutputFrameAlloc;
    private ScriptC_ConvertYUYV mConvertKernel;


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
        mPacketBuffer = new byte[3072];  // TODO: the packet size is temporary, I should get it
                                        // from the UsbTv class which calculates it from the endpoint


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
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        mIsStreaming.set(true);
        while (mIsStreaming.get()) {
            try {
                UsbIso.Request req = mIsonchronousManager.reapRequest(true);

                int numPackets = req.getPacketCount();
                for (int i = 0; i < numPackets; i++) {
                    mProfileTime = System.nanoTime();
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
                        req.getPacketData(i, mPacketBuffer, packetlength);
                        int count = packetlength / USBTV_PACKET_SIZE;
                        for (int j = 0; j < count; j++) {
                            processPacket(mPacketBuffer, j * USBTV_PACKET_SIZE);
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


    private void processPacket(byte[] packet, int startIndex) {

        if (mCurrentFrame == null) {
            // Current working frame not set, attempt to retrieve one from the pool
            mCurrentFrame = mFramePool.getInputBuffer(mDrawingSurface != null);
            if (mCurrentFrame == null) {
                // No frame in pool, skip the packet until one is available.
                Timber.d("No Frames available in Frame Pool");
                return;
            }
        }

        if (frameCheck(packet[startIndex])) {
            int id = getFrameId(packet[startIndex + 1]);
            boolean isFieldOdd = getPacketOdd(packet[startIndex + 2]) != 0;
            int packetNumber = getPacketNo(packet, startIndex + 2);

            if (DEBUG) {
                if(mExpectedId != id) {
                    Timber.d("Frame id: %d\n" +
                            "Packets dropped: %d\n" +
                                    "Time MicroSeconds: %d",
                            mExpectedId,
                            360 - packetNumber,
                            (System.nanoTime() - mProfileTime)/1000);
                    mExpectedId = id;
                }

                if (mExpectedPacket != packetNumber) {
                    Timber.d("Frame id: %d\n" +
                             "Packets dropped: %d\n" +
                                    "Time MicroSeconds: %d",
                            mExpectedId,
                            packetNumber - mExpectedPacket,
                            (System.nanoTime() - mProfileTime)/1000);
                    mExpectedPacket = packetNumber;
                }

                if (packetNumber == 359) {
                    mExpectedId++;
                    mExpectedPacket = 0;
                } else {
                    mExpectedPacket++;
                }
                return;
            }

            if (packetNumber >= mPacketsPerField) {
                Timber.d("Packet number exceeds maximum packets per Field");
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
                    writeProgressiveBuf(packetNumber, packet, startIndex + 4);
                    break;
                }
                case PROGRESIVE_30: {
                    if (isFieldOdd) {
                        writeProgressiveBuf(packetNumber, packet, startIndex + 4);
                    }
                    break;
                }
                case INTERLEAVED: {
                    writeInterleavedBuf(packetNumber, packet, startIndex + 4, isFieldOdd);
                    break;
                }
                default:
                   // Don't really need to do anything
            }

            // if callback is provided
            mPacketsProcessed++;

            // All parts of a field have been received
            if (packetNumber == (mPacketsPerField - 1)) {
                if (mPacketsProcessed != mPacketsPerField) {
                    Timber.d("Fewer Packets processed than packets per field");
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

            }
        } else {
         //   Timber.d("Invalid Packet Header");
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
    private void writeProgressiveBuf(int packetNo, byte[] packet, int payloadIndex) {
        int bufferOffset = packetNo * USBTV_PAYLOAD_SIZE;
        byte[] buf = mCurrentFrame.getFrameBuf();
        System.arraycopy(packet, payloadIndex, buf, bufferOffset, USBTV_PAYLOAD_SIZE);
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
    private void writeInterleavedBuf(int packetNo, byte[] packet,int payloadIndex, boolean isOdd) {
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

            System.arraycopy(packet, payloadIndex, buf, bufferOffset, halfPayloadSize);
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

    private static boolean frameCheck(byte checkByte) {
        return (checkByte == (byte)0x88);
    }

    private static int getFrameId(byte idByte) {
        return (idByte & 0xff);
    }

    private static int getPacketOdd(byte oddByte) {
        return ((oddByte & 0xf0) >> 7);
    }

    private static int getPacketNo (byte[] packet, int start) {
        int num = (packet[start + 1] & 0xff);
        num |= ((packet[start] & 0x0f) << 8);
        return num;
    }
}
