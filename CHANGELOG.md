# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 新增

- **AI 用量落库：宿主侧 `AiUsageListener` 实现（`ypbin-ai` 的 `AdminAiUsageListener`）**。
  此前 `ai_usage_log` **没有任何写入方**（全仓只有 Mapper 与统计读取），用量看板恒为空。
  starter 这一批补齐了回调触发点并改了契约（`AiUsageInfo` 的 token 由 `long` 改为可空 `Long`，
  新增 `outcome` 与 `errorMessage`），本仓按新契约实现宿主侧监听器：
  - **用户/租户维度由宿主补齐**（starter 回调不携带用户信息）：先取同线程
    `TenantContext`/`IdentityContext`，取不到时用 `conversationId`（即 `ai_chat_session` 主键）
    精确查一行补齐 tenantId/userId；匿名入口（`share-<kbId>`/`widget-<kbId>`/`kb-query-<kbId>`）
    没有用户与会话实体，`user_id`/`conversation_id` **如实落 NULL**，不伪造 ID；
    租户实在取不到则记 error 并放弃本次落库（禁止伪造成默认租户）。
  - **`ai_usage_log` 最小增量**（`deploy/sql/003-ai-schema.sql` + `deploy/sql/migration/2026-09-17-ai-usage-log-outcome.sql`）：
    `user_id` 与三个 token 列改可空（NULL＝上游未回报，**绝不折算成 0**）、新增
    `outcome`（success/failure/cancelled）与 `error_message`。已有库执行 migration 子目录脚本；
    全新安装无需额外操作（003 已是最终结构）。
  - **统计侧 NULL 口径**：聚合一律直写 `SUM(total_tokens)`，不在聚合参数上套 `COALESCE`
    （未知行绝不按 0 参与求和/均值）；每个聚合点额外返回未知条数
    （`unknownTokenCalls`/`unknownCalls`），用量总览另给出三类终局结果计数
    （`successCalls`/`failureCalls`/`cancelledCalls`）。
  - **异常隔离**：落库异常在监听器内捕获并 `log.error(..., ex)` 记完整堆栈与业务标识，
    不向对话主流程抛出；单条 INSERT（`AiUsageLogWriter`，`@Transactional(rollbackFor = Exception.class)`），
    无循环查询、无批量。
  - 测试：`AdminAiUsageListenerTest`（成功/失败/取消各自落库；token 为 null **不得写成 0**；
    落库失败不影响主流程且带堆栈留痕；会话补齐/同线程上下文/未知租户三种解析路径；超长原因按列宽截断）。
- **starter 依赖版本升至 `3.4.0-SNAPSHOT`**（`pom.xml`）。新契约（可空 token + `AiUsageOutcome`）
  只存在于 starter 未发布的 3.4.0 线（3.3.0 的 `ypbin-starter-ai` 只有 `AiUsageInfo`/`AiUsageListener`
  两个类）。⚠️ **合并前须等 starter 3.4.0 正式发布**：本仓 CI 有「starter 版本必须等于最新 GitHub Release」
  校验，SNAPSHOT 会直接失败；发布后由 `sync-starter-version` 工作流改写为发布版号。
  本地开发需先 `mvn -Dmaven.test.skip=true install` 安装 starter 3.4.0-SNAPSHOT。
- **权限数据源（starter 端口 `PermissionProvider`）三处宿主实现，注解鉴权具备开启条件**
  （`ypbin-system` / `ypbin-ai` / `ypbin-auth`）。此前宿主均未实现该端口，框架装配的是「返回空列表」的
  （`ypbin-system` / `ypbin-ai` / `ypbin-auth`）。此前宿主均未实现该端口，框架装配的是「返回空列表」的
  默认实现，156 处 `@SaCheckPermission`（29 个 Controller、92 个去重权限码）即便拦截器打开也恒不通过。
  - `SystemPermissionProvider`：复用本地 `SysPermissionService`（权限码唯一来源 `sys_role_menu` →
    `sys_menu.auth_code`），**原样透传**平台超管的 `*:*:*` 短路结果，不做任何过滤/截断
    ——这是「超管不掉权限」的唯一保障，已由 `SysPermissionServiceImplTest` 与
    `SystemPermissionProviderTest` 双向锁定。
  - `AiPermissionProvider` / `AuthPermissionProvider`：本服务无该库表，经 `SysCache`
    （`sys:perm:user:<id>` 永久缓存，未命中回源 Feign）复用 system 的唯一一份判定，避免每请求一次 RPC。
    **fail-closed**：system 不可达时 `SysCache` 抛业务异常、Provider 不捕获 ⇒ 请求以
    `R.code=409`「系统服务暂不可用」失败——既不放行，也不把依赖故障伪装成「无权限 403」；
    响应成功但数据缺失、loginId 缺失/非法时一律返回空集合（拒绝）。Feign 超时沿用共享配置的
    显式钉死值（`deploy/nacos/ypbin-common.yaml:35-41`：连接 2000ms / 读取 5000ms）。

### 修复

- **用户名查重移出数据范围，跨部门重名改为友好业务错误**（`SysUserServiceImpl` / `UserAccountSupport` /
  `SysUserMapper`）。`uk_username` 是**不带 `tenant_id` 的全局唯一键**，而 `updateUser` 带
  `@DataPermission`（数据范围按部门过滤），原先的 `exists()` 查重落在该范围内 ⇒ **跨部门重名查不到** ⇒
  校验通过后由数据库唯一键抛原始 SQL 错误。现改走语句级跳过数据权限的
  `SysUserMapper#countByUsernameGlobal`（`@InterceptorIgnore(dataPermission = "true")`，已核实
  MyBatis-Plus 3.5.17 `DataPermissionInterceptor#beforeQuery/beforePrepare` 首行即判该标记）
  + `TenantContext.executeIgnore`（关租户过滤），两道过滤缺一不可：
  `TenantContext` 关不掉数据权限（`DataPermissionContext` 只有进入/退出、无挂起语义，
  外层已激活时内层 `@DataPermission(ignore = true)` 并不生效），故必须用语句级注解。
  测试：`UserAccountSupportTest`（同租户内跨部门重名同样报「用户名已存在」、执行时断言租户过滤已关且退出后复位、
  编辑排除自身）、`SysUserMapperInterceptorIgnoreTest`（走真实 Mapper 解析路径断言 MP 判据为真，
  并以未标注语句/不存在语句反向证明判据非永真）。
- **角色授权变更时的权限缓存清理由「逐用户一次缓存往返」改为批量一次删除**
  （`ypbin-system-api`：`SysCache.evictUserAuth(Collection<Long>)`）。用户权限缓存是永久缓存
  （TTL 传 `null`），一致性完全依赖写路径主动失效；受影响用户多时原实现按用户逐个 `DEL`，
  放大为 N 次缓存往返。现合并键后一次删除，空集合短路、`null` 元素跳过；
  `SysRoleServiceImpl`、`SysMenuServiceImpl` 的失效入口改用批量语义（仍为**精确失效**，
  只清受影响用户的 `sys:role:user:*` / `sys:perm:user:*`，不做整体清）。
  新增回归测试：`SysRoleServiceImplPermissionCacheTest`（改角色勾选菜单/改角色状态后必须清掉该角色下
  **全部**去重用户的缓存；角色下无人时不得发起删除）、`SysCacheTest` 的批量与短路用例。

### 文档

- 新增 [`docs/permission-rollout.md`](docs/permission-rollout.md)：`@SaCheckPermission` 灰度开启手册
  ——生效三条件与现状、一键回滚（`ypbin.security.interceptor` 置回 `false` 即整体不装配）、
  开启前两个硬前置（先给租户角色勾菜单；system 的 `interceptor: false` 注释已部分过期，须先在 auth
  验证登录校验链路再动 system）、auth → system → ai 的分步开启表、三个角色的验收清单与回滚演练、
  已知风险（鉴权依赖 system 可用、失效发生在事务提交前的残留竞态、前端 403 只弹 toast 不跳页）。

### 说明（本轮刻意未做的两件事）

- **未改动任何 `ypbin.security.interceptor` 开关**（system/ai 仍为 `false`）⇒ 对现网零行为影响。
- **未给 `/social/bind/{source}`、`/social/unbind/{source}` 加权限注解**。影响面分析建议补注解，但
  核实发现：auth 的 dataId **没有**该开关键 ⇒ 走 starter 默认 `true`，其 `SaInterceptor` 早已装配且
  `preHandle` 会先做方法注解校验（`sa-token-spring-boot-webmvc-v3v4-common:1.46.0` 字节码核实）⇒
  在 auth 上新加注解**立即生效**；而 `system:social:bind` / `system:social:unbind` 在 `sys_menu.auth_code`
  没有对应行、无法在界面授权 ⇒ 直接补注解会让**所有非超管用户部署即无法绑定/解绑第三方账号**，
  与「本 PR 对现网零影响」冲突。安全落地顺序（先补菜单行与授权、再加注解）与「维持仅需登录
  （与 `UserProfileController` 的自作用域既有约定一致）」两条路已写进上述文档，待明确选一条。
