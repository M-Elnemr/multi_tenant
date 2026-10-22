import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'clinic_panel.dart';
import 'admin.dart';
import 'common.dart';
import 'dashboards.dart';
import 'domains_billing.dart';
import 'exam.dart';
import 'offline_ops.dart';
import 'products.dart';
import 'queue.dart';
import 'settings.dart';
import 'channels.dart';
import 'store_panel.dart';

/// One section of the control panel. Mirrors the website's menu: the same sections, hidden without the same permission.
class PanelSection {
  const PanelSection(this.ar, this.en, this.icon, {this.perm, this.screen, required this.build});
  final String ar, en;
  final IconData icon;
  final dynamic perm; // String or List<String>; for sections outside the access screens (staff)
  final dynamic screen; // access screen key (String or List<String>) the owner shows / hides per employee
  final Widget Function(Ctx) build;
  String title(bool isAr) => isAr ? ar : en;
  bool allowed(Ctx c) => (screen == null || c.canSee(screen as Object)) && (perm == null || (perm is List ? c.canAny((perm as List).cast<String>()) : c.can(perm as String)));
}

List<PanelSection> clinicSections() => [
      PanelSection('نظرة عامة', 'Overview', Icons.dashboard_rounded, screen: 'appointments', build: (c) => ClinicDashboard(api: c.api, perms: c.perms)),
      PanelSection('قاعة الانتظار', 'Waiting room', Icons.groups_rounded, screen: 'appointments', build: (c) => QueueScreen(ctx: c)),
      PanelSection('المواعيد', 'Appointments', Icons.calendar_month_rounded, screen: 'appointments', build: (c) => AppointmentsScreen(ctx: c)),
      PanelSection('المرضى', 'Patients', Icons.people_alt_rounded, screen: 'patients', build: (c) => PatientsScreen(ctx: c)),
      PanelSection('الكشف الحالي', 'Current exam', Icons.medical_services_rounded, screen: 'exam', build: (c) => CurrentExam(ctx: c)),
      PanelSection('مواعيد العمل', 'Schedule', Icons.schedule_rounded, screen: 'schedule', build: (c) => ScheduleScreen(ctx: c)),
      PanelSection('الخدمات', 'Services', Icons.checklist_rounded, screen: ['schedule', 'appointments'], build: (c) => ServicesScreen(ctx: c)),
      PanelSection('الإعدادات', 'Settings', Icons.settings_rounded, screen: ['settings', 'schedule'], build: (c) => ClinicSettings(ctx: c)),
      PanelSection('النطاقات', 'Domains', Icons.language_rounded, screen: 'domains', build: (c) => DomainsScreen(ctx: c)),
      PanelSection('الموظفون', 'Staff', Icons.badge_rounded, perm: 'staff.manage', build: (c) => StaffScreen(ctx: c)),
      PanelSection('الفواتير والاشتراك', 'Billing', Icons.credit_card_rounded, screen: 'billing', build: (c) => BillingScreen(ctx: c)),
      PanelSection('الإشعارات', 'Notifications', Icons.notifications_rounded, build: (c) => NotificationsScreen(ctx: c)),
    ];

List<PanelSection> storeSections() => [
      PanelSection('نظرة عامة', 'Overview', Icons.dashboard_rounded, build: (c) => StoreDashboard(api: c.api, currency: c.currency, perms: c.perms)),
      PanelSection('الطلبات', 'Orders', Icons.local_shipping_rounded, screen: 'orders', build: (c) => OrdersScreen(ctx: c)),
      PanelSection('قنوات البيع', 'Sales channels', Icons.hub_rounded, screen: 'integrations', build: (c) => ChannelsScreen(ctx: c)),
      PanelSection('الشحن', 'Shipping', Icons.local_shipping_outlined, screen: 'shipping', build: (c) => ShippingScreen(ctx: c)),
      PanelSection('المنتجات', 'Products', Icons.shopping_bag_rounded, screen: 'products', build: (c) => ProductsScreen(ctx: c)),
      PanelSection('المخزون', 'Inventory', Icons.inventory_2_rounded, screen: 'inventory', build: (c) => InventoryScreen(ctx: c)),
      PanelSection('المرتجعات', 'Returns', Icons.assignment_return_rounded, screen: 'orders', build: (c) => ReturnsScreen(ctx: c)),
      PanelSection('العملاء', 'Customers', Icons.people_alt_rounded, screen: 'customers', build: (c) => CustomersScreen(ctx: c)),
      PanelSection('الكوبونات', 'Coupons', Icons.local_offer_rounded, screen: 'coupons', build: (c) => CouponsScreen(ctx: c)),
      PanelSection('التقييمات', 'Reviews', Icons.star_rounded, screen: 'reviews', build: (c) => ReviewsScreen(ctx: c)),
      PanelSection('التسويق', 'Marketing', Icons.campaign_rounded, screen: 'marketing', build: (c) => MarketingScreen(ctx: c)),
      PanelSection('الإعدادات', 'Settings', Icons.settings_rounded, screen: 'settings', build: (c) => StoreSettings(ctx: c)),
      PanelSection('النطاقات', 'Domains', Icons.language_rounded, screen: 'domains', build: (c) => DomainsScreen(ctx: c)),
      PanelSection('الموظفون', 'Staff', Icons.badge_rounded, perm: 'staff.manage', build: (c) => StaffScreen(ctx: c)),
      PanelSection('الفواتير والاشتراك', 'Billing', Icons.credit_card_rounded, screen: 'billing', build: (c) => BillingScreen(ctx: c)),
      PanelSection('الإشعارات', 'Notifications', Icons.notifications_rounded, build: (c) => NotificationsScreen(ctx: c)),
    ];

/// Bottom bar with the four main sections the user may use, plus "More" with everything else (and the website-only settings pages).
class PanelShell extends StatefulWidget {
  const PanelShell({super.key, required this.ctx});
  final Ctx ctx;
  @override
  State<PanelShell> createState() => _PanelShellState();
}

class _PanelShellState extends State<PanelShell> {
  int _tab = 0;
  late final List<PanelSection> _all = (widget.ctx.type == 'STORE' ? storeSections() : clinicSections()).where((s) => s.allowed(widget.ctx)).toList();
  late final List<PanelSection> _native = _all;
  late final List<PanelSection> _bottom = _native.take(_native.length > 5 ? 4 : _native.length).toList();
  late final List<PanelSection> _more = _all.where((s) => !_bottom.contains(s)).toList();
  final Map<int, Widget> _pages = {};
  int _gen = 0;

  @override
  void initState() {
    super.initState();
    widget.ctx.api.offline?.synced.addListener(_onSynced);
  }

  @override
  void dispose() {
    widget.ctx.api.offline?.synced.removeListener(_onSynced);
    super.dispose();
  }

  /// Changes made offline were just sent: every screen reloads the real data.
  void _onSynced() { if (mounted) setState(() { _gen++; _pages.clear(); }); }

  Widget _page(int i) => _pages.putIfAbsent(i, () => i < _bottom.length ? _bottom[i].build(widget.ctx) : _MoreList(ctx: widget.ctx, sections: _more));

  @override
  Widget build(BuildContext context) {
    final s = S.of(context);
    final isAr = s.ar;
    final hasMore = _more.isNotEmpty;
    final count = _bottom.length + (hasMore ? 1 : 0);
    if (count == 0) return Center(child: Text(s.empty));
    final moreIndex = _bottom.length;
    final off = widget.ctx.api.offline;
    return Column(children: [
      if (off != null) OfflineBanner(store: off),
      Expanded(child: AnimatedSwitcher(duration: const Duration(milliseconds: 260), switchInCurve: Curves.easeOutCubic, child: KeyedSubtree(key: ValueKey('$_tab-$_gen'), child: _page(_tab)))),
      NavigationBar(
        selectedIndex: _tab.clamp(0, count - 1),
        onDestinationSelected: (i) => setState(() => _tab = i),
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        destinations: [
          for (final sec in _bottom) NavigationDestination(icon: Icon(sec.icon), label: sec.title(isAr)),
          if (hasMore) NavigationDestination(icon: const Icon(Icons.apps_rounded), label: isAr ? 'المزيد' : 'More'),
        ],
      ),
      if (_tab == moreIndex && !hasMore) const SizedBox.shrink(),
    ]);
  }
}

class _MoreList extends StatelessWidget {
  const _MoreList({required this.ctx, required this.sections});
  final Ctx ctx;
  final List<PanelSection> sections;
  @override
  Widget build(BuildContext context) {
    final isAr = S.of(context).ar;
    return ListView(padding: const EdgeInsets.all(16), children: [
      for (var i = 0; i < sections.length; i++)
        FadeSlideIn(index: i, child: Card(child: ListTile(
          leading: Container(padding: const EdgeInsets.all(9), decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(12)), child: Icon(sections[i].icon, color: Theme.of(context).colorScheme.primary)),
          title: Text(sections[i].title(isAr), style: const TextStyle(fontWeight: FontWeight.w800)),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: () {
            final sec = sections[i];
            Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(sec.title(isAr))), body: sec.build(ctx))));
          },
        ))),
    ]);
  }
}
