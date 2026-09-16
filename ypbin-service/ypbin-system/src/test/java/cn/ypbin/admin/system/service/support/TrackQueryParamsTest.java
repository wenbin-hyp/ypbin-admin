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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.starter.core.exception.BusinessException;
import org.junit.jupiter.api.Test;

/**
 * 埋点分析参数校验测试（纯逻辑，不依赖数据库）。
 *
 * @author wenbin
 * @since 2026-09-16
 */
class TrackQueryParamsTest {

    @Test
    void shouldAcceptBoundaryDays() {
        assertThatCode(() -> TrackQueryParams.requireDays(1)).doesNotThrowAnyException();
        assertThatCode(() -> TrackQueryParams.requireDays(90)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectOutOfRangeDays() {
        // 上限既防前端误传，也防明细表按天聚合的慢查询
        assertThatThrownBy(() -> TrackQueryParams.requireDays(0))
            .isInstanceOf(BusinessException.class).hasMessageContaining("统计天数");
        assertThatThrownBy(() -> TrackQueryParams.requireDays(91))
            .isInstanceOf(BusinessException.class).hasMessageContaining("统计天数");
    }

    @Test
    void shouldAcceptBoundaryLimit() {
        assertThatCode(() -> TrackQueryParams.requireLimit(1)).doesNotThrowAnyException();
        assertThatCode(() -> TrackQueryParams.requireLimit(50)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectOutOfRangeLimit() {
        assertThatThrownBy(() -> TrackQueryParams.requireLimit(0))
            .isInstanceOf(BusinessException.class).hasMessageContaining("排行条数");
        assertThatThrownBy(() -> TrackQueryParams.requireLimit(51))
            .isInstanceOf(BusinessException.class).hasMessageContaining("排行条数");
    }
}
