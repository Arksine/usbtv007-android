package com.arksine.libusbtv;

import android.support.annotation.NonNull;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * A class that manages a list of Input Frame buffers.  A buffer is checked out when
 * a new frame is received
 */
class JavaFramePool {

    private ConcurrentLinkedQueue<UsbTvFrame> mJavaFramePool;
    private AtomicReference<JavaFramePool> mSelf = new AtomicReference<>(null);

    JavaFramePool(@NonNull DeviceParams params, int bufferCount) {
        if (bufferCount <= 0) {
            // must have atleast one buffer
            Timber.i("Invalid buffer count received: %d", bufferCount);
            bufferCount = 1;
        }

        mSelf.set(this);

        // Initialize allocation buffers
        mJavaFramePool = new ConcurrentLinkedQueue<>();
        for(int i = 0; i < bufferCount; i++) {
            UsbTvFrame frame = new UsbTvFrame(mSelf, params);
            mJavaFramePool.add(frame);
        }
    }

    /**
     * Retreives a buffer from the pool
     *

     * @return  A buffer from the pool, or null if none are available
     */
    UsbTvFrame getLocalBuffer() {
        return mJavaFramePool.poll();
    }

    /**
     * Returns a buffer to the pool
     *
     * @param buf the buffer to return
     */
    void returnBuffer(@NonNull UsbTvFrame buf) {
        mJavaFramePool.add(buf);
    }

    /**
     * Removes all buffers from the list and clears the self atomic reference
     */
    void dispose() {
        mSelf.set(null);
        mJavaFramePool.clear();
    }
}
