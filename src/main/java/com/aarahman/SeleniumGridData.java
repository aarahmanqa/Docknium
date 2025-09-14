package com.aarahman;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class SeleniumGridData {
    @Builder.Default
    private boolean recordVideo = false;

    @Builder.Default
    private Browser browser = Browser.CHROME;

    @Builder.Default
    private boolean headless = false;

    @Builder.Default
    private boolean removeDanglingImages = true;

    @Builder.Default
    private boolean removeContainersOlderThan24Hours = true;

    @Builder.Default
    private boolean removeNetworkOlderThan24Hours = true;

    @Builder.Default
    private boolean colimaToBeLaunched = false;

    @Builder.Default
    private String downloadFolderAbsolutePath = System.getProperty("user.dir") + "/downloads";

    @Builder.Default
    private String videoFolderAbsolutePath = System.getProperty("user.dir") + "/videos";

    @Builder.Default
    private String screenWidth = "1920";

    @Builder.Default
    private String screenHeight = "1080";
}
