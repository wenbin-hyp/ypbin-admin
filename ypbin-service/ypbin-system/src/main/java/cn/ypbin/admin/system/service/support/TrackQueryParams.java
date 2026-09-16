/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.service.support;

import cn.ypbin.starter.core.exception.BusinessException;

/**
 * 埋点分析查询参数校验。
 *
 * <p>抽成独立工具类是为了能在<strong>不依赖数据库</strong>的前提下测试这些边界：
 * 天数上限既防误传（前端写错）也防慢查询（明细表按天聚合扫描），条数上限防一次拉回过多数据。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
public final class TrackQueryParams {

    /** 统计天数下限 */
    private static final int MIN_DAYS = 1;

    /** 统计天数上限 */
    private static final int MAX_DAYS = 90;

    /** 排行条数下限 */
    private static final int MIN_LIMIT = 1;

    /** 排行条数上限 */
    private static final int MAX_LIMIT = 50;

    private TrackQueryParams() {
    }

    /**
     * 校验统计天数。
     *
     * @param days 天数
     */
    public static void requireDays(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            throw new BusinessException("统计天数必须在 " + MIN_DAYS + " 到 " + MAX_DAYS + " 之间");
        }
    }

    /**
     * 校验排行条数。
     *
     * @param limit 条数
     */
    public static void requireLimit(int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new BusinessException("排行条数必须在 " + MIN_LIMIT + " 到 " + MAX_LIMIT + " 之间");
        }
    }
}
