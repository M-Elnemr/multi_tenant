import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

/// A requested test with its results: the doctor sees what the patient uploaded (text and photo) and reviews it, optionally sharing it with the patient.
/// Returns true when something changed (the caller reloads).
Future<bool> showLabSheet(BuildContext context, Ctx ctx, Map<String, dynamic> lab) async {
  var changed = false;
  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (sheet) => _LabSheet(ctx: ctx, lab: lab, onChanged: () => changed = true),
  );
  return changed;
}

class _LabSheet extends StatefulWidget {
  const _LabSheet({required this.ctx, required this.lab, required this.onChanged});
  final Ctx ctx;
  final Map<String, dynamic> lab;
  final VoidCallback onChanged;
  @override
  State<_LabSheet> createState() => _LabSheetState();
}

class _LabSheetState extends State<_LabSheet> {
  late Map<String, dynamic> lab = Map<String, dynamic>.from(widget.lab);
  bool busy = false;

  Future<void> _review(bool share) async {
    setState(() => busy = true);
    try {
      final r = await widget.ctx.api.post('clinic/lab-orders/${lab['id']}/review', {'shareWithPatient': share});
      if (mounted) setState(() { lab = Map<String, dynamic>.from(r as Map); busy = false; });
      widget.onChanged();
      if (mounted) toast(context, S.of(context).ar ? (share ? 'تمت المراجعة وإتاحة النتيجة للمريض' : 'تمت المراجعة') : (share ? 'Reviewed and shared with the patient' : 'Reviewed'));
    } catch (e) {
      if (mounted) { setState(() => busy = false); toast(context, errorText(e), isError: true); }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    final results = ((lab['results'] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
    final status = '${lab['status']}';
    final reviewed = status == 'REVIEWED';
    return DraggableScrollableSheet(
      expand: false, initialChildSize: 0.85, minChildSize: 0.5, maxChildSize: 0.95,
      builder: (c, scroll) => ListView(controller: scroll, padding: const EdgeInsets.fromLTRB(18, 0, 18, 28), children: [
        Row(children: [
          Text(lab['kind'] == 'RADIOLOGY' ? '🩻' : '🧪', style: const TextStyle(fontSize: 26)),
          const SizedBox(width: 10),
          Expanded(child: Text('${lab['testName']}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900))),
          StatusPill(status == 'ORDERED' ? 'REQUESTED' : status),
        ]),
        if ('${lab['instructions'] ?? ''}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: Text('${lab['instructions']}', style: const TextStyle(color: Color(0xFF6B7C8D)))),
        const SizedBox(height: 14),
        if (results.isEmpty) Card(child: Padding(padding: const EdgeInsets.all(18), child: Text(isAr ? 'لم تصل نتيجة بعد. سيصلك إشعار عندما يرفع المريض النتيجة.' : 'No result yet. You will be notified when the patient uploads it.', style: const TextStyle(color: Color(0xFF6B7C8D))))),
        for (final r in results) Card(child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [
              Icon(r['uploadedByPatient'] == true ? Icons.person_rounded : Icons.local_hospital_rounded, size: 18, color: const Color(0xFF6B7C8D)),
              const SizedBox(width: 6),
              Text(r['uploadedByPatient'] == true ? (isAr ? 'رفعه المريض' : 'Uploaded by the patient') : (isAr ? 'أضافته العيادة' : 'Added by the clinic'), style: const TextStyle(color: Color(0xFF6B7C8D), fontWeight: FontWeight.w700)),
              const Spacer(),
              Text(fmtDate(r['uploadedAt'] as String?), textDirection: TextDirection.ltr, style: const TextStyle(color: Color(0xFF8A97A5), fontSize: 12)),
            ]),
            if ('${r['resultText'] ?? ''}'.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text('${r['resultText']}', style: const TextStyle(fontSize: 15, height: 1.4))),
            if (r['fileId'] != null) Padding(padding: const EdgeInsets.only(top: 10), child: _LabImage(ctx: widget.ctx, fileId: '${r['fileId']}')),
          ]),
        )),
        const SizedBox(height: 10),
        if (results.isNotEmpty && !reviewed && widget.ctx.can('lab_result.review')) ...[
          FilledButton.icon(onPressed: busy ? null : () => _review(true), icon: const Icon(Icons.verified_rounded), label: Text(isAr ? 'تمت المراجعة وإتاحتها للمريض' : 'Reviewed, share with the patient')),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: busy ? null : () => _review(false), child: Text(isAr ? 'تمت المراجعة فقط' : 'Mark as reviewed only')),
        ],
        if (reviewed) Row(mainAxisAlignment: MainAxisAlignment.center, children: [const Icon(Icons.verified_rounded, color: Color(0xFF1E9E5A)), const SizedBox(width: 6), Text(isAr ? 'تمت مراجعة هذه النتيجة' : 'This result was reviewed', style: const TextStyle(color: Color(0xFF1E9E5A), fontWeight: FontWeight.w800))]),
      ]),
    );
  }
}

/// The patient's uploaded picture, downloaded with the doctor's own login (medical files are never public). Tap to zoom.
class _LabImage extends StatefulWidget {
  const _LabImage({required this.ctx, required this.fileId});
  final Ctx ctx;
  final String fileId;
  @override
  State<_LabImage> createState() => _LabImageState();
}

class _LabImageState extends State<_LabImage> {
  Uint8List? bytes;
  Object? error;

  @override
  void initState() {
    super.initState();
    widget.ctx.api.getBytes('files/${widget.fileId}/content').then((b) { if (mounted) setState(() => bytes = Uint8List.fromList(b)); }, onError: (e) { if (mounted) setState(() => error = e); });
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    if (error != null) return Text(isAr ? 'تعذر عرض الملف (قد يكون PDF): ${errorText(error!)}' : 'The file could not be shown (it may be a PDF): ${errorText(error!)}', style: const TextStyle(color: Color(0xFFD14343)));
    if (bytes == null) return const SizedBox(height: 120, child: Center(child: CircularProgressIndicator()));
    return GestureDetector(
      onTap: () => showDialog<void>(context: context, builder: (d) => Dialog.fullscreen(backgroundColor: Colors.black, child: Stack(children: [
        Positioned.fill(child: InteractiveViewer(minScale: 0.8, maxScale: 6, child: Center(child: Image.memory(bytes!)))),
        SafeArea(child: Align(alignment: AlignmentDirectional.topEnd, child: IconButton(icon: const Icon(Icons.close_rounded, color: Colors.white, size: 30), onPressed: () => Navigator.pop(d)))),
      ]))),
      child: ClipRRect(borderRadius: BorderRadius.circular(14), child: Image.memory(bytes!, width: double.infinity, fit: BoxFit.cover, errorBuilder: (_, __, ___) => Padding(padding: const EdgeInsets.all(12), child: Text(isAr ? 'ملف غير قابل للعرض (PDF؟)' : 'The file cannot be shown (PDF?)')))),
    );
  }
}
