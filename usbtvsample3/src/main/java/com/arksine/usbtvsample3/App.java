package com.arksine.usbtvsample3;

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
