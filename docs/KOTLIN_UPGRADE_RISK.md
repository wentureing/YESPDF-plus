# Kotlin 升级风险评估

> 结论先行：**在不动 Gradle/AGP 的前提下，最高只能升到 Kotlin 1.5.x**；
> 想上 1.7 必须连锁升级 Gradle + AGP；想上 1.8+ 必须放弃 `kotlin-android-extensions`（合成视图）并全量迁移 ViewBinding。

## 1. 当前工具链

| 组件 | 版本 | 备注 |
|---|---|---|
| Kotlin | **1.3.61** | 使用 `kotlin-android-extensions` 合成视图 |
| Gradle | 5.4.1 | wrapper 固定 |
| AGP | 3.4.1 | 只能用 JDK 8 |
| JDK（CI） | 8 | AGP 3.4.1 硬限制 |
| 合成视图使用面 | **24 个文件 / 34 条 import** | 对应约 300+ 属性引用 |
| Parcelize | 未使用 | 降低升级障碍 |

## 2. 关键约束：三个版本的硬门槛

### 门槛一：Kotlin Gradle Plugin 与 Gradle 版本

| Kotlin | 最低 Gradle | 与当前 5.4.1 |
|---|---|---|
| 1.4.x | 5.4.1 | ✅ 可用 |
| **1.5.x** | 5.4.1 | ✅ **可用（可升的最高档）** |
| 1.6.x | 6.1+ | ❌ 需先升 Gradle |
| 1.7.x | 6.7.1+ | ❌ 需先升 Gradle |
| 1.8.x | 6.8+ | ❌ 且移除 android-extensions |

### 门槛二：`kotlin-android-extensions` 生命周期

| Kotlin | 合成视图状态 |
|---|---|
| ≤1.4.10 | 正常 |
| 1.4.20 起 | **deprecated**（编译警告） |
| **1.8.0 起** | **彻底移除** → `The 'kotlin-android-extensions' plugin is no longer supported` |

### 门槛三：AGP 与合成视图

- AGP **7.0 起**移除了 `androidExtensions` 扩展 → 即使 Kotlin < 1.8，合成视图也无法使用
- 因此"保留合成视图"的天花板是：**AGP ≤ 4.2 + Kotlin ≤ 1.7.x**

## 3. 三套方案对比

### 方案 A：Kotlin 1.3.61 → **1.5.31**（推荐，本轮可做）

**改动**
```gradle
// build.gradle
ext.kotlin_version = '1.5.31'
```
```gradle
// app/build.gradle 与 android-pdf-viewer/build.gradle
kotlinOptions {
    jvmTarget = '1.8'   // Kotlin 1.5 起强制与 targetCompatibility 一致
}
```

**收益**
- 可用 `lowercase()/uppercase()`、`buildList/buildMap`、`sumOf`、`maxOrNull` 等 1.4/1.5 API
- 更好的类型推断与更少的前端 bug；为后续迁移铺路

**风险（均可控）**
| 风险 | 说明 | 缓解 |
|---|---|---|
| jvmTarget 校验 | Kotlin 1.5 强制 `jvmTarget` 与 `compileOptions` 一致，否则编译失败 | 显式声明 1.8 |
| JVM IR 后端 | 1.5 起默认启用 IR 后端，可能暴露旧代码的边界问题 | 出现问题时 `kotlinOptions.useIR = false` 临时回退 |
| stdlib 版本上浮 | base 库传递 kotlin-stdlib 1.3.31，Gradle 会取 1.5.31 | 无需处理 |
| android-extensions | 1.5 仍支持，只是 deprecated 警告 | 无 |
| 构建缓存失效 | KGP 版本变化 → Gradle 缓存不命中，首次构建慢 | 一次性成本 |

**结论**：改动 3 行、变量最少，一次 CI 即可验证。**建议先做这个。**

### 方案 B：Gradle 6.7.1 + AGP 4.2.2 + Kotlin 1.7.22（中风险）

**收益**：可用 Kotlin 1.6/1.7 语言特性；AGP 4.x 更好的构建性能
**风险**

| 风险 | 说明 |
|---|---|
| 连锁升级 | Gradle 5.4.1→6.7.1、AGP 3.4.1→4.2.2、JDK 8（AGP 4.2 支持） |
| ButterKnife 10.1.0 | 在 AGP 4.x 上有 `R2` 生成失败案例，通常需升到 10.2.3 |
| GreenDAO 插件 3.2.2 | 与 AGP 4.2 兼容性未验证，社区建议 3.3.0 |
| 老 aar 兼容 | `com.aaron:base`、parallaxbacklayout 等 2019 年构件与 AGP 4.2 的 resource 处理差异 |
| 回归面 | variant API、manifest merger、资源合并行为都有变化，需 1–2 轮 CI 调试 |
| 合成视图 | 仍可用，但 `kotlin-android-extensions` 已 deprecated |

**结论**：收益有限（主要是语言特性），风险明显放大。**不建议为 API 便利而做**，除非有别的必须理由（如需要 AGP 4.x 的新特性）。

### 方案 C：Kotlin 1.8+/1.9 + ViewBinding（高风险，大工程）

**必须做的事**
1. 移除 `kotlin-android-extensions` 插件
2. 打开 `buildFeatures { viewBinding true }`（需 AGP 4.1+）
3. **24 个文件、约 300+ 处**合成属性引用改为 `binding.xxx` / `findViewById`
4. Gradle 升到 7.x、AGP 升到 7.x、JDK 升到 11/17
5. Manifest 移除 `package` 属性、补齐 `namespace`（AGP 8 强制）

**风险**：改面最大、最容易引入空指针与生命周期问题（合成视图在 Fragment 中有 view 生命周期陷阱，ViewBinding 反而更安全，但迁移过程需要逐个验证），且 CI 只能验证编译、无法验证 UI 正确性。

**结论**：应作为独立里程碑，不要和其他改动混在一起。

## 4. 推荐路线

```
现在 ── 方案 A（Kotlin 1.5.31，改动 3 行）──► CI 绿
        │
        ├─（可选）观察一两个版本，确认无回归
        │
        └─（按需）再评估 方案 B / C，各自独立分支与里程碑
```

## 5. 如果不升级怎么办

用 1.3 的等价写法即可，成本几乎为零：

| 1.5+ API | 1.3 等价写法 |
|---|---|
| `s.lowercase()` | `s.toLowerCase()` |
| `list.buildList { }` | `mutableListOf<...>().apply { }` |
| `xs.sumOf { it.n }` | `xs.map { it.n }.sum()` |
| `xs.maxOrNull()` | `xs.max()`（空集合返回 null） |
| `s.uppercase()` | `s.toUpperCase()` |

本次 CI 失败（`lowercase`）就是这类问题，已用 `toLowerCase()` 修复。
