package com.payverse.user.service;

import com.payverse.common.web.provider.ProviderClients;
import com.payverse.common.web.provider.ProviderProperties.Provider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * KYC documents in Amazon S3 (or any S3-compatible store) with SSE-KMS encryption, signed with AWS Signature V4 —
 * no SDK, so STUB mode can answer through the same transport. {@code base-url} is the bucket endpoint
 * ({@code https://<bucket>.s3.<region>.amazonaws.com}); objects go to {@code kyc/<userId>/<uuid>}.
 */
@Component
@ConditionalOnProperty(name = "payverse.providers.document-storage.adapter", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {

    static final String PROVIDER = "document-storage";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final HexFormat HEX = HexFormat.of();

    private final RestClient http;
    private final Provider settings;
    private final Clock clock;

    public S3DocumentStorage(ProviderClients providers, Clock clock) {
        this.settings = providers.settings(PROVIDER);
        this.clock = clock;
        this.http = providers.client(PROVIDER, List.of("access-key-id", "secret-access-key", "region", "bucket"));
    }

    @Override
    public StoredDocument store(UUID userId, byte[] content, String contentType) {
        String key = "kyc/" + userId + "/" + UUID.randomUUID();
        String payloadHash = HEX.formatHex(sha256(content));
        String amzDate = AMZ_DATE.format(clock.instant());
        URI base = settings.baseUrl();
        String host = base.getPort() < 0 ? base.getHost() : base.getHost() + ":" + base.getPort();
        String authorization = authorization("PUT", "/" + key, host, contentType, payloadHash, amzDate);
        try {
            http.put().uri("/" + key)
                    .contentType(MediaType.parseMediaType(contentType))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", payloadHash)
                    .header("x-amz-server-side-encryption", "aws:kms")
                    .header("Authorization", authorization)
                    .body(content)
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new IllegalStateException("Document storage failed: " + e.getMessage(), e);
        }
        return new StoredDocument("s3://" + settings.credential("bucket") + "/" + key, payloadHash);
    }

    /** AWS Signature Version 4 for a single-chunk request with SSE-KMS. */
    String authorization(String method, String path, String host, String contentType, String payloadHash, String amzDate) {
        String date = amzDate.substring(0, 8);
        String region = settings.credential("region");
        String scope = date + "/" + region + "/s3/aws4_request";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-server-side-encryption";
        String canonicalRequest = method + "\n" + path + "\n\n"
                + "content-type:" + contentType + "\n" + "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n" + "x-amz-server-side-encryption:aws:kms\n\n"
                + signedHeaders + "\n" + payloadHash;
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + HEX.formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        String signature = HEX.formatHex(hmac(signingKey(settings.credential("secret-access-key"), date, region, "s3"), stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + settings.credential("access-key-id") + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    /** kSigning = HMAC(HMAC(HMAC(HMAC("AWS4" + secret, date), region), service), "aws4_request"). */
    static byte[] signingKey(String secret, String date, String region, String service) {
        byte[] key = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        return hmac(key, "aws4_request");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
