package com.erezer.admin.notification;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Marks an endpoint that changes data: when it succeeds, {@link #value()} is recorded as having happened. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Notifies {

    NotificationAction value();

    /** For endpoints whose outcome decides the wording. */
    final class Context {

        static final String ACTION = "notification.action";
        static final String DETAIL = "notification.detail";

        private Context() {
        }

        /** Records this action instead of the annotated one. */
        public static void as(NotificationAction action) {
            set(ACTION, action);
        }

        /** Adds a line to the notification, such as the new status. */
        public static void detail(String text) {
            set(DETAIL, text);
        }

        private static void set(String name, Object value) {
            RequestAttributes request = RequestContextHolder.getRequestAttributes();
            if (request != null) {
                request.setAttribute(name, value, RequestAttributes.SCOPE_REQUEST);
            }
        }
    }
}
