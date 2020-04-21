// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license
package com.arksine.usbtvsample2;

import android.app.Application;

import timber.log.Timber;

/**
 * Created by Eric on 10/5/2017.
 */

public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        Timber.plant(new Timber.DebugTree());
    }
}
