package com.finvault.module5_admin_audit.util;

import java.math.BigDecimal;

/**
 * ==============================================================================
 * [Owner: Member 5 - Admin Live CMS & Audit Logging Module]
 * DataMaskingUtil: Tiện ích che mặt nạ dữ liệu nhạy cảm (Data Masking) cho tài khoản Kiểm toán (Auditor)
 *
 * Tiêu chuẩn bảo mật dữ liệu doanh nghiệp:
 * - Che số dư tài khoản & số tiền giao dịch: "***,*** VND"
 * - Che số tài khoản ngân hàng: Giữ 4 số đầu và 4 số cuối (Ví dụ: "1903****8901")
 * - Che địa chỉ Email: Giữ ký tự đầu và domain (Ví dụ: "u***1@example.com")
 * - Che số điện thoại: Giữ 3 số đầu và 3 số cuối (Ví dụ: "091****888")
 * ==============================================================================
 */
public final class DataMaskingUtil {

    private DataMaskingUtil() {
    }

    /**
     * Che số tiền nhạy cảm đối với tài khoản kiểm toán viên
     */
    public static String maskAmount(BigDecimal amount) {
        if (amount == null) {
            return "***,*** VND";
        }
        return "***,*** VND";
    }

    /**
     * Che số tài khoản ngân hàng (Giữ 4 số đầu và 4 số cuối)
     */
    public static String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "N/A";
        }
        String clean = accountNumber.trim();
        if (clean.length() <= 8) {
            return "****" + clean.substring(Math.max(0, clean.length() - 4));
        }
        String prefix = clean.substring(0, 4);
        String suffix = clean.substring(clean.length() - 4);
        return prefix + "****" + suffix;
    }

    /**
     * Che địa chỉ Email
     */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***@***.com";
        }
        String[] parts = email.split("@");
        String name = parts[0];
        String domain = parts[1];

        if (name.length() <= 2) {
            return name.charAt(0) + "***@" + domain;
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1) + "@" + domain;
    }

    /**
     * Che số điện thoại
     */
    public static String maskPhoneNumber(String phone) {
        if (phone == null || phone.length() < 7) {
            return "09******";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 3);
    }
}
