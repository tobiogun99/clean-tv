package io.github.cleantv;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose. Android only shares "what's playing" with apps that have a notification
 * listener; Clean TV needs the permission for that and never looks at notifications.
 */
public final class MediaListener extends NotificationListenerService {}
