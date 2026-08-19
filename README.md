# Pichome

本仓库保存当前自托管 Pichome 项目的网页端源码和 Android APK 工程。

## 目录

- `web/`：基于 [FilePress](https://github.com/zyx0814/FilePress) `ea23905` 的网页端源码，并包含本机部署中的 Pichome 定制。
- `android/`：Pichome Android WebView 客户端源码。

## 当前定制

- 手机网页端左右侧栏与遮罩布局。
- 手机上传弹窗适配，上传到当前选中的分类。
- Eagle 素材库上传、删除和索引同步。
- 文件夹名称点击时展开或收起子文件夹。
- Android 端状态栏适配、双侧边缘手势、素材自适应弹窗及上下滑切换。
- Android 端隐藏下载入口，并支持服务器签发的可选 App Access Tag。

## 安全说明

仓库不包含生产服务器的 `web/config/config.php`、上传素材、缩略图、日志、锁文件、模板缓存、Android 本地 SDK 路径或真实 App Access Tag。

Android 构建前，将 `android/local.properties.example` 复制为 `android/local.properties`，填写本机 Android SDK 路径；只有服务器要求时才填写 `pichome.appAccessTag`。

## 许可证

`web/` 继承上游 FilePress README 中声明的 AGPL V2 许可证及其版权要求。第三方前端库、字体和二进制依赖保留各自原有许可证。

