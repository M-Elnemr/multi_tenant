import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';

/// Small declarative form kit so every settings page is a list of fields instead of hand-written widgets.
enum FK { text, area, ltr, number, money, toggle, image, csv, hours, color, choice, faqs, pairs, gallery, chips }

class F {
  const F(this.kind, this.key, this.ar, this.en, {this.options, this.category = 'PRODUCT_IMAGE', this.hint, this.required = false});
  final FK kind;
  final String key, ar, en;
  final Map<String, String>? options; // value -> label (Arabic|English separated by '|')
  final String category;
  final String? hint;
  final bool required;
  String label(bool isAr) => isAr ? ar : en;
}

String fmtMoney(num? minor) => minor == null ? '' : (minor / 100).toStringAsFixed(2);
int toMinor(String s) => ((double.tryParse(s.trim().replaceAll(',', '.')) ?? 0) * 100).round();

String fileUrl(Ctx c, String id, {String variant = 'thumb'}) => 'https://${c.host}/api/v1/files/$id/content?variant=$variant';

/// Picks a photo, uploads it through presign + PUT and returns the file id (null when cancelled).
Future<String?> pickAndUpload(BuildContext context, ApiClient api, String category, {String visibility = 'PUBLIC'}) async {
  final isAr = S.of(context).ar;
  final source = await showModalBottomSheet<ImageSource>(
    context: context,
    showDragHandle: true,
    builder: (c) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
      ListTile(leading: const Icon(Icons.photo_camera_outlined), title: Text(isAr ? 'الكاميرا' : 'Camera'), onTap: () => Navigator.pop(c, ImageSource.camera)),
      ListTile(leading: const Icon(Icons.photo_library_outlined), title: Text(isAr ? 'المعرض' : 'Gallery'), onTap: () => Navigator.pop(c, ImageSource.gallery)),
    ])),
  );
  if (source == null) return null;
  final f = await ImagePicker().pickImage(source: source, maxWidth: 2200, imageQuality: 85);
  if (f == null) return null;
  final bytes = await f.readAsBytes();
  final type = f.path.toLowerCase().endsWith('.png') ? 'image/png' : 'image/jpeg';
  final p = await api.post('files/presign', {'filename': f.name, 'contentType': type, 'size': bytes.length, 'category': category, 'visibility': visibility}) as Map<String, dynamic>;
  await api.putFile(p['uploadUrl'] as String, bytes, type);
  return p['fileId'] as String;
}

/// Copyable text row (DNS records, activation PINs, ...).
class CopyRow extends StatelessWidget {
  const CopyRow(this.label, this.value, {super.key});
  final String label, value;
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(children: [
          SizedBox(width: 56, child: Text(label, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 12))),
          Expanded(child: SelectableText(value, textDirection: TextDirection.ltr, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
          IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.copy_rounded, size: 18), onPressed: () { Clipboard.setData(ClipboardData(text: value)); toast(context, S.of(context).ar ? 'تم النسخ' : 'Copied'); }),
        ]),
      );
}

Widget sectionTitle(String t) => Padding(padding: const EdgeInsets.only(top: 14, bottom: 6), child: Text(t, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 15)));

/// Modal bottom sheet with a title, a body and a save button. Returns true when the action succeeded.
Future<bool> formSheet(BuildContext context, {required String title, required List<Widget> Function(BuildContext, StateSetter) body, required Future<void> Function() onSave, String? saveLabel, bool Function()? canSave}) async {
  final isAr = S.of(context).ar;
  final ok = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (ctx) {
      var busy = false;
      return StatefulBuilder(builder: (ctx, setM) => Padding(
        padding: EdgeInsets.fromLTRB(20, 0, 20, MediaQuery.of(ctx).viewInsets.bottom + 20),
        child: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(title, style: Theme.of(ctx).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)),
          const SizedBox(height: 12),
          ...body(ctx, setM),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: busy || (canSave != null && !canSave()) ? null : () async {
              setM(() => busy = true);
              final done = await run(ctx, onSave);
              if (done && ctx.mounted) { Navigator.of(ctx).pop(true); } else if (ctx.mounted) { setM(() => busy = false); }
            },
            child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(saveLabel ?? (isAr ? 'حفظ' : 'Save')),
          ),
        ])),
      ));
    },
  );
  return ok == true;
}

Future<bool> confirm(BuildContext context, String message) async {
  final isAr = S.of(context).ar;
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(content: Text(message), actions: [
      TextButton(onPressed: () => Navigator.pop(c, false), child: Text(isAr ? 'إلغاء' : 'Cancel')),
      FilledButton(onPressed: () => Navigator.pop(c, true), child: Text(isAr ? 'تأكيد' : 'Confirm')),
    ]),
  );
  return r == true;
}

/// A plain labelled text field bound to a controller (for sheets).
Widget tf(TextEditingController c, String label, {bool ltr = false, bool number = false, int lines = 1, String? hint, ValueChanged<String>? onChanged}) => Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TextField(
        controller: c,
        maxLines: lines,
        onChanged: onChanged,
        textDirection: ltr ? TextDirection.ltr : null,
        keyboardType: number ? const TextInputType.numberWithOptions(decimal: true) : (lines > 1 ? TextInputType.multiline : TextInputType.text),
        decoration: InputDecoration(labelText: label, hintText: hint),
      ),
    );

const _days = ['sat', 'sun', 'mon', 'tue', 'wed', 'thu', 'fri'];
const _daysAr = {'sat': 'السبت', 'sun': 'الأحد', 'mon': 'الاثنين', 'tue': 'الثلاثاء', 'wed': 'الأربعاء', 'thu': 'الخميس', 'fri': 'الجمعة'};
const _daysEn = {'sat': 'Saturday', 'sun': 'Sunday', 'mon': 'Monday', 'tue': 'Tuesday', 'wed': 'Wednesday', 'thu': 'Thursday', 'fri': 'Friday'};

Map<String, dynamic> defaultHours() => {for (final d in _days) d: {'open': '10:00', 'close': '22:00', 'closed': d == 'fri'}};

/// Edits one set of fields. Loads [loadPath] (or uses [initial]), saves the field values to [savePath].
class FormCard extends StatefulWidget {
  const FormCard({super.key, required this.ctx, required this.titleAr, required this.titleEn, required this.fields, this.loadPath, this.initial, required this.savePath, this.readOnly = false, this.method = 'PATCH', this.transform, this.onSaved, this.subtitleAr, this.subtitleEn, this.pick});
  final Ctx ctx;
  final String titleAr, titleEn;
  final String? subtitleAr, subtitleEn;
  final List<F> fields;
  final String? loadPath;
  final Map<String, dynamic>? initial;
  final String savePath, method;
  final bool readOnly; // read only access: shows the values, offers no way to change them
  final Map<String, dynamic> Function(Map<String, dynamic> body)? transform;
  final VoidCallback? onSaved;
  final Map<String, dynamic> Function(Map<String, dynamic> loaded)? pick; // adapt the loaded JSON to the field keys
  @override
  State<FormCard> createState() => _FormCardState();
}

class _FormCardState extends State<FormCard> {
  Map<String, dynamic>? m;
  Object? error;
  bool busy = false;
  final _ctrls = <String, TextEditingController>{};

  @override
  void initState() { super.initState(); _load(); }

  @override
  void dispose() { for (final c in _ctrls.values) { c.dispose(); } super.dispose(); }

  Future<void> _load() async {
    try {
      final raw = widget.initial ?? Map<String, dynamic>.from(await widget.ctx.api.get(widget.loadPath!) as Map);
      final d = widget.pick != null ? widget.pick!(raw) : raw;
      if (mounted) setState(() { m = Map<String, dynamic>.from(d); error = null; });
    } catch (e) {
      if (mounted) setState(() => error = e);
    }
  }

  TextEditingController _c(F f) => _ctrls.putIfAbsent(f.key, () {
        final v = m![f.key];
        return TextEditingController(text: f.kind == FK.money ? fmtMoney(v as num?) : (v == null ? '' : '$v'));
      });

  Future<void> _save() async {
    setState(() => busy = true);
    final body = <String, dynamic>{};
    for (final f in widget.fields) {
      final v = m![f.key];
      if (v == null && f.kind != FK.image && f.kind != FK.number) continue; // untouched empty fields are not sent
      body[f.key] = v;
    }
    final ok = await run(context, () async {
      final b = widget.transform != null ? widget.transform!(body) : body;
      switch (widget.method) {
        case 'PUT': await widget.ctx.api.put(widget.savePath, b);
        case 'POST': await widget.ctx.api.post(widget.savePath, b);
        default: await widget.ctx.api.patch(widget.savePath, b);
      }
    });
    if (!mounted) return;
    setState(() => busy = false);
    if (ok) { toast(context, S.of(context).ar ? 'تم الحفظ' : 'Saved'); widget.onSaved?.call(); }
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return Card(child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(isAr ? widget.titleAr : widget.titleEn, style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        if (widget.subtitleAr != null) Padding(padding: const EdgeInsets.only(top: 2), child: Text(isAr ? widget.subtitleAr! : widget.subtitleEn!, style: const TextStyle(color: Color(0xFF6B7C8D), fontSize: 12.5))),
        const SizedBox(height: 10),
        if (m == null && error == null) const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator())),
        if (error != null) Column(children: [Text(errorText(error!)), TextButton(onPressed: () { setState(() => error = null); _load(); }, child: Text(S.of(context).retry))]),
        if (m != null) ...[
          IgnorePointer(ignoring: widget.readOnly, child: Opacity(opacity: widget.readOnly ? 0.65 : 1, child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [for (final f in widget.fields) _field(context, f, isAr)]))),
          if (!widget.readOnly) const SizedBox(height: 6),
          if (!widget.readOnly) FilledButton(onPressed: busy ? null : _save, child: busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(isAr ? 'حفظ' : 'Save')),
        ],
      ]),
    ));
  }

  Widget _field(BuildContext context, F f, bool isAr) {
    final label = f.label(isAr);
    switch (f.kind) {
      case FK.text:
      case FK.area:
      case FK.ltr:
      case FK.number:
      case FK.money:
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: TextField(
            controller: _c(f),
            maxLines: f.kind == FK.area ? 3 : 1,
            textDirection: f.kind == FK.ltr || f.kind == FK.number || f.kind == FK.money ? TextDirection.ltr : null,
            keyboardType: f.kind == FK.number || f.kind == FK.money ? const TextInputType.numberWithOptions(decimal: true) : (f.kind == FK.area ? TextInputType.multiline : TextInputType.text),
            decoration: InputDecoration(labelText: label, helperText: f.hint),
            onChanged: (v) {
              if (f.kind == FK.number) { m![f.key] = v.trim().isEmpty ? null : int.tryParse(v.trim()); }
              else if (f.kind == FK.money) { m![f.key] = v.trim().isEmpty ? null : toMinor(v); }
              else { m![f.key] = v; }
            },
          ),
        );
      case FK.toggle:
        return SwitchListTile(contentPadding: EdgeInsets.zero, title: Text(label), value: m![f.key] == true, onChanged: (v) => setState(() => m![f.key] = v));
      case FK.color:
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: _ColorField(label: label, value: (m![f.key] as String?) ?? '#0B6BCB', onChanged: (v) => setState(() => m![f.key] = v)));
      case FK.choice:
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: DropdownButtonFormField<String>(
            initialValue: f.options!.containsKey(m![f.key]) ? m![f.key] as String : null,
            decoration: InputDecoration(labelText: label),
            items: [for (final e in f.options!.entries) DropdownMenuItem(value: e.key, child: Text(e.value.contains('|') ? e.value.split('|')[isAr ? 0 : 1] : e.value))],
            onChanged: (v) => setState(() => m![f.key] = v),
          ),
        );
      case FK.csv:
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: TextField(
            controller: _ctrls.putIfAbsent(f.key, () => TextEditingController(text: ((m![f.key] as List?) ?? const []).join(', '))),
            decoration: InputDecoration(labelText: label, helperText: f.hint ?? (isAr ? 'افصل بينها بفاصلة' : 'Separate with commas')),
            onChanged: (v) => m![f.key] = v.split(RegExp(r'[,،]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList(),
          ),
        );
      case FK.chips:
        final sel = ((m![f.key] as List?) ?? const []).cast<String>().toSet();
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final e in f.options!.entries)
              FilterChip(
                label: Text(e.value.contains('|') ? e.value.split('|')[isAr ? 0 : 1] : e.value),
                selected: sel.contains(e.key),
                selectedColor: Theme.of(context).colorScheme.primary,
                checkmarkColor: Colors.white,
                labelStyle: TextStyle(fontWeight: FontWeight.w700, color: sel.contains(e.key) ? Colors.white : const Color(0xFF1F2A37)),
                onSelected: (v) => setState(() { v ? sel.add(e.key) : sel.remove(e.key); m![f.key] = sel.toList(); }),
              ),
          ]),
        ]));
      case FK.image:
        final id = m![f.key] as String?;
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: Row(children: [
          if (id != null && id.isNotEmpty) ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(fileUrl(widget.ctx, id), width: 64, height: 64, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 64, height: 64))),
          if (id != null && id.isNotEmpty) const SizedBox(width: 10),
          Expanded(child: OutlinedButton.icon(icon: const Icon(Icons.add_photo_alternate_outlined), label: Text(label), onPressed: () async {
            String? nid;
            final ok = await run(context, () async { nid = await pickAndUpload(context, widget.ctx.api, f.category); });
            if (ok && nid != null) setState(() => m![f.key] = nid);
          })),
          if (id != null && id.isNotEmpty) IconButton(icon: const Icon(Icons.close_rounded), onPressed: () => setState(() => m![f.key] = null)),
        ]));
      case FK.gallery:
        final ids = ((m![f.key] as List?) ?? const []).cast<String>().toList();
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
          const SizedBox(height: 6),
          Wrap(spacing: 8, runSpacing: 8, children: [
            for (final id in ids) Stack(children: [
              ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(fileUrl(widget.ctx, id), width: 76, height: 76, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 76, height: 76))),
              PositionedDirectional(top: 0, end: 0, child: InkWell(onTap: () => setState(() => m![f.key] = ids.where((x) => x != id).toList()), child: const CircleAvatar(radius: 11, backgroundColor: Colors.black54, child: Icon(Icons.close, size: 14, color: Colors.white)))),
            ]),
            InkWell(
              onTap: () async {
                String? nid;
                final ok = await run(context, () async { nid = await pickAndUpload(context, widget.ctx.api, f.category); });
                if (ok && nid != null) setState(() => m![f.key] = [...ids, nid!]);
              },
              child: Container(width: 76, height: 76, decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(10)), child: const Icon(Icons.add_photo_alternate_outlined)),
            ),
          ]),
        ]));
      case FK.pairs:
      case FK.faqs:
        final pairs = f.kind == FK.pairs;
        final k1 = pairs ? 'k' : 'q', k2 = pairs ? 'v' : 'a';
        final list = ((m![f.key] as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
          for (var i = 0; i < list.length; i++) Card(margin: const EdgeInsets.only(top: 8), child: Padding(padding: const EdgeInsets.all(10), child: Column(children: [
            TextFormField(initialValue: '${list[i][k1] ?? ''}', decoration: InputDecoration(labelText: pairs ? (isAr ? 'الاسم' : 'Name') : (isAr ? 'السؤال' : 'Question')), onChanged: (v) { list[i][k1] = v; m![f.key] = list; }),
            TextFormField(initialValue: '${list[i][k2] ?? ''}', maxLines: pairs ? 1 : 2, decoration: InputDecoration(labelText: pairs ? (isAr ? 'القيمة' : 'Value') : (isAr ? 'الإجابة' : 'Answer')), onChanged: (v) { list[i][k2] = v; m![f.key] = list; }),
            Align(alignment: AlignmentDirectional.centerEnd, child: TextButton.icon(onPressed: () => setState(() { list.removeAt(i); m![f.key] = list; }), icon: const Icon(Icons.delete_outline), label: Text(isAr ? 'حذف' : 'Remove'))),
          ]))),
          TextButton.icon(onPressed: () => setState(() { list.add({k1: '', k2: ''}); m![f.key] = list; }), icon: const Icon(Icons.add), label: Text(pairs ? (isAr ? 'إضافة' : 'Add') : (isAr ? 'إضافة سؤال' : 'Add question'))),
        ]));
      case FK.hours:
        final h = Map<String, dynamic>.from((m![f.key] as Map?) ?? {});
        return Padding(padding: const EdgeInsets.only(bottom: 10), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w700))),
            TextButton(onPressed: () => setState(() => m![f.key] = defaultHours()), child: Text(isAr ? 'افتراضي' : 'Default')),
          ]),
          for (final d in _days) _hoursRow(context, h, d, isAr, f.key),
        ]));
    }
  }

  Widget _hoursRow(BuildContext context, Map<String, dynamic> h, String d, bool isAr, String key) {
    final day = Map<String, dynamic>.from((h[d] as Map?) ?? {'open': '10:00', 'close': '22:00', 'closed': true});
    final closed = day['closed'] == true;
    Future<void> pick(String which) async {
      final parts = '${day[which] ?? '10:00'}'.split(':');
      final t = await showTimePicker(context: context, initialTime: TimeOfDay(hour: int.tryParse(parts[0]) ?? 10, minute: int.tryParse(parts.length > 1 ? parts[1] : '0') ?? 0));
      if (t == null) return;
      day[which] = '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
      h[d] = day;
      setState(() => m![key] = h);
    }
    return Row(children: [
      SizedBox(width: 78, child: Text(isAr ? _daysAr[d]! : _daysEn[d]!)),
      Switch(value: !closed, onChanged: (v) { day['closed'] = !v; h[d] = day; setState(() => m![key] = h); }),
      if (closed) Text(isAr ? 'مغلق' : 'Closed', style: const TextStyle(color: Color(0xFF6B7C8D))) else ...[
        OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 34), padding: const EdgeInsets.symmetric(horizontal: 10)), onPressed: () => pick('open'), child: Text('${day['open']}')),
        const Padding(padding: EdgeInsets.symmetric(horizontal: 6), child: Text('-')),
        OutlinedButton(style: OutlinedButton.styleFrom(minimumSize: const Size(0, 34), padding: const EdgeInsets.symmetric(horizontal: 10)), onPressed: () => pick('close'), child: Text('${day['close']}')),
      ],
    ]);
  }
}

class _ColorField extends StatelessWidget {
  const _ColorField({required this.label, required this.value, required this.onChanged});
  final String label, value;
  final ValueChanged<String> onChanged;
  static const _palette = ['#0B6BCB', '#0F766E', '#15803D', '#B45309', '#BE123C', '#7C3AED', '#1F2937', '#E11D48', '#0891B2', '#CA8A04'];
  Color _c(String hex) => Color(int.tryParse('FF${hex.replaceAll('#', '')}', radix: 16) ?? 0xFF0B6BCB);
  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: const TextStyle(fontWeight: FontWeight.w700)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final p in _palette)
            GestureDetector(
              onTap: () => onChanged(p),
              child: Container(width: 34, height: 34, decoration: BoxDecoration(color: _c(p), shape: BoxShape.circle, border: Border.all(color: value.toLowerCase() == p.toLowerCase() ? Colors.black : Colors.transparent, width: 3))),
            ),
        ]),
      ]);
}
