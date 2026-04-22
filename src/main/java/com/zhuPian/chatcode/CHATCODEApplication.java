package com.zhuPian.chatcode;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.zhuPian.chatcode.mapper")
public class CHATCODEApplication {

    public static void main(String[] args) {
        SpringApplication.run(CHATCODEApplication.class, args);
    }

}
