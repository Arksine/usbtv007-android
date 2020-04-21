// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license
package com.arksine.libusbtv;

import android.view.Surface;

/**
 * Interface returned to the user for controlling the capture device
 */
public abstract class IUsbTvDriver {
    private final UsbTv mParentInstance;

    IUsbTvDriver(UsbTv parent) {
        mParentInstance = parent;
    }

    UsbTv getParentInstance() {
        return mParentInstance;
    }

    public abstract void close();
    public abstract boolean isOpen();
    public abstract boolean isStreaming();

    public abstract void startStreaming();
    public abstract void stopStreaming();

    public abstract DeviceParams getDeviceParams();

    public abstract void setOnFrameReceivedListener(UsbTv.onFrameReceivedListener cb);

    public abstract void setInput(UsbTv.InputSelection input);
    public abstract void setNorm(UsbTv.TvNorm norm);
    public abstract void setScanType(UsbTv.ScanType scanType);
    public abstract void setControl(UsbTv.ColorControl control, int value);
    public abstract int getColorControl(UsbTv.ColorControl control);

}
