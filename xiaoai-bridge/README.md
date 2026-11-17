# 接力（xiaoai-bridge）

星仪在电视上的运行时。它没有界面：小爱照常唤醒、听写、回答它会的；接力从系统日志（logd）里跟着小爱的每一轮，
遇到点歌直接交给网易云，遇到小爱答不了的，就交给同进程里的星仪引擎去做，做完借小爱的嗓子读出结果。

- 包名 `xyz.erapi0neer.xiaoaibridge`，Android 9（minSdk 23 / targetSdk 28），在小米电视 2 GB 内存的机型上跑。
- 星仪引擎（`../voice/assistant` 的 `xyz.erapi0neer.xingyi.agent`）在构建时编进这个 APK，源码只有那一份。
- 和桌面（`../launcher`）、星仪用同一把签名：它们之间的 signature 级权限靠这个。

## 构建

不用 Gradle：`build.sh` 依次跑 aapt2 → javac → d8 → zipalign → apksigner（Windows + Git Bash）。

```sh
ANDROID_SDK=/path/to/android-sdk JAVA_HOME=/path/to/jdk-17 KS=/path/to/debug.keystore sh build.sh
```

- `ANDROID_SDK` 下要有 `android-15`（build-tools）和 `android-35/android.jar`。
- `KS`：签名用的 keystore（口令 `android`）。公开仓库**不带** keystore，用你自己的；三个应用要用同一把。
- `XINGYI`：引擎源码目录，默认是旁边的 `../voice/assistant`。

## 装到电视

```sh
TV=<电视的 IP>:5555 sh bridge.sh install
```

装完会用 adb 授予开发用的权限（`READ_LOGS`、`DUMP`、`PACKAGE_USAGE_STATS`、`WRITE_SECURE_SETTINGS`），重启服务，
再把星仪的无障碍服务重新绑上。`bridge.sh status` 看服务、权限、模型线路和最近的日志。

## 模型线路

`python provision.py [--model <模型名>]` 把简单问答用的线路推到电视：网关地址、模型名，密钥从 Windows 用户环境变量
`GATEWAY_API_KEY` 读、经 adb 的 stdin 送过去，不进命令行、不落盘。星仪引擎的双线路配置（官方 MiniMax + 网关）见
`../voice/assistant/provision_minimax.py`。运行中换模型：

```sh
adb shell am broadcast -a xyz.erapi0neer.xiaoaibridge.ROUTE --es model <模型名> --es route gateway
```

## 测试

`sh tests/run_all.sh`：接力自己的 JVM 测试（不碰电视），编译时连同引擎源码一起编。
