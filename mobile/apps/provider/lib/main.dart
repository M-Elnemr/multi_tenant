import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:platform_core/platform_core.dart';

void main() => runApp(const ProviderApp());

class ProviderApp extends StatelessWidget {
  const ProviderApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Business',
        theme: themeFor(null),
        localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
        supportedLocales: const [Locale('ar'), Locale('en')],
        home: TenantGate(initial: null, expectedType: 'ANY', builder: (c, t, api) => Gate(tenant: t, api: api)),
      );
}

/// Store owners, doctors and staff use one app: the dashboard shown depends on the tenant type and on the permissions
/// the server grants them (the app hides what they cannot use; the server enforces it regardless).
class Gate extends StatefulWidget {
  const Gate({super.key, required this.tenant, required this.api});
  final TenantInfo tenant;
  final ApiClient api;
  @override
  State<Gate> createState() => _GateState();
}

class _GateState extends State<Gate> {
  Map<String, dynamic>? me;
  bool checked = false;

  @override
  void initState() {
    super.initState();
    _check();
  }

  Future<void> _check() async {
    Map<String, dynamic>? m;
    if (await widget.api.session.loggedIn) {
      try {
        m = await AuthRepository(widget.api).me();
      } catch (_) {
        m = null;
      }
    }
    if (mounted) {
      setState(() {
        me = m;
        checked = true;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (!checked) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (me == null) return LoginScreen(api: widget.api, title: widget.tenant.name, onLoggedIn: (u) => _check());
    final perms = (me!['permissions'] as List).cast<String>();
    return Scaffold(
      appBar: AppBar(title: Text(widget.tenant.name), actions: [
        IconButton(
          tooltip: s.logout,
          icon: const Icon(Icons.logout),
          onPressed: () async {
            await AuthRepository(widget.api).logout();
            setState(() => me = null);
          },
        ),
      ]),
      body: widget.tenant.type == 'STORE' ? StoreDashboard(api: widget.api, tenant: widget.tenant, perms: perms) : ClinicDashboard(api: widget.api, perms: perms),
    );
  }
}

class StoreDashboard extends StatelessWidget {
  const StoreDashboard({super.key, required this.api, required this.tenant, required this.perms});
  final ApiClient api;
  final TenantInfo tenant;
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
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            if (sum != null)
              Wrap(spacing: 12, runSpacing: 12, children: [
                _Stat(s.ar ? 'مبيعات اليوم' : 'Sales today', s.money(sum['salesTodayMinor'] as num, tenant.currency)),
                _Stat(s.ar ? 'طلبات اليوم' : 'Orders today', '${sum['ordersToday']}'),
                _Stat(s.ar ? 'مبيعات الشهر' : 'Sales this month', s.money(sum['salesThisMonthMinor'] as num, tenant.currency)),
                _Stat(s.ar ? 'مخزون منخفض' : 'Low stock', '${sum['lowStockCount']}'),
              ]),
            const SizedBox(height: 16),
            Text(s.orders, style: Theme.of(context).textTheme.titleMedium),
            for (final o in orders) ListTile(title: Text(o['orderNumber'] as String), subtitle: Text('${o['customerNameSnapshot']} · ${o['status']}'), trailing: Text(s.money(o['totalMinor'] as num, tenant.currency))),
            if (orders.isEmpty) Padding(padding: const EdgeInsets.all(24), child: Center(child: Text(s.empty))),
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
      if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errorText(e))));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (!perms.contains('appointment.manage')) return Center(child: Text(s.empty));
    return Async<Map<String, dynamic>>(
      load: () async => await api.get('clinic/dashboard') as Map<String, dynamic>,
      builder: (context, d, reload) {
        final today = (d['today'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Wrap(spacing: 12, runSpacing: 12, children: [
              _Stat(s.today, '${today.length}'),
              _Stat(s.ar ? 'بانتظار الموافقة' : 'Awaiting approval', '${d['pendingRequests']}'),
              _Stat(s.ar ? 'في الانتظار' : 'Waiting', '${d['checkedIn']}'),
            ]),
            const SizedBox(height: 16),
            Text(s.appointments, style: Theme.of(context).textTheme.titleMedium),
            for (final a in today)
              Card(
                child: ListTile(
                  title: Text('${a['patientName']}'),
                  subtitle: Text('${a['serviceName']} · ${a['status']}'),
                  trailing: switch (a['status']) {
                    'PENDING_CONFIRMATION' => TextButton(onPressed: () => _act(context, a['id'] as String, 'confirm', reload), child: Text(s.ar ? 'تأكيد' : 'Confirm')),
                    'CONFIRMED' => TextButton(onPressed: () => _act(context, a['id'] as String, 'check-in', reload), child: Text(s.ar ? 'حضر' : 'Check in')),
                    _ => null,
                  },
                ),
              ),
            if (today.isEmpty) Padding(padding: const EdgeInsets.all(24), child: Center(child: Text(s.empty))),
          ]),
        );
      },
    );
  }
}

class _Stat extends StatelessWidget {
  const _Stat(this.label, this.value);
  final String label, value;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: 160,
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 4),
              Text(value, style: Theme.of(context).textTheme.titleLarge),
            ]),
          ),
        ),
      );
}
