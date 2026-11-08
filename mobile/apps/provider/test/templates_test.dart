import 'package:elmanassa_business/inbox/inbox_filters.dart';
import 'package:elmanassa_business/theme/templates.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('each business type gets its own template and theme', () {
    expect(templateFor('STORE').kind, TemplateKind.shop);
    expect(templateFor('CLINIC').kind, TemplateKind.clinic);
    expect(templateFor('LAWYER').kind, TemplateKind.lawyer);
    expect(themeOfTemplate(templateFor('STORE')).extension<AppTemplate>(), same(shopTemplate));
    expect(themeOfTemplate(templateFor('CLINIC')).colorScheme.primary.toARGB32(), 0xFF0F3D3A);
    expect(themeOfTemplate(templateFor('LAWYER')).colorScheme.primary.toARGB32(), 0xFF14213D);
  });

  test('inbox filters join values with commas', () {
    final f = InboxFilters(status: {'NEEDS_REPLY', 'UNHAPPY'}, channel: {'WHATSAPP'});
    expect(f.query()['status'], 'NEEDS_REPLY,UNHAPPY');
    expect(f.query()['channel'], 'WHATSAPP');
    expect(f.count, 3);
    expect(InboxFilters().isEmpty, isTrue);
  });
}
