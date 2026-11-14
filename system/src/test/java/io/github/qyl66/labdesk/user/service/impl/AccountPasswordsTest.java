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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPasswordsTest {

    private final AccountPasswords passwords = new AccountPasswords();

    @Test
    void storesDistinctSaltedHashesAndChecksThePassword() {
        String raw = "mock-password-only";
        String first = passwords.encode(raw);
        String second = passwords.encode(raw);

        assertThat(first).isNotEqualTo(second).doesNotContain(raw);
        assertThat(passwords.matches(raw, first)).isTrue();
        assertThat(passwords.matches("different-password", first)).isFalse();
    }

    @Test
    void rejectsUninitializedAndLegacyPlaintextPasswords() {
        assertThat(passwords.matches("admin", "admin")).isFalse();
        assertThat(passwords.matches("demo-password", "!uninitialized")).isFalse();
        assertThat(passwords.matches("demo-password", null)).isFalse();
        assertThatThrownBy(() -> passwords.encode("too-short")).isInstanceOf(ClientException.class);
    }
}
