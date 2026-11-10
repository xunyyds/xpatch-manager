package io.xpatch.core.signbypass;

interface SignBypassLevel {
    /**
     * 启用该层级的 native io 重定向。
     *
     * @return 是否真的装上了重定向（false = 降级：无原包 / 库未加载 / native 返回 false），
     *         由 {@link SignBypass#enable()} 汇总后交给 XpInit 打日志
     */
    boolean enable() throws Exception;
}
