CREATE TABLE notification_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_uuid   VARCHAR(255) NOT NULL UNIQUE,
    customer_id    UUID NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    channel        VARCHAR(20) NOT NULL,
    recipient      VARCHAR(255) NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'SENT',
    provider_ref   VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notif_log_customer_id  ON notification_log(customer_id);
CREATE INDEX idx_notif_log_event_type   ON notification_log(event_type);
CREATE INDEX idx_notif_log_message_uuid ON notification_log(message_uuid);
