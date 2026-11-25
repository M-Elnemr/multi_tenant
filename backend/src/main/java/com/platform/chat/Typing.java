package com.platform.chat;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * «Typing…» on the customer's screen while the assistant works on a reply: nothing for a quick answer; when no reply has gone out {@code app.chat.typing-after-ms} (4 s) after
 * the message, the channel's typing signal is sent, and sent again every {@code repeatMs} (each network clears it by itself after a few seconds) until the reply is ready.
 * Best effort: a signal the network refuses is only logged, never delays or stops the reply, and slow networks never hold the timer (the calls run on their own small pool,
 * and a signal that cannot be started at once is dropped).
 */
@Component
public class Typing {
    private static final Logger log = LoggerFactory.getLogger(Typing.class);
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "typing-timer"); t.setDaemon(true); return t; });
    private static final ThreadPoolExecutor CALLS = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(64),
            r -> { Thread t = new Thread(r, "typing-call"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.DiscardPolicy());
    /** Longest a chat shows typing for one reply (a stuck reply must not show it forever). */
    private static final long MAX_MS = 120_000;
    private static volatile long afterMs = 4_000;

    public Typing(@Value("${app.chat.typing-after-ms:4000}") long after) { afterMs = after; }

    /** Stops the typing signal of one reply; closing twice is fine. */
    public interface Handle extends AutoCloseable { @Override void close(); }

    private static final Handle NONE = () -> { };

    /** Starts the timer for one reply: {@code signal} runs after the delay and every {@code repeatMs} until the handle is closed (at most {@link #MAX_MS}). */
    public static Handle start(Runnable signal, long repeatMs) {
        if (signal == null || afterMs <= 0 || repeatMs <= 0) return NONE;
        long started = System.currentTimeMillis();
        Runnable call = () -> CALLS.execute(() -> {
            try { signal.run(); } catch (RuntimeException e) { log.debug("Typing signal not sent: {}", e.getMessage()); }
        });
        ScheduledFuture<?>[] task = new ScheduledFuture<?>[1];
        task[0] = TIMER.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - started > MAX_MS) { task[0].cancel(false); return; }
            call.run();
        }, afterMs, repeatMs, TimeUnit.MILLISECONDS);
        return () -> task[0].cancel(false);
    }

    static long afterMs() { return afterMs; }
}
