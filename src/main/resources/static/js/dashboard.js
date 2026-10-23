/**
 * ==============================================================================
 * FinVault Enterprise - Main Live Dashboard Client Logic (dashboard.js)
 * Chart.js Visualizations & AJAX In-Place Multi-Role RBAC Operations
 * ==============================================================================
 */

// ==============================================================================
// CSRF TOKEN HELPER CHO MUTATING AJAX REQUESTS (POST, PUT, DELETE, PATCH)
// ==============================================================================
window.getCsrfHeaders = function () {
    const metaToken = document.querySelector('meta[name="_csrf"]');
    const metaHeader = document.querySelector('meta[name="_csrf_header"]');
    const headers = {};
    if (metaToken && metaHeader && metaToken.content && metaHeader.content) {
        headers[metaHeader.content] = metaToken.content;
    } else {
        const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        if (match) {
            headers['X-XSRF-TOKEN'] = decodeURIComponent(match[1]);
        }
    }
    return headers;
};

// ==============================================================================
// 0. TOP-LEVEL GLOBAL ACTIONS (In-Place RBAC Handlers attached to window)
// ==============================================================================

/**
 * Cuộn trang mượt mà đến khu vực chức năng RBAC và tạo hiệu ứng phát sáng nhận diện
 */
window.smoothScrollTo = function (sectionId) {
    try {
        const el = document.getElementById(sectionId);
        if (!el) {
            console.warn('[FinVault Navigation] Không tìm thấy phần tử #' + sectionId);
            return;
        }
        el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        el.classList.remove('section-highlight');
        void el.offsetWidth; // Ép browser tính toán lại layout (reflow)
        el.classList.add('section-highlight');
    } catch (e) {
        console.error('[FinVault Navigation] Lỗi điều hướng:', e);
    }
};

/**
 * Mở Modal chỉnh sửa tham số cấu hình từ nút bấm có gắn Data Attributes an toàn
 */
window.openEditConfigFromButton = function (buttonEl) {
    if (!buttonEl) return;
    const key = buttonEl.getAttribute('data-key') || '';
    const val = buttonEl.getAttribute('data-val') || '';
    const group = buttonEl.getAttribute('data-group') || '';
    const desc = buttonEl.getAttribute('data-desc') || '';
    window.openEditConfigModal(key, val, group, desc);
};

/**
 * Mở Modal chỉnh sửa tham số cấu hình thời gian thực (ROLE_SUPER_ADMIN)
 */
window.openEditConfigModal = function (configKey, currentValue, group, description) {
    try {
        const keyInput = document.getElementById('liveConfigKey');
        const valInput = document.getElementById('liveConfigValue');
        const grpInput = document.getElementById('liveConfigGroup');
        const descInput = document.getElementById('liveConfigDesc');

        if (keyInput) keyInput.value = configKey || '';
        if (valInput) valInput.value = currentValue || '';
        if (grpInput) grpInput.value = group || '';
        if (descInput) descInput.value = description || '';

        const modalEl = document.getElementById('editConfigModal');
        if (modalEl && window.bootstrap) {
            const modal = bootstrap.Modal.getOrCreateInstance(modalEl);
            modal.show();
        }
    } catch (e) {
        console.error('[FinVault Admin CMS] Lỗi hiển thị modal cấu hình:', e);
    }
};

/**
 * Gia trưởng duyệt hoặc từ chối chi tiêu quỹ gia đình (ROLE_FAMILY_HEAD / SUPER_ADMIN)
 */
window.decideFamilyTransaction = function (transactionId, isApproved) {
    const actionText = isApproved ? 'phê duyệt' : 'từ chối';
    if (!confirm('Bạn có chắc chắn muốn ' + actionText + ' khoản chi gia đình này?')) {
        return;
    }

    const payload = {
        approved: isApproved,
        rejectionReason: isApproved ? null : 'Chủ quỹ gia đình từ chối khoản chi tiêu này.'
    };

    fetch('/api/transactions/' + transactionId + '/approval', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, window.getCsrfHeaders()),
        body: JSON.stringify(payload)
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (window.FinVaultWS) {
                    window.FinVaultWS.showToast(
                        'Quyết Định Duyệt Chi',
                        (isApproved ? 'Đã duyệt thành công và giải ngân giao dịch #' : 'Đã từ chối giao dịch #') + transactionId,
                        isApproved ? 'success' : 'danger'
                    );
                } else {
                    alert(result.message || 'Thao tác thành công!');
                }
                // Xóa hàng trong bảng chờ duyệt
                const row = document.getElementById('pending-tx-row-' + transactionId);
                if (row) {
                    row.remove();
                }
                const countBadge = document.getElementById('pending-approvals-count');
                if (countBadge) {
                    let current = parseInt(countBadge.textContent || '1') - 1;
                    countBadge.textContent = Math.max(0, current);
                    if (current <= 0) {
                        const tableBody = document.getElementById('pending-approvals-tbody');
                        if (tableBody) {
                            tableBody.innerHTML = '<tr><td colspan="6" class="text-center text-muted py-3"><i class="bi bi-check2-all me-1"></i>Không có giao dịch nào đang chờ duyệt.</td></tr>';
                        }
                    }
                }
                const navBadge = document.getElementById('nav-pending-badge');
                if (navBadge) {
                    let current = parseInt(navBadge.textContent || '1') - 1;
                    if (current <= 0) navBadge.remove();
                    else navBadge.textContent = current;
                }
                // Tự động tải lại trang sau 1s để cập nhật số dư ví gia đình và biểu đồ
                setTimeout(() => window.location.reload(), 1000);
            } else {
                alert('Không thể thực hiện: ' + (result.message || 'Lỗi server'));
            }
        })
        .catch(err => {
            console.error('Lỗi phê duyệt giao dịch:', err);
            alert('Lỗi kết nối khi duyệt giao dịch gia đình.');
        });
};

/**
 * Kiểm toán viên mở khóa tài khoản người dùng bị khóa do brute-force (ROLE_SYSTEM_AUDITOR / ADMIN)
 */
window.unlockUserAccount = function (username) {
    if (!confirm('Xác nhận mở khóa tài khoản người dùng: [' + username + ']?')) {
        return;
    }

    fetch('/api/audit/locked-accounts/' + encodeURIComponent(username) + '/unlock', {
        method: 'POST',
        headers: window.getCsrfHeaders()
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (window.FinVaultWS) {
                    window.FinVaultWS.showToast('Mở Khóa Thành Công', 'Tài khoản [' + username + '] đã được phục hồi quyền truy cập!', 'success');
                } else {
                    alert('Đã mở khóa tài khoản thành công!');
                }
                const row = document.getElementById('locked-user-row-' + username);
                if (row) row.remove();
            } else {
                alert('Lỗi mở khóa: ' + (result.message || 'Không thành công'));
            }
        })
        .catch(err => {
            console.error('Lỗi mở khóa tài khoản:', err);
            alert('Đã xảy ra lỗi khi mở khóa tài khoản.');
        });
};

/**
 * Super Admin khôi phục đối tượng đã bị xóa mềm (Soft Delete Recovery)
 */
window.restoreSoftDeletedItem = function (type, id) {
    if (!confirm('Bạn có chắc chắn muốn khôi phục ' + type + ' (ID: ' + id + ') về trạng thái hoạt động?')) {
        return;
    }

    let endpoint = '';
    if (type === 'WALLET') endpoint = '/api/admin/recovery/wallets/' + id + '/restore';
    else if (type === 'TRANSACTION') endpoint = '/api/admin/recovery/transactions/' + id + '/restore';
    else if (type === 'BUDGET') endpoint = '/api/admin/recovery/budgets/' + id + '/restore';
    else if (type === 'SAVING_GOAL') endpoint = '/api/admin/recovery/saving-goals/' + id + '/restore';

    if (!endpoint) return;

    fetch(endpoint, {
        method: 'POST',
        headers: window.getCsrfHeaders()
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (window.FinVaultWS) {
                    window.FinVaultWS.showToast('Phục Hồi Dữ Liệu', 'Đã khôi phục ' + type + ' #' + id + ' thành công!', 'success');
                } else {
                    alert('Đã khôi phục thành công!');
                }
                const row = document.getElementById('deleted-item-row-' + type + '-' + id);
                if (row) row.remove();
                setTimeout(() => window.location.reload(), 1000);
            } else {
                alert('Lỗi khôi phục: ' + result.message);
            }
        })
        .catch(err => {
            console.error('Lỗi khôi phục:', err);
            alert('Đã xảy ra lỗi khi khôi phục đối tượng dữ liệu.');
        });
};

/**
 * Đổi trạng thái hiển thị thông báo hệ thống
 */
window.toggleNotificationStatus = function (notifId, currentStatus) {
    const newStatus = !currentStatus;
    fetch('/api/admin/cms/notifications/' + notifId + '/toggle?active=' + newStatus, {
        method: 'PATCH',
        headers: window.getCsrfHeaders()
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (window.FinVaultWS) {
                    window.FinVaultWS.showToast('Thông Báo Hệ Thống', 'Đã cập nhật trạng thái thông báo thành công!', 'info');
                }
                setTimeout(() => window.location.reload(), 800);
            } else {
                alert('Lỗi: ' + result.message);
            }
        })
        .catch(err => console.error('Lỗi toggle notification:', err));
};

// ==============================================================================
// 1. INITIALIZATION ON DOM READY
// ==============================================================================

document.addEventListener('DOMContentLoaded', function () {
    'use strict';

    // 1. Khởi tạo an toàn các biểu đồ phân tích BI (Chart.js)
    try { initDailyCashFlowChart(); } catch (e) { console.error('[Chart Error] Daily:', e); }
    try { initMonthlyCashFlowChart(); } catch (e) { console.error('[Chart Error] Monthly:', e); }
    try { initCategoryExpenseChart(); } catch (e) { console.error('[Chart Error] Category:', e); }

    // 2. Lắng nghe các sự kiện Form & Modal trong ứng dụng
    setupQuickTransactionForm();
    setupQuickTransferForm();
    setupCreateWalletForm();
    setupAdminLiveCms();
});

// ==============================================================================
// 2. CHART.JS VISUALIZATIONS
// ==============================================================================

let dailyChartInstance = null;
let monthlyChartInstance = null;
let categoryChartInstance = null;

function initDailyCashFlowChart() {
    const canvas = document.getElementById('dailyCashFlowChart');
    if (!canvas) return;

    fetch('/api/analytics/cashflow/daily')
        .then(res => res.json())
        .then(response => {
            if (!response.success || !response.data) {
                console.warn('[FinVault Analytics] Không có dữ liệu dòng tiền ngày');
                return;
            }
            const data = response.data;
            const ctx = canvas.getContext('2d');

            if (dailyChartInstance) dailyChartInstance.destroy();

            dailyChartInstance = new Chart(ctx, {
                type: 'line',
                data: {
                    labels: data.labels || [],
                    datasets: [
                        {
                            label: 'Tổng Thu Nhập (VND)',
                            data: data.incomes || [],
                            borderColor: '#10b981',
                            backgroundColor: 'rgba(16, 185, 129, 0.1)',
                            fill: true,
                            tension: 0.35,
                            borderWidth: 2
                        },
                        {
                            label: 'Tổng Chi Tiêu (VND)',
                            data: data.expenses || [],
                            borderColor: '#ef4444',
                            backgroundColor: 'rgba(239, 68, 68, 0.08)',
                            fill: true,
                            tension: 0.35,
                            borderWidth: 2
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: {
                            callbacks: {
                                label: function (context) {
                                    return context.dataset.label + ': ' + new Intl.NumberFormat('vi-VN').format(context.raw) + ' VND';
                                }
                            }
                        }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: {
                                callback: function (value) {
                                    return (value / 1000000).toFixed(1) + 'M';
                                }
                            }
                        }
                    }
                }
            });
        })
        .catch(err => console.error('[FinVault Analytics] Lỗi tải biểu đồ dòng tiền ngày:', err));
}

function initMonthlyCashFlowChart() {
    const canvas = document.getElementById('monthlyCashFlowChart');
    if (!canvas) return;

    fetch('/api/analytics/cashflow/monthly')
        .then(res => res.json())
        .then(response => {
            if (!response.success || !response.data) return;
            const data = response.data;
            const ctx = canvas.getContext('2d');

            if (monthlyChartInstance) monthlyChartInstance.destroy();

            monthlyChartInstance = new Chart(ctx, {
                type: 'bar',
                data: {
                    labels: data.labels || [],
                    datasets: [
                        {
                            label: 'Thu nhập',
                            data: data.incomes || [],
                            backgroundColor: 'rgba(16, 185, 129, 0.85)',
                            borderRadius: 4
                        },
                        {
                            label: 'Chi tiêu',
                            data: data.expenses || [],
                            backgroundColor: 'rgba(239, 68, 68, 0.85)',
                            borderRadius: 4
                        },
                        {
                            label: 'Dòng tiền ròng (Net)',
                            data: data.netCashFlows || [],
                            backgroundColor: 'rgba(59, 130, 246, 0.85)',
                            borderRadius: 4
                        }
                    ]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: { position: 'top' },
                        tooltip: {
                            callbacks: {
                                label: function (context) {
                                    return context.dataset.label + ': ' + new Intl.NumberFormat('vi-VN').format(context.raw) + ' VND';
                                }
                            }
                        }
                    },
                    scales: {
                        y: {
                            beginAtZero: true,
                            ticks: {
                                callback: function (value) {
                                    return (value / 1000000).toFixed(0) + 'M';
                                }
                            }
                        }
                    }
                }
            });
        })
        .catch(err => console.error('[FinVault Analytics] Lỗi tải biểu đồ 12 tháng:', err));
}

function initCategoryExpenseChart() {
    const canvas = document.getElementById('categoryDoughnutChart');
    if (!canvas) return;

    fetch('/api/analytics/category-expenses')
        .then(res => res.json())
        .then(response => {
            if (!response.success || !response.data) return;
            const data = response.data;
            const ctx = canvas.getContext('2d');

            if (categoryChartInstance) categoryChartInstance.destroy();

            const items = data.items || [];
            const labels = items.map(i => i.categoryName);
            const values = items.map(i => i.totalAmount);
            const colors = items.map(i => i.color || '#3b82f6');

            categoryChartInstance = new Chart(ctx, {
                type: 'doughnut',
                data: {
                    labels: labels.length > 0 ? labels : ['Chưa có chi tiêu'],
                    datasets: [{
                        data: values.length > 0 ? values : [1],
                        backgroundColor: colors.length > 0 ? colors : ['#e2e8f0'],
                        hoverOffset: 6
                    }]
                },
                options: {
                    responsive: true,
                    maintainAspectRatio: false,
                    plugins: {
                        legend: {
                            position: 'bottom',
                            labels: { boxWidth: 12, font: { size: 11 } }
                        },
                        tooltip: {
                            callbacks: {
                                label: function (context) {
                                    const val = context.raw;
                                    return context.label + ': ' + new Intl.NumberFormat('vi-VN').format(val) + ' VND';
                                }
                            }
                        }
                    },
                    cutout: '68%'
                }
            });
        })
        .catch(err => console.error('[FinVault Analytics] Lỗi tải biểu đồ tỷ trọng danh mục:', err));
}

// ==============================================================================
// 3. QUICK TRANSACTION & TRANSFER & CREATE WALLET
// ==============================================================================

function setupQuickTransactionForm() {
    const form = document.getElementById('quickTransactionForm');
    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();

        const walletId = document.getElementById('txWalletId').value;
        const categoryId = document.getElementById('txCategoryId').value;
        const type = document.getElementById('txType').value;
        const amount = document.getElementById('txAmount').value;
        const note = document.getElementById('txNote').value;
        const date = document.getElementById('txDate').value;

        if (!walletId) {
            alert('Vui lòng chọn ví tài chính!');
            return;
        }

        const payload = {
            walletId: parseInt(walletId),
            categoryId: parseInt(categoryId),
            transactionType: type,
            amount: parseFloat(amount),
            note: note,
            transactionDate: date ? date + 'T00:00:00' : null
        };

        const submitBtn = form.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang xử lý...';

        fetch('/api/transactions', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, window.getCsrfHeaders()),
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(result => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-check2-circle me-1"></i> Lưu Giao Dịch';

                if (result.success) {
                    if (window.FinVaultWS) {
                        window.FinVaultWS.showToast('Giao Dịch Thành Công', result.message || 'Đã ghi nhận giao dịch vào sổ cái an toàn!', 'success');
                    } else {
                        alert('Giao dịch thành công!');
                    }
                    // Đóng modal
                    const modalEl = document.getElementById('quickTransactionModal');
                    if (modalEl && window.bootstrap) {
                        const modal = bootstrap.Modal.getInstance(modalEl);
                        if (modal) modal.hide();
                    }
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    alert('Lỗi: ' + (result.message || 'Không thể tạo giao dịch'));
                }
            })
            .catch(err => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-check2-circle me-1"></i> Lưu Giao Dịch';
                console.error('Lỗi gửi giao dịch:', err);
                alert('Đã xảy ra lỗi kết nối khi lưu giao dịch.');
            });
    });
}

function setupQuickTransferForm() {
    const form = document.getElementById('quickTransferForm');
    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();

        const sourceWalletId = document.getElementById('transferSourceWalletId').value;
        const targetWalletId = document.getElementById('transferTargetWalletId').value;
        const amount = document.getElementById('transferAmount').value;
        const note = document.getElementById('transferNote').value;

        if (sourceWalletId === targetWalletId) {
            alert('Ví nguồn và Ví đích không được trùng nhau!');
            return;
        }

        const payload = {
            sourceWalletId: parseInt(sourceWalletId),
            targetWalletId: parseInt(targetWalletId),
            amount: parseFloat(amount),
            note: note
        };

        const submitBtn = form.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang chuyển tiền...';

        fetch('/api/transactions/transfer', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, window.getCsrfHeaders()),
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(result => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-arrow-left-right me-1"></i> Xác Nhận Chuyển';

                if (result.success) {
                    if (window.FinVaultWS) {
                        window.FinVaultWS.showToast('Chuyển Tiền Thành Công', 'Đã chuyển tiền liên ví thành công chống Deadlock!', 'success');
                    }
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    alert('Lỗi chuyển tiền: ' + (result.message || 'Thao tác không thành công'));
                }
            })
            .catch(err => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-arrow-left-right me-1"></i> Xác Nhận Chuyển';
                console.error('Lỗi chuyển tiền:', err);
                alert('Lỗi kết nối khi chuyển tiền liên ví.');
            });
    });
}

function setupCreateWalletForm() {
    const form = document.getElementById('createWalletForm');
    if (!form) return;

    form.addEventListener('submit', function (e) {
        e.preventDefault();

        const walletName = document.getElementById('newWalletName').value;
        const walletType = document.getElementById('newWalletType').value;
        const initialBalance = document.getElementById('newWalletInitialBalance').value || '0';
        const bankName = document.getElementById('newWalletBankName').value;
        const accountNumber = document.getElementById('newWalletAccountNumber').value;

        const payload = {
            walletName: walletName.trim(),
            walletType: walletType,
            initialBalance: parseFloat(initialBalance),
            currency: 'VND',
            bankName: bankName ? bankName.trim() : null,
            bankAccountNumber: accountNumber ? accountNumber.trim() : null
        };

        const submitBtn = form.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span> Đang tạo ví...';

        fetch('/api/wallets', {
            method: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, window.getCsrfHeaders()),
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(result => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-wallet2 me-1"></i> Tạo Ví Mới';

                if (result.success) {
                    if (window.FinVaultWS) {
                        window.FinVaultWS.showToast('Tạo Ví Thành Công', 'Ví [' + walletName + '] đã sẵn sàng sử dụng!', 'success');
                    } else {
                        alert('Tạo ví mới thành công!');
                    }
                    const modalEl = document.getElementById('createWalletModal');
                    if (modalEl && window.bootstrap) {
                        const modal = bootstrap.Modal.getInstance(modalEl);
                        if (modal) modal.hide();
                    }
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    alert('Lỗi tạo ví: ' + (result.message || 'Thất bại'));
                }
            })
            .catch(err => {
                submitBtn.disabled = false;
                submitBtn.innerHTML = '<i class="bi bi-wallet2 me-1"></i> Tạo Ví Mới';
                console.error('Lỗi tạo ví:', err);
                alert('Đã xảy ra lỗi khi tạo ví mới.');
            });
    });
}

// ==============================================================================
// 4. SUPER ADMIN LIVE CMS CONFIGURATION FORM
// ==============================================================================

function setupAdminLiveCms() {
    const configForm = document.getElementById('liveConfigForm');
    if (!configForm) return;

    configForm.addEventListener('submit', function (e) {
        e.preventDefault();
        const key = document.getElementById('liveConfigKey').value;
        const value = document.getElementById('liveConfigValue').value;
        const group = document.getElementById('liveConfigGroup').value;
        const desc = document.getElementById('liveConfigDesc').value;

        const payload = {
            configKey: key,
            configValue: value,
            configGroup: group,
            description: desc,
            isActive: true
        };

        fetch('/api/admin/cms/configs/' + encodeURIComponent(key), {
            method: 'PUT',
            headers: Object.assign({ 'Content-Type': 'application/json' }, window.getCsrfHeaders()),
            body: JSON.stringify(payload)
        })
            .then(res => res.json())
            .then(result => {
                if (result.success) {
                    if (window.FinVaultWS) {
                        window.FinVaultWS.showToast('Cập Nhật Tham Số', 'Đã lưu cấu hình [' + key + '] theo thời gian thực!', 'success');
                    }
                    const modalEl = document.getElementById('editConfigModal');
                    if (modalEl && window.bootstrap) {
                        const modal = bootstrap.Modal.getInstance(modalEl);
                        if (modal) modal.hide();
                    }
                    setTimeout(() => window.location.reload(), 1000);
                } else {
                    alert('Lỗi cập nhật cấu hình: ' + (result.message || 'Thất bại'));
                }
            })
            .catch(err => {
                console.error('Lỗi lưu config:', err);
                alert('Lỗi kết nối khi cập nhật cấu hình.');
            });
    });
}

// ==============================================================================
// 12. GOOGLE AUTHENTICATOR 2FA CLIENT LOGIC (Member 1 - Security & IAM)
// ==============================================================================
window.loadTwoFactorStatus = function () {
    const alertEl = document.getElementById('twoFactorAlert');
    if (alertEl) {
        alertEl.classList.add('d-none');
        alertEl.textContent = '';
    }

    const statusText = document.getElementById('twoFactorStatusText');
    const statusBadge = document.getElementById('twoFactorStatusBadge');
    const disabledSection = document.getElementById('twoFactorDisabledSection');
    const enabledSection = document.getElementById('twoFactorEnabledSection');
    const step1 = document.getElementById('twoFactorSetupStep1');
    const step2 = document.getElementById('twoFactorSetupStep2');

    if (statusText) statusText.textContent = 'Đang kiểm tra...';

    fetch('/api/2fa/status', {
        headers: Object.assign({ 'Accept': 'application/json' }, window.getCsrfHeaders())
    })
        .then(res => res.json())
        .then(result => {
            if (result.success && result.data) {
                const isEnabled = !!result.data.isTwoFactorEnabled;
                if (isEnabled) {
                    if (statusText) statusText.textContent = 'Đang Bật (Google Authenticator)';
                    if (statusBadge) {
                        statusBadge.className = 'badge bg-success';
                        statusBadge.textContent = 'Đã Bật';
                    }
                    if (disabledSection) disabledSection.style.display = 'none';
                    if (enabledSection) enabledSection.style.display = 'block';
                } else {
                    if (statusText) statusText.textContent = 'Chưa Kích Hoạt';
                    if (statusBadge) {
                        statusBadge.className = 'badge bg-warning text-dark';
                        statusBadge.textContent = 'Chưa Bật';
                    }
                    if (disabledSection) disabledSection.style.display = 'block';
                    if (enabledSection) enabledSection.style.display = 'none';
                    if (step1) step1.style.display = 'block';
                    if (step2) step2.style.display = 'none';
                }
            }
        })
        .catch(err => {
            console.error('Lỗi kiểm tra trạng thái 2FA:', err);
            if (statusText) statusText.textContent = 'Không thể kiểm tra';
        });
};

window.startTwoFactorSetup = function () {
    const alertEl = document.getElementById('twoFactorAlert');
    const step1 = document.getElementById('twoFactorSetupStep1');
    const step2 = document.getElementById('twoFactorSetupStep2');
    const qrImg = document.getElementById('twoFactorQrImage');
    const secretKeyInput = document.getElementById('twoFactorSecretKey');

    fetch('/api/2fa/setup', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json', 'Accept': 'application/json' }, window.getCsrfHeaders())
    })
        .then(res => res.json())
        .then(result => {
            if (result.success && result.data) {
                const secret = result.data.secretKey || '';
                const qrUrl = result.data.qrCodeUrl || '';

                if (secretKeyInput) secretKeyInput.value = secret;
                if (qrImg) {
                    qrImg.src = 'https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=' + encodeURIComponent(qrUrl);
                }

                if (step1) step1.style.display = 'none';
                if (step2) step2.style.display = 'block';
            } else {
                if (alertEl) {
                    alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                    alertEl.textContent = result.message || 'Không thể khởi tạo cấu hình 2FA.';
                    alertEl.classList.remove('d-none');
                }
            }
        })
        .catch(err => {
            console.error('Lỗi khởi tạo 2FA:', err);
            if (alertEl) {
                alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                alertEl.textContent = 'Lỗi kết nối khi khởi tạo 2FA.';
                alertEl.classList.remove('d-none');
            }
        });
};

window.confirmEnableTwoFactor = function () {
    const codeInput = document.getElementById('twoFactorVerifyCode');
    const alertEl = document.getElementById('twoFactorAlert');
    const code = (codeInput ? codeInput.value : '').trim();

    if (!code || code.length !== 6) {
        if (alertEl) {
            alertEl.className = 'alert alert-warning py-2 px-3 small rounded-3 mb-3';
            alertEl.textContent = 'Vui lòng nhập đúng mã 6 chữ số từ ứng dụng Google Authenticator.';
            alertEl.classList.remove('d-none');
        }
        return;
    }

    fetch('/api/2fa/enable', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json', 'Accept': 'application/json' }, window.getCsrfHeaders()),
        body: JSON.stringify({ code: code })
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (alertEl) {
                    alertEl.className = 'alert alert-success py-2 px-3 small rounded-3 mb-3';
                    alertEl.textContent = 'Kích hoạt 2FA (Google Authenticator) thành công!';
                    alertEl.classList.remove('d-none');
                }
                setTimeout(() => window.loadTwoFactorStatus(), 800);
            } else {
                if (alertEl) {
                    alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                    alertEl.textContent = result.message || 'Mã xác thực 2FA không chính xác.';
                    alertEl.classList.remove('d-none');
                }
            }
        })
        .catch(err => {
            console.error('Lỗi kích hoạt 2FA:', err);
            if (alertEl) {
                alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                alertEl.textContent = 'Lỗi hệ thống khi kích hoạt 2FA.';
                alertEl.classList.remove('d-none');
            }
        });
};

window.confirmDisableTwoFactor = function () {
    const codeInput = document.getElementById('twoFactorDisableCode');
    const alertEl = document.getElementById('twoFactorAlert');
    const code = (codeInput ? codeInput.value : '').trim();

    if (!code) {
        if (alertEl) {
            alertEl.className = 'alert alert-warning py-2 px-3 small rounded-3 mb-3';
            alertEl.textContent = 'Vui lòng nhập mã 6 số TOTP để xác nhận tắt 2FA.';
            alertEl.classList.remove('d-none');
        }
        return;
    }

    fetch('/api/2fa/disable', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json', 'Accept': 'application/json' }, window.getCsrfHeaders()),
        body: JSON.stringify({ code: code })
    })
        .then(res => res.json())
        .then(result => {
            if (result.success) {
                if (alertEl) {
                    alertEl.className = 'alert alert-info py-2 px-3 small rounded-3 mb-3';
                    alertEl.textContent = 'Đã tắt bảo mật 2 lớp thành công.';
                    alertEl.classList.remove('d-none');
                }
                setTimeout(() => window.loadTwoFactorStatus(), 800);
            } else {
                if (alertEl) {
                    alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                    alertEl.textContent = result.message || 'Mã xác thực không chính xác để tắt 2FA.';
                    alertEl.classList.remove('d-none');
                }
            }
        })
        .catch(err => {
            console.error('Lỗi tắt 2FA:', err);
            if (alertEl) {
                alertEl.className = 'alert alert-danger py-2 px-3 small rounded-3 mb-3';
                alertEl.textContent = 'Lỗi hệ thống khi tắt 2FA.';
                alertEl.classList.remove('d-none');
            }
        });
};

