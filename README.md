# 新椒盐 · SaltPlayer

面向 Android 6.0（API 23）及以上设备的本地音乐播放器，主要用于车机横屏。支持从授权文件夹扫描音乐，也支持连接自建 Subsonic／OpenSubsonic 服务器。

- 按歌曲或文件夹浏览，按当前筛选结果随机播放。
- 支持曲库缓存、队列恢复、歌词、ReplayGain 和手动导出诊断日志。
- 提供两个可并行安装的版本，分别使用系统播放和 LibVLC 软件解码。

## 版本

| 分支目录 | 版本 | 应用包名 | 验证情况 |
| --- | --- | --- | --- |
| [car/](car/) | 0.3.7.4 系统版 | `car.salt.player` | Android 6 模拟器完成播放、文件夹范围、缓存和重启恢复回归。 |
| [soft/](soft/) | 0.3.7.1-soft.2 软解候选 | `car.salt.soft` | 候选 APK 已核验签名。ARMv7 软件解码和播放回归记录对应此前的 soft.1。 |

真实 MT3561 车机仍待验证。软解版打包了 armeabi-v7a 和 arm64-v8a，ARM64 实机仍待验证。两份应用各自保存设置、服务器账号和文件访问授权。

## 构建

在 Windows PowerShell 中执行。安装 Android SDK Platform 36、Build Tools 35.0.0 和 Android Studio 自带的 JBR。脚本默认查找 Android Studio 与 SDK 的标准安装路径。自定义安装时，设置 `ANDROID_SDK_ROOT`（也支持 `ANDROID_HOME`）和 `SALTPLAYER_JDK`。

```powershell
.\car\build.ps1
.\soft\build.ps1
```

APK 输出到 `dist/`。首次构建会在 `build/` 下生成本机测试签名。更新已安装版本需要使用相同签名。软解版首次构建需要联网，从 Maven Central 下载 LibVLC Android 3.7.8，并按仓库中的 SHA-256 校验。构建缓存位于 build/soft。

系统版回归详见 [测试说明](car/tests/README.txt)。测试会写入应用内的测试账号、曲库和播放队列，请在独立测试模拟器运行。

## 许可证

应用源码按 GPL-2.0-or-later 提供，详见 [GPL 第 2 版全文](LICENSE)。JMAC、LibVLC 和 AndroidX 的上游地址与各自许可证见 [系统版声明](car/assets/licenses/NOTICE.txt)及[软解版声明](soft/assets/licenses/NOTICE.txt)。
