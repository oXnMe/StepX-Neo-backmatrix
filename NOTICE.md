# NOTICE

## 非官方项目

本项目与 STEPX 及其关联公司没有任何关系，也没有获得厂商的认证或背书。
"STEPX"、产品型号 "PZ261A" 及相关固件属于其各自所有者。

应用通过反射调用设备固件自带的 `display_ext` 系统服务（`setBackScreenAgentFrame` /
`getBackScreenAnimationInfo`）。这些接口不是公开 SDK，随固件 OTA 可能改变或消失；
接口变更导致的功能失效不属于本项目的缺陷承诺范围。

## 派生代码与出处

背屏协议本身属于事实性信息，不受著作权保护，这里只作说明：
服务名 `display_ext`、方法签名 `setBackScreenAgentFrame(int[], Bundle)` /
`getBackScreenAnimationInfo()`、391 点灰度、机型名 `PZ261A`，以及诊断用的
`getState()` / `getType()`。`core/BackScreen.kt` 只是把这些协议事实翻译成 Kotlin。

以下两处来自 [stepx-advance/backscreen-toolbox](https://github.com/stepx-advance/backscreen-toolbox)
（MIT）。该项目的版权归其作者：Copyright (c) 2026 STEPX Advance contributors，许可全文见其仓库的 LICENSE 文件。

| 本仓库 | 上游对应 | 关系 |
| --- | --- | --- |
| `core/Dot.kt` 的 `rotate()` | `FrameOrientation.project()` | 改写。投影算法与常量一致：绕中心 (8,11) 做整数个 90°、只在第 3..19 行的 17×17 安全区内映射、四个象限的映射表相同。改动是不再接收上游那个与合成器方向耦合的 `compositor` 参数，入参换成 `turns` |
| `core/Scene.kt` 的 `PixelText.GLYPHS` | `ToolFrames.java` 的 `LETTERS` / `NUM` | 36 个字形中 28 个逐位相同（`0 1 4 7 8 A B C D E F G H I J K L M O P R S T U V X Y Z`）。`2 3 5 6 9` 与 `N Q W` 是本仓库重画的——上游的 N 与 W 在 3 点宽下几乎同形，背屏上读不出区别 |

3×5 是最小可读点阵字体，很多字形本来就只有一种画法，这 28 个相同字形也可能是两边
同源于通行的 3×5 点阵字体。无法证实也无法排除，所以按最保守的方式处理：标注出处。

其余部分对照上游 55 个 Java 文件后没有发现同构实现，均为本仓库自行编写：
GIF 解码、转换管线、`.bsa` 编解码、动画库、播放器与前台服务、Compose 界面、
13 个工具的画面与算法、4×9 大号数字、圆角可用点判定。
`core/Scene.kt` 的直线/圆/椭圆/箭头原语也与之不同（上游用整数 Bresenham，本仓库用浮点参数插值）。

## 内置示例素材

仓库不含原始 GIF，只有转换后的 `app/src/main/assets/demo_basketball.bsa`。
它由一段网络流传的表情包转换而来，版权属于原作者，仅用于演示转换管线与自测，
不随本项目的 MIT 许可授权。要公开分发请替换成你有权使用的素材：

```bash
python3 tools/convert_demo.py your-clip.gif \
  --out app/src/main/assets/demo_basketball.bsa --name "内置演示"
```

## 第三方库

- AndroidX / Jetpack Compose（Apache-2.0）
- Kotlin 标准库与 kotlinx.coroutines（Apache-2.0）

GIF 解码器、转换管线、`.bsa` 编解码、背屏调用均为本仓库自行实现，没有引入第三方图像库。
