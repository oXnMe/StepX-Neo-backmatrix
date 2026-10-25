# 给 Step Studio 的接入提示词

在 Studio 里新建应用时，把下面整段作为需求贴进去。

桥内置在「点阵背屏」里：**设置 →「背屏桥」→ 启动背屏桥**，之后 `127.0.0.1:39871` 才有人听。
这份和应用里「设置 → 背屏桥 → 接入提示词」弹窗中的内容一致，以 `bridge/BridgePrompt.kt` 为准。

```text
设备：STEPX PZ261A 点阵背屏，17 × 23 灰度，共 391 个点。
像素值 0-255，0 是灭。索引按 y * 17 + x，y 从上到下。
宿主 App 已经在本机开了本地桥，页面走宿主代发请求就行，不要自己反射 display_ext。

基址：http://127.0.0.1:39871
鉴权：请求头 X-Bridge-Token: <设置页里显示的那串>
探活：GET /health（不需要 Token，返回机型与 display_ext 是否可用）
调用：POST /api/call

请求体：
{"name": "能力名", "arguments": {}}
字段名是 arguments，不是 args。
业务失败也返回 HTTP 200，必须看响应里的 ok 字段：
{"ok": true, ...} 成功
{"ok": false, "error": "原因"} 失败

三个能力：

1. get_back_screen_display_info
arguments 留空。返回：
width 17 / height 23 / rotation 0 / model
type、state：合成器上报，背屏熄灭或空闲时是 unknown
displayExtAvailable：背屏服务在不在
playing：宿主是不是正在播动画

2. set_back_screen_frame
arguments：{"frame": [391 个 0-255 整数]}
推之前宿主会自动停掉正在播的动画，不会互相盖帧。
这个接口不缩放、不旋转，给什么推什么，页面坐标和背屏点位一一对应。

3. clear_back_screen_frame
arguments 留空。交还背屏，恢复系统默认画面（时间 / amoo）。

另外，宿主里没有唤醒 / 休眠背屏、亮度、显示模式、待机类型这类接口，不要做这些入口。
```

## Studio 侧再加这几条

- 封装一个客户端：`health()` 与 `call(name, args)`，统一走 `invokeNative('request', ...)` 代发——
  页面直接 `fetch` 会被壳的 CSP 拦住。请求超时、非 2xx、`ok:false` 都要归一成能读的一句话。
- 进页面先 `health()`，明确显示「已连接 / 未连接 + 原因」。桌面浏览器预览（`getBridgeMode() !== 'native'`）
  直接提示需真机运行，不要假装连上了。
- 连不上时提示用户去「点阵背屏 → 设置 → 背屏桥 启动」。
- 端口与 Token 放配置里，不要散落在页面组件中。
- 页面主体建议就是 17×23 点阵绘制器（点格切换 0/255 → 生成 frame → 推帧）+ 清除按钮 + 状态展示。

## 说明

- 端口和 Token 在「点阵背屏」设置页里直接列出来。
- 桥只绑 `127.0.0.1`，页面必须跑在真机壳里；桌面浏览器预览一定连不到。
- 桥没启动、机型不对、`display_ext` 不可用，三种情况都会在 `health` 里体现。
