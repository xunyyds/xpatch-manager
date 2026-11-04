package io.xpatch.stub;

import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AppEntry extends AppComponentFactory {

    private static final String TAG = "XPatch";
    private static final String RUNTIME_ENTRY = "xp/core.dex";
    private static final String CONFIG_ENTRY = "xp/config.json";
    private static final String RUNTIME_CLASS = "io.xpatch.core.CoreEntry";

    private static volatile boolean injected;
    private static AppComponentFactory delegate;
    private static ApplicationInfo selfInfo;

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        inject(cl, info);
        return cl;
    }

    private static synchronized void inject(ClassLoader cl, ApplicationInfo info) {
        if (injected) {
            return;
        }
        injected = true;
        selfInfo = info;
        try {
            byte[] dex = readEntry(info.sourceDir, RUNTIME_ENTRY);
            if (dex == null || dex.length == 0) {
                Log.e(TAG, "runtime dex missing");
                return;
            }
            ClassLoader runtime = new dalvik.system.InMemoryDexClassLoader(
                    new ByteBuffer[]{ByteBuffer.wrap(dex)}, null, ClassLoader.getSystemClassLoader());
            Class<?> entry = Class.forName(RUNTIME_CLASS, true, runtime);
            Method method = entry.getMethod("loadAppInPack", ClassLoader.class, ApplicationInfo.class);
            method.invoke(null, cl, info);
            Log.i(TAG, "runtime injected into " + info.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "runtime injection failed", t);
        }
    }

    private static AppComponentFactory delegate(ClassLoader cl, ApplicationInfo info) {
        AppComponentFactory factory = delegate;
        if (factory != null) {
            return factory;
        }
        ApplicationInfo appInfo = info != null ? info : selfInfo;
        if (appInfo == null) {
            return null;
        }
        try {
            String name = readFactoryName(appInfo.sourceDir, CONFIG_ENTRY);
            if (name != null && name.length() > 0) {
                Class<?> cls = Class.forName(name, true, cl);
                Object instance = cls.newInstance();
                if (instance instanceof AppComponentFactory) {
                    factory = (AppComponentFactory) instance;
                    delegate = factory;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "original factory unavailable: " + t);
        }
        return factory;
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateApplication(cl, className);
        }
        return super.instantiateApplication(cl, className);
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }

    private static String readFactoryName(String apkPath, String entryName) {
        byte[] raw = readEntry(apkPath, entryName);
        if (raw == null) {
            return null;
        }
        String text = new String(raw);
        int key = text.indexOf("\"factory\"");
        if (key < 0) {
            return null;
        }
        int start = text.indexOf('"', text.indexOf(':', key) + 1);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return text.substring(start + 1, end);
    }

    private static byte[] readEntry(String apkPath, String entryName) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, (int) entry.getSize()));
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "read " + entryName + " failed", t);
            return null;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}