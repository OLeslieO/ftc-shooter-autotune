# 创建专用仓库并发布 Maven

## 仓库和安装地址

| 用途 | 地址或坐标 |
| --- | --- |
| 独立源码仓库 | `https://github.com/OLeslieO/ftc-shooter-autotune` |
| Maven 仓库 | `https://oleslieo.github.io/ftc-shooter-autotune/maven/` |
| FTC AAR | `io.github.oleslieo:shooter-autotune:0.1.0` |
| 自动传递的核心 JAR | `io.github.oleslieo:shooter-autotune-core:0.1.0` |

**0.1.0 已通过 `Publish Maven` workflow 发布，以上地址均可公开下载。** 机器人工程只作为使用方；不要把本目录的 workflow 放到机器人仓库根目录执行发布。

一个新的 GitHub 仓库即可同时托管源码与 Maven：`main` 存源码，发布流程管理 `maven` 分支保存全部历史制品，GitHub Pages 提供无需登录的 HTTPS 下载。不会使用已有机器人仓库的 Pages 或其他 Maven 服务。这里没有向 Maven Central 上传，也不要求消费者设置 GitHub token。

## 1. 导出独立本地仓库

在现有机器人工程根目录运行（需要 Python 3 和 Git）：

```bash
python3 external/ftc-shooter-pidf-tuner/scripts/prepare-standalone.py build/standalone/ftc-shooter-autotune
```

Windows 若没有 `python3`，使用 `py -3`。脚本复制库源码、示例、Gradle wrapper、说明和独立 CI，初始化新的 `.git`。它不复制 TeamCode、机器人历史、构建缓存或本机 SDK 路径，也不提交、不推送。目标已存在会拒绝覆盖；若已有本次生成的目录，直接使用即可。

`build/standalone` 只是导出位置，可能被机器人项目 clean 删除；长期开发请先移出机器人工程。本仓库即由此步骤导出，已完成创建和首次发布，以下第 2、3 步仅供换 owner 或重建仓库时参考。

## 2. 创建新的 GitHub 仓库

在 GitHub 登录 `OLeslieO`，创建 **Public** 仓库 **`ftc-shooter-autotune`**，不要自动初始化 README、license 或 gitignore。随后在独立目录中运行：

```bash
git add .
git commit -m "Initial standalone shooter autotune library"
git branch -M main
git remote add origin https://github.com/OLeslieO/ftc-shooter-autotune.git
git push -u origin main
```

若已经安装并登录 GitHub CLI，可以在独立目录完成初次提交后用以下命令替代网页创建和 remote/push：

```bash
gh repo create OLeslieO/ftc-shooter-autotune --public --source=. --remote=origin --push
```

这些命令只在独立仓库执行。

如果改用其他 GitHub owner 或仓库名，同步修改 `gradle/publishing.gradle` 的 group/SCM/URL、`scripts/verify-publication.py` 和发布 workflow 中的 group 路径，以及 README 的安装地址/坐标。

## 3. 开启专用仓库的 Pages

在**新仓库** `Settings → Pages → Build and deployment → Source` 选择 **GitHub Actions**。

工作流使用 GitHub 自带的 `GITHUB_TOKEN`，无需创建 PAT 或填写 Maven 密码。保存制品的 job 需要 `contents: write`，Pages 部署 job 使用 `pages: write` 和 `id-token: write`。如果组织策略禁止这些权限，需要仓库管理员开放相应 workflow 权限。

确认 `github-pages` environment 的 Deployment branches/tags 规则允许 `v*` 标签部署；若默认规则只允许默认分支，在该环境设置中添加允许 `v*` 的 tag 规则。仓库必须允许 Actions 执行。

## 4. 构建检查和首次发布

本机构建需要 JDK 17、Android SDK platform 30、build-tools 35.0.0。设置 `ANDROID_HOME` 或只在本机创建 `local.properties`；不要提交本机路径。CI 自动安装构建依赖。

```bash
./gradlew :tuner-core:check :ftc-library:assembleRelease
./gradlew :tuner-core:publish :ftc-library:publish
python3 scripts/verify-publication.py build/maven 0.1.0
```

Windows 使用 `gradlew.bat` 和 `py -3`。`publish` 只生成本地 Maven 布局，便于发布前检查。

确认 GitHub `Check library` 工作流通过，`gradle.properties` 为 `version=0.1.0`，然后在独立仓库打标签并推送：

```bash
git tag v0.1.0
git push origin v0.1.0
```

`Publish Maven` 会检查标签与源码版本一致，运行模拟测试、生成 AAR/JAR/sources/POM/Gradle metadata，检查 AAR 中的网页及入口类，保存历史制品并部署 Pages。首次部署可能需要几分钟。最后一步会检查两个公开 POM 的 HTTP 可达性。

发布 workflow 仅通过 tag 或手动指定已有 tag 启动；普通 main 提交只检查，不发布。所有发布串行执行，避免历史制品丢失。已经发布的版本绑定源码 commit，不能用同一版本发布不同源码；同一 commit 的重试会复用原制品。

## 5. 确认能远程安装

打开或下载：

```text
https://oleslieo.github.io/ftc-shooter-autotune/maven/io/github/oleslieo/shooter-autotune/0.1.0/shooter-autotune-0.1.0.pom
https://oleslieo.github.io/ftc-shooter-autotune/maven/io/github/oleslieo/shooter-autotune/0.1.0/shooter-autotune-0.1.0.aar
https://oleslieo.github.io/ftc-shooter-autotune/maven/io/github/oleslieo/shooter-autotune-core/0.1.0/shooter-autotune-core-0.1.0.jar
```

Maven 根目录不需要有首页，不能仅凭根 URL 404 判断失败。确认具体 POM/AAR/JAR 可访问后，按 README 的远程安装方式在 FTC 工程执行 Gradle Sync 与 `:TeamCode:assembleDebug`。这一步验证实际消费者解析和 APK 打包；工作流的制品检查不能代替机器人构建与实机验证。

首次确认后，再把机器人工程从本地 `implementation project(...)` 改为远程坐标，并删除两个本地 include。不要同时使用两种依赖。

## 后续版本与故障排查

- 将 `gradle.properties` 升级为如 `0.1.1`，提交并推送，然后推送对应 `v0.1.1` tag；消费者自行升级坐标版本。不要改写已发布标签。
- Pages 部署失败时修正仓库 Pages/environment 设置，再在 Actions 中重跑失败 job；也可手动运行 `Publish Maven`，输入相同 tag。相同 commit 会复用已有制品，不覆盖版本。
- 不要删除 `maven` 分支，它保存老版本；工作流从它恢复完整 Maven 目录后再增加新版本。
- 若 Gradle 曾缓存 404，确认发布成功后在消费者运行 `./gradlew --refresh-dependencies :TeamCode:assembleDebug`。
- `Could not find shooter-autotune-core` 通常表示只上传了 AAR、遗漏核心库或 POM 坐标错误；始终通过本工作流发布两个模块。
- 若 workflow 验证未通过，不要只手动上传 AAR 绕过检查。查看具体 POM、module、checksum 或 assets 报错。

每次发布都以独立仓库 CI、公开 POM/AAR/JAR 下载和消费者工程的 Gradle Sync 结果为准；workflow 通过不代表实机验证完成。
