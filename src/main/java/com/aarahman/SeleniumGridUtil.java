package com.aarahman;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.net.PortProber;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

import static com.aarahman.CommonUtil.*;

/**
 * SeleniumGridUtil is a utility class for managing Selenium Grid infrastructure using Docker containers.
 * This class provides functionality to launch, manage, and cleanup Selenium Hub and Node containers
 * along with video recording capabilities for test execution monitoring.
 *
 * <p>The class follows a singleton pattern and manages the complete lifecycle of a Selenium Grid:
 * <ul>
 *   <li>Docker client initialization and configuration</li>
 *   <li>Network creation and management</li>
 *   <li>Hub container creation and management</li>
 *   <li>Node container creation for different browsers</li>
 *   <li>Video recording container management</li>
 *   <li>Cleanup of containers, images, and networks</li>
 * </ul>
 *
 * <p>Usage pattern:
 * <pre>
 * SeleniumGridUtil gridUtil = SeleniumGridUtil.init(seleniumGridData);
 * gridUtil.launchGrid();
 * gridUtil.launchNode();
 * // ... test execution ...
 * gridUtil.stopAndRemoveNodeContainer();
 * gridUtil.stopGridIfAvailable();
 * </pre>
 *
 * @author Aarahman
 * @version 1.0
 */
@Slf4j
public class SeleniumGridUtil {

    // ========================================
    // CONSTANTS AND STATIC FIELDS
    // ========================================

    /** Docker image name for Selenium Hub */
    private static final String SELENIUM_HUB_IMAGE_NAME = "selenium/hub:latest";

    /** Docker image name template for Selenium Node (browser placeholder will be replaced) */
    private static String SELENIUM_NODE_IMAGE_NAME = "selenium/node-<BROWSER>:latest";

    /** Docker image name for video recording container */
    private static final String VIDEO_IMAGE_NAME = "selenium/video:latest";

    /** Container name prefix for video recording containers */
    private static final String VIDEO_CONTAINER_NAME = "video";

    /** Default download path inside the Selenium node container */
    public static final String DOWNLOAD_PATH = "/home/seluser/Downloads";

    /** Base network name for Docker network (will be suffixed with hub port) */
    private static String networkName = "AarahmanGrid";

    // ========================================
    // INSTANCE AND STATIC VARIABLES
    // ========================================

    /** Singleton instance of SeleniumGridUtil */
    private static SeleniumGridUtil seleniumGridUtil;

    /** Docker client instance for container operations */
    private DockerClient dockerClient;

    /** Configuration data for Selenium Grid setup */
    @Getter
    private final SeleniumGridData seleniumGridData;

    /** Flag indicating if tests should run in headless mode */
    private boolean headless;

    // ========================================
    // STATIC CONTAINER AND NETWORK TRACKING
    // ========================================

    /** Name of the hub container */
    private static String HUB_NAME;

    /** Container ID of the hub (static to ensure single hub per suite) */
    private static String hubContainerId;

    /** Port number for the Selenium Hub */
    @Getter
    private static Integer hubPort;

    /** Port number for event bus publishing */
    private static Integer eventBusPublishPort;

    /** Port number for event bus subscription */
    private static Integer eventBusSubscribePort;

    // ========================================
    // THREAD-LOCAL VARIABLES FOR NODE MANAGEMENT
    // ========================================

    /** Thread-local storage for node container IDs (allows multiple nodes per thread) */
    private static final ThreadLocal<String> nodeContainerId = new ThreadLocal<>();

    /** Thread-local storage for video container IDs */
    private static final ThreadLocal<String> videoContainerId = new ThreadLocal<>();

    /** Thread-local storage for VNC port numbers */
    private static final ThreadLocal<Integer> currentVncPort = new ThreadLocal<>();


    // ========================================
    // PUBLIC API METHODS - INITIALIZATION
    // ========================================

    /**
     * Initializes and returns a singleton instance of SeleniumGridUtil.
     * This method ensures only one instance exists per JVM, making it safe for concurrent access.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Creates Docker client based on operating system (Windows uses named pipes, others use default)</li>
     *   <li>Configures headless mode and disables video recording if headless is enabled</li>
     *   <li>Launches Colima if specified in configuration (macOS/Linux only)</li>
     * </ul>
     *
     * @param seleniumGridData Configuration object containing grid setup parameters
     * @return Singleton instance of SeleniumGridUtil
     * @throws RuntimeException if Docker client initialization fails
     */
    public static synchronized SeleniumGridUtil init(SeleniumGridData seleniumGridData) {
        if(seleniumGridUtil == null) {
            seleniumGridUtil = new SeleniumGridUtil(seleniumGridData);
        }
        return seleniumGridUtil;
    }

    // ========================================
    // PUBLIC API METHODS - GRID LIFECYCLE
    // ========================================

    /**
     * Launches the Selenium Grid infrastructure including hub container and network.
     * This method should be called before launching any nodes.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Initializes available ports for hub and event bus communication</li>
     *   <li>Performs cleanup of old containers and networks if configured</li>
     *   <li>Creates Docker network for grid communication</li>
     *   <li>Pulls and creates hub container with proper port bindings</li>
     *   <li>Skips hub creation if already launched (supports multiple node launches)</li>
     * </ul>
     *
     * @throws RuntimeException if port initialization fails or Docker operations fail
     */
    public void launchGrid() {
        initPorts();
        if(seleniumGridData.isRemoveContainersOlderThan24Hours()) {
            removeContainersOlderThan24Hours();
        }
        if(seleniumGridData.isRemoveNetworkOlderThan24Hours()) {
            stopOldContainersImagesNetwork();
        }
        if (hubContainerId == null) { //There could be multiple nodes getting launched - Chrome node, Firefox node etc. But a suite will have only one hub and one network. Once hub is launched, it shouldn't be launched again.
            createNetwork();
            pullAndCreateHubContainer();
        }
    }

    /**
     * Launches a Selenium node container using the default browser from configuration.
     * This is a convenience method that calls launchNode(Browser) with the configured browser.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses browser specified in seleniumGridData configuration</li>
     *   <li>Automatically launches grid if not already running</li>
     *   <li>Creates video recording container if enabled in configuration</li>
     * </ul>
     *
     * @see #launchNode(Browser)
     */
    public void launchNode() {
        launchNode(seleniumGridData.getBrowser());
    }

    /**
     * Launches a Selenium node container for the specified browser.
     * Each node runs in its own container with dedicated VNC access and optional video recording.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Automatically launches grid infrastructure if not already running</li>
     *   <li>Pulls appropriate browser-specific Docker image</li>
     *   <li>Creates node container with proper environment variables and port bindings</li>
     *   <li>Configures VNC access for remote debugging</li>
     *   <li>Sets up volume binding for file downloads</li>
     *   <li>Launches video recording container if enabled</li>
     *   <li>Provides VNC URL for monitoring test execution</li>
     * </ul>
     *
     * @param browser The browser type for which to launch the node (CHROME, FIREFOX, EDGE, etc.)
     * @throws RuntimeException if Docker operations fail or browser is unsupported
     */
    public void launchNode(Browser browser) {
        if(hubPort == null) {
            launchGrid();
        }
        pullAndCreateNodeContainer(browser);
        if(seleniumGridData.isRecordVideo()) {
            pullAndCreateVideoContainer();
        }
    }

    private void initialiseDockerClient() {
        if(isWindows()) {
            // Configure to use Windows named pipes
            DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                    .withDockerHost("npipe:////./pipe/docker_engine")
                    // This tells Docker to use Linux containers
                    .withDockerTlsVerify(false)
                    .build();

            // Use Apache HTTP client which supports named pipes on Windows
            DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                    .dockerHost(config.getDockerHost())
                    .build();

            dockerClient = DockerClientImpl.getInstance(config, httpClient);
        } else {
            dockerClient = DockerClientBuilder.getInstance().build();
        }
    }


    // ========================================
    // PUBLIC API METHODS - CLEANUP
    // ========================================

    /**
     * Stops and cleans up the entire Selenium Grid infrastructure.
     * This method should be called at the end of test suite execution to ensure proper cleanup.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Checks if grid was actually launched before attempting cleanup</li>
     *   <li>Performs comprehensive cleanup of containers, images, and networks</li>
     *   <li>Removes the Docker network created for grid communication</li>
     *   <li>Gracefully handles cases where no grid was launched</li>
     * </ul>
     *
     * <p>This method is typically called from @AfterSuite methods in test frameworks.
     */
    public void stopGridIfAvailable() {
        if (hubContainerId == null) {
            log.info("No Grid launched for this suite. Skipping stopGridIfAvailable()");
            return;
        }
        // First stop all containers, then remove network
        stopOldContainersImagesNetwork();
        removeNetwork();
    }

    /**
     * Performs cleanup of old containers, dangling images, and networks based on configuration.
     * This method helps maintain a clean Docker environment by removing unused resources.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Removes containers older than 24 hours if configured</li>
     *   <li>Removes dangling Docker images if configured</li>
     *   <li>Removes networks older than 24 hours if configured</li>
     *   <li>Each cleanup operation is conditional based on seleniumGridData settings</li>
     * </ul>
     *
     * <p>This method can be called independently or as part of grid lifecycle management.
     */
    public void stopOldContainersImagesNetwork() {
        if (seleniumGridData.isRemoveContainersOlderThan24Hours()) {
            removeContainersOlderThan24Hours();
        }
        if (seleniumGridData.isRemoveDanglingImages()) {
            removeDanglingImages();
        }
        if (seleniumGridData.isRemoveNetworkOlderThan24Hours()) {
            removeNetworksOlderThan24Hours();
        }
    }

    /**
     * Stops and removes the current thread's node container.
     * This method should be called after each test method to ensure proper cleanup.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses thread-local storage to identify the correct node container</li>
     *   <li>Gracefully stops the container before removal</li>
     *   <li>Provides feedback about video file location if video recording was enabled</li>
     *   <li>Handles cases where no node container exists for the current thread</li>
     *   <li>Includes error handling for cleanup failures</li>
     * </ul>
     *
     * <p>This method is typically called from @AfterMethod in test frameworks.
     */
    public void stopAndRemoveNodeContainer() {
        try {
            if (nodeContainerId.get() == null) {
                return;
            }
            log.info("Stopping and removing node container: {}", nodeContainerId.get());
            dockerClient.stopContainerCmd(nodeContainerId.get()).exec();
            dockerClient.removeContainerCmd(nodeContainerId.get()).exec();
            File videoFolder = new File(seleniumGridData.getVideoFolderAbsolutePath());
            if (videoFolder.exists() && Objects.requireNonNull(videoFolder.listFiles()).length > 0) {
                log.info("Video files available in: {}", videoFolder.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Unable to stop and remove node container: {} due to: {}", nodeContainerId.get(), e.getMessage());
        }
    }


    // ========================================
    // PUBLIC API METHODS - UTILITY
    // ========================================

    /**
     * Returns the current VNC URL for accessing the running node container.
     * VNC provides a graphical interface to monitor test execution in real-time.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses thread-local VNC port to ensure correct URL per thread</li>
     *   <li>Returns localhost URL accessible from the host machine</li>
     *   <li>VNC access is configured without password for convenience</li>
     * </ul>
     *
     * @return VNC URL in format "http://localhost:PORT"
     * @throws RuntimeException if no VNC port is available for current thread
     */
    public String getCurrentVncUrl() {
        return "http://localhost:" + currentVncPort.get();
    }

    /**
     * Returns the filename of the current video recording.
     * The filename includes the VNC port to ensure uniqueness across multiple nodes.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Combines video container name with VNC port for uniqueness</li>
     *   <li>Uses .mp4 format for broad compatibility</li>
     *   <li>Thread-safe through use of thread-local VNC port</li>
     * </ul>
     *
     * @return Video filename in format "video_PORT.mp4"
     */
    public String getCurrentVideoFileName() {
        return VIDEO_CONTAINER_NAME + "_" + currentVncPort.get() + ".mp4";
    }

    /**
     * Returns the complete path to the current video recording file.
     * This path points to the actual file location on the host filesystem.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Combines configured video folder path with generated filename</li>
     *   <li>Returns Path object for easy file system operations</li>
     *   <li>Path is valid only after video recording has started</li>
     * </ul>
     *
     * @return Path object pointing to the video file location
     */
    public Path getCurrentVideoPath() {
        return Path.of(seleniumGridData.getVideoFolderAbsolutePath() + "/" + getCurrentVideoFileName());
    }

    /**
     * Returns the Selenium Grid hub URL for WebDriver connections.
     * This URL is used by WebDriver instances to connect to the grid.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses localhost and dynamically assigned hub port</li>
     *   <li>Returns standard Selenium Grid endpoint format</li>
     *   <li>URL is valid only after grid has been launched</li>
     * </ul>
     *
     * @return URL object pointing to the Selenium Grid hub
     * @throws RuntimeException if hub port is not initialized
     */
    @SneakyThrows
    public URL getUrl() {
        return new URL("http://localhost:" + getHubPort());
    }

    /**
     * Removes dangling Docker images to free up disk space.
     * Dangling images are those with no repository or tag (shown as &lt;none&gt;:&lt;none&gt;).
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Identifies images with dangling filter set to true</li>
     *   <li>Force removes each dangling image</li>
     *   <li>Provides detailed logging of removal operations</li>
     *   <li>Continues processing even if individual removals fail</li>
     *   <li>Reports total count of successfully removed images</li>
     * </ul>
     *
     * <p>This method is safe to call regularly as it only removes unused images.
     */
    public void removeDanglingImages() {
        int removedCount = 0;

        try {
            // Get only dangling images (images not tagged and not used)
            List<Image> danglingImages = dockerClient.listImagesCmd()
                    .withDanglingFilter(true)
                    .exec();

            // Remove dangling images
            for (Image image : danglingImages) {
                try {
                    String imageId = image.getId();
                    log.info("Removing dangling image: {} created at {}", imageId, convertEpochToZonedDateTime(image.getCreated()));
                    dockerClient.removeImageCmd(imageId).withForce(true).exec();
                    removedCount++;
                } catch (Exception e) {
                    log.warn("Could not remove dangling image: {}", e.getMessage());
                }
            }

            log.info("Successfully removed {} dangling images", removedCount);
        } catch (Exception e) {
            log.error("Error while removing dangling images: {}", e.getMessage(), e);
        }
    }


    // ========================================
    // PRIVATE METHODS - CONSTRUCTOR AND INITIALIZATION
    // ========================================

    /**
     * Private constructor for singleton pattern implementation.
     * Initializes Docker client, configures headless mode, and launches Colima if needed.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Stores configuration data for later use</li>
     *   <li>Initializes Docker client based on operating system</li>
     *   <li>Configures headless mode and disables video recording if headless</li>
     *   <li>Launches Colima container runtime if specified in configuration</li>
     * </ul>
     *
     * @param seleniumGridData Configuration object containing all grid setup parameters
     */
    private SeleniumGridUtil(SeleniumGridData seleniumGridData) {
        this.seleniumGridData = seleniumGridData;
        initialiseDockerClient();
        this.headless = seleniumGridData.isHeadless();
        if(this.headless) {
            //If headless is expected. record video will be made as false.
            seleniumGridData.setRecordVideo(false);
        }
        if(seleniumGridData.isColimaToBeLaunched()) {
            launchColima();
        }
    }


    // ========================================
    // PRIVATE METHODS - NETWORK MANAGEMENT
    // ========================================

    /**
     * Creates a Docker network for Selenium Grid communication.
     * The network name includes the hub port to ensure uniqueness across multiple grid instances.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Appends hub port to base network name for uniqueness</li>
     *   <li>Uses 'nat' driver on Windows, 'bridge' driver on other platforms</li>
     *   <li>Provides isolated network environment for grid containers</li>
     *   <li>Handles network creation failures gracefully</li>
     * </ul>
     *
     * @throws RuntimeException if network creation fails
     */
    private void createNetwork() {
        try {
            networkName += "_" + hubPort;
            dockerClient.createNetworkCmd()
                    .withName(networkName)
                    .withDriver(isWindows() ? "nat" : "bridge")
                    .exec();
            log.info("Network for Grid is created: {}", networkName);
        } catch (Exception ex) {
            log.error("Failed to create network: {}", networkName, ex);
        }
    }

    /**
     * Removes the Docker network created for the Selenium Grid.
     * Ensures all containers are disconnected before network removal.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>First disconnects any remaining containers from the network</li>
     *   <li>Then removes the network itself</li>
     *   <li>Provides detailed logging of removal operations</li>
     *   <li>Handles removal failures gracefully with error reporting</li>
     * </ul>
     */
    private void removeNetwork() {
        try {
            log.info("Removing docker network: {}", networkName);
            // First try to disconnect any remaining containers from the network
            disconnectContainersFromNetwork(networkName);
            // Then remove the network
            dockerClient.removeNetworkCmd(networkName).exec();
            log.info("Successfully removed network: {}", networkName);
        } catch (Exception ex) {
            log.error("Failed to remove network: {}", networkName, ex);
        }
    }

    /**
     * Disconnects all containers from the specified Docker network.
     * This is a prerequisite for network removal to avoid conflicts.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Inspects network to find all connected containers</li>
     *   <li>Iterates through containers and disconnects each one</li>
     *   <li>Continues processing even if individual disconnections fail</li>
     *   <li>Provides detailed logging for troubleshooting</li>
     * </ul>
     *
     * @param networkName Name of the network from which to disconnect containers
     */
    private void disconnectContainersFromNetwork(String networkName) {
        try {
            // Get network details to find connected containers
            var network = dockerClient.inspectNetworkCmd().withNetworkId(networkName).exec();
            if (network.getContainers() != null) {
                network.getContainers().forEach((containerId, containerInfo) -> {
                    try {
                        log.info("Disconnecting container {} from network {}", containerId, networkName);
                        dockerClient.disconnectFromNetworkCmd()
                                .withNetworkId(networkName)
                                .withContainerId(containerId)
                                .exec();
                    } catch (Exception e) {
                        log.error("Failed to disconnect container {} from network: {}", containerId, networkName, e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Failed to inspect network for disconnection: {}", e.getMessage(), e);
        }
    }

    /**
     * Removes Docker networks older than 24 hours that match the grid naming pattern.
     * This helps maintain a clean Docker environment by removing unused networks.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Calculates 24-hour threshold timestamp</li>
     *   <li>Lists all Docker networks and filters by creation date</li>
     *   <li>Filters networks matching the grid naming pattern</li>
     *   <li>Removes each matching network individually</li>
     *   <li>Performs final network pruning to clean up unused networks</li>
     *   <li>Provides comprehensive logging of cleanup operations</li>
     * </ul>
     */
    private void removeNetworksOlderThan24Hours() {
        try {
            // Calculate timestamp for 24 hours ago
            long twentyFourHoursAgo = Instant.now().minusSeconds(24 * 60 * 60).getEpochSecond();

            // List all networks
            dockerClient.listNetworksCmd()
                    .exec()
                    .stream()
                    .filter(network -> {
                        // Filter networks created more than 24 hours ago
                        Date created = network.getCreated();
                        return created != null && created.toInstant().getEpochSecond() < twentyFourHoursAgo;
                    })
                    .filter(network -> contains(networkName + "_",network.getName()))
                    .forEach(network -> {
                        try {
                            log.info("Removing old network: {} (created on {})", network.getName(), network.getCreated());
                            dockerClient.removeNetworkCmd(network.getId()).exec();
                        } catch (Exception e) {
                            log.error("Failed to remove network: {}", network.getName(), e);
                        }
                    });

            // Finally, prune any remaining unused networks
            dockerClient.pruneCmd(PruneType.NETWORKS).exec();
            log.info("Network cleanup completed");
        } catch (Exception ex) {
            log.error("Error during network cleanup", ex);
        }
    }

    // ========================================
    // PRIVATE METHODS - PORT AND SYSTEM MANAGEMENT
    // ========================================

    /**
     * Initializes available ports for Selenium Grid components.
     * Finds three consecutive available ports for hub and event bus communication.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses PortProber to find free ports on the system</li>
     *   <li>Assigns separate ports for hub, event bus publish, and event bus subscribe</li>
     *   <li>Provides detailed logging of assigned ports</li>
     *   <li>Terminates application if port initialization fails</li>
     * </ul>
     *
     * @throws RuntimeException if no available ports can be found
     */
    private void initPorts() {
        try {
            hubPort = getNextAvailablePort();
            eventBusPublishPort = getNextAvailablePort();
            eventBusSubscribePort = getNextAvailablePort();
            log.info("Hub Port: {}", hubPort);
            log.info("Event Bus Publish Port: {}", eventBusPublishPort);
            log.info("Event Bus Subscribe Port: {}", eventBusSubscribePort);
        } catch (Exception ex) {
            log.error("Unable to initialise Docker ports. Unable to proceed");
            System.exit(1);
        }
    }

    /**
     * Launches Colima container runtime on macOS/Linux systems.
     * Colima provides Docker-compatible container runtime for non-Docker Desktop environments.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Skips Colima launch on Windows systems</li>
     *   <li>Executes 'colima start' command on Unix-like systems</li>
     *   <li>Provides appropriate messaging for different platforms</li>
     * </ul>
     *
     * <p>Note: This method assumes Colima is already installed on the system.
     */
    private void launchColima() {
        if (isWindows()) {
            log.info("No colima need to be launched for Windows");
        } else {
            executeCommand("colima start");
        }
    }

    /**
     * Finds the next available port on the system.
     * Uses Selenium's PortProber utility for reliable port detection.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Delegates to PortProber.findFreePort() for cross-platform compatibility</li>
     *   <li>Returns a port number that is currently available</li>
     *   <li>Port availability is checked at the time of call</li>
     * </ul>
     *
     * @return An available port number
     */
    private static int getNextAvailablePort() {
        return PortProber.findFreePort();
    }


    // ========================================
    // PRIVATE METHODS - CONTAINER CLEANUP
    // ========================================

    /**
     * Removes Docker containers older than 24 hours that are related to Selenium.
     * This helps maintain a clean Docker environment by removing stale test containers.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Calculates 24-hour threshold timestamp</li>
     *   <li>Lists all containers including stopped ones</li>
     *   <li>Filters containers by age and Selenium-related image names</li>
     *   <li>Force removes each matching container</li>
     *   <li>Provides error handling for individual container removal failures</li>
     * </ul>
     */
    private void removeContainersOlderThan24Hours() {
        try {
            // Calculate timestamp for 24 hours ago
            long twentyFourHoursAgo = Instant.now().minusSeconds(24 * 60 * 60).getEpochSecond();

            // List all containers
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)  // equivalent to docker ps -a
                    .exec();

            // Filter and remove containers that are:
            // 1. Older than 24 hours AND
            // 2. Related to Selenium (hub or node)
            containers.stream()
                    .filter(container -> container.getCreated() < twentyFourHoursAgo)
                    .filter(this::isSeleniumContainer)
                    .forEach(this::forceRemoveContainer);
        } catch (Exception ex) {
            log.error("Error during container cleanup", ex);
        }
    }

    /**
     * Checks if a container is related to Selenium Grid infrastructure.
     * Uses image name pattern matching to identify Selenium containers.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Extracts image name from container metadata</li>
     *   <li>Performs case-insensitive search for 'selenium' in image name</li>
     *   <li>Returns false for null or empty image names</li>
     * </ul>
     *
     * @param container The container to check
     * @return true if the container uses a Selenium-related Docker image
     */
    private boolean isSeleniumContainer(Container container) {
        // Get the image name from the container
        String imageId = container.getImage();

        // Check if it's any Selenium-related container
        return imageId != null && imageId.toLowerCase().contains("selenium");
    }

    /**
     * Force removes a Docker container, handling both running and stopped containers.
     * Uses force removal to ensure containers are removed even if they're running.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses force flag to remove running containers without stopping first</li>
     *   <li>Provides detailed logging of removal operations</li>
     *   <li>Handles individual container removal failures gracefully</li>
     *   <li>Continues processing other containers even if one fails</li>
     * </ul>
     *
     * @param container The container to remove
     */
    private void forceRemoveContainer(Container container) {
        try {
            dockerClient.removeContainerCmd(container.getId())
                    .withForce(true)  // Added force removal
                    .exec();
            log.info("Removed container: {}", container.getId());
        } catch (Exception e) {
            log.error("Failed to remove container: {}", container.getId(), e);
        }
    }


    // ========================================
    // PRIVATE METHODS - CONTAINER CREATION
    // ========================================

    /**
     * Pulls the Selenium Hub Docker image and creates the hub container.
     * The hub serves as the central coordinator for all Selenium node connections.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Generates unique hub name using the assigned port number</li>
     *   <li>Pulls latest Selenium Hub image from Docker registry</li>
     *   <li>Configures port bindings for hub (4444) and event bus (4442, 4443)</li>
     *   <li>Allocates 2GB memory and shared memory for hub operations</li>
     *   <li>Sets restart policy to retry on failure up to 3 times</li>
     *   <li>Connects hub to the created Docker network</li>
     *   <li>Starts the container and stores container ID for later management</li>
     * </ul>
     *
     * @throws RuntimeException if image pull or container creation fails
     */
    @SneakyThrows
    private void pullAndCreateHubContainer() {
        try {
            HUB_NAME = "selenium-hub-" + hubPort;

            //Pulling the docker image before creating container
            log.info("Pulling Hub image: {}", SELENIUM_HUB_IMAGE_NAME);
            dockerClient.pullImageCmd(SELENIUM_HUB_IMAGE_NAME)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();

            // Port bindings for the hub
            Ports portBindings = new Ports();
            portBindings.bind(ExposedPort.tcp(4444),
                    Ports.Binding.bindPort(hubPort));
            portBindings.bind(ExposedPort.tcp(4442),
                    Ports.Binding.bindPort(eventBusPublishPort));
            portBindings.bind(ExposedPort.tcp(4443),
                    Ports.Binding.bindPort(eventBusSubscribePort));

            // Create hub container
            log.info("Starting Hub container...");
            CreateContainerResponse hubContainer = dockerClient
                    .createContainerCmd(SELENIUM_HUB_IMAGE_NAME)
                    .withName(HUB_NAME)
                    .withExposedPorts(
                            ExposedPort.tcp(4444),
                            ExposedPort.tcp(4442),
                            ExposedPort.tcp(4443)
                    )
                    .withHostConfig(HostConfig.newHostConfig()
                            .withMemory(2147483648L)
                            .withShmSize(2147483648L)
                            .withRestartPolicy(RestartPolicy.onFailureRestart(3))
                            .withPortBindings(portBindings)
                            .withNetworkMode(networkName))
                    .exec();

            hubContainerId = hubContainer.getId();
            dockerClient.startContainerCmd(hubContainerId).exec();
            log.info("Hub container started successfully with ID: {}", hubContainerId);
        } catch (Exception ex) {
            log.error("Failed to create and start hub container", ex);
        }
    }

    /**
     * Pulls the appropriate Selenium Node Docker image and creates a node container for the specified browser.
     * Each node container provides an isolated browser environment with VNC access and download capabilities.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Assigns unique VNC port for remote access to the browser session</li>
     *   <li>Determines correct browser image name based on browser type and processor architecture</li>
     *   <li>Pulls browser-specific Selenium node image from Docker registry</li>
     *   <li>Allocates 2GB memory and shared memory for browser operations</li>
     *   <li>Configures environment variables for grid communication and display settings</li>
     *   <li>Sets up VNC port binding for remote browser access</li>
     *   <li>Creates volume binding for file downloads between container and host</li>
     *   <li>Links node container to hub for grid communication</li>
     *   <li>Provides VNC URL for monitoring test execution</li>
     * </ul>
     *
     * @param browser The browser type for which to create the node container
     * @throws RuntimeException if image pull or container creation fails
     */
    private void pullAndCreateNodeContainer(Browser browser) {
        try {
            //Pulling the docker image before creating container
            log.info("Pulling Node image for browser: {}", browser);
            currentVncPort.set(getNextAvailablePort());
            String browserName = getBrowserName(browser);
            SELENIUM_NODE_IMAGE_NAME = SELENIUM_NODE_IMAGE_NAME.replace("<BROWSER>", browserName);

            dockerClient.pullImageCmd(SELENIUM_NODE_IMAGE_NAME)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();

            //Allotting 2 GB for each node.
            Long memoryAndShmSize = 2L * 1024 * 1024 * 1024;

            // Environment variables for the node
            Map<String, String> environmentVariables = getEnvironmentVariablesOfANode();

            // Port bindings for the node
            Ports nodePortBindings = new Ports();
            nodePortBindings.bind(ExposedPort.tcp(7900),
                    Ports.Binding.bindPort(currentVncPort.get()));

            // Create node container
            log.info("Starting Node container for browser: {}", browserName);

            String uniqueNodeName = getUniqueNodeName();

            CreateContainerResponse nodeContainer = dockerClient
                    .createContainerCmd(SELENIUM_NODE_IMAGE_NAME)
                    .withName(uniqueNodeName)
                    .withExposedPorts(ExposedPort.tcp(7900))
                    .withEnv(environmentVariables.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .toArray(String[]::new))
                    .withHostConfig(HostConfig.newHostConfig()
                            .withPortBindings(nodePortBindings)
                            .withLinks(new Link(HUB_NAME, "selenium-hub"))
                            .withRestartPolicy(RestartPolicy.onFailureRestart(3))
                            .withMemory(memoryAndShmSize) // 2GB / 4GB
                            .withShmSize(memoryAndShmSize)// 2GB / 4GB shm_size
                            .withNetworkMode(networkName)
                            .withBinds(new Bind(seleniumGridData.getDownloadFolderAbsolutePath(), new Volume(DOWNLOAD_PATH))))
                    .exec();

            nodeContainerId.set(nodeContainer.getId());
            dockerClient.startContainerCmd(nodeContainer.getId()).exec();
            String vncInfoMsg = "Please use " + getCurrentVncUrl() + " to check VNC";
            log.info("Node container started successfully. {}", vncInfoMsg);
        } catch (Exception ex) {
            log.error("Failed to create and start node container", ex);
        }
    }

    /**
     * Generates a unique name for the node container.
     * The name includes browser type and VNC port to ensure uniqueness across multiple nodes.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Combines "node" prefix with browser name and VNC port</li>
     *   <li>Uses current thread's browser configuration</li>
     *   <li>Ensures uniqueness through VNC port inclusion</li>
     *   <li>Format: "node-{browser}-{vncPort}"</li>
     * </ul>
     *
     * @return Unique container name for the node
     */
    private String getUniqueNodeName() {
        return "node-" + getBrowserName(null) + "-" + currentVncPort.get();
    }

    /**
     * Pulls the video recording Docker image and creates a video container for session recording.
     * The video container captures the browser session for later review and debugging.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Pulls latest Selenium video recording image from Docker registry</li>
     *   <li>Generates unique video container name using VNC port</li>
     *   <li>Creates volume binding between host video folder and container /videos directory</li>
     *   <li>Configures environment variables to link with specific node container</li>
     *   <li>Sets custom video filename including VNC port for uniqueness</li>
     *   <li>Connects video container to the same network as grid components</li>
     *   <li>Provides feedback about video storage location</li>
     * </ul>
     *
     * <p>The video container automatically starts recording when the linked node container begins browser sessions.
     *
     * @throws RuntimeException if image pull or container creation fails
     */
    private void pullAndCreateVideoContainer() {
        try {
            dockerClient.pullImageCmd(VIDEO_IMAGE_NAME)
                    .exec(new PullImageResultCallback())
                    .awaitCompletion();

            String currentVideoName = VIDEO_CONTAINER_NAME + "_" + currentVncPort.get();
            // Create a volume binding for /tmp/videos:/videos
            Volume videoVolume = new Volume("/videos");
            HostConfig hostConfig = HostConfig.newHostConfig()
                    .withNetworkMode(networkName)
                    .withBinds(new Bind(seleniumGridData.getVideoFolderAbsolutePath(), videoVolume))
                    .withRestartPolicy(RestartPolicy.onFailureRestart(3)); // Optional: adding similar restart policy as your node

            //Define environment variables:
            List<String> videoEnvVars = new ArrayList<>();
            videoEnvVars.add("DISPLAY_CONTAINER_NAME=" + getUniqueNodeName()); // Link to specific node
            videoEnvVars.add("FILE_NAME=" + currentVideoName + ".mp4"); // Set custom video filename

            // Create the container
            CreateContainerResponse videoContainer = dockerClient
                    .createContainerCmd(VIDEO_IMAGE_NAME)
                    .withName(currentVideoName)
                    .withEnv(videoEnvVars)
                    .withHostConfig(hostConfig)
                    .exec();

            videoContainerId.set(videoContainer.getId());
            // Start the container
            dockerClient.startContainerCmd(videoContainer.getId()).exec();
            log.info("Video recording started. Videos will be saved in: {}", seleniumGridData.getVideoFolderAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to create and start video container", e);
        }
    }


    // ========================================
    // PRIVATE METHODS - CONFIGURATION AND UTILITY
    // ========================================

    /**
     * Builds environment variables map for Selenium node container configuration.
     * These variables configure the node's connection to the hub and display settings.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Sets event bus host to hub container name for internal network communication</li>
     *   <li>Configures event bus ports for publish (4442) and subscribe (4443) operations</li>
     *   <li>Sets grid URL for node registration with the hub</li>
     *   <li>Enables VNC access without password for convenience</li>
     *   <li>Configures session timeout (600 seconds) and max sessions (1) per node</li>
     *   <li>Sets screen resolution based on configuration</li>
     *   <li>Disables XVFB for headless mode to improve performance</li>
     * </ul>
     *
     * @return Map of environment variable names to values for node container
     */
    private Map<String, String> getEnvironmentVariablesOfANode() {
        Map<String, String> environmentVariables = new HashMap<>();
        environmentVariables.put("SE_EVENT_BUS_HOST", HUB_NAME);
        environmentVariables.put("SE_EVENT_BUS_PUBLISH_PORT", "4442");
        environmentVariables.put("SE_EVENT_BUS_SUBSCRIBE_PORT", "4443");
        environmentVariables.put("SE_NODE_GRID_URL", "http://localhost:" + hubPort);
        environmentVariables.put("SE_VNC_NO_PASSWORD", "1");
        environmentVariables.put("SE_NODE_SESSION_TIMEOUT", "600");
        environmentVariables.put("SE_NODE_MAX_SESSIONS", "1");
        environmentVariables.put("SE_SCREEN_WIDTH", seleniumGridData.getScreenWidth());
        environmentVariables.put("SE_SCREEN_HEIGHT", seleniumGridData.getScreenHeight());

        if (headless) {
            environmentVariables.put("SE_START_XVFB", "false");
        }
        return environmentVariables;
    }

    /**
     * Stops and removes the Selenium Hub container.
     * This method is typically called during grid shutdown or cleanup operations.
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Checks if hub container exists before attempting operations</li>
     *   <li>Gracefully stops the container before removal</li>
     *   <li>Uses force removal to ensure container is deleted</li>
     *   <li>Provides logging of container operations</li>
     * </ul>
     *
     * <p>This method is safe to call multiple times as it checks for container existence.
     */
    public void stopAndRemoveHubContainer() {
        if (hubContainerId == null) {
            return;
        }
        log.info("Stopping and removing hub container: {}", hubContainerId);
        dockerClient.stopContainerCmd(hubContainerId).exec();
        dockerClient.removeContainerCmd(hubContainerId).withForce(true).exec();
    }

    /**
     * Converts browser enum to appropriate Docker image name.
     * Handles platform-specific browser selection (e.g., Chromium for ARM processors).
     *
     * <p>Implementation details:
     * <ul>
     *   <li>Uses configuration browser if parameter is null</li>
     *   <li>Maps CHROME to "chromium" on ARM processors, "chrome" on others</li>
     *   <li>Maps other browsers to their lowercase names</li>
     *   <li>Throws exception for unsupported browser types</li>
     * </ul>
     *
     * @param browser Browser enum value, or null to use configuration default
     * @return Docker image name suffix for the specified browser
     * @throws IllegalArgumentException if browser type is not supported
     */
    private String getBrowserName(Browser browser) {
        if (browser == null) {
            browser = seleniumGridData.getBrowser();
        }
        switch (browser) {
            case CHROME:
                return isArmProcessor() ? "chromium" : "chrome";
            case FIREFOX:
                return "firefox";
            case SAFARI:
                return "safari";
            case EDGE:
                return "edge";
            case CHROMIUM:
                return "chromium";
            default:
                throw new IllegalArgumentException("Unsupported browser: " + browser);
        }
    }
}
