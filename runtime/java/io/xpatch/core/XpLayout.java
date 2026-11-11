package io.xpatch.core;

import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;

/**
 * 注入路径混淆（{@code docs/inject-path-obfuscation.md}）的「逻辑条目名 → APK 内真实条目名」解析。
 *
 * <p>为什么集中在一个类里：同一个逻辑条目（内核 dex、hook 引擎 dex/so、wrapper dex…）在内核里被
 * 五处按路径直接 {@code zipFile.getEntry("xp/...")} 读取；开了混淆的产物把这些名字换成随机名，
 * 而名字的来源只有一个 —— config 里的 {@code res_*} 键。散在五个调用点各写一遍等于把同一份契约
 * 实现五遍，所以统一走本类：改布局只改这里的名字表。
 *
 * <p>不变量：
 * <ol>
 *   <li><b>绝不向调用方抛异常</b>：本类跑在应用引导期，抛出去就是"应用打不开"。所有 IO / 解析
 *       失败一律吞掉并回落旧名字（这是老产物行为不变的保证，契约 §4.6）。</li>
 *   <li><b>读侧容忍缺失</b>：config 里没有 {@code res_*} 就是老产物，等价于"全部用旧名字"
 *       （契约 §4.2 读取约定）。</li>
 *   <li><b>发现机制不引入新痕迹</b>（契约 §3）：不写固定文件名、不用 extra field、不写 meta-data，
 *       只靠"条目名形状 + 内容里含 res_cfg"。这条规则与 manager 的 {@code InjectLayout.kt}、引导桩
 *       的 {@code AppEntry.java} 必须逐字一致，否则三份实现对同一份产物会得出不同结论。</li>
 *   <li><b>多进程 / 多线程安全</b>：每个进程各解析一次（{@link #ensureInstalled()}），结果以
 *       {@code volatile} 快照整体发布；失败不永久缓存，允许重试一次 —— 最早的调用可能早于
 *       {@code AppEnv.selfApk} 就绪。</li>
 * </ol>
 */
public final class XpLayout {

    private static final String TAG = "XpLayout";

    /** {@link #TABLE} 的列下标。 */
    private static final int COL_LOGICAL = 0;
    private static final int COL_LEGACY = 1;
    private static final int COL_KEY = 2;

    /** 候选 config 的体积上限：超过它的一律不可能是我们的 JSON，绝不为"形状像"去解压大文件。 */
    private static final int MAX_CONFIG_BYTES = 1024 * 1024;

    /** 老产物的 config 条目名；也是"一个布局候选都没发现"时的回落值（契约 §3 兜底）。 */
    private static final String LEGACY_CONFIG = "xp/config.json";

    /** ABI 专属资产的逻辑名前缀：{@code hook_so_<abi>}（模板行，不在 {@link #TABLE} 里）。 */
    private static final String HOOK_SO_ABI_PREFIX = "hook_so_";

    /** ABI 专属资产的 config 键前缀：{@code res_hook_so_<abi>}，如 {@code res_hook_so_x86_64}。 */
    private static final String HOOK_SO_ABI_KEY_PREFIX = "res_hook_so_";

    /**
     * 名字表（契约 §4.1 / §4.2）：逻辑名、旧条目名、config 键。
     *
     * <p>三列写在同一行，是为了让"改一个名字要同时改哪几处"一眼可见（历史上这三份东西分别写在
     * XpInit / NativeLoader / ModuleLoader 的常量里，改一处漏两处的风险很高）。
     * 第二列是<b>老产物的回落值</b>，必须与混淆功能上线前的字面量逐字一致。
     */
    private static final String[][] TABLE = {
            // 逻辑名            旧条目名（回落值）        config 键
            {"core", "xp/core.dex", "res_core"},
            {"hook_dex", "xp/hook_core.dex", "res_hook_dex"},
            {"hook_so", "xp/hook_core.so", "res_hook_so"},
            {"source", "xp/source.apk", "res_source"},
            {"source_manifest", "xp/source.manifest", "res_source_manifest"},
            {"pkg", "xp/pkg", "res_pkg"},
            {"file_provider", "xp/file_provider", "res_file_provider"},
            {"dex89", "xp/xp89.dex", "res_dex89"},
            {"dex100", "xp/xp100.dex", "res_dex100"},
            {"dex101", "xp/xp101.dex", "res_dex101"},
            {"dex102", "xp/xp102.dex", "res_dex102"},
            {"config", LEGACY_CONFIG, "res_cfg"},
            // xp/o_app.apk 是历史的"旧原包"条目，契约 §4.2 暂时没有对应键；读侧容忍缺失，
            // 保留这一行是为了让它与其它条目走同一条解析路径（A 加键时只改这里）。
            {"o_source", "xp/o_app.apk", "res_o_source"},
    };

    /**
     * 候选 config 的条目名形状（契约 §3）：
     * {@code assets/<8 位小写 hex>/<10~14 位小写字母数字>.<dat|bin|json>}。
     *
     * <p>它只是"形状"过滤：真实 APK 的资产名完全可能偶然撞上，所以命中后还要验内容。
     */
    private static final Pattern LAYOUT_CANDIDATE =
            Pattern.compile("^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\\.(dat|bin|json)$");

    /** 串行化首次解析：多线程同时第一次 get() 时只扫一遍 APK。 */
    private static final Object LOCK = new Object();

    /** 解析成功的快照；{@code null} 表示"还没成功解析过"（失败不缓存，见 {@link #attempts}）。 */
    private static volatile Layout layout;

    /** 已尝试次数。1 = 首次，2 = 失败后的那次重试；用完就永久回落旧名字。 */
    private static volatile int attempts;

    /** 发现尝试上限（首次 + 一次重试）。 */
    private static final int MAX_ATTEMPTS = 2;

    private XpLayout() {
    }

    /**
     * 逻辑名 → 包装 APK 里的真实条目名。
     *
     * <p>逻辑名：{@code core}、{@code hook_dex}、{@code hook_so}、{@code hook_so_<abi>}、
     * {@code source}、{@code source_manifest}、{@code pkg}、{@code file_provider}、
     * {@code dex89}/{@code dex100}/{@code dex101}/{@code dex102}。
     *
     * @param logical 逻辑名；为 {@code null} 或表里没有的名字时按 {@code xp/<logical>} 兜底
     * @return 真实条目名（可能是回落值，也不保证在包里存在）；<b>永不返回 null、永不抛异常</b>
     */
    public static String get(String logical) {
        try {
            ensureInstalled();
            Layout current = layout;
            if (current != null && logical != null) {
                String path = current.paths.get(logical);
                if (path != null) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
            // 引导期绝不允许异常逃出去：下面统一回落旧名字
        }
        return legacy(logical);
    }

    /**
     * 本次实际使用的 config 条目名：发现到的混淆条目名，或回落的 {@code xp/config.json}。
     *
     * <p>调用方（{@link XpInit}）用它打开 config；发现不到任何 config 时返回旧名字，
     * 让后续的"读不到配置"告警与今天完全一致。
     */
    public static String configEntry() {
        try {
            ensureInstalled();
            Layout current = layout;
            if (current != null && current.config != null) {
                return current.config;
            }
        } catch (Throwable ignored) {
        }
        return LEGACY_CONFIG;
    }

    /**
     * 惰性安装布局：从包装 APK 发现 config、读 {@code res_*}、把结果发布到 {@link #layout}。
     *
     * <p>幂等：成功后所有调用直接返回；真正失败（zip 打不开 / config 解析炸了…）只消耗一次尝试，
     * 下一次调用会再试一次，两次都失败就永久回落旧名字 —— 混淆产物在这种极端情况下会读不到
     * 内核资产，但这比"每个 get() 都重扫一遍 zip"要好，也比"抛异常让应用打不开"好得多。
     * {@code AppEnv.selfApk} 还没就绪<b>不算</b>失败尝试（那时什么都没做）。
     *
     * <p>失败时 {@link #get(String)} 的调用方仍会拿到<b>能用的旧名字</b>，所以本方法对调用方而言
     * 永远只是"尽力而为"，不需要判断返回值。
     */
    public static void ensureInstalled() {
        Layout current = layout;
        if (current != null || attempts >= MAX_ATTEMPTS) {
            return;
        }
        // "还没有 selfApk"不算一次失败尝试：这时什么都没做（不碰 IO），重试成本为零。
        // 否则极早的两次 get() 就会把重试机会用光，之后哪怕 AppEnv 就绪也永远只能读旧名字。
        if (selfApk() == null) {
            return;
        }
        synchronized (LOCK) {
            if (layout != null || attempts >= MAX_ATTEMPTS || selfApk() == null) {
                return;
            }
            attempts++;
            Layout discovered = null;
            try {
                discovered = discover();
            } catch (Throwable ignored) {
                // 失败不缓存：把这次尝试记在 attempts 里，下一次调用还能再试一次
            }
            layout = discovered;
        }
    }

    /**
     * 读"别的包"的 config 文本（{@code PMSignBypass} 用它从任意 APK 里取原证书）。
     *
     * <p>布局由候选正则 + 内容校验<b>现场发现</b>，与包名、与"是不是本进程的包"都无关：
     * 目标包可能是被别的管理器混淆过的产物，也可能根本不是 XPatch 产物。
     *
     * @param zip 已经打开的 APK；{@code null} 时返回 {@code null}
     * @return config 的 JSON 文本；没发现 config（普通 APK 就是这种情况，属正常）或读取失败
     *         一律返回 {@code null}
     */
    public static String readConfigText(ZipFile zip) {
        if (zip == null) {
            return null;
        }
        try {
            String name = discoverConfigEntry(zip);
            if (name == null) {
                return null;
            }
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_CONFIG_BYTES) {
                return null;
            }
            byte[] raw = readEntry(zip, entry, MAX_CONFIG_BYTES);
            return raw == null ? null : decode(raw);
        } catch (Throwable ignored) {
            // zip 损坏 / 条目读不出 / OOM：当作"这个包没有 config"，调用方走负缓存
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 发现与解析
    // ------------------------------------------------------------------

    /** 打开包装 APK、发现 config、解析布局；异常一律抛给调用方，由 {@link #ensureInstalled()} 吞掉当"暂时失败"。 */
    private static Layout discover() {
        String apk = selfApk();
        if (apk == null) {
            // AppEnv 还没就绪：当作"暂时失败"，让调用方还能再试一次
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            String configEntry = discoverConfigEntry(zip);
            if (configEntry == null) {
                // 既没有布局候选也没有 xp/config.json：老产物或没有注入过，全部回落旧名字
                return new Layout(null, null);
            }
            ZipEntry entry = zip.getEntry(configEntry);
            byte[] raw = entry == null ? null : readEntry(zip, entry, MAX_CONFIG_BYTES);
            return parse(configEntry, raw);
        } catch (Throwable th) {
            return null;
        } finally {
            closeQuietly(zip);
        }
    }

    /**
     * 发现 config 条目名（契约 §3）。规则与 manager / 引导桩逐字一致：
     *
     * <ol>
     *   <li>扫条目名，命中 {@link #LAYOUT_CANDIDATE} 的才算候选；</li>
     *   <li><b>按 zip 中心目录顺序</b>逐个验内容（≤1 MB、trim 后以 <code>{</code> 开头、含
     *       {@code "res_cfg"}），第一个命中的即 config；</li>
     *   <li>一个候选都没命中但存在 {@link #LEGACY_CONFIG} → 用它（老产物）；</li>
     *   <li>都没有 → {@code null}（老产物没有 config 是合法状态，不是错误）。</li>
     * </ol>
     *
     * <p>刻意<b>不排序</b>：manager 的 {@code InjectLayout.discoverConfigEntry} 与引导桩的
     * {@code AppEntry} 都是"zip 顺序第一个"，三份实现对同一份产物必须挑出同一个条目。
     * 不排序还顺带省掉一个候选列表与一次排序（本方法因此零分配）—— 引导期每个进程都会跑一遍。
     */
    private static String discoverConfigEntry(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry == null || entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (name == null || name.length() == 0 || !LAYOUT_CANDIDATE.matcher(name).matches()) {
                continue;
            }
            // 先看条目声明的大小再限量读取：正则只过滤"形状"，不能为一个撞名去解压几十 MB 的文件
            if (entry.getSize() > MAX_CONFIG_BYTES) {
                continue;
            }
            // 直接用枚举出来的 ZipEntry：不再 getEntry(name) 二次查找（Android 17 的
            // ZipFile.getEntry 在个别包装包上会返回 null，见 ModuleLoader.findEntry 的同类兜底）
            if (isLayoutConfig(readEntry(zip, entry, MAX_CONFIG_BYTES))) {
                return name;
            }
        }
        if (zip.getEntry(LEGACY_CONFIG) != null) {
            return LEGACY_CONFIG;
        }
        return null;
    }

    /** 内容判定：≤1 MB、trim 后以 '{' 开头、且包含 {@code "res_cfg"}（config 自描述它自己的路径）。 */
    private static boolean isLayoutConfig(byte[] raw) {
        if (raw == null || raw.length == 0 || raw.length > MAX_CONFIG_BYTES) {
            return false;
        }
        String text = decode(raw);
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() == 0 || trimmed.charAt(0) != '{') {
            return false;
        }
        return text.indexOf("\"res_cfg\"") >= 0;
    }

    /**
     * 解析 config 的 {@code res_*} 键，得到布局快照。
     *
     * <p>用 {@code org.json} 而不是自己写字符串查找：内核读 config 一直用的就是它
     * （见 {@code XpInit.performXpConfig}），它是 android.jar 自带的，不新增任何依赖。
     *
     * <p>所有 {@code res_*} 键都读一遍：内核今天只消费其中的<b>路径键</b>，
     * {@code res_v / res_enc / res_dir / res_boot_dex / res_factory / res_provider /
     * res_runtime_class} 是引导桩与自检用的，这里读出来只为把一行布局摘要打进日志（排障用，
     * 二期载荷加密直接读 {@code res_enc}）。任何键读不到都不影响行为。
     */
    private static Layout parse(String configEntry, byte[] raw) {
        String text = raw == null ? null : decode(raw);
        if (text == null) {
            return new Layout(configEntry, null);
        }
        JSONObject json;
        try {
            json = new JSONObject(text);
        } catch (Throwable th) {
            // config 坏了：按"没有布局"处理，全部回落旧名字（XpInit 随后还会因为同一个原因告警）
            return new Layout(configEntry, null);
        }
        Map<String, String> paths = new HashMap<String, String>();
        try {
            for (int i = 0; i < TABLE.length; i++) {
                String path = value(json, TABLE[i][COL_KEY]);
                if (path != null) {
                    paths.put(TABLE[i][COL_LOGICAL], path);
                }
            }
            // ABI 专属资产（res_hook_so_x86_64 等）无法穷举：键按 ABI 命名，扫一遍 json 的键比
            // "先探测设备 ABI 再拼键"更简单，也不会漏掉本机以外的 ABI。
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key == null || !key.startsWith(HOOK_SO_ABI_KEY_PREFIX)
                        || key.length() <= HOOK_SO_ABI_KEY_PREFIX.length()) {
                    continue;
                }
                String path = value(json, key);
                if (path != null) {
                    paths.put(HOOK_SO_ABI_PREFIX + key.substring(HOOK_SO_ABI_KEY_PREFIX.length()), path);
                }
            }
        } catch (Throwable ignored) {
            // 迭代异常只影响"用哪个名字"：已经读到的键仍然生效，剩下的回落旧名
        }
        if (!paths.isEmpty()) {
            logQuietly("注入布局：config=" + configEntry + "，v=" + optInt(json, "res_v")
                    + "，enc=" + optInt(json, "res_enc")
                    + "，dir=" + orDash(value(json, "res_dir"))
                    + "，boot=" + orDash(value(json, "res_boot_dex"))
                    + "，factory=" + orDash(value(json, "res_factory"))
                    + "，runtime=" + orDash(value(json, "res_runtime_class"))
                    + "，provider=" + orDash(value(json, "res_provider"))
                    + "，res=" + paths.size() + " 项");
        }
        return new Layout(configEntry, paths);
    }

    /** 读一个字符串键：缺失 / 空值 / JSON null 一律返回 {@code null}（= 该键视为不存在，回落旧名字）。 */
    private static String value(JSONObject json, String key) {
        try {
            String value = json.optString(key, "");
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            // Android 的 optString 会把 JSON null 转成字符串 "null"，必须当缺失处理
            if (trimmed.length() == 0 || "null".equals(trimmed)) {
                return null;
            }
            return trimmed;
        } catch (Throwable th) {
            return null;
        }
    }

    private static int optInt(JSONObject json, String key) {
        try {
            return json.optInt(key, 0);
        } catch (Throwable th) {
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // 回落值与小工具
    // ------------------------------------------------------------------

    /** 逻辑名 → 旧条目名（表里没有的名字按 {@code xp/<logical>} 兜底，与历史 {@code "xp/" + 裸名} 一致）。 */
    private static String legacy(String logical) {
        if (logical == null) {
            return "";
        }
        for (int i = 0; i < TABLE.length; i++) {
            if (TABLE[i][COL_LOGICAL].equals(logical)) {
                return TABLE[i][COL_LEGACY];
            }
        }
        if (logical.startsWith(HOOK_SO_ABI_PREFIX)) {
            // hook_so_<abi> → xp/hook_core_<abi>.so（模板行，不在 TABLE 里）
            return "xp/hook_core_" + logical.substring(HOOK_SO_ABI_PREFIX.length()) + ".so";
        }
        return "xp/" + logical;
    }

    /** 布局发现用的 APK：selfApk 是历史口径；为空时退回 wrapperApk（两者通常就是同一条路径）。 */
    private static String selfApk() {
        String apk = AppEnv.selfApk;
        if (apk == null || apk.trim().length() == 0) {
            apk = AppEnv.wrapperApk;
        }
        if (apk == null || apk.trim().length() == 0) {
            return null;
        }
        return apk;
    }

    /** 限量读条目内容；超过 {@code limit} 返回 {@code null}（不把"形状像 config"的大文件读进内存）。 */
    private static byte[] readEntry(ZipFile zip, ZipEntry entry, int limit) {
        InputStream in = null;
        try {
            in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) > 0) {
                total += count;
                if (total > limit) {
                    return null;
                }
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Throwable th) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 把 config 字节解成文本；编码问题一律当"读不到"处理，不当作注入失败。 */
    private static String decode(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new String(raw, "UTF-8");
        } catch (Throwable th) {
            return null;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 关闭失败无所谓：内容已经读完（或本来就失败了）
        }
    }

    /**
     * 只在"发现到布局"时打一行日志：引导期每个进程都会跑一遍，老产物必须一声不响，
     * 否则每次启动都多一行噪声。日志本身也不允许把布局解析搞挂。
     */
    private static void logQuietly(String message) {
        try {
            XpHook.log0(TAG, message);
        } catch (Throwable ignored) {
        }
    }

    private static String orDash(String value) {
        return value == null ? "-" : value;
    }

    /** 一次解析出的布局快照（构造后不再修改；老产物/解析失败时 {@link #paths} 为空 = 全用旧名字）。 */
    private static final class Layout {

        /** 本次用到的 config 条目名：发现到的混淆名，或回落的 xp/config.json。 */
        final String config;

        /** 逻辑名 → 真实条目名。只放 config 里真的出现过的键。 */
        final Map<String, String> paths;

        Layout(String config, Map<String, String> paths) {
            this.config = config;
            this.paths = paths == null ? Collections.<String, String>emptyMap() : paths;
        }
    }
}
