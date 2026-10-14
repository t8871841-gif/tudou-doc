import 'package:android_intent_plus/android_intent.dart';
import 'package:flutter/services.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:url_launcher/url_launcher.dart';

/// Device actions: call / message / read SMS / alarm / open app.
/// Chinese commands only. Returns a reply string when handled,
/// null when the text is not a device-action command (caller falls
/// through to LLM chat).
///
/// Safety: calls open the dialer (ACTION_DIAL semantics via tel:),
/// messages open the SMS composer (WeChat has no public compose
/// intent, so WeChat requests fall back to opening the SMS composer
/// with the body prefilled), alarms open the system Clock app to
/// confirm, SMS reading only reads the inbox and speaks it back.
/// Nothing is sent silently.
class DeviceActions {
  static final _phoneReg = RegExp(r'1[3-9]\d{9}');
  static const _smsChannel = MethodChannel('jarvis/sms');

  // Chinese app name -> candidate package names, tried in order.
  // System apps: AOSP name first, then Flyme/Meizu equivalents
  // (Flyme renames several system apps). A failed candidate just
  // falls through to the next one.
  static const _appPackages = <String, List<String>>{
    '微信': ['com.tencent.mm'],
    '支付宝': ['com.eg.android.AlipayGphone'],
    '抖音': ['com.ss.android.ugc.aweme'],
    '快手': ['com.smile.gifmaker'],
    '淘宝': ['com.taobao.taobao'],
    '京东': ['com.jingdong.app.mall'],
    '拼多多': ['com.xunmeng.pinduoduo'],
    '美团': ['com.sankuai.meituan'],
    '饿了么': ['me.ele'],
    '高德地图': ['com.autonavi.minimap'],
    '高德': ['com.autonavi.minimap'],
    '百度地图': ['com.baidu.BaiduMap'],
    '滴滴': ['com.sdu.didi.psnger'],
    'QQ': ['com.tencent.mobileqq'],
    '微博': ['com.sina.weibo'],
    '哔哩哔哩': ['tv.danmaku.bili'],
    'B站': ['tv.danmaku.bili'],
    '知乎': ['com.zhihu.android'],
    '小红书': ['com.xingin.xhs'],
    '网易云音乐': ['com.netease.cloudmusic'],
    'QQ音乐': ['com.tencent.qqmusic'],
    '酷狗': ['com.kugou.android'],
    '酷我': ['cn.kuwo.player'],
    '喜马拉雅': ['com.ximalaya.ting.android'],
    '爱奇艺': ['com.qiyi.video'],
    '腾讯视频': ['com.tencent.qqlive'],
    '优酷': ['com.youku.phone'],
    '芒果': ['com.hunantv.imgo.activity'],
    '百度': ['com.baidu.searchbox'],
    '今日头条': ['com.ss.android.article.news'],
    '头条': ['com.ss.android.article.news'],
    '闲鱼': ['com.taobao.idlefish'],
    '12306': ['com.MobileTicket'],
    '铁路12306': ['com.MobileTicket'],
    '微信读书': ['com.tencent.weread'],
    'WPS': ['cn.wps.moffice_eng'],
    '钉钉': ['com.alibaba.android.rimet'],
    '飞书': ['com.ss.android.lark'],
    '企业微信': ['com.tencent.wework'],
    '相机': ['com.meizu.media.camera', 'com.android.camera'],
    '相册': ['com.meizu.media.gallery', 'com.android.gallery3d'],
    '图库': ['com.meizu.media.gallery', 'com.android.gallery3d'],
    '电话': ['com.meizu.flyme.dialer', 'com.android.dialer'],
    '拨号': ['com.meizu.flyme.dialer', 'com.android.dialer'],
    '短信': ['com.meizu.flyme.mms', 'com.android.mms'],
    '信息': ['com.meizu.flyme.mms', 'com.android.mms'],
    '通讯录': ['com.meizu.flyme.contacts', 'com.android.contacts'],
    '联系人': ['com.meizu.flyme.contacts', 'com.android.contacts'],
    '设置': ['com.meizu.settings', 'com.android.settings'],
    '日历': ['com.meizu.flyme.calendar', 'com.android.calendar'],
    '时钟': ['com.meizu.flyme.calendar', 'com.android.deskclock'],
    '闹钟': ['com.meizu.flyme.calendar', 'com.android.deskclock'],
    '计算器': ['com.meizu.flyme.calculator', 'com.android.calculator2'],
    '音乐': ['com.meizu.media.music', 'com.android.music'],
    '浏览器': ['com.meizu.media.browser', 'com.android.browser'],
    '地图': ['com.autonavi.minimap'],
    '邮件': ['com.meizu.flyme.email', 'com.android.email'],
    '邮箱': ['com.meizu.flyme.email', 'com.android.email'],
    '文件': ['com.meizu.filemanager', 'com.android.documentsui'],
    '文件管理': ['com.meizu.filemanager', 'com.android.documentsui'],
    '应用商店': ['com.meizu.mstore', 'com.android.vending'],
    'WhatsApp': ['com.whatsapp'],
    'Telegram': ['org.telegram.messenger'],
    'Instagram': ['com.instagram.android'],
    'Facebook': ['com.facebook.katana'],
    'YouTube': ['com.google.android.youtube'],
    'Gmail': ['com.google.android.gm'],
    'Chrome': ['com.android.chrome'],
    'Spotify': ['com.spotify.music'],
    'Netflix': ['com.netflix.mediaclient'],
  };

  static Future<String?> tryHandle(String text) async {
    final t = text.trim();
    if (t.isEmpty) return null;
    return await _tryCall(t) ??
        await _tryMessage(t) ??
        await _tryReadSms(t) ??
        await _tryAlarm(t) ??
        await _tryOpenApp(t);
  }

  // ---------- Contacts ----------

  static String _normalizePhone(String raw) {
    var d = raw.replaceAll(RegExp(r'\D'), '');
    if (d.startsWith('0086')) d = d.substring(4);
    if (d.length == 13 && d.startsWith('86')) d = d.substring(2);
    return d;
  }

  /// Find the contact whose display name appears in [text].
  /// Longest matching name wins ("微信读书" beats "微信", "张小明" beats "张").
  /// Returns null when contacts permission is denied or no name matches.
  static Future<Contact?> _findContactInText(String text) async {
    try {
      final ok = await FlutterContacts.requestPermission();
      if (!ok) return null;
      final contacts =
          await FlutterContacts.getContacts(withProperties: true);
      final flat = text.replaceAll(' ', '');
      Contact? best;
      var bestLen = 0;
      for (final c in contacts) {
        final name = c.displayName.trim();
        if (name.isEmpty) continue;
        final flatName = name.replaceAll(' ', '');
        if (flatName.isNotEmpty &&
            flat.contains(flatName) &&
            flatName.length > bestLen) {
          best = c;
          bestLen = flatName.length;
        }
      }
      return best;
    } catch (_) {
      return null;
    }
  }

  static String? _contactPhone(Contact c) {
    for (final p in c.phones) {
      final d = _normalizePhone(p.number);
      if (_phoneReg.hasMatch(d)) return d;
    }
    return null;
  }

  /// Resolve a target phone number from text: raw 11-digit number first,
  /// then a contact name found in the text. Returns (phone, contactName).
  /// contactName is null when the number came from raw digits.
  static Future<({String? phone, String? name})> _resolveTarget(
      String t) async {
    final raw = _phoneReg.firstMatch(t)?.group(0);
    if (raw != null) return (phone: raw, name: null);
    final contact = await _findContactInText(t);
    if (contact == null) return (phone: null, name: null);
    final phone = _contactPhone(contact);
    if (phone == null) return (phone: null, name: contact.displayName);
    return (phone: phone, name: contact.displayName);
  }

  // ---------- Call ----------

  static Future<String?> _tryCall(String t) async {
    if (!t.contains('打电话') &&
        !t.contains('拨打') &&
        !t.contains('打给') &&
        !t.contains('呼叫')) {
      return null;
    }
    final target = await _resolveTarget(t);
    final phone = target.phone;
    if (phone == null) {
      if (target.name != null) {
        return '通讯录里 ${target.name} 没有手机号，请带上号码，例如：打电话给 13812345678。';
      }
      return '要打电话请带上号码或通讯录里的名字，例如：打电话给张三，或打电话给 13812345678。';
    }
    try {
      final uri = Uri(scheme: 'tel', path: phone);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
      final who = target.name != null ? '${target.name} $phone' : phone;
      return '已打开拨号盘，号码 $who，点拨打即可接通。';
    }
    return '打不开拨号盘，请检查电话权限。';
  }

  // ---------- Message ----------

  static Future<String?> _tryMessage(String t) async {
    final isSms = t.contains('发短信') ||
        t.contains('发短消息') ||
        t.contains('发信息') ||
        (t.contains('发消息') && !t.contains('微信'));
    final isWechat = t.contains('发微信');
    final isWhatsapp =
        t.toLowerCase().contains('whatsapp') || t.contains('发WhatsApp');
    if (!isSms && !isWechat && !isWhatsapp) return null;

    final target = await _resolveTarget(t);
    final phone = target.phone;
    if (phone == null) {
      if (target.name != null) {
        return '通讯录里 ${target.name} 没有手机号，请带上号码，例如：给 13812345678 发短信你好。';
      }
      return '要发消息请带上号码或通讯录里的名字，例如：给张三发短信你好，或给 13812345678 发短信你好。';
    }
    // Message body: text minus phone, contact name, keywords, fillers.
    var body = t
        .replaceAll(_phoneReg, '')
        .replaceAll(RegExp(r'^(给|帮我给|请给)'), '')
        .replaceAll(RegExp(r'(发短信|发短消息|发信息|发消息|发微信|发WhatsApp|WhatsApp)'), '')
        .replaceAll(RegExp(r'[给：:，,。 ]+'), ' ')
        .replaceAll(RegExp(r'^(说|讲|告诉他|告诉她|告诉)'), '')
        .trim();
    if (target.name != null) {
      body = body.replaceAll(target.name!, '').trim();
    }
    // Drop a leftover 1-4 char name segment when body still looks like
    // "张三 你好".
    if (body.length > 2 && body.contains(' ')) {
      final parts = body.split(' ');
      if (parts.length >= 2 && parts[0].length <= 4) {
        body = parts.sublist(1).join(' ').trim();
      }
    }
    if (body.isEmpty) {
      final who = target.name ?? phone;
      return '消息内容为空，请说：给 $who 发短信 + 内容。';
    }
    if (isWhatsapp) {
      final uri = Uri.parse(
          'https://wa.me/$phone?text=${Uri.encodeComponent(body)}');
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
        return '已打开 WhatsApp，收件人 $phone，内容已填好。';
      }
      return '打不开 WhatsApp，请确认已安装。';
    }
    if (isWechat) {
      // WeChat has no public compose intent. Open the SMS composer with
      // the body prefilled instead of leaving the user with nothing.
      final uri = Uri(
          scheme: 'sms', path: phone, queryParameters: {'body': body});
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri);
        return '微信没有直接发消息接口，已帮你打开短信，收件人 $phone，内容已填好，在短信里发就行。';
      }
      return '微信没有直接发消息接口，短信也打不开，请检查短信权限。';
    }
    final uri = Uri(
        scheme: 'sms', path: phone, queryParameters: {'body': body});
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
      return '已打开短信，收件人 $phone，内容已填好。';
    }
    return '打不开短信，请检查短信权限。';
  }

  // ---------- Read SMS ----------

  static Future<String?> _tryReadSms(String t) async {
    final isRead = t.contains('读短信') ||
        t.contains('看短信') ||
        t.contains('查看短信') ||
        t.contains('念短信') ||
        t.contains('读一下短信') ||
        t.contains('有什么短信') ||
        t.contains('读信息') ||
        t.contains('看信息');
    if (!isRead) return null;
    // READ_SMS is only requested here. Call/message only open the
    // dialer/composer and never needed it.
    final status = await Permission.sms.request();
    if (!status.isGranted) {
      return '读短信需要短信权限，请在设置里允许 Jarvis 读取短信后再试。';
    }
    try {
      final raw = await _smsChannel.invokeMethod('getInboxSms', {'limit': 5});
      final messages = (raw as List).cast<Map>();
      if (messages.isEmpty) return '收件箱里没有短信。';
      final buf = StringBuffer('最近 ${messages.length} 条短信：');
      for (var i = 0; i < messages.length; i++) {
        final m = messages[i];
        var body = (m['body'] as String? ?? '').replaceAll('\n', ' ').trim();
        if (body.length > 80) body = '${body.substring(0, 80)}…';
        final date = DateTime.fromMillisecondsSinceEpoch(
            (m['date'] as int? ?? 0));
        final when =
            '${date.month}月${date.day}日 ${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}';
        buf.write('\n${i + 1}. ${m['address'] ?? ''}（$when）：$body');
      }
      return buf.toString();
    } on PlatformException {
      return '读短信失败，请检查短信权限。';
    } catch (_) {
      return '读短信失败，请检查短信权限。';
    }
  }

  // ---------- Alarm ----------

  static const _cnDigits = <String, int>{
    '零': 0,
    '一': 1,
    '二': 2,
    '两': 2,
    '三': 3,
    '四': 4,
    '五': 5,
    '六': 6,
    '七': 7,
    '八': 8,
    '九': 9,
  };

  /// Parse a Chinese numeral (七 / 十二 / 二十三) to int. Null if invalid.
  static int? _parseCnNumber(String s) {
    if (s.isEmpty) return null;
    var total = 0;
    var current = 0;
    var seen = false;
    for (final ch in s.split('')) {
      if (ch == '十') {
        total += (current == 0 ? 1 : current) * 10;
        current = 0;
        seen = true;
      } else if (_cnDigits.containsKey(ch)) {
        current = _cnDigits[ch]!;
        seen = true;
      } else {
        return null;
      }
    }
    total += current;
    return seen ? total : null;
  }

  /// Convert Chinese-numeral times to Arabic digits so voice input like
  /// "明天早上七点半" / "十二点一刻" can be parsed.
  static String _normalizeTimeText(String t) {
    var out = t;
    out = out.replaceAllMapped(RegExp(r'[零一二两三四五六七八九十]+\s*[点时]'),
        (m) {
      final numPart = m.group(0)!.replaceAll(RegExp(r'\s*[点时]$'), '');
      final n = _parseCnNumber(numPart);
      return n == null ? m.group(0)! : '${n}点';
    });
    out = out.replaceAllMapped(RegExp(r'[零一二两三四五六七八九十]+\s*分'), (m) {
      final numPart = m.group(0)!.replaceAll(RegExp(r'\s*分$'), '');
      final n = _parseCnNumber(numPart);
      return n == null ? m.group(0)! : '${n}分';
    });
    out = out
        .replaceAll('点半', '点30分')
        .replaceAll('一刻', '15分')
        .replaceAll('三刻', '45分');
    return out;
  }

  static Future<String?> _tryAlarm(String t) async {
    if (!t.contains('闹钟') &&
        !t.contains('定个闹') &&
        !t.contains('设个闹') &&
        !t.contains('提醒我')) {
      return null;
    }
    final nt = _normalizeTimeText(t);
    // Parse: [明天|后天] [早上|上午|中午|下午|晚上|凌晨|今晚|夜里|半夜] N点 [M分]
    var dayOffset = 0;
    if (nt.contains('后天')) {
      dayOffset = 2;
    } else if (nt.contains('明天')) {
      dayOffset = 1;
    }
    var period = '';
    for (final p in ['凌晨', '早上', '上午', '中午', '下午', '晚上', '今晚', '夜里', '半夜']) {
      if (nt.contains(p)) {
        period = p;
        break;
      }
    }
    final timeMatch =
        RegExp(r'(\d{1,2})\s*[点时:：钟]\s*(\d{1,2})?\s*[分]?').firstMatch(nt);
    if (timeMatch == null) {
      return '要设闹钟请带上时间，例如：明天早上 7 点闹钟。';
    }
    var hour = int.parse(timeMatch.group(1)!);
    final minute = int.parse(timeMatch.group(2) ?? '0');
    if ((period == '下午' ||
            period == '晚上' ||
            period == '今晚' ||
            period == '夜里') &&
        hour < 12) {
      hour += 12;
    }
    if ((period == '凌晨' || period == '半夜') && hour == 12) hour = 0;
    if (hour > 23 || minute > 59) {
      return '时间不对，请说一个有效时间，例如：早上 7 点 30 分闹钟。';
    }
    var target = DateTime.now().add(Duration(days: dayOffset));
    target = DateTime(target.year, target.month, target.day, hour, minute);
    if (dayOffset == 0 && target.isBefore(DateTime.now())) {
      target = target.add(const Duration(days: 1));
    }
    final label = dayOffset == 0
        ? 'Jarvis 闹钟'
        : dayOffset == 1
            ? 'Jarvis 明天闹钟'
            : 'Jarvis 后天闹钟';
    try {
      final intent = AndroidIntent(
        action: 'android.intent.action.SET_ALARM',
        arguments: <String, dynamic>{
          'android.intent.extra.alarm.MESSAGE': label,
          'android.intent.extra.alarm.HOUR': target.hour,
          'android.intent.extra.alarm.MINUTES': target.minute,
          'android.intent.extra.alarm.SKIP_UI': false,
        },
      );
      await intent.launch();
      final hh = target.hour.toString().padLeft(2, '0');
      final mm = target.minute.toString().padLeft(2, '0');
      return '已打开系统闹钟，时间 ${target.month}月${target.day}日 $hh:$mm，点确认即可生效。';
    } catch (_) {
      return '打不开系统闹钟，请检查时钟应用。';
    }
  }

  // ---------- Open app ----------

  static Future<String?> _tryOpenApp(String t) async {
    if (!t.contains('打开') && !t.contains('启动') && !t.contains('开一下')) {
      return null;
    }
    // Longest app name first: "微信读书" must beat "微信",
    // "QQ音乐" must beat "QQ", "企业微信" must beat "微信".
    final keys = _appPackages.keys.toList()
      ..sort((a, b) => b.length.compareTo(a.length));
    String? name;
    for (final key in keys) {
      if (t.contains(key)) {
        name = key;
        break;
      }
    }
    if (name == null) {
      // "打开XXX" with unknown XXX: let the LLM answer instead of failing.
      return null;
    }
    for (final pkg in _appPackages[name]!) {
      try {
        final intent = AndroidIntent(
          action: 'android.intent.action.MAIN',
          package: pkg,
          category: 'android.intent.category.LAUNCHER',
        );
        await intent.launch();
        return '已打开 $name。';
      } catch (_) {
        // Try the next candidate package.
      }
    }
    return '$name 似乎没安装。';
  }
}
