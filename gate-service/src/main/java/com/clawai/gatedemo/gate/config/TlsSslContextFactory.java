package com.clawai.gatedemo.gate.config;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * TLS SslContext 工厂：支持 PEM (cert+key) 和 PKCS12 (keystore) 两种方式
 */
public class TlsSslContextFactory {

    private static final Logger logger = LoggerFactory.getLogger(TlsSslContextFactory.class);

    public static SslContext buildServerContext(GateConfig.TlsConfig tlsConfig) throws Exception {
        if (tlsConfig.getCertPath() != null && tlsConfig.getKeyPath() != null) {
            logger.info("Loading TLS certs from PEM: cert={}, key={}", tlsConfig.getCertPath(), tlsConfig.getKeyPath());
            return SslContextBuilder.forServer(
                    new File(tlsConfig.getCertPath()),
                    new File(tlsConfig.getKeyPath())
            ).build();
        }

        if (tlsConfig.getKeystorePath() != null && tlsConfig.getKeystorePassword() != null) {
            logger.info("Loading TLS certs from keystore: {}", tlsConfig.getKeystorePath());
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(tlsConfig.getKeystorePath())) {
                keyStore.load(fis, tlsConfig.getKeystorePassword().toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, tlsConfig.getKeystorePassword().toCharArray());
            return SslContextBuilder.forServer(kmf).build();
        }

        throw new IllegalStateException("TLS enabled but no cert-path/key-path or keystore-path configured");
    }
}
