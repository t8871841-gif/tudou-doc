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
        scaffoldBackgroundColor: const Color(0xFF050811),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF8AB4F8),
          secondary: Color(0xFFA98BE0),
          tertiary: Color(0xFFE8788A),
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}
