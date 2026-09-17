/*
 * Copyright (c) 2026-present ypbin-admin authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package cn.ypbin.admin.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * admin 仓「字节码级」架构约束：这些规则在字节码上判定比源码正则更可靠。
 *
 * <p>与 {@link SourceConventionTest} 的分工原则：能看字节码就别扫源码（源码正则会漏判「注解与签名同行」
 * 「通配导入」「泛型/raw 类型」等写法）。反过来，Lombok {@code @Data}（SOURCE 保留）、
 * {@code switch(enum)} 编译出的 {@code ordinal()}、循环边界这三类在字节码上<b>不可表达或必然误报</b>，
 * 才落到源码扫描——这个划分沿用 starter 侧已踩过坑的结论。</p>
 *
 * @author wenbin
 * @since 2026-09-16
 */
class CodingRulesTest {

    /** Spring 事务注解全限定名（精确匹配，避免把 @TransactionalEventListener 误当作 @Transactional） */
    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    /** 字段注入注解全限定名 */
    private static final List<String> FIELD_INJECTION_ANNOTATIONS = List.of(
        "org.springframework.beans.factory.annotation.Autowired",
        "jakarta.annotation.Resource");

    /** 谓词：调用 printStackTrace()（owner 多为 Throwable 子类，故按可赋值判定） */
    private static final DescribedPredicate<JavaMethodCall> CALLS_PRINT_STACK_TRACE =
        DescribedPredicate.describe("调用 printStackTrace()", call ->
            call.getName().equals("printStackTrace") && call.getTargetOwner().isAssignableTo(Throwable.class));

    /** 谓词：访问 System.out */
    private static final DescribedPredicate<JavaFieldAccess> ACCESSES_SYSTEM_OUT =
        DescribedPredicate.describe("访问 System.out", access ->
            access.getName().equals("out") && access.getTargetOwner().isAssignableTo(System.class));

    /** 谓词：访问 System.err */
    private static final DescribedPredicate<JavaFieldAccess> ACCESSES_SYSTEM_ERR =
        DescribedPredicate.describe("访问 System.err", access ->
            access.getName().equals("err") && access.getTargetOwner().isAssignableTo(System.class));

    /** 业务实现层包（各业务域的 {@code service/impl}） */
    private static final String SERVICE_IMPL_PACKAGE = "..service.impl..";

    /** 宿主端口适配层包（各业务域的 {@code provider}） */
    private static final String PROVIDER_PACKAGE = "..provider..";

    /**
     * 规则：{@code service/impl} 不得依赖 {@code provider}。
     *
     * <p><strong>为什么需要这条</strong>：{@code provider} 是「宿主按 starter 端口契约给出的适配实现」
     * （数据范围、字典、敏感词、日志等），{@code service/impl} 是业务实现层。实现层直接依赖适配实现类，
     * 会把「实现层 → 适配层」的依赖方向倒置成硬耦合：适配实现一旦换实现（换库、换缓存、换策略）
     * 就要动业务实现，且两者之间无法独立测试与替换。</p>
     *
     * <p><strong>这是一次真实回归的护栏</strong>（2026-09-17）：{@code SysUserServiceImpl} 曾直接持有
     * {@code provider.AdminDataScopeHandler} 以复用「该部门是否在数据范围内」的判定，导致写入路径与
     * 读取路径共用同一个具体实现类；上一轮重构把该判定抽为 {@code service/support} 下的共享能力
     * （{@code DataScopeResolver}/{@code AbstractDataScopeResolver}），{@code provider} 只保留端口适配。
     * 本条规则把「不许倒回去」固化成构建失败，而不是靠 review 记得。</p>
     *
     * <p><strong>方向性</strong>：只禁止 {@code service/impl → provider}。反向（{@code provider} 里的适配器
     * 依赖 {@code service/impl}）不在本条约束内，由 {@code ArchRuleSelfCheckTest} 显式断言不被误报。</p>
     *
     * <p><strong>为什么用包名通配而非写死模块</strong>：admin 是多业务域仓（{@code system} / {@code ai} /
     * 未来的域），写死 {@code cn.ypbin.admin.system} 会让新域的同类违规静默逃逸——正是本仓
     * 「门禁必须覆盖完整」的一贯口径。</p>
     *
     * @return 规则本体（门禁与「有效性自检」共用同一对象，避免自检验的是副本）
     */
    static ArchRule serviceImplShouldNotDependOnProvider() {
        return noClasses()
            .that().resideInAPackage(SERVICE_IMPL_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(PROVIDER_PACKAGE)
            .because("service/impl 是业务实现层、provider 是宿主端口适配层；实现层直接依赖适配实现类会把"
                + "「实现层 → 适配层」倒置成硬耦合（历史回归：SysUserServiceImpl 依赖 provider.AdminDataScopeHandler）。"
                + "共享能力请抽到 service/support（如 DataScopeResolver），provider 只保留端口适配");
    }

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.ypbin.admin");
    }

    @Test
    @DisplayName("禁止 printStackTrace 与直接使用 System.out/System.err")
    void shouldNotPrintStackTraceOrUseSystemStreams() {
        // 用 callMethodWhere 而非 callMethod(Throwable.class, ...)：调用点 owner 通常是子类
        // （如 Exception/IllegalStateException），按精确 owner 匹配会静默漏掉全部违规
        noClasses()
            .should().callMethodWhere(CALLS_PRINT_STACK_TRACE)
            .because("必须走日志框架并传完整堆栈（log.error(\"...\", ex)），printStackTrace 丢失日志上下文")
            .check(classes);

        noClasses()
            .should().accessFieldWhere(ACCESSES_SYSTEM_OUT)
            .orShould().accessFieldWhere(ACCESSES_SYSTEM_ERR)
            .because("标准输出绕过日志框架与统一格式，生产环境不可追踪")
            .check(classes);
    }

    @Test
    @DisplayName("@Transactional 必须显式声明 rollbackFor（方法级与类级都要，否则受检异常漏回滚）")
    void transactionalShouldAlwaysDeclareRollbackFor() {
        List<String> violations = transactionalMembersWithoutRollbackFor(classes);
        assertThat(violations)
            .as("写操作的 @Transactional 必须带 rollbackFor = Exception.class；"
                + "注意 @TransactionalEventListener 不是 @Transactional，不该被判违规")
            .isEmpty();
    }

    @Test
    @DisplayName("禁止字段注入（@Autowired/@Resource 标注字段），统一构造器注入")
    void shouldNotUseFieldInjection() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            for (JavaField field : clazz.getFields()) {
                boolean injected = field.getAnnotations().stream()
                    .anyMatch(annotation -> FIELD_INJECTION_ANNOTATIONS.contains(annotation.getRawType().getName()));
                if (injected) {
                    violations.add(clazz.getName() + "#" + field.getName());
                }
            }
        }
        assertThat(violations)
            .as("字段注入让依赖不可变性与可测性变差（本仓统一 @RequiredArgsConstructor + final 字段）")
            .isEmpty();
    }

    @Test
    @DisplayName("service/impl 包禁止依赖 provider 包（实现层不得直接依赖宿主端口适配实现）")
    void serviceImplShouldNotDependOnProviderPackages() {
        serviceImplShouldNotDependOnProvider().check(classes);
    }

    /**
     * 判定注解是否**显式**声明了 rollbackFor。
     *
     * <p>⚠️ 这里不能用 {@code getProperties().containsKey("rollbackFor")}：ArchUnit 的
     * {@code getProperties()} <b>会把注解默认值一并填进来</b>——实测未写任何属性的
     * {@code @Transactional} 其 props 里就已有 {@code rollbackFor=[]}（空数组），
     * 于是「containsKey」判定恒为真，规则<b>永远通过（恒真）</b>，是典型的假绿。
     * 正确做法是按<b>取值</b>判：{@code rollbackFor} 的默认值是空数组，显式声明后非空。</p>
     *
     * <p>本方法只认 {@code rollbackFor}（铁律的字面要求）。仅写 {@code rollbackForClassName}
     * 的写法会被判为违规——本仓 2026-09-16 实测 0 处该写法。</p>
     *
     * @param annotation 注解实例
     * @return 显式声明了非空 rollbackFor 时返回 true
     */
    static boolean declaresRollbackFor(JavaAnnotation<?> annotation) {
        Object value = annotation.get("rollbackFor").orElse(null);
        if (value instanceof Object[] declared) {
            // JavaClass[] 也是 Object[]，长度即「显式声明了几个回滚异常类型」
            return declared.length > 0;
        }
        return value != null;
    }

    static List<String> transactionalMembersWithoutRollbackFor(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : classes) {
            // 类级 @Transactional 会作用于该类全部方法，缺 rollbackFor 同样在受检异常上漏回滚
            List<JavaAnnotation<JavaClass>> classAnnotations = clazz.getAnnotations().stream()
                .filter(annotation -> annotation.getRawType().getName().equals(TRANSACTIONAL))
                .toList();
            if (!classAnnotations.isEmpty()
                && !classAnnotations.stream().anyMatch(CodingRulesTest::declaresRollbackFor)) {
                violations.add(clazz.getName() + "（类级注解）");
            }
            for (JavaMethod method : clazz.getMethods()) {
                List<JavaAnnotation<JavaMethod>> annotations = method.getAnnotations().stream()
                    .filter(annotation -> annotation.getRawType().getName().equals(TRANSACTIONAL))
                    .toList();
                if (annotations.isEmpty()) {
                    continue;
                }
                boolean declared = annotations.stream().anyMatch(CodingRulesTest::declaresRollbackFor);
                if (!declared) {
                    violations.add(clazz.getName() + "#" + method.getName());
                }
            }
        }
        return violations;
    }
}
