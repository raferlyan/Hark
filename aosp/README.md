# 随系统预置 Hark

目标：刷机后开机即可语音唤醒，不用用户打开应用、不用手动授权。

## 方案

| 做法 | 作用 |
|---|---|
| 放进 `/product/priv-app`（特权应用） | 能用 privapp 白名单里的特权权限；能用 `persistent` |
| `android:persistent="true"` | 系统在用户解锁后（没设锁屏密码就是开机后）主动拉起进程并常驻，不会被杀；崩了自动重启。`HarkApp` 进程一起来就启动引擎，模型每次开机只加载一次 |
| 平台密钥签名（`certificate: "platform"`） | 自动获得悬浮窗（胶囊浮窗）、修改系统设置（调亮度）这两个 signature 级权限，用户不用去设置里开 |
| `default-permissions` | 首次开机默认授予录音、通知权限 |
| `privapp-permissions` | 放行 `START_ACTIVITIES_FROM_BACKGROUND`，后台「打开 X」「导航」不受限制 |
| `allow-in-power-save` | Doze 下不限制网络（大模型、讯飞） |
| 模型放 `/product/etc/hark/models` | 只读分区，开机就能读，不依赖 sdcard 挂载；sdcard 上同名文件优先，调试时 push 一份就能覆盖 |

**不要**用 `android:sharedUserId="android.uid.system"`。应用会以 system 身份跑在 system_server 同一个 uid 下：外部存储、前台服务、权限都有各种特殊处理，一崩还可能连带系统，而且以后去不掉（改 sharedUserId 只能清数据重装）。特权应用加平台签名已经够用。

另外，Android 14 起普通安装的应用不能从 `BOOT_COMPLETED` 启动麦克风前台服务，所以手机上普通安装时开机自启会被拦下，只发一条「点此启动」的通知。persistent 系统应用不受这条限制。

## 步骤

1. 编译 APK，放进这个目录：

   ```bash
   ./gradlew assembleRelease
   ```

   ```bash
   cp app/build/outputs/apk/release/app-release.apk aosp/Hark.apk
   ```

2. 准备模型（只放要用的，唤醒 + 默认普通话流式约 200 MB）：

   ```bash
   OUT=aosp/models tools/push_models.sh kws asr
   ```

   RK3562 这类弱 CPU 的机器可以把 `asr` 换成 `asr-small` / `asr-ctc`，同时把 `AppSettings.kt` 里 `streamModel` 的默认值改成对应的模型再编 APK。

3. 把整个 `aosp/` 目录拷到源码树，比如 `vendor/raferlyan/hark/`，在产品 mk 里加：

   ```makefile
   $(call inherit-product, vendor/raferlyan/hark/hark.mk)
   ```

4. 编译刷机。开机后检查：

   ```bash
   adb shell dumpsys package com.raferlyan.hark | grep -E "flags=|privateFlags=|RECORD_AUDIO|SYSTEM_ALERT_WINDOW"
   ```

   ```bash
   adb logcat -s XiaoYan
   ```

   日志里应该能看到「自动启动（persistent 进程启动）」→「开始监听麦克风」→「识别模型就绪」。

## 开机到能唤醒要多久

引擎启动后唤醒模型约 0.2 s 就开始监听；普通话流式模型在后台加载（855 上约 5 s，冷启动没有页缓存会更久）。这段时间里被唤醒照样回应「我在」，指令音频先攒着，模型一好就补识别，用户感觉不到在等。

## 调试时覆盖升级

预置后系统里的 Hark 是平台密钥签的，debug 密钥签的 APK 装不上去（签名不一致）。把平台密钥转成 keystore，配到 `local.properties`，gradle 就会用它签 debug 和 release：

```bash
openssl pkcs8 -inform DER -nocrypt -in platform.pk8 -out platform.key
openssl pkcs12 -export -in platform.x509.pem -inkey platform.key -name platform -out platform.p12 -passout pass:android
```

```properties
platform.storeFile=platform.p12
platform.storePassword=android
platform.keyAlias=platform
platform.keyPassword=android
```

`platform.pk8` / `platform.x509.pem` 在源码树的 `build/target/product/security/`（AOSP 测试密钥），量产版本用厂商自己的平台密钥。

## 注意

- 当前 APK 的 so 是压缩存放的（`useLegacyPackaging = true`），系统应用首次开机会把 so 解压到 data 分区。如果想省这点空间、让 so 直接从 APK 里 mmap，可以把它改成 `false`。
- persistent 进程常驻内存：普通话模式约 200 到 300 MB，评估内存时算进去。
- 应用没声明 `directBootAware`：设了锁屏密码的设备，要第一次解锁后才开始监听。车机一般不设密码，开机就能用。
