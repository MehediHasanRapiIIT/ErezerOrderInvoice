package com.erezer.admin.notification;

import com.erezer.admin.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Watches every {@link Notifies} endpoint from one place, so no section can forget. The record is described before the
 * endpoint runs (a deleted one is gone afterwards), and the notification is made only after the endpoint succeeded. A
 * problem here is logged and never reaches the person who did the work.
 */
@Component
class NotificationRecorder implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(NotificationRecorder.class);
    private static final String SUMMARY = "notification.summary";
    private static final String BODY = "notification.body";
    private static final String[] ID_VARIABLES = {"id", "userId", "returnId", "requestId"};

    private final NotificationService notifications;

    NotificationRecorder(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Notifies notifies = notifiesOf(handler);
        Long id = pathId(request);
        if (notifies != null && id != null) {
            try {
                request.setAttribute(SUMMARY, notifications.describe(notifies.value().target(), id));
            } catch (RuntimeException ex) {
                log.warn("Could not describe {} {}", notifies.value(), id, ex);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        Notifies notifies = notifiesOf(handler);
        if (notifies == null || ex != null || response.getStatus() >= 400) {
            return;
        }
        try {
            NotificationAction action = request.getAttribute(Notifies.Context.ACTION) instanceof NotificationAction chosen ? chosen : notifies.value();
            Long id = pathId(request);
            if (id == null) {
                id = idOf(request.getAttribute(BODY));
            }
            String summary = request.getAttribute(SUMMARY) instanceof String text ? text : null;
            if (summary == null && id != null) {
                summary = notifications.describe(action.target(), id);
            }
            String detail = request.getAttribute(Notifies.Context.DETAIL) instanceof String text ? text : null;
            notifications.record(action, CurrentUser.id(), id, summary, detail);
        } catch (RuntimeException failure) {
            log.warn("Could not record notification {}", notifies.value(), failure);
        }
    }

    private static Notifies notifiesOf(Object handler) {
        return handler instanceof HandlerMethod method ? method.getMethodAnnotation(Notifies.class) : null;
    }

    private static Long pathId(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables) {
            for (String name : ID_VARIABLES) {
                if (variables.get(name) instanceof String value) {
                    try {
                        return Long.valueOf(value);
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /** The id of what an endpoint created, from its response. */
    private static Long idOf(Object body) {
        if (body == null) {
            return null;
        }
        if (body instanceof Number number) {
            return number.longValue();
        }
        if (body instanceof Map<?, ?> map) {
            return map.get("id") instanceof Number number ? Long.valueOf(number.longValue()) : null;
        }
        try {
            Method id = body.getClass().getMethod("id");
            id.setAccessible(true); // responses are often records private to their package
            return id.invoke(body) instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    /** Keeps what a {@link Notifies} endpoint answered, for the id of a new record. */
    @ControllerAdvice
    static class ResponseKeeper implements ResponseBodyAdvice<Object> {

        @Override
        public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
            return returnType.hasMethodAnnotation(Notifies.class);
        }

        @Override
        public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
            if (request instanceof ServletServerHttpRequest servlet) {
                servlet.getServletRequest().setAttribute(BODY, body);
            }
            return body;
        }
    }
}
