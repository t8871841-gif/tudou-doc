import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'data/acupuncture_repository.dart';
import 'data/acupoint_repository.dart';
import 'data/changelog_repository.dart';
import 'data/formula_oral_hint_repository.dart';
import 'data/formula_repository.dart';
import 'data/herb_repository.dart';
import 'data/settings_repository.dart';
import 'data/ziwei_rules_repository.dart';
import 'screens/home_screen.dart';
import 'theme/app_colors.dart';
import 'widgets/app_background.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await FormulaRepository.load();
  await FormulaOralHintRepository.load();
  await HerbRepository.load();
  await AcupunctureRepository.load();
  await AcupointRepository.load();
  await ChangelogRepository.load();
  await SettingsRepository.instance.load();
  // 紫微解读层外置规则；加载失败会在仓库内部静默降级为内建默认值，不影响启动。
  await ZiweiRulesRepository.load();
  runApp(const NiHaishaApp());
}

class NiHaishaApp extends StatelessWidget {
  const NiHaishaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: SettingsRepository.instance,
      builder: (context, _) {
        final settings = SettingsRepository.instance;
        // 自定义背景图片启用时，脚手架背景透明以露出全局背景图
        final hasCustomBg = settings.backgroundImagePath != null;
        return MaterialApp(
          title: '汉方唐鉴',
          debugShowCheckedModeBanner: false,
          themeMode: settings.themeMode,
          theme: ThemeData(
            colorScheme: ColorScheme.fromSeed(
              seedColor: const Color(0xFF8B4513),
              brightness: Brightness.light,
            ),
            useMaterial3: true,
            scaffoldBackgroundColor: hasCustomBg ? Colors.transparent : null,
            // ⛔ 关闭 Zoom 转场快照：快照机制会在设备动画缩放异常/手势取消时
            // 把进场页冻结在半透明帧（子午流注屏「打开模糊一大片」根因）。
            pageTransitionsTheme: const PageTransitionsTheme(
              builders: {
                TargetPlatform.android: ZoomPageTransitionsBuilder(
                  allowSnapshotting: false,
                  allowEnterRouteSnapshotting: false,
                ),
              },
            ),
            extensions: [AppColors.light],
          ),
          darkTheme: ThemeData(
            colorScheme: ColorScheme.fromSeed(
              seedColor: const Color(0xFFD4AF37),
              brightness: Brightness.dark,
            ),
            useMaterial3: true,
            scaffoldBackgroundColor:
                hasCustomBg ? Colors.transparent : const Color(0xFF0D0D0B),
            appBarTheme: const AppBarTheme(
              backgroundColor: Color(0xFF11110D),
              foregroundColor: Color(0xFFE2C36B),
              centerTitle: false,
            ),
            cardTheme: CardThemeData(
              color: const Color(0xFF171611),
              surfaceTintColor: Colors.transparent,
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(16),
                side: const BorderSide(color: Color(0xFF494126)),
              ),
            ),
            pageTransitionsTheme: const PageTransitionsTheme(
              builders: {
                TargetPlatform.android: ZoomPageTransitionsBuilder(
                  allowSnapshotting: false,
                  allowEnterRouteSnapshotting: false,
                ),
              },
            ),
            extensions: [AppColors.dark],
          ),
          home: MediaQuery(
            data: MediaQuery.of(context).copyWith(
              textScaler: TextScaler.linear(settings.textScaleFactor),
            ),
            child: HomeScreen(textScaleFactor: settings.textScaleFactor),
          ),
          // 全局自定义背景：包裹所有路由，图片由 AppBackground 按设置显示
          builder: (context, child) => AppBackground(child: child),
          localizationsDelegates: const [
            GlobalMaterialLocalizations.delegate,
            GlobalWidgetsLocalizations.delegate,
            GlobalCupertinoLocalizations.delegate,
          ],
          supportedLocales: const [
            Locale('zh', 'CN'),
            Locale('en', 'US'),
          ],
          locale: const Locale('zh', 'CN'),
        );
      },
    );
  }
}
