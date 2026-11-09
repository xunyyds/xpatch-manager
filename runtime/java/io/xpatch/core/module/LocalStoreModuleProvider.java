package io.xpatch.core.module;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Environment;
import android.text.TextUtils;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import io.xpatch.utils.FileUtils;
import java.io.File;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

class LocalStoreModuleProvider implements IModuleProvider {
    private static final String TAG = "LocalStoreModuleProvider";
    private static final String CONFIG_DIR = "XPatch";
    private final Context context;

    public LocalStoreModuleProvider(Context context) {
        this.context = context;
    }

    @Override
    public List<RemoteModule> getModules() {
        for (String str : getScopeFilePaths()) {
            if (!new File(str).isFile()) {
                continue;
            }
            try {
                return RemoteModuleProvider.parseModules(FileUtils.readString(str));
            } catch (Exception e) {
                XpHook.log0(TAG, "读取作用域文件失败：" + str + " " + e);
            }
        }
        return Collections.emptyList();
    }

    public void refreshScopeModulePaths() {
        if (this.context == null) {
            return;
        }
        String scopeFile = findScopeFile();
        try {
            refreshScopeFile(scopeFile);
        } catch (Exception e) {
            XpHook.log0(TAG, "刷新模块路径失败：" + scopeFile + " " + e);
        }
    }

    private String findScopeFile() {
        String[] scopeFilePaths = getScopeFilePaths();
        for (String str : scopeFilePaths) {
            if (new File(str).isFile()) {
                return str;
            }
        }
        return scopeFilePaths[0];
    }

    private void refreshScopeFile(String str) throws Exception {
        boolean zRefreshScopeArray;
        String string;
        String string2 = FileUtils.readString(str);
        if (TextUtils.isEmpty(string2)) {
            return;
        }
        String strTrim = string2.trim();
        if (TextUtils.isEmpty(strTrim)) {
            return;
        }
        if (strTrim.startsWith("[")) {
            JSONArray jSONArray = new JSONArray(strTrim);
            zRefreshScopeArray = refreshScopeArray(jSONArray, true);
            string = jSONArray.toString();
        } else {
            JSONObject jSONObject = new JSONObject(strTrim);
            JSONArray jSONArrayOptJSONArray = jSONObject.optJSONArray("scopes");
            if (jSONArrayOptJSONArray == null) {
                return;
            }
            zRefreshScopeArray = refreshScopeArray(jSONArrayOptJSONArray, false);
            string = jSONObject.toString();
        }
        if (zRefreshScopeArray) {
            FileUtils.write(str, string);
        }
    }

    private boolean refreshScopeArray(JSONArray jSONArray, boolean z) {
        boolean z2 = false;
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject jSONObjectOptJSONObject = jSONArray.optJSONObject(i);
            if (jSONObjectOptJSONObject != null) {
                String strOptString = jSONObjectOptJSONObject.optString("packageName", jSONObjectOptJSONObject.optString("pkg"));
                if (!TextUtils.isEmpty(strOptString)) {
                    String strOptString2 = jSONObjectOptJSONObject.optString("apkPath", jSONObjectOptJSONObject.optString("path"));
                    if (TextUtils.isEmpty(strOptString2) || !new File(strOptString2).exists()) {
                        String strResolveLatestApkPath = resolveLatestApkPath(strOptString);
                        if (!TextUtils.isEmpty(strResolveLatestApkPath) && !TextUtils.equals(strOptString2, strResolveLatestApkPath)) {
                            if (z) {
                                try {
                                    jSONObjectOptJSONObject.put("path", strResolveLatestApkPath);
                                } catch (Exception unused) {
                                }
                            } else {
                                try {
                                    jSONObjectOptJSONObject.put("apkPath", strResolveLatestApkPath);
                                    if (jSONObjectOptJSONObject.has("path")) {
                                        jSONObjectOptJSONObject.put("path", strResolveLatestApkPath);
                                    }
                                } catch (Exception unused) {
                                }
                            }
                            z2 = true;
                            XpHook.log0(TAG, "更新模块 apk 路径： " + strOptString + " -> " + strResolveLatestApkPath);
                        }
                    }
                }
            }
        }
        return z2;
    }

    private String resolveLatestApkPath(String str) {
        try {
            PackageInfo packageInfo = this.context.getPackageManager().getPackageInfo(str, 0);
            if (packageInfo != null && packageInfo.applicationInfo != null) {
                String str2 = packageInfo.applicationInfo.sourceDir;
                if (!TextUtils.isEmpty(str2) && new File(str2).exists()) {
                    return str2;
                }
            }
        } catch (Exception unused) {
        }
        return null;
    }

    private String[] getScopeFilePaths() {
        String strSha1 = DataUtils.getStrSha1(AppEnv.packageName + ".scope");
        if (strSha1 == null || strSha1.isEmpty()) {
            strSha1 = String.valueOf((AppEnv.packageName + ".scope").hashCode());
        }
        File externalStorageDirectory = Environment.getExternalStorageDirectory();
        return new String[]{
            new File(new File(new File(externalStorageDirectory, "Android/media/" + AppEnv.packageName), CONFIG_DIR), strSha1).getAbsolutePath(),
            new File(new File(new File(externalStorageDirectory, "Android/data/" + AppEnv.packageName), CONFIG_DIR), strSha1).getAbsolutePath(),
            new File(new File(externalStorageDirectory, "Android/media/" + AppEnv.packageName), strSha1).getAbsolutePath(),
            new File(new File(externalStorageDirectory, "Android/data/" + AppEnv.packageName), strSha1).getAbsolutePath()
        };
    }
}
