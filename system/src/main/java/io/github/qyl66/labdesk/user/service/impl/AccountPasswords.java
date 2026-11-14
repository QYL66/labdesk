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

package io.github.qyl66.labdesk.user.service.impl;

import io.github.qyl66.labdesk.framework.exception.ClientException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
class AccountPasswords {

    private static final int MAX_BYTES = 72;
    private static final int ITERATIONS = 600_000;
    private static final int SALT_BYTES = 16;
    private static final String FORMAT = "pbkdf2-sha256-v1";
    private static final Pattern HASH = Pattern.compile(FORMAT + ":[a-f0-9]{32}:[a-f0-9]{64}");
    private static final HexFormat HEX = HexFormat.of();
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash = encode(UUID.randomUUID().toString());

    String encode(String raw) {
        if (raw == null || raw.length() < 12 || !withinLimit(raw)) {
            throw new ClientException("密码至少 12 个字符，且不得超过 72 个 UTF-8 字节");
        }
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        return FORMAT + ":" + HEX.formatHex(salt) + ":" + HEX.formatHex(derive(raw, salt));
    }

    boolean matches(String raw, String stored) {
        boolean validHash = isEncoded(stored);
        if (raw == null || !withinLimit(raw)) {
            return false;
        }
        String[] parts = (validHash ? stored : dummyHash).split(":");
        byte[] calculated = derive(raw, HEX.parseHex(parts[1]));
        boolean matched = MessageDigest.isEqual(calculated, HEX.parseHex(parts[2]));
        return validHash && matched;
    }

    boolean isEncoded(String stored) {
        return stored != null && HASH.matcher(stored).matches();
    }

    private boolean withinLimit(String raw) {
        return raw.length() <= MAX_BYTES && raw.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }

    private byte[] derive(String raw, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
