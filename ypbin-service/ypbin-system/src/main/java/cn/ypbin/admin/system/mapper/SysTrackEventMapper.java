/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.system.mapper;

import cn.ypbin.admin.system.entity.SysTrackEvent;
import cn.ypbin.admin.system.model.resp.TrackAppCountResp;
import cn.ypbin.admin.system.model.resp.TrackOverviewResp;
import cn.ypbin.admin.system.model.resp.TrackTopEventResp;
import cn.ypbin.admin.system.model.resp.TrackTrendResp;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 埋点事件 Mapper。
 *
 * @author wenbin
 * @since 2026-09-16
 */
public interface SysTrackEventMapper extends BaseMapper<SysTrackEvent> {

    /**
     * 批量写入事件，并用 {@code ON DUPLICATE KEY UPDATE id = id} 实现幂等。
     *
     * <p><strong>为什么不是 {@code INSERT IGNORE}</strong>：{@code IGNORE} 会把数据截断、非法值等错误
     * 一并降级为告警（静默数据丢失）；{@code ON DUPLICATE KEY UPDATE} 只吞掉重复键，其余错误照旧抛出。</p>
     *
     * <p>重复键在此处是<strong>常态</strong>而非异常：客户端重试与离页兜底（sendBeacon）会与常规批量上报
     * 并发提交同一批事件。若用普通 INSERT，单条重复会让<strong>整批</strong>失败并丢弃。</p>
     *
     * <p>注解里写全限定类名是 MyBatis 的硬性要求（{@code typeHandler} 属性值由容器反射实例化，无法用 import
     * 表达）；这也是本仓唯一一处出现全限定类名的位置，特此说明。</p>
     *
     * @param events 待写入事件（调用方保证非空）
     * @return 受影响行数（MySQL 对「插入成功」计 1、「重复键未更新」计 0）
     */
    @Insert("""
        <script>
        INSERT INTO sys_track_event (id, event_id, event_code, app_id, user_id, tenant_id, session_id, anon_id,
            trace_id, event_time, received_time, page_url, referrer, ip, user_agent, duration_ms, success, payload)
        VALUES
        <foreach collection="events" item="item" separator=",">
            (#{item.id}, #{item.eventId}, #{item.eventCode}, #{item.appId}, #{item.userId}, #{item.tenantId},
             #{item.sessionId}, #{item.anonId}, #{item.traceId}, #{item.eventTime}, #{item.receivedTime},
             #{item.pageUrl}, #{item.referrer}, #{item.ip}, #{item.userAgent}, #{item.durationMs},
             #{item.success},
             #{item.payload, typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler})
        </foreach>
        ON DUPLICATE KEY UPDATE id = id
        </script>
        """)
    int insertBatch(@Param("events") List<SysTrackEvent> events);

    /**
     * 按天聚合事件数（升序）；无事件的日期不返回，由上层补零。
     *
     * @param since 起始时间（含）
     * @return 每天一条 {date, count}
     */
    @Select("""
        SELECT DATE_FORMAT(received_time, '%Y-%m-%d') AS `date`, COUNT(*) AS `count`
        FROM sys_track_event
        WHERE received_time >= #{since}
        GROUP BY DATE_FORMAT(received_time, '%Y-%m-%d')
        ORDER BY `date`
        """)
    List<TrackTrendResp> selectDailyTrend(@Param("since") LocalDateTime since);

    /**
     * 概览统计（一条 SQL 取全部计数，避免多次往返）。
     *
     * <p>用 {@code COALESCE} 兜住空表的 {@code SUM} 为 NULL 的情况；{@code COUNT(DISTINCT ...)} 只统计
     * 近 7 天且有用户标识的事件（匿名事件的 user_id 为空，不应计入活跃用户）。</p>
     *
     * @param todayStart 今日零点
     * @param weekStart  7 天前零点
     * @return 概览计数
     */
    @Select("""
        SELECT
            COUNT(*) AS totalEvents,
            COALESCE(SUM(CASE WHEN received_time >= #{todayStart} THEN 1 ELSE 0 END), 0) AS todayEvents,
            COALESCE(SUM(CASE WHEN received_time >= #{weekStart} THEN 1 ELSE 0 END), 0) AS weekEvents,
            COALESCE(SUM(CASE WHEN received_time >= #{weekStart} AND success = 0 THEN 1 ELSE 0 END), 0)
                AS weekFailures,
            COUNT(DISTINCT CASE WHEN received_time >= #{weekStart} THEN user_id END) AS weekUsers
        FROM sys_track_event
        """)
    TrackOverviewResp selectOverview(@Param("todayStart") LocalDateTime todayStart,
                                     @Param("weekStart") LocalDateTime weekStart);

    /**
     * 事件码排行（降序，取前 limit 个）。
     *
     * <p>只回事件码与次数：中文描述由前端按事件目录映射（目录的事实源在 starter 仓）。</p>
     *
     * @param since 起始时间（含）
     * @param limit 返回条数（调用方已校验上限）
     * @return 事件码与次数
     */
    @Select("""
        SELECT event_code, COUNT(*) AS `count`
        FROM sys_track_event
        WHERE received_time >= #{since}
        GROUP BY event_code
        ORDER BY `count` DESC
        LIMIT #{limit}
        """)
    List<TrackTopEventResp> selectTopEvents(@Param("since") LocalDateTime since, @Param("limit") int limit);

    /**
     * 应用维度分布（降序）。
     *
     * <p>事件未带 appId 时会聚出一行 app_id 为 NULL 的记录，由前端展示为"未设置"。</p>
     *
     * @param since 起始时间（含）
     * @return 应用与次数
     */
    @Select("""
        SELECT app_id, COUNT(*) AS `count`
        FROM sys_track_event
        WHERE received_time >= #{since}
        GROUP BY app_id
        ORDER BY `count` DESC
        """)
    List<TrackAppCountResp> selectAppDistribution(@Param("since") LocalDateTime since);
}
