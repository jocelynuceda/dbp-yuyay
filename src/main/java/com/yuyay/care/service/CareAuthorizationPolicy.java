package com.yuyay.care.service;

import com.yuyay.care.dto.CareAuthorizationDeclarationRequest;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse;
import com.yuyay.care.dto.CareAuthorizationPolicyResponse.Option;
import com.yuyay.care.entity.CareAuthorizationScope;
import com.yuyay.care.entity.CareAuthorizationType;
import com.yuyay.care.exception.InvalidCareAuthorizationDeclarationException;
import com.yuyay.exception.InvalidOperationException;

import java.util.List;

final class CareAuthorizationPolicy {
    private static final String THIRD_PARTY_CONFIRMATION = "Confirmo que esta declaración es verdadera. "
            + "Usaré y compartiré los datos únicamente dentro de lo autorizado";

    // Cambiar la versión cuando cambie el texto o alcance de alguna opción.
    static final CareAuthorizationPolicyResponse CURRENT = new CareAuthorizationPolicyResponse(
            "1",
            "¿Cómo estás autorizado para usar sus datos en Yuyay?",
            List.of(
                    new Option(CareAuthorizationType.SELF, "Este perfil es para mí.",
                            "Registraré mis datos y decidiré con quién compartirlos en Yuyay.",
                            CareAuthorizationScope.OWN_DATA, "Confirmo que este perfil corresponde a mi persona."),
                    new Option(CareAuthorizationType.FICTITIOUS, "Este perfil es ficticio.",
                            "Usaré únicamente datos inventados y evitaré incluir información de personas reales.",
                            CareAuthorizationScope.FICTITIOUS_DATA_ONLY,
                            "Confirmo que todos los datos de este perfil son ficticios."),
                    new Option(CareAuthorizationType.HOLDER_PERMISSION, "Esta persona me dio permiso.",
                            "Me autorizó a registrar sus datos y compartirlos con las personas que ella permita.",
                            CareAuthorizationScope.WITHIN_DECLARED_AUTHORIZATION, THIRD_PARTY_CONFIRMATION),
                    new Option(CareAuthorizationType.LEGAL_REPRESENTATION, "Actúo como su representante legal.",
                            "Tengo facultades legales para autorizar este uso en su nombre.",
                            CareAuthorizationScope.WITHIN_DECLARED_AUTHORIZATION, THIRD_PARTY_CONFIRMATION),
                    new Option(CareAuthorizationType.PENDING, "Todavía no tengo autorización o no estoy seguro.",
                            "Puedes continuar cuando cuentes con la autorización correspondiente", null, null)
            ));

    private CareAuthorizationPolicy() {}

    static Option validate(CareAuthorizationDeclarationRequest request) {
        Option option = CURRENT.options().stream()
                .filter(candidate -> candidate.type() == request.type())
                .findFirst()
                .orElseThrow(() -> new InvalidCareAuthorizationDeclarationException("Selecciona un tipo de declaración"));
        if (option.type() == CareAuthorizationType.PENDING) {
            throw new InvalidOperationException(option.description());
        }
        if (!CURRENT.textVersion().equals(request.textVersion())) {
            throw new InvalidOperationException("La declaración cambió. Vuelve a revisar el texto para continuar");
        }
        if (!Boolean.TRUE.equals(request.confirmed())) {
            throw new InvalidCareAuthorizationDeclarationException("Confirma la declaración para continuar");
        }
        return option;
    }

    static String snapshot(Option option) {
        String declaration = String.join("\n", option.label(), option.description(), option.confirmationText());
        if (option.scope() == CareAuthorizationScope.WITHIN_DECLARED_AUTHORIZATION) {
            return CURRENT.thirdPartyQuestion() + "\n" + declaration;
        }
        return declaration;
    }
}
