package io.github.cuukenn.fincalc.server.web;

import io.github.cuukenn.fincalc.server.error.ServerErrorCode;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * §15 的 strict 1MB JSON 请求体上限（form-field-gaps 收口轮 2026-11-14 实施——此前的
 * 「documented limitation」）：Tomcat 的 {@code max-http-form-post-size} 只覆盖 form/multipart
 * POST，普通 JSON 体不受保护，本过滤器补上。
 *
 * <p>双路径拦截：
 *
 * <ol>
 *   <li>{@code Content-Length} 头直接超限 → 不读体，立即 413；</li>
 *   <li>无长度头（chunked）或头部在限内 → 包装输入流逐字节计数，超限在读取中途 413
 *       （截断的体不会到达控制器）。</li>
 * </ol>
 *
 * <p>错误体沿用统一 {@link ApiError} 形状（R77：{code, message}），HTTP 413
 * PAYLOAD_TOO_LARGE，code 为 {@link ServerErrorCode#REQUEST_BODY_TOO_LARGE}。上限 1MB
 * 与 application.yml 里 Tomcat 的 form/multipart 上限同源（§15 的数字）。
 *
 * <p>只对 POST/PUT/PATCH 这类带体的方法生效；GET/HEAD/DELETE 直接放行。
 */
@org.springframework.stereotype.Component
public class RequestBodySizeFilter implements Filter {

    /** §15 的 1MB 上限。 */
    public static final long MAX_BODY_BYTES = 1024L * 1024;

    private static final String ERROR_BODY = "{\"code\":\"" + ServerErrorCode.REQUEST_BODY_TOO_LARGE.code()
            + "\",\"message\":\"" + ServerErrorCode.REQUEST_BODY_TOO_LARGE.code()
            + ": 请求体超过上限: " + MAX_BODY_BYTES + " 字节\"}";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http)) {
            chain.doFilter(request, response);
            return;
        }
        String method = http.getMethod();
        boolean hasBody = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        if (!hasBody) {
            chain.doFilter(request, response);
            return;
        }
        long declared = http.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) {
            reject(response);
            return;
        }
        if (declared >= 0) {
            // 长度头在限内：透传原请求（无需计数包装——容器按 Content-Length 读完为止）。
            chain.doFilter(request, response);
            return;
        }
        // chunked / 未知长度：计数包装，读取中途超限即拒。
        CountingRequest counted = new CountingRequest(http);
        try {
            chain.doFilter(counted, response);
        } catch (BodyTooLargeException ex) {
            // 控制器/反序列化在读取中途触发；响应可能尚未提交，仍可写 413。
            if (!response.isCommitted()) {
                reject(response);
            } else {
                throw ex;
            }
        }
    }

    private static void reject(ServletResponse response) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ((jakarta.servlet.http.HttpServletResponse) response)
                .setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.getWriter().write(ERROR_BODY);
    }

    /** 超限的流内信号：{@code read} 在越过上限时抛出，让读取方（Jackson）以异常收场。 */
    static final class BodyTooLargeException extends IOException {

        BodyTooLargeException() {
            super("request body exceeds " + MAX_BODY_BYTES + " bytes");
        }
    }

    /** 逐字节计数的请求包装；超限在流读取时抛 {@link BodyTooLargeException}。 */
    private static final class CountingRequest extends HttpServletRequestWrapper {

        private long read;

        CountingRequest(HttpServletRequest delegate) {
            super(delegate);
        }

        @Override
        public ServletInputStream getInputStream() {
            ServletInputStream delegate;
            try {
                delegate = getRequest().getInputStream();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            return new ServletInputStream() {
                @Override
                public int read() throws IOException {
                    if (read >= MAX_BODY_BYTES) {
                        throw new BodyTooLargeException();
                    }
                    int value = delegate.read();
                    if (value >= 0) {
                        read++;
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (read >= MAX_BODY_BYTES) {
                        throw new BodyTooLargeException();
                    }
                    int allowed = (int) Math.min(length, MAX_BODY_BYTES - read);
                    int count = delegate.read(buffer, offset, allowed);
                    if (count > 0) {
                        read += count;
                    }
                    return count;
                }

                @Override
                public int available() throws IOException {
                    return delegate.available();
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }


}
