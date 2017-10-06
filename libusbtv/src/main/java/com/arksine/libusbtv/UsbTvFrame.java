package com.arksine.libusbtv;


import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Encapsulates a Frame received from the UsbTv device.  It may be passed back to the user
 * through a RawFrameCallback, or it will be used to render to a surface
 */

public class UsbTvFrame {

    private AtomicReference<InputFramePool> mFramePool;
    private AtomicBoolean mLocked = new AtomicBoolean(false);

    private byte[] mFrame;
    private FrameInfo mFrameInfo;

    private int mFrameId = 0;
    private int mFramePixelHeight;


    public UsbTvFrame(AtomicReference<InputFramePool> pool, FrameInfo fInfo) {
        mFramePool = pool;
        mFrameInfo = fInfo;

        int frameSize;
        switch (mFrameInfo.getScanType()) {
            case INTERLEAVED:
                frameSize = mFrameInfo.getFrameSizeInBytes();
                mFramePixelHeight = mFrameInfo.getFrameHeight();
                break;
            case PROGRESSIVE_60:
                frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                mFramePixelHeight = mFrameInfo.getFrameHeight() / 2;
                break;
            case PROGRESIVE_30:
                frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                mFramePixelHeight = mFrameInfo.getFrameHeight() / 2;
                break;
            default:
                frameSize = mFrameInfo.getFrameSizeInBytes();
                mFramePixelHeight = mFrameInfo.getFrameHeight();
        }

        mFrame = new byte[frameSize];
    }

    public void copyToFrameBuf(byte[] src, int srcPos, int destPos, int length) {
        System.arraycopy(src, srcPos, mFrame, destPos, length);
    }

    public byte[] getFrameBuf() {
        return mFrame;
    }

    /**
     * Returns the Frame Width in pixels
     */
    public int getWidth() {
        return mFrameInfo.getFrameWidth();
    }

    /**
     * Returns the Frame Height in pixels.  Because the frame may be half the height
     * of a reported frame, it must be calculated based on the scan type and returned.
     */
    public int getHeight() {
        return mFramePixelHeight;
    }

    public UsbTv.ScanType getScanType() {
        return mFrameInfo.getScanType();
    }

    public UsbTv.TvNorm getFrameTvNorm() {
        return mFrameInfo.getTvNorm();
    }


    public int getFrameId() {
        return mFrameId;
    }

    void setFrameId(int mFrameId) {
        this.mFrameId = mFrameId;
    }

    void setLocked(boolean isLocked) {
        this.mLocked.set(isLocked);
    }


    /**
     * Creates a deep copy of the frame.  Frame copies do not have
     * references to the frame pool and are locked, so they cannot
     * be returned to the pool.
     *
     * @return A new UsbTvFrame that is a copy of the current frame
     */
    public UsbTvFrame copyOfFrame() {
        UsbTvFrame frame = new UsbTvFrame(null, mFrameInfo);
        System.arraycopy(mFrame, 0, frame.mFrame, 0, mFrame.length);
        frame.mFrameId = mFrameId;
        frame.mLocked.set(true);  // Do not allow copies to be returned to the frame pool

        return frame;
    }

    /**
     * Returns the Frame to the managed frame pool
     */
    public void returnFrame() {
        if (!mLocked.get()) {
            mFrameId = 0; // reset frame Id considering
            InputFramePool pool = mFramePool.get();
            if (pool != null) {
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
