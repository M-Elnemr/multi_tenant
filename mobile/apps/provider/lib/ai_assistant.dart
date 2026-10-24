// ignore_for_file: use_build_context_synchronously
import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'channels.dart';
import 'common.dart';
import 'connect.dart';

/// AI assistant setup: the connected Facebook / Instagram / WhatsApp channels and what WhatsApp still needs before confirmation messages can go out.
class AiAssistantScreen extends StatefulWidget {
  const AiAssistantScreen({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<AiAssistantScreen> createState() => _AiAssistantScreenState();
}

class _AiAssistantScreenState extends State<AiAssistantScreen> {
  int _gen = 0;

  Future<void> _wabaDialog() async {
    final ar = S.of(context).ar;
    final id = TextEditingController();
    final saved = await showDialog<bool>(context: context, builder: (d) => StatefulBuilder(builder: (d, set) => AlertDialog(
      title: Text(ar ? 'معرّف حساب واتساب للأعمال' : 'WhatsApp Business Account ID'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(ar ? 'ميتا بتطلب الرقم ده عشان تسجّل قوالب رسائلك. هو مختلف عن رقم التليفون وعن Phone number ID.' : 'Meta needs this number to register your message templates. It is different from your phone number and from the Phone number ID.', style: const TextStyle(color: Color(0xFF6B7C8D))),
        const SizedBox(height: 10),
        for (final (i, t) in [
          (ar ? 'افتح حسابات واتساب في إعدادات Meta Business (الزر تحت).' : 'Open your WhatsApp accounts in Meta Business settings (button below).'),
          (ar ? 'اختار حساب رقم واتساب بتاعك.' : 'Pick the account of your WhatsApp number.'),
          (ar ? 'انسخ الرقم المكتوب جنبه «WhatsApp Business Account ID» (حوالي ١٥ رقم).' : 'Copy the number called “WhatsApp Business Account ID” (about 15 digits).'),
          (ar ? 'الصقه هنا واضغط حفظ. هنتأكد من ميتا إنه يملك الرقم المربوط.' : 'Paste it here and press Save. We check with Meta that it owns your connected number.'),
        ].indexed) Padding(padding: const EdgeInsets.only(bottom: 4), child: Text('${i + 1}. $t')),
        TextButton.icon(onPressed: () => launch('https://business.facebook.com/latest/settings/whatsapp_account'), icon: const Icon(Icons.open_in_new_rounded), label: Text(ar ? 'افتح ميتا' : 'Open Meta')),
        TextField(controller: id, keyboardType: TextInputType.number, textDirection: TextDirection.ltr, onChanged: (_) => set(() {}), decoration: InputDecoration(labelText: ar ? 'المعرّف (أرقام فقط)' : 'ID (digits only)', hintText: '123456789012345')),
      ])),
      actions: [
        TextButton(onPressed: () async {
          if (await run(d, () async { await widget.ctx.api.post('store/ai/whatsapp/waba/detect', {}); }) && d.mounted) Navigator.pop(d, true);
        }, child: Text(ar ? 'دوّر عليه بدالي' : 'Find it for me')),
        FilledButton(onPressed: id.text.replaceAll(RegExp(r'\D'), '').length < 5 ? null : () async {
          if (await run(d, () async { await widget.ctx.api.put('store/ai/whatsapp/waba', {'wabaId': id.text.replaceAll(RegExp(r'\D'), '')}); }) && d.mounted) Navigator.pop(d, true);
        }, child: Text(ar ? 'حفظ' : 'Save')),
      ],
    )));
    id.dispose();
    if (saved == true && mounted) setState(() => _gen++);
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    final ctx = widget.ctx;
    final write = ctx.canWrite('ai');
    return ResumeReload(onResume: () => setState(() => _gen++), child: Async<List<dynamic>>(
      key: ValueKey('ai$_gen'),
      load: () async => [await ctx.api.get('store/ai/channels'), await ctx.api.get('store/ai/whatsapp')],
      builder: (context, d, reload) {
        final channels = ((d[0] as List?) ?? const []).cast<Map<String, dynamic>>();
        final wa = d[1] as Map<String, dynamic>;
        final ready = (wa['readiness'] as Map?)?.cast<String, dynamic>() ?? const {};
        Widget check(bool ok, String text, {Widget? action}) => Padding(padding: const EdgeInsets.symmetric(vertical: 5), child: Row(children: [
              Icon(ok ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded, color: ok ? const Color(0xFF16A34A) : const Color(0xFF9AA9BA), size: 22),
              const SizedBox(width: 10),
              Expanded(child: Text(text, style: TextStyle(color: ok ? null : const Color(0xFF6B7C8D)))),
              if (action != null) action,
            ]));
        const names = {'FACEBOOK': ('فيسبوك', 'Facebook'), 'INSTAGRAM': ('إنستجرام', 'Instagram'), 'WHATSAPP': ('واتساب', 'WhatsApp')};
        return RefreshIndicator(onRefresh: reload, child: ListView(padding: const EdgeInsets.all(16), children: [
          Text(ar ? 'القنوات المربوطة' : 'Connected channels', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          if (channels.isEmpty) emptyState(ar ? 'لسه ماربطتش فيسبوك أو إنستجرام أو واتساب.' : 'No Facebook, Instagram or WhatsApp connected yet.', icon: Icons.chat_outlined),
          for (final c in channels) Card(child: ListTile(leading: const Icon(Icons.forum_rounded), title: Text('${c['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text(ar ? (names['${c['platform']}']?.$1 ?? '${c['platform']}') : (names['${c['platform']}']?.$2 ?? '${c['platform']}')), trailing: Text('${c['status']}', style: const TextStyle(fontSize: 11)))),
          if (write) Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.icon(onPressed: () => connectMeta(context, ctx), icon: const Icon(Icons.facebook_rounded), label: Text(ar ? 'اربط بفيسبوك' : 'Connect with Facebook'))),
          const SizedBox(height: 20),
          Text(ar ? 'تأكيد الطلبات بواتساب' : 'WhatsApp order confirmation', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(children: [
            check(ready['channelConnected'] == true, ar ? 'رقم واتساب مربوط' : 'WhatsApp number connected'),
            check(ready['wabaKnown'] == true, ready['wabaKnown'] == true ? (ar ? 'معرّف حساب واتساب للأعمال محفوظ' : 'WhatsApp Business Account ID is saved') : (ar ? 'معرّف حساب واتساب للأعمال ناقص: أضفه لتسجيل القوالب' : 'WhatsApp Business Account ID is missing: add it to register templates'),
                action: ready['channelConnected'] == true && write ? FilledButton.tonal(onPressed: _wabaDialog, child: Text(ready['wabaKnown'] == true ? (ar ? 'تغيير' : 'Change') : (ar ? 'أضف المعرّف' : 'Add the ID'))) : null),
            check(ready['templatesApproved'] == true, ar ? 'قوالب الرسائل معتمدة من ميتا' : 'Message templates approved by Meta'),
            check(ready['hasCredit'] == true, ar ? 'يوجد رصيد' : 'Credit available'),
            const SizedBox(height: 6),
            Text(ar ? 'لن يُرسل شيء ولن يُخصم شيء حتى يكتمل ما سبق.' : 'Nothing is sent and nothing is charged until everything above is ready.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          ]))),
        ]));
      },
    ));
  }
}
