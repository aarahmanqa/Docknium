package com.aarahman;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.annotations.*;

import java.time.Duration;
import java.util.Optional;

public class BaseTest {

    protected WebDriver driver;
    protected WebDriverWait webDriverWait;
    protected SeleniumGridData seleniumGridData = SeleniumGridData.builder()
            .browser(Browser.CHROME)
            .colimaToBeLaunched(true)
            .recordVideo(true)
            .build();

    protected SeleniumGridUtil seleniumGridUtil;

    protected Duration timeout = Duration.ofSeconds(30);


    @BeforeSuite
    public void beforeSuite() {
        seleniumGridUtil = SeleniumGridUtil.init(seleniumGridData);
        seleniumGridUtil.stopOldContainersImagesNetwork();
        seleniumGridUtil.launchGrid();
    }

    @AfterMethod
    public void afterMethod() {
        driver.quit();
        seleniumGridUtil.stopAndRemoveNodeContainer();
    }

    @AfterSuite
    public void afterSuite() {
        seleniumGridUtil.stopGridIfAvailable();
    }

    public void navigateToUrl(String url) {
        driver.get(url);
    }

    public WebElement waitForVisibility(By by) {
        try {
            return webDriverWait.until(ExpectedConditions.visibilityOfElementLocated(by));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public WebElement waitForClickable(By by) {
        try {
            return webDriverWait.until(ExpectedConditions.elementToBeClickable(by));
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public boolean click(By by) {
        try {
            waitForClickable(by).click();
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return false;
    }

    public boolean sendKeys(By by, String text) {
        try {
            waitForVisibility(by).sendKeys(text);
            return true;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return false;
    }

}
