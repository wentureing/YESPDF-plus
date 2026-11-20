# YESPDF-plus

A PDF reader for Android —— 基于 [YESPDF](https://github.com/aaronzzx/YESPDF)（Apache-2.0）的强化分支。

## 下载最新版本

- **推荐**：<https://github.com/wentureing/YESPDF-plus/releases/latest>
- 镜像：`release` 分支根目录（CI 每次构建覆盖，只保留最新一份 APK）

两者都由 GitHub Actions 在每次构建成功后自动更新，**永远只保留最新一个版本**。

## 相对上游的增强

- **夜间显示拆分**：反色（黑白文档）与蒙版（彩色文档）二选一；单击切换「关 ↔ 上次模式」，长按选择模式
- **加密 PDF**：密码以 Android Keystore AES-256-GCM 加密后保存在本机，二次打开静默通过，可随时失效自愈
- **自动扫描规则**：可自定义「默认扫描目录」与「排除的目录 / 文件通配」，可编辑、勾选后生效
- **浏览全部**：同一份排除集合可一键应用到文件浏览器
- **应用数据管理**：三点菜单 →「管理」，可见私有目录中的真实文件（数据库 / 封面 / 设置 / 缓存），
  条目带中文用途注释（封面按书名匹配）、支持查看 / 重命名 / 复制路径 / 多选复制粘贴 / 导出 / 删除，
  数据库删除为高危流程（确认后立即退出，下次启动重建空库）

## 签名与校验

- 自 `v2.3.13` 起使用**固定正式密钥**签名（此前版本为 debug 签名，两者不能互相覆盖）：
  从旧版本升级需先卸载旧包，否则会提示"应用未安装"。
- 证书 SHA-256 指纹（可用于核对下载的 APK 是否出自本项目）：

```
DC:D4:C8:09:D5:9C:31:43:63:79:23:02:97:29:85:32:EA:CE:CD:3C:5F:29:6F:34:DF:A3:AD:F1:A6:7A:85:66
```

- 每个 Release 的说明里同时给出该次构建的**证书指纹**与 **APK SHA-256**。
- 校验方式（需 Android SDK build-tools）：

```bash
apksigner verify --print-certs YESPDF-plus-*.apk
sha256sum YESPDF-plus-*.apk
```

## 工程约定

- 构建：Gradle 5.4.1 + AGP 3.4.1 + JDK 8（CI 中先用 JDK 17 安装 SDK 包再切回 8）
- 依赖：jcenter 已停服，仓库顺序为 google → 阿里云 jcenter 镜像 → Maven Central → JitPack
- 详见 `AGENTS.md`（协作与发布口径）与 `docs/PLAN.md`（里程碑）、`docs/FILE_MANAGER_DESIGN.md`（数据管理设计）

## 许可

沿用上游 Apache License 2.0，见 `LICENSE`。
