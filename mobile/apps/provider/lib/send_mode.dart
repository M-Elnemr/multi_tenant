import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'settings.dart';

/// True when the shop's own WhatsApp number is linked and can be used now (no permission to read it counts as not linked).
Future<bool> ownNumberLinked(Ctx ctx) async {
  try {
    final r = await ctx.api.get('store/ai/evolution') as Map;
    return r['enabled'] == true && r['status'] == 'CONNECTED';
  } catch (_) { return false; }
}

/// Opens Settings on the tab where the own number is switched on.
void openOwnNumberSettings(BuildContext context, Ctx ctx) {
  final ar = S.of(context).ar;
  Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(ar ? 'الإعدادات' : 'Settings')), body: StoreSettings(ctx: ctx, initialTab: 'whatsapp'))));
}

/// Free (the shop's own linked number) or paid (official WhatsApp) for one WhatsApp service. Choosing Free before the number is linked opens Settings,
/// so no service silently falls back to paid messages.
class SendModePicker extends StatefulWidget {
  const SendModePicker({super.key, required this.ctx, required this.value, required this.onChanged, this.enabled = true, this.onLinked});
  final Ctx ctx;
  final String value; // PAID | FREE
  final ValueChanged<String> onChanged;
  final bool enabled;
  final ValueChanged<bool>? onLinked;
  @override
  State<SendModePicker> createState() => _SendModePickerState();
}

class _SendModePickerState extends State<SendModePicker> {
  bool? _linked;

  @override
  void initState() { super.initState(); _load(); }

  Future<void> _load() async {
    final l = await ownNumberLinked(widget.ctx);
    if (mounted) { setState(() => _linked = l); widget.onLinked?.call(l); }
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    final linked = _linked == true;
    final free = widget.value == 'FREE' && linked;
    Widget card(bool selected, String title, String hint, VoidCallback? onTap, {String? badge}) => Expanded(child: InkWell(
          borderRadius: BorderRadius.circular(16), onTap: widget.enabled ? onTap : null,
          child: Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(borderRadius: BorderRadius.circular(16), border: Border.all(color: selected ? Theme.of(context).colorScheme.primary : const Color(0xFFD9E1E8), width: selected ? 2 : 1), color: selected ? Theme.of(context).colorScheme.primary.withValues(alpha: 0.06) : null),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [Expanded(child: Text(title, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 13.5))), if (badge != null) Container(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2), decoration: BoxDecoration(color: const Color(0xFFFEF3C7), borderRadius: BorderRadius.circular(10)), child: Text(badge, style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w800)))]),
              const SizedBox(height: 4),
              Text(hint, style: const TextStyle(fontSize: 11.5, color: Color(0xFF475569))),
            ]))));
    return Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
      card(free, ar ? '🆓 مجاني: رقمي' : '🆓 Free: my number', linked ? (ar ? 'من رقمك المربوط. بدون تكلفة ولا نافذة ٢٤ ساعة.' : 'From your linked number. No charge, no 24-hour window.') : (ar ? 'لسه مش مربوط. اضغط واربطه من الإعدادات.' : 'Not linked yet. Tap to link it in Settings.'),
          () => linked ? widget.onChanged('FREE') : openOwnNumberSettings(context, widget.ctx), badge: !linked && _linked != null ? (ar ? 'اربط الأول' : 'Link first') : null),
      const SizedBox(width: 8),
      card(!free, ar ? '💳 مدفوع: الرسمي' : '💳 Paid: official', ar ? 'واتساب للأعمال الرسمي وبيتخصم من رصيدك.' : 'Official WhatsApp Business, charged from your credit.', () => widget.onChanged('PAID')),
    ]);
  }
}
