package com.yuyay.event.listener;

import com.yuyay.audit.entity.AccessLog;
import com.yuyay.audit.repository.AccessLogRepository;
import com.yuyay.care.repository.CareSubjectRepository;
import com.yuyay.care.repository.DelegationRepository;
import com.yuyay.event.AccessRecordedEvent;
import com.yuyay.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogListener {

    private final AccessLogRepository accessLogRepository;
    private final CareSubjectRepository careSubjectRepository;
    private final UserRepository userRepository;
    private final DelegationRepository delegationRepository;

    @Async("eventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAccessRecorded(AccessRecordedEvent event) {
        try {
            accessLogRepository.save(AccessLog.builder()
                    .careSubject(careSubjectRepository.getReferenceById(event.careSubjectId()))
                    .user(event.userId() == null ? null
                            : userRepository.getReferenceById(event.userId()))
                    .delegation(event.delegationId() == null ? null
                            : delegationRepository.getReferenceById(event.delegationId()))
                    .action(event.action())
                    .targetType(event.targetType())
                    .targetId(event.targetId())
                    .visitorName(event.visitorName())
                    .visitorRole(event.visitorRole())
                    .build());
        } catch (Exception e) {
            log.error("Fallo insertando AccessLog {}", event, e);
        }
    }
}
