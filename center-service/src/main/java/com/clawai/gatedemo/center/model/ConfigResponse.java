package com.clawai.gatedemo.center.model;

import com.clawai.gatedemo.center.config.CenterConfig;
import lombok.Data;

import java.util.List;

@Data
public class ConfigResponse {
    private VersionInfo version;
    private SdkInfo sdk;
    private LoginInfo login;
    private AnnouncementInfo announcement;

    public static ConfigResponse fromConfig(CenterConfig config) {
        ConfigResponse response = new ConfigResponse();
        
        VersionInfo versionInfo = new VersionInfo();
        versionInfo.setVersion(config.getVersion().getVersion());
        versionInfo.setMinVersion(config.getVersion().getMinVersion());
        versionInfo.setForceUpdate(config.getVersion().isForceUpdate());
        versionInfo.setUpdateUrl(config.getVersion().getUpdateUrl());
        response.setVersion(versionInfo);
        
        SdkInfo sdkInfo = new SdkInfo();
        sdkInfo.setHost(config.getSdk().getHost());
        sdkInfo.setPort(config.getSdk().getPort());
        response.setSdk(sdkInfo);
        
        LoginInfo loginInfo = new LoginInfo();
        loginInfo.setHost(config.getLogin().getHost());
        loginInfo.setPort(config.getLogin().getPort());
        response.setLogin(loginInfo);
        
        if (!config.getAnnouncements().isEmpty()) {
            CenterConfig.AnnouncementConfig first = config.getAnnouncements().get(0);
            AnnouncementInfo announcementInfo = new AnnouncementInfo();
            announcementInfo.setTitle(first.getTitle());
            announcementInfo.setContent(first.getContent());
            announcementInfo.setType(first.getType());
            response.setAnnouncement(announcementInfo);
        }
        
        return response;
    }

    @Data
    public static class VersionInfo {
        private String version;
        private String minVersion;
        private boolean forceUpdate;
        private String updateUrl;
    }

    @Data
    public static class SdkInfo {
        private String host;
        private int port;
    }

    @Data
    public static class LoginInfo {
        private String host;
        private int port;
    }

    @Data
    public static class AnnouncementInfo {
        private String title;
        private String content;
        private String type;
    }
}
