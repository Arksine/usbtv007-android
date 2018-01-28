package com.arksine.libusbtv;

/**
 * These Parameters are set by the driver.  Unlike DeviceParams, the DriverParams
 * cannot be set by the user and they are not subject to change (unless the
 * device type itself changes)
 */

// TODO: I'm going to keep this for now, but I don't need it;
class DriverParams {
    private final int mFileDescriptor;
    private final int mVideoEndpoint;
    private final int mAudioEndpoint;
    private final int mVideoUrbPacketSize;
    private final int mAudioUrbPacketSize;

    DriverParams(int fd, int vidEp, int audEp, int vidUrbSize, int audUrbSize) {
        mFileDescriptor = fd;
        mVideoEndpoint = vidEp;
        mAudioEndpoint = audEp;
        mVideoUrbPacketSize = vidUrbSize;
        mAudioUrbPacketSize = audUrbSize;
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
}
