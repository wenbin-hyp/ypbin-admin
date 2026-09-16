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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 聚合任务的重算窗口。
 *
 * <p><strong>为什么按天整段重算</strong>：事件可能延迟到达（消费者批量落库、客户端补发、
 * 离页兜底），只做增量会永久漏算。窗口取「最近 N 天」即可覆盖延迟，无需额外水位表，且天然自愈
 * ——任务失败后下次跑同一窗口就会补上。</p>
 *
 * <p>抽成独立类是为了能在<strong>不依赖数据库与时钟</strong>的前提下测试窗口边界：
 * 调用方把 {@code today} 传进来，本类不自己取 {@code LocalDate.now()}。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
public final class TrackAggregateWindows {

    /** 窗口天数下限 */
    private static final int MIN_WINDOW_DAYS = 1;

    private TrackAggregateWindows() {
    }

    /**
     * 计算需要重算的日期（升序，含结束日）。
     *
     * @param today 结束日期（通常是今天）
     * @param days  窗口天数，必须为正
     * @return 从 {@code today - days + 1} 到 {@code today} 的连续日期
     */
    public static List<LocalDate> recentDates(LocalDate today, int days) {
        if (days < MIN_WINDOW_DAYS) {
            throw new IllegalArgumentException("聚合窗口天数必须为正数，当前为 " + days);
        }
        List<LocalDate> dates = new ArrayList<>(days);
        LocalDate startDate = today.minusDays(days - 1L);
        for (int index = 0; index < days; index++) {
            dates.add(startDate.plusDays(index));
        }
        return dates;
    }
}
