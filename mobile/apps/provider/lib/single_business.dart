/// Set when the app is built for ONE business only (e.g. the law office app com.elhusseiny.lowyer): the business list, sign-up and
/// platform admin are hidden, the account's other businesses are not shown, and the app carries that business's name.
/// The Elmanassa Business app leaves it empty.
class SingleBusiness {
  static String? slug;
  static String appTitle = 'Elmanassa Business';
  static String nameAr = 'Elmanassa Business', nameEn = 'Elmanassa Business';
  static String taglineAr = 'أدر متجرك أو عيادتك أو مكتب المحاماة من هاتفك', taglineEn = 'Run your shop, clinic or law office from your phone';
  static String version = '1.3.38';   // shown in settings; keep in step with pubspec.yaml
  static bool get on => slug != null;
}
