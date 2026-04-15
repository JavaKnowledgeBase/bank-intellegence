package com.jpmc.cibap.customer.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer and consumer configuration for the Customer Agent Service.
 *
 * <h2>Topics used by this service</h2>
 * <ul>
 *   <li><strong>Producer:</strong> {@code customer-support-events} — support requests</li>
 *   <li><strong>Consumer:</strong> {@code account-update-events} — account balance changes
 *       that trigger Redis cache eviction</li>
 * </ul>
 *
 * <h2>Reliability settings</h2>
 * <ul>
 *   <li>{@code acks=all} — leader AND all in-sync replicas must acknowledge each produce.
 *       This prevents message loss if the leader crashes immediately after write.</li>
 *   <li>{@code retries=3} — producer retries transient network failures automatically.</li>
 *   <li>{@code enable.idempotence=true} — exactly-once producer semantics; prevents
 *       duplicate messages on retry.</li>
 *   <li>{@code enable.auto.commit=false} — manual offset commit; ensures we only advance
 *       the consumer offset after the eviction has been applied to Redis.</li>
 * </ul>
 *
 * <h2>Error handling</h2>
 * <p>The {@link DefaultErrorHandler} with {@link FixedBackOff} retries failed consumer
 * records up to 3 times before sending to a dead-letter topic. This prevents a single
 * poison-pill message from blocking the entire partition.
 *
 * <p><strong>⚠ Runtime risks:</strong>
 * <ul>
 *   <li>If the Kafka bootstrap server is unreachable, the producer will block for
 *       {@code delivery.timeout.ms} (default 120 s) before failing. The Retry pattern
 *       in {@link com.jpmc.cibap.customer.kafka.SupportRequestProducer} bounds this.</li>
 *   <li>Dead-letter topic ({@code account-update-events.DLT}) must be created in advance;
 *       Kafka does not auto-create it in production environments with
 *       {@code auto.create.topics.enable=false}.</li>
 * </ul>
 *
 * @author Ravi Kafley
 * @since 1.0.0
 */
@Slf4j
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Producer factory configured for idempotent, at-least-once delivery to Kafka.
     *
     * @return a {@link ProducerFactory} producing {@code String → String} records
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // acks=all: leader + all ISR must acknowledge — prevents data loss on leader failure
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        // Retry transient network errors up to 3 times before failing the send
        configs.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Idempotence prevents duplicate messages on retry (requires acks=all, retries>0)
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 1);
        log.info("Kafka producer factory configured for brokers: {}", bootstrapServers);
        return new DefaultKafkaProducerFactory<>(configs);
    }

    /**
     * {@link KafkaTemplate} backed by the idempotent producer factory.
     * Injected into {@link com.jpmc.cibap.customer.kafka.SupportRequestProducer}.
     *
     * @return the configured {@link KafkaTemplate}
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Consumer factory for the {@code account-update-events} topic.
     *
     * <p>Auto-commit is disabled so that the service only advances the offset after the
     * corresponding Redis eviction has completed successfully.
     *
     * @return a {@link ConsumerFactory} consuming {@code String → String} records
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "customer-agent-cache-invalidation");
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // Manual commit: only advance offset after Redis eviction succeeds
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Start from earliest offset on first join — ensures no cache events are missed
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    /**
     * Listener container factory with error handling and manual acknowledgement.
     *
     * <p>The {@link DefaultErrorHandler} retries each failed record up to 3 times with a
     * 1-second fixed back-off. After 3 failures the record is sent to the DLT
     * ({@code account-update-events.DLT}) and the partition continues.
     *
     * <p><strong>Why not infinite retry?</strong> Poison-pill messages (e.g., malformed
     * JSON) would block partition progress forever without a retry limit + DLT.
     *
     * @return a configured {@link ConcurrentKafkaListenerContainerFactory}
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        // Manual acknowledgement mode — offset committed only on explicit ack
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Retry up to 3 times with 1s back-off; then route to DLT
        factory.setCommonErrorHandler(
                new DefaultErrorHandler(new FixedBackOff(1000L, 3)));
        log.info("Kafka listener container factory configured with manual ack and 3-retry error handler");
        return factory;
    }
}
