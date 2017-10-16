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
import android.view.Surface;

import timber.log.Timber;

/**
 * Created by Eric on 10/7/2017.
 */

public class UsbTvRenderer {

    private Handler mRenderHandler;
    private Surface mRenderSurface;
    private RenderScript mRs;
    private Allocation mInputAllocation = null;
    private Allocation mOutputAllocation = null;
    private ScriptC_ConvertYUYV mConvertKernel;
    private int currentFrameSize = 0;

    UsbTvRenderer(Context context, Surface surface) {
        HandlerThread renderThread = new HandlerThread("Render Thread");
        renderThread.start();
        mRenderHandler = new Handler(renderThread.getLooper(), mRenderHandlerCallback);
        mRs = RenderScript.create(context);
        mConvertKernel = new ScriptC_ConvertYUYV(mRs);
    }

    public void processFrame(UsbTvFrame frame) {
        Message msg = mRenderHandler.obtainMessage();
        msg.obj = frame;
        mRenderHandler.sendMessage(msg);
    }

    public void setSurface(Surface surface) {
        mRenderSurface = surface;
        if (mOutputAllocation != null && mRenderSurface != null) {
            mOutputAllocation.setSurface(mRenderSurface);
        }
    }

    private final Handler.Callback mRenderHandlerCallback = new Handler.Callback() {
        @Override
        public boolean handleMessage(Message message) {
            UsbTvFrame frame = (UsbTvFrame) message.obj;

            // Copy frame to input allocaton
            byte[] buf = frame.getFrameBuf();

            if (currentFrameSize != buf.length) {
                currentFrameSize = buf.length;
                initAllocations();
                // Drop this frame
                return true;
            }

            mInputAllocation.copyFromUnchecked(buf);

            mConvertKernel.forEach_convertFromYUYV(mInputAllocation);
            mOutputAllocation.ioSend();  // Send output frame to surface

            return true;
        }
    };

    private void initAllocations () {
        Element inputElement = Element.U8_4(mRs);
        Element outputElement = Element.RGBA_8888(mRs);
        int inputSize = currentFrameSize / 4;
        int outputSize = inputSize * 2;
        mInputAllocation = Allocation.createSized(mRs, inputElement, inputSize);
        mOutputAllocation = Allocation.createSized(mRs, outputElement, outputSize,
                Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);

        if (mRenderSurface != null) {
            mOutputAllocation.setSurface(mRenderSurface);
        }
        mConvertKernel.set_output(mOutputAllocation);

    }

}
