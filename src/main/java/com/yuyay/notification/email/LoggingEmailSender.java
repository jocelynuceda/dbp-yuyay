package com.yuyay.notification.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@ConditionalOnExpression("'${app.mail.resend-api-key:}'.isEmpty()")
public class LoggingEmailSender implements EmailSender {

    private static final String MESSAGE =
            "Correo simulado (falta RESEND_API_KEY) para {}: \"{}\"{}";

    private static final Pattern CODE = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern LINK =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    @Override
    public void send(String to, String subject, String html) {
        log.info(MESSAGE, to, subject, details(html == null ? "" : html));
    }

    private String details(String html) {
        StringBuilder details = new StringBuilder();

        Matcher code = CODE.matcher(withoutTags(html));
        if (code.find()) {
            details.append(". Código: ").append(code.group());
        }

        Set<String> links = links(html);
        if (!links.isEmpty()) {
            details.append(links.size() == 1 ? ". Enlace: " : ". Enlaces: ")
                    .append(String.join(", ", links));
        }

        return details.toString();
    }

    private Set<String> links(String html) {
        Set<String> links = new LinkedHashSet<>();
        Matcher link = LINK.matcher(html);
        while (link.find()) {
            links.add(link.group(1));
        }
        return links;
    }

    private String withoutTags(String html) {
        return html.replaceAll("<[^>]*>", " ");
    }
}
