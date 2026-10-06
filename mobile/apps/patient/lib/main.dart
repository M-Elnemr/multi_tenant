import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart';
import 'package:platform_core/platform_core.dart';

void main() => runApp(const PatientApp());

class PatientApp extends StatelessWidget {
  const PatientApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Patient',
        theme: themeFor(null),
        localizationsDelegates: const [GlobalMaterialLocalizations.delegate, GlobalWidgetsLocalizations.delegate, GlobalCupertinoLocalizations.delegate],
        supportedLocales: const [Locale('ar'), Locale('en')],
        home: TenantGate(initial: null, expectedType: 'CLINIC', builder: (c, t, api) => PatientGate(tenant: t, api: api)),
      );
}

class PatientGate extends StatefulWidget {
  const PatientGate({super.key, required this.tenant, required this.api});
  final TenantInfo tenant;
  final ApiClient api;
  @override
  State<PatientGate> createState() => _PatientGateState();
}

class _PatientGateState extends State<PatientGate> {
  bool? loggedIn;
  int tab = 0;

  @override
  void initState() {
    super.initState();
    widget.api.session.loggedIn.then((v) {
      if (mounted) setState(() => loggedIn = v);
    });
  }

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    if (loggedIn == null) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    if (loggedIn == false) return LoginScreen(api: widget.api, title: widget.tenant.name, onLoggedIn: (_) => setState(() => loggedIn = true));
    final pages = [AppointmentsPage(api: widget.api), RecordPage(api: widget.api)];
    return Scaffold(
      appBar: AppBar(title: Text(widget.tenant.name), actions: [
        IconButton(
          tooltip: s.logout,
          icon: const Icon(Icons.logout),
          onPressed: () async {
            await AuthRepository(widget.api).logout();
            setState(() => loggedIn = false);
          },
        ),
      ]),
      body: pages[tab],
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (i) => setState(() => tab = i),
        destinations: [
          NavigationDestination(icon: const Icon(Icons.event_outlined), label: s.appointments),
          NavigationDestination(icon: const Icon(Icons.folder_shared_outlined), label: s.record),
        ],
      ),
    );
  }
}

String _when(String iso) => DateFormat.yMMMd().add_jm().format(DateTime.parse(iso).toLocal());

class AppointmentsPage extends StatelessWidget {
  const AppointmentsPage({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async => await api.get('portal/appointments', query: {'pageSize': '50'}) as Map<String, dynamic>,
      builder: (context, d, reload) {
        final items = (d['data'] as List).cast<Map<String, dynamic>>();
        if (items.isEmpty) return Center(child: Text(s.empty));
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(children: [
            for (final a in items)
              ListTile(
                title: Text(_when(a['startAt'] as String)),
                subtitle: Text('${a['serviceName']} · ${a['doctorName']}\n${a['status']}'),
                isThreeLine: true,
                trailing: ['REQUESTED', 'PENDING_CONFIRMATION', 'CONFIRMED'].contains(a['status'])
                    ? TextButton(
                        onPressed: () async {
                          try {
                            await api.post('portal/appointments/${a['id']}/cancel', {});
                            await reload();
                          } catch (e) {
                            if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(errorText(e))));
                          }
                        },
                        child: Text(s.ar ? 'إلغاء' : 'Cancel'),
                      )
                    : null,
              ),
          ]),
        );
      },
    );
  }
}

/// Shows only what the clinic shared with the patient (the server filters; internal notes never reach this app).
class RecordPage extends StatelessWidget {
  const RecordPage({super.key, required this.api});
  final ApiClient api;
  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    return Async<Map<String, dynamic>>(
      load: () async {
        final patients = (await api.get('portal/patients') as List).cast<Map<String, dynamic>>();
        if (patients.isEmpty) return <String, dynamic>{};
        return await api.get('portal/patients/${patients.first['id']}/timeline') as Map<String, dynamic>;
      },
      builder: (context, t, reload) {
        if (t.isEmpty) return Center(child: Text(s.empty));
        final rx = (t['prescriptions'] as List).cast<Map<String, dynamic>>();
        final labs = (t['labOrders'] as List).cast<Map<String, dynamic>>();
        return RefreshIndicator(
          onRefresh: reload,
          child: ListView(padding: const EdgeInsets.all(16), children: [
            Text(s.ar ? 'الروشتات' : 'Prescriptions', style: Theme.of(context).textTheme.titleMedium),
            for (final p in rx)
              Card(
                child: ListTile(
                  title: Text(p['issuedAt'] == null ? '' : _when(p['issuedAt'] as String)),
                  subtitle: Text([for (final i in (p['items'] as List)) '${i['medicationName']} ${i['dosage'] ?? ''} ${i['frequency'] ?? ''}'.trim()].join('\n')),
                ),
              ),
            if (rx.isEmpty) Text(s.empty),
            const SizedBox(height: 16),
            Text(s.ar ? 'التحاليل' : 'Lab tests', style: Theme.of(context).textTheme.titleMedium),
            for (final l in labs)
              Card(
                child: ListTile(
                  title: Text(l['testName'] as String),
                  subtitle: Text([l['status'] as String, for (final r in (l['results'] as List)) (r['resultText'] ?? '') as String].where((x) => x.isNotEmpty).join('\n')),
                ),
              ),
            if (labs.isEmpty) Text(s.empty),
          ]),
        );
      },
    );
  }
}
