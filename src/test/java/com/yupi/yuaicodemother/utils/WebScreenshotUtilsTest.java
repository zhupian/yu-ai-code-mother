package com.yupi.yuaicodemother.utils;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.WebDriver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebScreenshotUtilsTest {

    @Test
    void shouldNotKeepStaticSharedWebDriverSession() {
        List<Field> staticWebDriverFields = Arrays.stream(WebScreenshotUtils.class.getDeclaredFields())
                .filter(field -> WebDriver.class.isAssignableFrom(field.getType()))
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .toList();

        assertTrue(staticWebDriverFields.isEmpty(),
                "截图工具不应持有静态共享 WebDriver，否则 Chrome 会话失效或并发截图时容易出现 invalid session id");
    }
}