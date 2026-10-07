import 'package:flutter/widgets.dart';
import 'package:intl/intl.dart';

/// Minimal Arabic/English strings shared by the apps. Money is integer minor units; never format it by hand.
class S {
  S(this.code);
  final String code;
  static S of(BuildContext c) => S(Localizations.localeOf(c).languageCode == 'en' ? 'en' : 'ar');
  bool get ar => code == 'ar';

  String get login => ar ? 'تسجيل الدخول' : 'Log in';
  String get phone => ar ? 'رقم الهاتف أو البريد' : 'Phone or email';
  String get password => ar ? 'كلمة المرور' : 'Password';
  String get newPassword => ar ? 'كلمة مرور جديدة (8 أحرف على الأقل)' : 'New password (min 8 characters)';
  String get code_ => ar ? 'الكود من النشاط' : 'Code from the business';
  String get firstTime => ar ? 'أول مرة؟ أدخل الكود الذي أعطوه لك واختر كلمة مرور.' : 'First time? Enter the code you were given and choose a password.';
  String get continue_ => ar ? 'متابعة' : 'Continue';
  String get logout => ar ? 'خروج' : 'Log out';
  String get address => ar ? 'عنوان المتجر أو العيادة' : 'Store or clinic address';
  String get addressHint => ar ? 'مثال: fashion أو www.my-brand.com' : 'e.g. fashion or www.my-brand.com';
  String get products => ar ? 'المنتجات' : 'Products';
  String get cart => ar ? 'السلة' : 'Cart';
  String get addToCart => ar ? 'أضف إلى السلة' : 'Add to cart';
  String get checkout => ar ? 'إتمام الشراء' : 'Checkout';
  String get placeOrder => ar ? 'تأكيد الطلب' : 'Place order';
  String get cod => ar ? 'الدفع عند الاستلام' : 'Cash on delivery';
  String get orders => ar ? 'طلباتي' : 'My orders';
  String get appointments => ar ? 'المواعيد' : 'Appointments';
  String get record => ar ? 'ملفي' : 'My record';
  String get today => ar ? 'اليوم' : 'Today';
  String get retry => ar ? 'إعادة المحاولة' : 'Retry';
  String get empty => ar ? 'لا يوجد شيء بعد' : 'Nothing here yet';
  String get address1 => ar ? 'العنوان' : 'Address';
  String get city => ar ? 'المدينة' : 'City';
  String get total => ar ? 'الإجمالي' : 'Total';
  String get myClinics => ar ? 'عياداتي' : 'My clinics';
  String get queueTurn => ar ? 'حان دورك' : "It's your turn";
  String get queueNext => ar ? 'أنت التالي' : 'You are next';
  String queueAhead(int n) => ar ? 'قبلك $n مريض' : '$n patient(s) before you';
  String get account => ar ? 'حسابي' : 'My account';
  String get currentPassword => ar ? 'كلمة المرور الحالية' : 'Current password';
  String get changePassword => ar ? 'تغيير كلمة المرور' : 'Change password';
  String get passwordChanged => ar ? 'تم تغيير كلمة المرور' : 'Password changed';
  String get leaveClinic => ar ? 'مغادرة هذه العيادة' : 'Leave this clinic';
  String get waitingRoom => ar ? 'قاعة الانتظار' : 'Waiting room';
  String get call => ar ? 'نداء' : 'Call';
  String get startVisit => ar ? 'بدء الكشف' : 'Start visit';
  String get orderPlaced => ar ? 'تم استلام طلبك' : 'Order received';

  /// Integer minor units -> localized currency text (19999 EGP -> 199.99).
  String money(num? minor, String currency) {
    if (minor == null) return '-';
    return NumberFormat.currency(locale: ar ? 'ar_EG' : 'en_GB', name: currency, decimalDigits: 2).format(minor / 100);
  }
}
