package com.premisave.wallet.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates the SecurityCredential Safaricom requires for B2C, B2B,
 * TransactionStatus, and Account Balance requests: the initiator password,
 * RSA-encrypted with Safaricom's public certificate, then Base64-encoded.
 *
 * NOT the same as base64(plainPassword) — that will be rejected outright in
 * production and can behave inconsistently in sandbox.
 *
 * Sandbox and production certificates are DIFFERENT files — download both
 * from https://developer.safaricom.co.ke/test_credentials and
 * https://developer.safaricom.co.ke (production onboarding) and point
 * mpesa.daraja.certificate-path at the correct one per environment.
 *
 * Result is cached per (certPath, password) pair since it never changes
 * unless the initiator password itself is rotated.
 */
@Slf4j
@Service
public class MpesaSecurityCredentialService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String encrypt(String plainInitiatorPassword, String certificatePath) {
        if (plainInitiatorPassword == null || plainInitiatorPassword.isBlank()) {
            throw new IllegalStateException("M-Pesa initiator password is not configured");
        }
        if (certificatePath == null || certificatePath.isBlank()) {
            throw new IllegalStateException(
                    "mpesa.daraja.certificate-path is not configured — required to generate SecurityCredential");
        }

        String cacheKey = certificatePath + ":" + plainInitiatorPassword.hashCode();
        return cache.computeIfAbsent(cacheKey, k -> doEncrypt(plainInitiatorPassword, certificatePath));
    }

    private String doEncrypt(String plainPassword, String certificatePath) {
        try (InputStream is = new FileInputStream(certificatePath)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
            PublicKey publicKey = cert.getPublicKey();

            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(plainPassword.getBytes(StandardCharsets.UTF_8));

            String credential = Base64.getEncoder().encodeToString(encrypted);
            log.info("SecurityCredential generated from certificate: {}", certificatePath);
            return credential;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to generate M-Pesa SecurityCredential from certificate at " + certificatePath +
                    " — check the file exists, is a valid X.509 .cer, and matches your environment (sandbox vs production)", e);
        }
    }
}