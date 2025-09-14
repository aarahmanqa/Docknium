package com.aarahman;

import org.openqa.selenium.By;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DockerTest extends BaseTest {

    public void launchNodeAndBrowser(Browser browser) {
        seleniumGridUtil.launchNode(browser);
        DesiredCapabilities desiredCapabilities = new DesiredCapabilities();
        desiredCapabilities.setBrowserName(seleniumGridData.getBrowser().toString().toLowerCase());
        driver = new RemoteWebDriver(seleniumGridUtil.getUrl(), desiredCapabilities);
        driver.manage().window().maximize();
        ((RemoteWebDriver)driver).setFileDetector(new LocalFileDetector());
        webDriverWait = new WebDriverWait(driver, timeout);
    }

    @Test
    public void testChrome() {
        launchNodeAndBrowser(Browser.CHROME);
        validateLogin();
    }

    @Test
    public void testFirefox() {
        launchNodeAndBrowser(Browser.FIREFOX);
        validateLogin();
    }

    @Test
    public void testChromium() {
        launchNodeAndBrowser(Browser.CHROMIUM);
        validateLogin();
    }

    @Test
    public void testHeadless() {
        try {
            seleniumGridData.setHeadless(true);
            launchNodeAndBrowser(Browser.CHROME);
            validateLogin();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        seleniumGridData.setHeadless(false);
    }

    public void validateLogin() {
        navigateToUrl("http://eaapp.somee.com/");
        click(By.id("loginLink"));
        sendKeys(By.id("UserName"), "admin");
        sendKeys(By.id("Password"), "password");
        click(By.id("loginIn"));
        Assert.assertNotNull(waitForVisibility(By.linkText("Hello admin!")));
    }
}
