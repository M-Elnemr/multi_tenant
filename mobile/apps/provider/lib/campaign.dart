import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'common.dart';
import 'forms.dart';

// New Facebook / Instagram ad campaign: choose what to promote, let the AI suggest the settings, adjust them, and create (paused unless "start now").

List<Map<String, dynamic>> _maps(Object? v) => ((v as List?) ?? const []).cast<Map<String, dynamic>>();

const _objectives = {
  'OUTCOME_TRAFFIC': ('زيارات للمتجر', 'Visits to your shop'),
  'OUTCOME_ENGAGEMENT': ('تفاعل', 'Engagement'),
  'OUTCOME_AWARENESS': ('وصول وانتشار', 'Reach and awareness'),
};
const _ctas = {
  'SHOP_NOW': ('تسوّق الآن', 'Shop now'),
  'ORDER_NOW': ('اطلب الآن', 'Order now'),
  'LEARN_MORE': ('اعرف المزيد', 'Learn more'),
  'CONTACT_US': ('تواصل معنا', 'Contact us'),
  'SIGN_UP': ('سجّل', 'Sign up'),
};
const _countries = {'EG': ('مصر', 'Egypt'), 'SA': ('السعودية', 'Saudi Arabia'), 'AE': ('الإمارات', 'UAE')};

class NewCampaignScreen extends StatefulWidget {
  const NewCampaignScreen({super.key, required this.ctx, required this.overview});
  final Ctx ctx;
  final Map<String, dynamic> overview;
  @override
  State<NewCampaignScreen> createState() => _NewCampaignScreenState();
}

class _NewCampaignScreenState extends State<NewCampaignScreen> {
  late String _account = '${_maps(widget.overview['adAccounts']).first['id']}';
  late final List<Map<String, dynamic>> _pages = _maps(widget.overview['channels']).where((c) => c['platform'] == 'FACEBOOK').toList();
  String? _pageId;
  String _target = 'PRODUCT'; // PRODUCT | POST
  Map<String, dynamic>? _product, _post;
  final _search = TextEditingController();
  List<Map<String, dynamic>> _found = const [], _posts = const [];
  bool _busy = false;
  Map<String, dynamic>? _s; // the suggestion being edited (null = step 1)
  String _reasoning = '';
  bool _fromAi = true, _launch = false;
  final _name = TextEditingController(), _budget = TextEditingController(), _days = TextEditingController(), _ageMin = TextEditingController(), _ageMax = TextEditingController();
  final _interests = TextEditingController(), _headline = TextEditingController(), _text = TextEditingController();
  String _objective = 'OUTCOME_TRAFFIC', _cta = 'SHOP_NOW', _gender = 'all';
  final _countrySet = <String>{};

  @override
  void initState() {
    super.initState();
    _pageId = _pages.isEmpty ? null : '${_pages.first['externalId']}';
    _searchProducts('');
  }

  @override
  void dispose() {
    for (final c in [_search, _name, _budget, _days, _ageMin, _ageMax, _interests, _headline, _text]) { c.dispose(); }
    super.dispose();
  }

  Future<void> _searchProducts(String q) async {
    try {
      final r = await widget.ctx.api.get('store/products', query: {'q': q, 'page': '1'}) as Map<String, dynamic>;
      if (mounted) setState(() => _found = _maps(r['data']));
    } catch (_) {/* the list just stays empty */}
  }

  Future<void> _loadPosts() async {
    try {
      final r = await widget.ctx.api.get('store/marketing/posts', query: {'page': '1'}) as Map<String, dynamic>;
      if (mounted) setState(() => _posts = _maps(r['data']).where((p) => p['imageFileId'] != null || p['imageUrl'] != null).toList());
    } catch (_) {/* the list just stays empty */}
  }

  bool get _ready => _target == 'PRODUCT' ? _product != null : _post != null;

  Future<void> _suggest() async {
    setState(() => _busy = true);
    final ar = S.of(context).ar;
    final ok = await run(context, () async {
      final s = await widget.ctx.api.post('store/marketing/campaigns/suggest', {'accountId': _account, if (_target == 'PRODUCT') 'productId': _product!['id'], 'language': ar ? 'ar' : 'en'}) as Map<String, dynamic>;
      _s = s;
      _reasoning = '${s['reasoning'] ?? ''}';
      _fromAi = s['fromAi'] != false;
      _objective = _objectives.containsKey(s['objective']) ? '${s['objective']}' : 'OUTCOME_TRAFFIC';
      _cta = _ctas.containsKey(s['cta']) ? '${s['cta']}' : 'SHOP_NOW';
      _budget.text = fmtMoney(s['dailyBudgetMinor'] as num? ?? 0);
      _days.text = '${s['durationDays'] ?? 7}';
      _ageMin.text = '${s['ageMin'] ?? 18}';
      _ageMax.text = '${s['ageMax'] ?? 65}';
      _interests.text = ((s['interests'] as List?) ?? const []).join(', ');
      _headline.text = '${s['headline'] ?? ''}';
      _text.text = '${s['primaryText'] ?? ''}';
      final g = ((s['genders'] as List?) ?? const []).cast<String>();
      _gender = g.length == 1 ? g.first : 'all';
      _countrySet..clear()..addAll(((s['countries'] as List?) ?? const []).cast<String>());
    });
    if (mounted) setState(() { _busy = false; if (!ok) _s = null; });
  }

  Future<void> _create() async {
    setState(() => _busy = true);
    final ar = S.of(context).ar;
    final ok = await run(context, () async {
      await widget.ctx.api.post('store/marketing/campaigns', {
        'accountId': _account, if (_pageId != null) 'pageId': _pageId, if (_target == 'PRODUCT') 'productId': _product!['id'], if (_target == 'POST') 'postId': _post!['id'],
        if (_name.text.trim().isNotEmpty) 'name': _name.text.trim(), 'objective': _objective, 'dailyBudgetMinor': toMinor(_budget.text), 'durationDays': int.tryParse(_days.text) ?? 7,
        'countries': _countrySet.toList(), 'ageMin': int.tryParse(_ageMin.text) ?? 18, 'ageMax': int.tryParse(_ageMax.text) ?? 65, 'genders': _gender == 'all' ? <String>[] : [_gender],
        'interests': _interests.text.split(RegExp('[,،]')).map((e) => e.trim()).where((e) => e.isNotEmpty).toList(), 'headline': _headline.text.trim(), 'primaryText': _text.text.trim(), 'cta': _cta, 'launch': _launch,
      });
    });
    if (!mounted) return;
    setState(() => _busy = false);
    if (ok) { toast(context, ar ? (_launch ? 'تم إنشاء الحملة وبدأت' : 'تم إنشاء الحملة (متوقفة حتى تبدأها)') : (_launch ? 'Campaign created and started' : 'Campaign created (paused until you start it)')); Navigator.of(context).pop(true); }
  }

  @override
  Widget build(BuildContext context) {
    final ar = S.of(context).ar;
    return Scaffold(
      appBar: AppBar(title: Text(ar ? 'حملة إعلانية جديدة' : 'New ad campaign'), leading: BackButton(onPressed: () => _s != null ? setState(() => _s = null) : Navigator.of(context).pop())),
      body: _s == null ? _step1(ar) : _step2(ar),
    );
  }

  Widget _step1(bool ar) {
    final accounts = _maps(widget.overview['adAccounts']);
    return ListView(padding: const EdgeInsets.all(16), children: [
      if (accounts.length > 1) Padding(padding: const EdgeInsets.only(bottom: 12), child: DropdownButtonFormField<String>(
        initialValue: _account, decoration: InputDecoration(labelText: ar ? 'الحساب الإعلاني' : 'Ad account'),
        items: [for (final a in accounts) DropdownMenuItem(value: '${a['id']}', child: Text('${a['label']} (${a['externalId']})', overflow: TextOverflow.ellipsis))], onChanged: (v) => setState(() => _account = v ?? _account))),
      if (_pages.length > 1) Padding(padding: const EdgeInsets.only(bottom: 12), child: DropdownButtonFormField<String>(
        initialValue: _pageId, decoration: InputDecoration(labelText: ar ? 'صفحة فيسبوك' : 'Facebook page'),
        items: [for (final p in _pages) DropdownMenuItem(value: '${p['externalId']}', child: Text('${p['name'] ?? p['externalId']}', overflow: TextOverflow.ellipsis))], onChanged: (v) => setState(() => _pageId = v))),
      SegmentedButton<String>(
        segments: [ButtonSegment(value: 'PRODUCT', label: Text(ar ? 'منتج' : 'A product')), ButtonSegment(value: 'POST', label: Text(ar ? 'منشور' : 'A post'))],
        selected: {_target}, onSelectionChanged: (v) { setState(() => _target = v.first); if (_target == 'POST' && _posts.isEmpty) _loadPosts(); }),
      const SizedBox(height: 12),
      if (_target == 'PRODUCT') ...[
        if (_product != null) Card(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.08), child: ListTile(title: Text('${_product!['name']}', style: const TextStyle(fontWeight: FontWeight.w800)), trailing: TextButton(onPressed: () => setState(() => _product = null), child: Text(ar ? 'تغيير' : 'Change'))))
        else ...[
          TextField(controller: _search, decoration: InputDecoration(labelText: ar ? 'ابحث عن منتج' : 'Search a product', prefixIcon: const Icon(Icons.search_rounded)), onChanged: _searchProducts),
          for (final p in _found.take(12)) ListTile(contentPadding: EdgeInsets.zero, title: Text('${p['name']}'), onTap: () => setState(() => _product = p)),
        ],
      ] else ...[
        if (_posts.isEmpty) Padding(padding: const EdgeInsets.all(12), child: Text(ar ? 'لا توجد منشورات بصور بعد. أنشئ منشورًا من تبويب المنشورات.' : 'No posts with a picture yet. Create one in the Posts tab.', style: const TextStyle(color: Color(0xFF6B7C8D)))),
        for (final p in _posts) Card(shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14), side: BorderSide(color: _post?['id'] == p['id'] ? Theme.of(context).colorScheme.primary : Colors.transparent, width: 2)),
          child: ListTile(onTap: () => setState(() => _post = p), title: Text('${p['caption']}', maxLines: 3, overflow: TextOverflow.ellipsis))),
      ],
      const SizedBox(height: 16),
      FilledButton.icon(onPressed: _ready && !_busy ? _suggest : null, icon: _busy ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.auto_awesome_rounded), label: Text(ar ? 'اقترح الإعدادات بالذكاء الاصطناعي' : 'Suggest settings with AI')),
    ]);
  }

  Widget _step2(bool ar) {
    final cur = '${_s!['currency'] ?? widget.ctx.currency}';
    final total = toMinor(_budget.text) * (int.tryParse(_days.text) ?? 1);
    return ListView(padding: const EdgeInsets.all(16), children: [
      Container(padding: const EdgeInsets.all(12), decoration: BoxDecoration(color: const Color(0xFFE8F1FF), borderRadius: BorderRadius.circular(14)), child: Text('✨ $_reasoning${_fromAi ? '' : (ar ? ' (اقتراح تلقائي بدون ذكاء اصطناعي)' : ' (rule-based suggestion)')}', style: const TextStyle(height: 1.4))),
      const SizedBox(height: 12),
      tf(_name, '${ar ? 'اسم الحملة' : 'Campaign name'} (${ar ? 'اختياري' : 'optional'})'),
      DropdownButtonFormField<String>(initialValue: _objective, decoration: InputDecoration(labelText: ar ? 'الهدف' : 'Goal'),
        items: [for (final e in _objectives.entries) DropdownMenuItem(value: e.key, child: Text(ar ? e.value.$1 : e.value.$2))], onChanged: (v) => setState(() => _objective = v ?? _objective)),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: tf(_budget, '${ar ? 'الميزانية اليومية' : 'Daily budget'} ($cur)', number: true, ltr: true, onChanged: (_) => setState(() {}))),
        const SizedBox(width: 10),
        Expanded(child: tf(_days, ar ? 'عدد الأيام' : 'Days', number: true, ltr: true, onChanged: (_) => setState(() {}))),
      ]),
      Text(ar ? 'أقصى إنفاق: ${fmtMoney(total)} $cur' : 'Most you can spend: ${fmtMoney(total)} $cur', style: const TextStyle(fontSize: 12, color: Color(0xFF6B7C8D))),
      sectionTitle(ar ? 'الدول' : 'Countries'),
      Wrap(spacing: 8, children: [for (final e in _countries.entries) FilterChip(label: Text(ar ? e.value.$1 : e.value.$2), selected: _countrySet.contains(e.key), onSelected: (v) => setState(() { v ? _countrySet.add(e.key) : _countrySet.remove(e.key); }))]),
      const SizedBox(height: 10),
      Row(children: [
        Expanded(child: tf(_ageMin, ar ? 'أقل عمر' : 'Min age', number: true, ltr: true)),
        const SizedBox(width: 10),
        Expanded(child: tf(_ageMax, ar ? 'أكبر عمر' : 'Max age', number: true, ltr: true)),
      ]),
      DropdownButtonFormField<String>(initialValue: _gender, decoration: InputDecoration(labelText: ar ? 'الجنس' : 'Gender'),
        items: [DropdownMenuItem(value: 'all', child: Text(ar ? 'الكل' : 'Everyone')), DropdownMenuItem(value: 'male', child: Text(ar ? 'رجال' : 'Men')), DropdownMenuItem(value: 'female', child: Text(ar ? 'نساء' : 'Women'))], onChanged: (v) => setState(() => _gender = v ?? 'all')),
      const SizedBox(height: 10),
      tf(_interests, ar ? 'الاهتمامات (افصل بينها بفاصلة)' : 'Interests (separate with commas)'),
      tf(_headline, ar ? 'العنوان' : 'Headline'),
      tf(_text, ar ? 'نص الإعلان' : 'Ad text', lines: 3),
      DropdownButtonFormField<String>(initialValue: _cta, decoration: InputDecoration(labelText: ar ? 'زر الإجراء' : 'Button'),
        items: [for (final e in _ctas.entries) DropdownMenuItem(value: e.key, child: Text(ar ? e.value.$1 : e.value.$2))], onChanged: (v) => setState(() => _cta = v ?? _cta)),
      SwitchListTile(contentPadding: EdgeInsets.zero, value: _launch, onChanged: (v) => setState(() => _launch = v), title: Text(ar ? 'ابدأ الحملة الآن' : 'Start now'), subtitle: Text(ar ? 'إن أطفأته تُنشأ الحملة متوقفة وتبدأها أنت بعد المراجعة.' : 'If off, the campaign is created paused and you start it after checking.')),
      const SizedBox(height: 6),
      FilledButton(
        onPressed: _busy || _countrySet.isEmpty || _headline.text.trim().isEmpty || _text.text.trim().isEmpty || toMinor(_budget.text) <= 0 ? null : _create,
        child: _busy ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2)) : Text(_launch ? (ar ? 'أنشئ وابدأ' : 'Create and start') : (ar ? 'أنشئ الحملة' : 'Create campaign'))),
    ]);
  }
}
