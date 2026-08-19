# Pichome Android

一个轻量的 Android WebView 客户端，加载现有 Pichome 手机网页：

`https://pichome.lempet.top:5575/`

## 第一版功能

- 独立 APK、应用名称和图标
- 保持网页登录与 Cookie
- 支持网页选择并上传多个文件
- 支持网页视频播放及播放器全屏
- 支持 Android 返回键网页后退
- APK 专属请求自动通过 Caddy 入口认证，不显示账号密码框
- 为手机状态栏、导航栏和屏幕开孔预留系统安全区域，上下系统栏统一使用 `#303135` 深灰背景
- 左侧分类树使用 Element Plus 原生节点点击切换：点击文件夹名称展开直接子文件夹，再次点击收起
- APK 隐藏左右侧栏箭头按钮，支持边缘滑入打开、在侧栏内反向滑动收回，并让侧栏实时跟随手指
- APK 保留顶部时间/电量状态栏，将底部导航栏设为可临时唤出的沉浸式状态，并向系统申请左右整条物理屏幕边缘作为侧栏手势区
- 图片和视频详情使用贴满手机屏幕左右边缘、按素材比例计算高度的居中弹窗，点击外部关闭
- 视频详情打开后自动播放；屏幕底部提供 144×96px 的后退一帧、前进一帧按钮，只控制当前中央视频；短按走一帧，长按以 1× 速度连续前进或反向预览；逐帧状态无闪烁地隐藏播放器中央符号、进度、时间、音量及倍速控件
- APK 双击素材时只把被点击卡片的 `dpath` 传入详情页并将索引固定为 0，不再预载相邻素材；自动播放仅作用于中央当前视频
- APK 内图片和视频详情页按媒体比例及剩余屏幕高度自适应显示
- 外部域名交给系统浏览器打开
- 不申请存储权限；APK 内隐藏下载按钮并拦截下载请求

## 构建

```bash
cd /home/wangjiafeng/Pichome
./build-apk.sh
```

调试 APK 输出位置：

`app/build/outputs/apk/debug/app-debug.apk`

## 安装

连接开启 USB 调试的 Android 手机后：

```bash
/home/wangjiafeng/构建apk依赖包/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

当前是调试签名版本，适合直接安装测试。正式长期使用时可以再创建独立签名的 release APK。
