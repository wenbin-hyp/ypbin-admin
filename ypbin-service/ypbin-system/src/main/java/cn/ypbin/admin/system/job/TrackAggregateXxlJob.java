/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.job;

import cn.ypbin.admin.system.service.TrackAggregateService;
import cn.ypbin.admin.system.service.support.TrackAggregateWindows;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 埋点分析聚合任务（XXL-JOB 执行器）。
 *
 * <p>由 xxl-job-admin 按 cron 周期触发（建议每小时一次），重算最近
 * {@link #WINDOW_DAYS} 天的三张聚合表。注册执行器名 {@code trackAggregateScan}。</p>
 *
 * <p><strong>为什么是「最近 2 天」而不是「只算昨天」</strong>：事件会延迟到达，
 * 重算窗口必须覆盖延迟；窗口重算天然自愈——上次失败的那天会在下次跑窗口时被补上，
 * 不需要额外的水位表。</p>
 *
 * <p><strong>为什么每天单独一个事务</strong>：某一天失败不应影响其它天。
 * 单天失败在这里记完整堆栈后继续（与 {@link NoticePublishXxlJob} 同策略），
 * 错误被看见而非被吞掉；要判定整体失败请以日志为准，不在任务里静默汇总。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
@Component
@RequiredArgsConstructor
public class TrackAggregateXxlJob {

    private static final Logger log = LoggerFactory.getLogger(TrackAggregateXxlJob.class);

    /** 重算窗口天数：覆盖延迟到达的事件，2 天足够 */
    private static final int WINDOW_DAYS = 2;

    private final TrackAggregateService trackAggregateService;

    @XxlJob("trackAggregateScan")
    public void execute() {
        List<LocalDate> dates = TrackAggregateWindows.recentDates(LocalDate.now(), WINDOW_DAYS);
        for (LocalDate date : dates) {
            try {
                trackAggregateService.rebuildDate(date);
            } catch (Exception e) {
                log.error("埋点聚合失败，statDate={}", date, e);
            }
        }
        log.info("埋点聚合窗口重算完成，窗口={}", dates);
    }
}
