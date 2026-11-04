package io.xpatch.core.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

final class RemotePreferences implements SharedPreferences {
    private final Context context;
    private final String group;
    private final CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new CopyOnWriteArraySet<>();
    private final String modulePackageName;

    RemotePreferences(Context context, String str, String str2) {
        this.context = context;
        this.modulePackageName = str;
        this.group = str2;
    }

    private Map<String, Object> getSnapshot() {
        Bundle bundleRequestRemotePreferences = RemoteAccessBridge.requestRemotePreferences(this.context, this.modulePackageName, this.group);
        if (bundleRequestRemotePreferences == null) {
            return new LinkedHashMap();
        }
        Serializable serializable = bundleRequestRemotePreferences.getSerializable("map");
        if (serializable instanceof Map) {
            LinkedHashMap linkedHashMap = new LinkedHashMap();
            for (Object entryObj : ((Map) serializable).entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj;
                Object key = entry.getKey();
                if (key instanceof String) {
                    linkedHashMap.put((String) key, entry.getValue());
                }
            }
            return linkedHashMap;
        }
        Bundle bundle = bundleRequestRemotePreferences.getBundle("bundle");
        if (bundle == null) {
            return new LinkedHashMap();
        }
        LinkedHashMap linkedHashMap2 = new LinkedHashMap();
        for (String str : bundle.keySet()) {
            linkedHashMap2.put(str, bundle.get(str));
        }
        return linkedHashMap2;
    }

    @Override
    public Map<String, ?> getAll() {
        return new HashMap(getSnapshot());
    }

    @Override
    public String getString(String str, String str2) {
        Object obj = getSnapshot().get(str);
        return obj instanceof String ? (String) obj : str2;
    }

    @Override
    public Set<String> getStringSet(String str, Set<String> set) {
        Object obj = getSnapshot().get(str);
        if (obj instanceof Set) {
            HashSet hashSet = new HashSet();
            Iterator it = ((Set) obj).iterator();
            while (it.hasNext()) {
                hashSet.add(String.valueOf(it.next()));
            }
            return hashSet;
        }
        if (obj instanceof Iterable) {
            set = new HashSet<>();
            Iterator it2 = ((Iterable) obj).iterator();
            while (it2.hasNext()) {
                set.add(String.valueOf(it2.next()));
            }
        }
        return set;
    }

    @Override
    public int getInt(String str, int i) {
        Object obj = getSnapshot().get(str);
        return obj instanceof Number ? ((Number) obj).intValue() : i;
    }

    @Override
    public long getLong(String str, long j) {
        Object obj = getSnapshot().get(str);
        return obj instanceof Number ? ((Number) obj).longValue() : j;
    }

    @Override
    public float getFloat(String str, float f) {
        Object obj = getSnapshot().get(str);
        return obj instanceof Number ? ((Number) obj).floatValue() : f;
    }

    @Override
    public boolean getBoolean(String str, boolean z) {
        Object obj = getSnapshot().get(str);
        return obj instanceof Boolean ? ((Boolean) obj).booleanValue() : z;
    }

    @Override
    public boolean contains(String str) {
        return getSnapshot().containsKey(str);
    }

    @Override
    public SharedPreferences.Editor edit() {
        return new EditorImpl();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        if (onSharedPreferenceChangeListener != null) {
            this.listeners.add(onSharedPreferenceChangeListener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener) {
        if (onSharedPreferenceChangeListener != null) {
            this.listeners.remove(onSharedPreferenceChangeListener);
        }
    }

    private final class EditorImpl implements SharedPreferences.Editor {
        private boolean clear;
        private final ArrayList<String> deleteKeys;
        private final Bundle putBundle;

        private EditorImpl() {
            this.putBundle = new Bundle();
            this.deleteKeys = new ArrayList<>();
        }

        @Override
        public SharedPreferences.Editor putString(String str, String str2) {
            this.putBundle.putString(str, str2);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String str, Set<String> set) {
            this.putBundle.putStringArrayList(str, set == null ? null : new ArrayList<>(set));
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String str, int i) {
            this.putBundle.putInt(str, i);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String str, long j) {
            this.putBundle.putLong(str, j);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String str, float f) {
            this.putBundle.putFloat(str, f);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String str, boolean z) {
            this.putBundle.putBoolean(str, z);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String str) {
            this.deleteKeys.add(str);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            this.clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            Bundle bundle = new Bundle();
            bundle.putBoolean("clear", this.clear);
            if (!this.deleteKeys.isEmpty()) {
                bundle.putStringArrayList("delete", new ArrayList<>(this.deleteKeys));
            }
            bundle.putBundle("put", new Bundle(this.putBundle));
            boolean zUpdateRemotePreferences = RemoteAccessBridge.updateRemotePreferences(RemotePreferences.this.context, RemotePreferences.this.modulePackageName, RemotePreferences.this.group, bundle);
            if (zUpdateRemotePreferences) {
                HashSet hashSet = new HashSet(this.putBundle.keySet());
                hashSet.addAll(this.deleteKeys);
                notifyListeners(hashSet);
            }
            return zUpdateRemotePreferences;
        }

        @Override
        public void apply() {
            commit();
        }

        private void notifyListeners(Set<String> set) {
            if (RemotePreferences.this.listeners.isEmpty()) {
                return;
            }
            for (String str : set) {
                Iterator it = RemotePreferences.this.listeners.iterator();
                while (it.hasNext()) {
                    ((SharedPreferences.OnSharedPreferenceChangeListener) it.next()).onSharedPreferenceChanged(RemotePreferences.this, str);
                }
            }
            if (this.clear && set.isEmpty()) {
                Iterator it2 = RemotePreferences.this.listeners.iterator();
                while (it2.hasNext()) {
                    ((SharedPreferences.OnSharedPreferenceChangeListener) it2.next()).onSharedPreferenceChanged(RemotePreferences.this, null);
                }
            }
        }
    }
}
