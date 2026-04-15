package com.jpmc.cibap.orchestration.model;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Data
@Document(collection = "conversations")
public class Conversation {

    @Id
    private String id; // == memoryId (customerId or sessionId)
    private String customerId;
    private List<MessageEntry> messages;
    private Instant updatedAt;


    @Data
    @Builder
    public static class MessageEntry {
        private String role; // "user" | "assistant"
        private String content;
        private Instant timestamp;
    }
}
