// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license
package com.arksine.libusbtv;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Encapsulates a Frame received from the UsbTv device.  It may be passed back to the user
 * through a onFrameReceivedListener, or it will be used to render to a surface
 */

public class UsbTvFrame {

    private AtomicBoolean mLocked = new AtomicBoolean(false);
    private int mFrameId;
    private int mFlags;                         // For future use
    private final ByteBuffer mFrameBuf;
    private final int mPoolIndex;
    private final DeviceParams mParams;

    UsbTvFrame(DeviceParams params, ByteBuffer frameBuf, int poolIndex) {
        mFrameBuf = frameBuf;
        mParams = params;
        mPoolIndex = poolIndex;
        mFlags = 0;
        mFrameId = -1;
    }

    /**
     * A lock prevents a frame from being returned to the native frame pool.  This
     * unlocks the frame, allo
     */
    void unlock() {
        mLocked.set(false);
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

    void setFrameId(int id) {
        mFrameId = id;
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

        UsbTvFrame frame = new UsbTvFrame(mParams, clone, -1);
        frame.mFrameId = mFrameId;
        frame.mLocked.set(true);
        return frame;
    }

    /**
     * Returns the Frame to the native frame pool
     */
    public void returnFrame() {
        mFrameBuf.rewind();

        // Lock the frame after a return, so it cannot be returned twice
        if (mLocked.compareAndSet(false, true)) {
            if (!returnFrameToPool(mPoolIndex)) {
                Timber.d("Error returning frame to pool");
            }
        }
    }

    public UsbTvFrame returnAndCopy() {
        UsbTvFrame frame = copyOfFrame();
        returnFrame();
        return frame;
    }

    private native boolean returnFrameToPool(int index);

}
