package com.yuyay.config;

import com.yuyay.care.entity.CareRelationship;
import com.yuyay.care.entity.CareRole;
import com.yuyay.care.entity.CareStatus;
import com.yuyay.care.entity.CareSubject;
import com.yuyay.care.entity.Delegation;
import com.yuyay.care.repository.CareRelationshipRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.consultation.entity.Consultation;
import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffItem;
import com.yuyay.consultation.repository.ConsultationRepository;
import com.yuyay.consultation.repository.HandoffRepository;
import com.yuyay.health.entity.ChangeType;
import com.yuyay.health.entity.ConfidenceLevel;
import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.entity.HealthEntry;
import com.yuyay.health.entity.HealthEntryVersion;
import com.yuyay.health.repository.HealthCategoryRepository;
import com.yuyay.health.repository.HealthEntryRepository;
import com.yuyay.health.repository.HealthEntryVersionRepository;
import com.yuyay.notification.entity.Notification;
import com.yuyay.notification.entity.NotificationType;
import com.yuyay.notification.repository.NotificationRepository;
import com.yuyay.security.TokenHasher;
import com.yuyay.user.entity.Role;
import com.yuyay.user.entity.User;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Carga datos de ejemplo para la demo en vivo. Solo corre con el perfil "demo".
 * Escribe directo en los repositorios (sin eventos) para no disparar correos ni
 * depender del usuario autenticado, pero deja el mismo estado que los flujos reales.
 */
@Component
@Profile("demo")
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder {

    private static final String ANA_EMAIL = "ana@demo.yuyay";
    private static final String BRUNO_EMAIL = "bruno@demo.yuyay";
    private static final String CARMEN_EMAIL = "carmen@demo.yuyay";

    private final UserRepository userRepo;
    private final CareSubjectRepository subjectRepo;
    private final CareRelationshipRepository relationshipRepo;
    private final HealthCategoryRepository categoryRepo;
    private final HealthEntryRepository entryRepo;
    private final HealthEntryVersionRepository versionRepo;
    private final ConsultationRepository consultationRepo;
    private final HandoffRepository handoffRepo;
    private final DelegationRepository delegationRepo;
    private final NotificationRepository notificationRepo;
    private final PasswordEncoder passwordEncoder;

    // Inyección por campo: no es final, así que Lombok no lo mete al constructor.
    @Value("${app.demo.password:}")
    private String demoPassword;

    private record Users(User ana, User bruno) {}

    private record Subjects(CareSubject rosa, CareSubject pedro, CareSubject lucia) {}

    /** Datos de una versión de un registro de salud (los mismos campos del request del servicio). */
    private record EntryData(String title, String details, String dose, String frequency,
                             LocalDate occurredOn, ConfidenceLevel confidence, String notes) {}

    /** Registro guardado junto con su versión vigente (la de mayor número). */
    private record SavedEntry(HealthEntry entry, HealthEntryVersion latest) {}

    // Corre después de los ApplicationRunner de DataSeeder (que crean las categorías).
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("DEMO_PASSWORD no definido: no se cargan los datos de demo");
            return;
        }
        if (userRepo.existsByEmailIgnoreCase(ANA_EMAIL)) {
            log.info("Los datos de demo ya existen: no se vuelven a cargar");
            return;
        }

        Users users = createUsers();
        Subjects subjects = createSubjects(users);
        createCaregiverRelationships(users, subjects);
        List<SavedEntry> rosaEntries = createRosaEntries(users, subjects.rosa());
        createPedroEntries(users, subjects.pedro());
        createLuciaEntries(users, subjects.lucia());
        createConsultations(users, subjects.rosa());
        createHandoff(users, subjects.rosa(), rosaEntries);
        createDelegation(users, subjects.rosa());
        createNotifications(users, subjects);

        log.info("Datos de demo cargados: ana@demo.yuyay y bruno@demo.yuyay");
    }

    private Users createUsers() {
        User ana = saveUser(ANA_EMAIL, "Ana Torres");
        User bruno = saveUser(BRUNO_EMAIL, "Bruno Torres");
        return new Users(ana, bruno);
    }

    private User saveUser(String email, String name) {
        // Igual que AuthService.register: email en minúsculas, hash con el PasswordEncoder, rol USER.
        return userRepo.save(User.builder()
                .email(email.toLowerCase())
                .passwordHash(passwordEncoder.encode(demoPassword))
                .name(name)
                .role(Role.USER)
                .build());
    }

    private Subjects createSubjects(Users users) {
        LocalDate today = LocalDate.now();
        CareSubject rosa = createSubject(users.ana(), "Rosa", today.minusYears(72).minusDays(40),
                "Mamá. Vive en Jesús María. Controles en la clínica cada 3 meses.");
        CareSubject pedro = createSubject(users.ana(), "Pedro", today.minusYears(75).minusDays(100),
                "Papá. Camina todas las mañanas. Usa lentes para leer.");
        CareSubject lucia = createSubject(users.ana(), "Lucía", today.minusYears(8).minusDays(60),
                "Hija. Tercer grado de primaria. Asma desde los 4 años.");
        return new Subjects(rosa, pedro, lucia);
    }

    // Mismo patrón que CareSubjectService.create: persona + relación PRINCIPAL ACTIVE del creador.
    private CareSubject createSubject(User owner, String name, LocalDate birthDate, String notes) {
        CareSubject subject = subjectRepo.save(CareSubject.builder()
                .name(name)
                .birthDate(birthDate)
                .notes(notes)
                .build());
        relationshipRepo.save(CareRelationship.builder()
                .user(owner)
                .careSubject(subject)
                .role(CareRole.PRINCIPAL)
                .status(CareStatus.ACTIVE)
                .acceptedAt(Instant.now())
                .build());
        return subject;
    }

    private void createCaregiverRelationships(Users users, Subjects subjects) {
        Instant now = Instant.now();
        // Invitaciones ya aceptadas (CareRelationshipService.invite + accept): ACTIVE con acceptedAt.
        relationshipRepo.save(CareRelationship.builder()
                .user(users.bruno())
                .careSubject(subjects.rosa())
                .role(CareRole.CAREGIVER)
                .relationshipLabel("Hijo")
                .status(CareStatus.ACTIVE)
                .invitedAt(now.minus(30, ChronoUnit.DAYS))
                .acceptedAt(now.minus(29, ChronoUnit.DAYS))
                .build());
        relationshipRepo.save(CareRelationship.builder()
                .user(users.bruno())
                .careSubject(subjects.pedro())
                .role(CareRole.CAREGIVER)
                .relationshipLabel("Hijo")
                .status(CareStatus.ACTIVE)
                .invitedAt(now.minus(30, ChronoUnit.DAYS))
                .acceptedAt(now.minus(29, ChronoUnit.DAYS))
                .build());
        // Invitación pendiente (CareRelationshipService.invite): sin acceptedAt.
        relationshipRepo.save(CareRelationship.builder()
                .user(users.bruno())
                .careSubject(subjects.lucia())
                .role(CareRole.CAREGIVER)
                .relationshipLabel("Tío")
                .status(CareStatus.PENDING)
                .invitedAt(now.minus(1, ChronoUnit.DAYS))
                .build());
    }

    private List<SavedEntry> createRosaEntries(Users users, CareSubject rosa) {
        LocalDate today = LocalDate.now();
        User ana = users.ana();
        User bruno = users.bruno();
        List<SavedEntry> saved = new ArrayList<>();

        saved.add(createEntry(rosa, HealthCategory.ALLERGY, ana, new EntryData(
                "Alergia a la penicilina",
                "Erupción en la piel e hinchazón de labios con amoxicilina cuando era joven.",
                null, null, today.minusYears(30), ConfidenceLevel.CONFIRMED,
                "Evitar penicilinas y derivados. Avisar siempre en consulta o emergencia.")));

        SavedEntry hypertension = createEntry(rosa, HealthCategory.CONDITION, ana, new EntryData(
                "Hipertensión arterial",
                "Diagnosticada hace 8 años. Presión controlada con medicación.",
                null, null, today.minusYears(8), ConfidenceLevel.CONFIRMED, null));
        // Segunda versión, editada por Bruno (HealthEntryService.update).
        hypertension = updateEntry(hypertension, bruno, new EntryData(
                "Hipertensión arterial",
                "Diagnosticada hace 8 años. Presión controlada con medicación.",
                null, null, today.minusYears(8), ConfidenceLevel.CONFIRMED,
                "Última toma en casa: 128/80. Anotar la presión por las mañanas."));
        saved.add(hypertension);

        saved.add(createEntry(rosa, HealthCategory.CONDITION, ana, new EntryData(
                "Diabetes tipo 2",
                "Controlada con dieta y metformina. Hemoglobina glicosilada en 6.8 % en el último control.",
                null, null, today.minusYears(5), ConfidenceLevel.CONFIRMED, null)));
        saved.add(createEntry(rosa, HealthCategory.CONDITION, ana, new EntryData(
                "Artrosis de rodilla",
                "Dolor al subir escaleras, sobre todo en la rodilla derecha.",
                null, null, today.minusYears(3), ConfidenceLevel.CONFIRMED,
                "Pendiente evaluación con traumatología.")));

        SavedEntry losartan = createEntry(rosa, HealthCategory.MEDICATION, ana, new EntryData(
                "Losartán",
                "Para la presión arterial.",
                "50 mg", "Cada 24 h", today.minusYears(2), ConfidenceLevel.CONFIRMED, null));
        // Cambio de dosis hecho por Bruno (HealthEntryService.update): v2.
        losartan = updateEntry(losartan, bruno, new EntryData(
                "Losartán",
                "Para la presión arterial. El cardiólogo ajustó la frecuencia.",
                "50 mg", "Cada 12 h", today.minusYears(2), ConfidenceLevel.CONFIRMED,
                "Cambio de cada 24 h a cada 12 h por indicación del cardiólogo."));
        saved.add(losartan);

        saved.add(createEntry(rosa, HealthCategory.MEDICATION, ana, new EntryData(
                "Metformina",
                "Para el control de la glucosa. Tomar con comida para evitar molestias estomacales.",
                "850 mg", "Con el almuerzo", today.minusYears(5), ConfidenceLevel.CONFIRMED, null)));
        saved.add(createEntry(rosa, HealthCategory.MEDICATION, ana, new EntryData(
                "Paracetamol",
                "Solo si hay dolor de rodilla. No pasar de 3 tomas al día.",
                "500 mg", "Cada 8 h si hay dolor", today.minusMonths(6), ConfidenceLevel.CONFIRMED, null)));

        saved.add(createEntry(rosa, HealthCategory.IMMUNIZATION, ana, new EntryData(
                "Vacuna contra la influenza 2026",
                "Aplicada en el centro de salud del distrito.",
                null, null, today.minusMonths(5), ConfidenceLevel.CONFIRMED, null)));
        saved.add(createEntry(rosa, HealthCategory.IMMUNIZATION, ana, new EntryData(
                "Vacuna contra el neumococo",
                "Dosis única para mayores de 65 años.",
                null, null, today.minusYears(2), ConfidenceLevel.UNCERTAIN,
                "La fecha es aproximada. Buscar el carné de vacunación.")));
        saved.add(createEntry(rosa, HealthCategory.IMMUNIZATION, ana, new EntryData(
                "Refuerzo contra la COVID-19",
                "Cuarta dosis.",
                null, null, today.minusMonths(14), ConfidenceLevel.CONFIRMED, null)));

        saved.add(createEntry(rosa, HealthCategory.EPISODE, ana, new EntryData(
                "Mareo y caída leve",
                "Se mareó al levantarse de la cama y cayó sentada. Sin golpes en la cabeza. Presión 100/60 en ese momento.",
                null, null, today.minusDays(20), ConfidenceLevel.CONFIRMED,
                "Comentar en el control con cardiología.")));
        saved.add(createEntry(rosa, HealthCategory.EPISODE, bruno, new EntryData(
                "Hipoglucemia leve",
                "Sudor frío y temblor antes del almuerzo. Se le dio jugo y mejoró en 15 minutos.",
                null, null, today.minusDays(45), ConfidenceLevel.CONFIRMED, null)));

        return saved;
    }

    private void createPedroEntries(Users users, CareSubject pedro) {
        LocalDate today = LocalDate.now();
        User ana = users.ana();
        createEntry(pedro, HealthCategory.CONDITION, ana, new EntryData(
                "Hiperplasia prostática benigna",
                "Se levanta dos veces por la noche a orinar. En control con urología.",
                null, null, today.minusYears(4), ConfidenceLevel.CONFIRMED, null));
        createEntry(pedro, HealthCategory.CONDITION, ana, new EntryData(
                "Colesterol alto",
                "Se controla con dieta y atorvastatina.",
                null, null, today.minusYears(6), ConfidenceLevel.CONFIRMED, null));
        createEntry(pedro, HealthCategory.MEDICATION, ana, new EntryData(
                "Atorvastatina",
                "Tomar de noche.",
                "20 mg", "Cada 24 h", today.minusYears(6), ConfidenceLevel.CONFIRMED, null));
        createEntry(pedro, HealthCategory.IMMUNIZATION, ana, new EntryData(
                "Vacuna contra la influenza 2026",
                null,
                null, null, today.minusMonths(5), ConfidenceLevel.CONFIRMED, null));
    }

    private void createLuciaEntries(Users users, CareSubject lucia) {
        LocalDate today = LocalDate.now();
        User ana = users.ana();
        createEntry(lucia, HealthCategory.CONDITION, ana, new EntryData(
                "Asma",
                "Crisis con el frío y el ejercicio fuerte. Controla con la pediatra cada 6 meses.",
                null, null, today.minusYears(4), ConfidenceLevel.CONFIRMED,
                "Llevar el inhalador al colegio y a las excursiones."));
        createEntry(lucia, HealthCategory.MEDICATION, ana, new EntryData(
                "Salbutamol inhalador",
                "2 inhalaciones con aerocámara cuando hay tos o silbidos en el pecho.",
                "100 mcg", "Cada 6 h si hay síntomas", today.minusYears(4), ConfidenceLevel.CONFIRMED, null));
        createEntry(lucia, HealthCategory.ALLERGY, ana, new EntryData(
                "Alergia a los ácaros del polvo",
                "Estornudos y congestión por las mañanas. Lavar la ropa de cama con agua caliente.",
                null, null, today.minusYears(3), ConfidenceLevel.CONFIRMED, null));
        createEntry(lucia, HealthCategory.IMMUNIZATION, ana, new EntryData(
                "Vacuna contra la influenza 2026",
                null,
                null, null, today.minusMonths(5), ConfidenceLevel.CONFIRMED, null));
    }

    // Mismo patrón que HealthEntryService.create: registro + versión 1 (CREATED) declarada por el autor.
    private SavedEntry createEntry(CareSubject subject, String categoryCode, User author, EntryData data) {
        HealthCategory category = categoryRepo.findByCode(categoryCode)
                .orElseThrow(() -> new IllegalStateException("Categoría de salud no encontrada: " + categoryCode));

        HealthEntry entry = entryRepo.save(HealthEntry.builder()
                .careSubject(subject)
                .category(category)
                .createdBy(author)
                .build());

        HealthEntryVersion v1 = versionRepo.save(HealthEntryVersion.builder()
                .healthEntry(entry)
                .versionNumber(1)
                .changeType(ChangeType.CREATED)
                .title(data.title())
                .details(data.details())
                .dose(data.dose())
                .frequency(data.frequency())
                .occurredOn(data.occurredOn())
                .confidenceLevel(data.confidence())
                .notes(data.notes())
                .declaredBy(author)
                .build());
        return new SavedEntry(entry, v1);
    }

    // Mismo patrón que HealthEntryService.update: nueva versión con número + 1 y changeType UPDATED.
    private SavedEntry updateEntry(SavedEntry previous, User author, EntryData data) {
        HealthEntryVersion next = versionRepo.save(HealthEntryVersion.builder()
                .healthEntry(previous.entry())
                .versionNumber(previous.latest().getVersionNumber() + 1)
                .changeType(ChangeType.UPDATED)
                .sourceAttachment(previous.latest().getSourceAttachment())
                .title(data.title())
                .details(data.details())
                .dose(data.dose())
                .frequency(data.frequency())
                .occurredOn(data.occurredOn())
                .confidenceLevel(data.confidence())
                .notes(data.notes())
                .declaredBy(author)
                .build());
        return new SavedEntry(previous.entry(), next);
    }

    private void createConsultations(Users users, CareSubject rosa) {
        LocalDate today = LocalDate.now();
        consultationRepo.save(Consultation.builder()
                .careSubject(rosa)
                .createdBy(users.ana())
                .date(today.minusDays(14))
                .reason("Control de presión")
                .notes("Presión 130/85. El médico pidió anotar la presión en casa cada mañana.")
                .build());
        consultationRepo.save(Consultation.builder()
                .careSubject(rosa)
                .createdBy(users.ana())
                .date(today.plusDays(5))
                .reason("Control con cardiología")
                .notes("Llevar el carné de vacunación y la lista de medicamentos.")
                .build());
    }

    // Mismo patrón que HandoffService.create: un item por la versión vigente de cada registro elegido.
    private void createHandoff(Users users, CareSubject rosa, List<SavedEntry> rosaEntries) {
        Set<String> selected = Set.of(
                HealthCategory.MEDICATION, HealthCategory.ALLERGY, HealthCategory.CONDITION);

        Handoff handoff = Handoff.builder()
                .careSubject(rosa)
                .createdBy(users.ana())
                .reason("Control con cardiología")
                .questions("¿Hay que seguir con losartán cada 12 h o se puede bajar la dosis?\n"
                        + "¿El mareo de hace unas semanas puede venir de la presión baja?")
                .build();

        for (SavedEntry saved : rosaEntries) {
            if (selected.contains(saved.entry().getCategory().getCode())) {
                handoff.getItems().add(HandoffItem.builder()
                        .handoff(handoff)
                        .healthEntryVersion(saved.latest())
                        .build());
            }
        }
        // Sin HandoffSession: el enlace se genera en vivo desde la app (el token solo se ve al crearlo).
        handoffRepo.save(handoff);
    }

    // Mismo patrón que DelegationService.create, sin publicar DelegationCreatedEvent (no manda correo).
    private void createDelegation(Users users, CareSubject rosa) {
        Set<HealthCategory> categories = new HashSet<>(
                categoryRepo.findByCodeIn(Set.of(HealthCategory.MEDICATION, HealthCategory.ALLERGY)));
        if (categories.size() != 2) {
            throw new IllegalStateException("Faltan categorías de salud para la delegación de demo");
        }

        // El token en claro se descarta: solo se guarda el hash, como en el servicio.
        String rawToken = TokenHasher.randomToken();
        delegationRepo.save(Delegation.builder()
                .careSubject(rosa)
                .grantedBy(users.ana())
                .granteeName("Enfermera Carmen Quispe")
                .granteeEmail(CARMEN_EMAIL)
                .tokenHash(TokenHasher.sha256(rawToken))
                .validUntil(Instant.now().plus(7, ChronoUnit.DAYS))
                .categories(categories)
                .build());
    }

    // Misma forma que NotificationListener (ENTRY_CHANGED) y los avisos de invitación; sin leer (readAt null).
    private void createNotifications(Users users, Subjects subjects) {
        notificationRepo.save(Notification.builder()
                .user(users.ana())
                .type(NotificationType.ENTRY_CHANGED)
                .careSubjectId(subjects.rosa().getId())
                .title("Cambio en Rosa")
                .body("Bruno Torres actualizó \"Losartán\" (Medicación)")
                .build());
        notificationRepo.save(Notification.builder()
                .user(users.ana())
                .type(NotificationType.ENTRY_CHANGED)
                .careSubjectId(subjects.rosa().getId())
                .title("Cambio en Rosa")
                .body("Bruno Torres actualizó \"Hipertensión arterial\" (Condición)")
                .build());
        notificationRepo.save(Notification.builder()
                .user(users.ana())
                .type(NotificationType.INVITATION)
                .careSubjectId(subjects.pedro().getId())
                .title("Invitación aceptada")
                .body("Bruno Torres aceptó la invitación para cuidar a Pedro")
                .build());
        notificationRepo.save(Notification.builder()
                .user(users.bruno())
                .type(NotificationType.INVITATION)
                .careSubjectId(subjects.lucia().getId())
                .title("Te invitaron a cuidar a Lucía")
                .body("Ana Torres te invitó a cuidar a Lucía en Yuyay")
                .build());
    }
}
