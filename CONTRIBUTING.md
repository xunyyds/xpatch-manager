# 贡献指南

## 提交信息

使用约定式提交，标题用中文简洁描述，**不要使用括号**：

```
feat: 模块加载器支持 APK 内原生库
fix: 修复多进程解包同一文件的竞态
docs: 补充 Android 17 兼容性说明
refactor: 重构加载器链
```

## 分支与流程

1. 从 `main` 切出 `feat/xxx` 或 `fix/xxx`；
2. 修改后本地自测：
   - `./runtime/build-java.ps1` 能产出 `build/classes.dex`；
   - 用 `tools/dex-verify/` 在真机上跑到 `ALL OK`；
3. 提交 PR，描述里写清楚**现象 / 根因 / 方案 / 验证方式**。

## 修改运行时的注意事项

运行时主体是 `runtime/java/` 下的 Java 源码，经 javac + d8 构建；仅依赖 android.jar 隐藏签名的桥接类保留在 `runtime/smali/`。改动产物时几个 dex 级硬约束必须知道（踩过坑）：

| 约束 | 说明 |
| --- | --- |
| `method_ids` 有序 | 按 (class_idx, name_idx, proto_idx) 升序，擅改 class/proto 会触发 `Out-of-order method_ids` |
| `class_data` 连续 | 条目按偏移连续排列，**不能缩短**某个类的条目，否则后续类解析错位 |
| 方法不能删除 | 只能替换实现；不要留半条指令（例如只写 `return-void` 而不 `nop` 掉原指令剩余单元） |
| `outs_size` | 新增调用后必须不小于方法内最大调用参数数，否则 `invalid argument count` |
| 隐藏 API | 不要新增对隐藏 API 的依赖，新平台会直接过滤掉 |

改完务必先在 `tools/dex-verify/` 跑通，再打包装机。

## 代码风格

- 脚本优先 Python / PowerShell，避免依赖额外工具链；
- Python 使用 4 空格缩进，函数级 docstring 说明用途；
- 文档使用中文，术语保留英文原文（dex、verifier、ClassLoader 等）。
