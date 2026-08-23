package com.power.posval.app.seed;

import com.power.posval.domain.model.BlockDefinition;
import com.power.posval.domain.model.BlockType;
import com.power.posval.domain.model.HolidayCalendar;
import com.power.posval.persistence.entity.BlockDefinitionEntity;
import com.power.posval.persistence.entity.HolidayCalendarEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Seeds industry-standard EPEX Spot block definitions and EU public holiday
 * calendars for DA auction import and block order decomposition.
 *
 * <p>Block definitions (EPEX Spot standard products, valid for DE_LU, FR, AT, NL, BE):
 * <ul>
 *   <li><b>BASELOAD</b> — 00:00–00:00 CET (24h), all days</li>
 *   <li><b>PEAK</b> — 08:00–20:00 CET, Monday–Friday excluding public holidays</li>
 *   <li><b>OFF_PEAK</b> — 20:00–08:00 CET (wrap midnight), all days</li>
 * </ul>
 *
 * <p>Holiday calendars cover 2026 and 2027 for DE (Germany), FR (France),
 * AT (Austria), NL (Netherlands), and BE (Belgium).
 *
 * <p>All reference data is system-level (tenant-independent), effective from 2020-01-01
 * with no expiry. Uses EMF directly since repository ports are read-only for reference data.
 *
 * <p>Simulator-scope ({@code pv-app} only). D-14.
 */
@Component
@Order(1)
public class DaReferenceDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DaReferenceDataSeeder.class);

    private static final String EXCHANGE = "EPEX_SPOT";

    private final EntityManagerFactory emf;
    private final boolean seedEnabled;

    public DaReferenceDataSeeder(EntityManagerFactory emf,
                                  @Value("${pv.seed.enabled:true}") boolean seedEnabled) {
        this.emf = emf;
        this.seedEnabled = seedEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("DA reference data seeding disabled");
            return;
        }

        EntityManager em = emf.createEntityManager();
        try {
            // Check if already seeded
            long blockCount = em.createQuery(
                "SELECT COUNT(e) FROM BlockDefinitionEntity e WHERE e.exchange = :exchange",
                Long.class)
                .setParameter("exchange", EXCHANGE)
                .getSingleResult();

            if (blockCount > 0) {
                log.info("EPEX block definitions already seeded ({} found), skipping", blockCount);
            } else {
                em.getTransaction().begin();
                seedBlockDefinitions(em);
                em.getTransaction().commit();
            }

            long holidayCount = em.createQuery(
                "SELECT COUNT(e) FROM HolidayCalendarEntity e", Long.class)
                .getSingleResult();

            if (holidayCount > 0) {
                log.info("Holiday calendars already seeded ({} entries), skipping", holidayCount);
            } else {
                em.getTransaction().begin();
                seedHolidayCalendars(em);
                em.getTransaction().commit();
            }
        } finally {
            em.close();
        }
    }

    private void seedBlockDefinitions(EntityManager em) {
        log.info("Seeding EPEX Spot standard block definitions...");
        LocalDate effectiveFrom = LocalDate.of(2020, 1, 1);

        // BASELOAD: 00:00–00:00 CET (full 24h), all days
        em.persist(BlockDefinitionEntity.fromDomain(BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange(EXCHANGE)
            .blockType(BlockType.BASELOAD)
            .startHour(LocalTime.of(0, 0))
            .endHour(LocalTime.of(0, 0))
            .applicableDays("MON-SUN")
            .effectiveFrom(effectiveFrom)
            .build()));

        // PEAK: 08:00–20:00 CET, Monday–Friday excluding holidays
        em.persist(BlockDefinitionEntity.fromDomain(BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange(EXCHANGE)
            .blockType(BlockType.PEAK)
            .startHour(LocalTime.of(8, 0))
            .endHour(LocalTime.of(20, 0))
            .applicableDays("MON-FRI")
            .holidayCalendarRef("DE")
            .effectiveFrom(effectiveFrom)
            .build()));

        // OFF_PEAK: 20:00–08:00 (wraps midnight), all days
        em.persist(BlockDefinitionEntity.fromDomain(BlockDefinition.builder()
            .blockId(UUID.randomUUID())
            .exchange(EXCHANGE)
            .blockType(BlockType.OFF_PEAK)
            .startHour(LocalTime.of(20, 0))
            .endHour(LocalTime.of(8, 0))
            .applicableDays("MON-SUN")
            .holidayCalendarRef("DE")
            .effectiveFrom(effectiveFrom)
            .build()));

        log.info("Seeded 3 EPEX Spot block definitions (BASELOAD, PEAK, OFF_PEAK)");
    }

    private void seedHolidayCalendars(EntityManager em) {
        log.info("Seeding EU public holiday calendars for 2026 and 2027...");
        int count = 0;

        // --- Germany (DE) ---
        count += seedDeHolidays(em, 2026);
        count += seedDeHolidays(em, 2027);

        // --- France (FR) ---
        count += seedFrHolidays(em, 2026);
        count += seedFrHolidays(em, 2027);

        // --- Austria (AT) ---
        count += seedAtHolidays(em, 2026);
        count += seedAtHolidays(em, 2027);

        // --- Netherlands (NL) ---
        count += seedNlHolidays(em, 2026);
        count += seedNlHolidays(em, 2027);

        // --- Belgium (BE) ---
        count += seedBeHolidays(em, 2026);
        count += seedBeHolidays(em, 2027);

        log.info("Seeded {} holiday calendar entries across DE, FR, AT, NL, BE for 2026-2027", count);
    }

    // -----------------------------------------------------------------------
    // Germany (DE) — nationwide public holidays
    // -----------------------------------------------------------------------
    private int seedDeHolidays(EntityManager em, int year) {
        int count = 0;
        count += h(em, "DE", year, 1, 1,   "Neujahrstag");
        count += h(em, "DE", year, 5, 1,   "Tag der Arbeit");
        count += h(em, "DE", year, 10, 3,  "Tag der Deutschen Einheit");
        count += h(em, "DE", year, 12, 25, "1. Weihnachtstag");
        count += h(em, "DE", year, 12, 26, "2. Weihnachtstag");

        // Easter-based movable holidays
        LocalDate easter = computeEaster(year);
        count += h(em, "DE", easter.minusDays(2),  "Karfreitag");
        count += h(em, "DE", easter.plusDays(1),    "Ostermontag");
        count += h(em, "DE", easter.plusDays(39),   "Christi Himmelfahrt");
        count += h(em, "DE", easter.plusDays(50),   "Pfingstmontag");
        return count;
    }

    // -----------------------------------------------------------------------
    // France (FR) — nationwide public holidays
    // -----------------------------------------------------------------------
    private int seedFrHolidays(EntityManager em, int year) {
        int count = 0;
        count += h(em, "FR", year, 1, 1,   "Jour de l'An");
        count += h(em, "FR", year, 5, 1,   "Fête du Travail");
        count += h(em, "FR", year, 5, 8,   "Victoire 1945");
        count += h(em, "FR", year, 7, 14,  "Fête nationale");
        count += h(em, "FR", year, 8, 15,  "Assomption");
        count += h(em, "FR", year, 11, 1,  "Toussaint");
        count += h(em, "FR", year, 11, 11, "Armistice");
        count += h(em, "FR", year, 12, 25, "Noël");

        LocalDate easter = computeEaster(year);
        count += h(em, "FR", easter.plusDays(1),    "Lundi de Pâques");
        count += h(em, "FR", easter.plusDays(39),   "Ascension");
        count += h(em, "FR", easter.plusDays(50),   "Lundi de Pentecôte");
        return count;
    }

    // -----------------------------------------------------------------------
    // Austria (AT) — nationwide public holidays
    // -----------------------------------------------------------------------
    private int seedAtHolidays(EntityManager em, int year) {
        int count = 0;
        count += h(em, "AT", year, 1, 1,   "Neujahr");
        count += h(em, "AT", year, 1, 6,   "Heilige Drei Könige");
        count += h(em, "AT", year, 5, 1,   "Staatsfeiertag");
        count += h(em, "AT", year, 8, 15,  "Mariä Himmelfahrt");
        count += h(em, "AT", year, 10, 26, "Nationalfeiertag");
        count += h(em, "AT", year, 11, 1,  "Allerheiligen");
        count += h(em, "AT", year, 12, 8,  "Mariä Empfängnis");
        count += h(em, "AT", year, 12, 25, "Christtag");
        count += h(em, "AT", year, 12, 26, "Stefanitag");

        LocalDate easter = computeEaster(year);
        count += h(em, "AT", easter.plusDays(1),    "Ostermontag");
        count += h(em, "AT", easter.plusDays(39),   "Christi Himmelfahrt");
        count += h(em, "AT", easter.plusDays(50),   "Pfingstmontag");
        count += h(em, "AT", easter.plusDays(60),   "Fronleichnam");
        return count;
    }

    // -----------------------------------------------------------------------
    // Netherlands (NL) — nationwide public holidays
    // -----------------------------------------------------------------------
    private int seedNlHolidays(EntityManager em, int year) {
        int count = 0;
        count += h(em, "NL", year, 1, 1,   "Nieuwjaarsdag");
        count += h(em, "NL", year, 4, 27,  "Koningsdag");
        count += h(em, "NL", year, 5, 5,   "Bevrijdingsdag");
        count += h(em, "NL", year, 12, 25, "Eerste Kerstdag");
        count += h(em, "NL", year, 12, 26, "Tweede Kerstdag");

        LocalDate easter = computeEaster(year);
        count += h(em, "NL", easter.minusDays(2),  "Goede Vrijdag");
        count += h(em, "NL", easter.plusDays(1),    "Tweede Paasdag");
        count += h(em, "NL", easter.plusDays(39),   "Hemelvaartsdag");
        count += h(em, "NL", easter.plusDays(50),   "Tweede Pinksterdag");
        return count;
    }

    // -----------------------------------------------------------------------
    // Belgium (BE) — nationwide public holidays
    // -----------------------------------------------------------------------
    private int seedBeHolidays(EntityManager em, int year) {
        int count = 0;
        count += h(em, "BE", year, 1, 1,   "Nieuwjaar");
        count += h(em, "BE", year, 5, 1,   "Dag van de Arbeid");
        count += h(em, "BE", year, 7, 21,  "Nationale Feestdag");
        count += h(em, "BE", year, 8, 15,  "O.L.V. Hemelvaart");
        count += h(em, "BE", year, 11, 1,  "Allerheiligen");
        count += h(em, "BE", year, 11, 11, "Wapenstilstand");
        count += h(em, "BE", year, 12, 25, "Kerstmis");

        LocalDate easter = computeEaster(year);
        count += h(em, "BE", easter.plusDays(1),    "Paasmaandag");
        count += h(em, "BE", easter.plusDays(39),   "O.L.H. Hemelvaart");
        count += h(em, "BE", easter.plusDays(50),   "Pinkstermaandag");
        return count;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int h(EntityManager em, String zone, int year, int month, int day, String name) {
        return h(em, zone, LocalDate.of(year, month, day), name);
    }

    private int h(EntityManager em, String zone, LocalDate date, String name) {
        em.persist(HolidayCalendarEntity.fromDomain(
            new HolidayCalendar(UUID.randomUUID(), zone, date, name)));
        return 1;
    }

    /**
     * Compute Easter Sunday using the Anonymous Gregorian algorithm (Meeus/Jones/Butcher).
     * Valid for any year in the Gregorian calendar.
     */
    private static LocalDate computeEaster(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
