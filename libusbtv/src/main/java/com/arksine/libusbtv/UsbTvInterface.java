package com.arksine.libusbtv;

import android.view.Surface;

/**
 * Interface returned to the user for controlling the capture device
 */
public interface UsbTvInterface {
    boolean isOpen();
    boolean isStreaming();

    void close();
    void startStreaming();
    void stopStreaming();

    FrameInfo getFrameInfo();
    UsbTv.InputSelection getInput();

    void setDrawingSurface(Surface drawingSurface);
    void setRawCallback(UsbTv.RawFrameCallback cb);

    void setInput(UsbTv.InputSelection input);
    void setNorm(UsbTv.TvNorm norm);

    void setBrightness(int value);
    void setContrast(int value);
    void setSaturation(int value);
    void setHue(int value);
    void setSharpness(int value);

    int getBrightness();
    int getContrast();
    int getSaturation();
    int getHue();
    int getSharpness();
}
