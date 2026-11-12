// 1:1 移植自 Andee（原 net.kuafuai.andee.net.ToolSchemas），只拷手机操作相关的
// 工具（看屏/点按/输入/设备/通讯录），其余（保险库/日历/会议/机器狗/笔记本/
// 待办/话题/情景）Jarvis 没有对应的执行器，不拷。工具描述一字未改，只改两处：
// 包名和 ScreenController 的 import；以及 get_screen_element 描述里一句
// “switch Andee”改成“switch Jarvis”（否则模型会让用户去设置里找一个不存在
// 的 Andee 无障碍服务）。Andee 许可证为 Apache-2.0 附加条件；本项目自用不外发。
package com.jarvis.app.net

import com.jarvis.app.screen.ScreenController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机操作工具的 JSON Schema 表：模型看到的工具名、描述、参数。
 * 描述是模型读的提示词，照 Andee 原样；`method` 是本机分发键（见分发器）。
 * 工具描述遵循的规则：模型绝不用手算坐标——有 e 编号就按 e 编号点，
 * 坐标（0-1000 相对值）只是树被藏时的兜底。
 */
object ToolSchemas {

    /**
     * Whether `get_screen_element` ships a screenshot alongside the element
     * list. False: when the list has content it already says what is tappable
     * and where, and the picture is pure token cost. The device still attaches
     * one on its own when the list comes back empty — see
     * `ScreenController.dumpUiTree`, the only case where the picture is the
     * only thing the brain has.
     */
    const val UI_TREE_WITH_SHOT_DEFAULT = false

    fun all(): JSONArray {
        val arr = JSONArray()
        for (t in TOOLS) arr.put(t)
        return arr
    }

    /**
     * The subset that may be advertised — everything except `localOnly`
     * tools. 本表里没有 localOnly 工具，所以与 all() 相同；留着是为以后
     * 加本地工具时不用改调用处。
     */
    fun forHub(): JSONArray {
        val arr = JSONArray()
        for (t in TOOLS) if (!t.optBoolean("localOnly", false)) arr.put(t)
        return arr
    }

    private val TOOLS: List<JSONObject> = listOf(
        tool(
            name = "get_screen_element",
            method = "screen.ui_tree",
            description = """
        [DEFAULT TOOL · FIRST CHOICE] Read the current screen as an OUTLINE of
        screen elements — one line per element, indented to show what contains
        what:
            e17|LinearLayout||[500,570]
              e18*|ImageView|#avatar|[62,523]
              e19*|TextView|好友3|[181,509]
              e20|TextView|这是第3条朋友圈的正文|[493,535]
              e24*|ImageView|#comment_btn|[950,621]
        `e17` is the element's number, `*` marks clickable, then class, label,
        and the element's center in 0-1000 RELATIVE coordinates (computed by
        the system, not by you).

        INDENTATION IS MEANING, and it is the answer to "which one". The lines
        indented under e17 are one post: that avatar, that name, that body and
        that comment button belong to 好友3 and to nobody else. Whenever a
        screen repeats a shape — a feed, a chat list, search results, a
        settings page — the control you want is NOT identified by its own line
        alone. Find the labeled line that names the row you want, then take
        the control from that line's OWN indent group. Never pair a name in
        one group with a button in another.

        The label column is the text, or the description, or — when an element
        has neither — the tail of its resource id with `#` in front, like
        `#comment_btn` above. A `#` value is an internal name, not words
        printed on the screen: use it to tell controls apart, never read it
        back to the user as if it were visible text.

        Whenever the goal is "find a control and tap / type / scroll / read
        on-screen text", call this tool first, then act on what it returned.

        HARD RULES:
        1) To tap an element from this list, use tap_screen_element with its
           e-number. NEVER re-compute coordinates from the bracket values or
           from a screenshot when an e-number exists.
        2) An icon button with no text and no description (Moments' comment
           bubble, an avatar, a photo) is identified by WHERE IT SITS, not by
           what it says. Read its indent group: the `#comment_btn` sitting
           under the line that says 好友3 IS 好友3's comment button, and
           tapping it by e-number is right even though you cannot name the
           icon. Only when two candidates inside the SAME group are
           indistinguishable do you need to see them — then call this tool
           again with with_shot=true: each line's [x,y] is a 0-1000 relative
           center, the same relative space as the screenshot, so the icon at
           image position (x,y) is the line with the nearest bracket
           coordinates. Then still tap by e-number, never by the coordinates
           you read off. A missing `*` does not mean "not a button": ad
           popups draw their ✕ as a bare `ImageView` line with no label and
           no `*`. If such a line sits where the screenshot shows the ✕, tap
           it by e-number — that beats aiming at it by grid cell.
        3) Chinese input: tap the input field FIRST (by e-number), then the
           VERY NEXT action must be type_text. Never re-tap the same input box
           before typing; the keyboard opens by itself.
        4) After an action, the response already tells you what the screen
           became: tap responses carry `after_shot` (image), `screen_changed`,
           and — when the tree is readable — `elements` (the POST-action
           element list with fresh e-numbers, plus `elements_hint`). Chain
           your next action straight from that; do NOT call this tool again
           just to "see the result" when the action response already carried
           it. Re-dump only when you need to scroll deeper or the response
           had no elements (self-drawn pane).
        5) Popups first: close splash ads / membership dialogs / update
           prompts / permission requests before continuing the task.

        [EMPTY LIST] nodes_useful = 0 means nothing on this pane carries a
        name. Read `nodes_total` to tell the two very different cases apart:

        - nodes_total = 0 as well: the pane draws its own pixels (mini-program,
          WebView article, game canvas) and there is no outline at all. The
          device has already attached a screenshot to THIS response
          (shot_auto): read that one, do not call take_screenshot for the same
          screen. Prefer back / home to exit and retry via a different route
          over blind coordinate taps. Blame the pane, not the app: the same
          app's native screens (chat list, tabs, settings) dump fine.

          The exception is worth watching for, and `hint` will tell you when
          it applies: if this SAME app gave you a real element list earlier in
          the session and is now returning zero nodes, that is not a self-drawn
          pane — a self-drawn pane still reports its containers. It means our
          accessibility connection went stale, which happens to WeChat in
          particular. You cannot fix it with a tool call. Ask the user, in
          plain words, to open 设置 → 无障碍 and switch Jarvis (this device's
          assistant, the floating ball) off and back on; it takes seconds and
          the element list comes back. Judge whether it is worth interrupting
          them — you can still tap by coordinate meanwhile — but do not silently
          keep dumping an app that has stopped answering.
        - nodes_total > 0: the elements are there, they are merely nameless.
          The outline still groups them and still gives every centre, so use
          it — match what you see in the attached screenshot against the
          indentation and tap the line sitting at that spot by e-number.
          A coordinate tap here is a downgrade, not a shortcut.

        `elements_dropped: N` means the screen carried more elements than fit
        and N of the least useful lines were cut, bottom-first. What you were
        given is complete and correctly grouped; scroll if what you want is
        not in it.

        e-numbers are only valid until the page changes — if
        tap_screen_element reports an unknown element, call this tool again
        for a fresh list.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "verbose" to prop(
                    "boolean",
                    "Return the full nested JSON tree (debug only). The default outline carries the same structure for a third of the tokens — this is for a human reading a dump, not for you.",
                    default = false,
                ),
                "with_shot" to prop(
                    "boolean",
                    "Include a screenshot alongside the element outline. Default false: when the outline has content it already tells you what is tappable, where, and what belongs with what, so the picture costs tokens and adds nothing. Set it true only when you must judge the screen visually — two icons in the SAME indent group that you cannot tell apart by class or id, or a layout / color / render question. When the outline comes back EMPTY the device attaches a screenshot by itself (shot_auto), so never set this to work around an empty list.",
                    default = UI_TREE_WITH_SHOT_DEFAULT,
                ),
            ),
        ),
        tool(
            name = "tap_screen_element",
            method = "screen.tap_id",
            description = "[PREFERRED] Tap a screen element by its e-number from get_screen_element's element list — e.g. {\"eid\": \"e7\"}. The device resolves the number to that element's exact pixel center itself: no coordinate math, no scaling errors. Always tap e-numbers from the LATEST get_screen_element (the page may have changed since an older one; stale numbers fail loudly with 'unknown element' — that means re-dump, not guess).",
            required = listOf("eid"),
            props = mapOf(
                "eid" to prop("string", "Element number from the latest get_screen_element list, e.g. \"e7\"."),
            ),
        ),
        tool(
            name = "tap_by_coordinates",
            method = "screen.tap",
            description = """
        [LAST RESORT] Tap a point on the screen. Aim in ONE of two ways:

        1. **By grid cell + part** (preferred whenever you are looking at an
           image): {"cell": "D7", "part": "bottom-right"}. The cell is one of
           the aim grid drawn on the screenshot you were shown, columns A-H
           left to right, rows 1-12 top to bottom. The letters are printed
           along the TOP edge and the numbers down the LEFT edge, like a map's
           rulers — nothing is printed inside a cell, so the target is never
           covered. Follow the target's column up and its row left. Then `part`
           says which third of that cell the
           target is in, across and down: top-left, top, top-right, left,
           center, right, bottom-left, bottom, bottom-right (omit it for the
           centre). The device converts both to pixels itself; there is no
           arithmetic for you to do. A cell is too coarse to tap with on its
           own — always give the part unless the target fills the cell.
           If the target straddles two cells, pick the one holding most of it.
           For anything not clearly bigger than a cell (an icon, a ✕ close
           button, a small chip), CHECK FIRST: zoom_screen_region with the
           same cell and part shows a magenta crosshair exactly where this tap
           would land. On target → send this tap with the same arguments.
           Off target → aim off that magnified image: its labels are
           LOWERCASE and you must say so — {"cell": "d7", "part": "left",
           "on": "zoom"}. A lowercase label sent as if it were a full-screen
           cell will be about 120 units off.
           **On an app that hides its tree (get_screen_element came back empty
           / withheld — WeChat often does), zooming first is not optional**:
           a tap or long-press is refused unless its point lies inside a zoom
           taken after your last gesture. So the rhythm there is always
           zoom → tap, one zoom per screen.

        2. **By coordinate** {"x": …, "y": …} — RELATIVE 0-1000 integers
           ((0,0) top-left, (1000,1000) bottom-right; the device converts to real
           pixels for any screen size). With {"on": "zoom"} these numbers are
           0-1000 *within the last magnified image* instead of the whole screen,
           and the device maps them back — so you never add region.left or divide
           by scale_x yourself. Values outside 0-1000 are rejected, not clamped.

        Use this only when the target has no e-number: self-drawn panes
        (mini-program, WebView, game), images, pure-visual content. If a
        get_screen_element list exists for this screen, use tap_screen_element.

        When the app hides its element tree and your point lands on blank
        background right beside one small isolated shape (an icon, a "··"
        button), the device taps that shape's centre instead and says so with
        `snapped_from` / `snapped_to`. Check after_shot as usual — if the shape
        was not what you meant, aim again further away from it.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the image you aimed from, e.g. \"D7\" on a full screenshot or \"d7\" on a magnified one."),
                "part" to propEnum("Which part of that cell the target is in — one of nine thirds or, vertically, upper/lower between top/bottom. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "X, 0 (left edge) to 1000 (right edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "y" to prop("integer", "Y, 0 (top edge) to 1000 (bottom edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "on" to propEnum("Which image x/y (or a lowercase cell) were read off. \"screen\" is the default.", listOf("screen", "zoom")),
            ),
        ),
        tool(
            name = "long_press_screen_element",
            method = "screen.long_press_id",
            description = "Long-press a screen element by its e-number from get_screen_element (context menus, drag handles, multi-select). Same freshness rule as tap_screen_element: latest dump only. Duration is fixed at ~600ms.",
            required = listOf("eid"),
            props = mapOf(
                "eid" to prop("string", "Element number from the latest get_screen_element, e.g. \"e12\"."),
            ),
        ),
        tool(
            name = "long_press_by_coordinates",
            method = "screen.long_press",
            description = "Hold a finger on a point for a set duration (context menus, drag handles). Aim exactly like tap_by_coordinates — by grid cell plus the third of it the target is in, {\"cell\": \"D7\", \"part\": \"bottom-right\"} (add on=\"zoom\" and use a lowercase label for a magnified image), or by RELATIVE 0-1000 coordinates, not pixels. Prefer long_press_screen_element when the target has an e-number.",
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the image you aimed from, e.g. \"D7\" on a full screenshot or \"d7\" on a magnified one."),
                "part" to propEnum("Which part of that cell the target is in — one of nine thirds or, vertically, upper/lower between top/bottom. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "X, 0 (left edge) to 1000 (right edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "y" to prop("integer", "Y, 0 (top edge) to 1000 (bottom edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "on" to propEnum("Which image x/y (or a lowercase cell) were read off. \"screen\" is the default.", listOf("screen", "zoom")),
                "duration_ms" to prop("integer", "How long to hold, in milliseconds. Default 600.", default = 600),
            ),
        ),
        tool(
            name = "swipe_by_coordinates",
            method = "screen.swipe",
            description = "Swipe from one point to another: scroll lists, page carousels, drag things. To scroll a list DOWN (see more content below), swipe from a point near the BOTTOM UP to a point near the TOP — fingers move opposite to content. All coordinates are RELATIVE 0-1000 like tap_by_coordinates.\n\nBy default the finger stops at the end point before lifting, so the list moves about as far as the finger did and then stays put — no momentum. That makes the distance the control: to bring something 300 units further up into view, swipe 300 units.\n\n**To travel several screens, use `screens` — not `fling`.** {\"x1\":500,\"y1\":800,\"x2\":500,\"y2\":200,\"screens\":3} repeats one full-travel swipe three times, stops early if the list runs out, and answers with `screens_moved` — a NUMBER you can count with. That number is what lets you know where you are and how to come back; three screens down stays three screens down. `fling` gives you no such number: the system decides how far it coasts, so all you learn is \"something moved\". It also refuses the 7th same-direction fling in a row, because chaining them is how a long list becomes a maze (measured: 21 flings hunting one old post, never once the right place). Use fling only where a gesture has to be inertial — a carousel, a canvas — and look at the return before flinging again.",
            required = listOf("x1", "y1", "x2", "y2"),
            props = mapOf(
                "x1" to prop("integer", "Start X, 0 (left) to 1000 (right)."),
                "y1" to prop("integer", "Start Y, 0 (top) to 1000 (bottom)."),
                "x2" to prop("integer", "End X, 0 (left) to 1000 (right)."),
                "y2" to prop("integer", "End Y, 0 (top) to 1000 (bottom)."),
                "duration_ms" to prop("integer", "How long the finger takes to travel, in ms. Default 300.", default = 300),
                "screens" to prop("integer", "How many full finger-travels to repeat in ONE call (1..10). Stops early at the end of the list and reports screens_moved. Use this instead of fling whenever you want to go further than one screen."),
                "fling" to prop("boolean", "Lift while still moving so the list coasts. Distance is the system's to decide and is not reported — prefer `screens`. Default false.", default = false),
            ),
        ),
        tool(
            name = "type_text",
            method = "screen.type",
            description = "Type text into the CURRENTLY FOCUSED input field (tap the field first — by e-number — then call this as the very next action; the keyboard opens by itself, do not re-tap the field). Existing content is REPLACED, not appended. Does not press Enter/Send — use submit_input for that.\n\nWhen a tap opened an input bar by itself — a reply/comment box sliding up with the keyboard (WeChat Moments 评论, a chat's reply bar) — the field is ALREADY focused: call this straight away, without tapping the field. On an app that hides its element tree this matters double: the input strip above the keyboard is thin and sits right on top of the post or message below it, so a tap aimed at it by grid cell lands on that content instead and opens it. This tool does not need the tree — it types through the editor's own input connection — so `performed: true` with the text read back is proof it went into the box.",
            required = listOf("text"),
            props = mapOf(
                "text" to prop("string", "The text to enter."),
            ),
        ),
        tool(
            name = "submit_input",
            method = "screen.submit_input",
            description = "Submit the currently focused input by firing its IME action (Enter / Send / Search / Next / Done — whichever the field was set up for). Typical uses: send a chat message after type_text, run a search. Use this instead of trying to press a keyboard Enter key — Android delivers 'Enter/Send' through this action, not a raw keystroke.\n\nIt works only when the field itself declares a send/search action; many comment and reply boxes do not and send only from their own button (WeChat's comment box is one — this errors there, and that is the answer, not a fault). In that case tap the app's Send/发送 button — and on an app that hides its tree, zoom_screen_region on that button FIRST and tap with on=\"zoom\": the button sits in a thin strip right on top of the content below, and the device refuses a plain grid-cell tap right after typing there.",
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "wait_for_screen",
            method = "screen.wait",
            description = "Wait for the screen to finish loading, then observe it. Use this after anything that starts a load — tapping a link, submit_input, launch_app — instead of calling get_screen_element straight away and reading a half-drawn page. It returns the SAME element outline get_screen_element gives you, plus a screenshot, captured after the wait: you already have the fresh state, so do NOT follow it with get_screen_element. One call waits at most 20 seconds (the request itself times out at 30). Ask for what the page plausibly needs — 2-3s for an in-app screen, 5-10s for a slow site or a cold app start.\n\nSomething genuinely slow — a cold app start, an upload, a video that has to transcode — can need more than one wait, and that is fine. But each repeat MUST ASK FOR MORE TIME THAN THE LAST ONE: climb 3 → 5 → 10 → 20, and never send the same number twice in a row. Two reasons, and the second one is fatal: what was not ready after 5s deserves better than another 5s, and a run of calls with byte-identical arguments trips the loop guard, which kills the WHOLE TASK — not just the wait. Sending seconds=10 over and over is the most common way to lose a long job.\n\nEvery wait has to earn its place: read what came back before deciding to wait again. If the outline changed at all, the page is alive — carry on, don't wait more. If you have already climbed to 20s and the screen is still identical, it is not loading, it is stuck or blocked on something (a dialog, a login, no network) — report that or change route. Never wait just to pass time.",
            required = listOf("seconds"),
            props = mapOf(
                "seconds" to prop("number", "How long to wait before looking, in seconds. Values above 20 are reduced to 20; the result reports the time actually waited. On a repeat wait for the same screen this number must be larger than the one you just used."),
            ),
        ),
        tool(
            name = "zoom_screen_region",
            method = "screen.look_region",
            description = """
        [PRECISION AIMING · SCREEN, NOT CAMERA] Despite the method name,
        this is a magnified capture of a screen REGION and has nothing to
        do with the physical camera (camera_turn). A magnified look at ONE region of the screen —
        4x the pixel density of a full screenshot around wherever you point.

        Use it to CHECK an aim before tapping anything not clearly bigger
        than a grid cell (an icon, a ✕ close button, a small chip): pass the
        cell and part you were about to tap — {"cell": "D9", "part":
        "bottom-right"} — and you get a magnified view two cells each way,
        centred on that point, with a RED CROSSHAIR exactly where the tap
        would land. Crosshair on the target → tap with the same cell and
        part. Off it → aim off this image instead (below). Targets often sit
        right on a grid line; this view is centred on the aim, so they are
        never cut in half.

        The other form is x/y marking the region's CENTER plus w/h for its
        size (try 200x300 for one control, 400x600 for a card) — a plain
        magnified look, no crosshair. All four are 0-1000 of the WHOLE SCREEN,
        never pixels of an image you were shown: a screenshot arrives
        downscaled (often ~576 wide as you see it), so a position measured in
        its pixels is the wrong number here. Read the position off the grid
        instead — cell D is 375-500 across, row 7 is 500-583 down, each column
        125 wide and each row ~83 tall — or simply use the cell form above,
        which needs no numbers at all. Every zoom reports the screen rectangle
        it actually showed (`region`); if that is not where you meant to look,
        your numbers were in the wrong space.

        This image is a magnified region, and it carries the SAME 8x12 grid in
        LOWERCASE (a1..h12), labels at each cell's centre, so a label read off
        it can never be confused with a cell of the full screen. Aim your tap
        straight from one of those labels: {"cell": "d7", "part": "top",
        "on": "zoom"} on tap_by_coordinates, and the device
        converts it — no arithmetic. (The old route still works and is
        equivalent: the response carries the region's screen bounds and
        scale_x/scale_y, and a point (px,py) read off the image maps to
        screen-relative (region.left + px/scale_x, region.top + py/scale_y).)

        After a tap that reported screen_changed=false, its miss_shot field
        is exactly this — aim your retry from that image.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the full screenshot you are about to tap, e.g. \"D7\". Replaces x/y/w/h."),
                "part" to propEnum("Which part of that cell — the same part you would tap. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "Region center X, 0 (left) to 1000 (right) of the whole screen — not image pixels. Use instead of cell."),
                "y" to prop("integer", "Region center Y, 0 (top) to 1000 (bottom) of the whole screen — not image pixels. Use instead of cell."),
                "w" to prop("integer", "Region width in screen 0-1000 units, e.g. 200."),
                "h" to prop("integer", "Region height in screen 0-1000 units, e.g. 300."),
            ),
        ),
        tool(
            name = "take_screenshot",
            method = "screen.screenshot",
            description = """
        [FALLBACK · REQUIRES A REASON] Capture the current screen as a PNG
        (downscaled to 1280 on the long side — proportions preserved).

        Every screenshot taken with accessibility on arrives with an 8x12 AIM
        GRID drawn on it, labelled A1 (top-left) to H12 (bottom-right): letters
        along the top edge, numbers down the left edge, nothing inside a cell.
        This is the device's way of
        taking the arithmetic out of aiming: read the cell the target sits in
        and which third of it, and pass them straight to tap_by_coordinates as
        {"cell": "D7", "part": "bottom-right"} — no pixel measuring, no
        rescaling, nothing to get wrong. For anything not clearly bigger than a
        cell, first pass the same cell and part to zoom_screen_region: it shows
        a magenta crosshair where the tap would land, so you can confirm or re-aim
        off the magnified image (its grid is lowercase, and a lowercase label
        means "this was read off the zoom").

        **With accessibility off this is the one screen tool that still works**
        (through MediaProjection, once the user has allowed it) — and then the
        image arrives with NO grid, NO element list and no shot_id. Those three
        all exist to aim a tap, and there are no taps on that path: every other
        screen.* tool answers "accessibility is off". The response states which
        way in it used (`source`); on "media_projection" you may describe what
        you see and must not claim to have acted on it.

        Every screenshot now arrives WITH the current element list attached
        (`elements`, same e-numbers as get_screen_element) whenever the tree
        is readable. When it is attached: aim with tap_id(e) / type(id) from
        THAT list — system-computed and exact. The grid is for elements the
        list does not carry.

        Do NOT use this for finding controls, tapping buttons, or reading
        text — get_screen_element is cheaper and more precise. Only call it
        when:
        1) The content is inherently an image (photo/video/chart/CAPTCHA/map)
        2) get_screen_element returned an empty list (self-drawn pane:
           mini-program, WebView article, game canvas, Moments feed) AND you
           need a FRESH look — the empty response already carried a shot of
           that screen (shot_auto), so use this only after something changed.
           Read the shot and tap by grid cell. Prefer back /
           home over blind taps when unsure.
        3) A human-eye-level visual judgement is required
           (color, overlap, render bug).
        4) You must tell two elements apart by how they LOOK, and the element
           list alone cannot settle it (two unlabeled icons of the same
           class). Prefer get_screen_element with with_shot=true for this —
           it keeps the list and the picture in one response, in one screen
           state. Reach for this tool only when you already hold a current
           list and just need the pixels.

        Never use a screenshot as a "let me take a look first" exploration
        step. After tapping from a screenshot, verify the outcome (fresh
        screenshot or get_screen_element) before assuming it worked.

        A magenta ring with a small caption like "tap 500,500" or "hold 240,880"
        is NOT part of the app — it is where YOUR last tap / long-press
        actually landed, drawn by the device and kept on screen for a few
        seconds. Likewise the grid lines and their A1..H12 labels. Read the
        ring as ground truth for where a number you sent turned out to be on
        this display. Most useful after tap_screen_element (where you never knew the
        pixel location) and after a tap that seemed to do nothing — a ring
        sitting beside the button is a different problem from a ring on a
        button that did not respond.

        YOUR OWN BALL IS NEVER IN A SCREENSHOT. The device hides its floating
        ball and every card of its own for the instant of capture, and moves
        the ball aside when a tap would land under it. So a round shape, a
        face or a blue character in the picture belongs to the app — an
        avatar, a sticker, an article's cover image — and is never you, and
        never something covering a button. If a button seems hidden, scroll
        it into a clearer spot; do not route around the ball.
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "system_action",
            method = "screen.global",
            description = "Trigger an Android system-level navigation action. Use 'back' to dismiss the current screen (also the recommended way to escape a stuck self-drawn page), 'home' to return to the launcher, 'recents' to open the task switcher, 'notifications' to pull down the shade, 'quick_settings' to open toggles. This has nothing to do with keyboard keys — for the on-screen keyboard's Enter / Send / Search use submit_input; to type any character use type_text.",
            required = listOf("action"),
            props = mapOf(
                "action" to propEnum("Which system action to trigger.", listOf("back", "home", "recents", "notifications", "quick_settings")),
            ),
        ),
        tool(
            name = "get_device_status",
            method = "device.connectivity",
            description = "Check whether the device is online and how: returns online (true/false, validated connection), type (wifi / cellular / ethernet / none). Use before anything that needs the internet, or to answer \"are you connected?\".",
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "list_installed_apps",
            method = "device.apps",
            description = "List the launchable apps installed on the device (label + package name) — what a person means by \"what apps are on this tablet\", not the hidden system packages. Useful for \"is X installed?\", \"open something that can do Y\" (find the app, then launch it via the launcher).",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "get_location",
            method = "device.location",
            description = "Get the device's current GPS location: lat, lng, accuracy in meters, and the fix time. Fresh fix takes a few seconds outdoors; indoors it falls back to a cached one (check age_ms). If the app lacks location permission the result says so with a hint — ask the user to grant it once, then retry. Don't call repeatedly in a loop.",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Max wait for a fresh fix. Default 8000.", default = 8000),
            ),
        ),
        tool(
            name = "get_step_count",
            method = "device.steps",
            description = "Read the hardware step counter: steps since last reboot (cumulative, not per-day — say so when reporting). Needs the 'physical activity' permission; if missing the result explains how to get it. Tablets carried in a bag count too.",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Max wait for a sensor reading. Default 3000.", default = 3000),
            ),
        ),
        tool(
            name = "get_battery",
            method = "device.battery",
            description = "Battery level and charging state: percent, charging (bool), plugged (bool). Cheap to call any time.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "launch_app",
            method = "device.launch",
            description = "Launch an app by package name (preferred, e.g. 'com.tencent.mm') or by display label (e.g. '微信' — exact match, use list_installed_apps to find it). Direct launch intent, no screen-tapping needed.",
            required = emptyList(),
            props = mapOf(
                "pkg" to prop("string", "Package name, e.g. 'com.tencent.mm'."),
                "label" to prop("string", "Display label, e.g. '微信'. Used only if pkg is omitted."),
            ),
        ),
        tool(
            name = "set_volume",
            method = "device.volume",
            description = "Read or set media volume (0..max). Omit level/mute to just read the current state.",
            required = emptyList(),
            props = mapOf(
                "level" to prop("integer", "Target volume 0..max. Omit to read only."),
                "mute" to prop("boolean", "true=mute, false=unmute. Omit to leave as-is."),
            ),
        ),
        tool(
            name = "set_brightness",
            method = "device.brightness",
            description = "Read or set screen brightness (1..255). Omit the value to read. Writing needs the WRITE_SETTINGS special grant — the result says so if missing.",
            required = emptyList(),
            props = mapOf(
                "brightness" to prop("integer", "1..255. Omit to read only."),
            ),
        ),
        tool(
            name = "open_url",
            method = "device.open_url",
            description = "Open a URL with the right app: http(s)→browser, tel:→dialer, geo:→map, mailto:→email. Ask the user before opening anything they didn't explicitly request.",
            required = listOf("url"),
            props = mapOf(
                "url" to prop("string", "Full URL including scheme, e.g. 'https://example.com' or 'tel:10086'."),
            ),
        ),
        tool(
            name = "set_alarm",
            method = "device.alarm",
            description = "Set a system alarm (hands off to the clock app, skip-UI). Hour/minute in 24h local time.",
            required = listOf("hour", "minute"),
            props = mapOf(
                "hour" to prop("integer", "0-23."),
                "minute" to prop("integer", "0-59."),
                "message" to prop("string", "Alarm label."),
            ),
        ),
        tool(
            name = "device_facts",
            method = "device.facts",
            description = "Hardware facts: model, brand, Android version, RAM total/available, storage total/available.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "read_sensors",
            method = "device.sensors",
            description = "One-shot environment read: light_lux (room brightness — 0 dark, >300 bright) and accelerometer (was the tablet just moved/picked up).",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Per-sensor wait. Default 1500.", default = 1500),
            ),
        ),
        tool(
            name = "search_contacts",
            method = "device.contacts",
            description = "Search the contact book by name (fuzzy). Returns name + phone. Omit query to list the first 20. Needs Contacts permission; the result says how to get it if missing.",
            required = emptyList(),
            props = mapOf(
                "query" to prop("string", "Name to search for. Omit to list."),
            ),
        ),
        tool(
            name = "dial",
            method = "device.dial",
            description = "Open the dialer with a number filled in (direct=false, safe default) or place the call directly (direct=true, needs Phone permission AND explicit user confirmation — never dial directly on your own initiative).",
            required = listOf("number"),
            props = mapOf(
                "number" to prop("string", "Phone number."),
                "direct" to prop("boolean", "true = call immediately (requires user's explicit go-ahead); false/omit = open dialer for the user to press call.", default = false),
            ),
        ),
        tool(
            name = "get_call_log",
            method = "device.call_log",
            description = "Recent calls: name/number, incoming/outgoing/missed, time, duration. Needs Call log permission.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "read_sms",
            method = "device.sms.read",
            description = "Read SMS: inbox (default) or sent box, newest first. Classic use: fetch the verification code that just arrived — step 4 of the login playbook in list_vault. Needs SMS permission.",
            required = emptyList(),
            props = mapOf(
                "box" to prop("string", "'inbox' (default) or 'sent'.", default = "inbox"),
            ),
        ),
        tool(
            name = "send_sms",
            method = "device.sms.send",
            description = "Send an SMS directly. SENSITIVE: only when the user explicitly asked to send THIS text to THIS number. Say what you're about to send before sending if there is any doubt.",
            required = listOf("number", "body"),
            props = mapOf(
                "number" to prop("string", "Recipient phone number."),
                "body" to prop("string", "Message text."),
            ),
        ),
    )

    /** Lookup: given a tool name, return the method string the dispatcher routes on. */
    fun methodOf(toolName: String): String? =
        TOOLS.firstOrNull { it.getString("name") == toolName }?.optString("method")?.ifEmpty { null }

    // ---- builders ----

    /**
     * @param localOnly true for the brain's private notebook tools: kept in
     *   [all] (the on-device brain sees them) but stripped by [forHub]. Set
     *   explicitly on each of the five rather than inferred from the `note.`
     *   prefix — a tool silently escaping to the hub is not something that
     *   should happen because someone renamed something.
     */
    private fun tool(
        name: String, method: String, description: String,
        required: List<String>, props: Map<String, JSONObject>,
        localOnly: Boolean = false,
    ): JSONObject {
        val propsObj = JSONObject()
        for ((k, v) in props) propsObj.put(k, v)
        val reqArr = JSONArray()
        for (r in required) reqArr.put(r)
        val o = JSONObject()
            .put("name", name)
            .put("method", method)
            .put("description", description)
            .put(
                "parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", propsObj)
                    .put("required", reqArr)
            )
        if (localOnly) o.put("localOnly", true)
        return o
    }

    private fun prop(type: String, description: String, default: Any? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", description)
        if (default != null) o.put("default", default)
        return o
    }

    private fun propEnum(description: String, values: List<String>): JSONObject {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return JSONObject()
            .put("type", "string")
            .put("description", description)
            .put("enum", arr)
    }
}
