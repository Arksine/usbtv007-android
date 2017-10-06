package com.arksine.libusbtv;

import android.renderscript.RenderScript;
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
class InputFramePool {

    private List<UsbTvFrame> mAllocationBuf;
    private AtomicReference<InputFramePool> mSelf = new AtomicReference<>(null);

    public InputFramePool(FrameInfo info, int bufferCount) {
        if (bufferCount <= 0) {
            // must have atleast one buffer
            Timber.i("Invalid buffer count received: %d", bufferCount);
            bufferCount = 1;
        }

        mSelf.set(this);

        // Initialize allocation buffers
        mAllocationBuf = Collections.synchronizedList(new ArrayList<UsbTvFrame>(bufferCount));
        for(int i = 0; i < bufferCount; i++) {
            UsbTvFrame frame = new UsbTvFrame(mSelf, info);
            mAllocationBuf.add(frame);
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
        if (!mAllocationBuf.isEmpty()) {
            inBuf = mAllocationBuf.remove(0);
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
        mAllocationBuf.add(buf);
    }

    /**
     * Removes all buffers from the list and clears the self atomic reference
     */
    public void dispose() {
        mSelf.set(null);
        mAllocationBuf.clear();
    }
}
