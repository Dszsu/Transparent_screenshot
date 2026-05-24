package com.dszsu.tss;

import android.app.Application;

import androidx.annotation.NonNull;

import java.util.concurrent.CopyOnWriteArraySet;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final CopyOnWriteArraySet<ServiceListener> listeners = new CopyOnWriteArraySet<>();
    private static volatile XposedService sService;

    public static void addListener(@NonNull ServiceListener listener) {
        listeners.add(listener);
        XposedService service = sService;
        if (service != null) {
            listener.onServiceChanged(service);
        }
    }

    public static void removeListener(@NonNull ServiceListener listener) {
        listeners.remove(listener);
    }

    public static void notifyServiceUpdate() {
        XposedService svc = sService;
        if (svc != null) {
            for (ServiceListener l : listeners) {
                l.onServiceChanged(svc);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(@NonNull XposedService service) {
        sService = service;
        for (ServiceListener l : listeners) l.onServiceChanged(service);
    }

    @Override
    public void onServiceDied(@NonNull XposedService service) {
        sService = null;
        for (ServiceListener l : listeners) l.onServiceChanged(null);
    }

    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }
}