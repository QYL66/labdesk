/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.qyl66.labdesk.ingestion.util;

// Modified for LabDesk.

import io.github.qyl66.labdesk.framework.exception.ServiceException;
import okhttp3.OkHttpClient;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HTTP 请求工具类，用于获取网络资源
 */
@Component
public class HttpClientHelper {

    private final OkHttpClient client;

    public HttpClientHelper(@Qualifier("syncHttpClient") OkHttpClient client) {
        this.client = client.newBuilder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(host -> {
                    var addresses = Dns.SYSTEM.lookup(host);
                    if (addresses.stream().anyMatch(HttpClientHelper::isNonPublicAddress)) {
                        throw new UnknownHostException("仅支持公开网络上的文档地址");
                    }
                    return addresses;
                })
                .build();
    }

    private static boolean isNonPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        return bytes.length == 4
                ? first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                : (first & 0xe0) != 0x20;
    }

    private static Request.Builder request(String url) {
        HttpUrl target = HttpUrl.parse(url);
        if (target == null || !target.username().isEmpty() || !target.password().isEmpty()) {
            throw new ServiceException("文档地址必须是公开的 HTTP(S) URL");
        }
        // OkHttp bypasses the custom DNS resolver when the URL already contains a literal IP address.
        String host = target.host();
        if (host.indexOf(':') >= 0 || host.codePoints().allMatch(ch -> ch == '.' || (ch >= '0' && ch <= '9'))) {
            try {
                if (isNonPublicAddress(InetAddress.getByName(host))) {
                    throw new ServiceException("仅支持公开网络上的文档地址");
                }
            } catch (UnknownHostException e) {
                throw new ServiceException("文档地址无效");
            }
        }
        return new Request.Builder().url(target);
    }

    public HttpFetchResponse get(String url, Map<String, String> headers) {
        return doGet(url, headers, -1);
    }

    public HttpFetchResponse getWithLimit(String url, Map<String, String> headers, long maxBytes) {
        return doGet(url, headers, maxBytes);
    }

    public HttpFetchStream openStream(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = request(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try {
            Response response = client.newCall(builder.get().build()).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new ServiceException("网络请求失败: " + response.code());
            }
            ResponseBody responseBody = response.body();
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                response.close();
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }
            InputStream bodyStream = responseBody == null
                    ? InputStream.nullInputStream()
                    : wrapWithLimit(responseBody.byteStream(), maxBytes);
            return new HttpFetchStream(response, bodyStream, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    private HttpFetchResponse doGet(String url, Map<String, String> headers, long maxBytes) {
        Request.Builder builder = request(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.get().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            if (maxBytes > 0 && contentLength != null && contentLength > maxBytes) {
                throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
            }

            byte[] bytes;
            if (response.body() == null) {
                bytes = new byte[0];
            } else if (maxBytes > 0) {
                bytes = readWithLimit(response.body().byteStream(), maxBytes);
            } else {
                bytes = response.body().bytes();
            }
            return new HttpFetchResponse(bytes, contentType, fileName, etag, lastModified, contentLength);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    public HttpHeadResponse head(String url, Map<String, String> headers) {
        Request.Builder builder = request(url);
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
        try (Response response = client.newCall(builder.head().build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("网络请求失败: " + response.code());
            }
            String contentType = response.header("Content-Type");
            String disposition = response.header("Content-Disposition");
            String fileName = resolveFileName(disposition, url);
            String etag = response.header("ETag");
            String lastModified = response.header("Last-Modified");
            Long contentLength = parseContentLength(response.header("Content-Length"));
            return new HttpHeadResponse(etag, lastModified, contentType, contentLength, fileName);
        } catch (IOException e) {
            throw new ServiceException("网络请求失败: " + e.getMessage());
        }
    }

    private String resolveFileName(String disposition, String url) {
        if (disposition != null) {
            String[] parts = disposition.split(";");
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.startsWith("filename=")) {
                    String raw = trimmed.substring("filename=".length()).trim();
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    return decode(raw);
                }
            }
        }
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            int idx = path.lastIndexOf('/');
            return idx >= 0 ? path.substring(idx + 1) : path;
        } catch (Exception e) {
            return null;
        }
    }

    private String decode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    private Long parseContentLength(String header) {
        if (header == null) {
            return null;
        }
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    private byte[] readWithLimit(InputStream inputStream, long maxBytes) throws IOException {
        try (InputStream in = inputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int len;
            while ((len = in.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
                out.write(buffer, 0, len);
            }
            return out.toByteArray();
        }
    }

    private InputStream wrapWithLimit(InputStream inputStream, long maxBytes) {
        if (maxBytes <= 0) {
            return inputStream;
        }
        return new InputStream() {
            private long total;

            @Override
            public int read() throws IOException {
                int value = inputStream.read();
                if (value != -1) {
                    ensureWithinLimit(1);
                }
                return value;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int count = inputStream.read(b, off, len);
                if (count > 0) {
                    ensureWithinLimit(count);
                }
                return count;
            }

            @Override
            public void close() throws IOException {
                inputStream.close();
            }

            private void ensureWithinLimit(int delta) {
                total += delta;
                if (total > maxBytes) {
                    throw new ServiceException("文件大小超过限制: " + maxBytes + " bytes");
                }
            }
        };
    }

    public record HttpFetchResponse(byte[] body,
                                    String contentType,
                                    String fileName,
                                    String etag,
                                    String lastModified,
                                    Long contentLength) {
    }

    public record HttpFetchStream(Response response,
                                  InputStream bodyStream,
                                  String contentType,
                                  String fileName,
                                  String etag,
                                  String lastModified,
                                  Long contentLength) implements AutoCloseable {

        @Override
        public void close() {
            response.close();
        }
    }

    public record HttpHeadResponse(String etag, String lastModified, String contentType, Long contentLength,
                                   String fileName) {
    }
}
