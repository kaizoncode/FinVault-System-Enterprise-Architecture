-- ==============================================================================
-- FINVAULT ENTERPRISE - INITIAL SEED DATA (data.sql)
-- Default Roles, Accounts (Password: Password@123), Categories, System Configs
-- ==============================================================================

-- 1. SEED ROLES
MERGE INTO roles (id, role_name, description) KEY(role_name) VALUES
(1, 'ROLE_SUPER_ADMIN', 'Quản trị viên cấp cao có toàn quyền hệ thống và Live CMS Edit Mode'),
(2, 'ROLE_SYSTEM_AUDITOR', 'Kiểm toán viên hệ thống, giám sát Audit Log và Data Masking số dư'),
(3, 'ROLE_FAMILY_HEAD', 'Chủ hộ gia đình, quản lý Quỹ chung và phê duyệt chi tiêu thành viên'),
(4, 'ROLE_STANDARD_USER', 'Người dùng cá nhân chuẩn, quản lý ví và ngân sách cá nhân');

-- 2. SEED USERS (Mật khẩu mặc định: Password@123 -> Hash BCrypt chuẩn xác thực bởi Spring Security 6)
-- $2a$10$FEC/MeKwGq.y2xqlubzip.hpxujNVsafXGpVXLR2Z92WHQ3rlp5VG
MERGE INTO app_users KEY(username) VALUES
(1, 'admin', 'admin@finvault.com', '$2a$10$FEC/MeKwGq.y2xqlubzip.hpxujNVsafXGpVXLR2Z92WHQ3rlp5VG', 'Nguyễn Quản Trị Viên', '0901234567', FALSE, NULL, 0, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', FALSE, 0),
(2, 'auditor', 'auditor@finvault.com', '$2a$10$FEC/MeKwGq.y2xqlubzip.hpxujNVsafXGpVXLR2Z92WHQ3rlp5VG', 'Trần Kiểm Toán Viên', '0912345678', FALSE, NULL, 0, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', FALSE, 0),
(3, 'family_head', 'family@finvault.com', '$2a$10$FEC/MeKwGq.y2xqlubzip.hpxujNVsafXGpVXLR2Z92WHQ3rlp5VG', 'Lê Gia Trưởng', '0923456789', FALSE, NULL, 0, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', FALSE, 0),
(4, 'user1', 'user1@finvault.com', '$2a$10$FEC/MeKwGq.y2xqlubzip.hpxujNVsafXGpVXLR2Z92WHQ3rlp5VG', 'Phạm Người Dùng Chuẩn', '0934567890', FALSE, NULL, 0, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', FALSE, 0);

-- 3. SEED USER_ROLES MAPPING
MERGE INTO user_roles KEY(user_id, role_id) VALUES
(1, 1), -- admin -> ROLE_SUPER_ADMIN
(2, 2), -- auditor -> ROLE_SYSTEM_AUDITOR
(3, 3), -- family_head -> ROLE_FAMILY_HEAD
(4, 4); -- user1 -> ROLE_STANDARD_USER

-- 4. SEED SYSTEM DEFAULT CATEGORIES
MERGE INTO categories (id, user_id, category_name, category_type, icon, color, is_system_default, is_active, created_at, updated_at, is_deleted) KEY(id) VALUES
-- Thu nhập (Income)
(1, NULL, 'Lương & Thu nhập cố định', 'INCOME', 'bi-cash-coin', '#198754', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(2, NULL, 'Thưởng & Đầu tư sinh lời', 'INCOME', 'bi-graph-up-arrow', '#0dcaf0', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(3, NULL, 'Kinh doanh phụ & Freelance', 'INCOME', 'bi-laptop', '#20c997', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
-- Chi tiêu (Expense)
(4, NULL, 'Ăn uống & Cà phê', 'EXPENSE', 'bi-cup-hot', '#fd7e14', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(5, NULL, 'Tiền nhà & Hóa đơn tiện ích', 'EXPENSE', 'bi-house-door', '#dc3545', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(6, NULL, 'Đi lại & Xăng xe vận tải', 'EXPENSE', 'bi-car-front', '#6f42c1', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(7, NULL, 'Y tế & Chăm sóc sức khỏe', 'EXPENSE', 'bi-heart-pulse', '#d63384', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(8, NULL, 'Mua sắm & Giải trí gia đình', 'EXPENSE', 'bi-bag-heart', '#ffc107', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE),
(9, NULL, 'Giáo dục & Đào tạo cá nhân', 'EXPENSE', 'bi-book', '#0d6efd', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE);

-- 5. SEED SYSTEM CONFIGURATIONS (Live Admin CMS)
MERGE INTO system_configs (id, config_key, config_value, config_group, description, is_editable, updated_by, updated_at) KEY(config_key) VALUES
(1, 'SYSTEM_ALERT_THRESHOLD_WARNING', '80', 'SECURITY_ALERT', 'Ngưỡng cảnh báo chi tiêu vượt mức (Vàng)', TRUE, 'admin', CURRENT_TIMESTAMP),
(2, 'SYSTEM_ALERT_THRESHOLD_CRITICAL', '100', 'SECURITY_ALERT', 'Ngưỡng cảnh báo vỡ ngân sách nghiêm trọng (Đỏ)', TRUE, 'admin', CURRENT_TIMESTAMP),
(3, 'FAMILY_EXPENSE_APPROVAL_THRESHOLD', '5000000', 'FAMILY_RULE', 'Số tiền chi tiêu gia đình vượt mức cần Gia trưởng phê duyệt (VND)', TRUE, 'admin', CURRENT_TIMESTAMP),
(4, 'SYSTEM_BANNER_MESSAGE', 'Hệ thống FinVault Enterprise đang hoạt động ổn định với cơ chế Live UI & Dynamic RBAC v1.0.', 'SYSTEM_NOTIFICATION', 'Banner thông báo chạy trên thanh tiêu đề hệ thống', TRUE, 'admin', CURRENT_TIMESTAMP),
(5, 'DEFAULT_CURRENCY', 'VND', 'GENERAL', 'Đơn vị tiền tệ chuẩn mặc định của toàn hệ thống', TRUE, 'admin', CURRENT_TIMESTAMP);

-- 6. SEED SYSTEM NOTIFICATIONS
MERGE INTO system_notifications (id, title, content, notification_type, is_active, broadcast_to_roles, created_by, created_at, updated_at) KEY(id) VALUES
(1, 'Cập nhật hệ thống FinVault v1.0', 'Tính năng Live Admin Edit Mode và Dynamic RBAC 4 Roles đã chính thức đi vào hoạt động toàn diện.', 'INFO', TRUE, 'ALL', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 'Bảo mật 2FA & Mã hóa dữ liệu', 'Khuyến cáo tất cả quản trị viên và gia trưởng kích hoạt bảo mật hai lớp Google Authenticator (TOTP).', 'WARNING', TRUE, 'ROLE_SUPER_ADMIN,ROLE_FAMILY_HEAD', 'admin', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 7. SEED WALLETS
MERGE INTO wallets (id, user_id, wallet_name, wallet_type, balance, currency, bank_account_number, bank_name, is_family_wallet, monthly_spending_limit, is_active, created_at, updated_at, created_by, updated_by, is_deleted, version) KEY(id) VALUES
(1, 4, 'Ví Tiền mặt Hàng ngày', 'CASH', 8500000.00, 'VND', NULL, NULL, FALSE, 0.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(2, 4, 'Tài khoản Techcombank Cá nhân', 'BANK', 45000000.00, 'VND', '19034567890123', 'Techcombank', FALSE, 0.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(3, 3, 'Quỹ Tài Chính Chung Gia Đình', 'FAMILY_FUND', 185000000.00, 'VND', '1029384756', 'Vietcombank Priority', TRUE, 50000000.00, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'family_head', 'family_head', FALSE, 0);

-- 8. SEED WALLET MEMBERS (Gia đình)
MERGE INTO wallet_members (id, wallet_id, user_id, member_role, spending_limit, is_active, joined_at) KEY(wallet_id, user_id) VALUES
(1, 3, 3, 'OWNER', 50000000.00, TRUE, CURRENT_TIMESTAMP),
(2, 3, 4, 'MEMBER', 10000000.00, TRUE, CURRENT_TIMESTAMP);

-- 9. SEED BUDGETS (Ngân sách thông minh 50/30/20)
MERGE INTO budgets (id, user_id, category_id, wallet_id, budget_name, budget_type, allocation_type, allocated_amount, spent_amount, start_date, end_date, alert_threshold_80_sent, alert_threshold_100_sent, is_active, created_at, updated_at, created_by, updated_by, is_deleted, version) KEY(id) VALUES
(1, 4, 5, 1, 'Ngân sách Chi tiêu Thiết yếu (Needs 50%)', 'RULE_50_30_20', 'NEEDS', 15000000.00, 6800000.00, CURRENT_DATE, DATEADD('MONTH', 1, CURRENT_DATE), FALSE, FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(2, 4, 8, 1, 'Ngân sách Sở thích & Cá nhân (Wants 30%)', 'RULE_50_30_20', 'WANTS', 9000000.00, 7500000.00, CURRENT_DATE, DATEADD('MONTH', 1, CURRENT_DATE), TRUE, FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(3, 4, 1, 2, 'Mục tiêu Tích lũy Định kỳ (Savings 20%)', 'RULE_50_30_20', 'SAVINGS', 6000000.00, 6000000.00, CURRENT_DATE, DATEADD('MONTH', 1, CURRENT_DATE), FALSE, FALSE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0);

-- 10. SEED SAVING GOALS
MERGE INTO saving_goals (id, user_id, wallet_id, goal_name, target_amount, current_amount, target_date, status, notes, created_at, updated_at, is_deleted, version) KEY(id) VALUES
(1, 4, 2, 'Quỹ Dự Phòng Khẩn Cấp (6 Tháng)', 100000000.00, 45000000.00, DATEADD('YEAR', 1, CURRENT_DATE), 'IN_PROGRESS', 'Dành cho các tình huống phát sinh y tế hoặc bảo hiểm', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 0),
(2, 3, 3, 'Quỹ Mua Xe Ô Tô Gia Đình Mới', 500000000.00, 280000000.00, DATEADD('YEAR', 2, CURRENT_DATE), 'IN_PROGRESS', 'Quỹ đóng góp chung từ các thành viên trong gia đình', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, FALSE, 0);

-- 11. SEED SAMPLE TRANSACTIONS
MERGE INTO transactions (id, wallet_id, target_wallet_id, user_id, category_id, transaction_type, amount, transaction_date, note, receipt_url, approval_status, approved_by, approved_at, rejection_reason, is_recurring, recurring_interval, created_at, updated_at, created_by, updated_by, is_deleted, version) KEY(id) VALUES
(1, 2, NULL, 4, 1, 'INCOME', 30000000.00, CURRENT_TIMESTAMP, 'Nhận lương tháng cố định từ Công ty Phần mềm', NULL, 'APPROVED', 'SYSTEM', CURRENT_TIMESTAMP, NULL, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(2, 1, NULL, 4, 4, 'EXPENSE', 350000.00, CURRENT_TIMESTAMP, 'Ăn trưa tiếp khách và cà phê đối tác', NULL, 'APPROVED', 'SYSTEM', CURRENT_TIMESTAMP, NULL, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(3, 1, NULL, 4, 5, 'EXPENSE', 4500000.00, CURRENT_TIMESTAMP, 'Thanh toán tiền điện và phí dịch vụ chung cư', NULL, 'APPROVED', 'SYSTEM', CURRENT_TIMESTAMP, NULL, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0),
(4, 3, NULL, 4, 8, 'EXPENSE', 7500000.00, CURRENT_TIMESTAMP, 'Mua máy giặt sấy thông minh cho gia đình', NULL, 'PENDING', NULL, NULL, NULL, FALSE, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'user1', 'user1', FALSE, 0);

-- 12. SEED INITIAL AUDIT LOGS
MERGE INTO audit_logs (id, actor_username, actor_role, action, target_module, entity_name, entity_id, old_value, new_value, ip_address, user_agent, timestamp, status, failure_reason) KEY(id) VALUES
(1, 'SYSTEM', 'ROLE_SUPER_ADMIN', 'SYSTEM_INITIALIZATION', 'SYSTEM', 'system_configs', 1, NULL, 'Cấu hình mặc định ban đầu được khởi tạo thành công.', '127.0.0.1', 'FinVault Bootstrapper', CURRENT_TIMESTAMP, 'SUCCESS', NULL),
(2, 'admin', 'ROLE_SUPER_ADMIN', 'RBAC_BOOTSTRAP', 'SECURITY_IAM', 'roles', 4, NULL, 'Khởi tạo 4 nhóm phân quyền chuẩn: Super Admin, Auditor, Family Head, Standard User.', '127.0.0.1', 'Mozilla/5.0 (Windows NT 10.0)', CURRENT_TIMESTAMP, 'SUCCESS', NULL);

-- 13. TÁI THIẾT LẬP DÃY SEQUENCE IDENTITY (BẮT ĐẦU TỪ 1000 ĐỂ TRÁNH XUNG ĐỘT PRIMARY KEY)
ALTER TABLE roles ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE app_users ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE categories ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE wallets ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE wallet_members ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE transactions ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE budgets ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE saving_goals ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE system_configs ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE system_notifications ALTER COLUMN id RESTART WITH 1000;
ALTER TABLE audit_logs ALTER COLUMN id RESTART WITH 1000;
