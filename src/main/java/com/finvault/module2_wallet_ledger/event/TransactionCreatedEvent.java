package com.finvault.module2_wallet_ledger.event;

import com.finvault.module2_wallet_ledger.entity.Transaction;
import org.springframework.context.ApplicationEvent;

/**
 * ==============================================================================
 * [Owner: Member 2 - Core Ledger & Multi-Wallet Module]
 * TransactionCreatedEvent: Sự kiện giao dịch tài chính phát sinh thành công
 *
 * Áp dụng Observer Pattern / Event-Driven Architecture:
 * - Module 3 (Budget Automation) lắng nghe sự kiện này để tự động cập nhật ngân sách
 *   và kích hoạt cảnh báo vượt ngưỡng (80% / 100%).
 * - Module 5 (Audit Log & CMS) lắng nghe sự kiện để ghi vết kiểm toán tài chính bất đồng bộ.
 * ==============================================================================
 */
public class TransactionCreatedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final Transaction transaction;

    public TransactionCreatedEvent(Object source, Transaction transaction) {
        super(source);
        this.transaction = transaction;
    }

    public Transaction getTransaction() {
        return transaction;
    }
}
