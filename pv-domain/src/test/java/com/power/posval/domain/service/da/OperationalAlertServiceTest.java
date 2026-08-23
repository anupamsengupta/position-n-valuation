package com.power.posval.domain.service.da;

import com.power.posval.domain.event.OperationalAlertRaised;
import com.power.posval.domain.model.AlertCategory;
import com.power.posval.domain.model.AlertSeverity;
import com.power.posval.domain.model.AlertStatus;
import com.power.posval.domain.model.OperationalAlert;
import com.power.posval.domain.port.event.DomainEventPublisher;
import com.power.posval.domain.port.repository.OperationalAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultOperationalAlertService}.
 * Pattern #18, DA-OPS-01, S8.5.
 */
class OperationalAlertServiceTest {

    private final List<OperationalAlert> savedAlerts = new ArrayList<>();
    private final List<Object> publishedEvents       = new ArrayList<>();

    private String acknowledgedId;
    private String acknowledgedBy;
    private String resolvedId;

    private DefaultOperationalAlertService service;

    @BeforeEach
    void setUp() {
        savedAlerts.clear();
        publishedEvents.clear();
        acknowledgedId = null;
        acknowledgedBy = null;
        resolvedId     = null;

        OperationalAlertRepository stubRepo = new OperationalAlertRepository() {
            @Override public void save(OperationalAlert a) { savedAlerts.add(a); }

            @Override public List<OperationalAlert> findOpen(String tenantId) {
                return savedAlerts.stream()
                    .filter(a -> a.status() == AlertStatus.OPEN)
                    .toList();
            }

            @Override public List<OperationalAlert> findByCategory(String tenantId,
                    AlertCategory category, AlertStatus status) {
                return savedAlerts.stream()
                    .filter(a -> a.category() == category && a.status() == status)
                    .toList();
            }

            @Override public List<OperationalAlert> findByDeliveryDay(String tenantId,
                    LocalDate deliveryDay) {
                return savedAlerts.stream()
                    .filter(a -> deliveryDay.equals(a.deliveryDay()))
                    .toList();
            }

            @Override public void acknowledge(String tenantId, UUID alertId,
                    String user, Instant at) {
                acknowledgedId = alertId.toString();
                acknowledgedBy = user;
            }

            @Override public void resolve(String tenantId, UUID alertId, Instant at) {
                resolvedId = alertId.toString();
            }

            @Override public Map<AlertCategory, Long> countByStatus(String tenantId,
                    AlertStatus status) {
                return Map.of();
            }
        };

        DomainEventPublisher stubPublisher = publishedEvents::add;
        service = new DefaultOperationalAlertService(stubRepo, stubPublisher);
    }

    // ---------------------------------------------------------------------------
    // raise()
    // ---------------------------------------------------------------------------

    @Test
    void raise_persistsAlertAndPublishesEvent() {
        OperationalAlert alert = openAlert(AlertSeverity.WARNING, AlertCategory.AUCTION_INGESTION);
        service.raise(alert);

        assertEquals(1, savedAlerts.size());
        assertSame(alert, savedAlerts.get(0));

        assertEquals(1, publishedEvents.size());
        assertInstanceOf(OperationalAlertRaised.class, publishedEvents.get(0));
        OperationalAlertRaised event = (OperationalAlertRaised) publishedEvents.get(0);
        assertEquals(alert.tenantId(), event.tenantId());
        assertEquals(alert.alertId(), event.alertId());
        assertEquals(AlertCategory.AUCTION_INGESTION, event.category());
        assertEquals(AlertSeverity.WARNING, event.severity());
        assertEquals(alert.message(), event.message());
    }

    @Test
    void raise_criticalAlert_persistsAndPublishes() {
        OperationalAlert alert = openAlert(AlertSeverity.CRITICAL, AlertCategory.DA_CLEARING_PRICES);
        service.raise(alert);

        assertEquals(AlertSeverity.CRITICAL, savedAlerts.get(0).severity());
        assertFalse(publishedEvents.isEmpty());
    }

    @Test
    void raise_nullAlert_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.raise(null));
    }

    // ---------------------------------------------------------------------------
    // acknowledgeAlert()
    // ---------------------------------------------------------------------------

    @Test
    void acknowledgeAlert_delegatesToRepository() {
        UUID alertId = UUID.randomUUID();
        service.acknowledgeAlert("TN_0042", alertId, "operator@example.com");

        assertEquals(alertId.toString(), acknowledgedId);
        assertEquals("operator@example.com", acknowledgedBy);
    }

    @Test
    void acknowledgeAlert_nullTenant_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.acknowledgeAlert(null, UUID.randomUUID(), "user"));
    }

    @Test
    void acknowledgeAlert_nullAlertId_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.acknowledgeAlert("TN_0042", null, "user"));
    }

    @Test
    void acknowledgeAlert_nullUser_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.acknowledgeAlert("TN_0042", UUID.randomUUID(), null));
    }

    // ---------------------------------------------------------------------------
    // resolveAlert()
    // ---------------------------------------------------------------------------

    @Test
    void resolveAlert_delegatesToRepository() {
        UUID alertId = UUID.randomUUID();
        service.resolveAlert("TN_0042", alertId);

        assertEquals(alertId.toString(), resolvedId);
    }

    @Test
    void resolveAlert_nullTenant_throwsNpe() {
        assertThrows(NullPointerException.class,
            () -> service.resolveAlert(null, UUID.randomUUID()));
    }

    // ---------------------------------------------------------------------------
    // getOpenAlerts()
    // ---------------------------------------------------------------------------

    @Test
    void getOpenAlerts_returnsOnlyOpenAlerts() {
        service.raise(openAlert(AlertSeverity.INFO, AlertCategory.EXCHANGE_FEES));
        service.raise(openAlert(AlertSeverity.WARNING, AlertCategory.SETTLEMENT));

        List<OperationalAlert> open = service.getOpenAlerts("TN_0042");
        assertEquals(2, open.size());
        assertTrue(open.stream().allMatch(a -> a.status() == AlertStatus.OPEN));
    }

    @Test
    void getOpenAlerts_nullTenant_throwsNpe() {
        assertThrows(NullPointerException.class, () -> service.getOpenAlerts(null));
    }

    // ---------------------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------------------

    private static OperationalAlert openAlert(AlertSeverity severity, AlertCategory category) {
        return OperationalAlert.builder()
            .alertId(UUID.randomUUID())
            .tenantId("TN_0042")
            .category(category)
            .severity(severity)
            .alertType("TEST_ALERT")
            .message("Test alert message for T-7788")
            .deliveryDay(LocalDate.of(2026, 9, 16))
            .biddingZone("DE_LU")
            .sourceEventId("session-1")
            .raisedAt(Instant.now())
            .status(AlertStatus.OPEN)
            .build();
    }
}
