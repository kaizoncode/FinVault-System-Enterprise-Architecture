package com.finvault.module5_admin_audit.service;

import com.finvault.common.BusinessException;
import com.finvault.module5_admin_audit.aop.Auditable;
import com.finvault.module5_admin_audit.dto.SystemNotificationRequest;
import com.finvault.module5_admin_audit.dto.SystemNotificationResponseDto;
import com.finvault.module5_admin_audit.entity.SystemNotification;
import com.finvault.module5_admin_audit.repository.SystemNotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ==============================================================================
 * [Owner: Member 5 - Admin Live CMS & Audit Logging Module]
 * SystemNotificationServiceImpl: Triển khai phát thông báo toàn hệ thống (Live Admin CMS)
 * ==============================================================================
 */
@Service
@Transactional
public class SystemNotificationServiceImpl implements SystemNotificationService {

    private static final Logger log = LoggerFactory.getLogger(SystemNotificationServiceImpl.class);

    private final SystemNotificationRepository notificationRepository;

    public SystemNotificationServiceImpl(SystemNotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemNotificationResponseDto> getActiveNotifications() {
        return notificationRepository.findAllByIsActiveTrueOrderByCreatedAtDesc().stream()
                .map(SystemNotificationResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<SystemNotificationResponseDto> getAllNotifications() {
        return notificationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(SystemNotificationResponseDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Override
    @Auditable(action = "CREATE_SYSTEM_NOTIFICATION", module = "SYSTEM_NOTIFICATION", entityName = "system_notifications")
    public SystemNotificationResponseDto createNotification(String adminUsername, SystemNotificationRequest request) {
        log.info("Admin [{}] ban hành thông báo mới: [{}]", adminUsername, request.getTitle());

        SystemNotification notification = new SystemNotification(
                request.getTitle(),
                request.getContent(),
                request.getNotificationType(),
                request.getIsActive(),
                request.getBroadcastToRoles(),
                adminUsername
        );

        SystemNotification saved = notificationRepository.save(notification);
        log.info("Ban hành thành công thông báo ID: {}", saved.getId());
        return SystemNotificationResponseDto.fromEntity(saved);
    }

    @Override
    @Auditable(action = "UPDATE_SYSTEM_NOTIFICATION", module = "SYSTEM_NOTIFICATION", entityName = "system_notifications")
    public SystemNotificationResponseDto updateNotification(Long id, String adminUsername, SystemNotificationRequest request) {
        log.info("Admin [{}] cập nhật thông báo ID: {}", adminUsername, id);

        SystemNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo ID: " + id));

        notification.setTitle(request.getTitle());
        notification.setContent(request.getContent());
        if (request.getNotificationType() != null) {
            notification.setNotificationType(request.getNotificationType());
        }
        if (request.getIsActive() != null) {
            notification.setIsActive(request.getIsActive());
        }
        if (request.getBroadcastToRoles() != null) {
            notification.setBroadcastToRoles(request.getBroadcastToRoles());
        }
        notification.setUpdatedAt(LocalDateTime.now());

        SystemNotification updated = notificationRepository.save(notification);
        return SystemNotificationResponseDto.fromEntity(updated);
    }

    @Override
    @Auditable(action = "TOGGLE_NOTIFICATION_STATUS", module = "SYSTEM_NOTIFICATION", entityName = "system_notifications")
    public void toggleNotificationStatus(Long id, String adminUsername, boolean active) {
        log.info("Admin [{}] chuyển trạng thái thông báo ID: {} thành [{}]", adminUsername, id, active);
        SystemNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo ID: " + id));

        notification.setIsActive(active);
        notification.setUpdatedAt(LocalDateTime.now());
        notificationRepository.save(notification);
    }

    @Override
    @Auditable(action = "DELETE_SYSTEM_NOTIFICATION", module = "SYSTEM_NOTIFICATION", entityName = "system_notifications")
    public void deleteNotification(Long id, String adminUsername) {
        log.info("Admin [{}] xóa thông báo ID: {}", adminUsername, id);
        SystemNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("NOTIFICATION_NOT_FOUND", "Không tìm thấy thông báo ID: " + id));

        notificationRepository.delete(notification);
    }
}
