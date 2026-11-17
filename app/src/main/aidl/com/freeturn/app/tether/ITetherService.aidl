package com.freeturn.app.tether;

/**
 * Раздача точки доступа через VPN без root. Живёт в процессе Shizuku с правами shell:
 * только им доступны тестовые сети и управление раздачей.
 */
interface ITetherService {
    /** Код уничтожения UserService, зарезервированный Shizuku. */
    void destroy() = 16777114;

    /**
     * Поднимает тестовый TUN, делает его выходом раздачи, запускает helper и (по флагу)
     * точку доступа. Возвращает пустую строку при успехе или текст ошибки.
     * log - построчный журнал шагов, для логов приложения.
     */
    String start(String helperPath, String proxyUrl, in String[] dnsServers, int mtu,
                 boolean startHotspot, out List<String> log) = 1;

    /** Останавливает раздачу и убирает тестовую сеть. Журнал - как у start. */
    void stop(boolean stopHotspot, out List<String> log) = 2;

    /** Накопленный вывод helper'а (tun2socks) с прошлого вызова - для журнала приложения. */
    List<String> drainLog() = 3;
}
