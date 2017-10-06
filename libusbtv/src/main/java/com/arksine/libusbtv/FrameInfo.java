package com.arksine.libusbtv;

import android.graphics.ImageFormat;

/**
 * Class that contains all basic information of a frame;
 */

public class FrameInfo {
    private final int mFormat;
    private final UsbTv.ScanType mScanType;
    private final UsbTv.TvNorm mTvNorm;
    private final int mFrameWidth;
    private final int mFrameHeight;

    public FrameInfo(int imageFormat, UsbTv.ScanType scanType, UsbTv.TvNorm norm) {
        mFormat = imageFormat;
        mScanType = scanType;
        mTvNorm = norm;

        int height;
        switch (norm) {
            case NTSC:
                mFrameWidth = 720;
                mFrameHeight = 480;
                break;
            case PAL:
                mFrameWidth = 720;
                mFrameHeight = 576;
                break;
            default:
                mFrameWidth = 720;
                mFrameHeight = 480;
        }


    }

    public int getBytesPerPixel() {
        return (ImageFormat.getBitsPerPixel(mFormat) / 8);
    }

    public int getFrameSizeInBytes() {
        return (mFrameWidth * mFrameHeight * getBytesPerPixel());
    }

    public int getImageFormat() {
        return mFormat;
    }

    public UsbTv.ScanType getScanType() {
        return mScanType;
    }

    public UsbTv.TvNorm getTvNorm() {
        return mTvNorm;
    }

    public int getFrameWidth() {
        return mFrameWidth;
    }

    public int getFrameHeight() {
        return mFrameHeight;
    }
}
