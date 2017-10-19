package com.arksine.libusbtv;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encapsulates a Frame received from the UsbTv device.  It may be passed back to the user
 * through a onFrameReceivedListener, or it will be used to render to a surface
 */

public class UsbTvFrame {

    private AtomicReference<JavaFramePool> mFramePool;

    private final byte[] mFrame;
    private int mFrameId;
    private final DeviceParams mParams;

    public UsbTvFrame(AtomicReference<JavaFramePool> pool, DeviceParams params) {
        mFramePool = pool;
        mFrame = new byte[params.getFrameSizeInBytes()];
        mParams = params;
    }

    public byte[] getFrameBuf() {
        return mFrame;
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

    void setFrameId(int id) {
        this.mFrameId = id;
    }


    /**
     * Creates a deep copy of the frame.  Frame copies do not have
     * references to the frame pool and are locked, so they cannot
     * be returned to the pool.
     *
     * @return A new UsbTvFrame that is a copy of the current frame
     */
    public UsbTvFrame copyOfFrame() {
        UsbTvFrame frame = new UsbTvFrame(null, mParams);
        System.arraycopy(mFrame, 0, frame.mFrame, 0, mFrame.length);
        frame.mFrameId = mFrameId;

        return frame;
    }

    /**
     * Returns the Frame to the managed frame pool
     */
    public void returnFrame() {
        if (mFramePool != null) {
            JavaFramePool pool = mFramePool.get();
            if (pool != null) {
                mFrameId = 0; // reset frame Id
                pool.returnBuffer(this);
            }
        }
    }

    public UsbTvFrame returnAndCopy() {
        UsbTvFrame frame = copyOfFrame();
        returnFrame();
        return frame;
    }

}
