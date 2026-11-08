import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import '../common.dart';
import '../store_panel.dart' show OrderDetail;
import '../theme/templates.dart';
import 'inbox_common.dart';

/// The customer card of a chat: contact (call / WhatsApp), spend in EGP, tags, address, notes and the latest orders.
Future<void> showCustomerSheet(BuildContext context, Ctx ctx, Map<String, dynamic> chat) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (c) => _CustomerSheet(ctx: ctx, chat: chat),
  );
}

class _CustomerSheet extends StatelessWidget {
  const _CustomerSheet({required this.ctx, required this.chat});
  final Ctx ctx;
  final Map<String, dynamic> chat;

  @override
  Widget build(BuildContext context) {
    final t = AppTemplate.of(context);
    final s = S.of(context);
    final cu = chat['customer'] is Map ? Map<String, dynamic>.from(chat['customer'] as Map) : null;
    final name = cu == null ? whoName(chat) : '${cu['name'] ?? whoName(chat)}';
    final phone = '${cu?['phone'] ?? chat['customerPhone'] ?? ''}';
    final wa = '${cu?['whatsappNumber'] ?? phone}';
    final orders = maps(cu?['orders']);
    final tags = ((cu?['tags'] as List?) ?? const []).cast<Object?>();
    final addr = cu?['address'] is Map ? Map<String, dynamic>.from(cu!['address'] as Map) : null;
    final addrText = addr == null ? '' : dotted([addr['addressLine1'], addr['district'], addr['city']]);
    return SafeArea(
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * 0.88),
        child: ListView(shrinkWrap: true, padding: const EdgeInsets.fromLTRB(20, 0, 20, 20), children: [
          Row(children: [
            ChatAvatar(name: name, channel: chat['channel'], size: 56),
            const SizedBox(width: 14),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(name, style: t.heading(19)),
              const SizedBox(height: 2),
              Text(dotted([channelText(chat['channel']), if (cu != null) ((cu['ordersCount'] as num?) ?? 0) > 0 ? L('عميل قديم', 'Returning customer') : L('عميل جديد', 'New customer')]), style: TextStyle(color: t.muted, fontSize: 13)),
            ])),
          ]),
          const SizedBox(height: 14),
          if (phone.isNotEmpty)
            Row(children: [
              Expanded(child: OutlinedButton.icon(onPressed: () => dial(phone), icon: const Icon(Icons.phone_rounded), label: Text(localPhone(phone), textDirection: TextDirection.ltr))),
              const SizedBox(width: 8),
              IconButton.filled(style: IconButton.styleFrom(backgroundColor: const Color(0xFF25D366), minimumSize: const Size(52, 52)), onPressed: () => whatsapp(wa), icon: const Icon(Icons.chat_rounded), tooltip: 'WhatsApp'),
            ]),
          if (cu == null) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(color: t.paper, borderRadius: BorderRadius.circular(t.cardRadius * 0.7), border: Border.all(color: t.line)),
              child: Row(children: [
                Icon(Icons.link_off_rounded, color: t.muted),
                const SizedBox(width: 10),
                Expanded(child: Text(L('المحادثة دي مش مربوطة بعميل لسه. هتترتبط لما يطلب أو يدي رقمه.', 'This chat is not linked to a customer yet. It links when they order or share their number.'), style: TextStyle(color: t.muted, height: 1.4))),
              ]),
            ),
          ] else ...[
            const SizedBox(height: 14),
            Row(children: [
              Expanded(child: _figure(t, L('الطلبات', 'Orders'), '${(cu['ordersCount'] as num?) ?? 0}')),
              const SizedBox(width: 10),
              Expanded(child: _figure(t, L('إجمالي الشراء', 'Total spent'), s.money((cu['totalSpentMinor'] as num?) ?? 0, ctx.currency))),
            ]),
            if ('${cu['email'] ?? ''}'.isNotEmpty) kv(L('الإيميل', 'Email'), '${cu['email']}'),
            if (addrText.isNotEmpty) kv(L('العنوان', 'Address'), addrText),
            if ('${cu['createdAt'] ?? ''}'.isNotEmpty) kv(L('أول تواصل', 'First contact'), fmtDate('${cu['createdAt']}', time: false)),
            if (cu['broadcastOptOutAt'] != null) kv(L('الرسائل', 'Messages'), L('طلب ما يوصلوش عروض', 'Opted out of offers')),
            if (tags.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Wrap(runSpacing: 4, children: [for (final tg in tags) Tag('$tg', accentOf(t))])),
            if ('${cu['notes'] ?? ''}'.isNotEmpty) ...[
              const SizedBox(height: 10),
              Text(L('ملاحظات عن العميل', 'Customer notes'), style: TextStyle(fontWeight: FontWeight.w900, fontSize: 12.5, color: t.muted)),
              const SizedBox(height: 4),
              Text('${cu['notes']}'),
            ],
            const SizedBox(height: 14),
            Text(L('آخر الطلبات', 'Latest orders'), style: t.heading(15)),
            if (orders.isEmpty) Padding(padding: const EdgeInsets.symmetric(vertical: 10), child: Text(L('لسه مفيش طلبات.', 'No orders yet.'), style: TextStyle(color: t.muted))),
            for (final o in orders.take(6))
              Card(child: ListTile(
                title: Text('${o['orderNumber']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text(dotted([statusLabel('${o['status']}'), fmtDate('${o['createdAt']}', time: false)])),
                trailing: Text(s.money((o['totalMinor'] as num?) ?? 0, '${o['currency'] ?? ctx.currency}'), style: const TextStyle(fontWeight: FontWeight.w800)),
                onTap: () { Navigator.pop(context); Navigator.of(context).push(MaterialPageRoute(builder: (_) => OrderDetail(ctx: ctx, id: '${o['id']}'))); },
              )),
          ],
        ]),
      ),
    );
  }

  Widget _figure(AppTemplate t, String label, String value) => Container(
        padding: const EdgeInsets.all(12),
        decoration: t.cardDecoration(color: t.paper),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(value, style: t.heading(18)),
          Text(label, style: TextStyle(color: t.muted, fontSize: 12)),
        ]),
      );
}
