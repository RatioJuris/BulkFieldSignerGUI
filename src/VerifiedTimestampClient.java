// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import com.itextpdf.signatures.ITSAClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponse;

/** HTTPS, bounded response, end-to-end deadline, nonce/imprint and TSA signature/path validation. */
public final class VerifiedTimestampClient implements ITSAClient {
    private final TimestampProvider provider;
    private final Set<TrustAnchor> anchors;

    public VerifiedTimestampClient(TimestampProvider provider, Set<TrustAnchor> anchors) {
        this.provider = provider;
        this.anchors = Set.copyOf(anchors);
    }

    @Override public int getTokenSizeEstimate() { return 16384; }
    @Override public MessageDigest getMessageDigest() throws java.security.NoSuchAlgorithmException { return MessageDigest.getInstance("SHA-256"); }

    @Override public byte[] getTimeStampToken(byte[] imprint) throws Exception {
        TimeStampRequestGenerator generator = new TimeStampRequestGenerator();
        generator.setCertReq(true);
        var request = generator.generate(NISTObjectIdentifiers.id_sha256, imprint, new BigInteger(128, new SecureRandom()));
        HttpRequest httpRequest = HttpRequest.newBuilder(provider.uri()).timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/timestamp-query")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.getEncoded())).build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var pending = client.sendAsync(httpRequest, info -> new LimitedBodySubscriber(1_048_576));
            HttpResponse<byte[]> response;
            try { response = pending.get(30, java.util.concurrent.TimeUnit.SECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            if (response.statusCode() != 200) throw new IOException("Timestamp server returned HTTP " + response.statusCode());
            TimeStampResponse tsResponse = new TimeStampResponse(response.body());
            tsResponse.validate(request);
            var token = tsResponse.getTimeStampToken();
            if (token == null) throw new IOException("Timestamp server rejected the request.");
            List<X509Certificate> certificates = new ArrayList<>();
            X509Certificate tsa = null;
            for (X509CertificateHolder holder : token.getCertificates().getMatches(null)) {
                X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(holder);
                certificates.add(certificate);
                if (token.getSID().match(holder)) tsa = certificate;
            }
            if (tsa == null) throw new IOException("Timestamp signer certificate is missing.");
            token.validate(new JcaSimpleSignerInfoVerifierBuilder().build(tsa));
            X509CertSelector selector = new X509CertSelector();
            selector.setCertificate(tsa);
            PKIXBuilderParameters parameters = new PKIXBuilderParameters(anchors, selector);
            parameters.setDate(token.getTimeStampInfo().getGenTime());
            parameters.setRevocationEnabled(false);
            parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(certificates)));
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return token.getEncoded();
        }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private int received;
        private Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            long size = buffers.stream().mapToLong(ByteBuffer::remaining).sum();
            if (size > limit - received) {
                subscription.cancel();
                delegate.onError(new IOException("Timestamp response exceeds 1 MiB."));
            } else { received += (int) size; delegate.onNext(buffers); }
        }
        @Override public void onError(Throwable failure) { delegate.onError(failure); }
        @Override public void onComplete() { delegate.onComplete(); }
    }
}
