.class public Lio/xpatch/bridge/FUtils;
.super Ljava/lang/Object;
.source "FUtils.java"


.method public constructor <init>()V
    .locals 0

    .line 5
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method public static AssetManager_addAssetPath(Landroid/content/res/AssetManager;Ljava/lang/String;)I
    .locals 0

    .line 7
    invoke-virtual {p0, p1}, Landroid/content/res/AssetManager;->addAssetPath(Ljava/lang/String;)I

    move-result p0

    return p0
.end method
