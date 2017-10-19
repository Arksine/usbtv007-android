package com.arksine.libusbtv;


import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.support.annotation.NonNull;
import android.view.Surface;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * TODO: Now that I track deviceparams use them to initialize allocations rather
 * than doing so when a frame is recd
 */

public class UsbTvRenderer {

    private Thread mRenderThread;
    private Surface mRenderSurface;
    private RenderScript mRs;
    private Allocation mInputAllocation = null;
    private Allocation mOutputAllocation = null;
    private ScriptC_ConvertYUYV mConvertKernel;
    private AtomicBoolean mThreadRunning = new AtomicBoolean(false);

    private BlockingQueue<UsbTvFrame> mFrameQueue = new ArrayBlockingQueue<UsbTvFrame>(10, true);

    private final Runnable mRenderRunnable = new Runnable() {
        @Override
        public void run() {
            UsbTvFrame frame;
            mThreadRunning.set(true);
            Timber.d("Render Thread Started");

            //  TODO: profiling vars can be removed after testing
            /*
            *   Update:  Profile testing on an Xperia Z3 running lollipop show that it typically takes around
            *   1 ms to copy, convert, and call iosend on a half frame (720 x 240), with spikes as
            *   high as 9 ms.  That is pretty good, I doubt that an interleaved frame would take
            *   much longer, which leaves open the possibility for advanced deinterlacing
            *   algorithms.
            *
            *   Tests on a Samsung Galaxy Tab Pro 8.4 running Lineage OS 14.1 dont fare as well.
            *   Render times are as low as 2ms, but spike to 30ms.  This causes frame drops.
            *   The OS can't load the adreno renderscript library, so renderscript relies on a
             *  fallback.  This is probably a bug in Lineage OS and it is the
             *  likely cause of poor rendering performance.
             *
             */
            long renderTime;
            long highTime = 0;
            long lowTime = 1000;
            int frameCount = 0;

            while(mThreadRunning.get()) {
                try {
                    frame = mFrameQueue.take();
                } catch (InterruptedException e) {
                    break;
                }

                renderTime = System.currentTimeMillis();

                // Copy frame to input allocaton
                byte[] buf = frame.getFrameBuf();

                if (buf.length != mInputAllocation.getBytesSize()) {
                    Timber.e("Incoming frame buffer size, does not match input allocation size");
                    mThreadRunning.set(false);
                    return;
                }

                mInputAllocation.copyFromUnchecked(buf);

                frame.returnFrame();  // Return Frame to its Pool so it can be reused

                mConvertKernel.forEach_convertFromYUYV(mInputAllocation);
                mOutputAllocation.ioSend();  // Send output frame to surface

                /*
                Profile every 120 frames (Every two seconds)
                 */
                renderTime = System.currentTimeMillis() - renderTime;
                highTime = (renderTime > highTime) ? renderTime : highTime;
                lowTime = (renderTime < lowTime) ? renderTime : lowTime;
                frameCount++;
                if (frameCount >= 120) {
                    frameCount = 0;
                    Timber.d("Last 120 Frames - High Render Time: %d ms\nLow Render Time: %d ms", highTime, lowTime);
                    highTime = 0;
                    lowTime  = 1000;

                }
            }
        }
    };

    UsbTvRenderer(@NonNull Context context, Surface surface) {

        mRs = RenderScript.create(context);
        mConvertKernel = new ScriptC_ConvertYUYV(mRs);
        mRenderSurface = surface;
    }

    public void processFrame(UsbTvFrame frame) {
        if (!mFrameQueue.offer(frame)) {
            Timber.d("Frame skipped, queue full");
        }
    }

    public void setSurface(Surface surface) {
        mRenderSurface = surface;
        if (mOutputAllocation != null && mRenderSurface != null) {
            mOutputAllocation.setSurface(mRenderSurface);
        }
    }

    public void startRenderer(DeviceParams params) {
        if (!mThreadRunning.get()) {
            initAllocations(params);
            mRenderThread = new Thread(mRenderRunnable, "Render Thread");
            mRenderThread.start();
        }
    }

    public void stopRenderer() {
        if (mThreadRunning.compareAndSet(true, false)) {
            try {
                mRenderThread.join(500);
            } catch (InterruptedException e) {
                Timber.d(e);
            }

            if (mRenderThread.isAlive()) {
                mRenderThread.interrupt();
            }
        }
    }

    public boolean isRunning() {
        return mThreadRunning.get();
    }


    private void initAllocations (DeviceParams params) {
        Element inputElement = Element.U8_4(mRs);
        Type.Builder outputType = new Type.Builder(mRs, Element.RGBA_8888(mRs));
        outputType.setX(params.getFrameWidth());
        outputType.setY(params.getFrameHeight());
        int inputSize = params.getFrameSizeInBytes() / 4;

        mInputAllocation = Allocation.createSized(mRs, inputElement, inputSize, Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(mRs, outputType.create(),
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        if (mRenderSurface != null) {
            mOutputAllocation.setSurface(mRenderSurface);
        }
        mConvertKernel.set_output(mOutputAllocation);
        mConvertKernel.set_width(params.getFrameWidth());

    }

}
