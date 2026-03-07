import client from './client';

export interface NotificationPreference {
    notification_type: string;
    enabled: boolean;
}

export async function getNotificationPreferences(): Promise<NotificationPreference[]> {
    const { data } = await client.get<NotificationPreference[]>('/notifications/preferences');
    return data;
}

export async function updateNotificationPreferences(
    preferences: NotificationPreference[]
): Promise<void> {
    await client.put('/notifications/preferences', { preferences });
}
