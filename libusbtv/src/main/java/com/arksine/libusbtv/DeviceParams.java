package com.arksine.libusbtv;

import android.support.annotation.NonNull;

// TODO: add frame pool size
/**
 * TODO: This will also have a "Device Type" when I make this library more generic.  That
 * also means that there will be a color space paramater
 */
public class DeviceParams {
    private final UsbTv.DriverCallbacks mCallbacks;
    private final boolean mUseInternalReceiver;
    private final int mFramePoolSize;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private final int mFrameSizeBytes;
    private final UsbTv.TvNorm mNorm;
    private final UsbTv.ScanType mScanType;
    private final UsbTv.InputSelection mInput;

    private DeviceParams(Builder builder) {
        mCallbacks = builder.callbacks;
        mUseInternalReceiver = builder.useInternalReceiver;
        mFramePoolSize = builder.framePoolSize;
        mFrameWidth = builder.frameWidth;
        mFrameHeight = builder.frameHeight;
        mFrameSizeBytes = mFrameWidth * mFrameHeight * 2;  // TODO: this would change if the colorspace is different
        mNorm = builder.norm;
        mScanType = builder.scanType;
        mInput = builder.input;
    }

    public UsbTv.DriverCallbacks getDriverCallbacks() {
        return mCallbacks;
    }

    public boolean isUsingInternalReceiver() {
        return mUseInternalReceiver;
    }

    public int getFrameWidth() {
        return mFrameWidth;
    }

    public int getFrameHeight() {
        return mFrameHeight;
    }

    public int getFrameSizeInBytes() {
        return mFrameSizeBytes;
    }

    public UsbTv.TvNorm getTvNorm() {
        return mNorm;
    }

    public UsbTv.ScanType getScanType() {
        return mScanType;
    }

    public UsbTv.InputSelection getInputSelection() {
        return mInput;
    }

    public int getFramePoolSize() {
        return mFramePoolSize;
    }

    public static class Builder {
        private UsbTv.DriverCallbacks callbacks;
        private boolean useInternalReceiver;
        private int framePoolSize;
        private int frameWidth;
        private int frameHeight;
        private UsbTv.TvNorm norm;
        private UsbTv.ScanType scanType;
        private UsbTv.InputSelection input;

        public Builder() {
            callbacks = null;
            useInternalReceiver = true;
            framePoolSize = 4;
            norm = UsbTv.TvNorm.NTSC;
            scanType = UsbTv.ScanType.PROGRESSIVE;
            input = UsbTv.InputSelection.COMPOSITE;
        }

        public Builder(@NonNull DeviceParams params) {
            callbacks = params.mCallbacks;
            useInternalReceiver = params.mUseInternalReceiver;
            framePoolSize = params.mFramePoolSize;
            frameWidth = params.mFrameWidth;
            frameHeight = params.mFrameHeight;
            norm = params.mNorm;
            scanType = params.mScanType;
            input = params.mInput;
        }

        public Builder setCallbacks(@NonNull UsbTv.DriverCallbacks cbs) {
            callbacks = cbs;
            return this;
        }

        public Builder useLibraryReceiver(boolean use) {
            useInternalReceiver = use;
            return this;
        }

        public Builder setFramePoolSize(int poolSize) {
            framePoolSize = poolSize;
            return this;
        }

        public Builder setTvNorm(UsbTv.TvNorm nrm) {
            norm = nrm;
            return this;
        }

        public Builder setScanType(UsbTv.ScanType st) {
            scanType = st;
            return this;
        }

        public Builder setInput(UsbTv.InputSelection in) {
            input = in;
            return this;
        }

        public DeviceParams build() {
            if (callbacks == null) {
                callbacks = new UsbTv.DriverCallbacks() {
                    @Override
                    public void onOpen(IUsbTvDriver driver, boolean status) {}

                    @Override
                    public void onClose() {}

                    @Override
                    public void onError() {}
                };
            }
            setFrameSize();
            return new DeviceParams(this);
        }

        private void setFrameSize() {
            switch (norm) {
                case NTSC:
                    frameWidth = 720;
                    frameHeight = 480;
                    break;
                case PAL:
                    frameWidth = 720;
                    frameHeight = 576;
                    break;
                default:
                    frameWidth = 720;
                    frameHeight = 480;
            }

            // Non-interleaved frames are half height
            if (scanType != UsbTv.ScanType.INTERLEAVED) {
                frameHeight /= 2;
            }
        }
    }
}
