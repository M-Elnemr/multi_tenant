import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';
import 'common.dart';

/// Big gradient card with the day's headline number, plus two small figures beside it.
class _Hero extends StatelessWidget {
  const _Hero({required this.title, required this.value, required this.chips, this.format});
  final String title;
  final num value;
  final String Function(num)? format;
  final List<(String, num)> chips;
  @override
  Widget build(BuildContext context) => GradientHeader(
        radius: 28,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: TextStyle(color: Colors.white.withValues(alpha: 0.85), fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            CountUp(value: value, format: format, style: const TextStyle(color: Colors.white, fontSize: 38, fontWeight: FontWeight.w900)),
            const SizedBox(height: 16),
            Row(children: [
              for (final c in chips) Expanded(child: Container(
                margin: const EdgeInsetsDirectional.only(end: 10),
                padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
                decoration: BoxDecoration(color: Colors.white.withValues(alpha: 0.16), borderRadius: BorderRadius.circular(16)),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  CountUp(value: c.$2, style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w900)),
                  Text(c.$1, style: TextStyle(color: Colors.white.withValues(alpha: 0.85), fontSize: 12)),
                ]),
              )),
            ]),
          ]),
        ),
      );
}

class _StatTile extends StatelessWidget {
  const _StatTile(this.label, this.value, this.icon, this.color, {this.text});
  final String label;
  final num value;
  final String? text;
  final IconData icon;
  final Color color;
  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Container(padding: const EdgeInsets.all(8), decoration: BoxDecoration(color: color.withValues(alpha: 0.12), borderRadius: BorderRadius.circular(12)), child: Icon(icon, color: color, size: 20)),
            const SizedBox(height: 10),
            text != null ? Text(text!, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w900)) : CountUp(value: value, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w900)),
            const SizedBox(height: 2),
            Text(label, style: Theme.of(context).textTheme.bodySmall),
          ]),
        ),
      );
}

Widget _grid(List<Widget> tiles) => GridView.count(crossAxisCount: 2, shrinkWrap: true, physics: const NeverScrollableScrollPhysics(), mainAxisSpacing: 4, crossAxisSpacing: 12, childAspectRatio: 1.45, children: [for (var i = 0; i < tiles.length; i++) FadeSlideIn(index: i + 1, child: tiles[i])]);

Widget _title(BuildContext context, String t) => Padding(padding: const EdgeInsets.fromLTRB(4, 18, 4, 6), child: Text(t, style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900)));

Widget _empty(String t) => Padding(padding: const EdgeInsets.all(28), child: Center(child: Column(children: [const Icon(Icons.inbox_outlined, size: 40, color: Color(0xFF9AA9BA)), const SizedBox(height: 8), Text(t, style: const TextStyle(color: Color(0xFF6B7C8D)))])));

class StoreDashboard extends StatelessWidget {
  const StoreDashboard({super.key, required this.api, required this.currency, required this.perms});
  final ApiClient api;
  final String currency;
  final List<String> perms;

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => <String, dynamic>{
        if (perms.contains('report.read')) 'summary': await api.get('store/reports/summary'),
        if (perms.contains('order.read')) 'orders': await api.get('store/orders', query: {'pageSize': '20'}),
      },
      builder: (context, d, reload) {
        final sum = d['summary'] as Map<String, dynamic>?;
        final orders = ((d['orders'] as Map?)?['data'] as List?)?.cast<Map<String, dynamic>>() ?? [];
        final blue = Theme.of(context).colorScheme.primary;
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.fromLTRB(16, 8, 16, 24), children: [
            if (sum != null) ...[
              FadeSlideIn(child: _Hero(title: s.ar ? 'مبيعات اليوم' : 'Sales today', value: (sum['salesTodayMinor'] as num), format: (v) => s.money(v, currency), chips: [(s.ar ? 'طلبات اليوم' : 'Orders today', sum['ordersToday'] as num), (s.ar ? 'مخزون منخفض' : 'Low stock', sum['lowStockCount'] as num)])),
              const SizedBox(height: 12),
              _grid([
                _StatTile(s.ar ? 'مبيعات الشهر' : 'Sales this month', 0, Icons.bar_chart_rounded, blue, text: s.money(sum['salesThisMonthMinor'] as num, currency)),
                _StatTile(s.ar ? 'طلبات الشهر' : 'Orders this month', (sum['ordersThisMonth'] as num?) ?? 0, Icons.shopping_bag_outlined, kBrandGreen),
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
            if (orders.isEmpty) _empty(s.empty),
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
    if (!perms.contains('appointment.manage') && !perms.contains('appointment.read')) return _empty(s.empty);
    final blue = Theme.of(context).colorScheme.primary;
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
            FadeSlideIn(child: _Hero(title: s.ar ? 'مواعيد اليوم' : "Today's appointments", value: today.length, chips: [(s.ar ? 'في الانتظار' : 'Waiting', (d['checkedIn'] as num?) ?? 0), (s.ar ? 'بانتظار الموافقة' : 'Awaiting approval', (d['pendingRequests'] as num?) ?? 0)])),
            _title(context, s.waitingRoom),
            for (final e in inProgress)
              FadeSlideIn(child: Card(color: blue.withValues(alpha: 0.08), child: ListTile(leading: Icon(Icons.medical_services_rounded, color: blue), title: Text('${e['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)), subtitle: Text('#${e['queueNumber']} · ${e['serviceName']}')))),
            for (var i = 0; i < waiting.length; i++)
              FadeSlideIn(index: i, child: Card(
                child: ListTile(
                  leading: CircleAvatar(backgroundColor: blue.withValues(alpha: 0.12), foregroundColor: blue, child: Text('${waiting[i]['queueNumber']}', style: const TextStyle(fontWeight: FontWeight.w900))),
                  title: Text('${waiting[i]['patientName']}', style: const TextStyle(fontWeight: FontWeight.w800)),
                  subtitle: Text('${waiting[i]['serviceName']} · ${waiting[i]['waitedMinutes']} min'),
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    TextButton(onPressed: () => _act(context, waiting[i]['id'] as String, 'call', reload), child: Text(s.call)),
                    if (perms.contains('medical_note.create')) TextButton(onPressed: () => _act(context, waiting[i]['id'] as String, 'start', reload), child: Text(s.startVisit)),
                  ]),
                ),
              )),
            if (waiting.isEmpty && inProgress.isEmpty) _empty(s.empty),
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
            if (today.isEmpty) _empty(s.empty),
          ]),
        );
      },
    );
  }
}
