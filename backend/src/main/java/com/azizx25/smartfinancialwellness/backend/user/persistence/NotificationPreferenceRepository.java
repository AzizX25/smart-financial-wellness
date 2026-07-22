package com.azizx25.smartfinancialwellness.backend.user.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    List<NotificationPreference> findAllByUserIdOrderByEventTypeAscChannelAsc(UUID userId);
}
