package com.yupi.yuaicodemother.utils;

import cn.hutool.core.img.ImgUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.exception.BusinessException;
import com.yupi.yuaicodemother.exception.ErrorCode;
import io.github.bonigarcia.wdm.WebDriverManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.time.Duration;
import java.util.UUID;

/**
 * 网页截图工具类。
 *
 * <p>ChromeDriver / WebDriver 会话不是线程安全的，也不适合在后台任务中长期静态复用。
 * 如果浏览器进程被系统回收、崩溃，或者被其他并发截图任务影响，继续复用旧 session 就可能触发
 * {@code NoSuchSessionException: invalid session id}。因此本工具每次截图都创建独立 driver，
 * 并在 finally 中关闭，保证单次截图的浏览器生命周期清晰、互不干扰。
 */
@Slf4j
public class WebScreenshotUtils {

    private static final int DEFAULT_WIDTH = 1600;

    private static final int DEFAULT_HEIGHT = 900;

    /**
     * 生成网页截图。
     *
     * @param webUrl 要截图的网页地址
     * @return 压缩后的截图文件路径；失败时返回 null
     */
    public static String saveWebPageScreenshot(String webUrl) {
        if (StrUtil.isBlank(webUrl)) {
            log.error("网页截图失败，url 为空");
            return null;
        }
        WebDriver webDriver = null;
        try {
            String rootPath = System.getProperty("user.dir") + "/tmp/screenshots/" + UUID.randomUUID().toString().substring(0, 8);
            FileUtil.mkdir(rootPath);

            final String imageSuffix = ".png";
            String imageSavePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + imageSuffix;

            // 每次截图使用独立 ChromeDriver，避免静态共享 session 失效或并发访问同一个 driver。
            webDriver = initChromeDriver(DEFAULT_WIDTH, DEFAULT_HEIGHT);
            webDriver.get(webUrl);
            waitForPageLoad(webDriver);

            byte[] screenshotBytes = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
            saveImage(screenshotBytes, imageSavePath);
            log.info("原始截图保存成功：{}", imageSavePath);

            final String compressSuffix = "_compressed.jpg";
            String compressedImagePath = rootPath + File.separator + RandomUtil.randomNumbers(5) + compressSuffix;
            compressImage(imageSavePath, compressedImagePath);
            log.info("压缩图片保存成功：{}", compressedImagePath);

            FileUtil.del(imageSavePath);
            return compressedImagePath;
        } catch (Exception e) {
            log.error("网页截图失败：{}", webUrl, e);
            return null;
        } finally {
            if (webDriver != null) {
                try {
                    webDriver.quit();
                } catch (Exception e) {
                    log.warn("关闭 ChromeDriver 会话失败，忽略并继续清理流程", e);
                }
            }
        }
    }

    /**
     * 初始化 Chrome 浏览器驱动。
     */
    private static WebDriver initChromeDriver(int width, int height) {
        try {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless");
            options.addArguments("--disable-gpu");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments(String.format("--window-size=%d,%d", width, height));
            options.addArguments("--disable-extensions");
            options.addArguments("--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

            WebDriver driver = new ChromeDriver(options);
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
            return driver;
        } catch (Exception e) {
            log.error("初始化 Chrome 浏览器失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "初始化 Chrome 浏览器失败");
        }
    }

    /**
     * 保存图片到文件。
     */
    private static void saveImage(byte[] imageBytes, String imagePath) {
        try {
            FileUtil.writeBytes(imageBytes, imagePath);
        } catch (Exception e) {
            log.error("保存图片失败：{}", imagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "保存图片失败");
        }
    }

    /**
     * 压缩图片。
     */
    private static void compressImage(String originImagePath, String compressedImagePath) {
        final float compressionQuality = 0.3f;
        try {
            ImgUtil.compress(
                    FileUtil.file(originImagePath),
                    FileUtil.file(compressedImagePath),
                    compressionQuality
            );
        } catch (Exception e) {
            log.error("压缩图片失败：{} -> {}", originImagePath, compressedImagePath, e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "压缩图片失败");
        }
    }

    /**
     * 等待页面加载完成。
     */
    private static void waitForPageLoad(WebDriver webDriver) {
        try {
            WebDriverWait wait = new WebDriverWait(webDriver, Duration.ofSeconds(10));
            wait.until(driver -> ((JavascriptExecutor) driver)
                    .executeScript("return document.readyState")
                    .equals("complete")
            );
            // 额外等待动态内容渲染，避免 document complete 后图片/脚本仍未完成首屏绘制。
            Thread.sleep(2000);
            log.info("页面加载完成");
        } catch (Exception e) {
            log.error("等待页面加载时出现异常，继续执行截图", e);
        }
    }
}