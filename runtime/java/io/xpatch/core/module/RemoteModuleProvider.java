package io.xpatch.core.module;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import io.xpatch.hook.XpHook;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class RemoteModuleProvider implements IModuleProvider {
    private final LocalStoreModuleProvider localProvider;

    public RemoteModuleProvider(Context context) {
        this.localProvider = new LocalStoreModuleProvider(context);
    }

    @Override
    public List<RemoteModule> getModules() {
        try {
            return this.localProvider.getModules();
        } catch (Exception e) {
            XpHook.log0("RemoteService", Log.getStackTraceString(e));
            return this.localProvider.getModules();
        }
    }

    public void refreshScopeModulePaths() {
        this.localProvider.refreshScopeModulePaths();
    }

    public static List<RemoteModule> parseModules(String str) throws JSONException {
        ArrayList arrayList = new ArrayList();
        if (TextUtils.isEmpty(str)) {
            return arrayList;
        }
        String strTrim = str.trim();
        if (strTrim.startsWith("[")) {
            appendSharedScopeModules(arrayList, new JSONArray(strTrim));
            return arrayList;
        }
        JSONArray jSONArrayOptJSONArray = new JSONObject(strTrim).optJSONArray("scopes");
        if (jSONArrayOptJSONArray == null) {
            return arrayList;
        }
        for (int i = 0; i < jSONArrayOptJSONArray.length(); i++) {
            JSONObject jSONObjectOptJSONObject = jSONArrayOptJSONArray.optJSONObject(i);
            if (jSONObjectOptJSONObject != null && jSONObjectOptJSONObject.optBoolean("globalEnable", true)) {
                RemoteModule remoteModule = new RemoteModule();
                remoteModule.apkPath = jSONObjectOptJSONObject.optString("apkPath", jSONObjectOptJSONObject.optString("path"));
                remoteModule.packageName = jSONObjectOptJSONObject.optString("packageName", jSONObjectOptJSONObject.optString("pkg"));
                remoteModule.useApiLevel = jSONObjectOptJSONObject.optInt("useApiLevel", jSONObjectOptJSONObject.optInt("apiLevel"));
                if (!TextUtils.isEmpty(remoteModule.apkPath) && !TextUtils.isEmpty(remoteModule.packageName)) {
                    arrayList.add(remoteModule);
                }
            }
        }
        return arrayList;
    }

    private static void appendSharedScopeModules(List<RemoteModule> list, JSONArray jSONArray) throws JSONException {
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject jSONObject = jSONArray.getJSONObject(i);
            RemoteModule remoteModule = new RemoteModule();
            remoteModule.apkPath = jSONObject.getString("path");
            remoteModule.packageName = jSONObject.getString("pkg");
            remoteModule.useApiLevel = jSONObject.optInt("useApiLevel", jSONObject.optInt("apiLevel"));
            list.add(remoteModule);
        }
    }
}
