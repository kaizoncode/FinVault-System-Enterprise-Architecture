package com.finvault.module1_security_rbac.controller;

import com.finvault.module1_security_rbac.dto.RegisterRequest;
import com.finvault.module1_security_rbac.service.BruteForceProtectionService;
import com.finvault.module1_security_rbac.service.CustomUserDetailsService;
import com.finvault.module1_security_rbac.service.TwoFactorAuthService;
import com.finvault.module1_security_rbac.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ==============================================================================
 * [Owner: Member 1 - Security & IAM Module]
 * AuthController: Điều khiển giao diện Đăng nhập, Đăng ký và Xác thực 2 bước (2FA)
 *
 * Điểm chờ nâng cấp (Extension Point):
 * - Hỗ trợ WebAuthn FIDO2 Biometric Login UI (v2.0)
 * ==============================================================================
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserService userService;
    private final TwoFactorAuthService twoFactorAuthService;
    private final BruteForceProtectionService bruteForceProtectionService;
    private final CustomUserDetailsService customUserDetailsService;

    public AuthController(
            UserService userService,
            TwoFactorAuthService twoFactorAuthService,
            BruteForceProtectionService bruteForceProtectionService,
            CustomUserDetailsService customUserDetailsService) {
        this.userService = userService;
        this.twoFactorAuthService = twoFactorAuthService;
        this.bruteForceProtectionService = bruteForceProtectionService;
        this.customUserDetailsService = customUserDetailsService;
    }

    @GetMapping("/login")
    public String showLoginForm(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "locked", required = false) String locked,
            @RequestParam(value = "remainingMinutes", required = false) String remainingMinutes,
            Model model,
            Authentication authentication) {

        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "redirect:/dashboard";
        }

        if (locked != null) {
            model.addAttribute("errorMessage", "Tài khoản của bạn tạm thời bị khóa " +
                    (remainingMinutes != null ? remainingMinutes : "15") + " phút do nhập sai mật khẩu quá 5 lần.");
        } else if (error != null) {
            model.addAttribute("errorMessage", "Tên đăng nhập hoặc mật khẩu không chính xác.");
        }

        if (logout != null) {
            model.addAttribute("successMessage", "Bạn đã đăng xuất an toàn khỏi hệ thống.");
        }

        return "login";
    }

    @GetMapping("/register")
    public String showRegisterForm(Model model, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated() && !"anonymousUser".equals(authentication.getPrincipal())) {
            return "redirect:/dashboard";
        }
        model.addAttribute("registerRequest", new RegisterRequest());
        return "register";
    }

    @PostMapping("/register")
    public String handleRegister(
            @Valid @ModelAttribute("registerRequest") RegisterRequest request,
            BindingResult bindingResult,
            Model model) {

        if (bindingResult.hasErrors()) {
            return "register";
        }

        try {
            userService.registerUser(request);
            return "redirect:/login?registered=true";
        } catch (Exception ex) {
            log.warn("Đăng ký tài khoản thất bại: {}", ex.getMessage());
            model.addAttribute("errorMessage", ex.getMessage());
            return "register";
        }
    }

    @GetMapping("/auth/2fa")
    public String show2FaVerificationPage(HttpSession session, Model model) {
        String preAuthUser = (String) session.getAttribute("PRE_AUTH_USER");
        if (preAuthUser == null) {
            return "redirect:/login";
        }
        model.addAttribute("username", preAuthUser);
        return "2fa_verify";
    }

    @PostMapping("/auth/2fa")
    public String verify2FaLogin(
            @RequestParam("code") String code,
            HttpSession session,
            HttpServletRequest request,
            Model model) {

        String preAuthUser = (String) session.getAttribute("PRE_AUTH_USER");
        if (preAuthUser == null) {
            return "redirect:/login";
        }

        boolean isValid = twoFactorAuthService.verifyTotpCode(preAuthUser, code);
        if (!isValid) {
            model.addAttribute("username", preAuthUser);
            model.addAttribute("errorMessage", "Mã xác thực 2FA không chính xác hoặc đã hết hạn.");
            return "2fa_verify";
        }

        // Nạp UserDetails và tái thiết lập SecurityContext chính thức sau khi vượt qua 2FA
        UserDetails userDetails = customUserDetailsService.loadUserByUsername(preAuthUser);
        UsernamePasswordAuthenticationToken authToken =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authToken);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, SecurityContextHolder.getContext());

        bruteForceProtectionService.recordSuccessfulLogin(preAuthUser);
        session.removeAttribute("PRE_AUTH_USER");
        session.setAttribute("TWO_FACTOR_VERIFIED", true);
        log.info("Xác thực 2FA đăng nhập thành công cho người dùng [{}]", preAuthUser);
        return "redirect:/dashboard";
    }
}
