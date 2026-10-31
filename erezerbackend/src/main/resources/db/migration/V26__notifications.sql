-- What happened, kept as text so it stays readable after the record itself changes or is deleted.
CREATE TABLE notifications
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action     VARCHAR(50)  NOT NULL,
    actor_id   BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_name VARCHAR(120) NOT NULL,
    actor_role VARCHAR(20)  NOT NULL,
    title      VARCHAR(250) NOT NULL,
    body       VARCHAR(600),
    link       VARCHAR(200),
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_notifications_created ON notifications (created_at DESC, id DESC);

-- Who a notification went to, and whether they have read it.
CREATE TABLE notification_recipients
(
    notification_id BIGINT NOT NULL REFERENCES notifications (id) ON DELETE CASCADE,
    user_id         BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    read_at         TIMESTAMPTZ,
    PRIMARY KEY (notification_id, user_id)
);

CREATE INDEX ix_notification_recipients_user ON notification_recipients (user_id, notification_id DESC);

-- How each action behaves. An action with no row uses the defaults in NotificationAction.
CREATE TABLE notification_rules
(
    action         VARCHAR(50) PRIMARY KEY,
    enabled        BOOLEAN     NOT NULL,
    push           BOOLEAN     NOT NULL,
    from_moderator BOOLEAN     NOT NULL,
    from_employee  BOOLEAN     NOT NULL,
    from_admin     BOOLEAN     NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL
);

-- Who gets each action. With no row, admins get it and nobody else does.
CREATE TABLE notification_subscriptions
(
    user_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    action   VARCHAR(50) NOT NULL,
    can_see  BOOLEAN     NOT NULL,
    can_push BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, action)
);

-- The phones and browsers that accepted push notifications.
CREATE TABLE notification_devices
(
    token      VARCHAR(512) PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_notification_devices_user ON notification_devices (user_id);

-- The Firebase connection, entered in the panel so it is never in the repository. The private key is encrypted.
CREATE TABLE notification_settings
(
    id                    INTEGER PRIMARY KEY CHECK (id = 1),
    project_id            VARCHAR(120) NOT NULL,
    client_email          VARCHAR(200) NOT NULL,
    private_key_encrypted TEXT         NOT NULL,
    web_config            TEXT         NOT NULL,
    vapid_key             VARCHAR(200) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);
