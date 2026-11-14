package com.yuyay.attachment.ratelimit;

import com.yuyay.attachment.entity.OcrStatus;
import com.yuyay.attachment.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JpaOcrUsageStore implements OcrUsageStore {
    private final OcrRequestLogRepository logRepo;
    private final AttachmentRepository attachmentRepo;

    @Override
    public long countPendingSince(Long userId, Instant since) {
        return attachmentRepo.countByUploadedByIdAndOcrStatusAndUploadedAtGreaterThanEqual(
                userId, OcrStatus.PENDING, since);
    }

    @Override
    public long countRequestsSince(Long userId, Instant since) {
        return logRepo.countByUserIdAndRequestedAtGreaterThanEqual(userId, since);
    }

    @Override
    public Optional<Instant> oldestRequestSince(Long userId, Instant since) {
        return logRepo.findFirstByUserIdAndRequestedAtGreaterThanEqualOrderByRequestedAtAsc(userId, since)
                .map(OcrRequestLog::getRequestedAt);
    }

    @Override
    public void record(Long userId, Instant at) {
        logRepo.save(OcrRequestLog.builder().userId(userId).requestedAt(at).build());
    }

    @Override
    public void deleteOlderThan(Long userId, Instant before) {
        logRepo.deleteOlderThan(userId, before);
    }
}
