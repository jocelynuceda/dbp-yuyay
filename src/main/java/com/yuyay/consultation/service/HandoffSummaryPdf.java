package com.yuyay.consultation.service;

import com.yuyay.consultation.entity.Handoff;
import com.yuyay.consultation.entity.HandoffItem;
import com.yuyay.health.entity.HealthCategory;
import com.yuyay.health.entity.ConfidenceLevel;
import com.yuyay.health.entity.HealthEntryVersion;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
class HandoffSummaryPdf {
    private static final float LEFT = 45;
    private static final float RIGHT = PDRectangle.A4.getWidth() - LEFT;
    private static final float TOP = PDRectangle.A4.getHeight() - 106;
    private static final float BOTTOM = 66;
    private static final Color INK = new Color(30, 33, 88);
    private static final Color SECONDARY = new Color(71, 79, 124);
    private static final Color RULE = new Color(193, 193, 222);
    private static final Color ACCENT = new Color(106, 100, 194);
    private static final Color ALERT = new Color(176, 42, 99);
    private static final Color CONFIRMED_FILL = new Color(219, 230, 243);
    private static final Color CONFIRMED_TEXT = new Color(31, 79, 122);
    private static final Color UNCERTAIN_FILL = new Color(196, 232, 239);
    private static final Color UNCERTAIN_TEXT = new Color(46, 44, 126);
    private static final float INDENT = 13;
    // Mismo orden y mismos títulos que el resumen compartido en la web.
    private static final List<Group> GROUPS = List.of(
            new Group(HealthCategory.ALLERGY, "Alergias"),
            new Group(HealthCategory.CONDITION, "Condiciones"),
            new Group(HealthCategory.MEDICATION, "Medicación"),
            new Group(HealthCategory.IMMUNIZATION, "Vacunas"),
            new Group(HealthCategory.EPISODE, "Episodios"),
            new Group(HealthCategory.EXAM_RESULT, "Exámenes"));
    private static final Locale PERU = Locale.forLanguageTag("es-PE");
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter LIMA_TIME = DateTimeFormatter.ofPattern("dd/MM/uuuu, HH:mm:ss", PERU)
            .withZone(LIMA);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu", PERU).withZone(LIMA);
    private static final Pattern WORD = Pattern.compile("[^ ]+ *| +");

    byte[] render(Handoff handoff, String subjectName, Instant authorizedAt) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.getDocumentCatalog().setLanguage("es-PE");
            document.getDocumentInformation().setTitle("Resumen clínico");
            document.getDocumentInformation().setAuthor("Yuyay");
            Layout layout = new Layout(document);
            try (layout) {
                layout.paragraph("Resumen de " + subjectName, layout.title, LEFT);
                layout.paragraph("Preparado el " + DATE.format(handoff.getCreatedAt()), layout.meta, LEFT);
                layout.section("Motivo de la consulta", handoff.getReason());
                if (hasText(handoff.getQuestions())) {
                    layout.section("Preguntas de la familia", handoff.getQuestions());
                }

                layout.records(handoff);
            }
            writeFooters(document, layout.meta.font(),
                    "Descarga autorizada el " + LIMA_TIME.format(authorizedAt) + " (hora de Lima)");
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar el PDF del resumen", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static InputStream asset(String filename) throws IOException {
        InputStream asset = HandoffSummaryPdf.class.getResourceAsStream("/handoff-pdf/" + filename);
        if (asset == null) throw new IOException("No se encontró el recurso PDF " + filename);
        return asset;
    }

    private static void writeFooters(PDDocument document, PDFont font, String stamp) throws IOException {
        Style footer = new Style(font, 8.5f, 12, SECONDARY);
        int total = document.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(index),
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                rule(stream, 48);
                text(stream, footer, stamp, LEFT, 32);
                String number = (index + 1) + " / " + total;
                text(stream, footer, number, RIGHT - width(footer, number), 32);
            }
        }
    }

    private static void logo(PDPageContentStream stream, float x, float y) throws IOException {
        // Trazados del isotipo de Yuyay en web/public/favicon.svg.
        float scale = 23f / 1514f;
        stream.saveGraphicsState();
        stream.transform(new Matrix(scale, 0, 0, -scale, x - 190 * scale, y + 1648 * scale));
        stream.setNonStrokingColor(INK);
        stream.moveTo(965, 1603);
        stream.curveTo(640, 1375, 400, 1170, 280, 960);
        stream.curveTo(150, 760, 215, 445, 515, 445);
        stream.curveTo(665, 445, 800, 535, 880, 680);
        stream.curveTo(960, 825, 975, 1000, 975, 1160);
        stream.curveTo(975, 1230, 970, 1295, 962, 1355);
        stream.curveTo(850, 1140, 750, 950, 630, 790);
        stream.curveTo(560, 705, 490, 653, 425, 653);
        stream.curveTo(360, 653, 318, 705, 318, 780);
        stream.curveTo(318, 870, 380, 985, 450, 1070);
        stream.curveTo(590, 1245, 760, 1390, 928, 1502);
        stream.closePath();
        stream.fill();
        stream.setNonStrokingColor(new Color(128, 164, 212));
        stream.moveTo(965, 1603);
        stream.curveTo(1270, 1375, 1500, 1170, 1620, 930);
        stream.curveTo(1660, 850, 1682, 790, 1682, 725);
        stream.curveTo(1682, 540, 1550, 428, 1380, 428);
        stream.curveTo(1235, 428, 1105, 525, 1030, 655);
        stream.lineTo(940, 840);
        stream.lineTo(1022, 912);
        stream.curveTo(1120, 755, 1265, 632, 1380, 632);
        stream.curveTo(1460, 632, 1518, 690, 1518, 770);
        stream.curveTo(1518, 870, 1450, 985, 1370, 1095);
        stream.curveTo(1260, 1250, 1100, 1430, 965, 1603);
        stream.closePath();
        stream.fill();
        stream.setNonStrokingColor(new Color(106, 100, 194));
        stream.moveTo(965, 180);
        stream.curveTo(986, 290, 1045, 355, 1162, 377);
        stream.curveTo(1045, 399, 986, 464, 965, 574);
        stream.curveTo(944, 464, 885, 399, 770, 377);
        stream.curveTo(885, 355, 944, 290, 965, 180);
        stream.closePath();
        stream.fill();
        stream.restoreGraphicsState();
    }

    private static void rule(PDPageContentStream stream, float y) throws IOException {
        stream.setStrokingColor(RULE);
        stream.setLineWidth(0.6f);
        stream.moveTo(LEFT, y);
        stream.lineTo(RIGHT, y);
        stream.stroke();
    }

    private static void pill(PDPageContentStream stream, Color color, float x, float y, float width, float height)
            throws IOException {
        float radius = height / 2;
        float curve = radius * 0.5523f;
        float right = x + width;
        float top = y + height;
        stream.setNonStrokingColor(color);
        stream.moveTo(x + radius, y);
        stream.lineTo(right - radius, y);
        stream.curveTo(right - radius + curve, y, right, y + radius - curve, right, y + radius);
        stream.curveTo(right, y + radius + curve, right - radius + curve, top, right - radius, top);
        stream.lineTo(x + radius, top);
        stream.curveTo(x + radius - curve, top, x, y + radius + curve, x, y + radius);
        stream.curveTo(x, y + radius - curve, x + radius - curve, y, x + radius, y);
        stream.closePath();
        stream.fill();
    }

    private static void text(PDPageContentStream stream, Style style, String value, float x, float y) throws IOException {
        if (value.isEmpty()) return;
        stream.beginText();
        stream.setFont(style.font(), style.size());
        stream.setNonStrokingColor(style.color());
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static float width(Style style, String value) throws IOException {
        return style.font().getStringWidth(value) * style.size() / 1000;
    }

    private static String printable(PDFont font, String value) throws IOException {
        String normalized = Normalizer.normalize(value.replace('\t', ' '), Normalizer.Form.NFC);
        try {
            font.encode(normalized);
            return normalized;
        } catch (IllegalArgumentException unsupported) {
            // Los controles de OCR y glifos fuera de la fuente pueden aparecer en texto importado.
            StringBuilder safe = new StringBuilder(normalized.length());
            for (int offset = 0; offset < normalized.length(); ) {
                int codePoint = normalized.codePointAt(offset);
                offset += Character.charCount(codePoint);
                String glyph = Character.toString(codePoint);
                try {
                    font.encode(glyph);
                    safe.append(glyph);
                } catch (IllegalArgumentException missingGlyph) {
                    safe.append(Character.isISOControl(codePoint) ? ' ' : '\uFFFD');
                }
            }
            return safe.toString();
        }
    }

    private static List<String> wrap(String value, Style style, float available) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\\R", -1)) {
            Matcher words = WORD.matcher(printable(style.font(), paragraph));
            StringBuilder line = new StringBuilder();
            float lineWidth = 0;
            while (words.find()) {
                String word = words.group();
                float wordWidth = width(style, word);
                if (lineWidth + wordWidth > available && !line.isEmpty()) {
                    lines.add(line.toString().stripTrailing());
                    line.setLength(0);
                    lineWidth = 0;
                }
                if (wordWidth <= available) {
                    line.append(word);
                    lineWidth += wordWidth;
                    continue;
                }
                for (int offset = 0; offset < word.length(); ) {
                    int codePoint = word.codePointAt(offset);
                    offset += Character.charCount(codePoint);
                    String glyph = Character.toString(codePoint);
                    float glyphWidth = width(style, glyph);
                    if (lineWidth + glyphWidth > available) {
                        lines.add(line.toString().stripTrailing());
                        line.setLength(0);
                        lineWidth = 0;
                    }
                    line.append(glyph);
                    lineWidth += glyphWidth;
                }
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    private record Style(PDFont font, float size, float leading, Color color) {}

    private record Group(String code, String label) {}

    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final Style title;
        private final Style heading;
        private final Style itemTitle;
        private final Style body;
        private final Style meta;
        private final Style fact;
        private final Style note;
        private final Style badge;
        private Color bar;
        private PDPageContentStream stream;
        private float y;

        Layout(PDDocument document) throws IOException {
            this.document = document;
            try (InputStream regular = asset("DejaVuSans.ttf"); InputStream bold = asset("DejaVuSans-Bold.ttf")) {
                PDFont normal = PDType0Font.load(document, regular);
                PDFont strong = PDType0Font.load(document, bold);
                title = new Style(strong, 22, 29, INK);
                heading = new Style(strong, 14, 21, INK);
                itemTitle = new Style(strong, 11, 17, INK);
                body = new Style(normal, 10.5f, 16, INK);
                meta = new Style(normal, 9, 14, SECONDARY);
                fact = new Style(strong, 10.5f, 16, INK);
                note = new Style(normal, 10.5f, 16, SECONDARY);
                badge = new Style(strong, 8.5f, 17, CONFIRMED_TEXT);
            }
        }

        void section(String label, String content) throws IOException {
            heading(label);
            paragraph(content, body, LEFT);
        }

        void heading(String label) throws IOException {
            ensureSpace(62);
            y -= 12;
            paragraph(label, heading, LEFT);
        }

        void records(Handoff handoff) throws IOException {
            if (handoff.getItems().isEmpty()) {
                section("Registros seleccionados", "Este resumen no incluye registros.");
                return;
            }
            List<HealthEntryVersion> versions = handoff.getItems().stream()
                    .map(HandoffItem::getHealthEntryVersion)
                    .toList();
            for (Group group : GROUPS) {
                group(group.label(), group.code(), versions.stream()
                        .filter(version -> group.code().equals(categoryCode(version)))
                        .toList());
            }
            // Una categoría nueva no debe desaparecer del documento.
            group("Otros registros", "", versions.stream()
                    .filter(version -> GROUPS.stream().noneMatch(group -> group.code().equals(categoryCode(version))))
                    .toList());
        }

        private static String categoryCode(HealthEntryVersion version) {
            return version.getHealthEntry().getCategory().getCode();
        }

        private void group(String label, String code, List<HealthEntryVersion> versions) throws IOException {
            if (versions.isEmpty()) return;
            boolean alert = HealthCategory.ALLERGY.equals(code);
            // El título de la categoría nunca queda solo al pie de una página.
            ensureSpace(12 + heading.leading() + recordStartHeight(recordTitle(versions.getFirst())));
            y -= 12;
            paragraph(label, alert ? new Style(heading.font(), heading.size(), heading.leading(), ALERT) : heading, LEFT);
            for (HealthEntryVersion version : versions) {
                record(version, alert ? ALERT : ACCENT);
            }
        }

        private static String confidence(HealthEntryVersion version) {
            return version.getConfidenceLevel() == ConfidenceLevel.CONFIRMED ? "Confirmado" : "Por confirmar";
        }

        private List<String> recordTitle(HealthEntryVersion version) throws IOException {
            return wrap(version.getTitle(), itemTitle, RIGHT - LEFT - INDENT - badgeWidth(version) - 10);
        }

        private float badgeWidth(HealthEntryVersion version) throws IOException {
            return width(badge, confidence(version)) + 14;
        }

        private float recordStartHeight(List<String> name) {
            return name.size() * itemTitle.leading() + body.leading() * 2 + meta.leading();
        }

        private void record(HealthEntryVersion version, Color color) throws IOException {
            float x = LEFT + INDENT;
            List<String> name = recordTitle(version);
            ensureSpace(recordStartHeight(name));
            bar = color;
            badge(version);
            for (String line : name) {
                line(line, itemTitle, x);
            }
            List<String> facts = new ArrayList<>();
            if (hasText(version.getDose())) facts.add("Dosis: " + version.getDose());
            if (hasText(version.getFrequency())) facts.add("Frecuencia: " + version.getFrequency());
            if (!facts.isEmpty()) paragraph(String.join(" · ", facts), fact, x);
            if (hasText(version.getDetails())) paragraph(version.getDetails(), body, x);
            if (hasText(version.getNotes())) paragraph("Notas: " + version.getNotes(), note, x);
            String date = version.getOccurredOn() == null ? "" : "Fecha: " + DATE.format(version.getOccurredOn()) + " · ";
            paragraph(date + "Actualizado el " + DATE.format(version.getDeclaredAt()), meta, x);
            bar = null;
            y -= 12;
        }

        private void badge(HealthEntryVersion version) throws IOException {
            boolean confirmed = version.getConfidenceLevel() == ConfidenceLevel.CONFIRMED;
            float width = badgeWidth(version);
            float left = RIGHT - width;
            pill(stream, confirmed ? CONFIRMED_FILL : UNCERTAIN_FILL, left, y - 4, width, 15);
            Style style = new Style(badge.font(), badge.size(), badge.leading(),
                    confirmed ? CONFIRMED_TEXT : UNCERTAIN_TEXT);
            text(stream, style, confidence(version), left + 7, y + 0.5f);
        }

        void paragraph(String value, Style style, float x) throws IOException {
            for (String line : wrap(value, style, RIGHT - x)) {
                line(line, style, x);
            }
        }

        private void line(String value, Style style, float x) throws IOException {
            ensureSpace(style.leading());
            if (bar != null) {
                // Se dibuja línea a línea para que la barra continúe en la página siguiente.
                stream.setNonStrokingColor(bar);
                stream.addRect(LEFT, y + 11 - style.leading(), 2.5f, style.leading());
                stream.fill();
            }
            text(stream, style, value, x, y);
            y -= style.leading();
        }

        private void ensureSpace(float height) throws IOException {
            if (stream != null && y - height >= BOTTOM) return;
            close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            float brandBaseline = PDRectangle.A4.getHeight() - 62;
            logo(stream, LEFT, brandBaseline - 4);
            text(stream, title, "Yuyay", LEFT + 30, brandBaseline);
            String section = "Resumen de salud";
            text(stream, meta, section, RIGHT - width(meta, section), brandBaseline + 3);
            rule(stream, brandBaseline - 15);
            y = TOP;
        }

        @Override
        public void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }
    }
}
