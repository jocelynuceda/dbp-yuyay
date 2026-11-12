package com.yuyay.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class CapturedLog implements AutoCloseable {

    private final Logger logger;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    CapturedLog(Class<?> type) {
        this.logger = (Logger) LoggerFactory.getLogger(type);
        appender.start();
        logger.addAppender(appender);
    }

    String onlyMessage() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst().getFormattedMessage();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
    }
}
