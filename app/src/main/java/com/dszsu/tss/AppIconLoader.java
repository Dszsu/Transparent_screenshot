package com.dszsu.tss;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppIconLoader {

    private static final int CACHE_SIZE = 256;

    private static volatile AppIconLoader instance;

    private final LruCache<String, Drawable.ConstantState> cache = new LruCache<>(CACHE_SIZE);

    private final Map<String, List<Callback>> pending = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppIconLoader() {
    }

    public static AppIconLoader get() {
        if (instance == null) {
            synchronized (AppIconLoader.class) {
                if (instance == null) instance = new AppIconLoader();
            }
        }
        return instance;
    }

    public void load(@NonNull final PackageManager pm, @Nullable final String pkg,
                     @NonNull final Callback callback) {
        if (pkg == null || pkg.isEmpty()) {
            callback.onIcon(null);
            return;
        }
        Drawable.ConstantState state = cache.get(pkg);
        if (state != null) {
            callback.onIcon(state.newDrawable());
            return;
        }
        List<Callback> waiters = new CopyOnWriteArrayList<>();
        List<Callback> existing = pending.putIfAbsent(pkg, waiters);
        if (existing != null) {

            existing.add(callback);
            return;
        }

        waiters.add(callback);
        executor.execute(() -> {
            Drawable icon = null;
            try {
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            } catch (Throwable ignored) {
            }
            final Drawable result = icon;
            mainHandler.post(() -> {
                if (result != null) {
                    Drawable.ConstantState cs = result.getConstantState();
                    if (cs != null) cache.put(pkg, cs);
                }
                List<Callback> all = pending.remove(pkg);
                if (all != null) {
                    for (Callback cb : all) cb.onIcon(result);
                }
            });
        });
    }

    public interface Callback {
        void onIcon(@Nullable Drawable icon);
    }
}
