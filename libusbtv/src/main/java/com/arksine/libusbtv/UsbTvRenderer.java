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
import android.view.Surface;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import timber.log.Timber;

/**
 * Created by Eric on 10/7/2017.
 */

public class UsbTvRenderer extends Thread {

    private Surface mRenderSurface;
    private RenderScript mRs;
    private Allocation mInputAllocation = null;
    private Allocation mOutputAllocation = null;
    private ScriptC_ConvertYUYV mConvertKernel;
    private int currentFrameSize = 0;
    private AtomicBoolean mThreadRunning = new AtomicBoolean(false);

    private BlockingQueue<UsbTvFrame> mFrameQueue = new ArrayBlockingQueue<UsbTvFrame>(10, true);

    UsbTvRenderer(Context context, Surface surface) {
        super("Render Thread");
        mRs = RenderScript.create(context);
        mConvertKernel = new ScriptC_ConvertYUYV(mRs);
        mRenderSurface = surface;

        this.start();

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

    public void stopRenderer() {
        if (mThreadRunning.compareAndSet(true, false)) {
            try {
                this.join(500);
            } catch (InterruptedException e) {

            }

            if (this.isAlive()) {
                this.interrupt();
            }
        }
    }

    @Override
    public void run() {
        UsbTvFrame frame;
        mThreadRunning.set(true);
        Timber.d("Render Thread Started");

        while(mThreadRunning.get()) {
            try {
                frame = mFrameQueue.take();
            } catch (InterruptedException e) {
                break;
            }

            // Copy frame to input allocaton
            byte[] buf = frame.getFrameBuf();

            if (currentFrameSize != buf.length) {
                currentFrameSize = buf.length;
                initAllocations(frame);
                // Drop this frame
                continue;
            }


            mInputAllocation.copyFromUnchecked(buf);

            mConvertKernel.forEach_convertFromYUYV(mInputAllocation);

                mOutputAllocation.ioSend();  // Send output frame to surface
        }
    }

    private void initAllocations (UsbTvFrame frame) {
        Element inputElement = Element.U8_4(mRs);
        Type.Builder outputType = new Type.Builder(mRs, Element.RGBA_8888(mRs));
        outputType.setX(frame.getWidth());
        outputType.setY(frame.getHeight());
        int inputSize = currentFrameSize / 4;

        mInputAllocation = Allocation.createSized(mRs, inputElement, inputSize, Allocation.USAGE_SCRIPT);
        mOutputAllocation = Allocation.createTyped(mRs, outputType.create(),
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        if (mRenderSurface != null) {
            mOutputAllocation.setSurface(mRenderSurface);
        }
        mConvertKernel.set_output(mOutputAllocation);
        mConvertKernel.set_width(frame.getWidth());

    }

}
