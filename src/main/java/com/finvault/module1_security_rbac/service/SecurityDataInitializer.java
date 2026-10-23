package com.finvault.module1_security_rbac.service;

import com.finvault.module1_security_rbac.entity.Role;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.repository.RoleRepository;
import com.finvault.module1_security_rbac.repository.UserRepository;
import com.finvault.module2_wallet_ledger.entity.Wallet;
import com.finvault.module2_wallet_ledger.entity.WalletType;
import com.finvault.module2_wallet_ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

/**
 * ==============================================================================
 * [Owner: Member 1 - Security & IAM Module]
 * SecurityDataInitializer: Đảm bảo toàn vẹn dữ liệu cho các tài khoản lõi (Admin, Auditor,
 * Family Head, Standard User, và User 2FA) khi ứng dụng khởi động.
 * Đảm bảo 100% tài khoản demo luôn có mật khẩu Password@123, đúng phân quyền, và
 * user_2fa luôn sẵn sàng kiểm thử Google Authenticator TOTP.
 * ==============================================================================
 */
@Component
@Order(10)
public class SecurityDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDataInitializer.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public SecurityDataInitializer(
            UserRepository userRepository,
            RoleRepository roleRepository,
            WalletRepository walletRepository,
            PasswordEncoder passwordEncoder,
            org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.walletRepository = walletRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        log.info("Khởi động SecurityDataInitializer: Kiểm tra và chuẩn hóa tài khoản demo hệ thống...");

        // 1. Đảm bảo 4 Roles tồn tại
        Role superAdminRole = ensureRole("ROLE_SUPER_ADMIN", "Quản trị viên cấp cao");
        Role auditorRole = ensureRole("ROLE_SYSTEM_AUDITOR", "Kiểm toán viên hệ thống");
        Role familyHeadRole = ensureRole("ROLE_FAMILY_HEAD", "Chủ hộ gia đình");
        Role standardUserRole = ensureRole("ROLE_STANDARD_USER", "Người dùng cá nhân chuẩn");

        // 2. Chuẩn hóa/Tạo mới các tài khoản mẫu
        ensureUser("admin", "admin@finvault.com", "Nguyễn Quản Trị Viên", "0901234567", superAdminRole, false, null);
        ensureUser("auditor", "auditor@finvault.com", "Trần Kiểm Toán Viên", "0912345678", auditorRole, false, null);
        ensureUser("family_head", "family@finvault.com", "Lê Gia Trưởng", "0923456789", familyHeadRole, false, null);
        ensureUser("user1", "user1@finvault.com", "Phạm Người Dùng Chuẩn", "0934567890", standardUserRole, false, null);

        // 3. Đảm bảo tài khoản 2FA chuyên dụng (user_2fa) luôn tồn tại và bật 2FA
        User user2fa = ensureUser("user_2fa", "user2fa@finvault.com", "Nguyễn Hai Lớp 2FA", "0945678901", standardUserRole, true, "JBSWY3DPEHPK3PXP");

        // 4. Đảm bảo user_2fa có ví hoạt động
        if (user2fa != null) {
            long walletCount = walletRepository.countByUserIdAndIsDeletedFalse(user2fa.getId());
            if (walletCount == 0) {
                Wallet w = new Wallet();
                w.setUser(user2fa);
                w.setWalletName("Ví Tiết Kiệm Bảo Mật 2FA");
                w.setWalletType(WalletType.BANK);
                w.setBalance(new BigDecimal("25000000.00"));
                w.setCurrency("VND");
                w.setIsFamilyWallet(false);
                w.setIsActive(true);
                walletRepository.save(w);
                log.info("Đã khởi tạo ví bảo mật 25,000,000 VND cho tài khoản user_2fa.");
            }
        }

        // 5. Căn chỉnh Sequence Identity động cho tất cả các bảng để tránh xung đột khóa chính
        alignDatabaseSequences();

        log.info("SecurityDataInitializer hoàn tất chuẩn hóa tài khoản và căn chỉnh sequences.");
    }

    private void alignDatabaseSequences() {
        String[] tables = {
            "roles", "app_users", "categories", "wallets", "wallet_members",
            "transactions", "budgets", "saving_goals", "system_configs",
            "system_notifications", "audit_logs"
        };
        for (String table : tables) {
            try {
                Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM " + table, Long.class);
                long nextId = Math.max(1000L, (maxId != null ? maxId : 0) + 1);
                jdbcTemplate.execute("ALTER TABLE " + table + " ALTER COLUMN id RESTART WITH " + nextId);
                log.info("Căn chỉnh sequence bảng [{}]: maxId={}, nextId={}", table, maxId, nextId);
            } catch (Exception ex) {
                log.warn("Không thể căn chỉnh sequence bảng [{}]: {}", table, ex.getMessage());
            }
        }
    }

    private Role ensureRole(String roleName, String description) {
        return roleRepository.findByRoleName(roleName).orElseGet(() -> {
            Role r = new Role(roleName, description);
            return roleRepository.save(r);
        });
    }

    private User ensureUser(String username, String email, String fullName, String phone,
                            Role role, boolean isTwoFactor, String twoFactorSecret) {
        return userRepository.findByUsername(username).map(user -> {
            // Cập nhật lại mật khẩu chuẩn Password@123 nếu bị lệch
            if (!passwordEncoder.matches("Password@123", user.getPasswordHash())) {
                user.setPasswordHash(passwordEncoder.encode("Password@123"));
            }
            if (isTwoFactor) {
                user.setIsTwoFactorEnabled(true);
                if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
                    user.setTwoFactorSecret(twoFactorSecret);
                }
            }
            if (!user.hasRole(role.getRoleName())) {
                user.getRoles().add(role);
            }
            user.setIsActive(true);
            user.setIsDeleted(false);
            return userRepository.save(user);
        }).orElseGet(() -> {
            User newUser = new User(username, email, passwordEncoder.encode("Password@123"), fullName);
            newUser.setPhoneNumber(phone);
            newUser.setIsActive(true);
            newUser.setIsDeleted(false);
            newUser.setFailedLoginAttempts(0);
            newUser.setRoles(Set.of(role));
            if (isTwoFactor) {
                newUser.setIsTwoFactorEnabled(true);
                newUser.setTwoFactorSecret(twoFactorSecret);
            }
            User saved = userRepository.save(newUser);
            log.info("Tạo mới thành công tài khoản cốt lõi [{}] với Role [{}]", username, role.getRoleName());
            return saved;
        });
    }
}
