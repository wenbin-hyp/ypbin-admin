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
- **starter 依赖版本升至 `3.4.0`**（`pom.xml`）。新契约（可空 token + `AiUsageOutcome`）
  只存在于 starter 未发布的 3.4.0 线（3.3.0 的 `ypbin-starter-ai` 只有 `AiUsageInfo`/`AiUsageListener`
  两个类）。⚠️ **合并前须等 starter 3.4.0 正式发布**：本仓 CI 有「starter 版本必须等于最新 GitHub Release」
  校验，SNAPSHOT 会直接失败；发布后由 `sync-starter-version` 工作流改写为发布版号。
  本地开发需先 `mvn -Dmaven.test.skip=true install` 安装 starter 3.4.0。
- **数据范围处理器 `AdminDataScopeHandler`（starter 端口 `DataScopeHandler` 的宿主实现）**。
  此前宿主未实现该端口（实现数为 0），9 处 `@DataPermission` 全为空转——查询与写操作的 UPDATE/DELETE
  都不加任何数据范围条件。现按 `sys_role.data_scope` 计算 SQL 片段：平台超管（`PLATFORM` +
  `PLATFORM_SUPER` 角色）不受限；否则取各角色数据范围并集（1 全部⇒不加条件、2 本部门及以下⇒本部门+
  部门树后代、3 本部门、4 仅本人⇒`id = 当前用户`、5 自定义⇒角色绑定部门+其后代），部门条件与本人
  条件以 `OR` 组合；取不到身份头/无有效角色/解析结果为空时**拒绝全部**（`id = -1`）并记日志，
  不放行全量。只治理 `sys_user` 表（其余表无 `dept_id` 列，返回 null 放行）；租户隔离仍由 tenant
  拦截器独立施加，不拼 `tenant_id`；角色-部门关联走批量 IN（判空短路），解析自身的查询用线程内标记防递归。
- **敏感词词库提供者 `DbSensitiveWordProvider`（starter 端口 `SensitiveWordProvider` 的宿主实现）**。
  词库取自系统参数 `sys_config.SENSITIVE_WORDS`（逗号/分号/换行分隔），复用既有参数表与「系统参数」
  维护界面，不新建词表；词库为空时本类自行记 WARN（starter 只在「无 Provider 且配置项为空」时告警，
  宿主提供 Provider 后该告警不再触发，必须由实现把「过滤等同无操作」说出来）；读取失败带堆栈抛错，
  绝不降级为空词库。种子数据已补该参数行（`deploy/sql/002-data.sql`，默认留空待填）。
  **词库在启动期读取一次，改动后需重启生效**（starter 的契约如此，`SensitiveWordService#reload`
  本仓尚未接入）。
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

- **用户名查重移出数据范围，跨部门重名改为友好业务错误**（`SysUserServiceImpl` / `UserAccountSupport` / `SysUserMapper`）：改用**语句级** `@InterceptorIgnore(dataPermission = "true")` 的全局计数语句（`countByUsernameGlobal`），不再受 `@DataPermission` 部门条件影响。

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
- **操作日志 `sys_log.clientId/clientType/authType` 不再恒为空**：新增 `SessionLogClientProvider`
  （`ypbin-common`，由 `RemoteLogAutoConfiguration` 装配，带 `@ConditionalOnMissingBean` 可被宿主覆盖），
  从登录会话中的 `LoginUser` 读这三个值——gateway 身份头只有 id/username/tenantId/deptId/roles，
  **不新增任何请求头契约**，而是复用 auth 登录时写入、三服务共享同一 Redis 会话存储的登录态。
  读会话失败时记 error 且让本条日志照常落库（仅三列为空），不因三个附加字段丢掉整条审计记录。
- **在线用户接口改为统一分页响应**：`GET /online-user/list` 由 `R<List<OnlineUserResp>>` 改为
  `R<PageResult<OnlineUserResp>>`（新增 `OnlineUserQuery extends PageQuery`，`keyword` 语义不变）。
  在线用户来自会话存储而非数据库，属**内存分页**：先枚举全部在线会话再切片，分页只减少传输量，
  不减少会话读取开销（O(在线会话数)）；页码越界时 `items` 为空而 `total` 仍为真实总数；
  非法页码/每页条数显式报错，不做静默纠正。
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

### 待决策与未处理（本轮盘点发现，未动手）

  `@DataPermission` 作用域内执行，会带上部门条件 ⇒ 与「本部门之外已有同名用户」的重名检测不到，
  最终由 `sys_user.uk_username` 抛原始 SQL 错误（是显式失败，不是静默放行，但错误信息不友好）。
  建议把查重移到数据范围之外（独立 Bean，或 `@DataPermission(ignore = true)` 的方法），需先确认再改。
- **`dept_id IS NULL` 的用户对「部门范围」操作者不可见**：`dept_id IN (...)` 不匹配 NULL，
  这是数据范围语义的自然结果（与同类框架一致）。是否要 OR 出 `dept_id IS NULL`（放宽可见性）属产品决策。
- **`exportUsers` 双重注解**：`SysUserServiceImpl.exportUsers` 与 `UserExcelComponent.exportUsers`
  都标了 `@DataPermission`（跨 Bean 调用，切面各生效一次）。嵌套计数正确、无副作用，可择机去掉一处。
- **敏感词词库热更新未接**：改 `sys_config.SENSITIVE_WORDS` 需重启才生效（starter 装配期只取一次词）。
  若要即时生效，应在配置变更事件里调用 `SensitiveWordService#reload`（需处理 `enabled=false` 时 Bean 缺失）。
- **数据范围解析未加缓存**：每次回调 2～4 次查询（超管判定 + 角色 + 按需部门树/角色-部门）。
  缓存的失效点分散（角色、部门、用户角色变更），遗漏即越权，故本轮优先正确性；降本应作为独立改动评估。
- **在线用户分页仍是内存分页**：在线规模达到万级时应改为在会话侧维护可分页索引。
