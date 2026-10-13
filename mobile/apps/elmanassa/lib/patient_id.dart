import 'package:barcode_widget/barcode_widget.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:platform_core/platform_core.dart';
import 'package:qr_flutter/qr_flutter.dart';

/// The patient's platform number with a QR and a barcode of the same number. A clinic scans it (or types the number) to add the patient.
/// The last answer is kept on the phone, so the card also shows without internet.
class PatientIdCard extends StatelessWidget {
  const PatientIdCard({super.key, required this.api, this.margin = const EdgeInsets.fromLTRB(16, 8, 16, 8)});
  final ApiClient api;
  final EdgeInsets margin;

  @override
  Widget build(BuildContext context) => CachedAsync<Map<String, dynamic>>(
        cacheKey: 'patient.id',
        load: () async => Map<String, dynamic>.from(await api.get('me/patient') as Map),
        builder: (context, d, reload) {
          final id = '${d['publicId'] ?? ''}';
          if (id.isEmpty) return const SizedBox.shrink();
          return FadeSlideIn(child: _Card(id: id, name: '${d['displayName'] ?? ''}', margin: margin));
        },
      );
}

class _Card extends StatelessWidget {
  const _Card({required this.id, required this.name, required this.margin});
  final String id, name;
  final EdgeInsets margin;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final c = Theme.of(context).colorScheme.primary;
    return Card(
      margin: margin,
      child: InkWell(
        borderRadius: BorderRadius.circular(20),
        onTap: () => showModalBottomSheet<void>(context: context, isScrollControlled: true, showDragHandle: true, builder: (_) => _Big(id: id, name: name)),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(children: [
            Container(padding: const EdgeInsets.all(6), decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0xFFE2EAF2))), child: QrImageView(data: 'TABIB:$id', size: 84, padding: EdgeInsets.zero)),
            const SizedBox(width: 16),
            Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(s.ar ? 'رقم المريض' : 'Patient ID', style: const TextStyle(color: Color(0xFF6B7C8D), fontWeight: FontWeight.w700)),
              Text(id, textDirection: TextDirection.ltr, style: TextStyle(fontSize: 30, fontWeight: FontWeight.w900, letterSpacing: 4, color: c)),
              Text(s.ar ? 'اضغط لعرض الباركود بحجم كبير' : 'Tap to show the large QR and barcode', style: const TextStyle(color: Color(0xFF8A97A5), fontSize: 12)),
            ])),
            IconButton(tooltip: s.ar ? 'نسخ' : 'Copy', icon: const Icon(Icons.copy_rounded), onPressed: () { Clipboard.setData(ClipboardData(text: id)); ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(s.ar ? 'تم نسخ الرقم' : 'Copied'))); }),
          ]),
        ),
      ),
    );
  }
}

/// Full-width QR and barcode on a white background: easy for the clinic's scanner to read.
class _Big extends StatelessWidget {
  const _Big({required this.id, required this.name});
  final String id, name;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          if (name.isNotEmpty) Text(name, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w900)),
          const SizedBox(height: 4),
          Text(id, textDirection: TextDirection.ltr, style: TextStyle(fontSize: 40, fontWeight: FontWeight.w900, letterSpacing: 8, color: Theme.of(context).colorScheme.primary)),
          const SizedBox(height: 14),
          Container(color: Colors.white, padding: const EdgeInsets.all(12), child: QrImageView(data: 'TABIB:$id', size: 240)),
          const SizedBox(height: 14),
          Container(color: Colors.white, padding: const EdgeInsets.all(12), child: BarcodeWidget(barcode: Barcode.code128(), data: id, width: 280, height: 70, drawText: false)),
          const SizedBox(height: 12),
          Text(s.ar ? 'اعرض هذا الكود في العيادة لإضافتك بسرعة' : 'Show this at the clinic to be added quickly', style: const TextStyle(color: Color(0xFF6B7C8D))),
        ]),
      ),
    );
  }
}
