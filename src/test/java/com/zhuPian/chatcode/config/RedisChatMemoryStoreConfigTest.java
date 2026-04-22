package com.zhuPian.chatcode.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RedisChatMemoryStoreConfigTest {

    private static final String REDIS_HOST = "172.23.165.223";
    private static final int REDIS_PORT = 6379;
    private static final int REDIS_DATABASE = 5;
    private static final String REDIS_USERNAME = "default";
    private static final String REDIS_PASSWORD = "123456";

    @Test
    void createsAuthenticatedChatMemoryStoreOnConfiguredDatabase() {
        RedisChatMemoryStoreConfig config = new RedisChatMemoryStoreConfig();
        config.setHost(REDIS_HOST);
        config.setPort(REDIS_PORT);
        config.setDatabase(REDIS_DATABASE);
        config.setUsername(REDIS_USERNAME);
        config.setPassword(REDIS_PASSWORD);
        config.setTtl(60);

        ChatMemoryStore store = config.redisChatMemoryStore();
        String memoryId = "tdd-chat-memory:" + UUID.randomUUID();

        try (JedisPooled db5 = redisClient(REDIS_DATABASE);
             JedisPooled db0 = redisClient(0)) {
            store.updateMessages(memoryId, List.of(UserMessage.from("hello redis db5")));

            List<ChatMessage> messages = store.getMessages(memoryId);
            assertEquals(1, messages.size());
            assertEquals(UserMessage.from("hello redis db5"), messages.getFirst());
            assertNotNull(db5.get(memoryId), "聊天记忆必须写入配置指定的 Redis DB 5");
            assertNull(db0.get(memoryId), "聊天记忆不应写入默认 Redis DB 0");
        } finally {
            store.deleteMessages(memoryId);
            try (JedisPooled db0 = redisClient(0)) {
                db0.del(memoryId);
            }
        }
    }

    private JedisPooled redisClient(int database) {
        return new JedisPooled(
                new HostAndPort(REDIS_HOST, REDIS_PORT),
                DefaultJedisClientConfig.builder()
                        .user(REDIS_USERNAME)
                        .password(REDIS_PASSWORD)
                        .database(database)
                        .build()
        );
    }
}
