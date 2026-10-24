import 'package:flutter/material.dart';
import 'package:platform_core/platform_core.dart';

import 'clinic_panel.dart';
import 'copilot.dart';
import 'dev_content.dart';
import 'growth.dart';
import 'retail.dart';
import 'admin.dart';
import 'ai_assistant.dart';
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

/// Group headings of the menu (Arabic, English), in the order they appear.
const Map<String, List<String>> kMenuGroups = {
  'home': ['الرئيسية', 'Home'], 'sales': ['المبيعات', 'Sales'], 'catalog': ['الكتالوج', 'Catalog'], 'growth': ['العملاء والنمو', 'Customers & growth'],
  'insights': ['التحليلات', 'Insights'], 'channels': ['القنوات والشحن', 'Channels & delivery'], 'setup': ['الإعدادات', 'Setup'], 'account': ['الحساب', 'Account'],
  'clinic': ['العيادة', 'Clinic'], 'records': ['المرضى', 'Patients'],
};

/// One section of the control panel. Mirrors the website's menu: the same sections, hidden without the same permission.
class PanelSection {
  const PanelSection(this.ar, this.en, this.icon, {this.perm, this.screen, this.group = '', this.bottom = false, required this.build});
  final String ar, en;
  final IconData icon;
  final dynamic perm; // String or List<String>; for sections outside the access screens (staff)
  final dynamic screen; // access screen key (String or List<String>) the owner shows / hides per employee
  final String group; // heading in the More list, same grouping as the website's menu
  final bool bottom; // preferred for the bottom bar
  final Widget Function(Ctx) build;
  String title(bool isAr) => isAr ? ar : en;
  bool allowed(Ctx c) => (screen == null || c.canSee(screen as Object)) && (perm == null || (perm is List ? c.canAny((perm as List).cast<String>()) : c.can(perm as String)));
}

List<PanelSection> clinicSections() => [
      PanelSection('نظرة عامة', 'Overview', Icons.dashboard_rounded, screen: 'appointments', group: 'clinic', build: (c) => ClinicDashboard(api: c.api, perms: c.perms)),
      PanelSection('قاعة الانتظار', 'Waiting room', Icons.groups_rounded, screen: 'appointments', group: 'clinic', build: (c) => QueueScreen(ctx: c)),
      PanelSection('المواعيد', 'Appointments', Icons.calendar_month_rounded, screen: 'appointments', group: 'clinic', build: (c) => AppointmentsScreen(ctx: c)),
      PanelSection('المرضى', 'Patients', Icons.people_alt_rounded, screen: 'patients', group: 'records', build: (c) => PatientsScreen(ctx: c)),
      PanelSection('الكشف الحالي', 'Current exam', Icons.medical_services_rounded, screen: 'exam', group: 'clinic', build: (c) => CurrentExam(ctx: c)),
      PanelSection('مواعيد العمل', 'Schedule', Icons.schedule_rounded, screen: 'schedule', group: 'setup', build: (c) => ScheduleScreen(ctx: c)),
      PanelSection('الخدمات', 'Services', Icons.checklist_rounded, screen: ['schedule', 'appointments'], group: 'setup', build: (c) => ServicesScreen(ctx: c)),
      PanelSection('الإعدادات', 'Settings', Icons.settings_rounded, screen: ['settings', 'schedule'], group: 'setup', build: (c) => ClinicSettings(ctx: c)),
      PanelSection('النطاقات', 'Domains', Icons.language_rounded, screen: 'domains', group: 'setup', build: (c) => DomainsScreen(ctx: c)),
      PanelSection('الموظفون', 'Staff', Icons.badge_rounded, perm: 'staff.manage', group: 'account', build: (c) => StaffScreen(ctx: c)),
      PanelSection('الفواتير والاشتراك', 'Billing', Icons.credit_card_rounded, screen: 'billing', group: 'account', build: (c) => BillingScreen(ctx: c)),
      PanelSection('الإشعارات', 'Notifications', Icons.notifications_rounded, group: 'account', build: (c) => NotificationsScreen(ctx: c)),
    ];

List<PanelSection> storeSections() => [
      PanelSection('نظرة عامة', 'Overview', Icons.dashboard_rounded, group: 'home', bottom: true, build: (c) => StoreDashboard(api: c.api, currency: c.currency, perms: c.perms)),
      PanelSection('الطلبات', 'Orders', Icons.local_shipping_rounded, screen: 'orders', group: 'sales', bottom: true, build: (c) => OrdersScreen(ctx: c)),
      PanelSection('نقطة البيع', 'Point of sale', Icons.point_of_sale_rounded, perm: 'pos.use', group: 'sales', bottom: true, build: (c) => PosScreen(ctx: c)),
      PanelSection('سلات غير مكتملة', 'Incomplete carts', Icons.remove_shopping_cart_rounded, perm: ['cart.read', 'cart.manage'], group: 'sales', build: (c) => CartsScreen(ctx: c)),
      PanelSection('المرتجعات', 'Returns', Icons.assignment_return_rounded, screen: 'orders', group: 'sales', build: (c) => ReturnsScreen(ctx: c)),
      PanelSection('الفواتير الضريبية', 'Tax invoices', Icons.receipt_long_rounded, perm: 'invoice.manage', group: 'sales', build: (c) => InvoicesScreen(ctx: c)),
      PanelSection('المنتجات', 'Products', Icons.shopping_bag_rounded, screen: 'products', group: 'catalog', bottom: true, build: (c) => ProductsScreen(ctx: c)),
      PanelSection('المخزون', 'Inventory', Icons.inventory_2_rounded, screen: 'inventory', group: 'catalog', build: (c) => InventoryScreen(ctx: c)),
      PanelSection('المشتريات', 'Purchasing', Icons.local_mall_rounded, perm: 'purchase.manage', group: 'catalog', build: (c) => PurchasingScreen(ctx: c)),
      PanelSection('الكوبونات', 'Coupons', Icons.local_offer_rounded, screen: 'coupons', group: 'catalog', build: (c) => CouponsScreen(ctx: c)),
      PanelSection('التقييمات', 'Reviews', Icons.star_rounded, screen: 'reviews', group: 'catalog', build: (c) => ReviewsScreen(ctx: c)),
      PanelSection('العملاء', 'Customers', Icons.people_alt_rounded, screen: 'customers', group: 'growth', build: (c) => CustomersScreen(ctx: c)),
      PanelSection('نمو العملاء', 'Customers growth', Icons.trending_up_rounded, perm: 'growth.manage', group: 'growth', build: (c) => GrowthScreen(ctx: c)),
      PanelSection('التسويق', 'Marketing', Icons.campaign_rounded, screen: 'marketing', group: 'growth', build: (c) => MarketingScreen(ctx: c)),
      PanelSection('التقارير', 'Reports', Icons.bar_chart_rounded, perm: 'report.read', group: 'insights', build: (c) => ReportsScreen(ctx: c)),
      PanelSection('اسأل نشاطك', 'Ask your business', Icons.auto_awesome_rounded, perm: 'copilot.use', group: 'insights', build: (c) => CopilotScreen(ctx: c)),
      PanelSection('قنوات البيع', 'Sales channels', Icons.hub_rounded, screen: 'integrations', group: 'channels', build: (c) => ChannelsScreen(ctx: c)),
      PanelSection('المساعد الذكي', 'AI assistant', Icons.smart_toy_rounded, screen: 'ai', group: 'channels', build: (c) => AiAssistantScreen(ctx: c)),
      PanelSection('الشحن', 'Shipping', Icons.local_shipping_outlined, screen: 'shipping', group: 'channels', build: (c) => ShippingScreen(ctx: c)),
      PanelSection('الإعدادات', 'Settings', Icons.settings_rounded, screen: 'settings', group: 'setup', build: (c) => StoreSettings(ctx: c)),
      PanelSection('النطاقات', 'Domains', Icons.language_rounded, screen: 'domains', group: 'setup', build: (c) => DomainsScreen(ctx: c)),
      PanelSection('للمطورين', 'Developers', Icons.code_rounded, perm: 'api.manage', group: 'setup', build: (c) => DevelopersScreen(ctx: c)),
      PanelSection('الموظفون', 'Staff', Icons.badge_rounded, perm: 'staff.manage', group: 'account', build: (c) => StaffScreen(ctx: c)),
      PanelSection('الفواتير والاشتراك', 'Billing', Icons.credit_card_rounded, screen: 'billing', group: 'account', build: (c) => BillingScreen(ctx: c)),
      PanelSection('الإشعارات', 'Notifications', Icons.notifications_rounded, group: 'account', build: (c) => NotificationsScreen(ctx: c)),
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
  late final List<PanelSection> _bottom = _pickBottom(_native);
  late final List<PanelSection> _more = _all.where((s) => !_bottom.contains(s)).toList();
  /// Four tabs: the sections marked for the bar first (Overview, Orders, Point of sale, Products), then the first others when the shop may not see one of those.
  static List<PanelSection> _pickBottom(List<PanelSection> all) {
    if (all.length <= 5) return all.toList();
    final picked = all.where((x) => x.bottom).take(4).toList();
    for (final x in all) { if (picked.length >= 4) break; if (!picked.contains(x)) picked.add(x); }
    return picked;
  }

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
    final tiles = <Widget>[];
    String? lastGroup;
    for (var i = 0; i < sections.length; i++) {
      final g = sections[i].group;
      if (g.isNotEmpty && g != lastGroup && kMenuGroups[g] != null) {
        tiles.add(Padding(padding: EdgeInsets.fromLTRB(4, tiles.isEmpty ? 0 : 14, 4, 6), child: Text(kMenuGroups[g]![isAr ? 0 : 1], style: TextStyle(fontSize: 12.5, fontWeight: FontWeight.w800, color: Colors.grey.shade600))));
      }
      lastGroup = g;
      tiles.add(
        FadeSlideIn(index: i, child: Card(child: ListTile(
          leading: Container(padding: const EdgeInsets.all(9), decoration: BoxDecoration(color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(12)), child: Icon(sections[i].icon, color: Theme.of(context).colorScheme.primary)),
          title: Text(sections[i].title(isAr), style: const TextStyle(fontWeight: FontWeight.w800)),
          trailing: const Icon(Icons.chevron_right_rounded),
          onTap: () {
            final sec = sections[i];
            Navigator.of(context).push(MaterialPageRoute(builder: (_) => Scaffold(appBar: AppBar(title: Text(sec.title(isAr))), body: sec.build(ctx))));
          },
        ))));
    }
    return ListView(padding: const EdgeInsets.all(16), children: tiles);
  }
}
