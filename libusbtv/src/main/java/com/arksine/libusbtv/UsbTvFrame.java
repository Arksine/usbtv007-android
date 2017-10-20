package com.arksine.libusbtv;

import java.nio.ByteBuffer;

import timber.log.Timber;

/**
 * Encapsulates a Frame received from the UsbTv device.  It may be passed back to the user
 * through a onFrameReceivedListener, or it will be used to render to a surface
 */

public class UsbTvFrame {

    private final ByteBuffer mFrameBuf;
    private final int mFrameId;
    private final int mPoolIndex;
    private final DeviceParams mParams;

    public UsbTvFrame(DeviceParams params, ByteBuffer frameBuf,  int poolIndex, int frameId) {
        mFrameBuf = frameBuf;
        mParams = params;
        mPoolIndex = poolIndex;
        mFrameId = frameId;
    }

    public ByteBuffer getFrameBuf() {
        return mFrameBuf;
    }

    /**
     * Returns the Frame Width in pixels
     */
    public int getWidth() {
        return mParams.getFrameWidth();
    }

    /**
     * Returns the Frame Height in pixels.
     */
    public int getHeight() {
        return mParams.getFrameHeight();
    }

    public UsbTv.ScanType getScanType() {
        return mParams.getScanType();
    }

    public UsbTv.TvNorm getFrameTvNorm() {
        return mParams.getTvNorm();
    }

    public int getFrameId() {
        return mFrameId;
    }


    /**
     * Creates a deep copy of the frame.  Frame copies do not keep
     * the original's pool index, so they cannot be returned to
     * the native frame pool.
     *
     * @return A new UsbTvFrame that is a copy of the current frame
     */
    public UsbTvFrame copyOfFrame() {
        ByteBuffer clone = ByteBuffer.allocate(mFrameBuf.capacity());
        mFrameBuf.rewind();
        clone.put(mFrameBuf);
        mFrameBuf.rewind();
        clone.flip();

        return new UsbTvFrame(mParams, clone, -1, mFrameId);
    }

    /**
     * Returns the Frame to the native frame pool
     */
    public void returnFrame() {
        mFrameBuf.rewind();
        if (!returnFrameToPool(mPoolIndex)) {
            Timber.d("Error returning frame to pool");
        }
    }

    public UsbTvFrame returnAndCopy() {
        UsbTvFrame frame = copyOfFrame();
        returnFrame();
        return frame;
    }

    private native boolean returnFrameToPool(int index);

}
