package com.finvault.module1_security_rbac.service;

import com.finvault.common.BusinessException;
import com.finvault.module1_security_rbac.crypto.TotpManager;
import com.finvault.module1_security_rbac.dto.TwoFactorSetupDto;
import com.finvault.module1_security_rbac.entity.User;
import com.finvault.module1_security_rbac.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ==============================================================================
 * [Owner: Member 1 - Security & IAM Module]
 * TwoFactorAuthService: Xử lý quy trình thiết lập, bật/tắt và xác thực bảo mật 2 lớp (2FA TOTP)
 * Sử dụng Google Authenticator và mã hóa AES-256 cho khóa bí mật.
 *
 * Điểm chờ nâng cấp (Extension Point):
 * - Hỗ trợ WebAuthn FIDO2 Biometric Passkey song song với TOTP 2FA (v2.0)
 * ==============================================================================
 */
@Service
public class TwoFactorAuthService {

    private static final Logger log = LoggerFactory.getLogger(TwoFactorAuthService.class);

    private final UserRepository userRepository;
    private final TotpManager totpManager;

    public TwoFactorAuthService(UserRepository userRepository, TotpManager totpManager) {
        this.userRepository = userRepository;
        this.totpManager = totpManager;
    }

    /**
     * Khởi tạo quá trình đăng ký 2FA: Sinh mã bí mật mới và URL QR Code
     */
    @Transactional
    public TwoFactorSetupDto initiate2FaSetup(String username) {
        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy người dùng", HttpStatus.NOT_FOUND));

        String secretKey = totpManager.generateSecretKey();
        user.setTwoFactorSecret(secretKey);
        userRepository.save(user);

        String qrCodeUrl = totpManager.getQrCodeUrl(username, secretKey);
        List<String> scratchCodes = totpManager.generateScratchCodes();

        log.info("Khởi tạo thiết lập 2FA thành công cho người dùng [{}]", username);
        return new TwoFactorSetupDto(secretKey, qrCodeUrl, scratchCodes);
    }

    /**
     * Kích hoạt 2FA: Xác thực mã 6 số đầu tiên từ ứng dụng Authenticator
     */
    @Transactional
    public boolean confirmAndEnable2Fa(String username, String code) {
        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy người dùng", HttpStatus.NOT_FOUND));

        if (user.getTwoFactorSecret() == null || user.getTwoFactorSecret().isBlank()) {
            throw new BusinessException("TWO_FA_NOT_INITIATED", "Bạn chưa khởi tạo thiết lập 2FA");
        }

        boolean isValid = totpManager.verifyCode(user.getTwoFactorSecret(), code);
        if (!isValid) {
            log.warn("Kích hoạt 2FA thất bại: Mã TOTP '{}' không hợp lệ cho user [{}]", code, username);
            return false;
        }

        user.setIsTwoFactorEnabled(true);
        userRepository.save(user);
        log.info("Kích hoạt bảo mật 2FA (Google Authenticator) THÀNH CÔNG cho người dùng [{}]", username);
        return true;
    }

    /**
     * Hủy kích hoạt 2FA: Yêu cầu xác thực mã TOTP trước khi tắt
     */
    @Transactional
    public boolean disable2Fa(String username, String code) {
        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy người dùng", HttpStatus.NOT_FOUND));

        if (!Boolean.TRUE.equals(user.getIsTwoFactorEnabled())) {
            return true;
        }

        boolean isValid = totpManager.verifyCode(user.getTwoFactorSecret(), code);
        if (!isValid) {
            log.warn("Hủy 2FA thất bại: Mã TOTP không hợp lệ cho user [{}]", username);
            return false;
        }

        user.setIsTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        userRepository.save(user);
        log.info("Đã tắt bảo mật 2FA thành công cho người dùng [{}]", username);
        return true;
    }

    /**
     * Xác thực mã TOTP trong quá trình đăng nhập hoặc thực hiện hành động nhạy cảm
     */
    @Transactional(readOnly = true)
    public boolean verifyTotpCode(String username, String code) {
        User user = userRepository.findByUsernameAndIsDeletedFalse(username)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Không tìm thấy người dùng", HttpStatus.NOT_FOUND));

        if (!Boolean.TRUE.equals(user.getIsTwoFactorEnabled())) {
            return true; // Không bật 2FA thì luôn pass
        }

        if (user.getTwoFactorSecret() == null) {
            return false;
        }

        return totpManager.verifyCode(user.getTwoFactorSecret(), code);
    }
}
