package com.arksine.libusbtv;


import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;

/**
 * Created by Eric on 10/7/2017.
 */

public class UsbTvRenderer extends Handler {

    UsbTvRenderer() {

    }

    public void processFrame(UsbTvFrame frame) {

    }
/*  TODO: fix and implement
    private void initRenderScript () {
        if (mRsInitialized.compareAndSet(false, true)) {
            // Setup the handler
            HandlerThread renderThread = new HandlerThread("Render Thread",
                    Process.THREAD_PRIORITY_DISPLAY);
            renderThread.start();
            mRenderHandler = new Handler(renderThread.getLooper(), mRenderHandlerCallback);

            mRs = RenderScript.create(mContext);
            Element inputElement = Element.U8_4(mRs);
            Element outputElement = Element.RGBA_8888(mRs);
            int frameSize;
            switch (mFrameInfo.getScanType()) {
                case INTERLEAVED:
                    frameSize = mFrameInfo.getFrameSizeInBytes();
                    break;
                case PROGRESSIVE:
                    frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                    break;
                case DISCARD:
                    frameSize = mFrameInfo.getFrameSizeInBytes() / 2;
                    break;
                default:
                    frameSize = mFrameInfo.getFrameSizeInBytes();
            }
            int inputSize = frameSize / 4;
            int outputSize = inputSize * 2;
            mInputFrameAlloc = Allocation.createSized(mRs, inputElement, inputSize);
            mOutputFrameAlloc = Allocation.createSized(mRs, outputElement, outputSize,
                    Allocation.USAGE_IO_OUTPUT | Allocation.USAGE_SCRIPT);
            mConvertKernel = new ScriptC_ConvertYUYV(mRs);
            mConvertKernel.set_output(mOutputFrameAlloc);
        }
    }
*/

    //  public UsbTvRenderer getRenderer(Context context, FrameInfo info) {}

}
