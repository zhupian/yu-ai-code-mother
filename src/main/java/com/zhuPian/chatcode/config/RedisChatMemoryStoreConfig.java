package com.zhuPian.chatcode.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 鎸佷箙鍖栧璇濊蹇嗛厤缃€? *
 * <p>杩欓噷娌℃湁缁х画鐩存帴浣跨敤 langchain4j-community-redis 鐨?{@code RedisChatMemoryStore.builder()}锛? * 鏄洜涓?1.1.0-beta7 鐗堟湰鐨?builder 涓嶆毚闇?database 閫夋嫨鑳藉姏锛涘悓鏃跺畠鍦ㄥ彧璁剧疆 password銆? * 涓嶈缃?user 鏃朵細璧版棤璁よ瘉 Jedis 鏋勯€犲櫒锛屽鏄撹Е鍙?Redis 鐨?{@code NOAUTH Authentication required}銆? * 鍥犳杩欓噷鐩存帴鐢?Jedis 鐨?{@link DefaultJedisClientConfig} 鏄惧紡浼犲叆璁よ瘉淇℃伅鍜?database锛屼繚璇侊細
 * 1. 璁块棶闇€瑕佸瘑鐮佺殑 Redis 鏃朵竴瀹氫細璁よ瘉锛? * 2. 瀵硅瘽璁板繂鍐欏叆閰嶇疆鎸囧畾鐨?Redis database锛岃€屼笉鏄粯璁?DB 0銆? */
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    private String host;

    private int port;

    /**
     * Redis ACL 鐢ㄦ埛鍚嶃€俁edis 6+ 榛樿鐢ㄦ埛閫氬父鏄?default锛涘鏋?Redis 鍙娇鐢ㄤ紶缁?requirepass锛?     * 鍙互鐣欑┖锛屾鏃?Jedis 浼氭寜浠呭瘑鐮佹ā寮忚璇併€?     */
    private String username;

    private String password;

    /**
     * Redis database 涓嬫爣銆傛敞鎰忚繖涓嶆槸 Redis 鏈嶅姟绔彛锛涚鍙ｄ粛鐒舵槸 6379銆?     */
    private int database;

    private long ttl;

    @Bean
    public ChatMemoryStore redisChatMemoryStore() {
        return new DatabaseAwareRedisChatMemoryStore(createRedisClient(), ttl);
    }

    /**
     * 鍒涘缓鏄庣‘鎼哄甫璁よ瘉淇℃伅鍜?database 鐨?Jedis 瀹㈡埛绔€?     *
     * <p>鎶婅閫昏緫鎷嗗嚭鏉ユ槸涓轰簡閬垮厤鍐嶆钀藉洖鈥滃彧浼?host/port 瀵艰嚧鏈璇佲€濈殑璺緞锛屼篃鏂逛究娴嬭瘯楠岃瘉
     * username銆乸assword銆乨atabase 鐨勯厤缃‘瀹炰細杩涘叆 Jedis 瀹㈡埛绔厤缃€?     */
    JedisPooled createRedisClient() {
        DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                .database(database);
        if (hasText(username)) {
            clientConfigBuilder.user(username);
        }
        if (hasText(password)) {
            clientConfigBuilder.password(password);
        }
        return new JedisPooled(new HostAndPort(host, port), clientConfigBuilder.build());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 鏀寔 Redis database 鐨?LangChain4j 鑱婂ぉ璁板繂瀹炵幇銆?     *
     * <p>瀹炵幇閫昏緫淇濇寔鍜屽畼鏂?RedisChatMemoryStore 涓€鑷达細key 浣跨敤 memoryId 鐨勫瓧绗︿覆褰㈠紡锛?     * value 浣跨敤 LangChain4j 鑷甫 JSON 搴忓垪鍖栨牸寮忥紝閬垮厤鐮村潖宸叉湁 {@link ChatMemoryStore}
     * 涓?{@code MessageWindowChatMemory} 鐨勪氦浜掑绾︺€?     */
    private record DatabaseAwareRedisChatMemoryStore(JedisPooled client, long ttl) implements ChatMemoryStore {

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            String messagesJson = client.get(toRedisKey(memoryId));
            if (messagesJson == null) {
                return new ArrayList<>();
            }
            return ChatMessageDeserializer.messagesFromJson(messagesJson);
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            String redisKey = toRedisKey(memoryId);
            String messagesJson = ChatMessageSerializer.messagesToJson(messages);
            if (ttl > 0) {
                client.setex(redisKey, ttl, messagesJson);
            } else {
                client.set(redisKey, messagesJson);
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            client.del(toRedisKey(memoryId));
        }

        private String toRedisKey(Object memoryId) {
            if (memoryId == null || memoryId.toString().isBlank()) {
                throw new IllegalArgumentException("memoryId cannot be null or empty");
            }
            return memoryId.toString();
        }
    }
}
