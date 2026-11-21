package com.yuyay.auth.service;

import com.yuyay.auth.dto.TermsResponseDTO;
import com.yuyay.exception.AuthFlowException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class TermsService {
    private static final String CURRENT_VERSION = "1.0";

    private final TermsResponseDTO current;

    public TermsService() throws IOException {
        ClassPathResource text = new ClassPathResource("terms/yuyay-" + CURRENT_VERSION + ".txt");
        current = new TermsResponseDTO(CURRENT_VERSION, text.getContentAsString(StandardCharsets.UTF_8));
    }

    public TermsResponseDTO current() {
        return current;
    }

    public String requireCurrentAcceptance(JsonNode accepted, JsonNode version) {
        if (accepted == null || !accepted.isBoolean() || !accepted.booleanValue()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "TERMS_ACCEPTANCE_REQUIRED",
                    "Debes aceptar los términos y el aviso de privacidad para crear una cuenta. "
                            + "Si esta opción no aparece, actualiza Yuyay.");
        }
        if (version == null || !version.isString() || !current.version().equals(version.stringValue())) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "TERMS_VERSION_OUTDATED",
                    "Consulta y acepta la versión vigente de los términos y el aviso de privacidad");
        }
        return current.version();
    }
}
