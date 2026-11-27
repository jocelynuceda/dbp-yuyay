package com.yuyay.medication.service;

import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.repository.CareAuthorizationDeclarationRepository;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.exception.InvalidOperationException;
import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.health.exception.HealthEntryNotFoundException;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.exception.ResourceNotFoundException;
import com.yuyay.medication.dto.AgendaDoseResponse;
import com.yuyay.medication.dto.DoseMarkRequest;
import com.yuyay.medication.dto.MedicationScheduleRequest;
import com.yuyay.medication.dto.MedicationScheduleResponse;
import com.yuyay.medication.entity.DoseLog;
import com.yuyay.medication.entity.DoseStatus;
import com.yuyay.medication.entity.MedicationSchedule;
import com.yuyay.medication.repository.DoseLogRepository;
import com.yuyay.medication.repository.MedicationScheduleRepository;
import com.yuyay.security.AuthorizationService;
import com.yuyay.security.CurrentUserService;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Horario de los medicamentos y registro de tomas. Cualquier cuidador con acceso a la persona puede
 * ver, cambiar el horario y marcar tomas: es una tarea compartida, como anotar un registro.
 *
 * <p>Yuyay no propone dosis ni horarios: guarda los que escribe la persona a partir de su receta.
 */
@Service
public class MedicationScheduleService {
    /** Rango máximo de la agenda: un mes visible con sus semanas de borde. */
    static final int MAX_AGENDA_DAYS = 42;
    /** Una toma se puede marcar desde este tiempo antes de su hora. */
    static final Duration MARK_BEFORE = Duration.ofHours(2);
    /** …y como tomada a tiempo hasta este tiempo después. Luego, sin marcar, cuenta como no tomada. */
    static final Duration MARK_AFTER = Duration.ofMinutes(30);

    private final MedicationScheduleRepository schedules;
    private final DoseLogRepository doseLogs;
    private final HealthEntryRepository entries;
    private final HealthEntryVersionRepository versions;
    private final CareRelationshipRepository relationships;
    private final CareAuthorizationDeclarationRepository declarations;
    private final UserRepository users;
    private final AuthorizationService authorization;
    private final CurrentUserService currentUser;
    private final Clock clock;
    private final ZoneId zone;

    public MedicationScheduleService(MedicationScheduleRepository schedules, DoseLogRepository doseLogs,
                                     HealthEntryRepository entries, HealthEntryVersionRepository versions,
                                     CareRelationshipRepository relationships,
                                     CareAuthorizationDeclarationRepository declarations, UserRepository users,
                                     AuthorizationService authorization, CurrentUserService currentUser, Clock clock,
                                     @Value("${app.reminders.zone:America/Lima}") ZoneId zone) {
        this.schedules = schedules;
        this.doseLogs = doseLogs;
        this.entries = entries;
        this.versions = versions;
        this.relationships = relationships;
        this.declarations = declarations;
        this.users = users;
        this.authorization = authorization;
        this.currentUser = currentUser;
        this.clock = clock;
        this.zone = zone;
    }

    @Transactional(readOnly = true)
    public List<MedicationScheduleResponse> listForSubject(Long subjectId) {
        authorization.requireAccess(currentUser.getCurrentUserId(), subjectId);
        return schedules.findActiveBySubject(subjectId).stream().map(MedicationScheduleService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public MedicationScheduleResponse get(Long subjectId, Long entryId) {
        authorization.requireAccess(currentUser.getCurrentUserId(), subjectId);
        loadMedication(subjectId, entryId);
        return schedules.findByHealthEntryId(entryId).map(MedicationScheduleService::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Este medicamento no tiene horario"));
    }

    /** Crea o reemplaza el horario. Las tomas ya marcadas se conservan. */
    @Transactional
    public MedicationScheduleResponse put(Long subjectId, Long entryId, MedicationScheduleRequest request) {
        Long meId = currentUser.getCurrentUserId();
        authorization.requireAccess(meId, subjectId);
        HealthEntry entry = loadMedication(subjectId, entryId);
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new InvalidOperationException("La fecha de fin no puede ser anterior a la de inicio");
        }
        List<LocalTime> times = new ArrayList<>(new TreeSet<>(request.times().stream()
                .map(time -> time.truncatedTo(ChronoUnit.MINUTES)).toList()));

        MedicationSchedule schedule = schedules.findByHealthEntryId(entryId).orElseGet(() -> MedicationSchedule.builder()
                .healthEntry(entry)
                .careSubject(entry.getCareSubject())
                .build());
        schedule.getTimes().clear();
        schedule.getTimes().addAll(times);
        schedule.getDaysOfWeek().clear();
        if (request.daysOfWeek() != null && request.daysOfWeek().size() < 7) {
            schedule.getDaysOfWeek().addAll(request.daysOfWeek());
        }
        schedule.setStartDate(request.startDate());
        schedule.setEndDate(request.endDate());
        schedule.setUpdatedBy(loadUser(meId));
        schedule.setUpdatedAt(clock.instant());
        return toResponse(schedules.save(schedule));
    }

    @Transactional
    public void delete(Long subjectId, Long entryId) {
        authorization.requireAccess(currentUser.getCurrentUserId(), subjectId);
        loadMedication(subjectId, entryId);
        schedules.findByHealthEntryId(entryId).ifPresent(schedule -> {
            doseLogs.deleteByScheduleId(schedule.getId());
            schedules.delete(schedule);
        });
    }

    /** Todas las tomas de las personas que cuido entre dos fechas, en orden de día y hora. */
    @Transactional(readOnly = true)
    public List<AgendaDoseResponse> agenda(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) throw new InvalidOperationException("El rango de fechas está invertido");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_AGENDA_DAYS) {
            throw new InvalidOperationException("La agenda abarca como máximo " + MAX_AGENDA_DAYS + " días");
        }
        Long meId = currentUser.getCurrentUserId();
        List<Long> subjectIds = relationships.findByUserIdAndStatus(meId, CareStatus.ACTIVE).stream()
                .map(CareRelationship::getCareSubject).map(subject -> subject.getId()).toList();
        if (subjectIds.isEmpty()) return List.of();

        List<MedicationSchedule> found = schedules.findInRange(subjectIds, from, to);
        if (found.isEmpty()) return List.of();
        Map<Long, HealthEntryVersion> latest = versions.findLatestByHealthEntryIdIn(
                        found.stream().map(s -> s.getHealthEntry().getId()).toList()).stream()
                .collect(Collectors.toMap(v -> v.getHealthEntry().getId(), Function.identity()));
        Map<String, DoseLog> marked = doseLogs.findInRange(found.stream().map(MedicationSchedule::getId).toList(), from, to)
                .stream().collect(Collectors.toMap(l -> key(l.getSchedule().getId(), l.getDate(), l.getTime()), Function.identity()));

        Map<Long, Boolean> mine = found.stream().map(MedicationSchedule::getCareSubject).distinct()
                .collect(Collectors.toMap(CareSubject::getId, subject -> isOwnProfile(subject, meId), (a, b) -> a));

        List<AgendaDoseResponse> doses = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (MedicationSchedule schedule : found) {
                if (!schedule.appliesOn(day)) continue;
                HealthEntryVersion version = latest.get(schedule.getHealthEntry().getId());
                for (LocalTime time : schedule.getTimes()) {
                    DoseLog log = marked.get(key(schedule.getId(), day, time));
                    doses.add(new AgendaDoseResponse(
                            schedule.getId(),
                            schedule.getHealthEntry().getId(),
                            schedule.getCareSubject().getId(),
                            schedule.getCareSubject().getName(),
                            mine.get(schedule.getCareSubject().getId()),
                            version == null ? "" : version.getTitle(),
                            version == null ? null : version.getDose(),
                            day,
                            time,
                            log == null ? null : log.getStatus(),
                            log == null ? null : log.getMarkedBy().getName(),
                            log == null ? null : log.getMarkedAt()));
                }
            }
        }
        doses.sort(Comparator.comparing(AgendaDoseResponse::date).thenComparing(AgendaDoseResponse::time)
                .thenComparing(AgendaDoseResponse::careSubjectName).thenComparing(AgendaDoseResponse::medication));
        return doses;
    }

    /**
     * Marca una toma como tomada o no tomada. Si ya estaba marcada, se reemplaza (y queda quién la cambió).
     * Ver {@link #checkMarkWindow}: a tiempo desde 2 h antes hasta 30 min después; luego solo «tomada tarde».
     */
    @Transactional
    public AgendaDoseResponse mark(Long scheduleId, DoseMarkRequest request) {
        Long meId = currentUser.getCurrentUserId();
        MedicationSchedule schedule = loadSchedule(meId, scheduleId);
        requireScheduledDose(schedule, request.date(), request.time());
        checkMarkWindow(request.date().atTime(request.time()), LocalDateTime.now(clock.withZone(zone)), request.status());
        LocalTime time = request.time().truncatedTo(ChronoUnit.MINUTES);
        User me = loadUser(meId);
        DoseLog log = doseLogs.findByScheduleIdAndDateAndTime(scheduleId, request.date(), time)
                .orElseGet(() -> DoseLog.builder().schedule(schedule).date(request.date()).time(time).build());
        log.setStatus(request.status());
        log.setMarkedBy(me);
        log.setMarkedAt(clock.instant());
        doseLogs.save(log);
        HealthEntryVersion version = versions.findLatestByHealthEntryIdIn(List.of(schedule.getHealthEntry().getId()))
                .stream().findFirst().orElse(null);
        return new AgendaDoseResponse(schedule.getId(), schedule.getHealthEntry().getId(),
                schedule.getCareSubject().getId(), schedule.getCareSubject().getName(),
                isOwnProfile(schedule.getCareSubject(), meId),
                version == null ? "" : version.getTitle(), version == null ? null : version.getDose(),
                log.getDate(), log.getTime(), log.getStatus(), me.getName(), log.getMarkedAt());
    }

    /**
     * Cuándo se puede marcar una toma: nunca antes de 2 h de su hora (ni las de días siguientes); como
     * tomada o no tomada hasta 30 min después; y pasado ese plazo, solo como tomada tarde.
     */
    static void checkMarkWindow(LocalDateTime doseAt, LocalDateTime now, DoseStatus status) {
        if (doseAt.isAfter(now.plus(MARK_BEFORE))) {
            throw new InvalidOperationException("Todavía no es hora de esta toma: se puede marcar desde 2 horas antes.");
        }
        boolean late = now.isAfter(doseAt.plus(MARK_AFTER));
        if (late && status != DoseStatus.LATE) {
            throw new InvalidOperationException("Pasaron más de 30 minutos: márcala como «se tomó tarde».");
        }
        if (!late && status == DoseStatus.LATE) {
            throw new InvalidOperationException("Todavía está a tiempo: márcala como tomada.");
        }
    }

    /** Deshace la marca de una toma (por ejemplo, si se marcó por error). */
    @Transactional
    public void unmark(Long scheduleId, LocalDate date, LocalTime time) {
        Long meId = currentUser.getCurrentUserId();
        loadSchedule(meId, scheduleId);
        doseLogs.findByScheduleIdAndDateAndTime(scheduleId, date, time.truncatedTo(ChronoUnit.MINUTES))
                .ifPresent(doseLogs::delete);
    }

    /**
     * ¿El perfil es de quien consulta? Lo es si se le entregó (titular de la cuenta) o, mientras no tenga
     * titular, si quien lo creó declaró «Este perfil es para mí».
     */
    private boolean isOwnProfile(CareSubject subject, Long userId) {
        if (subject.getAccountUser() != null) return Objects.equals(subject.getAccountUser().getId(), userId);
        return declarations.findFirstByCareSubjectIdOrderByIdDesc(subject.getId())
                .filter(declaration -> declaration.getType() == CareAuthorizationType.SELF)
                .filter(declaration -> Objects.equals(declaration.getDeclaredBy().getId(), userId))
                .isPresent();
    }

    private MedicationSchedule loadSchedule(Long meId, Long scheduleId) {
        MedicationSchedule schedule = schedules.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Horario no encontrado"));
        authorization.requireAccess(meId, schedule.getCareSubject().getId());
        if (schedule.getHealthEntry().isDeleted()) throw new ResourceNotFoundException("Horario no encontrado");
        return schedule;
    }

    private static void requireScheduledDose(MedicationSchedule schedule, LocalDate date, LocalTime time) {
        LocalTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        if (!schedule.appliesOn(date) || !schedule.getTimes().contains(minute)) {
            throw new InvalidOperationException("Esa toma no está en el horario");
        }
    }

    private HealthEntry loadMedication(Long subjectId, Long entryId) {
        HealthEntry entry = entries.findById(entryId)
                .filter(e -> Objects.equals(e.getCareSubject().getId(), subjectId) && !e.isDeleted())
                .orElseThrow(() -> new HealthEntryNotFoundException(entryId));
        if (!HealthCategory.MEDICATION.equals(entry.getCategory().getCode())) {
            throw new InvalidOperationException("Solo los medicamentos tienen horario");
        }
        return entry;
    }

    private User loadUser(Long id) {
        return users.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
    }

    private static String key(Long scheduleId, LocalDate date, LocalTime time) {
        return scheduleId + "|" + date + "|" + time;
    }

    static MedicationScheduleResponse toResponse(MedicationSchedule schedule) {
        List<DayOfWeek> days = new ArrayList<>(new HashSet<>(schedule.getDaysOfWeek()));
        days.sort(Comparator.naturalOrder());
        return new MedicationScheduleResponse(schedule.getId(), schedule.getHealthEntry().getId(),
                schedule.getCareSubject().getId(), List.copyOf(schedule.getTimes()), days,
                schedule.getStartDate(), schedule.getEndDate(), schedule.getUpdatedAt());
    }
}
