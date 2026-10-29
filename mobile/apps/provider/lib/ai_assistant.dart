// ignore_for_file: use_build_context_synchronously
import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'channels.dart';
import 'common.dart';
import 'connect.dart';
import 'send_mode.dart';

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
          ReadinessCard(ctx: ctx),
          const SizedBox(height: 16),
          Text(ar ? 'القنوات المربوطة' : 'Connected channels', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          if (channels.isEmpty) emptyState(ar ? 'لسه ماربطتش فيسبوك أو إنستجرام أو واتساب.' : 'No Facebook, Instagram or WhatsApp connected yet.', icon: Icons.chat_outlined),
          for (final c in channels) Card(child: ListTile(leading: const Icon(Icons.forum_rounded), title: Text('${c['name']}', maxLines: 1, overflow: TextOverflow.ellipsis), subtitle: Text(ar ? (names['${c['platform']}']?.$1 ?? '${c['platform']}') : (names['${c['platform']}']?.$2 ?? '${c['platform']}')), trailing: Text('${c['status']}', style: const TextStyle(fontSize: 11)))),
          if (write) Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.icon(onPressed: () => connectMeta(context, ctx), icon: const Icon(Icons.facebook_rounded), label: Text(ar ? 'اربط بفيسبوك' : 'Connect with Facebook'))),
          const SizedBox(height: 20),
          Text(ar ? 'تأكيد الطلبات بواتساب' : 'WhatsApp order confirmation', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
          const SizedBox(height: 6),
          SendModePicker(ctx: ctx, value: '${(wa['settings'] as Map?)?['sendMode'] ?? 'PAID'}', enabled: write, onChanged: (m) async {
            if (await run(context, () async { await ctx.api.put('store/ai/whatsapp', {'sendMode': m}); }) && mounted) setState(() => _gen++);
          }),
          if (((wa['settings'] as Map?)?['sendMode']) == 'FREE') Padding(padding: const EdgeInsets.only(top: 8), child: Text(ar ? 'التأكيدات والتذكيرات بتتبعت مجانًا من رقمك المربوط، فمفيش قوالب ولا رصيد مطلوب.' : 'Confirmations and reminders are sent free from your linked number, so no templates or credit are needed.', style: const TextStyle(color: Color(0xFF475569)))),
          if (((wa['settings'] as Map?)?['sendMode']) != 'FREE') Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(children: [
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

/// Optional unofficial mode: the shop's own WhatsApp number linked by QR code. Off by default; the owner must read and accept the warning first.
class EvolutionCard extends StatefulWidget {
  const EvolutionCard({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<EvolutionCard> createState() => _EvolutionCardState();
}

class _EvolutionCardState extends State<EvolutionCard> {
  Map<String, dynamic>? _info, _qr;
  bool _accepted = false, _loading = true;
  final _number = TextEditingController();
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _load();
    _timer = Timer.periodic(const Duration(seconds: 6), (_) { if (mounted && _info?['enabled'] == true && _info?['status'] != 'CONNECTED') _poll(); });
  }

  @override
  void dispose() { _timer?.cancel(); _number.dispose(); super.dispose(); }

  Future<void> _load() async {
    try { final r = await widget.ctx.api.get('store/ai/evolution') as Map<String, dynamic>; if (mounted) setState(() { _info = r; _loading = false; }); } catch (_) { if (mounted) setState(() => _loading = false); }
  }

  Future<void> _poll() async {
    try {
      final q = await widget.ctx.api.get('store/ai/evolution/qr') as Map<String, dynamic>;
      if (mounted) setState(() { _qr = q; _info = {...?_info, 'status': q['status'], 'phone': q['phone']}; });
    } catch (_) { /* the next tick tries again */ }
  }

  Future<void> _enable() async {
    Map<String, dynamic>? r;
    if (await run(context, () async { r = await widget.ctx.api.post('store/ai/evolution/enable', {'acceptedRisk': true}) as Map<String, dynamic>; }) && mounted) {
      setState(() { _qr = r; _info = {...?_info, 'enabled': true, 'status': r!['status']}; });
    }
  }

  Future<void> _disable() async {
    final ar = S.of(context).ar;
    final sure = await showDialog<bool>(context: context, builder: (d) => AlertDialog(
      content: Text(ar ? 'توقف الميزة وتفصل التليفون؟' : 'Turn it off and unlink the phone?'),
      actions: [TextButton(onPressed: () => Navigator.pop(d, false), child: Text(ar ? 'إلغاء' : 'Cancel')), FilledButton(onPressed: () => Navigator.pop(d, true), child: Text(ar ? 'أوقف' : 'Turn off'))]));
    if (sure != true) return;
    if (await run(context, () async { await widget.ctx.api.post('store/ai/evolution/disable', {}); }) && mounted) setState(() { _qr = null; _accepted = false; _info = {...?_info, 'enabled': false, 'status': 'DISCONNECTED', 'phone': null}; });
  }

  Future<void> _test() async {
    final ar = S.of(context).ar;
    if (await run(context, () async { await widget.ctx.api.post('store/ai/evolution/test', {'number': _number.text.trim()}); }) && mounted) toast(context, ar ? 'اتبعتت الرسالة التجريبية' : 'The test message was sent');
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final write = widget.ctx.canWrite('ai');
    if (_loading) return const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator()));
    final i = _info;
    if (i == null || i['available'] != true) return Card(child: Padding(padding: const EdgeInsets.all(16), child: Text(ar ? 'الميزة دي مش متاحة على السيرفر ده لسه.' : 'This feature is not available on this server yet.')));
    final enabled = i['enabled'] == true, connected = enabled && i['status'] == 'CONNECTED';
    final qr = _qr?['qr'] as String?;
    return Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Expanded(child: Text(ar ? 'واتساب برقمك الخاص (غير رسمي)' : 'WhatsApp with your own number (unofficial)', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16))),
        if (enabled) Chip(visualDensity: VisualDensity.compact, label: Text(connected ? (ar ? 'مربوط' : 'Linked') : (ar ? 'في انتظار التليفون' : 'Waiting for the phone')), backgroundColor: connected ? const Color(0xFFDCFCE7) : const Color(0xFFFEF3C7)),
      ]),
      const SizedBox(height: 8),
      Text(ar ? 'اربط رقم واتساب بتاعك بمسح كود QR. بعدها البائع الذكي بيرد على العملاء على الرقم ده، وتأكيدات الطلبات وتذكيرات السلة بتتبعت كرسائل عادية من غير تكلفة للرسالة ومن غير نافذة الـ٢٤ ساعة.' : 'Link your own WhatsApp number by scanning a QR code. Then the AI seller answers customers on that number, and order confirmations and cart reminders go out as normal messages with no per-message charge and no 24-hour window.', style: const TextStyle(color: Color(0xFF475569))),
      const SizedBox(height: 10),
      Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFFFF4DD), borderRadius: BorderRadius.circular(12)), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('⚠️ ${ar ? 'اقرأ ده قبل ما تشغّلها' : 'Read this before you turn it on'}', style: const TextStyle(fontWeight: FontWeight.w900)),
        const SizedBox(height: 4),
        for (final w in ar
            ? ['ده مش واجهة واتساب للأعمال الرسمية. بيشتغل زي واتساب ويب، وشروط واتساب مبتسمحش بالأتمتة بالشكل ده.', 'واتساب ممكن يقيّد الرقم أو يحظره نهائيًا. لو حصل كده هتخسر الرقم ومحادثاته وإحنا مش هنقدر نرجّعه.', 'استخدم رقم مخصص مش خط العملاء الأساسي. خلي الرسائل قليلة وشخصية ومتبعتش رسائل مزعجة.', 'تقدر توقفها في أي وقت من هنا. وهي متوقفة لكل المتاجر لحد ما صاحب المتجر يشغّلها.']
            : ['This is NOT the official WhatsApp Business API. It works like WhatsApp Web, which WhatsApp’s terms do not allow for automation.', 'WhatsApp can restrict or permanently ban the number. If that happens you lose the number and its chats, and we cannot recover it.', 'Use a dedicated number, not your main customer line. Keep messages few and personal, and never send spam.', 'You can switch it off at any time here. It is off for every shop until its owner turns it on.'])
          Padding(padding: const EdgeInsets.only(top: 3), child: Text('• $w', style: const TextStyle(fontSize: 13))),
      ])),
      if (!enabled && write) ...[
        CheckboxListTile(contentPadding: EdgeInsets.zero, controlAffinity: ListTileControlAffinity.leading, value: _accepted, onChanged: (v) => setState(() => _accepted = v ?? false), title: Text(ar ? 'فاهم المخاطرة، وموافق إن رقم واتساب بتاعي ممكن يتحظر.' : 'I understand the risk, and I accept that my WhatsApp number may be banned.', style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14))),
        FilledButton(onPressed: _accepted ? _enable : null, child: Text(ar ? 'شغّل واربط تليفوني' : 'Turn on and link my phone')),
      ],
      if (enabled && !connected) ...[
        const SizedBox(height: 12),
        Text(ar ? '١. على التليفون افتح واتساب > الإعدادات > الأجهزة المرتبطة.\n٢. اضغط «ربط جهاز» وامسح الكود.\n٣. سيب التليفون متصل بالإنترنت. الصفحة بتتحدث لوحدها.' : '1. On the phone open WhatsApp > Settings > Linked devices.\n2. Press “Link a device” and scan the code.\n3. Keep the phone online. This page updates by itself.'),
        const SizedBox(height: 10),
        if (qr != null && qr.contains(',')) Center(child: Container(color: Colors.white, padding: const EdgeInsets.all(8), child: Image.memory(base64Decode(qr.split(',').last), width: 220, height: 220, gaplessPlayback: true)))
        else Center(child: Text(ar ? 'بنجهز كود QR…' : 'Preparing the QR code…')),
        if (_qr?['pairingCode'] != null) Padding(padding: const EdgeInsets.only(top: 8), child: Center(child: SelectableText('${ar ? 'أو اربط بالكود ده' : 'Or link with this code'}: ${_qr!['pairingCode']}', textDirection: TextDirection.ltr))),
      ],
      if (connected) ...[
        const SizedBox(height: 12),
        Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFDCFCE7), borderRadius: BorderRadius.circular(12)), child: Text('${ar ? 'تليفونك مربوط.' : 'Your phone is linked.'}${i['phone'] != null ? '  +${i['phone']}' : ''}', style: const TextStyle(fontWeight: FontWeight.w800))),
        const SizedBox(height: 6),
        Text(ar ? 'رسائل العملاء على الرقم ده بيرد عليها البائع الذكي. وتأكيدات الطلبات وتذكيرات السلة بتتبعت منه.' : 'Customer messages to this number are answered by the AI seller. Order confirmations and cart reminders are sent from it.'),
        if (write) ...[
          const SizedBox(height: 10),
          Row(children: [
            Expanded(child: TextField(controller: _number, keyboardType: TextInputType.phone, textDirection: TextDirection.ltr, onChanged: (_) => setState(() {}), decoration: InputDecoration(labelText: ar ? 'ابعت رسالة تجريبية إلى (مع كود الدولة)' : 'Send a test message to (with country code)', hintText: '201012345678', isDense: true))),
            const SizedBox(width: 8),
            FilledButton.tonal(onPressed: _number.text.replaceAll(RegExp(r'\D'), '').length < 8 ? null : _test, child: Text(ar ? 'ابعت' : 'Send')),
          ]),
        ],
      ],
      if (enabled && write) Padding(padding: const EdgeInsets.only(top: 14), child: OutlinedButton.icon(onPressed: _disable, style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFB91C1C)), icon: const Icon(Icons.link_off_rounded), label: Text(ar ? 'أوقف وافصل الربط' : 'Turn off and unlink'))),
    ])));
  }
}


/// How ready the shop's data is for the assistant (it only says what the shop wrote): the score and the gaps that cost the most.
class ReadinessCard extends StatelessWidget {
  const ReadinessCard({super.key, required this.ctx});
  final Ctx ctx;

  static const _ar = {
    'products': 'ضيف منتجاتك', 'prices': 'منتجات من غير سعر', 'photos': 'منتجات من غير صورة', 'descriptions': 'منتجات من غير وصف', 'details': 'منتجات من غير تفاصيل (الخامة، المقاس، المكونات، الضمان)',
    'categories': 'منتجات من غير قسم', 'delivery': 'حدد مناطق التوصيل ومصاريفها', 'returns': 'اكتب سياسة الاسترجاع', 'hours': 'حدد مواعيد الشغل', 'shippingText': 'اكتب سياسة التوصيل', 'address': 'ضيف عنوان الفرع', 'lessons': 'علّم المساعد حاجة عن محلك',
  };
  static const _en = {
    'products': 'Add your products', 'prices': 'Products without a price', 'photos': 'Products without a picture', 'descriptions': 'Products without a description', 'details': 'Products without details (material, size, ingredients, warranty)',
    'categories': 'Products without a category', 'delivery': 'Set your delivery areas and fees', 'returns': 'Write your returns policy', 'hours': 'Set your opening hours', 'shippingText': 'Write your delivery policy', 'address': 'Add your branch address', 'lessons': 'Teach the assistant one thing about your shop',
  };

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Async<dynamic>(
      load: () => ctx.api.get('store/ai/readiness'),
      builder: (context, data, reload) {
        final d = Map<String, dynamic>.from(data as Map);
        final score = (d['score'] as num).toInt();
        final missing = ((d['checks'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).where((c) => c['ok'] != true).take(4).toList();
        final color = score >= 85 ? const Color(0xFF1E9E5A) : score >= 60 ? const Color(0xFFB45309) : const Color(0xFFD14343);
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                Expanded(child: Text(ar ? 'جاهزية محلك للمساعد' : 'How ready your shop is for the assistant', style: const TextStyle(fontWeight: FontWeight.w900))),
                Text('$score/100', style: TextStyle(fontWeight: FontWeight.w900, fontSize: 20, color: color)),
              ]),
              const SizedBox(height: 8),
              ClipRRect(borderRadius: BorderRadius.circular(8), child: LinearProgressIndicator(value: score / 100, minHeight: 7, color: color, backgroundColor: const Color(0xFFEDF1F5))),
              const SizedBox(height: 8),
              if (missing.isEmpty) Text(ar ? 'محلك فيه كل اللي المساعد محتاجه.' : 'Your shop has everything the assistant needs.')
              else ...[
                Text(ar ? 'المساعد بيقول اللي محلك كاتبه بس. كمّل دول:' : 'The assistant only says what your shop wrote. Fill these in:', style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5)),
                for (final c in missing) Padding(padding: const EdgeInsets.only(top: 4), child: Text('• ${(ar ? _ar : _en)[c['key']] ?? c['key']}${c['count'] != null ? ' (${c['count']})' : ''}')),
              ],
            ]),
          ),
        );
      },
    );
  }
}
