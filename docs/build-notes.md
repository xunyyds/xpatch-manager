# 构建备注（沙箱 / CI）

面向「没有图形界面、没有网络、只有部分目录可写」的沙箱环境跑 `assembleRelease` 的场景。
常规构建流程见 [`build.md`](build.md)，这里只记沙箱里额外要处理的事。

## 环境变量

| 变量 | 作用 |
| --- | --- |
| `GRADLE_USER_HOME` | Gradle 的依赖缓存、wrapper 发行版、守护进程日志都放这里。沙箱里默认的 `%USERPROFILE%\.gradle` 往往不可写，指到一个可写目录即可 |
| `GRADLE_RO_DEP_CACHE` | 只读依赖缓存。多个沙箱共享同一份已经下载好的依赖，避免每次构建都重新联网拉取 |
| `JAVA_HOME` | Gradle 9 需要 JDK 17+；`d8.bat` 也靠它找 `java`（PATH 里没有 java 时尤其要设） |

```powershell
$env:GRADLE_USER_HOME     = 'D:\dev\gradle-home'
$env:GRADLE_RO_DEP_CACHE  = 'D:\gradle-ro-cache'
$env:JAVA_HOME            = 'D:\dev\jdk-21\jdk-21.0.12.1+1'
```

`GRADLE_RO_DEP_CACHE` 指向的目录只需要一个 `modules-2/` 结构（即另一个 `GRADLE_USER_HOME\caches`
里同名目录的副本），Gradle 会把它当只读的二级缓存使用，写入仍然发生在 `GRADLE_USER_HOME`。

## Kotlin 编译进程

沙箱里 Kotlin 守护进程经常起不来（端口被拦、子进程被限制），表现为构建卡在
`Waiting for the Kotlin daemon` 或直接超时。改成在 Gradle 守护进程内编译：

```powershell
gradle :app:assembleRelease -Dkotlin.compiler.execution.strategy=in-process
```

代价是内存占用上升（配合 `org.gradle.jvmargs=-Xmx3072m`），换来的是不会因为守护进程
握手失败而中断。该参数由 Kotlin Gradle 插件读取；AGP 9 的内置 Kotlin 编译（产物在
`intermediates/built_in_kotlinc/` 下）即使不识别它也不会报错，最坏情况是没有效果而不是构建失败。
仓库没有自带 `gradlew`，用环境里的 Gradle 9.7.1 调用即可；
有 wrapper 时把 `gradle` 换成 `./gradlew`。

## `problems-report.html` 的 AccessDenied：BUILD FAILED 但编译成功

**现象**

```
FAILURE: Build failed with an exception.

* What went wrong:
java.nio.file.AccessDeniedException: ...\build\reports\problems\problems-report.html
```

**判读**

Gradle 在构建结束时会写 HTML 问题报告。报告写不进去（目录只读、文件被杀毒软件或编辑器占用、
上一轮构建留下的只读属性）会让整次构建以 `BUILD FAILED` 收尾，但**编译和打包通常已经完成**。
这时不要回滚、也不要重跑，先确认产物是不是新的：

```powershell
# 1) Kotlin 编译产物（AGP 9 的内置 Kotlin 编译落在 built_in_kotlinc 下）
Get-ChildItem manager\app\build\intermediates\built_in_kotlinc\release\compileReleaseKotlin\classes -Recurse |
    Sort-Object LastWriteTime -Descending | Select-Object -First 5 LastWriteTime, FullName

# 2) 最终 APK
Get-ChildItem manager\app\build\outputs\apk\release\*.apk |
    Select-Object LastWriteTime, Length, Name

# 3) 报告文件本身（若存在且时间戳是旧的，说明本次根本没写成功）
Get-Item manager\build\reports\problems\problems-report.html -ErrorAction SilentlyContinue
```

判定规则：

- `built_in_kotlinc\release\` 下的 `.class` 时间戳是本次构建时间 → 编译确实成功，
  `BUILD FAILED` 只来自报告写盘；
- APK 的时间戳与体积都更新了 → 打包也成功，可以直接装机验证；
- 两者都没更新 → 才是真的编译失败，回到完整输出里找第一条 `e: ` / `error:`。

彻底绕开报告：加 `--no-problems-report`（等价于 `-Dorg.gradle.problems.report=false`），
或者构建前删掉 `manager/build/reports/problems` 并确认该目录可写。

## 与引导桩构建链相关的两个行为

- `:app:buildStubDex` 会重写 `manager/app/src/main/assets/xp/stub.dex`。构建机没有 SDK `d8`、
  没有 `javac` 或找不到 `android.jar` 时，它只打印警告并沿用已入库的 dex，**不会**让
  `assembleRelease` 失败；确认产物稳定后可以用 `-Pxp.stub.autoBuild=false` 关掉，回到手工维护。
- `:app:verifyStubDex` 只挂在 `check` 上，`assembleRelease` 不会触发。它比对
  `manager/app/build/stub-dex.sha256`，不一致时提示「stub 源码已变更，请重新生成 dex」，
  默认只警告不失败；需要硬门禁时加 `-Pxp.stub.verifyStrict=true`。
