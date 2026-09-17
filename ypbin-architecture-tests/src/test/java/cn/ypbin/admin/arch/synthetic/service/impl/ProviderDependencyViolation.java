/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.arch.synthetic.service.impl;

import cn.ypbin.admin.system.provider.AdminDataScopeHandler;

/**
 * 合成违规样例：{@code service/impl} 直接依赖 {@code provider} 的具体适配实现类。
 *
 * <p>复刻的是 2026-09-17 那次真实回归的形态——{@code SysUserServiceImpl} 曾直接持有
 * {@code provider.AdminDataScopeHandler} 来复用数据范围判定。本类只存在于<b>测试源码</b>，
 * 门禁的字节码导入带 {@code DO_NOT_INCLUDE_TESTS}、源码规则只扫 {@code src/main/java}，
 * 故它不会反过来把门禁自己搞红。</p>
 *
 * <p>刻意持有<b>真实主源码</b>的适配类（而非再造一个假的 provider 类）：这样自检证明的是
 * 「规则能抓住本仓真实存在的这类违规」，而不是「能抓住我特意为规则造的形状」。</p>
 *
 * @author wenbin
 * @since 2026-09-17
 */
@SuppressWarnings("unused")
public class ProviderDependencyViolation {

    /** 违规依赖：实现层直连宿主端口适配实现 */
    private AdminDataScopeHandler dataScopeHandler;
}
