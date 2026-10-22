package org.conscrypt;

import java.security.Provider;

/**
 * conscrypt 的最小替身。
 *
 * 背景：Robolectric 在构建 AndroidSandbox 时会注入一个基于
 * {@link OpenSSLProvider} 的加密服务提供者，而 conscrypt-openjdk-uber 只打包了
 * linux-x86_64 的 native 库，在 aarch64 上直接 UnsatisfiedLinkError。
 *
 * 本项目测试只走明文 TCP（127.0.0.1:47823），不需要真实的 TLS 实现，
 * 所以提供一个空壳 Provider 让 Robolectric 能完成注入即可。
 *
 * 仅存在于 test 源码集，不会进入 APK。
 */
public class OpenSSLProvider extends Provider {

    private static final long serialVersionUID = 1L;

    public OpenSSLProvider() {
        this("Conscrypt");
    }

    public OpenSSLProvider(String providerName) {
        super(providerName, 1.0d, "LanCmd test stub for conscrypt");
    }
}
