import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

const _days = ['sat', 'sun', 'mon', 'tue', 'wed', 'thu', 'fri'];
const _dayAr = {'sat': 'السبت', 'sun': 'الأحد', 'mon': 'الاثنين', 'tue': 'الثلاثاء', 'wed': 'الأربعاء', 'thu': 'الخميس', 'fri': 'الجمعة'};
const _dayEn = {'sat': 'Saturday', 'sun': 'Sunday', 'mon': 'Monday', 'tue': 'Tuesday', 'wed': 'Wednesday', 'thu': 'Thursday', 'fri': 'Friday'};

Widget hoursList(BuildContext context, Map? hours) {
  final ar = S.of(context).ar;
  if (hours == null || hours.isEmpty) return const SizedBox.shrink();
  return Column(children: [
    for (final d in _days)
      if (hours[d] is Map) Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: Row(mainAxisAlignment: MainAxisAlignment.spaceBetween, children: [
        Text(ar ? _dayAr[d]! : _dayEn[d]!),
        Text((hours[d] as Map)['closed'] == true ? (ar ? 'مغلق' : 'Closed') : '${(hours[d] as Map)['open']} – ${(hours[d] as Map)['close']}', textDirection: TextDirection.ltr, style: const TextStyle(fontWeight: FontWeight.w700)),
      ])),
  ]);
}

/// Contact, address, hours, social links, branches and policies: what a customer needs to trust and reach the shop.
class InfoPage extends StatelessWidget {
  const InfoPage({super.key, required this.ctx});
  final StoreCtx ctx;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final p = ctx.p;
    final cs = Theme.of(context).colorScheme;
    String v(String k) => (p[k] as String?) ?? '';
    final phones = [v('supportPhone'), ...((p['extraPhones'] as List?) ?? []).cast<String>()].where((x) => x.isNotEmpty).toList();
    Widget tile(IconData icon, String title, String sub, VoidCallback? onTap) => ListTile(leading: CircleAvatar(backgroundColor: cs.primary.withValues(alpha: 0.1), child: Icon(icon, color: cs.primary)), title: Text(title, style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text(sub, textDirection: TextDirection.ltr, textAlign: TextAlign.start), onTap: onTap, trailing: onTap == null ? null : const Icon(Icons.chevron_right));
    return Async<List<Map<String, dynamic>>>(
      load: () async => ((await ctx.api.get('shop/branches')) as List).cast<Map<String, dynamic>>(),
      builder: (context, branches, reload) => ListView(padding: const EdgeInsets.only(bottom: 24), children: [
        if (v('shortDescription').isNotEmpty || v('about').isNotEmpty) Padding(padding: const EdgeInsets.all(16), child: Text(v('shortDescription').isNotEmpty ? v('shortDescription') : v('about'), style: const TextStyle(height: 1.6))),
        if (v('whatsapp').isNotEmpty) tile(Icons.chat, 'WhatsApp', v('whatsapp'), () => whatsapp(v('whatsapp'), '${ctx.name} 👋')),
        for (final ph in phones) tile(Icons.phone, s.ar ? 'اتصل بنا' : 'Call us', ph, () => callPhone(ph)),
        if (v('supportEmail').isNotEmpty) tile(Icons.email_outlined, s.ar ? 'البريد الإلكتروني' : 'Email', v('supportEmail'), () => openUrl('mailto:${v('supportEmail')}')),
        if (v('addressText').isNotEmpty) tile(Icons.place_outlined, s.ar ? 'العنوان' : 'Address', v('addressText'), v('mapsUrl').isNotEmpty ? () => openUrl(v('mapsUrl')) : null),
        for (final e in {'facebookUrl': 'Facebook', 'instagramUrl': 'Instagram', 'tiktokUrl': 'TikTok', 'websiteUrl': s.ar ? 'الموقع' : 'Website'}.entries) if (v(e.key).isNotEmpty) tile(Icons.public, e.value, v(e.key), () => openUrl(v(e.key))),
        if ((p['workingHours'] as Map?)?.isNotEmpty == true) ...[
          Padding(padding: const EdgeInsets.fromLTRB(16, 16, 16, 4), child: Text(s.ar ? 'مواعيد العمل' : 'Opening hours', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
          Padding(padding: const EdgeInsets.symmetric(horizontal: 16), child: hoursList(context, p['workingHours'] as Map)),
        ],
        if (branches.isNotEmpty) ...[
          Padding(padding: const EdgeInsets.fromLTRB(16, 20, 16, 4), child: Text(s.ar ? 'فروعنا' : 'Our branches', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
          for (final b in branches) Card(margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6), child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [Expanded(child: Text(b['name'] as String, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15))), if (b['isPickup'] == true) pill(context, s.ar ? 'استلام' : 'Pickup')]),
            const SizedBox(height: 4),
            Text([b['addressLine1'], b['area'], govName(b['governorateCode'] as String?, s.ar).isEmpty ? b['city'] : govName(b['governorateCode'] as String?, s.ar)].where((x) => x != null && '$x'.isNotEmpty).join('، '), style: const TextStyle(color: Colors.grey)),
            Wrap(spacing: 8, children: [
              if ((b['phone'] as String?)?.isNotEmpty == true) ActionChip(avatar: const Icon(Icons.phone, size: 16), label: Text(b['phone'] as String), onPressed: () => callPhone(b['phone'] as String)),
              if ((b['whatsapp'] as String?)?.isNotEmpty == true) ActionChip(avatar: const Icon(Icons.chat, size: 16), label: const Text('WhatsApp'), onPressed: () => whatsapp(b['whatsapp'] as String)),
              if ((b['mapsUrl'] as String?)?.isNotEmpty == true) ActionChip(avatar: const Icon(Icons.map_outlined, size: 16), label: Text(s.ar ? 'الخريطة' : 'Map'), onPressed: () => openUrl(b['mapsUrl'] as String)),
            ]),
            if ((b['workingHours'] as Map?)?.isNotEmpty == true) ExpansionTile(tilePadding: EdgeInsets.zero, title: Text(s.ar ? 'المواعيد' : 'Hours', style: const TextStyle(fontSize: 13)), children: [hoursList(context, b['workingHours'] as Map)]),
          ]))),
        ],
        for (final e in {'shippingPolicy': s.ar ? 'سياسة الشحن' : 'Shipping policy', 'returnPolicy': s.ar ? 'سياسة الاسترجاع' : 'Return policy', 'privacyPolicy': s.ar ? 'سياسة الخصوصية' : 'Privacy policy', 'termsText': s.ar ? 'الشروط والأحكام' : 'Terms'}.entries)
          if (v(e.key).isNotEmpty) ExpansionTile(title: Text(e.value, style: const TextStyle(fontWeight: FontWeight.w800)), childrenPadding: const EdgeInsets.all(16), children: [Align(alignment: AlignmentDirectional.centerStart, child: Text(v(e.key), style: const TextStyle(height: 1.6)))]),
      ]),
    );
  }
}
