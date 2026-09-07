# Pichome

本仓库保存当前自托管 Pichome 项目的网页端源码和 Android APK 工程。

## 目录

- `web/`：基于 [FilePress](https://github.com/zyx0814/FilePress) `ea23905` 的网页端源码，并包含本机部署中的 Pichome 定制。
- `android/`：Pichome Android WebView 客户端源码。
- `eagle-sync/`：通过 Tailscale 在 Pichome 与其他电脑的 Eagle 之间同步素材的插件、桥接服务与最新安装包。
- `releases/`：可直接安装的公开构建产物；当前包含不嵌入私人 App Access Tag 的 Android 调试版 APK。

## 当前定制

- 手机网页端左右侧栏与遮罩布局。
- 手机上传弹窗适配，上传到当前选中的分类。
- Eagle 素材库上传、删除和索引同步。
- 文件夹名称点击时展开或收起子文件夹。
- 电脑端素材拖拽移动：左键按住素材卡片（支持多选）拖到左侧分类树节点即可移动进该文件夹，拖到树下方空白处移回根目录（未分类）；拖拽时节点/根目录有高亮提示，悬停节点自动展开子文件夹。
- Android 端提供状态栏适配、双侧边缘手势和素材自适应弹窗；视频逐帧预览中，电脑网页使用约 0.25 秒一段、最高 1440/960px 的平衡档帧图集，手机网页与新版 App 使用约每秒 640/480px 图集及三段内存窗口；分段按整数采样帧对齐，并在松手或手机短按帧按钮后校验同帧再切回原视频清晰画面。
- Android 端隐藏下载入口，并支持服务器签发的可选 App Access Tag。
- Eagle 插件支持手动、自动单向和自动双向同步，包括新增素材、分类移动、软删除、进度显示和两端素材数量统计。

## 安全说明

仓库不包含生产服务器的 `web/config/config.php`、上传素材、缩略图、日志、锁文件、模板缓存、Android 本地 SDK 路径或真实 App Access Tag。

Android 构建前，将 `android/local.properties.example` 复制为 `android/local.properties`，填写本机 Android SDK 路径；只有服务器要求时才填写 `pichome.appAccessTag`。

公开 APK：`releases/Pichome-Android-1.0.25-debug-public.apk`。该构建使用 Android 调试签名且不包含私人 App Access Tag；受保护的服务器入口仍需正常认证。

## 许可证

`web/` 继承上游 FilePress README 中声明的 AGPL V2 许可证及其版权要求。第三方前端库、字体和二进制依赖保留各自原有许可证。
