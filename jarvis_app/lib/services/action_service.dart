import 'package:android_intent_plus/android_intent.dart';
import 'package:device_apps/device_apps.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:url_launcher/url_launcher.dart';

/// Device actions: call / message / alarm / open app.
/// Chinese commands only. Returns a reply string when handled,
/// null when the text is not a device-action command (caller falls
/// through to LLM chat).
///
/// Safety: calls open the dialer (ACTION_DIAL semantics via tel:),
/// messages open the SMS / WhatsApp composer, alarms open the
/// system Clock app to confirm. Nothing is sent silently.
class DeviceActions {
  static final _phoneReg = RegExp(r'1[3-9]\d{9}');

  // Chinese app name -> package name. Domestic apps first.
  static const _appPackages = <String, String>{
    '微信': 'com.tencent.mm',
    '支付宝': 'com.eg.android.AlipayGphone',
    '抖音': 'com.ss.android.ugc.aweme',
    '快手': 'com.smile.gifmaker',
    '淘宝': 'com.taobao.taobao',
    '京东': 'com.jingdong.app.mall',
    '拼多多': 'com.xunmeng.pinduoduo',
    '美团': 'com.sankuai.meituan',
    '饿了么': 'me.ele',
    '高德地图': 'com.autonavi.minimap',
    '高德': 'com.autonavi.minimap',
    '百度地图': 'com.baidu.BaiduMap',
    '滴滴': 'com.sdu.didi.psnger',
    'QQ': 'com.tencent.mobileqq',
    '微博': 'com.sina.weibo',
    '哔哩哔哩': 'tv.danmaku.bili',
    'B站': 'tv.danmaku.bili',
    '知乎': 'com.zhihu.android',
    '小红书': 'com.xingin.xhs',
    '网易云音乐': 'com.netease.cloudmusic',
    'QQ音乐': 'com.tencent.qqmusic',
    '酷狗': 'com.kugou.android',
    '酷我': 'cn.kuwo.player',
    '喜马拉雅': 'com.ximalaya.ting.android',
    '爱奇艺': 'com.qiyi.video',
    '腾讯视频': 'com.tencent.qqlive',
    '优酷': 'com.youku.phone',
    '芒果': 'com.hunantv.imgo.activity',
    '百度': 'com.baidu.searchbox',
    '今日头条': 'com.ss.android.article.news',
    '头条': 'com.ss.android.article.news',
    '闲鱼': 'com.taobao.idlefish',
    '12306': 'com.MobileTicket',
    '铁路12306': 'com.MobileTicket',
    '微信读书': 'com.tencent.weread',
    'WPS': 'cn.wps.moffice_eng',
    '钉钉': 'com.alibaba.android.rimet',
    '飞书': 'com.ss.android.lark',
    '企业微信': 'com.tencent.wework',
    '相机': 'com.android.camera',
    '相册': 'com.android.gallery3d',
    '图库': 'com.android.gallery3d',
    '电话': 'com.android.dialer',
    '拨号': 'com.android.dialer',
    '短信': 'com.android.mms',
    '信息': 'com.android.mms',
    '通讯录': 'com.android.contacts',
    '联系人': 'com.android.contacts',
    '设置': 'com.android.settings',
    '日历': 'com.android.calendar',
    '时钟': 'com.android.deskclock',
    '闹钟': 'com.android.deskclock',
    '计算器': 'com.android.calculator2',
    '音乐': 'com.android.music',
    '浏览器': 'com.android.browser',
    '地图': 'com.autonavi.minimap',
    '邮件': 'com.android.email',
    '邮箱': 'com.android.email',
    '文件': 'com.android.documentsui',
    '文件管理': 'com.android.documentsui',
    '应用商店': 'com.android.vending',
    'WhatsApp': 'com.whatsapp',
    'Telegram': 'org.telegram.messenger',
    'Instagram': 'com.instagram.android',
    'Facebook': 'com.facebook.katana',
    'YouTube': 'com.google.android.youtube',
    'Gmail': 'com.google.android.gm',
    'Chrome': 'com.android.chrome',
    'Spotify': 'com.spotify.music',
    'Netflix': 'com.netflix.mediaclient',
  };

  static Future<String?> tryHandle(String text) async {
    final t = text.trim();
    if (t.isEmpty) return null;
    return await _tryCall(t) ??
        await _tryMessage(t) ??
        await _tryAlarm(t) ??
        await _tryOpenApp(t);
  }

  // ---------- Call ----------

  static Future<String?> _tryCall(String t) async {
    if (!t.contains('打电话') &&
        !t.contains('拨打') &&
        !t.contains('打给') &&
        !t.contains('呼叫')) {
      return null;
    }
    final phone = _phoneReg.firstMatch(t)?.group(0);
    if (phone == null) {
      return '要打电话请带上号码，例如：打电话给 13812345678。';
    }
    await Permission.phone.request();
    final uri = Uri(scheme: 'tel', path: phone);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
      return '已打开拨号盘，号码 $phone，点拨打即可接通。';
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

    final phone = _phoneReg.firstMatch(t)?.group(0);
    // Message body: text after 给/发短信/发消息/发微信/发WhatsApp, minus phone.
    var body = t
        .replaceAll(_phoneReg, '')
        .replaceAll(RegExp(r'^(给|帮我给|请给)'), '')
        .replaceAll(RegExp(r'(发短信|发短消息|发信息|发消息|发微信|发WhatsApp|WhatsApp)'), '')
        .replaceAll(RegExp(r'[给：:，,。 ]+'), ' ')
        .trim();
    // If the name is still in there (e.g. 给张三发短信你好), drop the first
    // 1-4 chars name segment when body still looks like "张三 你好".
    if (body.length > 2 && body.contains(' ')) {
      final parts = body.split(' ');
      if (parts.length >= 2 && parts[0].length <= 4) {
        body = parts.sublist(1).join(' ').trim();
      }
    }
    if (phone == null) {
      return '要发消息请带上号码，例如：给 13812345678 发短信你好。';
    }
    if (body.isEmpty) {
      return '消息内容为空，请说：给 $phone 发短信 + 内容。';
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
      // WeChat has no public compose intent; fall back to SMS composer
      // and tell the user clearly instead of pretending.
      return '微信没有公开的直接发消息接口，已帮你改用短信。说“给 $phone 发短信 $body”即可。';
    }
    await Permission.sms.request();
    final uri = Uri(
        scheme: 'sms', path: phone, queryParameters: {'body': body});
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
      return '已打开短信，收件人 $phone，内容已填好。';
    }
    return '打不开短信，请检查短信权限。';
  }

  // ---------- Alarm ----------

  static Future<String?> _tryAlarm(String t) async {
    if (!t.contains('闹钟') &&
        !t.contains('定个闹') &&
        !t.contains('设个闹') &&
        !t.contains('提醒我')) {
      return null;
    }
    // Parse: [明天|后天] [早上|上午|中午|下午|晚上|凌晨] N点 [M分]
    var dayOffset = 0;
    if (t.contains('后天')) {
      dayOffset = 2;
    } else if (t.contains('明天')) {
      dayOffset = 1;
    }
    var period = '';
    for (final p in ['凌晨', '早上', '上午', '中午', '下午', '晚上', '今晚']) {
      if (t.contains(p)) {
        period = p;
        break;
      }
    }
    final timeMatch =
        RegExp(r'(\d{1,2})\s*[点时:：]\s*(\d{1,2})?\s*[分]?').firstMatch(t);
    if (timeMatch == null) {
      return '要设闹钟请带上时间，例如：明天早上 7 点闹钟。';
    }
    var hour = int.parse(timeMatch.group(1)!);
    final minute = int.parse(timeMatch.group(2) ?? '0');
    if ((period == '下午' || period == '晚上' || period == '今晚') &&
        hour < 12) {
      hour += 12;
    }
    if (period == '凌晨' && hour == 12) hour = 0;
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
    String? name;
    for (final key in _appPackages.keys) {
      if (t.contains(key)) {
        name = key;
        break;
      }
    }
    if (name == null) {
      // "打开XXX" with unknown XXX: let the LLM answer instead of failing.
      return null;
    }
    final pkg = _appPackages[name]!;
    try {
      final installed = await DeviceApps.isAppInstalled(pkg);
      if (installed) {
        await DeviceApps.openApp(pkg);
        return '已打开 $name。';
      }
      // Fallback: Play store search.
      final uri = Uri.parse(
          'https://play.google.com/store/search?q=${Uri.encodeComponent(name)}&c=apps');
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
        return '$name 似乎没安装，已打开应用商店搜索页。';
      }
      return '$name 似乎没安装。';
    } catch (_) {
      return '打开 $name 失败，请确认已安装。';
    }
  }
}
