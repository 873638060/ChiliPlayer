# 轻椒音乐 · ChiliPlayer

面向 Android 6.0（API 23）及以上设备的本地音乐播放器，主要用于车机横屏。「轻椒」谐音「青椒」，寓意轻量版椒盐。支持从授权文件夹扫描音乐，也支持连接自建 Subsonic／OpenSubsonic 服务器。

- 按歌曲或文件夹浏览，按当前筛选结果随机播放。
- 支持曲库缓存、队列恢复、歌词、ReplayGain 和手动导出诊断日志。
## 版本

| 分支目录 | 版本 | 应用包名 | 验证情况 |
| --- | --- | --- | --- |
| [car/](car/) | 0.3.7.4 系统版 | `car.salt.player` | Android 6 模拟器完成播放、文件夹范围、缓存和重启恢复回归。 |

真实 MT3561 车机仍待验证。

## 构建

在 Windows PowerShell 中执行。安装 Android SDK Platform 36、Build Tools 35.0.0 和 Android Studio 自带的 JBR。脚本默认查找 Android Studio 与 SDK 的标准安装路径。自定义安装时，设置 `ANDROID_SDK_ROOT`（也支持 `ANDROID_HOME`）和 `SALTPLAYER_JDK`。

```powershell
.\car\build.ps1
```

APK 输出到 `dist/`。首次构建会在 `build/` 下生成本机测试签名。更新已安装版本需要使用相同签名。

系统版回归详见 [测试说明](car/tests/README.txt)。测试会写入应用内的测试账号、曲库和播放队列，请在独立测试模拟器运行。

## 参考与致谢

- **椒盐音乐（Salt Player）**。本项目的横屏曲库、迷你播放栏和展开播放页参考椒盐音乐 12.0.0 的界面与交互。早期开发也查看过发行 APK 的曲库与播放行为。对照当前公开应用源码与留存的分析资料，未发现直接移植的椒盐应用源码。轻椒音乐由本项目独立维护，与椒盐音乐无官方隶属关系。
- **Tempus**。开发时参考过 Tempus 的 Subsonic 专辑浏览、搜索和串流接口，以及 ReplayGain 的预读、缓存和切歌处理。对照现有源码，未发现直接移植的 Tempus 应用源码。[Tempus 上游仓库](https://github.com/eddyizm/tempus)

## 许可证

应用源码按 GPL-2.0-or-later 提供，详见 [GPL 第 2 版全文](LICENSE)。JMAC 的上游地址与许可证见 [第三方声明](car/assets/licenses/NOTICE.txt)。
