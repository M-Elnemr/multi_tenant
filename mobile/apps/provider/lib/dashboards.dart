import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'common.dart';
import 'theme/templates.dart';

/// Dashboard pieces come from the business template (lib/theme): the hero card, figure tiles and headings change look per shop / clinic / lawyer.
Widget _hero(BuildContext context, {required String title, required num value, String Function(num)? format, required List<(String, num)> chips}) =>
    TemplateHero(overline: title, value: value, format: format, figures: [for (final c in chips) HeroFigure(c.$1, c.$2)]);

Widget _grid(BuildContext context, List<Widget> tiles) => templateGrid(context, tiles, aspect: 1.35);

Widget _title(BuildContext context, String t) => TemplateSectionTitle(t);

Widget _empty(BuildContext context, String t) => templateEmpty(context, t);

class StoreDashboard extends StatelessWidget {
  const StoreDashboard({super.key, required this.api, required this.currency, required this.perms});
  final ApiClient api;
  final String currency;
  final List<String> perms;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final tpl = AppTemplate.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => <String, dynamic>{
        if (perms.contains('report.read')) 'summary': await api.get('store/reports/summary'),
        if (perms.contains('order.read')) 'orders': await api.get('store/orders', query: {'pageSize': '20'}),
      },
      builder: (context, d, reload) {
        final sum = d['summary'] as Map<String, dynamic>?;
        final orders = ((d['orders'] as Map?)?['data'] as List?)?.cast<Map<String, dynamic>>() ?? [];
        final blue = tpl.isShop ? tpl.accent : tpl.ink;
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
            if (sum != null) ...[
              FadeSlideIn(child: _hero(context, title: s.ar ? 'مبيعات اليوم' : 'Sales today', value: (sum['salesTodayMinor'] as num), format: (v) => s.money(v, currency), chips: [(s.ar ? 'طلبات اليوم' : 'Orders today', sum['ordersToday'] as num), (s.ar ? 'مخزون منخفض' : 'Low stock', sum['lowStockCount'] as num)])),
              const SizedBox(height: 12),
              _grid(context, [
                TemplateStatTile(label: s.ar ? 'مبيعات الشهر' : 'Sales this month', icon: Icons.bar_chart_rounded, text: s.money(sum['salesThisMonthMinor'] as num, currency)),
                TemplateStatTile(label: s.ar ? 'طلبات الشهر' : 'Orders this month', icon: Icons.shopping_bag_outlined, value: (sum['ordersThisMonth'] as num?) ?? 0, color: tpl.isShop ? tpl.accent2 : null),
              ]),
            ],
            _title(context, s.orders),
            for (var i = 0; i < orders.length; i++)
              FadeSlideIn(index: i, child: Card(child: ListTile(
                leading: CircleAvatar(backgroundColor: blue.withValues(alpha: 0.1), child: Icon(Icons.receipt_long_rounded, color: blue, size: 20)),
                title: Text(orders[i]['orderNumber'] as String, style: const TextStyle(fontWeight: FontWeight.w800)),
                subtitle: Text('${orders[i]['customerNameSnapshot']} · ${statusLabel('${orders[i]['status']}')}'),
                trailing: Text(s.money(orders[i]['totalMinor'] as num, currency), style: const TextStyle(fontWeight: FontWeight.w800)),
              ))),
            if (orders.isEmpty) _empty(context, s.empty),
          ]),
        );
      },
    );
  }
}

class ClinicDashboard extends StatelessWidget {
  const ClinicDashboard({super.key, required this.api, required this.perms});
  final ApiClient api;
  final List<String> perms;

  Future<void> _act(BuildContext context, String id, String action, Future<void> Function() reload) async {
    try {
      await api.post('clinic/appointments/$id/$action', {});
      await reload();
    } catch (e) {
      if (context.mounted) toast(context, errorText(e), isError: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (!perms.contains('appointment.manage') && !perms.contains('appointment.read')) return _empty(context, s.empty);
    final tpl = AppTemplate.of(context);
    final blue = tpl.ink;
    return Async<Map<String, dynamic>>(
      load: () async => {...(await api.get('clinic/dashboard') as Map<String, dynamic>), 'queue': await api.get('clinic/queue')},
      builder: (context, d, reload) {
        final today = (d['today'] as List).cast<Map<String, dynamic>>();
        final queue = d['queue'] as Map<String, dynamic>;
        final waiting = (queue['waiting'] as List).cast<Map<String, dynamic>>();
        final inProgress = (queue['inProgress'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
            FadeSlideIn(child: _hero(context, title: s.ar ? 'مواعيد اليوم' : "Today's appointments", value: today.length, chips: [(s.ar ? 'في الانتظار' : 'Waiting', (d['checkedIn'] as num?) ?? 0), (s.ar ? 'بانتظار الموافقة' : 'Awaiting approval', (d['pendingRequests'] as num?) ?? 0)])),
            _title(context, s.waitingRoom),
            for (final e in inProgress)
              FadeSlideIn(child: Card(color: tpl.accent.withValues(alpha: 0.4), child: ListTile(leading: Icon(Icons.medical_services_rounded, color: blue), title: Text('${e['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text('#${e['queueNumber']} · ${e['serviceName']}')))),
            for (var i = 0; i < waiting.length; i++)
              FadeSlideIn(index: i, child: Card(
                child: ListTile(
                  leading: CircleAvatar(backgroundColor: tpl.accent.withValues(alpha: 0.55), foregroundColor: blue, child: Text('${waiting[i]['queueNumber']}', style: const TextStyle(fontWeight: FontWeight.w900))),
                  title: Text('${waiting[i]['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  subtitle: Text('${waiting[i]['serviceName']} · ${waiting[i]['waitedMinutes']} min'),
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    TextButton(onPressed: () => _act(context, waiting[i]['id'] as String, 'call', reload), child: Text(s.call)),
                    if (perms.contains('medical_note.create')) TextButton(onPressed: () => _act(context, waiting[i]['id'] as String, 'start', reload), child: Text(s.startVisit)),
                  ]),
                ),
              )),
            if (waiting.isEmpty && inProgress.isEmpty) _empty(context, s.empty),
            _title(context, s.appointments),
            for (var i = 0; i < today.length; i++)
              FadeSlideIn(index: i, child: Card(
                child: ListTile(
                  title: Text('${today[i]['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  subtitle: Text('${today[i]['serviceName']} · ${statusLabel('${today[i]['status']}')}'),
                  trailing: switch (today[i]['status']) {
                    'PENDING_CONFIRMATION' => FilledButton.tonal(style: FilledButton.styleFrom(minimumSize: const Size(0, 38)), onPressed: () => _act(context, today[i]['id'] as String, 'confirm', reload), child: Text(s.ar ? 'تأكيد' : 'Confirm')),
                    'CONFIRMED' => FilledButton.tonal(style: FilledButton.styleFrom(minimumSize: const Size(0, 38)), onPressed: () => _act(context, today[i]['id'] as String, 'check-in', reload), child: Text(s.ar ? 'حضر' : 'Check in')),
                    _ => null,
                  },
                ),
              )),
            if (today.isEmpty) _empty(context, s.empty),
          ]),
        );
      },
    );
  }
}
