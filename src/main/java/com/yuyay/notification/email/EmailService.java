package com.yuyay.notification.email;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailService {

    private final EmailSender emailSender;
    private final SpringTemplateEngine templateEngine;

    public void sendTemplate(
            String to,
            String subject,
            String templateName,
            Map<String, Object> variables
    ) {
        Context context = new Context();
        context.setVariables(variables);

        String html = templateEngine.process(
                "email/" + templateName,
                context
        );

        emailSender.send(to, subject, html);
    }
}