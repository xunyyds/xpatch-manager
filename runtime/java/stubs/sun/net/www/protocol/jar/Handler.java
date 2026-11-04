package sun.net.www.protocol.jar;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;

public class Handler extends java.net.URLStreamHandler {
protected URLConnection openConnection(URL url) throws IOException {
throw new UnsupportedOperationException();
}
}
