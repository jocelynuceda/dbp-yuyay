package com.yuyay.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Contraste del nombre del perfil contra el nombre del DNI")
class IdentityRecordTest {

    private static final IdentityRecord PERSON =
            new IdentityRecord("ANA MARIA", "QUISPE", "ROJAS");

    @Test
    @DisplayName("el nombre completo coincide")
    void theFullNameMatches() {
        assertThat(PERSON.matchesName("Ana Maria Quispe Rojas")).isTrue();
    }

    @Test
    @DisplayName("el orden de las palabras no importa")
    void theOrderOfTheWordsDoesNotMatter() {
        assertThat(PERSON.matchesName("QUISPE ROJAS ANA MARIA")).isTrue();
    }

    @Test
    @DisplayName("se ignoran tildes, mayúsculas y puntuación")
    void accentsCaseAndPunctuationAreIgnored() {
        assertThat(PERSON.matchesName("  aná-maría  quispe, rojas ")).isTrue();
    }

    @Test
    @DisplayName("la ñ se compara sin tilde")
    void theSpanishNIsFolded() {
        IdentityRecord munoz = new IdentityRecord("JOSE", "MUÑOZ", "PEÑA");

        assertThat(munoz.matchesName("Jose Munoz")).isTrue();
    }

    @Test
    @DisplayName("un nombre corto con un nombre y un apellido coincide")
    void aShortNameWithOneGivenNameAndOneSurnameMatches() {
        assertThat(PERSON.matchesName("Ana Quispe")).isTrue();
        assertThat(PERSON.matchesName("Maria Rojas")).isTrue();
    }

    @Test
    @DisplayName("se ignoran las iniciales de una sola letra")
    void singleLetterInitialsAreIgnored() {
        assertThat(PERSON.matchesName("Ana M. Quispe")).isTrue();
    }

    @Test
    @DisplayName("solo el nombre no alcanza")
    void givenNamesAloneAreNotEnough() {
        assertThat(PERSON.matchesName("Ana Maria")).isFalse();
    }

    @Test
    @DisplayName("solo los apellidos no alcanzan")
    void surnamesAloneAreNotEnough() {
        assertThat(PERSON.matchesName("Quispe Rojas")).isFalse();
    }

    @Test
    @DisplayName("una palabra ajena al DNI lo rechaza")
    void aWordThatIsNotOnTheDocumentRejectsIt() {
        assertThat(PERSON.matchesName("Ana Maria Perez")).isFalse();
        assertThat(PERSON.matchesName("Ana Maria Quispe Rojas Perez")).isFalse();
    }

    @Test
    @DisplayName("un nombre vacío o nulo nunca coincide")
    void anEmptyOrNullNameNeverMatches() {
        assertThat(PERSON.matchesName(null)).isFalse();
        assertThat(PERSON.matchesName("   ")).isFalse();
        assertThat(PERSON.matchesName("J. M.")).isFalse();
    }

    @Test
    @DisplayName("un apellido materno vacío no estorba la comparación")
    void anEmptySecondSurnameDoesNotBreakTheComparison() {
        IdentityRecord single = new IdentityRecord("LUIS", "TORRES", "");

        assertThat(single.matchesName("Luis Torres")).isTrue();
        assertThat(single.fullName()).isEqualTo("LUIS TORRES");
    }

    @Test
    void aOneLetterDifferenceRequestsCorrectionEvenWhenTheSurnameMatches() {
        var person = new IdentityRecord("LUIS FELIPE", "RAMOS", "SOTO");
        assertThat(person.nameSimilarity("Luiz Ramos")).isEqualTo(0.75);
        assertThat(person.matchesName("Luiz Ramos")).isFalse();
        assertThat(person.matchesName("Felipe Ramos")).isTrue();
        assertThat(person.matchesName("Luiz Felipe Ramos")).isFalse();
    }

    @Test
    void theGivenNameAndSurnameNeedSeparateWordsAndParticlesCannotFillEitherRole() {
        assertThat(new IdentityRecord("MARÍA DEL CARMEN", "DEL CASTILLO", "ROJAS")
                .matchesName("Del Castillo")).isFalse();
        var person = new IdentityRecord("ÁNGEL", "ÁNGEL", "ROJAS");
        assertThat(person.matchesName("Ángel")).isFalse();
        assertThat(person.matchesName("Ángel Ángel")).isTrue();
    }
}
