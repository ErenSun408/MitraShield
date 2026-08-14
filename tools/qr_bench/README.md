# 邀请码识别率量化台（`[qr]`）

真机一次只能试几十张、还混着拍摄条件，分不清是「码本身不行」还是「手抖」。这里把渲染与解码两端按生产代码
1:1 复刻，跑几百张随机载荷，单独量出**码本身可不可解**这一项。

两半分工：

| | 位置 | 覆盖 | 为什么分开 |
|---|---|---|---|
| 原图往返 | `app/src/test/.../InvitePayloadQrTest.kt` | 渲染 → 解码 | 跟着 `:app:testDebugUnitTest` 跑，改动即回归 |
| JPEG 有损 | 本目录 `QrJpegBench.java` | 渲染 → JPEG → 解码 | `java.awt`/`javax.imageio` 不在 Android 单测 classpath 上（编译走 android.jar 桩），只能挪到桌面 JVM |

## 跑 JPEG 那一轮

需要 JDK 11+（用 JEP 330 单文件源码启动器，不必先编译）与 `zxing-core` 的 jar。jar 在 Gradle 缓存里：

```bash
# GRADLE_USER_HOME 因机器而异，本项目当前是 E:\AndroidStudioProjects-cache\.gradle
ZXING="$GRADLE_USER_HOME/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3/*/core-3.5.3.jar"
java -cp "$ZXING" tools/qr_bench/QrJpegBench.java
```

## 2026-08-14 的结论（邀请码载荷改密文那次）

新旧两种格式各 300 张，原图 + JPEG q=0.9/0.7/0.5，**共 2400 次解码，零失败**：

| 格式 | 均长 | QR 版本 | 出图 | 原图 | q=0.9 | q=0.7 | q=0.5 |
|---|---|---|---|---|---|---|---|
| 旧（明文 JSON，含 ver/sid） | 185 字符 | 9–10 | 610–650px | 0/300 | 0/300 | 0/300 | 0/300 |
| 新（密文 base64url） | 252 字符 | 11–12 | 730×730px | 0/300 | 0/300 | 0/300 | 0/300 |

**这台子量不到的两件事**，别拿上面的零失败去替它们背书：

1. **相机那条路的解码器不是 ZXing 而是 ML Kit**（见 `QrCodeScreen` 里两条路的分工说明），而 ML Kit 是
   Android-only，离线台上跑不了。相册/链接那条路是 ZXing，这里全覆盖。
2. **拍摄本身**：模糊、斜角、暗光、拍屏幕的摩尔纹。码变密（模块 57×57 → 65×65，同样显示尺寸下每个模块更小）
   最先影响的正是这一项，只能真机验。
