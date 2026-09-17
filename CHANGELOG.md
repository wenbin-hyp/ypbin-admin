# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布]

### 新增

- **权限数据源（starter 端口 `PermissionProvider`）三处宿主实现，注解鉴权具备开启条件**
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
