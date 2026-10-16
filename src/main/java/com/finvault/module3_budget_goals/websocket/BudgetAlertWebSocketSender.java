package com.finvault.module3_budget_goals.websocket;

import com.finvault.module3_budget_goals.dto.BudgetAlertNotificationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * ==============================================================================
 * [Owner: Member 3 - Budget & Financial Goals Module]
 * BudgetAlertWebSocketSender: Thành phần gửi cảnh báo thời gian thực qua STOMP WebSocket
 *
 * Kênh truyền thông báo (STOMP Topics):
 * - /topic/budget-alerts/{username}: Nhắm trực tiếp vào người dùng sở hữu ngân sách
 * - /topic/admin/budget-alerts: Thông báo đến kênh giám sát của Super Admin và System Auditor
 * ==============================================================================
 */
@Component
public class BudgetAlertWebSocketSender {

    private static final Logger log = LoggerFactory.getLogger(BudgetAlertWebSocketSender.class);

    private final SimpMessagingTemplate messagingTemplate;

    public BudgetAlertWebSocketSender(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Bắn cảnh báo ngân sách thời gian thực tới Client đang kết nối
     */
    public void sendAlert(String targetUsername, BudgetAlertNotificationDto alert) {
        if (targetUsername == null || alert == null) {
            return;
        }

        try {
            // Gửi tới kênh người dùng cụ thể
            String userDestination = "/topic/budget-alerts/" + targetUsername;
            messagingTemplate.convertAndSend(userDestination, alert);

            // Gửi tới kênh giám sát chung của Quản trị viên
            messagingTemplate.convertAndSend("/topic/admin/budget-alerts", alert);

            log.info("Đã phát cảnh báo WebSocket thành công tới [{}]: Mức độ [{}], Ngân sách [{}]",
                    targetUsername, alert.getSeverity(), alert.getBudgetName());
        } catch (Exception e) {
            log.error("Lỗi khi gửi thông báo WebSocket cảnh báo ngân sách cho user [{}]: {}", targetUsername, e.getMessage());
        }
    }
}
