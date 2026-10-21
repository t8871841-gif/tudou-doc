import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import 'llm_service.dart';
import 'native_bridge.dart';
import 'settings_store.dart';
import 'weather_service.dart';

/// 快路径新增工具：提醒 / 计时器 / 音量 / 媒体控制 / 勿扰 / 天气 / 定位 /
/// 日程查询 / 通知读取 / 剪贴板 / 晨报。全部本地正则识别，不调大模型。
/// 返回 null = 不是这类指令，交给后面的处理器。
class ToolService {
  static const _numRe = r'(\d+|[零〇一二两三四五六七八九十百]+)';

  static int? cnToInt(String s) {
    s = s.trim();
    if (s.isEmpty) return null;
    if (RegExp(r'^\d+$').hasMatch(s)) return int.tryParse(s);
    const d = {'零': 0, '〇': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4,
      '五': 5, '六': 6, '七': 7, '八': 8, '九': 9};
    var total = 0, cur = 0;
    for (final ch in s.split('')) {
      if (d.containsKey(ch)) {
        cur = d[ch]!;
      } else if (ch == '十') {
        total += (cur == 0 ? 1 : cur) * 10;
        cur = 0;
      } else if (ch == '百') {
        total += (cur == 0 ? 1 : cur) * 100;
        cur = 0;
      } else {
        return null;
      }
    }
    return total + cur;
  }

  static Future<String?> tryHandle(String raw, JarvisSettings settings) async {
    final t = raw.trim().replaceAll(RegExp(r'[。.!！]+$'), '');
    if (t.isEmpty) return null;
    final opening = RegExp(r'^(请|帮我)?(打开|启动|开一下)').hasMatch(t);
    return await _brief(t, settings) ??
        await _reminder(t) ??
        await _timer(t) ??
        await _volume(t) ??
        await _media(t) ??
        await _switches(t) ??
        (opening ? null : await _weather(t, settings)) ??
        await _location(t) ??
        await _calendar(t) ??
        await _notifications(t) ??
        await _clipboard(t);
  }

  // ---------- 时间解析 ----------

  /// 解析“N小时M分钟/半小时/N分钟/N秒”。返回 (时长, 匹配到的原文)。
  static (Duration, String)? _duration(String t) {
    var m = RegExp('半个?小时').firstMatch(t);
    if (m != null) return (const Duration(minutes: 30), m.group(0)!);
    m = RegExp('$_numRe个半小时').firstMatch(t);
    if (m != null) {
      final h = cnToInt(m.group(1)!);
      if (h != null) return (Duration(minutes: h * 60 + 30), m.group(0)!);
    }
    m = RegExp('$_numRe\\s*个?小时(?:$_numRe\\s*分钟?)?').firstMatch(t);
    if (m != null) {
      final h = cnToInt(m.group(1)!);
      final mi = m.group(2) == null ? 0 : cnToInt(m.group(2)!) ?? 0;
      if (h != null) return (Duration(minutes: h * 60 + mi), m.group(0)!);
    }
    m = RegExp('$_numRe\\s*分钟').firstMatch(t);
    if (m != null) {
      final v = cnToInt(m.group(1)!);
      if (v != null) return (Duration(minutes: v), m.group(0)!);
    }
    m = RegExp('$_numRe\\s*秒钟?').firstMatch(t);
    if (m != null) {
      final v = cnToInt(m.group(1)!);
      if (v != null) return (Duration(seconds: v), m.group(0)!);
    }
    return null;
  }

  static String _two(int v) => v.toString().padLeft(2, '0');

  static String _fmt(DateTime d) {
    final now = DateTime.now();
    final day = DateTime(d.year, d.month, d.day)
        .difference(DateTime(now.year, now.month, now.day))
        .inDays;
    final label = day == 0 ? '今天' : day == 1 ? '明天' : day == 2 ? '后天' : '${d.month}月${d.day}日';
    return '$label ${_two(d.hour)}:${_two(d.minute)}';
  }

  static String _cleanContent(String s) => s
      .replaceAll(RegExp(r'^[，,、\s]+|[，,、\s]+$'), '')
      .replaceAll(RegExp(r'^(请|麻烦|帮我|给我|记得|到时候|去|要)+'), '')
      .replaceAll(RegExp(r'^[，,、\s]+'), '')
      .trim();

  // ---------- 提醒 ----------

  static Future<String?> _reminder(String t) async {
    if (RegExp(r'取消.*提醒|清空.*提醒|删除.*提醒').hasMatch(t)) {
      await NativeBridge.cancelAllReminders();
      return '好的，已取消所有待触发的提醒。';
    }
    if (RegExp(r'(有哪些|有什么|查看|看看).{0,4}提醒').hasMatch(t)) {
      final l = await NativeBridge.listReminders();
      if (l.isEmpty) return '目前没有待触发的提醒。';
      final sb = StringBuffer('待触发的提醒：\n');
      for (final r in l.take(8)) {
        final at = DateTime.fromMillisecondsSinceEpoch((r['atMs'] as num).toInt());
        sb.writeln('· ${_fmt(at)}  ${r['body']}');
      }
      return sb.toString().trimRight();
    }
    if (!t.contains('提醒') || t.contains('闹钟')) return null;
    if (RegExp(r'怎么|如何|怎样|为什么').hasMatch(t)) return null;

    final now = DateTime.now();
    DateTime? at;
    var content = t;

    // a) 相对时间：10 分钟后提醒我…
    final dur = _duration(t);
    if (dur != null && RegExp('${RegExp.escape(dur.$2)}\\s*(之后|以后|后)').hasMatch(t)) {
      at = now.add(dur.$1);
      content = t.replaceFirst(RegExp('${RegExp.escape(dur.$2)}\\s*(之后|以后|后)?'), '');
    } else {
      // b) 绝对时间：[明天|后天][上午/下午…]8点[半|30分]
      final m = RegExp('(今天|明天|后天|大后天)?\\s*(凌晨|早上|早晨|上午|中午|下午|晚上|今晚|夜里)?\\s*'
              '$_numRe\\s*[点时:：]\\s*(半|$_numRe\\s*分?)?')
          .firstMatch(t);
      if (m == null) return null;
      final hour0 = cnToInt(m.group(3)!);
      if (hour0 == null || hour0 > 24) return null;
      var hour = hour0;
      final period = m.group(2) ?? '';
      if ((period == '下午' || period == '晚上' || period == '今晚' || period == '夜里') && hour < 12) {
        hour += 12;
      } else if (period == '中午' && hour < 11) {
        hour += 12;
      }
      var minute = 0;
      final mm = m.group(4);
      if (mm == '半') {
        minute = 30;
      } else if (mm != null && mm.isNotEmpty) {
        minute = cnToInt(mm.replaceAll(RegExp(r'分'), '')) ?? 0;
      }
      var day = m.group(1) ?? '';
      if (period == '今晚') day = day.isEmpty ? '今天' : day;
      final add = day == '明天' ? 1 : day == '后天' ? 2 : day == '大后天' ? 3 : 0;
      var d = DateTime(now.year, now.month, now.day + add, hour, minute);
      if (day.isEmpty && !d.isAfter(now)) d = d.add(const Duration(days: 1));
      if (!d.isAfter(now)) return '这个时间已经过去了，请换一个时间。';
      at = d;
      content = t.replaceFirst(m.group(0)!, '');
    }
    content = _cleanContent(content.replaceAll(RegExp(r'提醒我?|叫我|喊我'), ''));
    if (content.isEmpty) content = '时间到了';
    if (!await _ensureNotifyPermission()) {
      return '没有通知权限，提醒无法弹出。请在系统设置里允许 Jarvis 发送通知后再试。';
    }
    final ok = await NativeBridge.scheduleReminder(
        at.millisecondsSinceEpoch ~/ 1000 % 900000000 + 2000,
        at.millisecondsSinceEpoch, 'Jarvis 提醒', content);
    return ok
        ? '好的，${_fmt(at)} 提醒您：$content。'
        : '设置提醒失败了，系统没有接受这个定时。';
  }

  static Future<bool> _ensureNotifyPermission() async {
    final s = await Permission.notification.status;
    if (s.isGranted) return true;
    return (await Permission.notification.request()).isGranted;
  }

  // ---------- 计时器 ----------

  static Future<String?> _timer(String t) async {
    if (!RegExp('计时器|倒计时|计时').hasMatch(t)) return null;
    if (RegExp(r'怎么|如何|怎样|是什么').hasMatch(t)) return null;
    final dur = _duration(t);
    if (dur == null || dur.$1.inSeconds <= 0) return null;
    final secs = dur.$1.inSeconds;
    final ok = await NativeBridge.setTimer(secs, 'Jarvis 计时');
    if (ok) return '好的，已设置${_spoken(dur.$1)}的计时器。';
    // 系统时钟不接受时，用我们自己的定时兜底。
    if (await _ensureNotifyPermission()) {
      final at = DateTime.now().add(dur.$1);
      if (await NativeBridge.scheduleReminder(
          at.millisecondsSinceEpoch ~/ 1000 % 900000000 + 2000,
          at.millisecondsSinceEpoch, 'Jarvis 计时', '计时结束')) {
        return '系统时钟没有响应，已改用 Jarvis 自己的定时：${_spoken(dur.$1)}后通知您。';
      }
    }
    return '设置计时器失败了。';
  }

  static String _spoken(Duration d) {
    final h = d.inHours, m = d.inMinutes % 60, s = d.inSeconds % 60;
    return [if (h > 0) '$h小时', if (m > 0) '$m分钟', if (s > 0 && h == 0) '$s秒'].join('');
  }

  // ---------- 音量 / 媒体 ----------

  static Future<String?> _volume(String t) async {
    final hasVol = t.contains('音量') ||
        RegExp(r'(调|开|关|放|把).{0,4}声音|声音.{0,3}(调|大|小|高|低|最)').hasMatch(t);
    if (!hasVol && !RegExp(r'^(把)?(手机)?(静音|取消静音|解除静音)$').hasMatch(t)) {
      return null;
    }
    if (RegExp(r'怎么|如何|怎样').hasMatch(t)) return null;
    int p;
    final pct = RegExp('(?:调到|设为|设置为|到)\\s*$_numRe\\s*(?:%|％|百分之)?').firstMatch(t);
    if (RegExp('取消静音|解除静音').hasMatch(t)) {
      p = await NativeBridge.volume('unmute');
    } else if (t.contains('静音')) {
      p = await NativeBridge.volume('mute');
    } else if (t.contains('最大') || t.contains('最高')) {
      p = await NativeBridge.volume('max');
    } else if (t.contains('最小') || t.contains('最低')) {
      p = await NativeBridge.volume('min');
    } else if (pct != null && cnToInt(pct.group(1)!) != null) {
      p = await NativeBridge.volume('set', cnToInt(pct.group(1)!)!.clamp(0, 100).toInt());
    } else if (RegExp('大|高|加|增|调响|响一').hasMatch(t)) {
      p = await NativeBridge.volume('up');
    } else if (RegExp('小|低|减|降|轻').hasMatch(t)) {
      p = await NativeBridge.volume('down');
    } else if (t.contains('音量') && RegExp('多少|多大|几').hasMatch(t)) {
      p = await NativeBridge.volume('get');
      return p < 0 ? '读取音量失败。' : '当前媒体音量 $p%。';
    } else {
      return null;
    }
    return p < 0 ? '调音量失败了。' : '好的，现在媒体音量 $p%。';
  }

  static Future<String?> _media(String t) async {
    String? a;
    if (RegExp('下一首|下首歌|切歌|换一首|换首歌').hasMatch(t)) {
      a = 'next';
    } else if (t.contains('上一首') || t.contains('上首歌')) {
      a = 'prev';
    } else if (RegExp(r'^(请|帮我)?(暂停|停止)(播放|音乐|歌曲|一下)?$|别放了|不听了|暂停音乐|停止播放').hasMatch(t)) {
      a = 'pause';
    } else if (RegExp(r'^(请|帮我)?(继续|恢复)(播放|音乐|歌曲)?$|^(请|帮我)?播放(音乐|歌曲)?$').hasMatch(t)) {
      a = 'play';
    }
    if (a == null) return null;
    final ok = await NativeBridge.mediaKey(a);
    const names = {'next': '下一首', 'prev': '上一首', 'pause': '暂停', 'play': '播放'};
    return ok ? '好的，已发送“${names[a]}”。' : '发送媒体按键失败了。';
  }

  // ---------- Wi-Fi / 飞行模式 / 勿扰 ----------

  static Future<String?> _switches(String t) async {
    final m = RegExp(r'(打开|开启|关闭|关掉|关一下|开一下|开|关)\s*(wifi|wi-fi|无线网|无线局域网|飞行模式|勿扰模式|勿扰|免打扰)',
            caseSensitive: false)
        .firstMatch(t);
    if (m == null) return null;
    final on = RegExp('打开|开启|开').hasMatch(m.group(1)!);
    final what = m.group(2)!.toLowerCase();
    if (what.contains('勿扰') || what.contains('免打扰')) {
      final r = await NativeBridge.setDnd(on);
      if (r == 'ok') return on ? '好的，已开启勿扰。' : '好的，已关闭勿扰。';
      if (r == 'need_access') {
        await NativeBridge.openDndAccess();
        return '需要先授权“勿扰权限”，已为您打开设置页，授权后再说一次。';
      }
      return '设置勿扰失败了。';
    }
    if (what.contains('飞行')) {
      await NativeBridge.openPanel('airplane');
      return '系统不允许应用直接切换飞行模式，已为您打开设置页，点一下即可。';
    }
    await NativeBridge.openPanel('wifi');
    return '系统不允许应用直接开关 Wi-Fi，已为您打开网络面板，点一下即可。';
  }

  // ---------- 天气 / 定位 ----------

  static final _cityStop = RegExp(r'^(今天|明天|后天|现在|当前|查一下|查询|查|帮我|看看|告诉我|请问|想知道|请)+');

  static Future<String?> _weather(String t, JarvisSettings s) async {
    if (!RegExp('天气|气温|下雨|下雪|冷不冷|热不热|要带伞|带伞').hasMatch(t)) return null;
    if (RegExp(r'怎么|如何|原理|为什么|预报是怎么').hasMatch(t)) return null;
    String? city;
    final m = RegExp(r'([\u4e00-\u9fa5]{2,6}?)(?:市|区|县)?的?(?:今天|明天|后天)?(?:天气|气温|下雨|下雪)').firstMatch(t);
    if (m != null) {
      var c = m.group(1)!;
      c = c.replaceFirst(_cityStop, '').replaceAll(RegExp(r'(今天|明天|后天|会不会|会|要不要)$'), '');
      if (c.length >= 2 && !RegExp(r'^(今天|明天|后天|现在|当前|这里|本地|当地)$').hasMatch(c)) city = c;
    }
    try {
      if (city != null) return await WeatherService.byCity(city);
      final loc = await _locate();
      if (loc != null) {
        return await WeatherService.byCoords(loc.$1, loc.$2, '您所在位置');
      }
      if (s.homeCity.trim().isNotEmpty) return await WeatherService.byCity(s.homeCity.trim());
      return '我不知道您在哪个城市。请说“北京天气”，或在设置里填一个默认城市。';
    } catch (e) {
      return '查询天气失败：$e';
    }
  }

  static Future<(double, double)?> _locate() async {
    var st = await Permission.locationWhenInUse.status;
    if (!st.isGranted) st = await Permission.locationWhenInUse.request();
    if (!st.isGranted) return null;
    final r = await NativeBridge.getLocation();
    if (r == null || r['lat'] == null || r['lon'] == null) return null;
    return ((r['lat'] as num).toDouble(), (r['lon'] as num).toDouble());
  }

  static Future<String?> _location(String t) async {
    if (!RegExp(r'^(请问)?(我|咱们)(现在)?(在哪|在什么地方|的位置)|当前位置|我的位置|我在哪').hasMatch(t)) return null;
    var st = await Permission.locationWhenInUse.status;
    if (!st.isGranted) st = await Permission.locationWhenInUse.request();
    if (!st.isGranted) return '没有定位权限，请在系统设置里允许 Jarvis 使用位置。';
    final r = await NativeBridge.getLocation();
    if (r == null) return '暂时拿不到位置，请确认已打开定位开关。';
    final place = (r['place'] ?? '').toString();
    final coord = '纬度 ${(r['lat'] as num).toStringAsFixed(4)}，经度 ${(r['lon'] as num).toStringAsFixed(4)}';
    return place.isNotEmpty ? '您现在大约在$place（$coord）。' : '您现在的位置：$coord。';
  }

  // ---------- 日程查询 ----------

  static Future<String?> _calendar(String t) async {
    if (!RegExp(r'日程|行程|安排|会议|日历').hasMatch(t)) return null;
    if (!RegExp(r'有什么|有哪些|有啥|查看|看看|查一下|是什么|什么安排|有没有').hasMatch(t) &&
        !RegExp(r'^(今天|明天|后天|本周|这周)的?(日程|安排|行程)$').hasMatch(t)) {
      return null;
    }
    if (RegExp(r'记|添加|新建|创建|设').hasMatch(t) && RegExp(r'\d|点').hasMatch(t)) return null;
    final now = DateTime.now();
    var start = DateTime(now.year, now.month, now.day);
    var end = start.add(const Duration(days: 1));
    var label = '今天';
    if (t.contains('明天')) {
      start = start.add(const Duration(days: 1));
      end = start.add(const Duration(days: 1));
      label = '明天';
    } else if (t.contains('后天')) {
      start = start.add(const Duration(days: 2));
      end = start.add(const Duration(days: 1));
      label = '后天';
    } else if (RegExp('本周|这周|这几天|接下来').hasMatch(t)) {
      end = start.add(const Duration(days: 7));
      label = '未来七天';
    }
    return calendarText(start, end, label);
  }

  static Future<String> calendarText(DateTime start, DateTime end, String label,
      {bool askPermission = true}) async {
    var st = await Permission.calendar.status;
    if (!st.isGranted && askPermission) st = await Permission.calendar.request();
    if (!st.isGranted) return '没有日历读取权限，请在系统设置里允许 Jarvis 读取日历。';
    final ev = await NativeBridge.queryCalendar(
        start.millisecondsSinceEpoch, end.millisecondsSinceEpoch);
    if (ev == null) return '读取日历失败了。';
    if (ev.isEmpty) return '$label没有日程安排。';
    final sb = StringBuffer('$label有 ${ev.length} 项日程：\n');
    for (final e in ev.take(10)) {
      final b = DateTime.fromMillisecondsSinceEpoch((e['begin'] as num).toInt());
      final when = e['allDay'] == true ? '全天' : '${_two(b.hour)}:${_two(b.minute)}';
      final loc = (e['location'] ?? '').toString();
      sb.writeln('· $when ${e['title']}${loc.isEmpty ? '' : '（$loc）'}');
    }
    return sb.toString().trimRight();
  }

  // ---------- 通知 ----------

  static Future<String?> _notifications(String t) async {
    if (!RegExp(r'通知|未读消息').hasMatch(t)) return null;
    if (!RegExp(r'有什么|有哪些|读|念|看看|查看|总结|说说').hasMatch(t)) return null;
    if (!await NativeBridge.notificationAccessEnabled()) {
      await NativeBridge.openNotificationAccess();
      return '读取通知需要先授权。已为您打开“通知使用权”页面，请打开 Jarvis 的开关后再说一次。';
    }
    return notificationsText();
  }

  static Future<String> notificationsText() async {
    final list = await NativeBridge.notifications(30);
    if (list.isEmpty) return '最近没有新的通知。';
    final sb = StringBuffer('最近 ${list.length > 8 ? 8 : list.length} 条通知：\n');
    for (final n in list.take(8)) {
      final body = '${n['title'] ?? ''} ${n['text'] ?? ''}';
      // 验证码类通知只报来源，不读内容（和看屏引擎不碰验证码的规则一致）。
      final secret = RegExp('验证码|校验码|动态码|密码|code', caseSensitive: false).hasMatch(body);
      sb.writeln(secret ? '· ${n['app']}：一条验证码类通知（内容已隐藏）' : '· ${n['app']}：$body'.trim());
    }
    return sb.toString().trimRight();
  }

  // ---------- 剪贴板 ----------

  static Future<String?> _clipboard(String t) async {
    if (RegExp(r'^(读|念|看看|查看)(一下)?剪贴板|剪贴板(里|中)?(有什么|的内容|内容)').hasMatch(t)) {
      final d = await Clipboard.getData(Clipboard.kTextPlain);
      final s = d?.text?.trim() ?? '';
      return s.isEmpty ? '剪贴板是空的。' : '剪贴板里是：${s.length > 300 ? '${s.substring(0, 300)}…' : s}';
    }
    final m = RegExp(r'^(请|帮我)?(把)?(.+?)(复制到剪贴板|拷贝到剪贴板|放到剪贴板)$').firstMatch(t);
    if (m != null && m.group(3)!.trim().isNotEmpty) {
      await Clipboard.setData(ClipboardData(text: m.group(3)!.trim()));
      return '好的，已复制到剪贴板。';
    }
    return null;
  }

  // ---------- 晨报 ----------

  static Future<String?> _brief(String t, JarvisSettings s) async {
    if (!RegExp(r'^(请|帮我)?(给我)?(来个|播报|说说|念一下)?(今日|今天的|早安|每日)?(早报|晨报|简报)').hasMatch(t) &&
        !RegExp(r'^(播报|说说)(一下)?今天(的)?(安排|情况)$').hasMatch(t)) {
      return null;
    }
    return briefing(s);
  }

  /// 汇总：日期 + 天气 + 今日日程 + 待触发提醒 + 通知条数；再让（本地/云端）模型口语化。
  static Future<String> briefing(JarvisSettings s, {bool askPermission = true}) async {
    final now = DateTime.now();
    final parts = <String>[LlmService.nowLine(now).split('（').first.replaceFirst('当前时间：', '今天是')];
    try {
      final loc = await _locate();
      if (loc != null) {
        parts.add('天气：${await WeatherService.byCoords(loc.$1, loc.$2, '您所在位置')}');
      } else if (s.homeCity.trim().isNotEmpty) {
        parts.add('天气：${await WeatherService.byCity(s.homeCity.trim())}');
      }
    } catch (_) {}
    final start = DateTime(now.year, now.month, now.day);
    parts.add(await calendarText(start, start.add(const Duration(days: 1)), '今天',
        askPermission: askPermission));
    final rem = await NativeBridge.listReminders();
    if (rem.isNotEmpty) parts.add('待触发提醒 ${rem.length} 条');
    if (await NativeBridge.notificationAccessEnabled()) {
      final n = await NativeBridge.notifications(30);
      if (n.isNotEmpty) parts.add('最近有 ${n.length} 条通知未处理');
    }
    final raw = parts.join('\n');
    if (!s.configured && !s.localEnabled) return raw;
    try {
      return await LlmService.ask(
        settings: s,
        question: '把下面的信息整理成一段 120 字以内、口语化的早安播报，不要编造信息里没有的内容：\n$raw',
        history: const [],
        withMemory: true,
        purpose: LlmPurpose.brief,
      );
    } catch (_) {
      return raw;
    }
  }
}
