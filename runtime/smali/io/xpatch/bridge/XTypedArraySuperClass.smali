.class public Lio/xpatch/bridge/XTypedArraySuperClass;
.super Landroid/content/res/TypedArray;
.source "XTypedArraySuperClass.java"


# direct methods
.method protected constructor <init>(Landroid/content/res/Resources;)V
    .locals 0

    .line 17
    invoke-direct {p0, p1}, Landroid/content/res/TypedArray;-><init>(Landroid/content/res/Resources;)V

    .line 18
    new-instance p1, Ljava/lang/UnsupportedOperationException;

    invoke-direct {p1}, Ljava/lang/UnsupportedOperationException;-><init>()V

    throw p1
.end method
