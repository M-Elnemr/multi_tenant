import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'channels.dart';
import 'forms.dart';
import 'retail.dart' show CodeScanPage;

List<Map<String, dynamic>> _maps(dynamic v) => ((v as List?) ?? const []).map((e) => Map<String, dynamic>.from(e as Map)).toList();

String? _mediaUrl(Ctx c, Map<String, dynamic> m, {String variant = 'thumb'}) {
  final mb = m['mediaBase'], ext = m['mediaExt'];
  if (mb is String && ext is String) return 'https://${c.host}$mb-$variant.$ext';
  final u = m['url'] as String?;
  if (u == null || u.isEmpty) return null;
  final full = u.startsWith('http') ? u : 'https://${c.host}$u';
  return full.contains('/files/') ? '$full${full.contains('?') ? '&' : '?'}variant=$variant' : full;
}

// ---------------------------------------------------------------- Taxonomy picker

class TaxonomyPage extends StatefulWidget {
  const TaxonomyPage({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<TaxonomyPage> createState() => _TaxonomyPageState();
}

class _TaxonomyPageState extends State<TaxonomyPage> {
  final _q = TextEditingController();
  List<Map<String, dynamic>>? _results, _children, _suggested;
  final _path = <Map<String, dynamic>>[];
  Object? _error;
  String get _lang => AppLocale.isAr ? 'ar' : 'en';

  @override
  void initState() {
    super.initState();
    _loadChildren();
    widget.ctx.api.get('store/taxonomy/suggested', query: {'lang': _lang}).then((r) { if (mounted) setState(() => _suggested = _maps(r)); }).catchError((_) {});
  }

  @override
  void dispose() { _q.dispose(); super.dispose(); }

  Future<void> _loadChildren() async {
    setState(() { _children = null; _error = null; });
    try {
      final r = await widget.ctx.api.get('store/taxonomy/children', query: {'lang': _lang, if (_path.isNotEmpty) 'parent': '${_path.last['id']}'});
      if (mounted) setState(() => _children = _maps(r));
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _search(String v) async {
    if (v.trim().length < 2) { setState(() => _results = null); return; }
    try {
      final r = await widget.ctx.api.get('store/taxonomy/search', query: {'lang': _lang, 'q': v.trim()});
      if (mounted && _q.text == v) setState(() => _results = _maps(r));
    } catch (_) { if (mounted) setState(() => _results = []); }
  }

  Future<void> _suggest() async {
    final isAr = S.of(context).ar;
    final name = TextEditingController(), note = TextEditingController();
    if (await formSheet(context, title: isAr ? 'اقتراح تصنيف جديد' : 'Suggest a category', canSave: () => name.text.trim().isNotEmpty,
        body: (x, set) => [Text(isAr ? 'لا يمكن للمتاجر إنشاء تصنيفات. سنراجع اقتراحك.' : 'Shops cannot create categories. We will review your suggestion.'), const SizedBox(height: 10), tf(name, isAr ? 'اسم التصنيف' : 'Category name', onChanged: (_) => set(() {})), tf(note, isAr ? 'ملاحظة' : 'Note', lines: 2)],
        onSave: () async { await widget.ctx.api.post('store/taxonomy/requests', {'name': name.text.trim(), 'note': note.text.trim()}); }) && mounted) {
      toast(context, isAr ? 'تم إرسال الاقتراح' : 'Suggestion sent');
    }
  }

  Widget _tile(Map<String, dynamic> n, {bool crumb = false}) {
    final isAr = S.of(context).ar;
    final has = n['hasChildren'] == true;
    return ListTile(
      title: Text('${n['icon'] ?? ''} ${n['name']}'.trim(), style: const TextStyle(fontWeight: FontWeight.w700)),
      subtitle: crumb ? Text('${n['breadcrumb']}', maxLines: 1, overflow: TextOverflow.ellipsis) : null,
      trailing: has && !crumb
          ? Row(mainAxisSize: MainAxisSize.min, children: [
              if ((n['level'] as num? ?? 0) >= 2) TextButton(onPressed: () => Navigator.pop(context, n), child: Text(isAr ? 'اختر هذا' : 'Pick this')),
              Icon(isAr ? Icons.chevron_left_rounded : Icons.chevron_right_rounded),
            ])
          : null,
      onTap: () { if (has && !crumb) { setState(() => _path.add(n)); _loadChildren(); } else { Navigator.pop(context, n); } },
    );
  }

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(isAr ? 'اختر التصنيف' : 'Choose a category')),
      body: Column(children: [
        Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 4), child: TextField(controller: _q, onChanged: _search, decoration: InputDecoration(prefixIcon: const Icon(Icons.search_rounded), hintText: isAr ? 'ابحث بأي كلمة' : 'Search any word'))),
        Expanded(child: _results != null
            ? ListView(children: [for (final n in _results!) _tile(n, crumb: true), if (_results!.isEmpty) Padding(padding: const EdgeInsets.all(24), child: Center(child: Text(isAr ? 'لا نتائج' : 'No matches')))])
            : ListView(children: [
                if (_path.isEmpty && (_suggested?.isNotEmpty ?? false)) Padding(padding: const EdgeInsets.fromLTRB(16, 8, 16, 4), child: Wrap(spacing: 6, runSpacing: 6, children: [
                  for (final n in _suggested!.take(18)) ActionChip(label: Text('${n['name']}'), onPressed: () => Navigator.pop(context, n)),
                ])),
                Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Wrap(crossAxisAlignment: WrapCrossAlignment.center, children: [
                  TextButton(onPressed: () { setState(_path.clear); _loadChildren(); }, child: Text(isAr ? 'الكل' : 'All')),
                  for (var i = 0; i < _path.length; i++) ...[const Text('/'), TextButton(onPressed: () { setState(() => _path.removeRange(i + 1, _path.length)); _loadChildren(); }, child: Text('${_path[i]['name']}'))],
                ])),
                if (_error != null) Center(child: TextButton(onPressed: _loadChildren, child: Text(errorText(_error!)))),
                if (_children == null && _error == null) const Padding(padding: EdgeInsets.all(24), child: Center(child: CircularProgressIndicator())),
                if (_children != null) for (final n in _children!) _tile(n),
              ])),
        TextButton(onPressed: _suggest, child: Text(isAr ? 'تصنيفك غير موجود؟ اقترحه' : 'Missing a category? Suggest it')),
      ]),
    );
  }
}

// ---------------------------------------------------------------- Product form (create + edit)

class _Opt {
  _Opt(this.kind, {this.scale = 'APPAREL'});
  final String kind; // COLOR | SIZE | CUSTOM
  String scale;
  final values = <String>{};
  final name = TextEditingController(), custom = TextEditingController();
  Map<String, dynamic> toRequest() => switch (kind) {
        'COLOR' => {'name': 'اللون', 'attribute': 'COLOR', 'values': values.toList()},
        'SIZE' => {'name': 'المقاس', 'attribute': 'SIZE', 'sizeScale': scale, 'values': values.toList()},
        _ => {'name': name.text.trim(), 'values': custom.text.split(RegExp(r'[,،]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList()},
      };
}

List<Map<String, String>> _combos(List<Map<String, dynamic>> opts) => opts.fold<List<Map<String, String>>>([{}], (acc, o) => [for (final c in acc) for (final v in (o['values'] as List).cast<String>()) {...c, o['name'] as String: v}]);

class ProductFormPage extends StatefulWidget {
  const ProductFormPage({super.key, required this.ctx, this.id});
  final Ctx ctx;
  final String? id;
  @override
  State<ProductFormPage> createState() => _ProductFormPageState();
}

class _ProductFormPageState extends State<ProductFormPage> {
  Ctx get ctx => widget.ctx;
  bool get isNew => widget.id == null;
  final _name = TextEditingController(), _desc = TextEditingController(), _brand = TextEditingController();
  Map<String, dynamic>? _detail, _picked, _attrs;
  List<Map<String, dynamic>> _branches = [];
  String _status = 'ACTIVE', _condition = 'NEW', _audience = '';
  String? _branchId;
  final _images = <String>[]; // create mode only
  final _imageColor = <String, String>{};   // picture id -> the option value (colour) it is for; missing = all colours
  String _newColor = '';   // edit mode: the colour the next uploaded picture is for
  final _opts = <_Opt>[];
  final _rows = <String, Map<String, TextEditingController>>{};
  final _prices = <String, TextEditingController>{};
  final _barcodes = <String, TextEditingController>{};
  Object? _error;
  bool _busy = false;
  final int _skuSeed = DateTime.now().millisecondsSinceEpoch % 100000;

  @override
  void initState() { super.initState(); _init(); }

  @override
  void dispose() { _name.dispose(); _desc.dispose(); _brand.dispose(); super.dispose(); }

  Future<void> _init() async {
    try {
      if (isNew) {
        _branches = _maps(await ctx.api.get('store/branches'));
        _branchId = _branches.isEmpty ? null : '${_branches.first['id']}';
        ctx.api.get('store/taxonomy/attributes', query: {'lang': AppLocale.isAr ? 'ar' : 'en'}).then((r) { if (mounted) setState(() => _attrs = Map<String, dynamic>.from(r as Map)); }).catchError((_) {});
        if (mounted) setState(() => _detail = {});
      } else {
        final d = Map<String, dynamic>.from(await ctx.api.get('store/products/${widget.id}') as Map);
        _name.text = '${d['name'] ?? ''}'; _desc.text = '${d['description'] ?? ''}'; _brand.text = '${d['brand'] ?? ''}';
        _status = '${d['status'] ?? 'ACTIVE'}'; _condition = '${d['itemCondition'] ?? 'NEW'}'; _audience = '${d['audience'] ?? ''}';
        _prices.clear();
        _barcodes.clear();
        for (final v in _maps(d['variants'])) { _prices['${v['id']}'] = TextEditingController(text: fmtMoney(v['priceMinor'] as num?)); _barcodes['${v['id']}'] = TextEditingController(text: '${v['barcode'] ?? ''}'); }
        if (mounted) setState(() => _detail = d);
      }
    } catch (e) {
      if (mounted) setState(() => _error = e);
    }
  }

  Future<void> _reloadDetail() async {
    final d = Map<String, dynamic>.from(await ctx.api.get('store/products/${widget.id}') as Map);
    if (mounted) setState(() => _detail = d);
  }

  Future<void> _pickCategory() async {
    final n = await Navigator.of(context).push<Map<String, dynamic>>(MaterialPageRoute(builder: (_) => TaxonomyPage(ctx: ctx)));
    if (n != null && mounted) setState(() { _picked = n; if (n['appliesAudience'] != true) _audience = ''; });
  }

  List<String> get _scales { final s = ((_picked?['sizeScales'] as List?) ?? const []).cast<String>(); return s.isEmpty ? ['APPAREL', 'SHOE', 'KIDS', 'BED'] : s; }

  Future<void> _save() async {
    final isAr = S.of(context).ar;
    setState(() => _busy = true);
    final ok = await run(context, () async {
      if (isNew) {
        final parsed = _opts.map((o) => o.toRequest()).where((o) => (o['name'] as String).isNotEmpty && (o['values'] as List).isNotEmpty).toList();
        final combos = _combos(parsed);
        final variants = <Map<String, dynamic>>[];
        for (var i = 0; i < combos.length; i++) {
          final r = _rowFor(combos[i], i);
          variants.add({'sku': r['sku']!.text.trim(), if (r['barcode']!.text.trim().isNotEmpty) 'barcode': r['barcode']!.text.trim(), 'priceMinor': toMinor(r['price']!.text), 'optionValues': combos[i], 'stock': [{'branchId': _branchId, 'quantity': int.tryParse(r['stock']!.text.trim()) ?? 0}]});
        }
        final res = await ctx.api.post('store/products', {
          'name': _name.text.trim(), 'description': _desc.text.trim(), if (_picked != null) 'taxonomyId': _picked!['id'], if (_audience.isNotEmpty) 'audience': _audience,
          'condition': _condition, if (_brand.text.trim().isNotEmpty) 'brand': _brand.text.trim(), 'status': _status, 'options': parsed, 'variants': variants, 'media': [for (final id in _images) {'fileId': id, if (_colorValues(parsed).contains(_imageColor[id])) 'optionValue': _imageColor[id]}],
        }) as Map<String, dynamic>;
        if (mounted) {
          toast(context, isAr ? 'تم إنشاء المنتج' : 'Product created');
          Navigator.of(context).pushReplacement(MaterialPageRoute(builder: (_) => ProductFormPage(ctx: ctx, id: '${res['id']}')));
        }
      } else {
        await ctx.api.patch('store/products/${widget.id}', {'name': _name.text.trim(), 'description': _desc.text.trim(), 'status': _status, 'brand': _brand.text.trim(), if (_audience.isNotEmpty) 'audience': _audience, 'condition': _condition, if (_picked != null) 'taxonomyId': _picked!['id']});
        for (final e in _prices.entries) {
          await ctx.api.patch('store/variants/${e.key}', {'priceMinor': toMinor(e.value.text), 'barcode': _barcodes[e.key]?.text.trim() ?? ''});
        }
        await _reloadDetail();
        if (mounted) toast(context, isAr ? 'تم الحفظ' : 'Saved');
      }
    });
    if (mounted && !ok) setState(() {});
    if (mounted) setState(() => _busy = false);
  }

  Map<String, TextEditingController> _rowFor(Map<String, String> combo, int i) => _rows.putIfAbsent(combo.values.join('|'), () => {'sku': TextEditingController(text: 'SKU-${_skuSeed + i}'), 'barcode': TextEditingController(), 'price': TextEditingController(), 'stock': TextEditingController(text: '0')});

  /// A barcode box that takes typing, a USB / laser scanner, or the camera.
  Widget _barcodeField(TextEditingController c, bool isAr, {String? label}) => Padding(padding: const EdgeInsets.only(top: 8), child: TextField(
        controller: c, keyboardType: TextInputType.number, textDirection: TextDirection.ltr,
        decoration: InputDecoration(labelText: label ?? (isAr ? 'الباركود (اختياري)' : 'Barcode (optional)'), isDense: true,
            suffixIcon: IconButton(icon: const Icon(Icons.qr_code_scanner_rounded), onPressed: () async {
              final code = await Navigator.of(context).push<String>(MaterialPageRoute(builder: (_) => const CodeScanPage()));
              if (code != null) setState(() => c.text = code.trim());
            })),
      ));

  bool get _showAudience => !isNew || _picked?['appliesAudience'] == true;

  bool get _valid => _name.text.trim().isNotEmpty && (isNew ? _branchId != null && (_status != 'ACTIVE' || _picked != null) : true);

  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(isNew ? (isAr ? 'منتج جديد' : 'New product') : '${_detail?['name'] ?? ''}')),
      body: _detail == null
          ? (_error != null ? Center(child: Column(mainAxisSize: MainAxisSize.min, children: [Text(errorText(_error!)), TextButton(onPressed: () { setState(() => _error = null); _init(); }, child: Text(S.of(context).retry))])) : const Center(child: CircularProgressIndicator()))
          : ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 100), children: [
              Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                tf(_name, isAr ? 'اسم المنتج' : 'Product name', onChanged: (_) => setState(() {})),
                tf(_desc, isAr ? 'الوصف' : 'Description', lines: 4),
                OutlinedButton.icon(
                  onPressed: _pickCategory,
                  icon: const Icon(Icons.category_outlined),
                  label: Text(_picked != null ? '${_picked!['breadcrumb']}' : (!isNew && ((_detail!['taxonomy'] as List?)?.isNotEmpty ?? false) ? ((_detail!['taxonomy'] as List).map((x) => (isAr ? (x as Map)['nameAr'] : x['nameEn'])).join(' › ')) : (isAr ? 'اختر التصنيف' : 'Choose category')), maxLines: 2, overflow: TextOverflow.ellipsis),
                ),
                const SizedBox(height: 10),
                if (_showAudience) DropdownButtonFormField<String>(
                  initialValue: _audience.isEmpty ? null : _audience,
                  decoration: InputDecoration(labelText: isAr ? 'لـ' : 'For'),
                  items: [for (final a in const {'MEN': 'رجال|Men', 'WOMEN': 'نساء|Women', 'BOYS': 'أولاد|Boys', 'GIRLS': 'بنات|Girls', 'BABY': 'أطفال|Baby', 'ALL': 'الجميع|All'}.entries) DropdownMenuItem(value: a.key, child: Text(a.value.split('|')[isAr ? 0 : 1]))],
                  onChanged: (v) => setState(() => _audience = v ?? ''),
                ),
                const SizedBox(height: 10),
                DropdownButtonFormField<String>(
                  initialValue: _condition,
                  decoration: InputDecoration(labelText: isAr ? 'الحالة' : 'Condition'),
                  items: [for (final a in const {'NEW': 'جديد|New', 'USED': 'مستعمل|Used', 'REFURBISHED': 'مجدد|Refurbished'}.entries) DropdownMenuItem(value: a.key, child: Text(a.value.split('|')[isAr ? 0 : 1]))],
                  onChanged: (v) => setState(() => _condition = v ?? 'NEW'),
                ),
                const SizedBox(height: 10),
                tf(_brand, isAr ? 'الماركة' : 'Brand'),
                DropdownButtonFormField<String>(
                  initialValue: _status,
                  decoration: InputDecoration(labelText: isAr ? 'الحالة في المتجر' : 'Status'),
                  items: [for (final a in (isNew ? const ['ACTIVE', 'DRAFT'] : const ['ACTIVE', 'DRAFT', 'ARCHIVED'])) DropdownMenuItem(value: a, child: Text(isAr ? {'ACTIVE': 'منشور', 'DRAFT': 'مسودة', 'ARCHIVED': 'مؤرشف'}[a]! : a))],
                  onChanged: (v) => setState(() => _status = v ?? 'ACTIVE'),
                ),
              ]))),
              const SizedBox(height: 12),
              if (isNew) ..._createExtras(context, isAr) else ..._editExtras(context, isAr),
            ]),
      bottomNavigationBar: _detail == null ? null : SafeArea(child: Padding(padding: const EdgeInsets.fromLTRB(16, 6, 16, 10), child: Row(children: [
        Expanded(child: FilledButton(onPressed: _busy || !_valid ? null : _save, child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(isAr ? 'حفظ' : 'Save'))),
        if (!isNew && ctx.can('product.delete')) ...[
          const SizedBox(width: 10),
          OutlinedButton(
            style: OutlinedButton.styleFrom(foregroundColor: const Color(0xFFD14343)),
            onPressed: _busy ? null : () async {
              if (!await confirm(context, isAr ? 'أرشفة المنتج؟' : 'Archive this product?') || !context.mounted) return;
              if (await run(context, () => ctx.api.delete('store/products/${widget.id}')) && context.mounted) Navigator.of(context).pop();
            },
            child: Text(isAr ? 'أرشفة' : 'Archive'),
          ),
        ],
      ]))),
    );
  }

  /// The values a picture can be for: the colour option when the product has one, else the first option.
  List<String> _colorValues(List<Map<String, dynamic>> parsed) {
    if (parsed.isEmpty) return const [];
    final o = parsed.firstWhere((x) => RegExp('color|colour|لون', caseSensitive: false).hasMatch('${x['name']}'), orElse: () => parsed.first);
    return ((o['values'] as List?) ?? const []).cast<String>();
  }

  /// A small "which colour is this picture for" menu; the empty value means every colour.
  Widget _colorPicker(bool isAr, List<(String, String)> values, String current, ValueChanged<String> onChanged) => DropdownButton<String>(
        value: values.any((v) => v.$1 == current) ? current : '',
        isExpanded: true,
        isDense: true,
        underline: const SizedBox.shrink(),
        style: TextStyle(fontSize: 12, color: Theme.of(context).colorScheme.onSurface),
        items: [DropdownMenuItem(value: '', child: Text(isAr ? 'كل الألوان' : 'All colours', overflow: TextOverflow.ellipsis)), for (final v in values) DropdownMenuItem(value: v.$1, child: Text(v.$2, overflow: TextOverflow.ellipsis))],
        onChanged: (v) => onChanged(v ?? ''),
      );

  // --- create: images, options, variants
  List<Widget> _createExtras(BuildContext context, bool isAr) {
    final parsed = _opts.map((o) => o.toRequest()).where((o) => (o['name'] as String).isNotEmpty && (o['values'] as List).isNotEmpty).toList();
    final combos = _combos(parsed);
    final used = _opts.map((o) => o.kind).toSet();
    return [
      Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(isAr ? 'الصور' : 'Pictures', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        const SizedBox(height: 8),
        Wrap(spacing: 8, runSpacing: 8, children: [
          for (final id in _images) SizedBox(width: 84, child: Column(children: [
            Stack(children: [
              ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(fileUrl(ctx, id), width: 84, height: 76, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 84, height: 76))),
              PositionedDirectional(top: 0, end: 0, child: InkWell(onTap: () => setState(() { _images.remove(id); _imageColor.remove(id); }), child: const CircleAvatar(radius: 11, backgroundColor: Colors.black54, child: Icon(Icons.close, size: 14, color: Colors.white)))),
            ]),
            if (_colorValues(parsed).isNotEmpty) _colorPicker(isAr, _colorValues(parsed).map((v) => (v, v)).toList(), _imageColor[id] ?? '', (v) => setState(() => _imageColor[id] = v)),
          ])),
          if (_images.length < 10) InkWell(
            onTap: () async { String? id; if (await run(context, () async { id = await pickAndUpload(context, ctx.api, 'PRODUCT_IMAGE'); }) && id != null) setState(() => _images.add(id!)); },
            child: Container(width: 76, height: 76, decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(10)), child: const Icon(Icons.add_photo_alternate_outlined)),
          ),
        ]),
      ]))),
      const SizedBox(height: 12),
      Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(isAr ? 'الخيارات (لون، مقاس...)' : 'Options (colour, size...)', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        const SizedBox(height: 6),
        for (var i = 0; i < _opts.length; i++) _optionEditor(context, i, isAr),
        Wrap(spacing: 8, children: [
          if (!used.contains('COLOR')) ActionChip(label: Text(isAr ? '+ لون' : '+ Colour'), onPressed: () => setState(() => _opts.add(_Opt('COLOR')))),
          if (!used.contains('SIZE')) ActionChip(label: Text(isAr ? '+ مقاس' : '+ Size'), onPressed: () => setState(() => _opts.add(_Opt('SIZE', scale: _scales.first)))),
          ActionChip(label: Text(isAr ? '+ خيار آخر' : '+ Other'), onPressed: () => setState(() => _opts.add(_Opt('CUSTOM')))),
        ]),
      ]))),
      const SizedBox(height: 12),
      Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(isAr ? 'الأسعار والمخزون' : 'Prices and stock', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        if (_branches.length > 1) Padding(padding: const EdgeInsets.only(top: 8), child: DropdownButtonFormField<String>(
          initialValue: _branchId,
          decoration: InputDecoration(labelText: isAr ? 'الفرع' : 'Branch'),
          items: [for (final b in _branches) DropdownMenuItem(value: '${b['id']}', child: Text('${b['name']}'))],
          onChanged: (v) => setState(() => _branchId = v),
        )),
        if (_branches.isEmpty) Padding(padding: const EdgeInsets.only(top: 8), child: Text(isAr ? 'أضف فرعًا من الإعدادات أولًا.' : 'Add a branch in Settings first.', style: const TextStyle(color: Color(0xFFD14343)))),
        for (var i = 0; i < combos.length; i++) Builder(builder: (_) {
          final r = _rowFor(combos[i], i);
          return Container(
            margin: const EdgeInsets.only(top: 10), padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(12)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              if (combos[i].isNotEmpty) Padding(padding: const EdgeInsets.only(bottom: 6), child: Text(combos[i].values.join(' / '), style: const TextStyle(fontWeight: FontWeight.w800))),
              Row(children: [
                Expanded(flex: 3, child: tf(r['sku']!, 'SKU', ltr: true)),
                const SizedBox(width: 8),
                Expanded(flex: 2, child: tf(r['price']!, isAr ? 'السعر' : 'Price', number: true, ltr: true)),
                const SizedBox(width: 8),
                Expanded(flex: 2, child: tf(r['stock']!, isAr ? 'المخزون' : 'Stock', number: true, ltr: true)),
              ]),
              _barcodeField(r['barcode']!, isAr),
            ]),
          );
        }),
      ]))),
    ];
  }

  Widget _optionEditor(BuildContext context, int i, bool isAr) {
    final o = _opts[i];
    final lang = AppLocale.isAr;
    List<Map<String, dynamic>> colors = _maps(_attrs?['colors']);
    List<Map<String, dynamic>> sizes = _maps((_attrs?['sizes'] as Map?)?[o.scale]);
    String nameOf(Map<String, dynamic> v) => '${v['name'] ?? (lang ? v['nameAr'] : v['nameEn'])}';
    return Container(
      margin: const EdgeInsets.only(bottom: 10), padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(12)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(o.kind == 'COLOR' ? (isAr ? 'اللون' : 'Colour') : o.kind == 'SIZE' ? (isAr ? 'المقاس' : 'Size') : (isAr ? 'خيار آخر' : 'Other option'), style: const TextStyle(fontWeight: FontWeight.w800))),
          IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.close_rounded), onPressed: () => setState(() => _opts.removeAt(i))),
        ]),
        if (o.kind == 'COLOR') Wrap(spacing: 6, runSpacing: 6, children: [
          for (final c in colors) FilterChip(
            label: Text(nameOf(c)),
            avatar: CircleAvatar(radius: 8, backgroundColor: c['hex'] == null ? Colors.grey : Color(int.tryParse('FF${'${c['hex']}'.replaceAll('#', '')}', radix: 16) ?? 0xFF999999)),
            selected: o.values.contains('${c['nameAr']}'),
            selectedColor: Theme.of(context).colorScheme.primary.withValues(alpha: 0.2),
            onSelected: (v) => setState(() => v ? o.values.add('${c['nameAr']}') : o.values.remove('${c['nameAr']}')),
          ),
        ]),
        if (o.kind == 'SIZE') ...[
          if (_scales.length > 1) DropdownButtonFormField<String>(
            initialValue: _scales.contains(o.scale) ? o.scale : _scales.first,
            decoration: InputDecoration(labelText: isAr ? 'نوع المقاسات' : 'Size scale'),
            items: [for (final sc in _scales) DropdownMenuItem(value: sc, child: Text(sc))],
            onChanged: (v) => setState(() { o.scale = v ?? o.scale; o.values.clear(); }),
          ),
          const SizedBox(height: 6),
          Wrap(spacing: 6, runSpacing: 6, children: [
            for (final z in sizes) FilterChip(
              label: Text(nameOf(z)),
              selected: o.values.contains('${z['nameAr']}'),
              selectedColor: Theme.of(context).colorScheme.primary,
              checkmarkColor: Colors.white,
              labelStyle: TextStyle(fontWeight: FontWeight.w700, color: o.values.contains('${z['nameAr']}') ? Colors.white : const Color(0xFF1F2A37)),
              onSelected: (v) => setState(() => v ? o.values.add('${z['nameAr']}') : o.values.remove('${z['nameAr']}')),
            ),
          ]),
        ],
        if (o.kind == 'CUSTOM') ...[
          tf(o.name, isAr ? 'اسم الخيار' : 'Option name', onChanged: (_) => setState(() {})),
          tf(o.custom, isAr ? 'القيم (افصل بفاصلة)' : 'Values (comma separated)', onChanged: (_) => setState(() {})),
        ],
      ]),
    );
  }

  /// (stored value, label in the app's language) for the colour option of the open product
  List<(String, String)> _editColors(Map<String, dynamic> d, bool isAr) {
    final opts = _maps(d['options']);
    if (opts.isEmpty) return const [];
    final o = opts.firstWhere((x) => x['attribute'] == 'COLOR', orElse: () => opts.first);
    final meta = _maps(o['valueMeta']);
    return [for (final v in ((o['values'] as List?) ?? const []).cast<String>()) (v, '${meta.where((m) => m['value'] == v).map((m) => isAr ? m['nameAr'] : m['nameEn']).firstOrNull ?? v}')];
  }

  // --- edit: variants prices, images, extras
  List<Widget> _editExtras(BuildContext context, bool isAr) {
    final d = _detail!;
    final variants = _maps(d['variants']);
    final media = _maps(d['media']);
    return [
      if (ctx.canSee('integrations')) Padding(padding: const EdgeInsets.only(bottom: 10), child: OutlinedButton.icon(
        onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => PublishScreen(ctx: ctx, productId: widget.id!))),
        icon: const Icon(Icons.hub_rounded), label: Text(isAr ? 'النشر على المنصات (أمازون، نون، شوبيفاي...)' : 'Publish to platforms (Amazon, noon, Shopify...)'))),
      Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text(isAr ? 'الأسعار والمخزون' : 'Prices and stock', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        for (final v in variants) Padding(padding: const EdgeInsets.only(top: 10), child: Row(children: [
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(comboLabel(v['comboKey']).isEmpty ? '-' : comboLabel(v['comboKey']), style: const TextStyle(fontWeight: FontWeight.w700)),
            Text('${v['sku']} · ${isAr ? 'المخزون' : 'stock'}: ${v['available']}', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          ])),
          SizedBox(width: 110, child: TextField(controller: _prices['${v['id']}'], keyboardType: const TextInputType.numberWithOptions(decimal: true), textDirection: TextDirection.ltr, decoration: InputDecoration(labelText: ctx.currency))),
        ])),
        for (final v in variants) if (_barcodes['${v['id']}'] != null) _barcodeField(_barcodes['${v['id']}']!, isAr, label: '${isAr ? 'الباركود' : 'Barcode'} · ${comboLabel(v['comboKey']).isEmpty ? '${v['sku']}' : comboLabel(v['comboKey'])}'),
        Padding(padding: const EdgeInsets.only(top: 8), child: Text(isAr ? 'لتعديل المخزون استخدم قسم المخزون.' : 'Use the Inventory section to change stock.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D)))),
      ]))),
      const SizedBox(height: 12),
      Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${isAr ? 'الصور' : 'Pictures'} (${media.length}/10)', style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16)),
        if (_editColors(d, isAr).isNotEmpty) ...[
          const SizedBox(height: 4),
          Text(isAr ? 'اختر لونًا تحت كل صورة لتظهر للعميل عند اختياره هذا اللون.' : 'Pick a colour under a picture so shoppers see it when they choose that colour.', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
          Row(children: [
            Text(isAr ? 'الصورة الجديدة للون: ' : 'New picture is for: ', style: const TextStyle(fontSize: 13)),
            Expanded(child: _colorPicker(isAr, _editColors(d, isAr), _newColor, (v) => setState(() => _newColor = v))),
          ]),
        ],
        const SizedBox(height: 8),
        Wrap(spacing: 10, runSpacing: 10, children: [
          for (var i = 0; i < media.length; i++) SizedBox(width: 92, child: Column(children: [
            Stack(children: [
              ClipRRect(borderRadius: BorderRadius.circular(10), child: Image.network(_mediaUrl(ctx, media[i]) ?? '', width: 92, height: 92, fit: BoxFit.cover, errorBuilder: (_, __, ___) => const SizedBox(width: 92, height: 92, child: Icon(Icons.image_not_supported_outlined)))),
              if (i == 0) PositionedDirectional(top: 4, start: 4, child: Container(padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2), decoration: BoxDecoration(color: const Color(0xFF1E9E5A), borderRadius: BorderRadius.circular(8)), child: Text(isAr ? 'رئيسية' : 'Main', style: const TextStyle(color: Colors.white, fontSize: 10, fontWeight: FontWeight.w800)))),
            ]),
            if (_editColors(d, isAr).isNotEmpty) _colorPicker(isAr, _editColors(d, isAr), '${media[i]['optionValue'] ?? ''}', (v) async { if (await run(context, () => ctx.api.put('store/products/${widget.id}/media/${media[i]['id']}/color', {'value': v}))) await _reloadDetail(); }),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              if (i > 0) IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.star_rounded, size: 20), onPressed: () async { if (await run(context, () => ctx.api.put('store/products/${widget.id}/media/order', {'ids': [media[i]['id']]}))) await _reloadDetail(); }),
              IconButton(visualDensity: VisualDensity.compact, icon: const Icon(Icons.delete_outline, size: 20), onPressed: () async { if (await run(context, () => ctx.api.delete('store/products/${widget.id}/media/${media[i]['id']}'))) await _reloadDetail(); }),
            ]),
          ])),
          if (media.length < 10) InkWell(
            onTap: () async {
              String? id;
              final ok = await run(context, () async { id = await pickAndUpload(context, ctx.api, 'PRODUCT_IMAGE'); if (id != null) await ctx.api.post('store/products/${widget.id}/media', {'fileId': id, if (_newColor.isNotEmpty) 'optionValue': _newColor}); });
              if (ok && id != null) await _reloadDetail();
            },
            child: Container(width: 92, height: 92, decoration: BoxDecoration(border: Border.all(color: Theme.of(context).dividerColor), borderRadius: BorderRadius.circular(10)), child: const Icon(Icons.add_photo_alternate_outlined)),
          ),
        ]),
      ]))),
      const SizedBox(height: 12),
      FormCard(
        key: ValueKey('extras-${d['id']}'),
        ctx: ctx, titleAr: 'تفاصيل البيع', titleEn: 'Selling details', initial: {'badge': d['badge'] ?? '', 'tags': d['tags'] ?? [], 'specs': d['specs'] ?? [], 'sizeGuide': d['sizeGuide'] ?? '', 'isFeatured': d['isFeatured'] == true},
        savePath: 'store/products/${widget.id}/extras',
        transform: (b) => {...b, 'specs': ((b['specs'] as List?) ?? const []).where((s) => '${(s as Map)['k']}'.trim().isNotEmpty && '${s['v']}'.trim().isNotEmpty).toList()},
        fields: const [
          F(FK.choice, 'badge', 'شارة', 'Badge', options: {'': 'بدون|None', 'NEW': 'جديد|New', 'SALE': 'تخفيض|Sale', 'BEST_SELLER': 'الأكثر مبيعًا|Best seller', 'LIMITED': 'كمية محدودة|Limited'}),
          F(FK.csv, 'tags', 'كلمات بحث', 'Search tags'),
          F(FK.toggle, 'isFeatured', 'مميّز في الصفحة الرئيسية ⭐', 'Featured on the home page ⭐'),
          F(FK.pairs, 'specs', 'المواصفات', 'Specifications'),
          F(FK.area, 'sizeGuide', 'دليل المقاسات', 'Size guide'),
        ],
      ),
    ];
  }
}

// ---------------------------------------------------------------- Coupons

class CouponsScreen extends StatelessWidget {
  const CouponsScreen({super.key, required this.ctx});
  final Ctx ctx;

  Future<void> _add(BuildContext c, Future<void> Function() reload) async {
    final isAr = S.of(c).ar;
    final code = TextEditingController(), value = TextEditingController(), minOrder = TextEditingController(), maxRed = TextEditingController(), perCust = TextEditingController();
    var type = 'PERCENT';
    if (await formSheet(c, title: isAr ? 'كوبون جديد' : 'New coupon', canSave: () => code.text.trim().isNotEmpty && value.text.trim().isNotEmpty,
        body: (x, set) => [
              tf(code, isAr ? 'الكود' : 'Code', ltr: true, onChanged: (_) => set(() {})),
              SegmentedButton<String>(segments: [ButtonSegment(value: 'PERCENT', label: Text(isAr ? 'نسبة %' : 'Percent')), ButtonSegment(value: 'FIXED', label: Text(isAr ? 'مبلغ ثابت' : 'Fixed'))], selected: {type}, onSelectionChanged: (s) => set(() => type = s.first)),
              const SizedBox(height: 10),
              tf(value, type == 'PERCENT' ? '%' : (isAr ? 'المبلغ' : 'Amount'), number: true, ltr: true, onChanged: (_) => set(() {})),
              tf(minOrder, isAr ? 'أقل قيمة للطلب' : 'Minimum order', number: true, ltr: true),
              tf(maxRed, isAr ? 'أقصى عدد استخدامات' : 'Max redemptions', number: true, ltr: true),
              tf(perCust, isAr ? 'حد لكل عميل' : 'Per customer limit', number: true, ltr: true),
            ],
        onSave: () async {
          await ctx.api.post('store/coupons', {
            'code': code.text.trim().toUpperCase(), 'discountType': type, 'value': type == 'PERCENT' ? (num.tryParse(value.text.trim()) ?? 0) : toMinor(value.text),
            'minOrderMinor': minOrder.text.trim().isEmpty ? 0 : toMinor(minOrder.text),
            if (maxRed.text.trim().isNotEmpty) 'maxRedemptions': int.tryParse(maxRed.text.trim()),
            if (perCust.text.trim().isNotEmpty) 'perCustomerLimit': int.tryParse(perCust.text.trim()),
          });
        })) {
      await reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final ar = s.ar;
    return Async<List<dynamic>>(
      load: () async => await ctx.api.get('store/coupons') as List<dynamic>,
      builder: (context, list, reload) {
        final items = list.cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 32), children: [
            if (ctx.can('coupon.manage')) FilledButton.icon(onPressed: () => _add(context, reload), icon: const Icon(Icons.add_rounded), label: Text(ar ? 'كوبون جديد' : 'New coupon')),
            const SizedBox(height: 8),
            for (var i = 0; i < items.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: const Icon(Icons.local_offer_rounded),
                title: Text('${items[i]['code']}', textDirection: TextDirection.ltr, textAlign: TextAlign.start, style: const TextStyle(fontWeight: FontWeight.w900)),
                subtitle: Text('${items[i]['discountType'] == 'PERCENT' ? '${items[i]['value']}%' : s.money(items[i]['value'] as num, ctx.currency)} · ${ar ? 'استخدم' : 'used'} ${items[i]['redemptions']}${items[i]['maxRedemptions'] != null ? ' / ${items[i]['maxRedemptions']}' : ''}'),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  Switch(value: items[i]['isActive'] == true, onChanged: (v) async { if (await run(context, () => ctx.api.put('store/coupons/${items[i]['id']}/active', {'active': v}))) await reload(); }),
                  IconButton(icon: const Icon(Icons.delete_outline), onPressed: () async { if (await confirm(context, ar ? 'حذف الكوبون؟' : 'Delete this coupon?') && context.mounted && await run(context, () => ctx.api.delete('store/coupons/${items[i]['id']}'))) await reload(); }),
                ]),
              ))),
            if (items.isEmpty) emptyState(ar ? 'لا توجد كوبونات' : 'No coupons'),
          ]),
        );
      },
    );
  }
}
