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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 聚合窗口测试（纯逻辑，不依赖数据库与系统时钟）。
 *
 * @author wenbin
 * @since 2026-09-16
 */
class TrackAggregateWindowsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 16);

    @Test
    void shouldCoverRecentDaysInAscendingOrder() {
        List<LocalDate> dates = TrackAggregateWindows.recentDates(TODAY, 2);

        // 窗口必须覆盖「昨天」——延迟到达的事件会落在昨天，只算今天会永久漏算
        assertThat(dates).containsExactly(LocalDate.of(2026, 9, 15), TODAY);
    }

    @Test
    void shouldReturnSingleDayWhenWindowIsOne() {
        assertThat(TrackAggregateWindows.recentDates(TODAY, 1)).containsExactly(TODAY);
    }

    @Test
    void shouldRejectNonPositiveWindow() {
        assertThatThrownBy(() -> TrackAggregateWindows.recentDates(TODAY, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("窗口天数");
    }
}
