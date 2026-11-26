系统版回归测试
仅在测试模拟器使用，测试会替换应用内测试账号、曲库和队列，不应在个人音乐数据所在设备执行。
1. 在项目根目录运行 car/build.ps1，再运行 car/tests/build.ps1。
2. 启动 Python car/tests/qa_server.py；默认监听本机 18053。
3. adb reverse tcp:18053 tcp:18053；安装 dist/ChiliPlayer-0.3.7.5.apk 和 car/tests/test.apk。
4. 常规回归：adb shell am instrument -w car.salt.probe/car.salt.player.Probe
5. 强制停止应用后运行 -e restore true，检查队列/曲库恢复。
6. -e audit true：缓存故障、随机优先队列、歌词、ReplayGain、按钮、扫描取消和界面恢复。
7. -e cachePhase fetch，强制停止应用，再运行 -e cachePhase afterFetch，验证真实拉取中断。
测试通过返回 INSTRUMENTATION_CODE: -1；失败返回 0 和断言栈。不要只检查 adb 退出码。


8. -e scopeBefore true：名称沿用旧版复现入口，当前执行新版文件夹/搜索/多选范围断言。需先运行常规回归创建测试音频队列。
