# Docknium

A comprehensive Java utility library for managing Selenium Grid with Docker containers, providing automated setup, teardown, and management of Selenium Hub and Node containers for parallel test execution.



## Features

- 🐳 **Docker-based Selenium Grid**: Automated Docker container management for Selenium Hub and Nodes
- 🌐 **Multi-browser Support**: Chrome, Chromium, Firefox, Safari, and Edge browsers
- 🎥 **Video Recording**: Optional test execution recording with automatic video file management
- 🔄 **Container Lifecycle Management**: Automatic cleanup of old containers, images, and networks
- 🖥️ **VNC Support**: Real-time test execution monitoring via VNC
- 🏗️ **Singleton Pattern**: Thread-safe singleton implementation for grid management
- 📱 **Cross-platform**: Support for Windows, macOS (including ARM processors), and Linux
- 🔧 **Configurable**: Highly customizable through SeleniumGridData configuration

## Prerequisites

- Java 8 or higher
- Docker installed and running
- TestNG framework
- Gradle build system

## Maven Dependency

```xml
<dependency>
    <groupId>io.github.aarahman7</groupId>
    <artifactId>docknium</artifactId>
    <version>0.0.1</version>
</dependency>
```

## Gradle Dependency

```gradle
implementation 'io.github.aarahman7:docknium:0.0.1'
```

## Quick Start

### 1. Basic Configuration

Create a `Docknium` configuration object:

```java
SeleniumGridData seleniumGridData = SeleniumGridData.builder()
    .browser(Browser.CHROME)
    .recordVideo(true)
    .headless(false)
    .colimaToBeLaunched(true) // For macOS users if you use colima for Docker locally
    .build();
```
Please refer here for more update on "Colima" - https://github.com/abiosoft/colima.

### 2. Test Class Setup

Extend your test class and follow the proper lifecycle management:

```java
public class MyTest extends BaseTest {
    
    @BeforeSuite
    public void beforeSuite() {
        // Initialize SeleniumGridUtil (Singleton)
        seleniumGridUtil = SeleniumGridUtil.init(seleniumGridData);
        
        // Clean up old containers/images/networks
        seleniumGridUtil.stopOldContainersImagesNetwork();
        
        // Launch the Selenium Grid Hub
        seleniumGridUtil.launchGrid();
    }
    
    @Test
    public void testExample() {
        // Launch browser node for this test
        seleniumGridUtil.launchNode();
        
        // Create WebDriver instance
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setBrowserName("chrome");
        driver = new RemoteWebDriver(seleniumGridUtil.getUrl(), caps);
        
        // Your test logic here
        driver.get("https://example.com");
        // ... test steps
    }
    
    @AfterMethod
    public void afterMethod() {
        // Clean up node container after each test
        seleniumGridUtil.stopAndRemoveNodeContainer();
    }
    
    @AfterSuite
    public void afterSuite() {
        // Stop and clean up the entire grid
        seleniumGridUtil.stopGridIfAvailable();
    }
}
```

## Method Usage Guidelines

### Critical Lifecycle Methods

#### 1. `SeleniumGridUtil.init()` - @BeforeSuite Level
```java
@BeforeSuite
public void beforeSuite() {
    seleniumGridUtil = SeleniumGridUtil.init(seleniumGridData);
}
```
- **When**: Must be called at `@BeforeSuite` level
- **Purpose**: Initializes the singleton instance of SeleniumGridUtil
- **Behavior**: Thread-safe singleton pattern ensures only one instance per test suite

#### 2. `stopOldContainersImagesNetwork()` - @BeforeSuite or @BeforeClass
```java
seleniumGridUtil.stopOldContainersImagesNetwork();
```
- **When**: Call at `@BeforeSuite` or `@BeforeClass` level
- **Purpose**: Cleans up old Docker containers, dangling images, and networks
- **Configurable**: Behavior controlled by SeleniumGridData settings

#### 3. `launchGrid()` - @BeforeSuite or @BeforeClass
```java
seleniumGridUtil.launchGrid();
```
- **When**: Call after `stopOldContainersImagesNetwork()`
- **Purpose**: Creates Docker network and launches Selenium Hub container
- **Note**: Should be called only once per test suite

#### 4. `launchNode()` - @Test or @BeforeMethod Level
```java
@Test
public void myTest() {
    seleniumGridUtil.launchNode(); // or launchNode(Browser.FIREFOX)
    // ... test logic
}
```
- **When**: Call at `@Test` method or `@BeforeMethod` level
- **Purpose**: Launches browser-specific node container
- **Flexibility**: Can specify different browsers per test

#### 5. `stopAndRemoveNodeContainer()` - @AfterMethod Level
```java
@AfterMethod
public void afterMethod() {
    driver.quit();
    seleniumGridUtil.stopAndRemoveNodeContainer();
}
```
- **When**: Must be called at `@AfterMethod` level
- **Purpose**: Stops and removes node container, saves video files
- **Important**: Call after `driver.quit()`

#### 6. `stopGridIfAvailable()` - @AfterSuite Level
```java
@AfterSuite
public void afterSuite() {
    seleniumGridUtil.stopGridIfAvailable();
}
```
- **When**: Call at `@AfterSuite` level
- **Purpose**: Stops hub container and removes Docker network
- **Cleanup**: Final cleanup of all grid resources

## SeleniumGridData Configuration

The `SeleniumGridData` class provides comprehensive configuration options:

### Core Settings
```java
SeleniumGridData config = SeleniumGridData.builder()
    .browser(Browser.CHROME)           // Default browser type
    .headless(false)                   // Run in headless mode
    .recordVideo(true)                 // Enable video recording
    .build();
```

### Cleanup Settings
```java
.removeDanglingImages(true)                    // Remove unused Docker images
.removeContainersOlderThan24Hours(true)        // Clean old containers
.removeNetworkOlderThan24Hours(true)           // Clean old networks
```

### Platform-specific Settings
```java
.colimaToBeLaunched(true)              // Launch Colima on macOS
```

### File Paths
```java
.downloadFolderAbsolutePath("/path/to/downloads")  // Browser download folder
.videoFolderAbsolutePath("/path/to/videos")        // Video recording folder
.screenWidth("1920")                               // Screen resolution width
.screenHeight("1080")                              // Screen resolution height
```

### Available Browsers
- `Browser.CHROME` - Google Chrome
- `Browser.CHROMIUM` - Chromium browser
- `Browser.FIREFOX` - Mozilla Firefox
- `Browser.SAFARI` - Safari (macOS only)
- `Browser.EDGE` - Microsoft Edge

## Sample Test Implementation

The project includes comprehensive test examples in the `src/test` directory:

### BaseTest.java
- Provides common test infrastructure
- Implements proper lifecycle management
- Includes utility methods for web interactions
- Demonstrates configuration setup

### DockerTest.java
- Multi-browser testing examples
- Headless mode testing
- Video recording demonstration
- Login validation test case

## Advanced Features

### Video Recording
When `recordVideo(true)` is enabled:
- Videos are automatically recorded for each test
- Files are saved to the configured video folder
- Video files are named with VNC port for uniqueness
- Access current video info: `seleniumGridUtil.getCurrentVideoPath()`

### VNC Monitoring
- Real-time test execution monitoring
- Access VNC URL: `seleniumGridUtil.getCurrentVncUrl()`
- Default VNC port allocation for each node

### Container Management
- Automatic port allocation for hub and nodes
- Memory and shared memory configuration (2GB each)
- Restart policies for container resilience
- Network isolation for test execution

## Troubleshooting

### Common Issues

1. **Docker not running**: Ensure Docker daemon is running
2. **Port conflicts**: The utility automatically allocates available ports
3. **macOS ARM processors**: Use `colimaToBeLaunched(true)` for Apple Silicon
4. **Video recording issues**: Ensure video folder path exists and is writable

### Debug Information
- Hub URL: `seleniumGridUtil.getUrl()`
- VNC URL: `seleniumGridUtil.getCurrentVncUrl()`
- Video path: `seleniumGridUtil.getCurrentVideoPath()`

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Ensure all tests pass
5. Submit a pull request

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

### Why MIT License?
- ✅ **Commercial Use**: Companies can use this library in commercial products
- ✅ **Modification**: Anyone can modify and improve the code
- ✅ **Distribution**: Free to distribute and share
- ✅ **Private Use**: Can be used in private/internal projects
- ✅ **No Warranty**: No liability for the author

## Support

For issues and questions, please create an issue in the GitHub repository.
