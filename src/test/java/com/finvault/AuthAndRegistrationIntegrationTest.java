package com.finvault;

import com.finvault.module1_security_rbac.dto.RegisterRequest;
import com.finvault.module1_security_rbac.dto.UserResponseDto;
import com.finvault.module1_security_rbac.service.CustomUserDetailsService;
import com.finvault.module1_security_rbac.service.UserService;
import com.finvault.controller.MainDashboardController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthAndRegistrationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthenticationProvider authenticationProvider;

    @Autowired
    private CustomUserDetailsService userDetailsService;

    @Autowired
    private UserService userService;

    @Autowired
    private MainDashboardController mainDashboardController;

    @Test
    @DisplayName("1. Kiểm tra tài khoản seeded có thể nạp thông tin và đăng nhập chuẩn xác")
    void testSeededUsersLogin() {
        String[] testUsers = {"admin", "auditor", "family_head", "user1"};
        for (String username : testUsers) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            assertNotNull(userDetails, "UserDetails không được null đối với user: " + username);
            assertEquals(username, userDetails.getUsername());

            Authentication authRequest = new UsernamePasswordAuthenticationToken(username, "Password@123");
            Authentication authResult = authenticationProvider.authenticate(authRequest);
            assertNotNull(authResult);
            assertTrue(authResult.isAuthenticated(), "Tài khoản " + username + " phải đăng nhập thành công với Password@123");
        }
    }

    @Test
    @DisplayName("2. Kiểm tra đăng ký người dùng mới động không bị lỗi thiếu cột PENDING_TWO_FACTOR_SECRET")
    void testRegisterNewUser() {
        String uniqueUser = "dynamic_user_" + System.currentTimeMillis();
        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setUsername(uniqueUser);
        registerRequest.setEmail(uniqueUser + "@example.com");
        registerRequest.setPassword("TestPass@2026");
        registerRequest.setFullName("Người Dùng Thử Nghiệm Tự Động");
        registerRequest.setPhoneNumber("0988776655");

        UserResponseDto createdUser = userService.registerUser(registerRequest);
        assertNotNull(createdUser, "Người dùng mới tạo không được null");
        assertEquals(uniqueUser, createdUser.getUsername());

        // Kiểm tra đăng nhập với tài khoản vừa tạo
        Authentication authRequest = new UsernamePasswordAuthenticationToken(uniqueUser, "TestPass@2026");
        Authentication authResult = authenticationProvider.authenticate(authRequest);
        assertNotNull(authResult);
        assertTrue(authResult.isAuthenticated());

        // Kiểm tra truy cập Dashboard của người dùng mới tạo
        SecurityContextHolder.getContext().setAuthentication(authResult);
        Model model = new ConcurrentModel();
        String viewName = mainDashboardController.showDashboard(model);
        assertEquals("dashboard", viewName);
        assertNotNull(model.getAttribute("currentUser"));
        assertNotNull(model.getAttribute("wallets"));
        assertNotNull(model.getAttribute("activeBudgets"));
    }

    @Test
    @DisplayName("3. Kiểm tra gia trưởng (family_head) truy cập dashboard không bị lỗi")
    void testFamilyHeadDashboard() {
        Authentication authRequest = new UsernamePasswordAuthenticationToken("family_head", "Password@123");
        Authentication authResult = authenticationProvider.authenticate(authRequest);
        assertNotNull(authResult);
        SecurityContextHolder.getContext().setAuthentication(authResult);

        Model model = new ConcurrentModel();
        String viewName = mainDashboardController.showDashboard(model);
        assertEquals("dashboard", viewName);
        assertNotNull(model.getAttribute("pendingApprovals"));
        assertNotNull(model.getAttribute("familyWallets"));
    }

    @Test
    @DisplayName("4. Kiểm tra full HTTP MockMvc GET /dashboard cho family_head và Thymeleaf template rendering")
    void testFamilyHeadHttpMockMvc() throws Exception {
        mockMvc.perform(get("/dashboard")
                .with(user("family_head").roles("FAMILY_HEAD")))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }

    @Test
    @DisplayName("5. Kiểm tra full HTTP MockMvc GET /dashboard cho user1 và Thymeleaf template rendering")
    void testUser1HttpMockMvc() throws Exception {
        mockMvc.perform(get("/dashboard")
                .with(user("user1").roles("STANDARD_USER")))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"));
    }
}
