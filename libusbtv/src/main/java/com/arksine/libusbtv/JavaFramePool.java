package com.arksine.libusbtv;

import android.support.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * A class that manages a list of Input Frame buffers.  A buffer is checked out when
 * a new frame is received
 */
class JavaFramePool {

    private List<UsbTvFrame> mJavaFrameBuf;
    private AtomicReference<JavaFramePool> mSelf = new AtomicReference<>(null);

    public JavaFramePool(DeviceParams params, int bufferCount) {
        if (bufferCount <= 0) {
            // must have atleast one buffer
            Timber.i("Invalid buffer count received: %d", bufferCount);
            bufferCount = 1;
        }

        mSelf.set(this);

        // Initialize allocation buffers
        mJavaFrameBuf = Collections.synchronizedList(new ArrayList<UsbTvFrame>(bufferCount));
        for(int i = 0; i < bufferCount; i++) {
            UsbTvFrame frame = new UsbTvFrame(mSelf, params);
            mJavaFrameBuf.add(frame);
        }
    }

    /**
     * Retreives a buffer from the pool
     *
     * @param locked  Sets the buffer to be locked to the frame renderer or not
     * @return  A buffer from the pool, or null if none are available
     */
    public UsbTvFrame getInputBuffer(boolean locked) {
        UsbTvFrame inBuf = null;

        // Retreive the first Allocation in the list
        if (!mJavaFrameBuf.isEmpty()) {
            inBuf = mJavaFrameBuf.remove(0);
            inBuf.setLocked(locked);
        }
        return inBuf;
    }

    /**
     * Returns a buffer to the pool
     *
     * @param buf the buffer to return
     */
    public void returnBuffer(@NonNull UsbTvFrame buf) {
        mJavaFrameBuf.add(buf);
    }

    /**
     * Removes all buffers from the list and clears the self atomic reference
     */
    public void dispose() {
        mSelf.set(null);
        mJavaFrameBuf.clear();
    }
}
