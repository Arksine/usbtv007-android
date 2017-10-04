package com.arksine.usbtv007;

import android.app.Application;

import timber.log.Timber;

/**
 * Created by Eric on 10/2/2017.
 */

public class ExampleApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // TODO: add logger
        Timber.plant(new Timber.DebugTree());
    }
}
