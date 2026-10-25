package com.stepx.backmatrix.bridge

/**
 * 设置页「接入提示词」弹窗的正文，也是 [BridgeApi] 里能力的对外说明。
 *
 * 只写 PZ261A 上验证过能用的三件：推帧、清屏、查状态。
 * 那 14 个待验证探针不进提示词——写进去 H5 就会照着调，然后拿到一堆
 * `bestEffort:true` 的成功响应，最后发现背屏没变化，反而更难查。
 */
object BridgePrompt {

    /**
     * 内容分成「设备 / 接口 / 能力 / 示例」四段，方便直接整段丢给模型。
     * 这里不出现 `$`，免得 Kotlin raw string 把模板变量吃进去。
     */
    val TEXT: String = """
设备：STEPX PZ261A 点阵背屏，17 × 23 灰度，共 391 个点。
像素值 0-255，0 是灭。索引按 y * 17 + x，y 从上到下。
宿主 App 已经在本机开了本地桥，页面走宿主代发请求就行，不要自己反射 display_ext。

基址：http://127.0.0.1:39871
鉴权：请求头 X-Bridge-Token: backscreen-bridge
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

JS 调用模板：

const BASE = 'http://127.0.0.1:39871';

async function call(name, args) {
  const res = await fetch(BASE + '/api/call', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Bridge-Token': 'backscreen-bridge'
    },
    body: JSON.stringify({ name: name, arguments: args || {} })
  });
  const data = await res.json();
  if (!data.ok) throw new Error(data.error);
  return data;
}

async function draw(fn) {
  const frame = new Array(17 * 23).fill(0);
  for (let y = 0; y < 23; y++) {
    for (let x = 0; x < 17; x++) {
      frame[y * 17 + x] = fn(x, y) & 255;
    }
  }
  return call('set_back_screen_frame', { frame: frame });
}

// 画一条对角线
draw(function (x, y) { return x === y % 17 ? 255 : 0; });

// 收工时交还背屏
call('clear_back_screen_frame', {});

前提：桥要在「设置 → 背屏桥」里处于运行中，否则页面连不上。
""".trimIndent()
}
