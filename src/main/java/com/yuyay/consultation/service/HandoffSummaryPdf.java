package com.yuyay.consultation.service;

import com.yuyay.consultation.entity.Handoff;
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

    private static final class Layout implements AutoCloseable {
        private final PDDocument document;
        private final Style title;
        private final Style heading;
        private final Style itemTitle;
        private final Style body;
        private final Style meta;
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
            if (!handoff.getItems().isEmpty()) {
                HealthEntryVersion first = handoff.getItems().getFirst().getHealthEntryVersion();
                ensureSpace(12 + heading.leading() + recordStartHeight(recordTitle(first)));
            }
            heading("Registros seleccionados");
            for (var item : handoff.getItems()) {
                record(item.getHealthEntryVersion());
            }
        }

        private List<String> recordTitle(HealthEntryVersion version) throws IOException {
            return wrap(version.getTitle(), itemTitle, RIGHT - LEFT - 11);
        }

        private float recordStartHeight(List<String> name) {
            return name.size() * itemTitle.leading() + meta.leading() * 2 + body.leading() + 5;
        }

        void record(HealthEntryVersion version) throws IOException {
            float x = LEFT + 11;
            List<String> name = recordTitle(version);
            ensureSpace(recordStartHeight(name));
            rule(stream, y + 12);
            for (String line : name) {
                line(line, itemTitle, x);
            }
            String confidence = version.getConfidenceLevel() == ConfidenceLevel.CONFIRMED ? "Confirmado" : "Por confirmar";
            String category = version.getHealthEntry().getCategory().getName();
            String date = version.getOccurredOn() == null ? "" : " · " + DATE.format(version.getOccurredOn());
            paragraph(category + " · " + confidence + date, meta, x);
            paragraph("Registrado el " + DATE.format(version.getDeclaredAt()) + " · Versión " + version.getVersionNumber(), meta, x);
            if (hasText(version.getDose())) paragraph("Dosis · " + version.getDose(), body, x);
            if (hasText(version.getFrequency())) paragraph("Frecuencia · " + version.getFrequency(), body, x);
            if (hasText(version.getDetails())) paragraph(version.getDetails(), body, x);
            if (hasText(version.getNotes())) {
                ensureSpace(itemTitle.leading() + body.leading());
                paragraph("Notas", itemTitle, x);
                paragraph(version.getNotes(), body, x);
            }
            y -= 10;
        }

        void paragraph(String value, Style style, float x) throws IOException {
            for (String line : wrap(value, style, RIGHT - x)) {
                line(line, style, x);
            }
        }

        private void line(String value, Style style, float x) throws IOException {
            ensureSpace(style.leading());
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
