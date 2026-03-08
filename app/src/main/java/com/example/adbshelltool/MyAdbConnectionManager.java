package com.example.adbshelltool;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// APIs internas de Android para construir certificados X.509.
// Package "android.sun.*" — son las APIs internas del JVM de Android,
// distintas del "sun.*" del JDK de escritorio. Existen en Android API 30+.
// Estos imports son IDENTICOS a los que usa la app de ejemplo oficial de libadb-android:
// github.com/MuntashirAkon/libadb-android/blob/master/app/src/main/java/.../AdbConnectionManager.java
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.Random;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * MyAdbConnectionManager — concrete implementation of the ADB connection manager.
 *
 * Why AbsAdbConnectionManager must be extended:
 * -----------------------------------------------
 * The ADB protocol authenticates clients with RSA public-key cryptography.
 * During pairing the daemon stores our public key as trusted.
 * On subsequent connections we present that key to authenticate without a PIN.
 *
 * Three abstract methods must be implemented:
 *   - getPrivateKey()  → RSA private key used to sign the authentication challenge
 *   - getCertificate() → X.509 certificate containing our public key
 *   - getDeviceName()  → name shown in the list of authorized devices
 *
 * Certificate generation:
 * ------------------------
 * Uses android.sun.security.x509.* — Android runtime internal APIs.
 * These are the same APIs used by the official libadb-android sample app.
 * Keys are stored in the app's private storage and reused across sessions,
 * so pairing only needs to happen once.
 */
public class MyAdbConnectionManager extends AbsAdbConnectionManager {

    // Singleton
    private static AbsAdbConnectionManager INSTANCE;

    private PrivateKey mPrivateKey;
    private Certificate mCertificate;

    /**
     * Returns the singleton instance.
     * Loads keys from disk if they exist, or generates new ones on the first run.
     */
    public static AbsAdbConnectionManager getInstance(@NonNull Context context) throws Exception {
        if (INSTANCE == null) {
            INSTANCE = new MyAdbConnectionManager(context.getApplicationContext());
        }
        return INSTANCE;
    }

    private MyAdbConnectionManager(@NonNull Context context) throws Exception {
        // Tell the base class which Android API level to use for protocol selection
        setApi(Build.VERSION.SDK_INT);

        // Try to load existing keys
        mPrivateKey = readPrivateKeyFromFile(context);
        mCertificate = readCertificateFromFile(context);

        if (mPrivateKey == null) {
            // First run: generate a new RSA 2048-bit key pair
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            // SHA1PRNG is the standard secure random generator on Android
            keyPairGenerator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            mPrivateKey = keyPair.getPrivate();

            // Build a self-signed X.509 certificate.
            // The ADB daemon uses this certificate to identify the client
            // during the TLS handshake in pairing and subsequent connections.
            String subject = "CN=AdbShellTool";
            String algorithmName = "SHA512withRSA";
            // Validity: 24 hours (the daemon renews trust on each pairing)
            long expiryDate = System.currentTimeMillis() + 86400000L;

            // Certificate extensions: subject key identifier + private key usage period
            CertificateExtensions extensions = new CertificateExtensions();
            extensions.set("SubjectKeyIdentifier",
                    new SubjectKeyIdentifierExtension(new KeyIdentifier(publicKey).getIdentifier()));
            Date notBefore = new Date();
            Date notAfter = new Date(expiryDate);
            extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));

            // Fill in the certificate fields
            X500Name x500Name = new X500Name(subject);
            X509CertInfo certInfo = new X509CertInfo();
            certInfo.set("version",      new CertificateVersion(2));
            certInfo.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
            certInfo.set("algorithmID",  new CertificateAlgorithmId(AlgorithmId.get(algorithmName)));
            certInfo.set("subject",      new CertificateSubjectName(x500Name));
            certInfo.set("key",          new CertificateX509Key(publicKey));
            certInfo.set("validity",     new CertificateValidity(notBefore, notAfter));
            certInfo.set("issuer",       new CertificateIssuerName(x500Name));
            certInfo.set("extensions",   extensions);

            // Sign the certificate with our own private key (self-signed)
            X509CertImpl certImpl = new X509CertImpl(certInfo);
            certImpl.sign(mPrivateKey, algorithmName);
            mCertificate = certImpl;

            // Persist to disk so pairing only needs to happen once
            writePrivateKeyToFile(context, mPrivateKey);
            writeCertificateToFile(context, mCertificate);
        }
    }

    // -----------------------------------------------------------------------
    // Abstract methods required by AbsAdbConnectionManager
    // -----------------------------------------------------------------------

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    /** Name shown in Settings > Wireless Debugging in the list of authorized devices. */
    @NonNull
    @Override
    protected String getDeviceName() {
        return "AdbShellTool";
    }

    // -----------------------------------------------------------------------
    // Reading and writing keys to the app's private storage
    // -----------------------------------------------------------------------

    /** Reads the X.509 certificate from cert.pem. Returns null if the file does not exist. */
    @Nullable
    private static Certificate readCertificateFromFile(@NonNull Context context)
            throws IOException, CertificateException {
        File certFile = new File(context.getFilesDir(), "cert.pem");
        if (!certFile.exists()) return null;
        try (InputStream is = new FileInputStream(certFile)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(is);
        }
    }

    /** Writes the certificate in PEM format (Base64 with BEGIN/END CERTIFICATE headers). */
    private static void writeCertificateToFile(@NonNull Context context, @NonNull Certificate cert)
            throws CertificateEncodingException, IOException {
        File certFile = new File(context.getFilesDir(), "cert.pem");
        BASE64Encoder encoder = new BASE64Encoder();
        try (OutputStream os = new FileOutputStream(certFile)) {
            // X509Factory.BEGIN_CERT = "-----BEGIN CERTIFICATE-----"
            os.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            os.write('\n');
            encoder.encode(cert.getEncoded(), os);
            os.write('\n');
            // X509Factory.END_CERT = "-----END CERTIFICATE-----"
            os.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Reads the RSA private key from private.key (PKCS8 DER). Returns null if file does not exist. */
    @Nullable
    private static PrivateKey readPrivateKeyFromFile(@NonNull Context context)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        File keyFile = new File(context.getFilesDir(), "private.key");
        if (!keyFile.exists()) return null;
        byte[] keyBytes = new byte[(int) keyFile.length()];
        try (InputStream is = new FileInputStream(keyFile)) {
            //noinspection ResultOfMethodCallIgnored
            is.read(keyBytes);
        }
        EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec);
    }

    /** Writes the private key in PKCS8 DER format (raw bytes). */
    private static void writePrivateKeyToFile(@NonNull Context context, @NonNull PrivateKey privateKey)
            throws IOException {
        File keyFile = new File(context.getFilesDir(), "private.key");
        try (OutputStream os = new FileOutputStream(keyFile)) {
            // getEncoded() returns the key in PKCS8 DER format
            os.write(privateKey.getEncoded());
        }
    }
}
