package com.arksine.libusbtv;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.support.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import timber.log.Timber;

/**
 * A class that manages a list of Input allocation buffers.  A buffer is checked out when
 * a new frame is received
 */
public class InputAllocationManager {

    private List<Allocation> mAllocationBuf;

    public InputAllocationManager(@NonNull RenderScript rs, int numElements, int bufferCount) {
        if (bufferCount <= 0) {
            // must have atleast one buffer
            Timber.i("Invalid buffer count received: %d", bufferCount);
            bufferCount = 1;
        }

        if (numElements <= 0 || (numElements % 2) != 0) {
            // Number of elements must be even, and it must be greater than zero
            // TODO: what should I do here?
        }

        // Initialize allocation buffers
        mAllocationBuf = Collections.synchronizedList(new ArrayList<Allocation>(bufferCount));
        Element inElement = Element.U8_4(rs);
        for(int i = 0; i < bufferCount; i++) {
            Allocation alloc = Allocation.createSized(rs, inElement, numElements);
            mAllocationBuf.add(alloc);
        }
    }

    /**
     * Retreives a buffer from the list of allocations.
     *
     * @return An available allocation, or null if none are available
     */
    public Allocation getAllocationBuffer() {
        Allocation inBuf = null;

        // Retreive the first Allocation in the list
        if (!mAllocationBuf.isEmpty()) {
            inBuf = mAllocationBuf.remove(0);
        }
        return inBuf;
    }

    /**
     * Returns an allocation to the buffer
     *
     * @param buf the allocation to return
     */
    public void returnAllocation(@NonNull Allocation buf) {
        mAllocationBuf.add(buf);
    }

    /**
     * Removes all buffers from the list
     */
    public void dispose() {
        mAllocationBuf.clear();
    }
}
