package org.codesentry.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CodeSentryApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeSentryApplication.class, args);
    }
}
