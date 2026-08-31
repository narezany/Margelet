# Margy 插件

[English](plugins.md) · [Русский](plugins.ru.md) · **中文**

插件是在这个分支内部运行的 Python 代码。它不是独立的程序，也不是外部的机器人：
它就住在你聊天所在的那个应用里。

## 论坛的规矩

**插件代码不得混淆。** 它以源码形式分发，任何人都应能打开来看。混淆＝论坛封禁。

原因很直白：这里没有沙箱。想知道一个插件到底做了什么，唯一的办法就是读它的代码。
藏起来的代码，等于一次性剥夺了所有人的这个办法。

## 关于安全，说实话

应用能做的，插件都能做：读你的聊天、以你的名义发消息、翻你的文件。清单里的权限
只是**作者的声明**，不是限制；应用不会核实，也无法核实。

只安装你自己读过的，或者你信任的。

## .marp 格式

就是一个改名成 `.marp` 的普通 zip：

```
margelet_example.marp
├── manifest.json   必需
├── main.py         必需
├── icon.png        可选
└── ...             插件需要的其它文件
```

`icon.png` 会显示在插件列表里。方形图片，128×128 就够了。

## manifest.json

```json
{
  "id": "margelet.example",
  "name": "示例",
  "version": "1.0",
  "author": "narezany",
  "description": "在控制台里打个招呼。",
  "min_version": "0.3",
  "permissions": ["ui"]
}
```

| 字段 | 含义 |
|---|---|
| `id` | 插件编号。拉丁字母加点。更新和设置都按它来存，不要改。 |
| `name` | 列表里显示的名字。 |
| `version` | 版本，字符串。 |
| `author` | 作者。 |
| `description` | 一两句话：它做什么。 |
| `min_version` | 插件能运行的最低 Margy 版本。版本更旧就根本装不上——并且会说明原因，而不是默默失败。可不填。 |
| `permissions` | 插件对自己的声明。见下表。 |
| `name_en`、`name_zh`、`description_en`… | 同一字段的其它语言版本。应用按自己的语言取，没有对应翻译就用原字段。 |

权限：`read_chats`、`send_messages`、`edit_messages`、`delete_messages`、
`change_profile`、`ui`。也可以写自定义名称——它会原样显示。

## main.py

```python
def on_start():
    margelet.log("插件在此问好", margelet.name)
```

插件启动时会调用 `on_start()`。没有它，插件就从上到下直接执行一遍。

`margelet` 对象无需 import 即可使用：

| | |
|---|---|
| `margelet.id` | 清单里的编号 |
| `margelet.name` | 清单里的名字 |
| `margelet.folder` | 插件在手机上的文件夹 |
| `margelet.log(*部分)` | 往控制台写一行 |
| `margelet.error(*部分)` | 同上，红色 |
| `margelet.ui(调用, delay_ms=0)` | 在主线程上执行——凡是碰屏幕的都必须如此 |
| `margelet.every(毫秒, 调用)` | 每隔这么久重复一次，返回一个句柄 |
| `margelet.cancel(句柄)` | 停止重复 |
| `margelet.toast(文本)` | 屏幕上的一行短提示 |
| `margelet.get(键, 默认=None)` | 插件自己的记忆 |
| `margelet.set(键, 值)` | 写入其中 |
| `margelet.flag(键, 默认=False)` | 把设置界面上的开关读成是/否 |
| `margelet.background(调用)` | 把耗时的活儿放到屏幕之外去做 |
| `margelet.send(聊天, 文本)` | 往聊天里发一条消息 |
| `margelet.fetch(地址, 调用)` | 访问网络并调用 `调用(文本)`；失败时给 `None` |
| `margelet.activity()` | 应用当前的界面 |
| `margelet.window(标题, 视图)` | 用应用自己的外观显示你的窗口 |
| `margelet.color(0xFFRRGGBB)` | 安卓所理解的那种颜色 |

`get` 与 `set` 既能挺过重启，也能挺过插件自身的更新：它们不放在插件目录里，
而目录在更新时会被替换。

## 事件

不是插件去问应用，而是应用来叫插件。

| | |
|---|---|
| `margelet.on_chat_opened(调用)` | 打开了聊天，并把该界面交给你 |
| `margelet.on_send(调用)` | 有人要发文本，在发出去之前 |
| `margelet.on_send_photo(调用)` | 有人要发图片；可以换成文本 |
| `margelet.on_message(调用)` | 来了一条消息 |
| `margelet.on_deleted(调用)` | 消息被删除 |
| `margelet.on_pin(调用)` | 会话被置顶或取消置顶；可以取消 |
| `margelet.on_request(调用)` | 发往服务器的请求，在发出之前；可以取消或替换 |
| `margelet.on_answer(调用)` | 服务器的回应，在应用看到它之前 |
| `margelet.on_update(调用)` | 服务器发来的更新，在被处理之前 |
| `margelet.button(标题, 调用)` | 在聊天菜单（三个点）里加自己的一行 |
| `margelet.menu(位置, 标题, 调用)` | 在聊天、资料页、消息或侧边菜单里加自己的一行 |
| `margelet.on_settings(调用)` | 有人改了本插件的某项设置 |
| `margelet.pick_file(调用, types=)` | 让人选一个文件 |

门是有意留得少的，而且每一扇都有名字。有名字的门能挺过 Telegram 的更新，因为
守着它的是我们，不是名字的偶然相同：里面变了，我们去改，插件察觉不到。

应用的任意方法也可以替换——见[下文](#替换任意方法)——但那是另一种承诺。更准确
地说，那是没有承诺：钩子靠的是别人方法的名字，而那个名字没人向我们保证过。

需要的门这里没有，就[去论坛说](https://t.me/margeletforum)。我们会加一扇有
名字的，而不是把所有门一次性打开。

### 打开了聊天

```python
def on_start():
    margelet.on_chat_opened(sit_on_the_box)

def sit_on_the_box(chat):
    box = chat.getChatActivityEnterView()
    ...
```

每次聊天界面出现时都会调用，并把那个界面交给你。

### 发送

```python
def on_start():
    margelet.on_send(sign)

def sign(text, chat):
    if text.startswith("/"):
        return False          # 干脆不发
    return text + " 🌿"       # 发这个
```

返回什么：字符串——发出去的就是它；`False`——不发；什么都不返回——原样发。
若有多个插件订阅，会依次调用，每个看到的都是上一个改过之后的文本。

这是应用唯一会**等**的事件：处理函数在想的时候，人正盯着还没发出去的消息。
处理函数要是想了超过十分之一秒，控制台会说一声——不是责备，是让作者知道。

> **不要在发送处理函数里访问网络。**
>
> 这是警告，不是风格建议。处理函数跑在绘制屏幕的那个线程上：请求还没回来，
> 手机就什么都不画。超时六秒的请求就是六秒的死机，连着三个就是十八秒。
>
> 安卓平时会自己抓住这种事，直接让应用崩溃并说明原因。这里那道保护不起作用：
> 它在 Java 的套接字里，而 Python 走自己的套接字，绕开了 Java。既不崩溃也没有
> 提示——应用就那么僵住。头两个不是我们写的插件正是这么写的，两位作者都以为
> 一切正常。
>
> 正确的写法见下面「从网络取答案的命令」。

### 用字符画代替照片

```python
def on_start():
    margelet.on_send_photo(instead_of_photo)

def instead_of_photo(path, caption, chat):
    if caption.strip() != ".ascii":
        return None           # 不是我们的说明——照片照常发出
    return "```\n" + draw(path) + "\n```"
```

`path` 是磁盘上的文件，`caption` 是照片下面写的说明。

返回什么：字符串——照片不发，改发这段文字；`False`——什么都不发；什么都不返回
——照片照常发出。谁先接手谁就拿走这张图：两个插件之间没什么可分的，所以这里没有
文本那样的接力。

文字可以用三个反引号包起来，这样会以等宽块发出。对字符画来说这不是装饰而是前提：
普通字体里字母宽度不一，任何字符画都会散架。

和 `on_send` 不同，这个调用**不在主线程**。所以在这里直接解码图片是合适的：这段
时间界面仍然是活的。从这里访问网络依然不合适，但理由变成了普通的那个——有人正等着
消息发出去——而不是手机会卡死。

要经常而安静地放弃。不是你的说明、文件打不开、放不下——返回 `None`，照片会自己发出
去。吃掉别人的发送又一声不吭，比不工作更糟。

基于这道门写好的插件就在旁边：[margelet.ascii.marp](margelet.ascii.marp)，源码在里面。

### 从网络取答案的命令

```python
def on_start():
    margelet.on_send(command)

def command(text, chat):
    if text.strip() != ".weather":
        return None
    margelet.background(lambda: margelet.send(chat, weather()))
    return False        # 命令本身不发出去

def weather():
    ...                 # 这里访问网络、慢一点都没关系
```

| | |
|---|---|
| `margelet.background(调用)` | 把耗时的活儿放到屏幕之外去做 |
| `margelet.send(聊天, 文本)` | 往聊天里发一条消息 |
| `margelet.dont_send()` | 等同于返回 `False` |

取消发送有三种等价写法：返回 `False`、调用 `margelet.dont_send()`，或者不带
参数调用 `margelet.cancel()`。之所以有三种，是因为大家想取消时伸手就去找
`cancel`——而它此前只表示「停止重复」，并且什么也没取消。

### 消息到来

```python
def on_start():
    margelet.on_message(count)

def count(text, chat, message_id, mine):
    if not mine:
        margelet.log("来了：", text)
```

自己发出去的消息也会到这里——`mine` 就是用来区分的。返回值不起作用：消息
已经到了。

### 聊天里属于自己的按钮

```python
def on_start():
    margelet.button("数一数", count)

def count(chat):
    margelet.toast("这里有 " + str(chat.getMessagesCount()) + " 条消息")
```

这一行排在聊天菜单最后，在所有常规条目之后：别人的代码不该把熟悉的条目挤开。

某个插件的回调抛错不会连累其他插件：每个都单独调用，出错的那个会在控制台里
拿到自己的堆栈。

`print()` 也会进控制台——它被接管了。

### 在其余菜单里的一行

`margelet.button` 是聊天菜单的简写。位置一共四个，通往它们的门只有一扇：

```python
def on_start():
    margelet.menu("chat", "数一数", count)
    margelet.menu("profile", "这是谁的资料页", whose)
    margelet.menu("message", "这是什么消息", what)
    margelet.menu("drawer", "数一数", count)

def count(界面):
    margelet.toast("在聊天里或侧边菜单里按了")

def whose(界面, 号码):
    margelet.toast("这是 " + str(号码))

def what(界面, 消息):
    margelet.toast("消息 " + str(消息.getId()))
```

| 位置 | 是什么 | 回调会收到什么 |
|---|---|---|
| `"chat"` | 会话顶部的三个点 | 界面 |
| `"profile"` | 人、群或频道页面上的三个点 | 界面，以及这是谁的资料页 |
| `"message"` | 长按一条消息 | 界面，以及这条消息本身 |
| `"drawer"` | 从左边滑出来的侧边菜单 | 界面 |

回调的参数有多少，取决于有多少意义：资料页有对象，侧边菜单没有。回调要按
自己的位置来写，但也因此不必每次都去接一个空。

这四个地方，插件的行都排在最后，在所有常规条目之后。

### 和服务器说话

三扇门，通向应用与服务器之间来往的东西。这是这里最锋利的一处，所以先说代价。

```python
def on_start():
    margelet.on_request(went)
    margelet.on_answer(came)
    margelet.on_update(happened)

def went(请求):
    margelet.log("请求", 请求.getClass().getSimpleName())

def came(请求, 回应, 错误):
    margelet.log("回应", 回应.getClass().getSimpleName())

def happened(更新):
    margelet.log("更新", 更新.getClass().getSimpleName())
```

三扇门的返回值读法相同：

| 返回什么 | 会怎样 |
|---|---|
| 什么都不返回 | 原样放行 |
| `False` | 完全不放行 |
| 一个对象 | 放行这个对象 |

**回调是在网络线程上想事情的。** 不是画界面的那条线程——但它想的时候，应用
对服务器是沉默的。客户端对服务器说的一切都走 `on_request`，服务器对客户端说
的一切都走 `on_update`：新消息、编辑、已读、谁在打字。安静的会话里每分钟几十
条，热闹的会话里几百条。

因此工作的顺序是：先看这是不是你要的东西，然后再动手。耗时的活儿挪到
`margelet.background` 里。回调想事情超过五十毫秒，引擎会自己在控制台里说出
来——自己的延迟从里面是感觉不到的，但人是能察觉的。

被取消的请求不会无声消失：发出它的那一方会拿到 `MARGELET_PLUGIN_CANCELLED`
错误。否则，等回应的界面就会一直等下去。

在这些回调里发自己的请求是可以的——它会绕过插件，而不会绕回你这里。

### 消息被删除

```python
def on_start():
    margelet.on_deleted(记下)

def 记下(编号, 会话):
    margelet.log("删除了", len(编号), "条，在", 会话)
```

`编号` 是列表，`会话` 是频道号，普通聊天为零。返回值不起作用：消息已经没了。

到这一刻消息本身已经消失——从这里读不到它。想要被删消息的正文，就得更早地在
`on_message` 里记下来。没有「让我看看删掉了什么」这道门，将来也不会有：应用得知
删除的时刻，和你得知的时刻是同一刻。

### 置顶

```python
def on_start():
    margelet.on_pin(不置顶频道)

def 不置顶频道(会话, 要置顶):
    if 要置顶 and 会话 < 0:
        margelet.toast("频道不置顶")
        return False
```

返回 `False`，就不置顶。这是第一道通向会话列表、而不是通向某个聊天的门。

和发送一样，应用在等你的回答：你的处理函数在想的时候，有人正看着自己刚按下的
按钮。慢活儿要挪进 `margelet.background`。

### 让人选一个文件

```python
def on_start():
    margelet.button("选一张图", 选择)

def 选择(会话):
    margelet.pick_file(拿到, types="image/*")

def 拿到(路径):
    if 路径 is None:
        margelet.toast("改主意了")
        return
    margelet.log("选了：", 路径)
```

`types` 决定文件选择器显示什么：`"image/*"`、`"text/plain"`。留空就是任意文件。

无论如何都会回调，包括对方中途退出——那时路径是 `None`。不吭声更糟：插件会一直
等一个永远不来的答复。

选中的文件会复制到这个插件自己的目录，路径指向那份副本。选择器给的不是路径，而
是一个带临时读取权限的地址：它只活到应用重启，而且在 Python 里打不开。副本才是
插件真正能用的东西。

不要自己拼 `Intent`，那样行不通：Python 桥靠反射挑构造函数，会摸到实际并不存在
的隐藏 `Intent(Parcel)`。这道门就是为了让你不必去碰它。

## 替换任意方法

上面这些，都是我们特意开的门。有时候你要的那道不在，又等不起。那就用
`margelet.hook`：像 Xposed 模块那样，替换应用的任意方法。

先说开关：**设置 → Margy → 插件 → 方法钩子**。默认关闭，这不是谨慎过头。有问题
的钩子会让应用一启动就崩溃——那时你没有地方去关掉它，因为设置就在那个打不开的
应用里面。

有一道保险：如果带钩子的那次启动没能活到最后，下一次就不带钩子启动并告诉你。但
保险是网，不是承诺，而且它张在你下面，不是张在你的插件下面。

```python
def on_start():
    if not margelet.hooks_work():
        margelet.log("没有钩子：", margelet.hooks_why())
        return

    margelet.hook(
        "org.telegram.messenger.MessagesController",
        "isDialogMuted",
        args=["long", "long"],
        after=显示,
    )

def 显示(调用):
    margelet.log("静音了吗", 调用.getResult())
```

| | |
|---|---|
| `margelet.hooks_work()` | 钩子到底起来了没有 |
| `margelet.hooks_why()` | 没起来的话，为什么 |
| `margelet.hook(类, 方法, before=, after=, args=)` | 替换 |

只有方法有重载时才需要 `args`，写成类型：`"int"`、`"long"`、
`"java.lang.String"`。处理函数只有一个参数 `调用`：

| | |
|---|---|
| `调用.args` | 调用的参数，在 `before` 里可以改 |
| `调用.thisObject` | 在哪个对象上调用的；静态方法为 `None` |
| `调用.getResult()` | 方法的返回值（在 `after` 里） |
| `调用.setResult(x)` | 替换返回值；在 `before` 里这同时也是取消调用 |

替换不成时 `margelet.hook` 返回 `False`，并把原因写进控制台。它不会默不作声地
装作成功了：一个以为自己在跑的插件，比一个老实承认没跑的插件更糟。

**该有什么预期。** 没人向我们保证过 Telegram 的方法名：今天叫这个，明天叫那个，
钩子就单纯地找不到了。所以要在事前问 `hooks_work()`，而不是事后；插件也应当在没
有钩子时照样能用，而不是散架。这不需要 root：替换只活在这一个应用里面，而且只在
它运行期间。别的应用碰不到。

如果某个钩子长期都需要、很多人都需要——[写到论坛来](https://t.me/margeletforum)。
用开了的钩子会变成一道有名字的门，而有名字的门扛得住更新。

## 属于自己的设置界面

插件不自己画界面——它只说界面由什么组成，画由应用来画。所以插件里的开关和
别处的开关一模一样：同一套主题、同一种颜色、同样的点法。

```python
def on_start():
    margelet.settings(
        margelet.header("怎么问好"),
        margelet.switch("hello", "问好", default=True,
                        about="打开聊天时说一句你好。"),
        margelet.text("name", "名字", default="朋友"),
        margelet.choice("mood", "语气", ["轻快", "平静"]),
        margelet.note("这些都留在手机上，哪儿也不去。"),
        margelet.action("全部忘掉", forget, danger=True),
    )
    margelet.on_settings(changed)

def changed(key, value):
    margelet.log("现在", key, "=", value)

def forget():
    margelet.toast("忘了")
```

| 行 | 含义 |
|---|---|
| `margelet.header(文本)` | 分组标题 |
| `margelet.note(文本)` | 灰色的说明 |
| `margelet.switch(键, 标题, default=False, about=None)` | 开关；用 `margelet.flag(键)` 读 |
| `margelet.text(键, 标题, default="", about=None)` | 手动填的一行；用 `margelet.get(键)` 读 |
| `margelet.choice(键, 标题, 选项, default=None)` | 多选一 |
| `margelet.action(标题, 调用, danger=False)` | 只干一件事的按钮 |

`settings()` 在启动时调用一次。默认值会立刻写进去——否则明明谁也没改，第一次
读却是空的。

有设置的插件会在列表里出现一个齿轮。点齿轮左边的那一行进设置，点右边的开关
则是开关插件本身。

这份声明和插件的记忆存在一起，而不是留在内存里，所以关着的插件也能打开设置
界面：有时正是要先把设置改好，再打开插件。

## 与安卓交界处的坑

这些都不是理论：三个坑都是写游戏插件时踩出来的，每个都花了一轮。

**颜色是有符号的数。** 在 java 里颜色是有符号的 32 位整数，凡是不透明的在
其中都是负数。Python 把 `0xFFFFFFFF` 当成一个很大的数，通往 java 的桥拒绝
转换它：

```
OverflowError: value too large to convert to int32_t
```

所以颜色要折算：

```python
def argb(value):
    return value - 0x100000000 if value > 0x7FFFFFFF else value

view.setTextColor(argb(0xFFFFFFFF))
```

它在第一个颜色上就抛错，于是界面根本不出现——看上去和「插件没启动」一模一样。

**Python 的列表不是 java 的数组。** 方法要数组的时候，列表不行：

```python
from java import jarray
from java.lang import CharSequence

titles = jarray(CharSequence)(["第一", "第二"])
```

**碰屏幕只能在主线程上。** `on_send` 事件本来就在主线程；设置界面上的按钮
不是。从那里打开任何窗口都要经过 `margelet.ui`，否则什么也打不开。

**从 Python 里不能在 Canvas 上画东西。** 完全不能。`Canvas.drawText` 的一部分
形式声明在非公开的 `android.graphics.BaseCanvas` 里，应用拿不到：

```
NoSuchMethodError: no non-static method
"Landroid/graphics/BaseCanvas;.drawText(Ljava/lang/CharSequence;IIFFLandroid/graphics/Paint;)V"
```

`drawCircle`、`drawRect` 以及几乎所有绘制都来自那里。而且它是在别人的
`onDraw` 里抛出的，倒下的是整个应用，不是插件。让安卓去画，插件只说画什么：
文字上的颜色 span（`ForegroundColorSpan`）、控件浮层里的图形
（`View.getOverlay`）、现成的 `GradientDrawable`。

**一个被封的形式会毒掉整个名字。** 桥会一次性解析同名方法的所有形式，所以
会绊在你根本没调用的那个上。一个只传一个参数的调用就是这么崩的：

```
NoSuchMethodError: no non-static method "Landroid/text/Layout;.getLineForOffset(IZ)I"
```

`(int, boolean)` 这个形式在 `android.jar` 和 AOSP 源码里都没有——是手机的
固件加的。`Spannable.removeSpan` 也一样。两个结论。第一，替代办法通常有：
用 `getLineCount` 和 `getLineEnd` 找行，行底就是下一行的顶，span 可以不删除，
用同一个 `setSpan` 把它挪到空区间上停着。第二，更重要：**没法事先照着手册
核对名字。** 一有机会就把要用的调用空跑一遍——空区间上的 span、零尺寸的
图形——通过了才启用。否则你会在半个效果已经留在屏幕上之后，才知道某个名字
被封了。

**浮层用的是已经滚动过的坐标。** `View.draw` 是在
`canvas.translate(-mScrollX, -mScrollY)` 之后调用的，所以不要自己再减一次
滚动量。文字装得下时看不出差别——长文本上就露馅了。

**在绘制里调用 `invalidate()` 不会带来新的一帧。** 只要还有别人在送帧，动画
就在动；人一停下来，它就僵住。要向 choreographer 要：
`postInvalidateOnAnimation()`。

**每一部分各自看护。** 整个插件只包一个 `try`，第一个错误就把全部关掉，
用户看到的不是「效果少了一半」，而是「什么都不工作」。自己的错误绝不该
让别人赔上一段对话，所以看护必须有——但它该关掉的是一部分，不是整个插件。

这类错误只在控制台里看得见：设置 → Margy → 插件 → 控制台。那里有出错的
行，也有原因。

## 除此之外还能用什么

这里的 Python 是真的 Python，能访问应用的 Java 类：

```python
from java import jclass

Config = jclass("org.telegram.margelet.MargeletConfig")
margelet.log("水印：", Config.watermarkOnSend())
```

顺着它就能摸到应用的其余部分。所谓“插件什么都能做”，不是修辞。

## 控制台

设置 → Margy → 插件 → 控制台。插件打印的一切都进这里，错误是红色的。
Python 出错时是沉默的，没有这个界面，作者只能从“怎么什么都不动”里猜自己的笔误。

## 安装

设置 → Margy → 插件 → 从文件安装。安装窗口会显示作者和声明的权限。

也可以直接在聊天里点 `.marp` 文件——应用会提示安装。

安装窗口有两个按钮。“安装”装上但保持关闭。“安装并启用”装上、打开，并立刻
重启 Margy——插件马上就开始工作，不必等着手动关掉应用。

如果插件声明的 `min_version` 高于你的版本，它就装不上，并会告诉你需要哪个
版本——而不是装上了再坏掉。

安装窗口上有「查看压缩包」按钮：它会用手机上能打开压缩包的程序打开这个
`.marp`，让人在装之前就能读代码，而不是装完再读。论坛的规矩要求代码是公开的，
那就得有东西能把它打开。

长按那一行看插件详情和删除。

关闭的意思是“不再启动它”。已经跑起来的 Python 代码没有办法停下——它会活到应用
重启为止。插件界面上的“重启 Margy”按钮就是为此：关掉、点一下，插件就没了。

### 商店

同一个界面的第二个标签页，列出
[@margelet_marps](https://t.me/margelet_marps) 频道里的插件。一点就装，走的是
同一个安装窗口、问的是同样的问题：频道并不会让别人的代码变成审核过的代码。没
有人看过它们——我们也没有。读代码。

想让插件进商店，就把 `.marp` 作为文件发到频道里。文件名会成为列表里的名字，文
件的说明会成为描述。顺序按时间，新的在上面。

### 跟着插件一起打开的东西

“安装后启用”的意思是“让它跑起来”。如果插件系统本身是关着的，它会被打开：否则
这个请求根本没法完成，而在这之前插件只是什么也不做，还无从猜起。

如果插件用到钩子，安装窗口会单独写一行，启用插件也会一并打开钩子。说明钩子不
是出于礼貌——[上文](#替换任意方法)写了它的代价。

## 示例

[margelet_example.marp](margelet_example.marp) —— 和应用里自带的是同一个。
里头就两行，从它开始正好。

## 有问题去哪

[论坛](https://t.me/margeletforum) · [频道](https://t.me/margeletter)
