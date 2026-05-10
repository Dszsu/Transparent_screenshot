package com.dszsu.tss;

import android.app.Application;
import androidx.annotation.NonNull;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import java.util.concurrent.CopyOnWriteArraySet;

public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService sService;
    private static final CopyOnWriteArraySet<ServiceListener> listeners = new CopyOnWriteArraySet<>();

    public interface ServiceListener {
        void onServiceChanged(XposedService service);
    }

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
}