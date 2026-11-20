package com.platform.core.tenant;

/**
 * STORE sells products. CLINIC is a medical practice. LAWYER is a law office. SERVICE is an appointment-based service business (salon, barber, plumber, repair, cleaning...):
 * it is its own business type everywhere, and it runs on the shop's building blocks (catalog, orders, bookings, AI, wallet), so those checks use {@link #isShopLike}.
 */
public enum TenantType {
    STORE, CLINIC, LAWYER, SERVICE;

    /** Types that run on the commerce modules (products, orders, customers, bookings, assistant, points wallet). */
    public static boolean isShopLike(String type) { return "STORE".equals(type) || "SERVICE".equals(type); }
}
