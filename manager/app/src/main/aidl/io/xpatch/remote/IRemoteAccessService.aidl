package io.xpatch.remote;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IRemoteAccessService {
    Bundle requestRemotePreferences(String modulePackageName, String group) = 2;
    void updateRemotePreferences(String modulePackageName, String group, in Bundle diff) = 3;
    void deleteRemotePreferences(String modulePackageName, String group) = 4;
    String[] listRemoteFiles(String modulePackageName) = 11;
    ParcelFileDescriptor openRemoteFile(String modulePackageName, String path) = 12;
    boolean deleteRemoteFile(String modulePackageName, String path) = 13;
}
