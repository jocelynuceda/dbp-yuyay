package com.yuyay.attachment.web;

import com.yuyay.common.ClientHeader;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Registra una línea por cada subida de adjunto y cada reintento de OCR, incluso si la solicitud falla antes de
 * guardar el archivo (401/403 de seguridad, errores de multipart, validaciones 4xx).
 * Sirve para depurar: si no hay línea, la solicitud nunca llegó al backend; si hay una línea 4xx, llegó y fue rechazada.
 * Corre antes de Spring Security (precedencia más alta). Nunca registra la URI, la query, el nombre del archivo,
 * otras cabeceras ni el cuerpo; solo el endpoint lógico, el estado, la duración, el tamaño declarado y el cliente.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AttachmentRequestLogFilter extends OncePerRequestFilter {
    private static final Pattern UPLOAD = Pattern.compile("^/api/v1/care-subjects/\\d+/attachments$");
    private static final Pattern OCR_RETRY = Pattern.compile("^/api/v1/care-subjects/\\d+/attachments/\\d+/ocr-retry$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return endpointOf(request) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String endpoint = endpointOf(request);
        // Se lee aquí, antes de Spring Security: su firewall lanza RequestRejectedException al leer valores
        // con caracteres de control, y esta cabecera es solo informativa.
        String client = ClientHeader.sanitize(request.getHeader(ClientHeader.NAME));
        request.setAttribute(ClientHeader.ATTRIBUTE, client);
        long started = System.nanoTime();
        int status = 500;
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            long contentLength = request.getContentLengthLong();
            log.info("Solicitud de adjunto endpoint={} status={} durationMs={} contentLength={} client={}",
                    endpoint, status, durationMs,
                    contentLength < 0 ? "unavailable" : String.valueOf(contentLength),
                    client);
        }
    }

    /** Endpoint lógico de la solicitud, o null si no interesa (cualquier otro método o ruta). */
    private static String endpointOf(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return null;
        String uri = request.getRequestURI();
        if (uri == null) return null;
        if (UPLOAD.matcher(uri).matches()) return "upload";
        if (OCR_RETRY.matcher(uri).matches()) return "ocr-retry";
        return null;
    }
}
