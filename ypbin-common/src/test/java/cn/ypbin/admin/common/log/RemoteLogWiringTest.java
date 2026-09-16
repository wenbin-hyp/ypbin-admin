/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.common.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.ypbin.admin.system.api.feign.ISystemClient;
import cn.ypbin.starter.log.autoconfigure.LogAutoConfiguration;
import cn.ypbin.starter.log.core.LogUserProvider;
import cn.ypbin.starter.log.dao.LogDao;
import cn.ypbin.starter.log.model.LogRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 跨服务日志上报的装配测试。
 *
 * <p>这是本任务的核心静默失效点：starter 的 {@code LogAutoConfiguration.logDao()} 带
 * {@code @ConditionalOnMissingBean}，谁都没提供实现时它会安静地装上"只打印不落库"的
 * {@code DefaultLogDao}——接口照常跑、{@code sys_log} 恒为空。本测试钉住两条：
 * ① 无本地实现时 {@code RemoteLogDao} 必须<b>排在 starter 默认实现之前</b>被装配
 * （靠 {@code @AutoConfiguration(beforeName = ...})）</p>
 * ② 宿主自带实现（system 的 {@code DbLogProviders.DbLogDao}）时上报实现必须退让，
 * 全容器只能存在一个 {@link LogDao}。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
class RemoteLogWiringTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(LogAutoConfiguration.class,
            RemoteLogAutoConfiguration.class))
        .withBean(ISystemClient.class, () -> mock(ISystemClient.class));

    @Test
    void remoteDaoShouldOverrideStarterPrintOnlyDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LogDao.class);
            assertThat(context).getBean(LogDao.class).isInstanceOf(RemoteLogDao.class);
            assertThat(context).getBean(LogUserProvider.class)
                .isInstanceOf(IdentityHeaderLogUserProvider.class);
        });
    }

    @Test
    void hostProvidedDaoShouldWinOverRemoteDao() {
        runner.withUserConfiguration(HostLogConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LogDao.class);
            assertThat(context).getBean(LogDao.class).isInstanceOf(HostLogDao.class);
        });
    }

    /**
     * fail-fast 边界：有 {@code LogDao}/{@code ISystemClient} 的类、却没有 system 客户端 Bean 时
     * （典型是漏了 {@code @EnableFeignClients}），启动<b>显式失败</b>而不是静默退回"只打印不落库"。
     * 用 {@code @ConditionalOnBean} 兜底没用——自动配置条件在 Feign 客户端注册之前求值。
     */
    @Test
    void shouldFailFastWhenNoSystemClientBeanIsAvailable() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogAutoConfiguration.class,
                RemoteLogAutoConfiguration.class))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(NoSuchBeanDefinitionException.class)
                    .hasMessageContaining("ISystemClient");
            });
    }

    /**
     * 模拟 system 的组件扫描结果：宿主自带落库实现。
     */
    @Configuration
    static class HostLogConfig {

        @Bean
        LogDao hostLogDao() {
            return new HostLogDao();
        }
    }

    /** 宿主实现替身（system 侧真实实现为 {@code DbLogProviders.DbLogDao}） */
    static class HostLogDao implements LogDao {

        @Override
        public void add(LogRecord logRecord) {
            // 测试替身：不做任何事
        }
    }
}
