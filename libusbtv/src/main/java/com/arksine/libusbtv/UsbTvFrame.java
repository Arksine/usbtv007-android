package com.arksine.libusbtv;

/**
 * Encapsulates a Frame received from the UsbTv device.  It may be passed back to the user
 * through a onFrameReceivedListener, or it will be used to render to a surface
 */

public class UsbTvFrame {

    private final byte[] mFrame;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private final int mFrameId;
    private final UsbTv.ScanType mScanType;
    private final UsbTv.TvNorm mTvNorm;

    public UsbTvFrame(byte[] frame, int width, int height, int frameId,
                      UsbTv.ScanType scan, UsbTv.TvNorm norm) {
        mFrame = frame;
        mFrameWidth = width;
        mFrameHeight = height;
        mFrameId = frameId;
        mScanType = scan;
        mTvNorm = norm;


    }

    public byte[] getFrameBuf() {
        return mFrame;
    }

    /**
     * Returns the Frame Width in pixels
     */
    public int getWidth() {
        return mFrameWidth;
    }

    /**
     * Returns the Frame Height in pixels.
     */
    public int getHeight() {
        return mFrameHeight;
    }

    public UsbTv.ScanType getScanType() {
        return mScanType;
    }

    public UsbTv.TvNorm getFrameTvNorm() {
        return mTvNorm;
    }

    public int getFrameId() {
        return mFrameId;
    }

}
