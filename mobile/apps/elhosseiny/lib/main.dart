import 'package:elmanassa_business/main.dart' show ProviderApp;
import 'package:elmanassa_business/single_business.dart';
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

/// The office app of المستشار أحمد الحسيني: the same screens and notifications as the Elmanassa Business app (and the web dashboard
/// of elhosseiny.elmanassa.shop), opened straight on this office, with his picture and colours instead of the platform's.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  SingleBusiness.slug = 'elhosseiny';
  SingleBusiness.appTitle = 'المستشار أحمد الحسيني';
  SingleBusiness.nameAr = 'المستشار أحمد الحسيني';
  SingleBusiness.nameEn = 'Counsellor Ahmed Elhosseiny';
  SingleBusiness.taglineAr = 'مكتبك وقضاياك وموكلوك في مكان واحد';
  SingleBusiness.taglineEn = 'Your office, cases and clients in one place';
  AppBrand.logo = const AssetImage('assets/lawyer.jpg');
  AppBrand.primary = const Color(0xFF0B1D3A);   // navy
  AppBrand.accent = const Color(0xFF2E5C93);    // lighter navy for the gradients (gold stays in the icon and the website)
  SingleBusiness.version = '1.0.0';
  await AppLocale.load();
  runApp(const ProviderApp());
}
