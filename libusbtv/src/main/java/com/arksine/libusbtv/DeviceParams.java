package com.arksine.libusbtv;

import android.hardware.usb.UsbDevice;
import android.support.annotation.NonNull;


/**
 * TODO: This will also have a "Device Type" when I make this library more generic.  That
 * also means that there will be a color space parameter
 */
public class DeviceParams {
    private final UsbDevice mDevice;
    private final int mFileDescriptor;
    private final int mVideoEndpoint;
    private final int mAudioEndpoint;
    private final int mVideoUrbPacketSize;
    private final int mAudioUrbPacketSize;
    private final int mVideoPacketsPerField;
    private final boolean mCaptureAudio;
    private final UsbTv.DriverCallbacks mCallbacks;
    private final int mFramePoolSize;
    private final int mFrameWidth;
    private final int mFrameHeight;
    private final int mFrameSizeBytes;
    private final UsbTv.TvNorm mNorm;
    private final UsbTv.ScanType mScanType;
    private final UsbTv.InputSelection mInput;

    private DeviceParams(Builder builder) {
        mDevice = builder.device;
        mFileDescriptor = builder.fileDescriptor;
        mVideoEndpoint = builder.videoEndpoint;
        mAudioEndpoint = builder.audioEndpoint;
        mVideoUrbPacketSize = builder.videoUrbPacketSize;
        mAudioUrbPacketSize = builder.audioUrbPacketSize;
        mVideoPacketsPerField = builder.videoPacketsPerField;
        mCaptureAudio = builder.captureAudio;
        mCallbacks = builder.callbacks;
        mFramePoolSize = builder.framePoolSize;
        mFrameWidth = builder.frameWidth;
        mFrameHeight = builder.frameHeight;
        mFrameSizeBytes = mFrameWidth * mFrameHeight * 2;  // TODO: this would change if the colorspace is different
        mNorm = builder.norm;
        mScanType = builder.scanType;
        mInput = builder.input;
    }
    public UsbDevice getUsbDevice() {
        return mDevice;
    }

    public int getFileDescriptor() {
        return mFileDescriptor;
    }

    public int getVideoEndpoint() {
        return mVideoEndpoint;
    }

    public int getAudioEndpoint() {
        return mAudioEndpoint;
    }

    public int getVideoUrbPacketSize() {
        return mVideoUrbPacketSize;
    }

    public int getAudioUrbPacketSize() {
        return mAudioUrbPacketSize;
    }

    public int getVideoPacketsPerField() {
        return mVideoPacketsPerField;
    }

    public boolean isAudioEnabled() {
        return mCaptureAudio;
    }

    public UsbTv.DriverCallbacks getDriverCallbacks() {
        return mCallbacks;
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
        private UsbDevice device;
        private int fileDescriptor;
        private int videoEndpoint;
        private int audioEndpoint;
        private int videoUrbPacketSize;
        private int audioUrbPacketSize;
        private int videoPacketsPerField;
        private boolean captureAudio;
        private UsbTv.DriverCallbacks callbacks;
        private int framePoolSize;
        private int frameWidth;
        private int frameHeight;
        private UsbTv.TvNorm norm;
        private UsbTv.ScanType scanType;
        private UsbTv.InputSelection input;

        public Builder() {
            device = null;
            fileDescriptor = -1;
            videoEndpoint = -1;
            audioEndpoint = -1;
            videoUrbPacketSize = 0;
            audioUrbPacketSize = 0;
            videoPacketsPerField = 0;
            captureAudio = false;
            callbacks = null;
            framePoolSize = 4;
            norm = UsbTv.TvNorm.NTSC;
            scanType = UsbTv.ScanType.PROGRESSIVE;
            input = UsbTv.InputSelection.COMPOSITE;
        }

        public Builder(@NonNull DeviceParams params) {
            device = params.mDevice;
            fileDescriptor = params.mFileDescriptor;
            videoEndpoint = params.mVideoEndpoint;
            audioEndpoint = params.mAudioEndpoint;
            videoUrbPacketSize = params.mVideoUrbPacketSize;
            audioUrbPacketSize = params.mAudioUrbPacketSize;
            videoPacketsPerField = params.mVideoPacketsPerField;
            captureAudio = params.mCaptureAudio;
            callbacks = params.mCallbacks;
            framePoolSize = params.mFramePoolSize;
            frameWidth = params.mFrameWidth;
            frameHeight = params.mFrameHeight;
            norm = params.mNorm;
            scanType = params.mScanType;
            input = params.mInput;
        }

        public Builder setUsbDevice(@NonNull UsbDevice capDevice) {
            device = capDevice;
            return this;
        }

        public Builder setAudioCapture(boolean enabled) {
            captureAudio = enabled;
            return this;
        }

        public Builder setDriverCallbacks(@NonNull UsbTv.DriverCallbacks cbs) {
            callbacks = cbs;
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

        // TODO: if the USB Device hasnt been set, should I do something here?
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

            videoPacketsPerField = (frameWidth * frameHeight) / UsbTv.USBTV_PAYLOAD_SIZE;

            // Non-interleaved frames are half height
            if (scanType != UsbTv.ScanType.INTERLEAVED) {
                frameHeight /= 2;
            }
        }

        /*
         * The Following Paramaters can ONLY be set by the drive, as it determines
         * which values are valid.  Thus, they are given package local access.
         */

        Builder setFileDescriptor(int fd) {
            fileDescriptor = fd;
            return this;
        }

        Builder setVideoEndpoint(int vidEp) {
            videoEndpoint = vidEp;
            return this;
        }

        Builder setAudioEp(int audEp) {
            audioEndpoint = audEp;
            return this;
        }

        Builder setVideoUrbPacketSize(int packetSize) {
            videoUrbPacketSize = packetSize;
            return this;
        }

        Builder setAudioUrbPacketSize(int packetSize) {
            audioUrbPacketSize = packetSize;
            return this;
        }

    }
}
