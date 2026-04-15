package com.jpmc.cibap.orchestration.memory;

import com.jpmc.cibap.orchestration.model.Conversation;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationMemoryStore implements ChatMemoryStore {

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String id = memoryId.toString();
        Conversation conv = mongoTemplate
                .findOne(Query.query(Criteria.where("_id").is(id)), Conversation.class)
                .block();
        if (conv == null) {
            return List.of();
        }
        return conv.getMessages().stream()
                .map(this::toChatMessage)
                .toList();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String id = memoryId.toString();
        List<Conversation.MessageEntry> entries = messages.stream()
                .map(this::toEntry)
                .toList();
        mongoTemplate.upsert(
                Query.query(Criteria.where("_id").is(id)),
                new Update()
                        .set("messages", entries)
                        .set("updatedAt", Instant.now()),
                Conversation.class
        ).subscribe();
    }

    @Override
    public void deleteMessages(Object memoryId) {
        mongoTemplate.remove(
                Query.query(Criteria.where("_id").is(memoryId.toString())),
                Conversation.class
        ).subscribe();
    }

    private ChatMessage toChatMessage(Conversation.MessageEntry entry) {
        return "user".equals(entry.getRole())
                ? UserMessage.from(entry.getContent())
                : AiMessage.from(entry.getContent());
    }

    private Conversation.MessageEntry toEntry(ChatMessage msg) {
        return Conversation.MessageEntry.builder()
                .role(msg instanceof UserMessage ? "user" : "assistant")
                .content(msg.text())
                .timestamp(Instant.now())
                .build();
    }
}
