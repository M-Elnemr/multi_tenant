import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart' show AppLocale;
import 'package:url_launcher/url_launcher.dart';

import '../core/api.dart';
import '../core/config.dart';
import '../core/models.dart';
import '../core/state.dart';
import '../core/strings.dart';
import '../core/theme.dart';
import '../ui/widgets.dart';
import 'auth_screen.dart';
import 'orders_screen.dart';
import 'shop_screen.dart';
import 'wishlist_screen.dart';

class AccountScreen extends StatelessWidget {
  const AccountScreen({super.key});

  Future<void> _editProfile(BuildContext context) async {
    final a = AppState.I.account ?? const {};
    final name = TextEditingController(text: s(a['name'])), phone = TextEditingController(text: s(a['phone']).replaceFirst('+20', '0'));
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (c) => Padding(
        padding: EdgeInsets.fromLTRB(20, 20, 20, 20 + MediaQuery.of(c).viewInsets.bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(tr('open.account.profile'), style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)),
          const SizedBox(height: 14),
          TextField(controller: name, decoration: InputDecoration(labelText: tr('open.checkout.name'))),
          const SizedBox(height: 12),
          TextField(controller: phone, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: tr('open.checkout.phone'), hintText: '01xxxxxxxxx', helperText: tr('open.account.phoneHint'))),
          const SizedBox(height: 16),
          GradientButton(label: tr('common.save'), onPressed: () async {
            try {
              final res = Map<String, dynamic>.from(await OpenApi.put('me', {'name': name.text.trim(), if (phone.text.trim().isNotEmpty) 'phone': phone.text.trim()}) as Map);
              AppState.I.account = res;
              AppState.I.refresh();
              if (c.mounted) Navigator.pop(c, true);
            } on ApiException catch (e) {
              if (c.mounted) toast(c, e.text);
            }
          }),
        ]),
      ),
    );
    name.dispose();
    phone.dispose();
    if (ok == true && context.mounted) toast(context, tr('open.account.saved'));
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: AppState.I,
        builder: (context, _) {
          final a = AppState.I.account;
          final signedIn = AppState.I.signedIn;
          Widget tile(IconData i, String t, String sub, VoidCallback onTap) => Card2(margin: const EdgeInsets.only(bottom: 10), onTap: onTap, child: Row(children: [Container(width: 44, height: 44, decoration: BoxDecoration(color: kSoft, borderRadius: BorderRadius.circular(14)), child: Icon(i, color: kAccent)), const SizedBox(width: 14), Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(t, style: const TextStyle(fontWeight: FontWeight.w900)), Text(sub, style: const TextStyle(color: kMute, fontSize: 12))])), const Icon(Icons.chevron_right_rounded, color: kMute)]));
          return Scaffold(
            appBar: AppBar(title: Text(tr('nav.account'))),
            body: ListView(padding: const EdgeInsets.fromLTRB(16, 4, 16, 28), children: [
              if (signedIn && a != null)
                Card2(margin: const EdgeInsets.only(bottom: 14), child: Row(children: [
                  Container(width: 62, height: 62, decoration: const BoxDecoration(gradient: kAccentGradient, shape: BoxShape.circle), alignment: Alignment.center, child: Text(s(a['name']).isEmpty ? '?' : s(a['name']).substring(0, 1), style: const TextStyle(color: Colors.white, fontSize: 26, fontWeight: FontWeight.w900))),
                  const SizedBox(width: 14),
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [Text(s(a['name']), maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 18)), Text(s(a['phone']).isEmpty ? tr('open.account.noPhone') : s(a['phone']).replaceFirst('+20', '0'), textDirection: TextDirection.ltr, style: const TextStyle(color: kMute, fontSize: 13))])),
                ]))
              else
                Container(
                  margin: const EdgeInsets.only(bottom: 14),
                  padding: const EdgeInsets.all(20),
                  decoration: BoxDecoration(gradient: const LinearGradient(colors: [kNavy, kNavy2]), borderRadius: BorderRadius.circular(24), boxShadow: softShadow(.3)),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    const Text('👋', style: TextStyle(fontSize: 34)),
                    const SizedBox(height: 6),
                    Text(tr('open.orders.signInTitle'), style: const TextStyle(color: Colors.white, fontSize: 19, fontWeight: FontWeight.w900)),
                    const SizedBox(height: 4),
                    Text(tr('open.orders.signInHint'), style: const TextStyle(color: Colors.white70, height: 1.4)),
                    const SizedBox(height: 14),
                    Row(children: [
                      Expanded(child: GradientButton(label: tr('login.google'), compact: true, onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const AuthScreen())))),
                    ]),
                  ]),
                ),
              if (signedIn) tile(Icons.edit_outlined, tr('open.account.profile'), tr('open.account.phoneHint'), () => _editProfile(context)),
              tile(Icons.inventory_2_outlined, tr('open.myOrders'), tr('open.account.ordersSub'), () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const OrdersScreen()))),
              tile(Icons.favorite_border_rounded, tr('open.wishlist'), tr('open.account.wishlistSub'), () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const WishlistScreen()))),
              tile(Icons.storefront_outlined, tr('open.allShops'), tr('open.account.shopsSub'), () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ShopsScreen()))),
              tile(Icons.translate_rounded, AppLocale.isAr ? 'English' : 'العربية', AppLocale.isAr ? 'Switch language' : 'تغيير اللغة', AppLocale.toggle),
              tile(Icons.add_business_outlined, tr('open.sellWithUs'), kRootSite.replaceFirst('https://', ''), () => launchUrl(Uri.parse(kRootSite), mode: LaunchMode.externalApplication)),
              if (signedIn) ...[
                const SizedBox(height: 8),
                GradientButton(label: tr('open.account.signOut'), dark: true, onPressed: () => AppState.I.signOut()),
                const SizedBox(height: 6),
                TextButton(
                  onPressed: () async {
                    final ok = await showDialog<bool>(context: context, builder: (c) => AlertDialog(content: Text(tr('open.account.deleteConfirm')), actions: [TextButton(onPressed: () => Navigator.pop(c, false), child: Text(tr('common.cancel'))), TextButton(onPressed: () => Navigator.pop(c, true), child: Text(tr('open.account.delete'), style: const TextStyle(color: kRed)))]));
                    if (ok != true) return;
                    try {
                      await OpenApi.delete('me');
                      OpenApi.token = null;
                      AppState.I.account = null;
                      await AppState.I.signOut();
                    } on ApiException catch (e) {
                      if (context.mounted) toast(context, e.text);
                    }
                  },
                  child: Text(tr('open.account.delete'), style: const TextStyle(color: kRed, fontWeight: FontWeight.w800)),
                ),
              ],
            ]),
          );
        },
      );
}
