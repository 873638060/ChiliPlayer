轻椒音乐 / ChiliPlayer 0.4.5
当前唯一活动源码目录：app/。统一ARM32＋ARM64，无独立系统/软解版本线。

安装：dist/ChiliPlayer-0.4.5.apk，约1.26 MB，Android 6.0/API 23起。
与原0.3.7.5同包名car.salt.player、同证书，已验证覆盖升级保留测试数据。
旧car.salt.soft实验包是另一应用，其数据不会自动迁入；历史源码与交付物保留作参考。
发布详情与最新验证状态见 ../更新日志.md 和 ../README.md；编译前必须重读这些文件及 version.json，并检查 dist 最新正式 APK 实际版本，不能依据旧聊天版本编译。版本规则见 VERSIONING.txt。

构建
在项目根目录运行app/build.ps1，输出dist/ChiliPlayer-<版本号>.apk。
工具链沿用Android SDK platform36/build-tools35.0.0与Android Studio JBR；其他机器按实际安装位置修改脚本路径。
默认签名文件build/chiliplayer/signing.p12；本机首次构建会从保留的build/car/signing.p12复制原证书。
也可通过app/build.ps1 -SigningKey <原证书路径>指定。缺少原证书或证书不匹配会停止构建，不自动生成新证书。
源码包不含签名私钥。原生库与许可证位于native/、licenses/、assets/licenses/NOTICE.txt；这不是纯开源二进制成品。

测试（仅专用模拟器，会写入测试账号/曲库/队列）
先构建主应用，再运行app/tests/build.ps1。
启动app/tests/qa_server.py，设置NEWSALT_QA_PORT=18066；adb reverse tcp:18053 tcp:18066。
安装主APK与app/tests/test.apk。
完整播放：am instrument -w car.salt.unifiedprobe/car.salt.player.Probe。
重启主应用后用-e restore true；额外定位/网络验证用-e extra true。
焦点/暂停/触控/生命周期：am instrument -w car.salt.unifiedprobe/car.salt.player.AuditProbe。
通用逻辑：am instrument -w -e audit true car.salt.unifiedprobe/car.salt.player.SystemMergeProbe。
同一入口用-e scopeBefore true测试范围，用-e uiAudit true生成明暗截图。
拉取中断：-e cachePhase fetch，强制停止主应用后用-e cachePhase afterFetch。
覆盖升级：先安装原0.3.7.5，以UpgradeProbe执行-e phase seed；覆盖安装新版后执行-e phase verify，再强制停止后执行-e phase restart。
检查INSTRUMENTATION_CODE: -1，不只看adb退出码。

Android 6 ARM32运行回归已通过。ARM64仅完成静态库检查，未在64位设备运行；未做真实车机性能或混音测试。
验证记录：tests/verification-0.4.0/。
APK SHA256：1e1fcd33722b7249806ceadb85d92791cb110744639d5cba99c062845bcc2ace

0.4.1 新增目录扫描回归：car.salt.unifiedprobe/car.salt.player.FolderProbe，仅创建独立测试目录，不重写账号和曲库。已在 Android 6 ARM32 模拟器通过。


0.4.2 修正播放页封面信息独立垂直居中；先科 C2 1280×720 截图核对通过，中心 y=380，见 ../更新日志.md。


0.4.3 遥控回归入口：car.salt.unifiedprobe/car.salt.player.RemoteProbe，只能在专用模拟器运行（会更改测试队列及内存曲库）。新布局与遥控操作见 ../更新日志.md。


0.4.4 将纯图标按钮焦点统一为正圆；构建、原签名和先科 C2 明暗主题视觉验证通过。本轮未重跑模拟器/完整播放回归，参见更新日志。

0.4.5 删除组件许可中的未使用组件说明；构建和先科 C2 许可弹窗核对通过，未重跑播放回归。
