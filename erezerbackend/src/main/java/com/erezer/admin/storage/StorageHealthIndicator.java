package com.erezer.admin.storage;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("storage")
class StorageHealthIndicator implements HealthIndicator {

    private final StorageService storage;

    StorageHealthIndicator(StorageService storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        return storage.isAvailable() ? Health.up().build() : Health.down().build();
    }
}
