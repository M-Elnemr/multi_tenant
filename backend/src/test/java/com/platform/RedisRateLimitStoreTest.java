package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.platform.shared.RedisRateLimitStore;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Runs against a real Redis protocol server. Set REDIS_SERVER_CMD to a python with fakeredis, or run a local redis on REDIS_TEST_PORT. Skipped otherwise (CI installs fakeredis). */
class RedisRateLimitStoreTest {
    static Process server;
    static int port;
    static LettuceConnectionFactory factory;

    static RedisRateLimitStore newStore() {
        StringRedisTemplate t = new StringRedisTemplate(factory);
        t.afterPropertiesSet();
        return new RedisRateLimitStore(t);
    }

    @BeforeAll
    static void start() throws Exception {
        String py = System.getenv("REDIS_SERVER_PYTHON");
        if (py == null || !Files.isExecutable(Path.of(py))) return;
        try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
        server = new ProcessBuilder(py, "-c", "from fakeredis import TcpFakeServer; TcpFakeServer(('127.0.0.1', %d)).serve_forever()".formatted(port))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Thread.sleep(1500);
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration("127.0.0.1", port);
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (factory != null) factory.destroy();
        if (server != null) server.destroyForcibly();
    }

    @Test
    void limitsAreSharedByEveryInstanceExpireWithTheWindowAndResetOnDemand() throws Exception {
        assumeTrue(server != null, "REDIS_SERVER_PYTHON not set: Redis test skipped");
        RedisRateLimitStore a = newStore(), b = newStore();   // two backend instances
        String k = "login:" + System.nanoTime();
        assertThat(a.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();
        assertThat(b.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();
        assertThat(a.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();
        assertThat(b.tryAcquire(k, 3, Duration.ofSeconds(30))).isFalse();   // the 4th attempt is blocked no matter which instance receives it
        assertThat(a.tryAcquire(k + "x", 3, Duration.ofSeconds(30))).isTrue();   // other keys are independent
        a.reset(k);
        assertThat(b.tryAcquire(k, 3, Duration.ofSeconds(30))).isTrue();

        String short1 = "short:" + System.nanoTime();
        assertThat(a.tryAcquire(short1, 1, Duration.ofSeconds(1))).isTrue();
        assertThat(a.tryAcquire(short1, 1, Duration.ofSeconds(1))).isFalse();
        Thread.sleep(1300);
        assertThat(b.tryAcquire(short1, 1, Duration.ofSeconds(1))).isTrue();   // the window rolled over
    }

    @Test
    void aRedisOutageNeverBlocksUsers() throws Exception {
        assumeTrue(server != null, "REDIS_SERVER_PYTHON not set: Redis test skipped");
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        dead.afterPropertiesSet();
        StringRedisTemplate t = new StringRedisTemplate(dead);
        t.afterPropertiesSet();
        assertThat(new RedisRateLimitStore(t).tryAcquire("any", 1, Duration.ofSeconds(5))).isTrue();   // fail open
        dead.destroy();
    }
}
