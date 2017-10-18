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
            long renderStartTime = 0;
            int frameCount = 60;

            while(mThreadRunning.get()) {
                try {
                    frame = mFrameQueue.take();
                } catch (InterruptedException e) {
                    break;
                }

                renderStartTime = System.currentTimeMillis();

                // Copy frame to input allocaton
                byte[] buf = frame.getFrameBuf();

                if (buf.length != mInputAllocation.getBytesSize()) {
                    Timber.e("Incoming frame buffer size, does not match input allocation size");
                    mThreadRunning.set(false);
                    return;
                }

                mInputAllocation.copyFromUnchecked(buf);

                mConvertKernel.forEach_convertFromYUYV(mInputAllocation);
                mOutputAllocation.ioSend();  // Send output frame to surface

                if (frameCount >= 60) {
                    frameCount = 0;
                    Timber.d("Frame Render Time: %d ms", System.currentTimeMillis() - renderStartTime);
                } else {
                    frameCount++;
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
