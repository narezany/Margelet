# Margy plugins

**English** · [Русский](plugins.ru.md) · [中文](plugins.zh.md)

A plugin is Python code that runs inside the fork. Not a separate program, not
a bot on the side: it lives in the same app your chats do.

## The forum rule

**Plugin code is not obfuscated.** It ships as source, and anyone must be able
to open it. Obfuscation means a ban on the forum.

The reason is plain: there is no sandbox here. The only way to know what a
plugin does is to read it. Hidden code takes that away from everyone at once.

## Honestly, about safety

A plugin can do anything the app can: read your chats, write as you, get at
your files. The permissions in the manifest are the **author's declaration**,
not a restriction; the app does not check them and cannot.

Install only what you have read yourself or what you trust.

## The .marp format

An ordinary zip renamed to `.marp`:

```
margelet_example.marp
├── manifest.json   required
├── main.py         required
├── icon.png        optional
└── ...             anything else the plugin needs
```

`icon.png` is shown in the plugin list. A square picture; 128×128 is plenty.

## manifest.json

```json
{
  "id": "margelet.example",
  "name": "Example",
  "version": "1.0",
  "author": "narezany",
  "description": "Says hello in the console.",
  "min_version": "0.3",
  "permissions": ["ui"]
}
```

| Field | What it is |
|---|---|
| `id` | The plugin's number. Latin letters and dots. Updates and settings are keyed by it — do not change it. |
| `name` | The name in the list. |
| `version` | Version, as a string. |
| `author` | Who wrote it. |
| `description` | A line or two: what it does. |
| `min_version` | The oldest Margy the plugin works on. On anything older it will not install at all — with an explanation, not silently. Optional. |
| `permissions` | What the plugin declares about itself. The list is below. |
| `name_en`, `name_zh`, `description_en`, … | The same in another language. The app picks by its own language and falls back to the plain field. |

Permissions: `read_chats`, `send_messages`, `edit_messages`,
`delete_messages`, `change_profile`, `ui`. A name of your own works too — it is
shown as written.

## main.py

```python
def on_start():
    margelet.log("hello from the plugin", margelet.name)
```

`on_start()` is called when the plugin starts. Without it the plugin is simply
executed top to bottom.

The `margelet` object is available without an import:

| | |
|---|---|
| `margelet.id` | the number from the manifest |
| `margelet.name` | the name from the manifest |
| `margelet.folder` | the plugin's folder on the phone |
| `margelet.log(*parts)` | a line in the console |
| `margelet.error(*parts)` | the same, in red |
| `margelet.ui(call, delay_ms=0)` | run this on the main thread — anything touching the screen has to |
| `margelet.every(ms, call)` | repeat every `ms`. Returns a handle |
| `margelet.cancel(handle)` | stop repeating |
| `margelet.toast(text)` | a short line over the screen |
| `margelet.get(key, fallback=None)` | the plugin's own memory |
| `margelet.set(key, value)` | write to it |
| `margelet.flag(key, fallback=False)` | read a switch from the settings screen as yes/no |
| `margelet.background(call)` | do something slow away from the screen |
| `margelet.send(chat, text)` | send a message to a chat |
| `margelet.fetch(url, call)` | go to the network and call `call(text)`; `None` if it failed |
| `margelet.activity()` | the app's current screen |
| `margelet.window(title, view)` | show your own window in the app's own dressing |
| `margelet.color(0xFFRRGGBB)` | a colour in the form Android understands |

`get` and `set` survive both a restart and an update of the plugin itself:
they are not kept in the plugin's folder, which is replaced on update.

## Events

A plugin does not poll the app — the app calls the plugin.

| | |
|---|---|
| `margelet.on_chat_opened(call)` | a chat was opened; gets the chat screen |
| `margelet.on_send(call)` | a text is being sent, before it goes |
| `margelet.on_send_photo(call)` | a picture is being sent; can be replaced with text |
| `margelet.on_message(call)` | a message arrived |
| `margelet.on_deleted(call)` | messages were deleted |
| `margelet.on_pin(call)` | a chat is being pinned or unpinned; can be cancelled |
| `margelet.on_request(call)` | a request to the server, before it goes; can be cancelled or replaced |
| `margelet.on_answer(call)` | the server's answer, before the app sees it |
| `margelet.on_update(call)` | an update from the server, before it is processed |
| `margelet.button(title, call)` | your own line in the chat menu (the three dots) |
| `margelet.menu(where, title, call)` | your own line in the chat, profile, message or side menu |
| `margelet.on_settings(call)` | a setting of this plugin was changed |
| `margelet.pick_file(call, types=)` | ask the person for a file |

There are deliberately few doors, and each one has a name. A named door survives
a Telegram update, because we are the ones who keep it, not a coincidence of
names: when the insides change, we fix it and your plugin never notices.

You can also replace any other method of the app — see
[below](#hooking-any-method) — but that is a different promise. More precisely,
it is the absence of one: a hook rests on someone else's method name, and nobody
guaranteed us that name.

If you need a door that is not here, [say so on the forum](https://t.me/margeletforum).
We will add a named one rather than open all of them at once.

### A chat was opened

```python
def on_start():
    margelet.on_chat_opened(sit_on_the_box)

def sit_on_the_box(chat):
    box = chat.getChatActivityEnterView()
    ...
```

Called every time a chat screen comes up, and handed that screen.

### Sending

```python
def on_start():
    margelet.on_send(sign)

def sign(text, chat):
    if text.startswith("/"):
        return False          # do not send at all
    return text + " 🌿"       # this goes instead
```

What to return: a string — that is what gets sent; `False` — do not send;
nothing — leave it alone. If several plugins are subscribed they are called in
turn, each seeing the text as the previous one left it.

This is the one event the app **waits** for: while the handler thinks, a person
is looking at an unsent message. If a handler took longer than a tenth of a
second, the console says so — not as a reproach, but so the author knows.

> **Never go to the network from a send handler.**
>
> This is a warning, not a style note. The handler runs on the same thread that
> draws the screen: while a request is in flight, the phone draws nothing. A
> request with a six-second timeout is six seconds of frozen app; three requests
> in a row are eighteen.
>
> Android normally catches this itself and crashes the app with a clear message.
> That protection does not apply here: it lives in Java's sockets, and Python
> goes to the network through its own, bypassing Java. No crash, no warning —
> just a frozen app. The first two plugins that were not ours were written
> exactly this way, and both authors believed everything was fine.
>
> The right shape is below, under "A command that answers from the network".

### A picture instead of a photo

```python
def on_start():
    margelet.on_send_photo(instead_of_photo)

def instead_of_photo(path, caption, chat):
    if caption.strip() != ".ascii":
        return None           # not our caption — the photo goes as usual
    return "```\n" + draw(path) + "\n```"
```

`path` is the file on disk, `caption` is what was typed under the photo.

What to return: a string — the photo does not go, this text goes instead;
`False` — nothing goes; nothing — the photo goes the usual way. The first
handler that takes the picture keeps it: there is nothing to share between two
plugins, so there is no chain here the way there is for text.

The text can be wrapped in triple backticks — then it goes as a monospace
block. For pictures made of characters that is not decoration but a condition:
in a normal font letters have different widths, and any such picture falls
apart.

Unlike `on_send`, this is called **off the main thread**. So decoding the
picture right here is fine and expected: the screen stays alive meanwhile.
Going to the network from here is still a bad idea, but now for the ordinary
reason — someone is waiting for their message to send — not because the phone
would freeze.

Refuse quietly and often. Not your caption, the file would not open, it does
not fit — return `None` and the photo goes by itself. Eating someone's send and
saying nothing is worse than not working.

A finished plugin built on this door is right here:
[margelet.ascii.marp](margelet.ascii.marp), source inside.

### A command that answers from the network

```python
def on_start():
    margelet.on_send(command)

def command(text, chat):
    if text.strip() != ".weather":
        return None
    margelet.background(lambda: margelet.send(chat, weather()))
    return False        # the command itself is not sent

def weather():
    ...                 # network and slowness are fine here
```

| | |
|---|---|
| `margelet.background(call)` | do something slow away from the screen |
| `margelet.send(chat, text)` | send a message to a chat |
| `margelet.dont_send()` | the same as returning `False` |

There are three equal ways to cancel a send: return `False`, call
`margelet.dont_send()`, or call `margelet.cancel()` with no argument. Three,
because people reach for `cancel` — and until now it only meant "stop
repeating" and silently cancelled nothing.

### Messages arriving

```python
def on_start():
    margelet.on_message(count)

def count(text, chat, message_id, mine):
    if not mine:
        margelet.log("arrived:", text)
```

Your own sent messages arrive here too — that is what `mine` is for. The return
value changes nothing: the message has already arrived.

### Your own button in a chat

```python
def on_start():
    margelet.button("Count", count)

def count(chat):
    margelet.toast("there are " + str(chat.getMessagesCount()) + " messages here")
```

The line goes last in the chat menu, after all the usual entries: someone
else's code should not push the familiar ones around.

If one plugin's callback throws, the others still get called: each is called
separately and the broken one gets its traceback in the console.

`print()` goes to the console as well — it is intercepted.

### Your own line in the other menus

`margelet.button` is the short form for the chat menu. There are four places in
all, and one door leads to them:

```python
def on_start():
    margelet.menu("chat", "Count", count)
    margelet.menu("profile", "Whose profile is this", whose)
    margelet.menu("message", "What message is this", what)
    margelet.menu("drawer", "Count", count)

def count(screen):
    margelet.toast("pressed in a chat or in the side menu")

def whose(screen, peer):
    margelet.toast("this is " + str(peer))

def what(screen, message):
    margelet.toast("message " + str(message.getId()))
```

| where | what it is | what the callback gets |
|---|---|---|
| `"chat"` | the three dots at the top of a conversation | the screen |
| `"profile"` | the three dots on a person, group or channel screen | the screen, and whose profile it is |
| `"message"` | a long press on a message | the screen, and the message itself |
| `"drawer"` | the side menu that slides out from the left | the screen |

A callback takes as many arguments as there is sense in: a profile has a subject,
the side menu does not. You write the callback for its own place — and in return
you never have to accept an emptiness.

In all four places the plugins' lines come last, after all the usual ones.

### Talking to the server

Three doors into what the app exchanges with the server. This is the sharpest
thing here, so the price comes first.

```python
def on_start():
    margelet.on_request(went)
    margelet.on_answer(came)
    margelet.on_update(happened)

def went(request):
    margelet.log("request", request.getClass().getSimpleName())

def came(request, response, error):
    margelet.log("answer", response.getClass().getSimpleName())

def happened(update):
    margelet.log("update", update.getClass().getSimpleName())
```

All three read the answer the same way:

| what you return | what happens |
|---|---|
| nothing | let it through as is |
| `False` | do not let it through at all |
| an object | let that through instead |

**The callback thinks on the network thread.** Not the one that draws the
screen — but while it thinks, the app is silent towards the server. Everything
the client says to the server goes through `on_request`, and everything the
server says to the client goes through `on_update`: new messages, edits, read
marks, who is typing. That is dozens of things a minute in a quiet chat and
hundreds in a busy one.

Hence the order of work: first look at whether this is the thing you want, and
only then do something. Move long work into `margelet.background`. If a callback
thinks for longer than fifty milliseconds, the engine says so in the console by
itself — you cannot feel your own delay from the inside, but people notice it.

A cancelled request does not vanish silently: whoever sent it gets a
`MARGELET_PLUGIN_CANCELLED` error. Otherwise a screen waiting for an answer
would wait forever.

You may send your own request from inside these callbacks — it goes past the
plugins instead of coming back round to you.

### Deleted messages

```python
def on_start():
    margelet.on_deleted(remember)

def remember(ids, chat):
    margelet.log("deleted", len(ids), "in", chat)
```

`ids` is a list, `chat` is a channel id or zero for an ordinary chat. The return
value changes nothing: the messages are already gone.

The message itself is gone by then — you cannot read it from here. If you want
the text of a deleted message, you had to keep it earlier, in `on_message`.
There is no "show me what was deleted" door and there will not be one: the app
learns about the deletion at the same moment you do.

### Pinning

```python
def on_start():
    margelet.on_pin(no_channels)

def no_channels(chat, pinning):
    if pinning and chat < 0:
        margelet.toast("not pinning channels")
        return False
```

Return `False` and the chat stays unpinned. This is the first door that opens
into the chat list rather than into a conversation.

The app waits for your answer, the way it does on send: while your handler
thinks, someone is looking at a button they just pressed. Move slow work into
`margelet.background`.

### A file from the person

```python
def on_start():
    margelet.button("Pick a picture", pick)

def pick(chat):
    margelet.pick_file(ready, types="image/*")

def ready(path):
    if path is None:
        margelet.toast("changed their mind")
        return
    margelet.log("picked:", path)
```

`types` is what the file picker shows: `"image/*"`, `"text/plain"`. Empty means
any file.

The handler is always called, including when the person backs out — then the
path is `None`. Staying silent would be worse: the plugin would wait for an
answer that never comes.

What was picked is copied into this plugin's own folder, and the path points at
the copy. The picker hands out an address with temporary read permission, not a
path: it lives until the app restarts and cannot be opened from Python. The copy
is what the plugin can actually use.

Do not build an `Intent` by hand — it will not work: the Python bridge picks the
constructor by reflection and reaches the hidden `Intent(Parcel)`, which is not
really there. This door exists so you do not have to go there.

## Hooking any method

Everything above is a door we opened on purpose. Sometimes the one you need
isn't there and waiting isn't an option. That's `margelet.hook`: replace any
method of the app, the way Xposed modules do.

The switch first: **Settings → Margy → Plugins → Method hooks**. Off by default,
and not out of caution. A bad hook crashes the app at startup — and then there
is nowhere to turn it off, because the settings live inside the app that won't
open.

There is a guard: if a launch with hooks does not survive, the next one starts
without them and says so. But a guard is a net, not a promise, and it is strung
under you, not under your plugin.

```python
def on_start():
    if not margelet.hooks_work():
        margelet.log("no hooks:", margelet.hooks_why())
        return

    margelet.hook(
        "org.telegram.messenger.MessagesController",
        "isDialogMuted",
        args=["long", "long"],
        after=show,
    )

def show(param):
    margelet.log("muted?", param.getResult())
```

| | |
|---|---|
| `margelet.hooks_work()` | did hooks come up at all |
| `margelet.hooks_why()` | why they didn't, if they didn't |
| `margelet.hook(cls, method, before=, after=, args=)` | replace it |

`args` is only needed when the method is overloaded, and is written as types:
`"int"`, `"long"`, `"java.lang.String"`. The handler gets one argument, `param`:

| | |
|---|---|
| `param.args` | call arguments; changeable in `before` |
| `param.thisObject` | the object it was called on; `None` for static |
| `param.getResult()` | what the method returned (in `after`) |
| `param.setResult(x)` | replace the result; in `before` this also cancels the call |

`margelet.hook` returns `False` when it could not hook, and writes the reason to
the console. It will not quietly pretend it worked: a plugin that thinks it is
running is worse than one that plainly isn't.

**What to expect.** Nobody promised us Telegram's method names: a method is
called one thing today and another tomorrow, and the hook simply stops finding
it. That's why you ask `hooks_work()` before, not after, and why a plugin should
cope without its hook instead of falling apart. No root is needed: the
replacement lives inside this one app and only while it runs. Other apps are out
of reach.

If a hook turns out to be needed permanently and by many —
[write to the forum](https://t.me/margeletforum). A hook that catches on becomes
a named door, and a named door survives updates.

## A settings screen of your own

A plugin does not draw its own screens — it says what the screen is made of,
and the app draws it. That is why a plugin's switch looks like every other
switch: same theme, same colour, same tap.

```python
def on_start():
    margelet.settings(
        margelet.header("How to say hello"),
        margelet.switch("hello", "Say hello", default=True,
                        about="Says hi when you open a chat."),
        margelet.text("name", "Name", default="friend"),
        margelet.choice("mood", "Mood", ["cheerful", "calm"]),
        margelet.note("All of this stays on the phone and goes nowhere."),
        margelet.action("Forget everything", forget, danger=True),
    )
    margelet.on_settings(changed)

def changed(key, value):
    margelet.log("now", key, "=", value)

def forget():
    margelet.toast("forgotten")
```

| Row | What it is |
|---|---|
| `margelet.header(text)` | a section title |
| `margelet.note(text)` | an explanation in grey |
| `margelet.switch(key, title, default=False, about=None)` | a switch; read with `margelet.flag(key)` |
| `margelet.text(key, title, default="", about=None)` | a line typed by hand; read with `margelet.get(key)` |
| `margelet.choice(key, title, options, default=None)` | one of several |
| `margelet.action(title, call, danger=False)` | a button that just does something |

`settings()` is called once, at start. Defaults are written straight away —
otherwise the first read would come back empty although nobody changed
anything.

A plugin with settings gets a gear in the list. Tapping the row to the left of
it opens the settings; tapping the switch on the right turns the plugin itself
on and off.

The declaration is kept together with the plugin's memory rather than in RAM,
so the settings screen opens for a disabled plugin too: you may want to fix a
setting before turning it on.

## Traps at the Android boundary

None of this is theory: all three came up while writing the games plugin, and
each cost a separate round.

**A colour is a signed number.** In Java a colour is a signed 32-bit integer,
and everything opaque is negative in it. Python treats `0xFFFFFFFF` as just a
large number, and the bridge into Java refuses to convert it:

```
OverflowError: value too large to convert to int32_t
```

So a colour has to be folded:

```python
def argb(value):
    return value - 0x100000000 if value > 0x7FFFFFFF else value

view.setTextColor(argb(0xFFFFFFFF))
```

This throws on the very first colour, so nothing appears at all — and it looks
exactly like "the plugin did not start".

**A Python list is not a Java array.** If a method wants an array, a list will
not do:

```python
from java import jarray
from java.lang import CharSequence

titles = jarray(CharSequence)(["First", "Second"])
```

**Touch the screen only from the main thread.** The `on_send` event is already
on it; a button on the settings screen is not. From there any window has to be
opened through `margelet.ui`, or nothing opens.

**You cannot draw on a Canvas from Python.** Not at all. Some forms of
`Canvas.drawText` are declared in the non-public
`android.graphics.BaseCanvas`, and an app is not given them:

```
NoSuchMethodError: no non-static method
"Landroid/graphics/BaseCanvas;.drawText(Ljava/lang/CharSequence;IIFFLandroid/graphics/Paint;)V"
```

`drawCircle`, `drawRect` and nearly all the rest of drawing come from there
too. And it throws inside somebody else's `onDraw`, so it takes down the whole
app, not the plugin. Let Android draw, and tell it what: a colour span on the
text (`ForegroundColorSpan`), a drawable in the view's overlay
(`View.getOverlay`), a ready-made `GradientDrawable`.

**One blocked form poisons the whole name.** The bridge resolves every form of
a method name at once, so it trips over one you never call. This is how a
one-argument call failed:

```
NoSuchMethodError: no non-static method "Landroid/text/Layout;.getLineForOffset(IZ)I"
```

The `(int, boolean)` form is in neither `android.jar` nor the AOSP sources —
the phone's firmware added it. The same happened to `Spannable.removeSpan`.
Two conclusions. First, replacements usually exist: find the line through
`getLineCount` and `getLineEnd`, a line's bottom is the next line's top, and a
span need not be removed — park it on an empty range with the same `setSpan`.
Second, and more important: **you cannot check the names against a reference
up front.** Try the calls you need for real but harmlessly at the first
opportunity — a span on an empty range, a zero-sized drawable — and enable only
what got through. Otherwise you learn about a blocked name after half the
effect is already on screen.

**The overlay lives in scrolled coordinates.** `View.draw` is called after
`canvas.translate(-mScrollX, -mScrollY)`, so do not subtract the scroll
yourself. While the text fits, there is no difference — it shows up on a long
one.

**`invalidate()` from inside drawing gives you no new frame.** The animation
runs while somebody else brings frames and freezes the moment the person stops
typing. Ask the choreographer instead: `postInvalidateOnAnimation()`.

**Guard each part separately.** One `try` around the whole plugin switches
everything off at the first error, and the person sees not "half the effect"
but "nothing works". Your own error must never cost somebody their
conversation, so a guard is a must — but let it silence one part, not the
plugin.

Errors like these are visible only in the console: Settings → Margy →
Plugins → Console. It has both the line and the reason.

## What else is available

The Python here is the real thing, with access to the app's Java classes:

```python
from java import jclass

Config = jclass("org.telegram.margelet.MargeletConfig")
margelet.log("watermark:", Config.watermarkOnSend())
```

The rest of the app is reachable from there. That is what "a plugin can do
everything" means — it is not a figure of speech.

## The console

Settings → Margy → Plugins → Console. Everything the plugins print goes
there, and their errors in red. Python fails silently, so without this screen
an author learns about a typo only from "nothing works".

## Installing

Settings → Margy → Plugins → Install from file. The install dialog shows the
author and the declared permissions.

You can also tap a `.marp` file right in a chat — the app will offer to install
it.

The install dialog has two buttons. "Install" installs the plugin disabled.
"Install and run" installs it, turns it on and restarts Margy right away, so
the plugin starts working without waiting for the app to be closed by hand.

If the plugin declares a `min_version` above yours it will not install, and you
are told which version it needs — instead of installing and then breaking.

The install dialog has a "View the archive" button: it opens the `.marp` with
whatever on the phone handles archives, so the code can be read before
installing rather than after. The forum rule demands that the code be open —
so there has to be something to open it with.

Hold the row for the plugin's card and for deleting.

Turning a plugin off means "do not start it again". There is no way to stop
Python code that is already running — it lives until the app is restarted. That
is what the "Restart Margy" button on the plugins screen is for: switch it
off, tap, and the plugin is gone.

### The store

The second tab on the same screen lists plugins from the
[@margelet_marps](https://t.me/margelet_marps) channel. One tap installs them,
through the same dialog and the same questions: a channel does not make someone
else's code checked. Nobody looked at these — we did not either. Read the code.

To get a plugin into the store, post the `.marp` as a file to the channel. The
file name becomes the name in the list, the caption becomes the description.
The order is by time, newest first.

### What gets turned on with a plugin

"Enable after installing" means "let it run". If the plugin system itself is
off, it gets turned on: otherwise the request cannot be carried out, and before
this the plugin simply did nothing with no way to guess why.

If the plugin uses hooks, the install dialog says so on its own line, and
enabling the plugin enables hooks too. Hooks are announced not out of politeness
— [above](#hooking-any-method) says what they cost.

## The example

[margelet_example.marp](margelet_example.marp) — the same one that ships with
the app. Two lines inside; they are a fine place to start.

## Questions

[Forum](https://t.me/margeletforum) · [Channel](https://t.me/margeletter)
