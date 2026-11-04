package io.xpatch.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import sun.net.www.ParseUtil;
import sun.net.www.protocol.jar.Handler;

final class ClassPathURLStreamHandler extends Handler {
    private final String fileUri;
    private final JarFile jarFile;

    ClassPathURLStreamHandler(String str) throws IOException {
        this.jarFile = new JarFile(str);
        this.fileUri = new File(str).toURI().toString();
    }

    URL getEntryUrlOrNull(String str) {
        if (this.jarFile.getEntry(str) == null) {
            return null;
        }
        try {
            return new URL("jar", null, -1, this.fileUri + "!/" + ParseUtil.encodePath(str, false), this);
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid entry name", e);
        }
    }

    protected URLConnection openConnection(URL url) throws IOException {
        return new ClassPathURLConnection(url);
    }

    protected void finalize() throws IOException {
        this.jarFile.close();
    }

    private final class ClassPathURLConnection extends JarURLConnection {
        private boolean closed;
        private JarFile connectionJarFile;
        private ZipEntry jarEntry;
        private InputStream jarInput;

        private ClassPathURLConnection(URL url) throws MalformedURLException {
            super(url);
            this.connectionJarFile = null;
            this.jarEntry = null;
            this.jarInput = null;
            this.closed = false;
            setUseCaches(false);
        }

        @Override
        public void setUseCaches(boolean z) {
            super.setUseCaches(false);
        }

        @Override
        public void connect() throws IOException {
            if (this.closed) {
                throw new IllegalStateException("JarURLConnection has been closed");
            }
            if (this.connected) {
                return;
            }
            ZipEntry entry = ClassPathURLStreamHandler.this.jarFile.getEntry(getEntryName());
            this.jarEntry = entry;
            if (entry == null) {
                throw new FileNotFoundException("URL=" + this.url + ", zipfile=" + ClassPathURLStreamHandler.this.jarFile.getName());
            }
            this.connected = true;
        }

        @Override
        public JarFile getJarFile() throws IOException {
            connect();
            JarFile jarFile = this.connectionJarFile;
            if (jarFile != null) {
                return jarFile;
            }
            JarFile jarFile2 = new JarFile(ClassPathURLStreamHandler.this.jarFile.getName());
            this.connectionJarFile = jarFile2;
            return jarFile2;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            connect();
            InputStream inputStream = this.jarInput;
            if (inputStream != null) {
                return inputStream;
            }
            FilterInputStream filterInputStream = new FilterInputStream(ClassPathURLStreamHandler.this.jarFile.getInputStream(this.jarEntry)) {
                @Override
                public void close() throws IOException {
                    super.close();
                    ClassPathURLConnection.this.closed = true;
                    ClassPathURLStreamHandler.this.jarFile.close();
                    if (ClassPathURLConnection.this.connectionJarFile != null) {
                        ClassPathURLConnection.this.connectionJarFile.close();
                    }
                }
            };
            this.jarInput = filterInputStream;
            return filterInputStream;
        }

        @Override
        public String getContentType() {
            String strGuessContentTypeFromName = guessContentTypeFromName(getEntryName());
            return strGuessContentTypeFromName == null ? "content/unknown" : strGuessContentTypeFromName;
        }

        @Override
        public int getContentLength() {
            try {
                connect();
                return (int) getJarEntry().getSize();
            } catch (IOException unused) {
                return -1;
            }
        }
    }
}
