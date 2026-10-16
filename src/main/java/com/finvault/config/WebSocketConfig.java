package com.finvault.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * ==============================================================================
 * [Owner: Architecture Core / Member 3 (WebSocket Alert Engine)]
 * WebSocketConfig: Cấu hình STOMP WebSocket Broker phục vụ đẩy cảnh báo ngân sách
 * và cập nhật giao diện Live UI theo thời gian thực (Real-time In-place UI Updates)
 *
 * Điểm chờ nâng cấp (Extension Point):
 * - Tích hợp External Message Broker (RabbitMQ Web STOMP Relay) khi scale đa node
 * ==============================================================================
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Kích hoạt simple in-memory broker cho các kênh /topic (Broadcast) và /queue (User-specific)
        config.enableSimpleBroker("/topic", "/queue");
        // Tiền tố cho các message gửi từ Client lên Server
        config.setApplicationDestinationPrefixes("/app");
        // Tiền tố cho các thông báo nhắm cụ thể vào từng User
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Đăng ký STOMP endpoint cho web socket client kết nối
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }
}
