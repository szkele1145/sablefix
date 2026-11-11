# Sable Fix Core 项目交接文档

> **版本**：v1.6.5 (最终修复版)  
> **更新时间**：2026-11-11  
> **维护者**：sablefixcore 团队  
> **项目路径**：`F:\vibe coding工程\sfx\proj`  
> **Git 仓库**：https://github.com/szkele1145/sablefix.git

---

## 📋 项目概览

| 项目 | 详情 |
|------|------|
| **模组 ID** | `sablefixcore` (原 `sablefix`) |
| **模组名称** | Sable Fix Core |
| **当前版本** | 1.6.5 |
| **Minecraft 版本** | 1.21.1 |
| **加载器** | NeoForge 21.1.x |
| **Java 版本** | 21 |
| **构建工具** | Gradle 8.11 (Wrapper) |
| **许可证** | MIT |

---

## 🔑 关键 Token 与凭证

### GitHub
- **仓库**：https://github.com/szkele1145/sablefix.git
- **Token**：[已撤销/轮换，请在 GitHub Settings 生成新 PAT]
- **Release 页面**：https://github.com/szkele1145/sablefix/releases/tag/v1.6.6

### Modrinth
- **项目 ID**：`rPkYnI7j`
- **项目 Slug**：`sablefixcore`
- **Token**：[已撤销/轮换，请在 Modrinth Settings 生成新 Token]
- **项目页面**：https://modrinth.com/project/sablefixcore

### Gradle/JDK 配置
- **Gradle Wrapper**：8.11 (已包含在项目中)
- **JDK 路径**：`C:/java/jdk-21` (Temurin 21.0.13)
- **Gradle 属性**：`org.gradle.java.installations.paths=C:/java/jdk-21`
- **自动检测/下载**：已禁用 (`auto-detect=false`, `auto-download=false`)

---

## 📦 版本与发布信息

### Git 标签历史
```
v1.0.0-hotfix
v1.0.0-hotfix2
v1.0.0
v1.1.0
v1.2.0
v1.3.0
v1.3.1
v1.4.0
v1.4.1
v1.5.0
v1.5.1
v1.5.2
v1.5.3
v1.5.4
v1.5.5
v1.6.0
v1.6.1
v1.6.2
v1.6.3  ← 发布目标版本（已发布，禁止发布 1.5.4）
v1.6.4
v1.6.5  ← 含 `mods.toml` 反引号修复，但 `modLoader` 仍为 `neoforge`（错误）
v1.6.6  ← 当前最新版本：修正 `modLoader = "javafml"` / `loaderVersion = "[4,)"`，移除 neoforge 依赖声明
```

### 当前发布状态 (v1.6.6)

| 平台 | 版本 ID | 文件名 | SHA1 | 状态 |
|------|---------|--------|------|------|
| **GitHub Release** | 待创建 | `sablefixcore-1.6.6-1.21.1-neoforge.jar` | `93972B23288781116E742F83F1A918D453A91415` | ⏳ 待上传 |
| **Modrinth** | 待创建 | `sablefixcore-1.6.6-1.21.1-neoforge.jar` | `93972B23288781116E742F83F1A918D453A91415` | ⏳ 待上传 |

---

## 🛠️ 核心修复记录

### 1. 模组重命名：`sablefix` → `sablefixcore`
- **提交**：`3df2503` - "refactor: rename mod to sablefixcore"
- **变更范围**：
  - Java 包名：`dev.sfx.sablefix` → `dev.sfx.sablefixcore`
  - 模组 ID：`sablefix` → `sablefixcore`
  - 资源路径：`assets/sablefix/` → `assets/sablefixcore/`
  - 命令前缀：`/sablefix` → `/sablefixcore`
  - 日志/备份路径：`logs/sablefix/` → `logs/sablefixcore/`, `backups/sablefix/` → `backups/sablefixcore/`
  - 语言键：`sablefix.*` → `sablefixcore.*`
  - JAR 命名：`sablefix-...` → `sablefixcore-...`

### 2. `mods.toml` 修复 (v1.6.5)
- **提交**：`cf10891` + `36b83dd`
- **问题**：`modLoader = "javafml"` + `loaderVersion = "[4,)"` + description 中的反引号导致 `Invalid escapement` 崩溃
- **修复**：
  - `modLoader = "neoforge"` （当时误判）
  - `loaderVersion = "[21.1,)"`
  - 移除 description 中所有反引号 (`` ` ``)

### 3. `modLoader` 修正 (v1.6.6)
- **提交**：`99230d8` (本次)
- **问题**：`modLoader = "neoforge"` 导致 `Missing language provider neoforge`，NeoForge 21.1.x 无法加载模组
- **修复**：
  - `modLoader = "javafml"` (正确值)
  - `loaderVersion = "[4,)"` (FML 4.x)
  - 移除 `[[dependencies.sablefixcore]]` 中 `modId = "neoforge"` 段（loader 不作为普通 mod 依赖）

### 3. 根命令修复
- **提交**：`763c517`
- **问题**：命令注册仍为 `sablefix` 而非 `sablefixcore`
- **修复**：`Commands.literal("sablefixcore")`

### 4. 语言键修复
- **提交**：`0176851`
- **问题**：语言文件中遗留 `SableFixCorecore` 前缀
- **修复**：`SableFixCorecore.*` → `sablefixcore.*`

### 5. Gradle Wrapper & JDK 配置
- **提交**：`2e0573c`
- **Wrapper 版本**：8.11 (含 `gradlew`, `gradlew.bat`, `gradle/wrapper/`)
- **网络超时**：60s (`networkTimeout=60000`)
- **JDK 路径**：`C:/java/jdk-21` (显式指定，避免中文用户名路径编码问题)

### 5. 构建产物命名
- **JAR 命名**：`sablefixcore-${version}-${minecraft_version}-neoforge.jar`
- **当前产物 (v1.6.6)**：`sablefixcore-1.6.6-1.21.1-neoforge.jar` (SHA1: `93972B23288781116E742F83F1A918D453A91415`)

---

## 📁 关键文件路径

```
F:\vibe coding工程\sfx\proj\
├── build.gradle                          # Gradle 构建脚本
├── gradle.properties                     # Gradle 属性 (含 JDK 路径、代理)
├── settings.gradle                       # Gradle 设置
├── gradlew / gradlew.bat                 # Gradle Wrapper
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── src/
│   └── main/
│       ├── java/dev/sfx/sablefixcore/    # 主代码包
│       │   ├── SableFixMod.java          # 主入口类
│       │   ├── command/SfxCommands.java  # 命令注册
│       │   ├── client/SfxPanelScreen.java # GUI 面板 (含 resetFrameHits)
│       │   ├── net/                      # 网络层
│       │   ├── scan/                     # 扫描/修复逻辑
│       │   ├── watchdog/                 # 看门狗/保存观察者
│       │   └── log/                      # 日志系统
│       └── resources/
│           ├── META-INF/neoforge.mods.toml  # 模组元数据 (关键修复点)
│           └── assets/sablefixcore/
│               └── lang/
│                   ├── zh_cn.json
│                   └── en_us.json
└── build/libs/
    └── sablefixcore-1.6.6-1.21.1-neoforge.jar
```

---

## 🎮 核心功能清单

### 命令系统 (`/sablefixcore`)
| 命令 | 说明 | 权限 |
|------|------|------|
| `/sablefixcore help` | 帮助菜单 | OP (2) |
| `/sablefixcore check` | 全量体检 (子层级+票据) | OP |
| `/sablefixcore list` | 问题文件列表 | OP |
| `/sablefixcore tickets` | 票据明细 | OP |
| `/sablefixcore dump` | 抓取全线程栈 | OP |
| `/sablefixcore diag <x y z>` | 区块诊断 | OP |
| `/sablefixcore report` | 生成诊断报告 | OP |
| `/sablefixcore fix` | 一键修复 (屏蔽写回→修复→踢人重启) | OP |
| `/sablefixcore unmask` | 兜底解锁只读文件 | OP |
| `/sablefixcore repair <file> dry|apply` | 单文件修复 | OP |
| `/sablefixcore repair-all dry|apply` | 批量修复 | OP |
| `/sablefixcore tickets-purge dry|apply` | 清空票据 | OP |
| `/sablefixcore backup list|del|purge` | 备份管理 | OP |

### 客户端 GUI (`/sfxpanel`)
- 7 个标签页：总览、存档体检、子层级浏览器、区块诊断、死锁监视、运行日志、关于
- **resetFrameHits 三条硬规则** (SfxPanelScreen.java:574-582)：
  1. 清除帧级命中区/extent，不碰用户滚动偏移
  2. `render()` 帧首统一清零 (`render()` 第 595 行调用)
  3. `tgBtn*` / `purgeBtnX` / 所有滚动 extent 全清零

### 被动功能
- 启动时全量扫描 `.slvlr/.slvls` 指针 + `force-load` 票据
- 死锁监视：主线程 20 秒无响应自动抓栈 (原版 60 秒杀服前抢现场)
- 保存观察者：每 30s 轮询 chunk 保存 future，跨过一轮仍卡住才登记
- 损伤登记簿：持久化跨重启，自动清理已恢复文件

---

## 🌐 网络协议

| 字段 | 值 |
|------|-----|
| **协议版本** | 10 |
| **通道** | `sablefixcore:panel_req`, `sablefixcore:panel_data`, `sablefixcore:diag_req`, `sablefixcore:diag_res`, `sablefixcore:repair_req`, `sablefixcore:repair_res`, `sablefixcore:purge_req`, `sablefixcore:purge_res` |
| **序列化** | `FriendlyByteBuf` + `StreamCodec` |
| **截断保护** | 写侧 UTF-8 字节截断 (`utf8Trunc`)，防帧超限断连 |

---

## 📦 构建与发布流程

### 本地构建
```bash
# 清理并构建
./gradlew clean jar --no-daemon

# 仅编译验证
./gradlew compileJava --no-daemon
```

### 发布步骤

#### 1. 构建 JAR
```bash
./gradlew clean jar --no-daemon
# 产物：build/libs/sablefixcore-1.6.5-1.21.1-neoforge.jar
```

#### 2. GitHub Release 上传
```bash
# 使用 GitHub API 上传
# Release ID: 430453986 (v1.6.5)
# 上传文件：build/libs/sablefixcore-1.6.5-1.21.1-neoforge.jar
```

#### 3. Modrinth 上传
```bash
# 创建新版本并上传文件 (multipart/form-data)
# endpoint: https://api.modrinth.com/v2/version
# project_id: rPkYnI7j
# file_parts: ["sablefixcore-1.6.5-1.21.1-neoforge.jar"]
```

#### 4. Git 提交与推送
```bash
git add -A
git commit -m "chore: release v1.6.5"
git tag v1.6.5
git push origin main --tags
```

---

## 🔍 常用验证命令

```bash
# 检查 Git 状态
git status && git log -1 --oneline

# 验证本地 JAR SHA1
Get-FileHash build/libs/sablefixcore-1.6.5-1.21.1-neoforge.jar -Algorithm SHA1

# 验证 Modrinth 版本
curl -H "Authorization: <YOUR_MODRINTH_TOKEN>" \
  https://api.modrinth.com/v2/version/<VERSION_ID>

# 验证 GitHub Release
curl -H "Authorization: token <YOUR_GITHUB_PAT>" \
  https://api.github.com/repos/szkele1145/sablefix/releases/tags/v1.6.6

# 本地编译验证
./gradlew compileJava --no-daemon
```

---

## ⚠️ 已知问题与注意事项

1. **Modrinth API 限制**：创建版本需使用 `multipart/form-data` 上传，不能用 JSON 直接 POST
2. **Gradle Wrapper 下载**：国内网络需配置代理或增加超时 (`networkTimeout=60000`)
3. **JDK 路径硬编码**：`C:/java/jdk-21` 需在新环境中手动调整
4. **中文用户名路径问题**：Gradle 自动检测会读取 `C:\Users\一只屑\...` 导致编码问题，已通过显式指定 `C:/java/jdk-21` 规避
5. **TOML 反引号陷阱**：`mods.toml` 的多行字符串中不能出现未转义的反引号 `` ` ``，会导致 `Invalid escapement` 解析错误
6. **NeoForge modLoader 陷阱**：`modLoader` 必须为 `"javafml"` (FML 语言提供者)，不能写 `"neoforge"`；`loaderVersion` 对应 FML 版本 `[4,)`，而非 NeoForge 版本

---

## 📞 联系与支持

- **GitHub Issues**：https://github.com/szkele1145/sablefix/issues
- **Telegram**：@maodie_mods
- **QQ 群**：1129543042

---

## 📝 更新日志摘要 (v1.6.6)

> **v1.6.6** - 修正 `modLoader` 为 `javafml`，修复 NeoForge 21.1.x 无法加载模组
> - `modLoader = "javafml"` / `loaderVersion = "[4,)"` (原 `neoforge` / `[21.1,)`)
> - 移除 `dependencies.sablefixcore.neoforge` 声明（loader 不作为普通 mod 依赖）
> - 同步更新 GitHub Release / Modrinth 双平台
>
> **v1.6.5** - 修复 `mods.toml` 反引号导致的 `Invalid escapement` 崩溃
> - 移除 `neoforge.mods.toml` description 中的所有反引号
> - 修复 `Invalid escapement: `` ` `` 解析错误
> - 同步更新 GitHub Release / Modrinth 双平台
> - 清理 Modrinth 重复版本 (KqY7jzZX, BjzITCgY, LLzvfrbL)

---

> **文档版本**：1.0  
> **最后更新**：2026-11-11  
> **维护者**：sablefixcore 团队  
> **下一步计划**：上传 Release 到 GitHub / Modrinth