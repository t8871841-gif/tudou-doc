import 'package:flutter/material.dart';

import 'screens/home_screen.dart';

void main() {
  runApp(const JarvisApp());
}

class JarvisApp extends StatelessWidget {
  const JarvisApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Jarvis',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF00060B),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF33DDFF),
          secondary: Color(0xFF8B5CFF),
          tertiary: Color(0xFFFF3DCB),
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}
