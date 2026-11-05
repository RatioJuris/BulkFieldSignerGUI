// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

/**
 * DSC USB tokens through the token vendor's PKCS#11 library (the standard on Linux and macOS, optional on
 * Windows). Certificates are listed without a PIN; the PIN is requested only when signing.
 */
public final class Pkcs11Token {
    static final Path FILE = AppPaths.CONFIG.resolve("token.properties");
    private static final Map<Path, Provider> PROVIDERS = new HashMap<>();

    private Pkcs11Token() { }

    /** The configured library, or null when no PKCS#11 token is configured. */
    public static Path configuredLibrary() throws IOException {
        if (!Files.exists(AppPaths.requireLocal(FILE))) return null;
        Properties values = new Properties();
        values.load(new ByteArrayInputStream(LocalFiles.readBounded(FILE, 4096)));
        String library = values.getProperty("library");
        return library == null || library.isBlank() ? null : validate(Path.of(library));
    }

    public static void configure(Path library) throws IOException {
        Properties values = new Properties();
        if (library != null) values.setProperty("library", validate(library).toString());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        values.store(bytes, "DSC token PKCS#11 library");
        LocalFiles.atomicWrite(FILE, bytes.toByteArray());
    }

    static Path validate(Path library) throws IOException {
        Path path = library.toAbsolutePath().normalize();
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".so") || name.contains(".so.") || name.endsWith(".dylib") || name.endsWith(".dll"))) {
            throw new IOException("Select the token's PKCS#11 library (.so, .dylib or .dll).");
        }
        // The value is written into a provider configuration; reject characters with syntactic meaning.
        if (path.toString().chars().anyMatch(c -> c == '"' || c == '\n' || c == '\r' || c == '\\' && Platform.os() != Platform.Os.WINDOWS)) {
            throw new IOException("The library path contains unsupported characters.");
        }
        if (!Files.isRegularFile(path)) throw new IOException("PKCS#11 library not found: " + path);
        return path;
    }

    static synchronized Provider provider(Path library) {
        return PROVIDERS.computeIfAbsent(library, path -> {
            Provider base = Security.getProvider("SunPKCS11");
            if (base == null) throw new IllegalStateException("PKCS#11 support is not available in this Java runtime.");
            String location = path.toString().replace('\\', '/');
            String config = "--name=DSCToken" + PROVIDERS.size() + "\nlibrary=\"" + location + "\"\nslotListIndex=0\n";
            Provider configured = base.configure(config);
            Security.addProvider(configured);
            return configured;
        });
    }

    /** Lists the token's personal signing certificates. No PIN is requested. */
    static RegistryContext read(Path library) throws Exception {
        Provider provider = provider(library);
        KeyStore store = KeyStore.getInstance("PKCS11", provider);
        store.load(null, null);
        return KeystoreMonitorThread.readRegistry(store, provider.getName(), true);
    }

    /** Logs in to the token, asking for the PIN, and returns a store that exposes private keys. */
    static KeyStore login(SignerIdentity identity) throws Exception {
        Provider provider = Security.getProvider(identity.provider());
        if (provider == null) throw new IOException("The DSC token is no longer available. Reconnect it and select the certificate again.");
        KeyStore.Builder builder = KeyStore.Builder.newInstance("PKCS11", provider,
                new KeyStore.CallbackHandlerProtection(callbacks -> askPin(callbacks, identity.displayName())));
        return builder.getKeyStore();
    }

    static PrivateKey privateKey(KeyStore loggedIn, String alias) throws Exception {
        if (!(loggedIn.getKey(alias, null) instanceof PrivateKey key)) throw new IOException("The private key was not found on the token.");
        return key;
    }

    static Certificate[] chain(KeyStore loggedIn, String alias) throws Exception {
        Certificate[] chain = loggedIn.getCertificateChain(alias);
        return chain != null ? chain : new Certificate[]{loggedIn.getCertificate(alias)};
    }

    private static void askPin(Callback[] callbacks, String certificate) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (!(callback instanceof PasswordCallback password)) throw new UnsupportedCallbackException(callback);
            char[][] entered = new char[1][];
            Runnable prompt = () -> {
                JPasswordField field = new JPasswordField(20);
                int answer = JOptionPane.showConfirmDialog(null, new Object[]{"Enter the token PIN for " + certificate + ":", field},
                        Product.NAME + " · DSC token PIN", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
                entered[0] = answer == JOptionPane.OK_OPTION ? field.getPassword() : null;
                field.setText("");
            };
            try {
                if (SwingUtilities.isEventDispatchThread()) prompt.run(); else SwingUtilities.invokeAndWait(prompt);
            } catch (Exception ex) { throw new IOException("PIN entry failed.", ex); }
            if (entered[0] == null) throw new IOException("PIN entry was cancelled.");
            password.setPassword(entered[0]);
            Arrays.fill(entered[0], '\0');
        }
    }
}
