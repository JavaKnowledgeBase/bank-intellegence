package com.jpmc.cibap.notification.repository;

import com.jpmc.cibap.notification.model.NotificationLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface NotificationLogRepository extends ReactiveCrudRepository<NotificationLog, UUID> {
}
