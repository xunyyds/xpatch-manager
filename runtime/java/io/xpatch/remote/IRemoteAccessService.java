package io.xpatch.remote;

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;

public interface IRemoteAccessService extends IInterface {
    public static final String DESCRIPTOR = "io.xpatch.remote.IRemoteAccessService";

    public static class Default implements IRemoteAccessService {
        @Override
        public IBinder asBinder() {
            return null;
        }

        @Override
        public boolean deleteRemoteFile(String str, String str2) throws RemoteException {
            return false;
        }

        @Override
        public void deleteRemotePreferences(String str, String str2) throws RemoteException {
        }

        @Override
        public String[] listRemoteFiles(String str) throws RemoteException {
            return null;
        }

        @Override
        public ParcelFileDescriptor openRemoteFile(String str, String str2) throws RemoteException {
            return null;
        }

        @Override
        public Bundle requestRemotePreferences(String str, String str2) throws RemoteException {
            return null;
        }

        @Override
        public void updateRemotePreferences(String str, String str2, Bundle bundle) throws RemoteException {
        }
    }

    boolean deleteRemoteFile(String str, String str2) throws RemoteException;

    void deleteRemotePreferences(String str, String str2) throws RemoteException;

    String[] listRemoteFiles(String str) throws RemoteException;

    ParcelFileDescriptor openRemoteFile(String str, String str2) throws RemoteException;

    Bundle requestRemotePreferences(String str, String str2) throws RemoteException;

    void updateRemotePreferences(String str, String str2, Bundle bundle) throws RemoteException;

    public static abstract class Stub extends Binder implements IRemoteAccessService {
        static final int TRANSACTION_deleteRemoteFile = 13;
        static final int TRANSACTION_deleteRemotePreferences = 4;
        static final int TRANSACTION_listRemoteFiles = 11;
        static final int TRANSACTION_openRemoteFile = 12;
        static final int TRANSACTION_requestRemotePreferences = 2;
        static final int TRANSACTION_updateRemotePreferences = 3;

        @Override
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            attachInterface(this, IRemoteAccessService.DESCRIPTOR);
        }

        public static IRemoteAccessService asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(IRemoteAccessService.DESCRIPTOR);
            if (iInterfaceQueryLocalInterface != null && (iInterfaceQueryLocalInterface instanceof IRemoteAccessService)) {
                return (IRemoteAccessService) iInterfaceQueryLocalInterface;
            }
            return new Proxy(iBinder);
        }

        @Override
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i >= 1 && i <= 16777215) {
                parcel.enforceInterface(IRemoteAccessService.DESCRIPTOR);
            }
            if (i == 1598968902) {
                parcel2.writeString(IRemoteAccessService.DESCRIPTOR);
                return true;
            }
            if (i == TRANSACTION_requestRemotePreferences) {
                Bundle bundleRequestRemotePreferences = requestRemotePreferences(parcel.readString(), parcel.readString());
                parcel2.writeNoException();
                _Parcel.writeTypedObject(parcel2, bundleRequestRemotePreferences, 1);
            } else if (i == TRANSACTION_updateRemotePreferences) {
                updateRemotePreferences(parcel.readString(), parcel.readString(), (Bundle) _Parcel.readTypedObject(parcel, Bundle.CREATOR));
                parcel2.writeNoException();
            } else if (i == TRANSACTION_deleteRemotePreferences) {
                deleteRemotePreferences(parcel.readString(), parcel.readString());
                parcel2.writeNoException();
            } else {
                switch (i) {
                    case TRANSACTION_listRemoteFiles :
                        String[] strArrListRemoteFiles = listRemoteFiles(parcel.readString());
                        parcel2.writeNoException();
                        parcel2.writeStringArray(strArrListRemoteFiles);
                        break;
                    case TRANSACTION_openRemoteFile :
                        ParcelFileDescriptor parcelFileDescriptorOpenRemoteFile = openRemoteFile(parcel.readString(), parcel.readString());
                        parcel2.writeNoException();
                        _Parcel.writeTypedObject(parcel2, parcelFileDescriptorOpenRemoteFile, 1);
                        break;
                    case TRANSACTION_deleteRemoteFile :
                        boolean zDeleteRemoteFile = deleteRemoteFile(parcel.readString(), parcel.readString());
                        parcel2.writeNoException();
                        parcel2.writeInt(zDeleteRemoteFile ? 1 : 0);
                        break;
                    default:
                        return super.onTransact(i, parcel, parcel2, i2);
                }
            }
            return true;
        }

        private static class Proxy implements IRemoteAccessService {
            private IBinder mRemote;

            public String getInterfaceDescriptor() {
                return IRemoteAccessService.DESCRIPTOR;
            }

            Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override
            public Bundle requestRemotePreferences(String str, String str2) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    this.mRemote.transact(Stub.TRANSACTION_requestRemotePreferences, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return (Bundle) _Parcel.readTypedObject(parcelObtain2, Bundle.CREATOR);
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override
            public void updateRemotePreferences(String str, String str2, Bundle bundle) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    _Parcel.writeTypedObject(parcelObtain, bundle, 0);
                    this.mRemote.transact(Stub.TRANSACTION_updateRemotePreferences, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override
            public void deleteRemotePreferences(String str, String str2) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    this.mRemote.transact(Stub.TRANSACTION_deleteRemotePreferences, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override
            public String[] listRemoteFiles(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_listRemoteFiles, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.createStringArray();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override
            public ParcelFileDescriptor openRemoteFile(String str, String str2) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    this.mRemote.transact(Stub.TRANSACTION_openRemoteFile, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return (ParcelFileDescriptor) _Parcel.readTypedObject(parcelObtain2, ParcelFileDescriptor.CREATOR);
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override
            public boolean deleteRemoteFile(String str, String str2) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(IRemoteAccessService.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    parcelObtain.writeString(str2);
                    this.mRemote.transact(Stub.TRANSACTION_deleteRemoteFile, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }
    }

    public static class _Parcel {

        public static <T> T readTypedObject(Parcel parcel, Parcelable.Creator<T> creator) {
            if (parcel.readInt() != 0) {
                return creator.createFromParcel(parcel);
            }
            return null;
        }

        public static <T extends Parcelable> void writeTypedObject(Parcel parcel, T t, int i) {
            if (t != null) {
                parcel.writeInt(1);
                t.writeToParcel(parcel, i);
            } else {
                parcel.writeInt(0);
            }
        }
    }
}
