import 'package:android_intent_plus/android_intent.dart';
import 'package:flutter/services.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:url_launcher/url_launcher.dart';

/// Device actions: call / message / cross-app message / read SMS / alarm / open app.
/// Chinese commands only. Returns a reply string when handled,
/// null when the text is not a device-action command (caller falls
/// through to LLM chat).
///
/// 安全与诚实原则：
///  - 打电话：先复述确认，用户确认后才真拨（ACTION_CALL）；拨出后查通话记录核实；
///    真拨失败时回落到打开拨号盘，让用户手动点拨打；
///  - 微信 / QQ 等跨应用发消息：先复述确认，用户确认后才执行；
///    由原生无障碍引擎在“白名单 App”里一步步操作（每步等界面就绪，超时即失败），
///    发完回查界面核实，核实成功才说“已发送”，否则如实说“没发成 / 没能确认”和卡在哪一步；
///  - 不做：转账 / 付款 / 红包、输入密码 / 验证码、删除内容、发朋友圈 / 动态、
///    点赞 / 评论 / 关注、修改账号设置；
///  - 闹钟只打开系统时钟让用户确认；读短信只读收件箱并朗读。
///
/// 跨应用状态：确认发送 / 选第几个联系人，靠 [_pending] 在下一次 [tryHandle] 里接上，
/// 所以调用方必须让“用户的下一句话”也先经过 tryHandle（见 [hasPending]）。
class DeviceActions {
  static final _phoneReg = RegExp(r'1[3-9]\d{9}');
  static final _mobileFull = RegExp(r'^1[3-9]\d{9}$');
  static const _smsChannel = MethodChannel('jarvis/sms');
  static const _launcherChannel = MethodChannel('jarvis/launcher');
  static const _accessibilityChannel =
      MethodChannel('com.example.jarvis/accessibility');
  // Agent 通道：真拨电话（ACTION_CALL）+ 查通话记录核实 + 看屏 / 通用动作。
  static const _agentChannel = MethodChannel('com.jarvis.jarvis_app/agent');

  static Future<bool> _isAccessibilityEnabled() async {
    try {
      return await _accessibilityChannel.invokeMethod<bool>(
              'isAccessibilityEnabled') ??
          false;
    } catch (_) {
      return false;
    }
  }

  /// 跳转系统无障碍设置，供用户手动开启 Jarvis 跨应用全自动引擎。
  static Future<void> openAccessibilitySettings() async {
    try {
      await _accessibilityChannel.invokeMethod('openAccessibilitySettings');
    } catch (_) {}
  }

  /// 跳转本应用的系统设置页（用来开通讯录权限）。UI 想做“去开权限”按钮时直接调这个。
  static Future<void> openContactsPermissionSettings() async {
    try {
      await openAppSettings();
    } catch (_) {}
  }

  /// 是否有“等用户回答”的挂起操作（确认发送 / 选联系人）。
  /// 调用方应保证：hasPending 为 true 时，用户的下一句话一定先交给 [tryHandle]。
  static bool get hasPending {
    final p = _pending;
    return p != null && DateTime.now().isBefore(p.expires);
  }

  // Chinese app name -> candidate package names, tried in order.
  // System apps: AOSP name first, then Flyme/Meizu equivalents
  // (Flyme renames several system apps). A failed candidate just
  // falls through to the next one.
  static const _appPackages = <String, List<String>>{
    '微信': ['com.tencent.mm'],
    '支付宝': ['com.eg.android.AlipayGphone'],
    '抖音': ['com.ss.android.ugc.aweme'],
    '抖音极速版': ['com.ss.android.ugc.aweme.lite'],
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
    'Salt Player': ['com.salt.music'],
    '盐播放器': ['com.salt.music'],
    '天气': ['com.meizu.weather', 'com.meizu.flyme.weather'],
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
    '音乐': ['com.salt.music', 'com.meizu.media.music', 'com.android.music'],
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


  // ======================================================================
  // 入口
  // ======================================================================

  static bool _busy = false;
  static _Pending? _pending;

  static Future<String?> tryHandle(String text) async {
    final t = text.trim();
    if (t.isEmpty) return null;

    // 上一轮留下的“确认发送 / 选第几个联系人”：先取走，只给一次机会。
    final p = _pending;
    if (p != null) {
      _pending = null;
      if (DateTime.now().isBefore(p.expires)) {
        final r = await _handlePending(p, t);
        if (r != null) return r;
        // 既不是确认也不是取消：旧的作废，当作新指令继续往下走
      }
    }
    // 顺序有讲究：先判“发消息”，再判“打电话”——
    // 否则消息正文里带“打电话”（“发信息说我打电话给你”）会被当成拨号。
    // “拒绝类”放发消息/打电话之后：只在不是合法发消息/打电话指令时才判拒绝，
    // 否则消息正文里的“点赞/验证码”会被误判（“发消息说别点赞了”）。
    return await _tryMessage(t) ??
        await _tryCall(t) ??
        await _tryRefuse(t) ??
        await _tryReadSms(t) ??
        await _tryAlarm(t) ??
        await _tryFlashlight(t) ??
        await _tryLocalSearch(t) ??
        await _tryOpenApp(t);
  }

  // ---------- 挂起操作：确认 / 选人 ----------

  static final _yesReg = RegExp(
      r'^(对|对的|对啊|对呀|是|是的|是啊|没错|正确|确认|确定|好|好的|好吧|好啊|可以|行|行的|发吧|发送|发|嗯|嗯嗯|ok|yes)$',
      caseSensitive: false);
  static final _noReg = RegExp(r'^(不|不对|不是|不用|不要|不发|取消|算了|别发|别|错了|否|no)',
      caseSensitive: false);

  static String _trimPunct(String s) => s
      .replaceAll(RegExp(r'^[\s，。！？,.!?~、]+'), '')
      .replaceAll(RegExp(r'[\s，。！？,.!?~、]+$'), '');

  static Future<String?> _handlePending(_Pending p, String raw) async {
    final s = _trimPunct(raw);
    if (_noReg.hasMatch(s)) {
      return p.cands == null ? '好的，已取消，没有发送。' : '好的，已取消。';
    }
    if (p.cands == null) {
      if (_yesReg.hasMatch(s)) return await p.run(null);
      return null;
    }
    final idx = _pickIndex(s, p.cands!);
    if (idx == null) return null;
    return await p.run(p.cands![idx]);
  }

  static int? _pickIndex(String s, List<_Cand> cands) {
    final m = RegExp(r'^第?([0-9]+|[零一二两三四五六七八九十]+)(?:个|位|号|条)?$')
        .firstMatch(s);
    if (m != null) {
      final g = m.group(1)!;
      final n = int.tryParse(g) ?? _parseCnNumber(g);
      if (n != null && n >= 1 && n <= cands.length) return n - 1;
      return null;
    }
    // 直接说名字 / 尾号也行：必须唯一命中才算，绝不擅自挑第一个
    final hits = <int>[];
    for (var i = 0; i < cands.length; i++) {
      if (s.isNotEmpty &&
          (cands[i].label.contains(s) || s.contains(cands[i].name))) {
        hits.add(i);
      }
    }
    return hits.length == 1 ? hits.first : null;
  }

  // ---------- 明确不做的事：直说，别答应了又没做 ----------

  static Future<String?> _tryRefuse(String t) async {
    final bad = RegExp(
        r'转账|汇款|付款|发红包|抢红包|发朋友圈|发动态|点赞|刷赞|评论|关注|删除.{0,6}(聊天|消息|好友|联系人)|验证码|密码');
    final question = RegExp(r'怎么|如何|怎样|什么|为什么|吗|\?|？');
    if (bad.hasMatch(t) && _detectApp(t) != null && !question.hasMatch(t)) {
      return '这类操作（转账、红包、发朋友圈/动态、点赞评论关注、删除内容、验证码和密码）我不做，也没有动手。我只能帮你给联系人发消息。';
    }
    return null;
  }

  // ======================================================================
  // 通讯录
  // ======================================================================

  static String _normalizePhone(String raw) {
    var d = raw.replaceAll(RegExp(r'\D'), '');
    if (d.startsWith('0086')) d = d.substring(4);
    if (d.length == 13 && d.startsWith('86')) d = d.substring(2);
    return d;
  }

  static String _flat(String s) =>
      s.replaceAll(RegExp(r'[\s，。,.！？!?、：:“”"]'), '');

  static const _compoundSurnames = <String>[
    '欧阳', '司马', '上官', '诸葛', '东方', '皇甫', '尉迟', '公孙',
    '慕容', '令狐', '长孙', '宇文', '轩辕', '夏侯', '司徒', '端木',
  ];

  /// 去掉姓后的“名”。只处理纯中文名；两字名去姓后只剩一个字，太短，不按名匹配。
  static String _givenName(String name) {
    if (!RegExp(r'^[\u4e00-\u9fa5]+$').hasMatch(name)) return '';
    for (final s in _compoundSurnames) {
      if (name.startsWith(s) && name.length > 2) return name.substring(2);
    }
    return name.length >= 3 ? name.substring(1) : '';
  }

  /// 取联系人号码：11 位手机号优先；打电话时没有手机号也接受其他号码（座机 / 客服号）。
  static String? _contactPhone(Contact c, {required bool mobileOnly}) {
    String? fallback;
    for (final p in c.phones) {
      final d = _normalizePhone(p.number);
      if (_mobileFull.hasMatch(d)) return d;
      if (!mobileOnly && d.length >= 5) fallback ??= d;
    }
    return fallback;
  }

  /// 按“名字”查通讯录。匹配分三档，哪一档先有结果就用哪一档：
  ///  1. 全名；2. 名（去掉姓，如“小明”匹配“张小明”）；3. 名字里包含这几个字。
  /// 只拿“收件人 / 被叫人名字”去匹配，不再拿整句话（正文里的人名会串进来）。
  static Future<_Lookup> _lookupContact(String query,
      {required bool forCall}) async {
    try {
      var st = await Permission.contacts.status;
      if (!st.isGranted && !st.isLimited) {
        st = await Permission.contacts.request();
      }
      if (!st.isGranted && !st.isLimited) {
        return const _Lookup(_LookupKind.denied);
      }
      final q = _flat(query);
      if (q.isEmpty) return const _Lookup(_LookupKind.none);
      final contacts = await FlutterContacts.getContacts(withProperties: true);
      final t1 = <Contact>[], t2 = <Contact>[], t3 = <Contact>[];
      for (final c in contacts) {
        final name = _flat(c.displayName);
        if (name.isEmpty) continue;
        if (name == q || (name.length >= 2 && q.contains(name))) {
          t1.add(c);
          continue;
        }
        final given = _givenName(name);
        if (given.isNotEmpty &&
            (given == q || (given.length >= 2 && q.contains(given)))) {
          t2.add(c);
          continue;
        }
        if (q.length >= 2 && name.contains(q)) t3.add(c);
      }
      final tier = t1.isNotEmpty ? t1 : (t2.isNotEmpty ? t2 : t3);
      if (tier.isEmpty) return const _Lookup(_LookupKind.none);
      final cands = <_Cand>[];
      final seen = <String>{};
      String? noNumName;
      for (final c in tier) {
        final phone = _contactPhone(c, mobileOnly: !forCall);
        if (phone == null) {
          noNumName ??= c.displayName;
          continue;
        }
        if (seen.add('${c.displayName}|$phone')) {
          cands.add(_Cand(c.displayName.trim(), phone));
        }
      }
      if (cands.isEmpty) {
        return _Lookup(_LookupKind.noNumber, name: noNumName);
      }
      return cands.length == 1
          ? _Lookup(_LookupKind.one, cands: cands)
          : _Lookup(_LookupKind.many, cands: cands);
    } catch (_) {
      return const _Lookup(_LookupKind.error);
    }
  }

  /// 通讯录权限被拒：明说，并直接跳到应用设置，不再静默回落到“请带上号码”。
  static Future<String> _contactsDeniedReply() async {
    await openContactsPermissionSettings();
    return '通讯录权限被拒绝，所以我没法按名字找人。已为你打开应用设置，请在“权限”里允许 Jarvis 读取通讯录，然后再说一次。';
  }

  /// 按名字找人并交给 [onPick]。多个匹配时列出来让用户选，绝不擅自选第一个。
  static Future<String> _withContact(
    String query, {
    required bool forCall,
    required Future<String> Function(_Cand c) onPick,
  }) async {
    final r = await _lookupContact(query, forCall: forCall);
    switch (r.kind) {
      case _LookupKind.denied:
        return await _contactsDeniedReply();
      case _LookupKind.error:
        return '读取通讯录失败，请稍后再试，或者直接说号码。';
      case _LookupKind.none:
        return '通讯录里没找到“$query”，请换个叫法，或者直接说号码。';
      case _LookupKind.noNumber:
        return '通讯录里的 ${r.name} 没有${forCall ? '' : '手机'}号码，请直接说号码。';
      case _LookupKind.one:
        return await onPick(r.cands!.first);
      case _LookupKind.many:
        _pending = _Pending(r.cands, (c) => onPick(c!));
        final buf = StringBuffer('通讯录里有 ${r.cands!.length} 个匹配，请说第几个：');
        for (var i = 0; i < r.cands!.length; i++) {
          buf.write('\n${i + 1}. ${r.cands![i].label}');
        }
        return buf.toString();
    }
  }

  // ======================================================================
  // 打电话
  // ======================================================================

  static final _callTrigger =
      RegExp(r'打个?电话|拨个?电话|拨打|打给|呼叫|拨个?号|给.{1,12}?[打拨]个?电话');

  static String _parseCallTarget(String t) {
    var q = RegExp(r'给(.{1,12}?)[打拨]个?电话').firstMatch(t)?.group(1);
    q ??= RegExp(r'(?:打个?电话|拨个?电话|拨打|打给|呼叫|拨个?号)(?:给|到)?(.*)$')
            .firstMatch(t)
            ?.group(1) ??
        '';
    q = q
        .replaceAll(RegExp(r'^(请|帮我|麻烦|我要|我想|帮忙)+'), '')
        .replaceAll(RegExp(r'(的电话|的号码|的手机|电话|一下|吧|啊|谢谢|好吗)+$'), '');
    return _flat(q).replaceAll(RegExp(r'的$'), '');
  }

  static Future<String?> _tryCall(String t) async {
    if (!_callTrigger.hasMatch(t)) return null;
    // 说了号码优先用号码（语音识别常带空格 / 横线，先去掉再找）
    final raw =
        _phoneReg.firstMatch(t.replaceAll(RegExp(r'[\s\-]'), ''))?.group(0);
    if (raw != null) return await _dial(raw, null);
    final q = _parseCallTarget(t);
    if (q.isEmpty) return '打给谁？请说名字或号码，例如：给张三打个电话。';
    return await _withContact(q,
        forCall: true, onPick: (c) => _dial(c.phone, c.name));
  }

  /// 查到人 / 拿到号码后，先复述确认；用户确认后才真拨（ACTION_CALL）。
  /// 拨出后查通话记录核实，核实结果如实汇报。
  static Future<String> _dial(String phone, String? name) async {
    final who = name != null ? '$name $phone' : phone;
    _pending = _Pending(null, (_) => _directDial(phone, name));
    return '准备给 $who 拨打电话，确认请回复“确认”，取消请回复“取消”。';
  }

  /// 真拨出。失败时回落到打开拨号盘（旧行为），让用户手动点拨打。
  static Future<String> _directDial(String phone, String? name) async {
    final who = name != null ? '$name $phone' : phone;
    final ok = await _makeDirectCall(phone);
    if (!ok) {
      final uri = Uri(scheme: 'tel', path: phone);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri);
        return '直接拨号失败，已打开拨号盘，号码 $who，点拨打即可接通。';
      }
      return '拨号失败：既拨不出去，也打不开拨号盘，请检查电话权限。';
    }
    // 拨出去了：查通话记录核实。没权限时如实说无法核实，不谎称失败。
    await Future.delayed(const Duration(seconds: 2));
    final logged = await _checkCallLog(phone);
    if (logged == null) {
      return '已给 $who 拨出电话（通话记录权限未授予，无法核实）。';
    }
    if (logged) return '已给 $who 拨出电话，通话记录已确认。';
    return '已给 $who 拨出电话，未在通话记录里找到（可能对方未接通或记录有延迟）。';
  }

  static Future<bool> _makeDirectCall(String phone) async {
    try {
      final ok =
          await _agentChannel.invokeMethod<bool>('makeCall', {'phoneNumber': phone});
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 返回 null = 没有通话记录权限（或查询失败）；true/false = 查到 / 没查到。
  static Future<bool?> _checkCallLog(String phone) async {
    try {
      final res = await _agentChannel
          .invokeMethod<Map>('checkCallLog', {'phoneNumber': phone});
      if (res == null) return null;
      if (res['granted'] != true) return null;
      return res['found'] == true;
    } catch (_) {
      return null;
    }
  }

  // ======================================================================
  // 发消息：识别 App / 收件人 / 正文
  // ======================================================================

  static const _channelWords =
      '微信|QQ|qq|短信|短消息|信息|消息|WhatsApp|whatsapp|Whatsapp';
  static const _lead = r'(?:请|麻烦|帮我|帮忙|替我|我要|我想|你)*';
  static const _fill = r'(?:了|个|条|一条|一下|封)?';

  /// 找出这句话要用哪个 App：取“最先出现”的那个，
  /// 这样“给张三发短信说我在微信上等你”不会被正文里的“微信”带偏。
  static String? _detectApp(String s) {
    final low = s.toLowerCase();
    String? best;
    var bestIdx = 1 << 30;
    var bestLen = 0;
    void consider(String alias, String id) {
      final i = low.indexOf(alias.toLowerCase());
      if (i < 0) return;
      if (i < bestIdx || (i == bestIdx && alias.length > bestLen)) {
        best = id;
        bestIdx = i;
        bestLen = alias.length;
      }
    }

    for (final a in _adapters) {
      for (final al in a.aliases) {
        consider(al, a.id);
      }
    }
    consider('whatsapp', 'whatsapp');
    consider('短信', 'sms');
    consider('短消息', 'sms');
    return best;
  }

  /// 所有 App 名字拼成正则备选（长名在前，“企业微信”先于“微信”）。
  static String _appAlternation() {
    final names = <String>{'WhatsApp', 'whatsapp', '短信', '短消息'};
    for (final a in _adapters) {
      names.addAll(a.aliases);
    }
    final list = names.toList()..sort((x, y) => y.length.compareTo(x.length));
    return list.map(RegExp.escape).join('|');
  }

  static _Adapter? _adapterById(String id) {
    for (final a in _adapters) {
      if (a.id == id) return a;
    }
    return null;
  }

  static String _cleanBody(String b) {
    var s = b.replaceFirst(RegExp(r'^[，,：:、\s]+'), '');
    s = s.replaceFirst(RegExp(r'^(?:说|讲|道|告诉他|告诉她|告诉)(?=.)'), '');
    s = s.replaceFirst(RegExp(r'^[，,：:、\s]+'), '');
    return s.replaceFirst(RegExp(r'[。\s]+$'), '').trim();
  }

  static String _cleanRecipient(String n) =>
      _flat(n).replaceAll(RegExp(r'^我的'), '').replaceAll(RegExp(r'的$'), '');

  /// “收件人+正文”粘在一起时拆开：优先标点 / “说”做分界；都没有就取前 2 个字当名字。
  /// 拆错了也不要紧：发送前一定会复述确认。
  static (String, String)? _splitNameBody(String rest0) {
    // 开头是手机号（语音识别常把号码拆成“138 1234 5678”）：号码就是收件人
    final rest = rest0.replaceAll(RegExp(r'(?<=\d)[\s\-](?=\d)'), '');
    final ph = RegExp(r'^(1[3-9]\d{9})(.*)$').firstMatch(rest);
    if (ph != null) return (ph.group(1)!, ph.group(2)!);
    final p1 = RegExp(r'^(.{1,10}?)[，,：:、\s]+(.+)$').firstMatch(rest);
    if (p1 != null) return (p1.group(1)!, p1.group(2)!);
    final p2 =
        RegExp(r'^(.{1,6}?)(?:说|讲|道|告诉他|告诉她)[，,：:\s]*(.+)$').firstMatch(rest);
    if (p2 != null) return (p2.group(1)!, p2.group(2)!);
    if (rest.length > 3) return (rest.substring(0, 2), rest.substring(2));
    return null;
  }

  static _MsgIntent? _parseMessageIntent(String s0, String? app) {
    // 去掉开头的“在微信上 / 用QQ / 通过短信”
    final s = s0.replaceFirst(
        RegExp(r'^' +
            _lead +
            r'(?:在|用|通过|打开|切到)?(?:' +
            _appAlternation() +
            r')(?:上|里|中|里面|app|App)?[，,\s]*'),
        '');
    // 没点名 App 时，必须出现“微信/短信/信息/消息…”这类词才算发消息，避免误伤“给我发个红包”。
    final chan = app != null ? '(?:$_channelWords)?' : '(?:$_channelWords)';

    // A：给<名字>发/留言<微信/信息…>[，说]<正文>
    final a = RegExp('^$_lead' +
            r'给(.{1,12}?)(?:发(?:送)?|留言)' +
            _fill +
            chan +
            r'[，,：:\s]*(?:说|讲|道|告诉他|告诉她|告诉)?[，,：:\s]*(.*)$')
        .firstMatch(s);
    if (a != null) {
      return _MsgIntent(
          _cleanRecipient(a.group(1)!), _cleanBody(a.group(2) ?? ''));
    }

    // B：发/留言<微信/信息…>给<名字>[，说]<正文>
    final b = RegExp('^$_lead' + r'(?:发(?:送)?|留言)' + _fill + chan + r'(?:给|到)(.+)$')
        .firstMatch(s);
    if (b != null) {
      final nb = _splitNameBody(b.group(1)!);
      if (nb == null) return _MsgIntent(_cleanRecipient(b.group(1)!), '');
      return _MsgIntent(_cleanRecipient(nb.$1), _cleanBody(nb.$2));
    }
    return null;
  }

  static Future<String?> _tryMessage(String t) async {
    final s0 = t.replaceAll(RegExp(r'\s+'), ' ').trim();
    final app = _detectApp(s0);
    final intent = _parseMessageIntent(s0, app);
    if (intent == null) {
      // 明明是发消息的口气却没解析出来：直说，不要漏给闲聊、更不要假装办了。
      // 疑问句（“怎么在微信上发消息”）不是指令：别问“发给谁”，交给闲聊回答。
      final isQuestion =
          RegExp(r'怎么|如何|怎样|什么|为什么|吗|\?|？').hasMatch(s0);
      if (!isQuestion &&
          RegExp(r'(?:发(?:送)?|留言)' + _fill + r'(?:' + _channelWords + r')')
              .hasMatch(s0)) {
        return '要发给谁、发什么内容？例如：在微信上给张三发消息，晚上我回家吃饭。';
      }
      return null;
    }
    if (intent.name.isEmpty || intent.body.isEmpty) {
      final where = app == null ? '' : (_adapterById(app)?.label ?? '');
      return intent.name.isEmpty
          ? '要发给谁？例如：在微信上给张三发消息，晚上我回家吃饭。'
          : '要给$where${intent.name}发什么内容？请连内容一起说，例如：给${intent.name}发消息，晚上我回家吃饭。';
    }

    final key = app ?? 'sms'; // 没点名 App：保持原有行为，按短信处理
    final adapter = _adapterById(key);
    if (adapter != null) {
      if (!adapter.enabled) {
        return '目前还没适配“${adapter.label}”的自动发消息，所以我没有动手，也没有发出任何内容。目前能自动发的有：微信、QQ、短信、WhatsApp。';
      }
      return await _confirmCrossApp(adapter, intent.name, intent.body);
    }

    // 短信 / WhatsApp：号码优先，否则按名字查通讯录
    final wa = key == 'whatsapp';
    final raw = _phoneReg
        .firstMatch(intent.name.replaceAll(RegExp(r'[\s\-]'), ''))
        ?.group(0);
    if (raw != null) return await _sendDirect(wa, raw, null, intent.body);
    return await _withContact(intent.name,
        forCall: false, onPick: (c) => _sendDirect(wa, c.phone, c.name, intent.body));
  }

  // ======================================================================
  // 跨应用发消息（微信 / QQ …）
  // ======================================================================

  /// 每个 App 一个适配器，登记在这里。要扩展新 App：写一个 _Adapter 子类，加进列表即可，
  /// 同时在原生 JarvisAccessibilityService.HARD_WHITELIST 里有意识地加上包名。
  static final List<_Adapter> _adapters = <_Adapter>[
    _WeChatAdapter(),
    _QqAdapter(),
    // 以下只是预留（enabled=false）：没有实现，也不在原生白名单里，
    // 被问到时会如实说“还没适配”，不会假装办了。
    const _ReservedAdapter('wework', '企业微信', ['企业微信'], 'com.tencent.wework'),
    const _ReservedAdapter('douyin', '抖音', ['抖音'], 'com.ss.android.ugc.aweme'),
    const _ReservedAdapter('taobao', '淘宝', ['淘宝'], 'com.taobao.taobao'),
    const _ReservedAdapter('alipay', '支付宝', ['支付宝'], 'com.eg.android.AlipayGphone'),
    const _ReservedAdapter('weibo', '微博', ['微博'], 'com.sina.weibo'),
    const _ReservedAdapter('xhs', '小红书', ['小红书'], 'com.xingin.xhs'),
    const _ReservedAdapter('bilibili', '哔哩哔哩', ['哔哩哔哩', 'B站'], 'tv.danmaku.bili'),
  ];

  static Future<String> _confirmCrossApp(
      _Adapter a, String name, String body) async {
    if (!await _isAccessibilityEnabled()) {
      return await _openAndHandOver(a, name, body,
          '没开启无障碍自动化，没法替你在${a.label}里操作（系统设置 > 无障碍 > 开启“Jarvis 跨应用全自动引擎”后即可全自动发送）。这次没发成。');
    }
    // 发送前复述确认：用户说“对 / 确认”才执行
    _pending = _Pending(null, (_) => _runCrossApp(a, name, body));
    return '给${a.label}上的$name发：“$body”，对吗？说“对”或“确认”我就发，说“取消”就不发。';
  }

  static Future<bool> _openPackage(String pkg) async {
    try {
      return await _launcherChannel
              .invokeMethod<bool>('openApp', {'package': pkg}) ??
          false;
    } catch (_) {
      return false;
    }
  }

  /// 失败降级：打开 App，内容复制到剪贴板，交给用户手动发。绝不“答应了但什么都没做”。
  static Future<String> _openAndHandOver(
      _Adapter a, String name, String body, String reason) async {
    await Clipboard.setData(ClipboardData(text: body));
    final opened = await _openPackage(a.package);
    if (!opened) return '$reason ${a.label} 似乎没安装。';
    return '$reason 已打开${a.label}，内容已复制到剪贴板，请手动找到$name，粘贴后点发送。';
  }

  static Future<String> _runCrossApp(_Adapter a, String name, String body) async {
    if (_busy) return '上一个跨应用任务还在执行，请稍等它结束再说。';
    _busy = true;
    try {
      final steps = a.steps(name, body);
      final start = await _startTask(a.package, steps, 45000);
      if (start != 'ok') {
        return await _openAndHandOver(a, name, body, _startFailText(start));
      }
      // 必须在拉起目标 App 之前武装（上面已经做了）
      if (!await _openPackage(a.package)) {
        await _cancelTask();
        return '${a.label} 似乎没安装，没发成。';
      }
      final res = await _awaitTask(const Duration(seconds: 60));
      return await _describeResult(res, steps,
          appLabel: a.label,
          sentPhrase: '给${a.label}上的$name发了',
          manualTarget: name,
          body: body);
    } finally {
      _busy = false;
    }
  }

  static String _startFailText(String code) {
    switch (code) {
      case 'busy':
        return '上一个自动任务还没结束，这次没发成。';
      case 'no_service':
        return '无障碍服务没有在运行（刚开启的话请稍等几秒，或重新开关一次），这次没发成。';
      default:
        return '自动化没能启动（$code），这次没发成。';
    }
  }

  // ---------- 与原生引擎通信 ----------

  static Future<String> _startTask(
      String? pkg, List<Map<String, dynamic>> steps, int windowMs) async {
    try {
      return await _accessibilityChannel.invokeMethod<String>('startTask', {
            'packages': pkg == null ? <String>[] : <String>[pkg],
            'steps': steps,
            'windowMs': windowMs,
          }) ??
          'error';
    } catch (_) {
      return 'error';
    }
  }

  static Future<void> _cancelTask() async {
    try {
      await _accessibilityChannel.invokeMethod('cancelTask');
    } catch (_) {}
  }

  static Future<Map<dynamic, dynamic>?> _awaitTask(Duration guard) async {
    try {
      return await _accessibilityChannel
          .invokeMethod<Map<dynamic, dynamic>>('awaitTask')
          .timeout(guard, onTimeout: () async {
        await _cancelTask();
        return <dynamic, dynamic>{
          'status': 'timeout',
          'step': -1,
          'stepName': '等待结果',
          'detail': '等待超时',
        };
      });
    } catch (_) {
      return null;
    }
  }

  /// 把引擎结果翻成人话。核实成功才说“已发送”；否则如实说没发成 / 没能确认，以及卡在哪一步。
  static Future<String> _describeResult(
    Map<dynamic, dynamic>? raw,
    List<Map<String, dynamic>> steps, {
    required String appLabel,
    required String sentPhrase,
    required String manualTarget,
    required String body,
    bool prefilled = false, // 短信 / WhatsApp：内容是随 intent 预填好的
  }) async {
    final status = (raw?['status'] as String?) ?? 'error';
    final idx = (raw?['step'] as num?)?.toInt() ?? -1;
    final stepName = (raw?['stepName'] as String?) ?? '';
    final detail = (raw?['detail'] as String?) ?? '';
    final hint = (idx >= 0 && idx < steps.length)
        ? ((steps[idx]['hint'] as String?) ?? '')
        : '';
    switch (status) {
      case 'sent':
        return '已发送：$sentPhrase“$body”，并且已在聊天界面核实这条消息出现了。';
      case 'unverified':
        return '没能确认已发送（卡在“$stepName”）：我点了发送，但没能在界面上核实到这条消息，可能发出了也可能没发出，请你打开$appLabel看一眼。';
      case 'blocked':
        return '为安全起见我停下了，没有继续：$detail。';
      case 'cancelled':
        return '任务被取消了，没有发送。';
      default:
        final filledIdx = steps.indexWhere((s) => s['fills'] == true);
        final filled = prefilled || (filledIdx >= 0 && idx > filledIdx);
        if (filled) {
          return '自动发送失败，已帮你打开并填好，请手动点发送。（卡在“$stepName”：$hint）';
        }
        await Clipboard.setData(ClipboardData(text: body));
        return '没发成，卡在“$stepName”：$hint 已打开$appLabel，内容也复制到剪贴板了，可以手动找到$manualTarget后粘贴发送。';
    }
  }

  // ======================================================================
  // 短信 / WhatsApp
  // ======================================================================

  static Future<String> _sendDirect(
      bool wa, String phone, String? name, String body) async {
    final who = name != null ? '$name $phone' : phone;
    final label = wa ? 'WhatsApp' : '短信';
    final uri = wa
        ? Uri.parse('https://wa.me/$phone?text=${Uri.encodeComponent(body)}')
        : Uri(scheme: 'sms', path: phone, queryParameters: {'body': body});
    if (!await canLaunchUrl(uri)) {
      return wa ? '打不开 WhatsApp，请确认已安装，这次没发成。' : '打不开短信，请检查短信权限，这次没发成。';
    }
    Future<void> open() => wa
        ? launchUrl(uri, mode: LaunchMode.externalApplication)
        : launchUrl(uri);

    if (!await _isAccessibilityEnabled()) {
      await open();
      return '已打开$label，收件人 $who，内容已填好。没开启无障碍自动发送，需要你手动点发送（系统设置 > 无障碍 > 开启“Jarvis 跨应用全自动引擎”后可自动发送）。这次还没有发出。';
    }
    final steps = <Map<String, dynamic>>[
      _step('send', '点发送', '没找到可点的发送按钮', timeoutMs: 15000, settleMs: 600),
      _step('verifyText', '核实消息已发出', '点了发送，但没能在界面上看到这条消息',
          text: body, timeoutMs: 7000, soft: true),
    ];
    if (_busy) return '上一个跨应用任务还在执行，请稍等它结束再说。';
    _busy = true;
    try {
      final start = await _startTask(null, steps, 30000);
      if (start != 'ok') {
        await open();
        return '${_startFailText(start)} 已打开$label，收件人 $who，内容已填好，请手动点发送。';
      }
      await open();
      final res = await _awaitTask(const Duration(seconds: 40));
      return await _describeResult(res, steps,
          appLabel: label,
          sentPhrase: '给$who发了',
          manualTarget: who,
          body: body,
          prefilled: true);
    } finally {
      _busy = false;
    }
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
      return n == null ? m.group(0)! : '$n点';
    });
    out = out.replaceAllMapped(RegExp(r'[零一二两三四五六七八九十]+\s*分'), (m) {
      final numPart = m.group(0)!.replaceAll(RegExp(r'\s*分$'), '');
      final n = _parseCnNumber(numPart);
      return n == null ? m.group(0)! : '$n分';
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
            period == '夜里' ||
            period == '中午') &&
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

  // ---------- 慢路径预开目标 App ----------

  /// 慢路径启动前快开目标 App（直接拉起，秒开，不进慢循环）：
  /// 只认白名单 App（与原生 HARD_WHITELIST 一致），长名优先（“微信读书”先于“微信”）。
  /// 打开成功返回 true；找不到 App 名 / 打开失败返回 false（调用方继续慢路径）。
  static const _slowTargetApps = <String, List<String>>{
    '微信': ['com.tencent.mm'],
    'QQ': ['com.tencent.mobileqq'],
    'WhatsApp': ['com.whatsapp'],
    '支付宝': ['com.eg.android.AlipayGphone'],
    '高德地图': ['com.autonavi.minimap'],
    '高德': ['com.autonavi.minimap'],
    '抖音极速版': ['com.ss.android.ugc.aweme.lite'],
    '天气': ['com.meizu.weather', 'com.meizu.flyme.weather'],
    'Salt Player': ['com.salt.music'],
    '盐播放器': ['com.salt.music'],
    '音乐': ['com.salt.music'],
    '电话': ['com.meizu.flyme.dialer', 'com.android.dialer'],
    '短信': ['com.meizu.flyme.mms', 'com.android.mms'],
  };

  static Future<bool> openAppForTask(String text) async {
    final keys = _slowTargetApps.keys.toList()
      ..sort((a, b) => b.length.compareTo(a.length));
    for (final key in keys) {
      if (!text.contains(key)) continue;
      for (final pkg in _slowTargetApps[key]!) {
        if (await _openPackage(pkg)) return true;
      }
      // 找到了 App 名但没打开（可能没装）：别继续匹配更短的名字，避免开错 App。
      return false;
    }
    // 任务关键词 → App：导航 / 路线 → 高德地图。
    if (text.contains('导航') || text.contains('路线')) {
      for (final pkg in _slowTargetApps['高德地图']!) {
        if (await _openPackage(pkg)) return true;
      }
    }
    return false;
  }

  // ---------- 手电筒 ----------

  static Future<String?> _tryFlashlight(String t) async {
    if (!t.contains('手电') && !t.contains('电筒') && !t.contains('闪光灯')) {
      return null;
    }
    // 疑问句（“手电筒怎么开”）不是指令：交给闲聊回答。
    if (RegExp(r'怎么|如何|怎样|什么|为什么|吗|\?|？').hasMatch(t)) return null;
    final off =
        t.contains('关闭') || t.contains('关掉') || t.contains('关手电') || t.contains('灭');
    final enable = !off;
    // 开手电需要相机权限（CameraManager.setTorchMode 要求 CAMERA）。
    var st = await Permission.camera.status;
    if (!st.isGranted) st = await Permission.camera.request();
    if (!st.isGranted) {
      return '开手电需要相机权限，请在设置里允许 Jarvis 使用相机后再试。';
    }
    try {
      final ok = await _launcherChannel
          .invokeMethod<bool>('setTorch', {'enabled': enable});
      if (ok == true) return enable ? '手电筒已打开。' : '手电筒已关闭。';
    } catch (_) {}
    return '手电筒${enable ? '打开' : '关闭'}失败，请检查相机权限或设备是否带闪光灯。';
  }

  // ---------- 本地搜索：图片 / 文档 / 音乐 / 视频 ----------

  static Future<String?> _tryLocalSearch(String t) async {
    // 必须有“搜 / 找” + 类型词；“打开文件管理”这类交给 _tryOpenApp。
    if (!t.contains('搜') && !t.contains('找')) return null;
    if (t.contains('打开') || t.contains('启动') || t.contains('开')) return null;
    if (RegExp(r'怎么|如何|怎样|什么|为什么|吗|\?|？').hasMatch(t)) return null;
    String? kind;
    List<String> kindWords;
    if (t.contains('视频') || t.contains('录像') || t.contains('影片')) {
      kind = 'video';
      kindWords = ['视频', '录像', '影片'];
    } else if (t.contains('图片') || t.contains('照片') || t.contains('相片')) {
      kind = 'image';
      kindWords = ['图片', '照片', '相片'];
    } else if (t.contains('音乐') || t.contains('歌曲') || t.contains('音频')) {
      kind = 'audio';
      kindWords = ['音乐', '歌曲', '音频'];
    } else if (t.contains('文档') || t.contains('文件') || t.contains('资料')) {
      kind = 'document';
      kindWords = ['文档', '文件', '资料'];
    } else {
      return null;
    }
    final label =
        kind == 'video' ? '视频' : kind == 'image' ? '图片' : kind == 'audio' ? '音乐' : '文档';
    final keyword = _extractSearchKeyword(t, kindWords);
    // 媒体权限：文档无专属权限，直接查（MediaStore 只返回能看见的）。
    Permission? perm;
    if (kind == 'image') perm = Permission.photos;
    if (kind == 'video') perm = Permission.videos;
    if (kind == 'audio') perm = Permission.audio;
    if (perm != null) {
      var st = await perm.status;
      if (!st.isGranted) st = await perm.request();
      if (!st.isGranted) return '搜本地$label需要相册 / 媒体权限，请在设置里允许后再试。';
    }
    try {
      final raw = await _launcherChannel.invokeMethod<List>(
          'searchLocalMedia', {'kind': kind, 'keyword': keyword});
      final items = (raw ?? []).cast<Map>();
      if (items.isEmpty) {
        return keyword.isEmpty
            ? '手机上没有$label。'
            : '没找到“$keyword”相关的$label。';
      }
      final buf = StringBuffer(
          '找到 ${items.length} 个$label${keyword.isEmpty ? '（按最新排序）' : '（含“$keyword”）'}：');
      for (var i = 0; i < items.length; i++) {
        final m = items[i];
        final name = (m['name'] as String?) ?? '';
        final date = DateTime.fromMillisecondsSinceEpoch(
            (m['date'] as int? ?? 0));
        final when = '${date.month}月${date.day}日';
        final size = _formatSize((m['size'] as int? ?? 0));
        final extra = (m['extra'] as String?) ?? '';
        buf.write('\n${i + 1}. $name（$when，$size）${extra.isEmpty ? '' : ' $extra'}');
      }
      return buf.toString();
    } catch (_) {
      return '本地搜索失败，请稍后再试。';
    }
  }

  /// 去掉“搜 / 找 / 手机上 / 一下 / 我的”等套话和类型词，剩下的是关键词。
  /// 没关键词 = 列最新的。
  static String _extractSearchKeyword(String t, List<String> kindWords) {
    var s = t;
    for (final w in ['搜一下', '搜索', '查找', '搜', '找', '一下', '手机上', '手机里', '手机中', '本地', '帮我', '请', '给我', '我的', '一张', '一个', '一份', '一首', '一段', '一条', '这个', '那个', '一些', '所有']) {
      s = s.replaceAll(w, '');
    }
    for (final w in kindWords) {
      s = s.replaceAll(w, '');
    }
    s = s
        .replaceAll(RegExp(r'^[的在里上中了\s]+'), '')
        .replaceAll(RegExp(r'[的\s]+$'), '')
        .replaceAll(RegExp(r'[\s，。,.！？!?、：:]+'), '')
        .trim();
    return s;
  }

  static String _formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(0)} KB';
    return '${(bytes / 1024 / 1024).toStringAsFixed(1)} MB';
  }

  // ---------- Open app ----------

  static Future<String?> _tryOpenApp(String t) async {
    // Bare "开" also counts: "开微信" must trigger, not just 打开/启动/开一下.
    if (!t.contains('打开') &&
        !t.contains('启动') &&
        !t.contains('开一下') &&
        !t.contains('开')) {
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
      // 不在内置表里：只有明确说了“打开 / 启动 / 开一下”才按名字匹配已安装 App；
      // 光一个“开”（如“开车去…”）不是打开 App，别误判。
      if (!t.contains('打开') && !t.contains('启动') && !t.contains('开一下')) {
        return null;
      }
      final raw = _extractOpenAppName(t);
      if (raw == null) return null;
      try {
        final ok = await _launcherChannel.invokeMethod<bool>(
          'openAppByName',
          {'name': raw},
        );
        if (ok == true) return '已打开 $raw。';
      } catch (_) {}
      return '$raw 似乎没安装。';
    }
    for (final pkg in _appPackages[name]!) {
      try {
        // Launch via the native getLaunchIntentForPackage channel.
        // Do NOT use AndroidIntent(ACTION_MAIN + CATEGORY_LAUNCHER +
        // package): android_intent_plus strips the package when
        // resolveActivity(MATCH_DEFAULT_ONLY) fails (launcher
        // activities don't declare CATEGORY_DEFAULT), which turns
        // the launch into an implicit intent and pops a system
        // "打开方式" chooser instead of opening the app.
        final ok = await _launcherChannel.invokeMethod<bool>(
          'openApp',
          {'package': pkg},
        );
        if (ok == true) return '已打开 $name。';
      } catch (_) {
        // Try the next candidate package.
      }
    }
    return '$name 似乎没安装。';
  }
  /// 从“打开XXX / 启动XXX / 开XXX”里取出 XXX；取不出（只有“打开一下”）返回 null。
  static String? _extractOpenAppName(String t) {
    var s = t.replaceFirst(RegExp(r'^(请|帮我|麻烦|我要|我想|帮忙)+'), '');
    final m = RegExp(r'(?:打开|启动|开一下|开)\s*(.+)$').firstMatch(s);
    if (m == null) return null;
    var name = m.group(1)!.trim();
    name = name
        .replaceAll(RegExp(r'(一下|吧|啊|了|呢|嘛)+$'), '')
        .replaceAll(RegExp(r'[\s，。,.！？!?、：:]+$'), '')
        .trim();
    if (name.isEmpty) return null;
    return name;
  }
}

// ============================================================================
// 数据类
// ============================================================================

/// 挂起的操作：cands == null 表示“等用户确认发送”，否则表示“等用户选第几个联系人”。
class _Pending {
  _Pending(this.cands, this.run)
      : expires = DateTime.now().add(const Duration(seconds: 90));
  final List<_Cand>? cands;
  final Future<String> Function(_Cand? c) run;
  final DateTime expires;
}

class _Cand {
  const _Cand(this.name, this.phone);
  final String name;
  final String phone;
  String get label {
    final tail =
        phone.length >= 4 ? phone.substring(phone.length - 4) : phone;
    return '$name（尾号$tail）';
  }
}

enum _LookupKind { one, many, none, noNumber, denied, error }

class _Lookup {
  const _Lookup(this.kind, {this.cands, this.name});
  final _LookupKind kind;
  final List<_Cand>? cands;
  final String? name;
}

class _MsgIntent {
  const _MsgIntent(this.name, this.body);
  final String name;
  final String body;
}

// ============================================================================
// 跨应用适配器：每个 App 一个类，各自声明“怎么找人、怎么填内容、怎么发送、怎么核实”。
// 步骤是声明式的（选择器 + 动作），由原生 JarvisAccessibilityService 逐步执行：
//   click      点击匹配的控件（点前有黑名单检查）
//   setText    向匹配的输入框填字
//   wait       等某个控件出现（用来确认“已进入正确的聊天”）
//   send       点“发送”（只点一次）
//   verifyText 回查：聊天里是否出现了这句话（只读）
// 选择器字段：textEquals / textRegex / descRegex / idRegex / classContains /
//            editable / topOnly（只认界面最上方 20% 以内，用来认聊天标题）
// 步骤里的 hint = 这步失败时对用户说的人话；fills = 这步之后内容已经填进输入框。
// 注意：各 App 的界面文字会随版本变化，选择器需要真机校准（失败时返回的“卡在哪一步”
// 就是校准依据）。
// ============================================================================

Map<String, dynamic> _m({
  String? textEquals,
  String? textRegex,
  String? descRegex,
  String? idRegex,
  String? classContains,
  bool? editable,
  bool topOnly = false,
}) =>
    <String, dynamic>{
      if (textEquals != null) 'textEquals': textEquals,
      if (textRegex != null) 'textRegex': textRegex,
      if (descRegex != null) 'descRegex': descRegex,
      if (idRegex != null) 'idRegex': idRegex,
      if (classContains != null) 'classContains': classContains,
      if (editable != null) 'editable': editable,
      if (topOnly) 'topOnly': true,
    };

Map<String, dynamic> _step(
  String action,
  String name,
  String hint, {
  List<Map<String, dynamic>> matches = const <Map<String, dynamic>>[],
  String? text,
  int timeoutMs = 6000,
  int settleMs = 500,
  bool optional = false,
  bool soft = false,
  int maxBack = 0,
  int backAfterMs = 2500,
  bool fills = false,
}) =>
    <String, dynamic>{
      'action': action,
      'name': name,
      'hint': hint,
      'matches': matches,
      if (text != null) 'text': text,
      'timeoutMs': timeoutMs,
      'settleMs': settleMs,
      'optional': optional,
      'soft': soft,
      'maxBack': maxBack,
      'backAfterMs': backAfterMs,
      if (fills) 'fills': true,
    };

abstract class _Adapter {
  const _Adapter();
  String get id;
  String get label;
  List<String> get aliases;
  String get package;

  /// 是否已实现。false = 只是预留位置，不会被执行。
  bool get enabled;

  /// 声明式步骤。[contact] / [body] 已经是干净的收件人和正文。
  List<Map<String, dynamic>> steps(String contact, String body);
}

/// 微信：首页搜索 → 搜联系人 → 点进聊天 → 核对标题 → 填内容 → 发送 → 核实。
class _WeChatAdapter extends _Adapter {
  _WeChatAdapter();
  @override
  String get id => 'wechat';
  @override
  String get label => '微信';
  @override
  List<String> get aliases => const ['微信', 'weixin', 'wechat'];
  @override
  String get package => 'com.tencent.mm';
  @override
  bool get enabled => true;

  @override
  List<Map<String, dynamic>> steps(String contact, String body) => [
        _step('click', '打开搜索', '没找到微信首页的搜索入口（微信可能没停在首页）。',
            matches: [_m(descRegex: r'^搜索$'), _m(textRegex: r'^搜索$')],
            timeoutMs: 10000,
            settleMs: 900,
            maxBack: 3),
        _step('setText', '输入联系人名', '没能在搜索框里输入名字。',
            matches: [_m(editable: true)],
            text: contact,
            timeoutMs: 6000,
            settleMs: 900),
        _step('click', '点选联系人', '没在搜索结果里找到“$contact”（名字需要和微信里显示的一致）。',
            matches: [_m(textEquals: contact, editable: false)],
            timeoutMs: 9000,
            settleMs: 1000),
        _step('wait', '确认聊天对象', '进入聊天后标题不是“$contact”，为避免发错人已停止。',
            matches: [_m(textEquals: contact, editable: false, topOnly: true)],
            timeoutMs: 6000),
        _step('setText', '输入消息', '没找到聊天输入框（可能处于语音输入模式）。',
            matches: [_m(editable: true)],
            text: body,
            timeoutMs: 6000,
            settleMs: 600,
            fills: true),
        _step('send', '点发送', '没找到可点的发送按钮。', timeoutMs: 6000, settleMs: 600),
        _step('verifyText', '核实消息已发出', '点了发送，但没能在聊天里看到这条消息。',
            text: body, timeoutMs: 7000, soft: true),
      ];
}

/// QQ：流程同微信，但搜索入口是顶部的“搜索”框，各步骤的选择器各自声明。
class _QqAdapter extends _Adapter {
  _QqAdapter();
  @override
  String get id => 'qq';
  @override
  String get label => 'QQ';
  @override
  List<String> get aliases => const ['QQ'];
  @override
  String get package => 'com.tencent.mobileqq';
  @override
  bool get enabled => true;

  @override
  List<Map<String, dynamic>> steps(String contact, String body) => [
        _step('click', '打开搜索', '没找到 QQ 顶部的搜索框（QQ 可能没停在消息首页）。',
            matches: [_m(textRegex: r'^搜索$'), _m(descRegex: r'^搜索$')],
            timeoutMs: 10000,
            settleMs: 900,
            maxBack: 3),
        _step('setText', '输入联系人名', '没能在搜索框里输入名字。',
            matches: [_m(editable: true)],
            text: contact,
            timeoutMs: 6000,
            settleMs: 900),
        _step('click', '点选联系人', '没在搜索结果里找到“$contact”（名字需要和 QQ 里显示的一致）。',
            matches: [_m(textEquals: contact, editable: false)],
            timeoutMs: 9000,
            settleMs: 1000),
        _step('wait', '确认聊天对象', '进入聊天后标题不是“$contact”，为避免发错人已停止。',
            matches: [_m(textEquals: contact, editable: false, topOnly: true)],
            timeoutMs: 6000),
        _step('setText', '输入消息', '没找到聊天输入框。',
            matches: [_m(editable: true)],
            text: body,
            timeoutMs: 6000,
            settleMs: 600,
            fills: true),
        _step('send', '点发送', '没找到可点的发送按钮。', timeoutMs: 6000, settleMs: 600),
        _step('verifyText', '核实消息已发出', '点了发送，但没能在聊天里看到这条消息。',
            text: body, timeoutMs: 7000, soft: true),
      ];
}

/// 预留适配器（抖音、淘宝、支付宝、微博、小红书、哔哩哔哩、企业微信……）：
/// 现在不实现，被问到时如实说“还没适配”。以后按 _WeChatAdapter 的模式
/// 写成真正的子类，并在原生 HARD_WHITELIST 里有意识地加上包名即可。
/// 支付类 App（支付宝等）按安全要求不应进入原生白名单。
class _ReservedAdapter extends _Adapter {
  const _ReservedAdapter(this._id, this._label, this._aliases, this._package);
  final String _id;
  final String _label;
  final List<String> _aliases;
  final String _package;
  @override
  String get id => _id;
  @override
  String get label => _label;
  @override
  List<String> get aliases => _aliases;
  @override
  String get package => _package;
  @override
  bool get enabled => false;
  @override
  List<Map<String, dynamic>> steps(String contact, String body) => const [];
}
