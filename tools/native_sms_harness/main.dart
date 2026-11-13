import 'package:flutter/material.dart';
import 'package:bluebubbles/app/layouts/settings/pages/native_sms_page.dart';

void main() {
  runApp(MaterialApp(
    title: 'SMS Prototype',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(colorSchemeSeed: const Color(0xff78517d)),
    darkTheme: ThemeData(
      brightness: Brightness.dark,
      colorSchemeSeed: const Color(0xffcba8d5),
    ),
    home: const NativeSmsPage(standalone: true),
  ));
}
